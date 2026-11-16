package com.jarvis.assistant.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    const val GITHUB_OWNER = "stylesheet48-max"
    const val GITHUB_REPO = "Jarvis"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    data class UpdateInfo(
        val tagName: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val sizeBytes: Long,
        val publishedAt: String,
        val isNewer: Boolean
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun checkForUpdate(
        scope: CoroutineScope,
        onResult: (Result<UpdateInfo?>) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(RELEASES_API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "JARVIS-Assistant-Updater")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    val code = response.code
                    if (code == 404) {
                        // No release published yet
                        withContext(Dispatchers.Main) { onResult(Result.success(null)) }
                        return@launch
                    }
                    throw IllegalStateException("GitHub API returned error HTTP $code")
                }

                val bodyStr = response.body?.string().orEmpty()
                val json = JsonParser.parseString(bodyStr).asJsonObject

                val tagName = json.get("tag_name")?.asString.orEmpty()
                val releaseNotes = json.get("body")?.asString.orEmpty()
                val publishedAt = json.get("published_at")?.asString.orEmpty()

                var apkDownloadUrl = ""
                var apkSizeBytes = 0L

                val assetsArray = json.getAsJsonArray("assets")
                if (assetsArray != null) {
                    for (assetElement in assetsArray) {
                        val assetObj = assetElement.asJsonObject
                        val name = assetObj.get("name")?.asString.orEmpty()
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = assetObj.get("browser_download_url")?.asString.orEmpty()
                            apkSizeBytes = assetObj.get("size")?.asLong ?: 0L
                            break
                        }
                    }
                }

                if (apkDownloadUrl.isBlank()) {
                    apkDownloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest/download/app-debug.apk"
                }

                // Check if release is newer
                val isNewer = true // Rolling builds always allow direct refresh/update

                val updateInfo = UpdateInfo(
                    tagName = tagName,
                    downloadUrl = apkDownloadUrl,
                    releaseNotes = releaseNotes,
                    sizeBytes = apkSizeBytes,
                    publishedAt = publishedAt,
                    isNewer = isNewer
                )

                withContext(Dispatchers.Main) {
                    onResult(Result.success(updateInfo))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check update: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(e))
                }
            }
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun downloadAndInstall(
        context: Context,
        scope: CoroutineScope,
        downloadUrl: String,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!canRequestPackageInstalls(context)) {
            requestInstallPermission(context)
            onError("Please allow 'Install unknown apps' permission for JARVIS and tap Update again.")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
                val targetFile = File(targetDir, "jarvis-update.apk")
                if (targetFile.exists()) {
                    targetFile.delete()
                }

                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "JARVIS-Assistant-Updater")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Download failed with HTTP ${response.code}")
                }

                val responseBody = response.body ?: throw IllegalStateException("Empty response body")
                val totalBytes = responseBody.contentLength()

                responseBody.byteStream().use { inputStream ->
                    FileOutputStream(targetFile).use { outputStream ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead: Int
                        var downloadedBytes: Long = 0
                        var lastProgress = 0

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    withContext(Dispatchers.Main) {
                                        onProgress(progress)
                                    }
                                }
                            }
                        }
                        outputStream.flush()
                    }
                }

                withContext(Dispatchers.Main) {
                    onProgress(100)
                    launchPackageInstaller(context, targetFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Download failed")
                }
            }
        }
    }

    private fun launchPackageInstaller(context: Context, apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            Log.i(TAG, "Launched package installer for: ${apkFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
        }
    }
}
