package agency.encrypt.glrenderer.assets

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer

class AndroidAssetSource(private val assets: AssetManager) : AssetSource {

    override fun readText(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

    override fun readImage(path: String): Image {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }
        val bitmap = assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Cannot decode image $path")
        try {
            // ARGB_8888 is laid out in memory as R, G, B, A bytes: exactly what GL_RGBA expects.
            val pixels = ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(pixels)
            return Image(bitmap.width, bitmap.height, pixels.array())
        } finally {
            bitmap.recycle()
        }
    }
}
