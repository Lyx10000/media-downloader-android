package com.local.douyindownloader

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntentCompatibilityTest {
    @Test
    fun chooserCarriesRawClipDataAndReadPermission() {
        val uri = Uri.parse("content://media/external/video/media/42")
        val target = buildFileShareIntent(
            listOf(ShareableFile(uri, "video.mp4", "video/mp4")),
        ) ?: error("intent was not built")
        val chooser = buildFileShareChooser(target)

        assertEquals(Intent.ACTION_SEND, target.action)
        @Suppress("DEPRECATION")
        val stream = target.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(uri, stream)
        assertEquals(uri, target.clipData?.getItemAt(0)?.uri)
        assertTrue(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(chooser.clipData)
    }
}
