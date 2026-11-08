package android.provider
import android.net.Uri
object MediaStore {
    object Downloads {
        const val DISPLAY_NAME = "display_name"
        const val MIME_TYPE = "mime_type"
        const val RELATIVE_PATH = "relative_path"
        const val IS_PENDING = "is_pending"
        val EXTERNAL_CONTENT_URI = Uri("content://test/downloads")
    }
}
