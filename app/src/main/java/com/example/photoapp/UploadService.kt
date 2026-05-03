package com.example.photoapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import androidx.core.net.toUri
import okio.BufferedSink
import okio.source

class UploadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val NOTIFICATION_ID = 405
        private const val CHANNEL_ID = "UploadServiceChannel"
        private const val TAG = "UploadWorker"

        data class UploadState(val statusText: String, val isRunning: Boolean)
        private val _uploadState = MutableStateFlow(UploadState("Ready", false))
        val uploadState = _uploadState.asStateFlow()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(0, 0)
    }

    override suspend fun doWork(): Result {
        val serverUrl = inputData.getString("SERVER_URL") ?: return Result.failure()
        val authToken = inputData.getString("AUTH_TOKEN") ?: run {
            Log.e(TAG, "Missing AUTH_TOKEN")
            return Result.failure()
        }
        val folderUriString = inputData.getString("FOLDER_URI") ?: return Result.failure()
        val userEmail = inputData.getString("USER_EMAIL") ?: return Result.failure()

        _uploadState.value = UploadState("Checking sync status...", true)
        createNotificationChannel()
        
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set foreground service", e)
        }

        return withContext(Dispatchers.IO) {
            try {
                // 1. Get latest synced timestamp from server
                val listRequest = Request.Builder()
                    .url("$serverUrl/list-objects")
                    .addHeader("Authorization", "Bearer $authToken")
                    .build()

                val checkpointResponse = okHttpClient.newCall(listRequest).execute()
                if (!checkpointResponse.isSuccessful) {
                    checkpointResponse.close()
                    return@withContext Result.failure()
                }
                
                val body = checkpointResponse.body?.string() ?: ""
                checkpointResponse.close()
                
                if (body.isEmpty() || body == "{}") {
                    return@withContext Result.failure()
                }

                val json = JSONObject(body)
                val lastSyncedSeconds = json.optLong("date", -1L).let { 
                    if (it == -1L) json.optString("date", "0").toLongOrNull() ?: 0L else it
                }

                // CRITICAL SAFETY CHECKS
                if (lastSyncedSeconds <= 0L) {
                    Log.e(TAG, "Invalid checkpoint ($lastSyncedSeconds). Aborting.")
                    return@withContext Result.failure()
                }
                if (lastSyncedSeconds > 2524608000L) { // Year 2050
                    Log.e(TAG, "Checkpoint is in milliseconds ($lastSyncedSeconds). Aborting.")
                    return@withContext Result.failure()
                }

                // 2. Scan folder recursively for strictly newer files
                val treeUri = folderUriString.toUri()
                val allowedExtensions = setOf("jpg", "jpeg", "png", "mp4", "heic", "heif")
                
                val allFiles = mutableListOf<Triple<Uri, String, Long>>()
                findFilesRecursively(
                    applicationContext,
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                    lastSyncedSeconds,
                    allowedExtensions,
                    allFiles
                )

                // Sort by last modified ASC to upload in chronological order
                val filesToUpload = allFiles.sortedBy { it.third }

                if (filesToUpload.isEmpty()) {
                    _uploadState.value = UploadState("Up to date", false)
                    return@withContext Result.success()
                }

                // 3. Get Signed URL / Policy
                var signedUrlInfo = fetchSignedUrl(serverUrl, authToken) ?: return@withContext Result.failure()
                var uploadUrl = signedUrlInfo.getString("URL")
                var fields = signedUrlInfo.getJSONObject("Fields")

                // 4. Upload Files
                var successCount = 0
                val total = filesToUpload.size

                for (fileData in filesToUpload) {
                    if (isStopped) break
                    
                    if (isExpiringSoon(fields)) {
                        fetchSignedUrl(serverUrl, authToken)?.let {
                            signedUrlInfo = it
                            uploadUrl = signedUrlInfo.getString("URL")
                            fields = signedUrlInfo.getJSONObject("Fields")
                        }
                    }

                    val (uri, name, dt) = fileData
                    
                    val responseCode = performMultipartUpload(uploadUrl, fields, userEmail, uri, name, dt)
                    if (responseCode in 200..299) {
                        successCount++
                        _uploadState.value = UploadState("Uploaded $successCount / $total", true)
                        try {
                            setForeground(createForegroundInfo(successCount, total))
                        } catch (e: Exception) {
                            Log.e(TAG, "Update notification failed", e)
                        }
                    } else {
                        Log.e(TAG, "Failed to upload $name: $responseCode")
                    }
                }

                _uploadState.value = UploadState("Sync complete: $successCount files", false)
                Result.success()
            } catch (e: Exception) {
                _uploadState.value = UploadState("Error: ${e.message}", false)
                Result.failure()
            }
        }
    }

    private fun findFilesRecursively(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        lastSyncedSeconds: Long,
        allowedExtensions: Set<String>,
        result: MutableList<Triple<Uri, String, Long>>
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val modCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val docId = cursor.getString(idCol)
                val name = cursor.getString(nameCol) ?: "unknown"
                val mTime = cursor.getLong(modCol)
                val mime = cursor.getString(mimeCol)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    findFilesRecursively(context, treeUri, docId, lastSyncedSeconds, allowedExtensions, result)
                } else {
                    val mTimeSeconds = mTime / 1000
                    val ext = name.substringAfterLast('.', "").lowercase()
                    
                    if (ext in allowedExtensions) {
                        if (mTimeSeconds > lastSyncedSeconds) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            result.add(Triple(docUri, name, mTimeSeconds))
                        }
                    }
                }
            }
        }
    }

    private fun fetchSignedUrl(serverUrl: String, authToken: String): JSONObject? {
        val signedUrlRequest = Request.Builder()
            .url("$serverUrl/signed-url")
            .addHeader("Authorization", "Bearer $authToken")
            .build()

        return try {
            okHttpClient.newCall(signedUrlRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    JSONObject(response.body?.string() ?: "{}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch signed URL", e)
            null
        }
    }

    private fun isExpiringSoon(fields: JSONObject): Boolean {
        val xGoogDate = fields.optString("x-goog-date")
        if (xGoogDate.isEmpty()) return true

        return try {
            val format = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            val date = format.parse(xGoogDate) ?: return true
            val expirationTime = date.time + TimeUnit.MINUTES.toMillis(15)
            System.currentTimeMillis() >= (expirationTime - TimeUnit.MINUTES.toMillis(1))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse x-goog-date", e)
            true
        }
    }

    private fun performMultipartUpload(url: String, fields: JSONObject, email: String, uri: Uri, fileName: String, dt: Long): Int {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)

        var dateTaken = ".$dt"
        var latitude = "."
        var longitude = "."

        try {
            applicationContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                val dtStr = exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (dtStr != null) dateTaken = ".$dtStr"

                val latLong = exif.latLong
                if (latLong != null) {
                    latitude = ".${latLong[0]}"
                    longitude = ".${latLong[1]}"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read EXIF data", e)
        }

        builder.addFormDataPart("key", "$email/$fileName")
        builder.addFormDataPart("x-goog-meta-date_taken", dateTaken)
        builder.addFormDataPart("x-goog-meta-latitude", latitude)
        builder.addFormDataPart("x-goog-meta-longitude", longitude)

        val keys = fields.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            builder.addFormDataPart(key, fields.getString(key))
        }

        val fileBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength() = DocumentFile.fromSingleUri(applicationContext, uri)?.length() ?: -1L
            override fun writeTo(sink: BufferedSink) {
                applicationContext.contentResolver.openInputStream(uri)?.use {
                    sink.writeAll(it.source()) 
                }
            }
        }
        builder.addFormDataPart("file", fileName, fileBody)

        val request = Request.Builder()
            .url(url)
            .post(builder.build())
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            response.code
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Upload Service", NotificationManager.IMPORTANCE_LOW)
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createForegroundInfo(progress: Int, total: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Syncing Photos")
            .setContentText(if (total > 0) "Uploading $progress of $total" else "Initializing...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
