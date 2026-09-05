package com.local.douyindownloader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun `semantic versions compare numeric components`() {
        assertTrue(compareReleaseVersions("1.6.0", "1.5.12") > 0)
        assertEquals(0, compareReleaseVersions("v1.5.4", "1.5.4"))
        assertTrue(compareReleaseVersions("1.5.3", "1.5.4") < 0)
    }

    @Test
    fun `release parser selects arm64 apk and digest`() {
        val release = UpdateReleaseParser.parse(
            JSONObject(
                """
                {"tag_name":"v1.6.0","name":"聚合下载器 1.6.0","body":"更新说明","html_url":"https://github.com/Lyx10000/media-downloader-android/releases/tag/v1.6.0","draft":false,"prerelease":false,
                 "assets":[
                   {"name":"universal.apk","browser_download_url":"https://github.com/Lyx10000/media-downloader-android/releases/download/v1.6.0/universal.apk","size":9000000},
                   {"name":"MediaDownloader-1.6.0-arm64.apk","browser_download_url":"https://github.com/Lyx10000/media-downloader-android/releases/download/v1.6.0/MediaDownloader-1.6.0-arm64.apk","size":6000000,"digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                 ]}
                """.trimIndent(),
            ),
        )

        assertEquals("1.6.0", release?.versionName)
        assertEquals("MediaDownloader-1.6.0-arm64.apk", release?.asset?.name)
        assertEquals("a".repeat(64), release?.asset?.sha256)
        assertEquals(6_000_000L, release?.asset?.sizeBytes)
    }

    @Test
    fun `draft and prerelease are not stable updates`() {
        assertNull(UpdateReleaseParser.parse(JSONObject("""{"tag_name":"v2.0.0","draft":true,"prerelease":false,"assets":[]}""")))
        assertNull(UpdateReleaseParser.parse(JSONObject("""{"tag_name":"v2.0.0-beta","draft":false,"prerelease":true,"assets":[]}""")))
    }

    @Test
    fun `mirror URL only accepts official release assets`() {
        val official = "https://github.com/Lyx10000/media-downloader-android/releases/download/v1.6.0/app.apk"

        assertEquals("https://gh-proxy.org/$official", updateDownloadUrl(official, UpdateSource.MIRROR))
        assertEquals(official, updateDownloadUrl(official, UpdateSource.GITHUB))
        assertFalse(isOfficialUpdateAsset("https://evil.example/app.apk"))
    }

    @Test
    fun `daily automatic check is throttled but manual check is not`() {
        assertFalse(shouldCheckForUpdate(manual = false, todayEpochDay = 100, lastCheckEpochDay = 100))
        assertTrue(shouldCheckForUpdate(manual = false, todayEpochDay = 101, lastCheckEpochDay = 100))
        assertTrue(shouldCheckForUpdate(manual = true, todayEpochDay = 100, lastCheckEpochDay = 100))
    }

    @Test
    fun `update identity requires a newer version and the exact signer set`() {
        validateUpdateIdentity("app.id", 10, setOf("cert"), "app.id", 11, setOf("cert"))

        assertFails { validateUpdateIdentity("app.id", 10, setOf("cert"), "other.id", 11, setOf("cert")) }
        assertFails { validateUpdateIdentity("app.id", 10, setOf("cert"), "app.id", 10, setOf("cert")) }
        assertFails { validateUpdateIdentity("app.id", 10, setOf("cert"), "app.id", 11, setOf("other")) }
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
