package android.content

import android.content.pm.PackageManager
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

// Test-only in-memory Android boundary. No device resolver or package manager is contacted.
class ContentValues {
    val entries = mutableMapOf<String, Any>()
    fun put(key: String, value: String) { entries[key] = value }
    fun put(key: String, value: Int) { entries[key] = value }
}
open class Context(val cacheDir: File, val contentResolver: ContentResolver, val packageManager: PackageManager) {
    val applicationContext: Context get() = this
    val packageName = "com.RobinNotBad.BiliClient"
    fun getString(id: Int): String = "test-error-$id"
}
class ContentResolver {
    var insertNull = false
    var outputNull = false
    var outputThrows = false
    var updateCount = 1
    var updateThrows = false
    var deleteThrows = false
    var inserts = 0
    var deletes = 0
    var pending: Int? = null
    var rowExists = false
    val bytes = ByteArrayOutputStream()
    var beforeWrite: (() -> Unit)? = null
    var beforeUpdate: (() -> Unit)? = null
    fun insert(uri: Uri, values: ContentValues): Uri? {
        inserts++
        if (insertNull) return null
        pending = values.entries["is_pending"] as Int
        rowExists = true
        return Uri("content://test/downloads/1")
    }
    fun openOutputStream(uri: Uri, mode: String): OutputStream? {
        if (outputNull) return null
        return object : OutputStream() {
            override fun write(value: Int) {
                beforeWrite?.invoke()
                if (outputThrows) throw IOException("fixture storage write failure")
                bytes.write(value)
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                beforeWrite?.invoke()
                if (outputThrows) throw IOException("fixture storage write failure")
                bytes.write(buffer, offset, length)
            }
        }
    }
    fun update(uri: Uri, values: ContentValues, selection: String?, args: Array<String>?): Int {
        beforeUpdate?.invoke()
        if (updateThrows) throw IOException("fixture publish failure")
        if (updateCount == 1) pending = values.entries["is_pending"] as Int
        return updateCount
    }
    fun delete(uri: Uri, selection: String?, args: Array<String>?): Int {
        deletes++
        if (deleteThrows) throw IOException("fixture delete failure")
        rowExists = false
        return 1
    }
}
