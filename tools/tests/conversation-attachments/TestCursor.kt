package android.database

class Cursor(
    private val columns: Array<String>,
    private val values: List<Any?>,
) : AutoCloseable {
    var closed = false
    fun moveToFirst(): Boolean = true
    fun getColumnIndex(name: String): Int = columns.indexOf(name)
    fun isNull(index: Int): Boolean = values[index] == null
    fun getString(index: Int): String = values[index] as String
    fun getLong(index: Int): Long = values[index] as Long
    override fun close() { closed = true }
}
