package android.content

/** JVM-only, in-memory Android API subset; never opens host/device preference files. */
interface SharedPreferences {
    fun getString(key: String?, def: String?): String?
    fun getInt(key: String?, def: Int): Int
    fun getLong(key: String?, def: Long): Long
    fun getBoolean(key: String?, def: Boolean): Boolean
    fun getFloat(key: String?, def: Float): Float
    fun edit(): Editor
    interface Editor {
        fun putString(key: String?, value: String?): Editor
        fun putInt(key: String?, value: Int): Editor
        fun putLong(key: String?, value: Long): Editor
        fun putBoolean(key: String?, value: Boolean): Editor
        fun putFloat(key: String?, value: Float): Editor
        fun remove(key: String?): Editor
        fun apply()
    }
}

class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String?, Any?>()
    var afterStringRead: ((String?) -> Unit)? = null
    override fun getString(key: String?, def: String?): String? {
        val result = synchronized(values) { values[key] as? String ?: def }
        afterStringRead?.invoke(key)
        return result
    }
    override fun getInt(key: String?, def: Int): Int = synchronized(values) { values[key] as? Int ?: def }
    override fun getLong(key: String?, def: Long): Long = synchronized(values) { values[key] as? Long ?: def }
    override fun getBoolean(key: String?, def: Boolean): Boolean = synchronized(values) { values[key] as? Boolean ?: def }
    override fun getFloat(key: String?, def: Float): Float = synchronized(values) { values[key] as? Float ?: def }
    override fun edit(): SharedPreferences.Editor = MemoryEditor()
    private inner class MemoryEditor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String?, Any?>()
        override fun putString(key: String?, value: String?) = also { updates[key] = value }
        override fun putInt(key: String?, value: Int) = also { updates[key] = value }
        override fun putLong(key: String?, value: Long) = also { updates[key] = value }
        override fun putBoolean(key: String?, value: Boolean) = also { updates[key] = value }
        override fun putFloat(key: String?, value: Float) = also { updates[key] = value }
        override fun remove(key: String?) = also { updates[key] = null }
        override fun apply() = synchronized(values) {
            for ((key, value) in updates) if (value == null) values.remove(key) else values[key] = value
        }
    }
}
