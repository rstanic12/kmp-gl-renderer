@file:OptIn(ExperimentalForeignApi::class)

package agency.encrypt.glrenderer.assets

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.UIKit.UIImage

/** Reads assets from a folder copied into the app bundle (`assets/` by default). */
class BundleAssetSource(private val directory: String = "assets") : AssetSource {

    private fun resolve(path: String): String {
        val root = NSBundle.mainBundle.resourcePath ?: error("App bundle has no resource path")
        return "$root/$directory/$path"
    }

    override fun readText(path: String): String =
        NSString.stringWithContentsOfFile(resolve(path), NSUTF8StringEncoding, null) ?: error("Cannot read asset $path")

    override fun readImage(path: String): Image {
        val image = UIImage.imageWithContentsOfFile(resolve(path))?.CGImage ?: error("Cannot decode image $path")
        val width = CGImageGetWidth(image).toInt()
        val height = CGImageGetHeight(image).toInt()
        val rgba = ByteArray(width * height * 4)
        rgba.usePinned { pinned ->
            val colorSpace = CGColorSpaceCreateDeviceRGB()
            // A bitmap context's memory starts with the top row, matching Image's row order.
            val context = CGBitmapContextCreate(
                pinned.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(),
                colorSpace, CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
            CGContextRelease(context)
            CGColorSpaceRelease(colorSpace)
        }
        return Image(width, height, rgba)
    }
}
