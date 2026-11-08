package android.util
object Log {
    val warnings = mutableListOf<String>()
    fun w(tag: String, message: String): Int { warnings += message; return 0 }
    fun w(tag: String, message: String, error: Throwable): Int { warnings += message; return 0 }
}
