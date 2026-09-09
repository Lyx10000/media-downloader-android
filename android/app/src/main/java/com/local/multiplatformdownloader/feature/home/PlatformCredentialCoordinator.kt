package com.local.multiplatformdownloader.feature.home

import android.net.Uri
import android.webkit.CookieManager
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.WebPageSnapshot
import com.local.multiplatformdownloader.platform.bilibili.BilibiliPlatformParser
import com.local.multiplatformdownloader.platform.common.PlatformCredentialState
import com.local.multiplatformdownloader.platform.common.XiaohongshuCredentialValidationCache
import com.local.multiplatformdownloader.platform.common.classifyXiaohongshuCredentialSnapshot
import com.local.multiplatformdownloader.platform.common.detectPlatformCredential
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
internal class PlatformCredentialCoordinator @Inject constructor(
    private val logger: DiagnosticLogger,
    private val bilibiliParser: BilibiliPlatformParser,
) {
    private val _states = MutableStateFlow(
        SourcePlatform.entries.associateWith { PlatformCredentialState.NOT_DETECTED },
    )
    val states: StateFlow<Map<SourcePlatform, PlatformCredentialState>> = _states.asStateFlow()

    private val xiaohongshuCache = XiaohongshuCredentialValidationCache()
    private var bilibiliCredentialJob: Job? = null

    fun refresh(scope: CoroutineScope, forceXiaohongshuValidation: Boolean = false) {
        val cookieManager = CookieManager.getInstance()
        if (forceXiaohongshuValidation) xiaohongshuCache.clear()
        val cookies = SourcePlatform.entries.associateWith { platform ->
            cookieManager.getCookie(platform.homeUrl).orEmpty()
        }
        val localStates = SourcePlatform.entries.associateWith { platform ->
            detectPlatformCredential(platform, cookies.getValue(platform))
        }.toMutableMap()
        if (localStates[SourcePlatform.XIAOHONGSHU] == PlatformCredentialState.DETECTED) {
            localStates[SourcePlatform.XIAOHONGSHU] = if (forceXiaohongshuValidation) {
                PlatformCredentialState.CHECKING
            } else {
                xiaohongshuCache.reusableState(
                    cookies.getValue(SourcePlatform.XIAOHONGSHU),
                    System.currentTimeMillis(),
                ) ?: PlatformCredentialState.CHECKING
            }
        } else {
            xiaohongshuCache.clear()
        }
        bilibiliCredentialJob?.cancel()
        val bilibiliCookie = cookies.getValue(SourcePlatform.BILIBILI)
        val checkBilibili = localStates[SourcePlatform.BILIBILI] == PlatformCredentialState.DETECTED
        if (checkBilibili) localStates[SourcePlatform.BILIBILI] = PlatformCredentialState.CHECKING
        _states.value = localStates.toMap()
        if (checkBilibili) {
            bilibiliCredentialJob = scope.launch {
                val validated = withContext(Dispatchers.IO) {
                    bilibiliParser.credentialState(bilibiliCookie)
                }
                if (CookieManager.getInstance()
                        .getCookie(SourcePlatform.BILIBILI.homeUrl)
                        .orEmpty() == bilibiliCookie
                ) {
                    _states.update { current ->
                        current + (SourcePlatform.BILIBILI to validated)
                    }
                }
            }
        }
    }

    fun onEnvironmentOpened(scope: CoroutineScope, platform: SourcePlatform) {
        refresh(scope)
        logger.event("app-login", "LOGIN_WEBVIEW", "LOGIN_ENVIRONMENT_OPENED", JSONObject().apply {
            put("platform", platform.wireValue)
            put("credential_detected", states.value[platform] == PlatformCredentialState.DETECTED)
            put("credential_state", states.value[platform]?.name.orEmpty())
        })
    }

    fun onEnvironmentClosed(scope: CoroutineScope, platform: SourcePlatform) {
        CookieManager.getInstance().flush()
        refresh(scope, forceXiaohongshuValidation = platform == SourcePlatform.XIAOHONGSHU)
        logger.event("app-login", "LOGIN_WEBVIEW", "LOGIN_ENVIRONMENT_CLOSED", JSONObject().apply {
            put("platform", platform.wireValue)
            put("credential_detected", states.value[platform] == PlatformCredentialState.DETECTED)
            put("credential_state", states.value[platform]?.name.orEmpty())
        })
    }

    fun onPageFinished(platform: SourcePlatform, url: String) {
        logger.event("app-login", "LOGIN_WEBVIEW", "LOGIN_PAGE_FINISHED", JSONObject().apply {
            put("platform", platform.wireValue)
            put("host", runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault(""))
            put("credential_detected", states.value[platform] == PlatformCredentialState.DETECTED)
            put("credential_state", states.value[platform]?.name.orEmpty())
        })
    }

    fun onAssistResult(platform: SourcePlatform, url: String, result: String) {
        logger.event("app-login", "LOGIN_WEBVIEW", "LOGIN_ASSIST_RESULT", JSONObject().apply {
            put("platform", platform.wireValue)
            put("host", runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault(""))
            put("path", runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault(""))
            put("result", Redactor.sanitize(result))
            put("desktop_mode", shouldUseDesktopLoginMode(platform))
        })
    }

    fun onPageError(platform: SourcePlatform, url: String, errorCode: Int, description: String) {
        logger.event("app-login", "LOGIN_WEBVIEW", "LOGIN_PAGE_ERROR", JSONObject().apply {
            put("platform", platform.wireValue)
            put("host", runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault(""))
            put("error_code", errorCode)
            put("description", Redactor.sanitize(description))
        })
    }

    fun onXiaohongshuProbe(snapshot: WebPageSnapshot?) {
        if (states.value[SourcePlatform.XIAOHONGSHU] != PlatformCredentialState.CHECKING) return
        val state = snapshot?.initialData
            ?.let(::classifyXiaohongshuCredentialSnapshot)
            ?: PlatformCredentialState.UNVERIFIED
        val cookieHeader = CookieManager.getInstance()
            .getCookie(SourcePlatform.XIAOHONGSHU.homeUrl)
            .orEmpty()
        if (cookieHeader.isNotBlank()) {
            xiaohongshuCache.update(cookieHeader, state, System.currentTimeMillis())
        } else {
            xiaohongshuCache.clear()
        }
        _states.update { current -> current + (SourcePlatform.XIAOHONGSHU to state) }
        logger.event("app-login", "LOGIN_STATUS", "CREDENTIAL_VALIDATED", JSONObject().apply {
            put("platform", SourcePlatform.XIAOHONGSHU.wireValue)
            put("state", state.name)
            put("source", "webview")
            put("final_path", runCatching { Uri.parse(snapshot?.finalUrl).path.orEmpty() }.getOrDefault(""))
        })
    }

    fun cancel() {
        bilibiliCredentialJob?.cancel()
    }
}
