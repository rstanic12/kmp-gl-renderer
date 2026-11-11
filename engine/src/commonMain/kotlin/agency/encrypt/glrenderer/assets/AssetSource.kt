package agency.encrypt.glrenderer.assets

/** Decoded image, 8-bit RGBA, rows from top to bottom. */
class Image(val width: Int, val height: Int, val rgba: ByteArray) {
    init {
        require(rgba.size == width * height * 4) { "Expected ${width * height * 4} bytes, got ${rgba.size}" }
    }
}

/** Where models and textures come from: Android assets, the iOS app bundle, or memory in tests. */
interface AssetSource {
    fun readText(path: String): String
    fun readImage(path: String): Image
}

/** Resolves [name] next to [path]: `models/car.obj` + `car.mtl` -> `models/car.mtl`. */
fun siblingPath(path: String, name: String): String {
    val slash = path.lastIndexOf('/')
    return if (slash < 0) name else path.substring(0, slash + 1) + name
}
