package agency.encrypt.glrenderer.assets

/** Decoded image, 8-bit RGBA, rows from top to bottom. */
public class Image(public val width: Int, public val height: Int, public val rgba: ByteArray) {
    init {
        require(rgba.size == width * height * 4) { "Expected ${width * height * 4} bytes, got ${rgba.size}" }
    }
}

/** Where models and textures come from: Android assets, the iOS app bundle, or memory in tests. */
public interface AssetSource {
    public fun readText(path: String): String
    public fun readImage(path: String): Image
}

/** Resolves [name] next to [path]: `models/car.obj` + `car.mtl` -> `models/car.mtl`. */
public fun siblingPath(path: String, name: String): String {
    val slash = path.lastIndexOf('/')
    return if (slash < 0) name else path.substring(0, slash + 1) + name
}
