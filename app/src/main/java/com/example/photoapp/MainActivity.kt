package com.example.photoapp

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.work.*
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.videoFrameMicros
import com.example.photoapp.ui.theme.PhotoAppTheme
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkManager.getInstance(this).cancelAllWork()
        createNotificationChannel()
        enableEdgeToEdge()
        setContent {
            PhotoAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SyncScreen()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "UploadServiceChannel",
            "Upload Service",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}

private const val SERVER_URL = "https://photoapp-service-1078232518222.northamerica-northeast2.run.app"
private const val IAP_CLIENT_ID = "1078232518222-qkqben9iqpsef5496t922fqk0js9i3qe.apps.googleusercontent.com"

@Composable
fun VideoPlayer(videoUrl: String) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    ) {
        onDispose {
            exoPlayer.release()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@SuppressLint("BatteryLife")
@Composable
fun SyncScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    // Video support for Coil
    val videoImageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }

    var isStarting by remember { mutableStateOf(false) }
    val uploadState by UploadWorker.uploadState.collectAsState()

    var authToken by remember { mutableStateOf<String?>(null) }
    var userEmail by remember { mutableStateOf<String?>(null) }
    
    val thumbnails = remember { mutableStateListOf<String>() }
    var nextPageToken by remember { mutableStateOf<String?>(null) }
    var isLoadingThumbnails by remember { mutableStateOf(false) }

    var selectedFullImageUrl by remember { mutableStateOf<String?>(null) }
    var selectedImageName by remember { mutableStateOf<String?>(null) }
    var isFetchingFullImageUrl by remember { mutableStateOf(false) }

    // Multi-select state
    val selectedThumbnails = remember { mutableStateListOf<String>() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteProgress by remember { mutableStateOf("") }

    // Cache for mapping imageName to the fetched full size URL
    val urlCache = remember { mutableStateMapOf<String, String>() }

    val okHttpClient = remember { OkHttpClient() }

    suspend fun fetchThumbnails(token: String, pageToken: String? = null) {
        if (isLoadingThumbnails) return
        isLoadingThumbnails = true
        var responseBody: String? = null
        
        try {
            val url = if (!pageToken.isNullOrEmpty()) {
                "$SERVER_URL/thumbnails?pageToken=$pageToken"
            } else {
                "$SERVER_URL/thumbnails"
            }
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()

            responseBody = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        Log.e("SyncScreen", "Server error ${response.code}: $body")
                    }
                    body
                }
            }
            
            if (responseBody != null) {
                val json = JSONObject(responseBody)
                val data = json.getJSONArray("data")
                val newThumbnails = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    newThumbnails.add(data.getString(i))
                }
                
                withContext(Dispatchers.Main) {
                    thumbnails.addAll(newThumbnails)
                    val nextToken = json.optString("nextPageToken", "")
                    nextPageToken = nextToken.ifEmpty { null }
                }
            }
        } catch (e: Exception) {
            Log.e("SyncScreen", "Failed to fetch thumbnails. Body: $responseBody", e)
        } finally {
            isLoadingThumbnails = false
        }
    }

    suspend fun fetchFullImageUrl(imageName: String) {
        val token = authToken ?: return
        isFetchingFullImageUrl = true
        try {
            val url = "$SERVER_URL/image?image=$imageName"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val responseBody = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("SyncScreen", "Failed to get image URL: ${response.code}")
                        null
                    } else {
                        response.body?.string()
                    }
                }
            }

            if (responseBody != null) {
                val json = JSONObject(responseBody)
                val fullUrl = json.getString("url")
                withContext(Dispatchers.Main) {
                    urlCache[imageName] = fullUrl
                    selectedFullImageUrl = fullUrl
                    selectedImageName = imageName
                }
            }
        } catch (e: Exception) {
            Log.e("SyncScreen", "Error fetching full image URL", e)
        } finally {
            isFetchingFullImageUrl = false
        }
    }

    suspend fun deleteSelectedImages() {
        val token = authToken ?: return
        isDeleting = true
        val total = selectedThumbnails.size
        var count = 0
        
        val toDelete = selectedThumbnails.toList()
        
        for (url in toDelete) {
            val uri = url.toUri()
            val imageName = uri.path?.substringAfterLast('/')
            if (imageName != null) {
                try {
                    val deleteUrl = "$SERVER_URL/image?image=$imageName"
                    val request = Request.Builder()
                        .url(deleteUrl)
                        .delete()
                        .addHeader("Authorization", "Bearer $token")
                        .build()

                    val success = withContext(Dispatchers.IO) {
                        okHttpClient.newCall(request).execute().use { it.isSuccessful }
                    }

                    if (success) {
                        count++
                        deleteProgress = "$count/$total images deleted"
                        withContext(Dispatchers.Main) {
                            thumbnails.remove(url)
                            selectedThumbnails.remove(url)
                        }
                    } else {
                        Log.e("SyncScreen", "Failed to delete $imageName: Server returned error")
                    }
                } catch (e: Exception) {
                    Log.e("SyncScreen", "Failed to delete $imageName", e)
                }
            }
        }
        isDeleting = false
        selectedThumbnails.clear()
        deleteProgress = ""
    }

    suspend fun doSignIn() {
        isStarting = true
        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(IAP_CLIENT_ID)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context, request)
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
            val token = googleIdTokenCredential.idToken
            val email = googleIdTokenCredential.id
            
            authToken = token
            userEmail = email
            
            fetchThumbnails(token)
        } catch (e: Exception) {
            Log.e("SyncScreen", "Sign-in failed", e)
        } finally {
            isStarting = false
        }
    }

    fun startUploadTask(folderUri: Uri, token: String, email: String) {
        val uploadWorkRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(
                "FOLDER_URI" to folderUri.toString(),
                "SERVER_URL" to SERVER_URL,
                "AUTH_TOKEN" to token,
                "USER_EMAIL" to email
            ))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "PhotoSyncJob",
            ExistingWorkPolicy.REPLACE,
            uploadWorkRequest
        )
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { folderUri ->
            context.contentResolver.takePersistableUriPermission(
                folderUri, 
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val token = authToken
            val email = userEmail
            if (token != null && email != null) {
                startUploadTask(folderUri, token, email)
            }
        }
    }

    LaunchedEffect(Unit) {
        doSignIn()
    }

    val gridState = rememberLazyGridState()
    
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }
            .collect { layoutInfo ->
                val totalItemsCount = layoutInfo.totalItemsCount
                if (totalItemsCount > 0) {
                    val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    if (lastVisibleItemIndex >= totalItemsCount * 0.8 && nextPageToken != null && !isLoadingThumbnails) {
                        authToken?.let { fetchThumbnails(it, nextPageToken) }
                    }
                }
            }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Confirm Delete") },
            text = { Text("Are you sure you want to delete ${selectedThumbnails.size} images?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { deleteSelectedImages() }
                }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = { }) {
                    Text("No")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Photo App") },
                    navigationIcon = {
                        if (selectedThumbnails.isNotEmpty()) {
                            IconButton(onClick = { }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    },
                    actions = {
                        if (authToken != null) {
                            TextButton(
                                onClick = { folderPickerLauncher.launch(null) },
                                enabled = !uploadState.isRunning && !isStarting && !isDeleting
                            ) {
                                Text(if (uploadState.isRunning) "Syncing..." else "Sync")
                            }
                        } else if (!isStarting) {
                            TextButton(onClick = { scope.launch { doSignIn() } }) {
                                Text("Sign In")
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).fillMaxSize().padding(16.dp)) {
                if (uploadState.isRunning || isStarting || isDeleting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    val statusText = when {
                        uploadState.isRunning -> uploadState.statusText
                        isStarting -> "Authenticating..."
                        isDeleting -> deleteProgress
                        else -> ""
                    }
                    if (statusText.isNotEmpty()) {
                        Text(text = statusText, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    state = gridState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(thumbnails) { url ->
                        val isSelected = selectedThumbnails.contains(url)
                        val uri = remember(url) { url.toUri() }
                        val isVideo = remember(uri) { uri.path?.lowercase()?.contains(".mp4") == true }

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selectedThumbnails.isNotEmpty()) {
                                            if (isSelected) selectedThumbnails.remove(url)
                                            else selectedThumbnails.add(url)
                                        } else {
                                            val imageName = uri.path?.substringAfterLast('/')
                                            if (imageName != null) {
                                                if (urlCache.containsKey(imageName)) {
                                                    selectedFullImageUrl = urlCache[imageName]
                                                    selectedImageName = imageName
                                                } else {
                                                    scope.launch { fetchFullImageUrl(imageName) }
                                                }
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelected) selectedThumbnails.add(url)
                                    }
                                )
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(url)
                                    .crossfade(true)
                                    .apply {
                                        if (isVideo) {
                                            videoFrameMicros(1000000) // Capture frame at 1 second to avoid black start frames
                                        }
                                    }
                                    .build(),
                                contentDescription = null,
                                imageLoader = videoImageLoader,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )

                            if (isVideo) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Video",
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(32.dp)
                                )
                            }

                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Blue.copy(alpha = 0.4f))
                                )
                            }
                        }
                    }
                    
                    if (isLoadingThumbnails) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }

        // Loading Overlay for Full Image Fetch
        if (isFetchingFullImageUrl) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // Full Screen Overlay
        selectedFullImageUrl?.let { imageUrl ->
            BackHandler {
                selectedFullImageUrl = null
                selectedImageName = null
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val isVideo = selectedImageName?.lowercase()?.contains(".mp4", true) == true
                
                if (isVideo) {
                     VideoPlayer(videoUrl = imageUrl)
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageUrl)
                            .diskCacheKey(selectedImageName)
                            .memoryCacheKey(selectedImageName)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .listener(
                                onStart = { Log.d("AsyncImage", "Loading started: $imageUrl") },
                                onSuccess = { _, _ -> Log.d("AsyncImage", "Loading success") },
                                onError = { _, result -> Log.e("AsyncImage", "Loading error", result.throwable) }
                            )
                            .build(),
                        imageLoader = videoImageLoader,
                        contentDescription = "Full size image",
                        modifier = Modifier.fillMaxSize().clickable { 
                            selectedFullImageUrl = null
                            selectedImageName = null
                        },
                        contentScale = ContentScale.Fit
                    )
                }

                IconButton(
                    onClick = { 
                        selectedFullImageUrl = null
                        selectedImageName = null
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}
