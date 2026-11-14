package com.alad.app.presentation.main

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private data class ResolvedMedia(
    val title: String,
    val audioUrl: String,
    val videoUrl: String
)

private suspend fun resolveMedia(pageUrl: String): ResolvedMedia =
    withContext(Dispatchers.IO) {
        val connection = (URL("https://chatgptdub.onrender.com/v1/media/resolve")
            .openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 45000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use {
                it.write(JSONObject().put("url", pageUrl).toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (code !in 200..299) {
                throw IllegalStateException(json.optString("detail", "دریافت لینک ناموفق بود."))
            }
            ResolvedMedia(
                title = json.optString("title", "ویدئو"),
                audioUrl = json.getString("audio_url"),
                videoUrl = json.getString("video_url")
            )
        } finally {
            connection.disconnect()
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkPlayerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var delaySeconds by remember { mutableFloatStateOf(10f) }
    var status by remember { mutableStateOf("لینک یوتیوب یا آدرس مستقیم ویدئو را وارد کن.") }
    var isResolving by remember { mutableStateOf(false) }
    var playJob by remember { mutableStateOf<Job?>(null) }

    val audioPlayer = remember { ExoPlayer.Builder(context).build().apply { volume = 1f } }
    val videoPlayer = remember { ExoPlayer.Builder(context).build().apply { volume = 0f } }

    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        audioPlayer.stop()
        audioPlayer.clearMediaItems()
        videoPlayer.stop()
        videoPlayer.clearMediaItems()
        status = "پخش متوقف شد."
    }

    DisposableEffect(Unit) {
        onDispose {
            playJob?.cancel()
            audioPlayer.release()
            videoPlayer.release()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Alsadidub • پلیر لینک") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("لینک ویدئو") },
                placeholder = { Text("https://youtube.com/watch?v=...") },
                trailingIcon = {
                    if (link.isNotBlank()) {
                        IconButton(onClick = {
                            stopPlayback()
                            link = ""
                            status = "لینک پاک شد. لینک تازه را وارد کن."
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "پاک کردن لینک")
                        }
                    }
                }
            )

            Text("تأخیر تصویر نسبت به صدا: ${delaySeconds.toInt()} ثانیه")
            Slider(
                value = delaySeconds,
                onValueChange = { delaySeconds = it },
                valueRange = 0f..30f,
                steps = 29
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = link.isNotBlank() && !isResolving,
                    onClick = {
                        playJob?.cancel()
                        playJob = scope.launch {
                            isResolving = true
                            status = "در حال دریافت اطلاعات ویدئو..."
                            try {
                                val media = resolveMedia(link.trim())
                                audioPlayer.stop()
                                videoPlayer.stop()
                                audioPlayer.setMediaItem(MediaItem.fromUri(media.audioUrl))
                                videoPlayer.setMediaItem(MediaItem.fromUri(media.videoUrl))
                                audioPlayer.prepare()
                                videoPlayer.prepare()
                                audioPlayer.playWhenReady = true
                                status = "${media.title} • صدا پخش شد؛ تصویر ${delaySeconds.toInt()} ثانیه دیگر شروع می‌شود."
                                if (delaySeconds > 0f) delay((delaySeconds * 1000).toLong())
                                videoPlayer.playWhenReady = true
                                status = "${media.title} • در حال پخش"
                            } catch (error: Exception) {
                                val message = error.message ?: "خطای ناشناخته"
                                status = if (message.contains("not a bot", true) ||
                                    message.contains("confirm you're", true) ||
                                    message.contains("sign in", true)) {
                                    "یوتیوب این درخواست را ربات تشخیص داده است. سرور دریافت لینک نیاز به احراز هویت/راهکار سازگار دارد."
                                } else {
                                    "خطا: $message"
                                }
                            } finally {
                                isResolving = false
                            }
                        }
                    }
                ) { Text(if (isResolving) "در حال دریافت..." else "پخش") }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { stopPlayback() }
                ) { Text("توقف") }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = videoPlayer
                            useController = false
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                )
            }

            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text(
                "صدا بلافاصله شروع می‌شود و تصویر بی‌صدا با تأخیر انتخابی پخش می‌شود.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
