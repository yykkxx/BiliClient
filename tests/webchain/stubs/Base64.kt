package android.util
object Base64 {
    const val DEFAULT = 0
    @JvmStatic fun decode(value: String, flags: Int): ByteArray = java.util.Base64.getDecoder().decode(value)
}
