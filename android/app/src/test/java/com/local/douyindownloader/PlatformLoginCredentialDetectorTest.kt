package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformLoginCredentialDetectorTest {
    @Test
    fun detectsOnlyPlatformSpecificLoginCookies() {
        assertEquals(
            PlatformCredentialState.DETECTED,
            detectPlatformCredential(SourcePlatform.DOUYIN, "ttwid=guest; SESSIONID=account-session"),
        )
        assertEquals(
            PlatformCredentialState.DETECTED,
            detectPlatformCredential(SourcePlatform.XIAOHONGSHU, "a1=device; web_session=account-session"),
        )
        assertEquals(
            PlatformCredentialState.DETECTED,
            detectPlatformCredential(SourcePlatform.ZHIHU, "_xsrf=value; z_c0=account-session"),
        )
    }

    @Test
    fun genericAndEmptyCookiesAreNotReportedAsLogin() {
        SourcePlatform.entries.forEach { platform ->
            assertEquals(
                PlatformCredentialState.NOT_DETECTED,
                detectPlatformCredential(platform, ""),
            )
        }
        assertEquals(
            PlatformCredentialState.NOT_DETECTED,
            detectPlatformCredential(SourcePlatform.DOUYIN, "ttwid=guest; passport_csrf_token=value"),
        )
        assertEquals(
            PlatformCredentialState.NOT_DETECTED,
            detectPlatformCredential(SourcePlatform.XIAOHONGSHU, "a1=device; webId=guest"),
        )
        assertEquals(
            PlatformCredentialState.NOT_DETECTED,
            detectPlatformCredential(SourcePlatform.ZHIHU, "_xsrf=value; d_c0=device"),
        )
    }

    @Test
    fun cookieNameMatchingIsExact() {
        assertEquals(
            PlatformCredentialState.NOT_DETECTED,
            detectPlatformCredential(
                SourcePlatform.ZHIHU,
                "not_z_c0=value; z_c0_backup=value; malformed",
            ),
        )
    }

    @Test
    fun emptyLoginCookieValueIsNotReportedAsCredential() {
        assertEquals(
            PlatformCredentialState.NOT_DETECTED,
            detectPlatformCredential(SourcePlatform.DOUYIN, "sessionid=; ttwid=guest"),
        )
        assertEquals(
            PlatformCredentialState.NOT_DETECTED,
            detectPlatformCredential(SourcePlatform.XIAOHONGSHU, "web_session=   ; a1=device"),
        )
        assertEquals(
            PlatformCredentialState.NOT_DETECTED,
            detectPlatformCredential(SourcePlatform.ZHIHU, "z_c0=; _xsrf=value"),
        )
    }

    @Test
    fun xiaohongshuWebSnapshotDistinguishesAccountGuestAndUnknownSessions() {
        assertEquals(
            PlatformCredentialState.DETECTED,
            classifyXiaohongshuCredentialSnapshot("""{"loggedIn":true,"loginPrompt":false}"""),
        )
        assertEquals(
            PlatformCredentialState.ANONYMOUS,
            classifyXiaohongshuCredentialSnapshot("""{"loggedIn":false,"loginPrompt":true}"""),
        )
        assertEquals(
            PlatformCredentialState.CHALLENGE_REQUIRED,
            classifyXiaohongshuCredentialSnapshot(
                """{"loggedIn":false,"challengeRequired":true,"loginPrompt":false}""",
            ),
        )
        assertEquals(
            PlatformCredentialState.UNVERIFIED,
            classifyXiaohongshuCredentialSnapshot("""{"loggedIn":false,"loginPrompt":false}"""),
        )
        assertEquals(
            PlatformCredentialState.UNVERIFIED,
            classifyXiaohongshuCredentialSnapshot("not-json"),
        )
    }

    @Test
    fun xiaohongshuSuccessfulValidationIsReusedUntilCookieChangesOrCacheExpires() {
        val cache = XiaohongshuCredentialValidationCache()
        val now = 1_000_000L
        cache.update("web_session=one", PlatformCredentialState.DETECTED, now)

        assertEquals(
            PlatformCredentialState.DETECTED,
            cache.reusableState("web_session=one", now + 29 * 60_000L),
        )
        assertEquals(null, cache.reusableState("web_session=two", now + 1_000L))
        assertEquals(null, cache.reusableState("web_session=one", now + 31 * 60_000L))
        cache.clear()
        assertEquals(null, cache.reusableState("web_session=one", now + 1_000L))
    }

    @Test
    fun xiaohongshuUnknownValidationUsesShortCache() {
        val cache = XiaohongshuCredentialValidationCache()
        val now = 1_000_000L
        cache.update("web_session=one", PlatformCredentialState.UNVERIFIED, now)

        assertEquals(
            PlatformCredentialState.UNVERIFIED,
            cache.reusableState("web_session=one", now + 59_000L),
        )
        assertEquals(null, cache.reusableState("web_session=one", now + 61_000L))
    }
}
