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
}
