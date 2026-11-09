package ooo.doo.cnct.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import ooo.doo.cnct.CnctAttachmentRules
import ooo.doo.cnct.CnctLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FilesTest {
    @Test
    fun `HEIC becomes JPEG, and nothing goes up larger than 2560 pixels`() {
        val prepared = ImagePrep.prepare("IMG_0001.HEIC", "image/heic", 4032, 3024) { w, h ->
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }!!
        assertEquals("image/jpeg", prepared.mimeType)
        assertEquals("IMG_0001.jpg", prepared.name)
        val bytes = prepared.bytes!!
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(2560, bounds.outWidth)
        assertEquals(1920, bounds.outHeight)
        assertEquals(bytes.size.toLong(), prepared.size)
    }

    @Test
    fun `a HEIC photo that is already small is converted, not scaled`() {
        assertEquals(ImagePrep.Plan(1200, 900, ImagePrep.Output.JPEG), ImagePrep.plan("image/heif", 1200, 900))
    }

    @Test
    fun `big photos come down, small ones and everything else are left alone`() {
        assertEquals(ImagePrep.Plan(2560, 1440, ImagePrep.Output.JPEG), ImagePrep.plan("image/jpeg", 4000, 2250))
        assertEquals(ImagePrep.Plan(1440, 2560, ImagePrep.Output.JPEG), ImagePrep.plan("image/webp", 2250, 4000))
        // A screenshot stays a PNG: it is usually words.
        assertEquals(ImagePrep.Output.PNG, ImagePrep.plan("image/png", 3000, 1000)!!.output)
        // Never up.
        assertNull(ImagePrep.plan("image/jpeg", 2560, 1920))
        assertNull(ImagePrep.plan("image/jpeg", 800, 600))
        // A GIF moves; a PDF is not a photo.
        assertNull(ImagePrep.plan("image/gif", 4000, 4000))
        assertNull(ImagePrep.plan("application/pdf", 4000, 4000))
    }

    @Test
    fun `the eleventh file, one over 50 MB and one of the wrong type are refused before upload`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val client = CnctChatFixture.client(context, CnctLanguage.EN, scope)
            val controller = ChatController(client, scope, null, null) { null }
            fun file(name: String, type: String, size: Long = 10) = PickedFile(name, type, size) { ByteArray(size.toInt().coerceAtMost(10)).inputStream() }

            controller.addFiles(listOf(file("setup.exe", "application/x-msdownload")))
            assertEquals(ChatController.Notice.Word(R.string.cnct_upload_failed, listOf("setup.exe")), controller.notice.value)
            assertTrue(controller.uploads.value.isEmpty())

            controller.addFiles(listOf(file("film.mp4", "video/mp4", CnctAttachmentRules.MAX_BYTES + 1)))
            assertEquals(ChatController.Notice.Word(R.string.cnct_too_big, listOf("film.mp4")), controller.notice.value)
            assertTrue(controller.uploads.value.isEmpty())

            controller.addFiles((1..11).map { file("doc$it.pdf", "application/pdf") })
            assertEquals(CnctAttachmentRules.MAX_FILES, controller.uploads.value.size)
            assertEquals(ChatController.Notice.Word(R.string.cnct_too_many), controller.notice.value)
            ShadowLooper.idleMainLooper()
        } finally {
            scope.cancel()
        }
    }
}
