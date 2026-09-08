package com.local.multiplatformdownloader.core.update

import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.core.model.UpdateAsset
import com.local.multiplatformdownloader.core.model.UpdateReleaseParser
import com.local.multiplatformdownloader.core.model.UpdateSource
import com.local.multiplatformdownloader.core.model.UpdateStatus
import com.local.multiplatformdownloader.core.model.UpdateUiState
import com.local.multiplatformdownloader.core.model.compareReleaseVersions
import com.local.multiplatformdownloader.core.model.shouldCheckForUpdate
import com.local.multiplatformdownloader.core.model.updateDownloadUrl
import com.local.multiplatformdownloader.core.model.validateUpdateIdentity
import com.local.multiplatformdownloader.core.network.ParserHttpClient
import com.local.multiplatformdownloader.core.settings.SettingsRepository

import com.local.multiplatformdownloader.BuildConfig

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

internal sealed interface UpdateLaunchRequest {
    val intent: Intent

    data class GrantInstallPermission(override val intent: Intent) : UpdateLaunchRequest
    data class InstallApk(override val intent: Intent) : UpdateLaunchRequest
    data class OpenReleasePage(override val intent: Intent) : UpdateLaunchRequest
}

@Singleton
internal class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val parserHttpClient: ParserHttpClient,
    private val logger: DiagnosticLogger,
) {
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    private val checkMutex = Mutex()
    private val downloadMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
    @Volatile private var activeCall: Call? = null
    @Volatile private var readyApk: File? = null
    @Volatile private var cancelRequested = false

    init {
        cleanupUpdateCache()
    }

    suspend fun check(manual: Boolean) = checkMutex.withLock {
        if (_state.value.status == UpdateStatus.DOWNLOADING || _state.value.status == UpdateStatus.VALIDATING) return
        val today = LocalDate.now().toEpochDay()
        val lastCheck = runCatching { settingsRepository.current().lastUpdateCheckEpochDay }
            .getOrDefault(Long.MIN_VALUE)
        if (!shouldCheckForUpdate(manual, today, lastCheck)) return
        runCatching { settingsRepository.setLastUpdateCheckEpochDay(today) }
        _state.value = _state.value.copy(status = UpdateStatus.CHECKING, message = "正在检查更新")
        logger.event(UPDATE_LOG_ID, "UPDATE", "UPDATE_CHECK_STARTED", JSONObject().put("manual", manual))
        try {
            val response = withContext(Dispatchers.IO) {
                parserHttpClient.get(
                    LATEST_RELEASE_API,
                    headers = mapOf(
                        "User-Agent" to "MultiPlatformDownloader/${BuildConfig.VERSION_NAME}",
                        "Accept" to "application/vnd.github+json",
                        "X-GitHub-Api-Version" to "2022-11-28",
                    ),
                    timeoutSeconds = 12,
                )
            }
            if (response.statusCode !in 200..299) throw IOException("GitHub HTTP ${response.statusCode}")
            val release = UpdateReleaseParser.parse(JSONObject(response.body))
                ?: throw IOException("Release 没有可识别的稳定版本")
            if (compareReleaseVersions(release.versionName, BuildConfig.VERSION_NAME) > 0) {
                _state.value = UpdateUiState(
                    status = UpdateStatus.AVAILABLE,
                    release = release,
                    message = "发现新版本 ${release.versionName}",
                )
                logger.event(UPDATE_LOG_ID, "UPDATE", "UPDATE_AVAILABLE", JSONObject().apply {
                    put("current_version", BuildConfig.VERSION_NAME)
                    put("latest_version", release.versionName)
                    put("apk_available", release.asset != null)
                })
            } else {
                _state.value = UpdateUiState(
                    status = UpdateStatus.UP_TO_DATE,
                    release = release,
                    message = "当前已是最新版",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
            _state.value = _state.value.copy(status = UpdateStatus.FAILED, message = "检查更新失败：$message")
            logger.event(UPDATE_LOG_ID, "UPDATE", "UPDATE_CHECK_FAILED", JSONObject().apply {
                put("type", error.javaClass.name)
                put("message", message)
            })
        }
    }

    suspend fun download(source: UpdateSource) = downloadMutex.withLock {
        val release = _state.value.release ?: return
        val asset = release.asset
        if (asset == null) {
            _state.value = _state.value.copy(
                status = UpdateStatus.FAILED,
                source = source,
                message = "Release 中没有可自动选择的 ARM64 APK",
            )
            return
        }
        val directory = updateDirectory().apply { mkdirs() }
        val partial = File(directory, "${asset.name}.part")
        val destination = File(directory, asset.name)
        partial.delete()
        destination.delete()
        readyApk = null
        cancelRequested = false
        _state.value = UpdateUiState(
            status = UpdateStatus.DOWNLOADING,
            release = release,
            source = source,
            totalBytes = asset.sizeBytes,
            message = if (source == UpdateSource.MIRROR) "正在通过镜像下载" else "正在通过 GitHub 下载",
        )
        logger.event(UPDATE_LOG_ID, "UPDATE", "UPDATE_DOWNLOAD_STARTED", JSONObject().apply {
            put("source", source.name.lowercase())
            put("version", release.versionName)
            put("expected_bytes", asset.sizeBytes)
        })
        try {
            downloadFile(updateDownloadUrl(asset.url, source), partial, asset)
            _state.value = _state.value.copy(status = UpdateStatus.VALIDATING, message = "正在验证安装包")
            validatePackage(partial, asset)
            check(partial.renameTo(destination)) { "无法完成更新文件写入" }
            readyApk = destination
            _state.value = _state.value.copy(
                status = UpdateStatus.READY_TO_INSTALL,
                downloadedBytes = destination.length(),
                totalBytes = destination.length(),
                bytesPerSecond = 0L,
                message = "安装包验证通过，等待安装",
            )
        } catch (cancelled: CancellationException) {
            partial.delete()
            _state.value = UpdateUiState(
                status = UpdateStatus.AVAILABLE,
                release = release,
                message = "已取消更新下载",
            )
            throw cancelled
        } catch (error: Throwable) {
            partial.delete()
            destination.delete()
            if (cancelRequested) {
                _state.value = UpdateUiState(
                    status = UpdateStatus.AVAILABLE,
                    release = release,
                    message = "已取消更新下载",
                )
                return@withLock
            }
            val message = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
            _state.value = UpdateUiState(
                status = UpdateStatus.FAILED,
                release = release,
                source = source,
                message = if (source == UpdateSource.MIRROR) {
                    "镜像下载失败：$message"
                } else {
                    "GitHub 下载失败：$message"
                },
            )
            logger.event(UPDATE_LOG_ID, "UPDATE", "UPDATE_SOURCE_FAILED", JSONObject().apply {
                put("source", source.name.lowercase())
                put("type", error.javaClass.name)
                put("message", message)
            })
        } finally {
            activeCall = null
        }
    }

    fun cancelDownload() {
        cancelRequested = true
        activeCall?.cancel()
    }

    fun installRequest(): UpdateLaunchRequest? {
        val file = readyApk?.takeIf(File::isFile) ?: return null
        if (!context.packageManager.canRequestPackageInstalls()) {
            return UpdateLaunchRequest.GrantInstallPermission(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.share-files",
            file,
        )
        return UpdateLaunchRequest.InstallApk(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ).also {
            logger.event(UPDATE_LOG_ID, "UPDATE", "UPDATE_INSTALL_REQUESTED", JSONObject().apply {
                put("version", _state.value.release?.versionName.orEmpty())
            })
        }
    }

    fun releasePageRequest(): UpdateLaunchRequest.OpenReleasePage {
        val url = _state.value.release?.pageUrl
            ?: "https://github.com/Lyx10000/multi-platform-downloader-android/releases/latest"
        return UpdateLaunchRequest.OpenReleasePage(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private suspend fun downloadFile(url: String, target: File, asset: UpdateAsset) = withContext(Dispatchers.IO) {
        var currentUrl = url
        var response: Response? = null
        try {
            for (redirectIndex in 0..MAX_REDIRECTS) {
                currentCoroutineContext().ensureActive()
                require(URI(currentUrl).scheme.equals("https", ignoreCase = true)) { "更新下载只允许 HTTPS" }
                val call = client.newCall(
                    Request.Builder()
                        .url(currentUrl)
                        .header("User-Agent", "MultiPlatformDownloader/${BuildConfig.VERSION_NAME}")
                        .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
                        .build(),
                )
                activeCall = call
                val candidate = call.execute()
                if (candidate.code in 300..399) {
                    val location = candidate.header("Location")
                    candidate.close()
                    if (location.isNullOrBlank() || redirectIndex >= MAX_REDIRECTS) {
                        throw IOException("更新地址重定向异常")
                    }
                    currentUrl = URI(currentUrl).resolve(location).toString()
                } else {
                    response = candidate
                    break
                }
            }
            val finalResponse = response ?: throw IOException("更新地址重定向次数过多")
            if (!finalResponse.isSuccessful) throw IOException("下载 HTTP ${finalResponse.code}")
            val body = finalResponse.body ?: throw IOException("更新响应为空")
            val responseLength = body.contentLength().takeIf { it > 0L } ?: asset.sizeBytes
            var received = 0L
            var sampleBytes = 0L
            var sampleTime = System.nanoTime()
            body.byteStream().use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        sampleBytes += count
                        val now = System.nanoTime()
                        val elapsed = now - sampleTime
                        if (elapsed >= 250_000_000L) {
                            val speed = (sampleBytes * 1_000_000_000L / elapsed).coerceAtLeast(0L)
                            _state.value = _state.value.copy(
                                downloadedBytes = received,
                                totalBytes = responseLength.coerceAtLeast(0L),
                                bytesPerSecond = speed,
                            )
                            sampleBytes = 0L
                            sampleTime = now
                        }
                    }
                    output.flush()
                }
            }
            _state.value = _state.value.copy(
                downloadedBytes = received,
                totalBytes = responseLength.coerceAtLeast(received),
                bytesPerSecond = 0L,
            )
        } finally {
            response?.close()
        }
    }

    private fun validatePackage(file: File, asset: UpdateAsset) {
        try {
            require(file.length() > 0L) { "安装包为空" }
            if (asset.sizeBytes > 0L) require(file.length() == asset.sizeBytes) { "安装包大小与 Release 不一致" }
            file.inputStream().use { input ->
                require(input.read() == 'P'.code && input.read() == 'K'.code) { "下载内容不是 APK 文件" }
            }
            if (asset.sha256.isNotBlank()) {
                require(file.sha256() == asset.sha256) { "安装包 SHA-256 校验失败" }
            }
            val archive = packageInfo(file.absolutePath)
                ?: throw IllegalArgumentException("系统无法读取安装包信息")
            val current = currentPackageInfo()
            val archiveSigners = signerDigests(archive)
            val currentSigners = signerDigests(current)
            validateUpdateIdentity(
                expectedPackage = context.packageName,
                currentVersionCode = current.longVersionCode,
                currentSigners = currentSigners,
                archivePackage = archive.packageName,
                archiveVersionCode = archive.longVersionCode,
                archiveSigners = archiveSigners,
            )
        } catch (error: Throwable) {
            logger.event(UPDATE_LOG_ID, "UPDATE", "UPDATE_VALIDATION_FAILED", JSONObject().apply {
                put("type", error.javaClass.name)
                put("message", Redactor.sanitize(error.message ?: error.javaClass.simpleName))
            })
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(path: String): PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageArchiveInfo(
            path,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        context.packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    @Suppress("DEPRECATION")
    private fun currentPackageInfo(): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    private fun signerDigests(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }

    private fun cleanupUpdateCache() {
        updateDirectory().listFiles()?.forEach(File::delete)
    }

    private fun updateDirectory(): File = File(context.cacheDir, "updates")

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val UPDATE_LOG_ID = "app-update"
        private const val MAX_REDIRECTS = 5
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/Lyx10000/multi-platform-downloader-android/releases/latest"
    }
}
