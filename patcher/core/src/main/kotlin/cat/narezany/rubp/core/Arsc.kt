package cat.narezany.rubp.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * resources.arsc, read. A port of the lookup half of margyt/arsc.py.
 *
 * Used to find out where a resource lives: an icon's res id becomes the path
 * of the file the table points at, for every configuration it has.
 */
class Arsc(data: ByteArray) {

    class ArscError(message: String) : IOException(message)

    /** One configuration's value for one resource id. */
    class Value(
        val typeName: String,
        val config: ByteArray,
        val kind: Int,
        val data: Int,
    ) {
        /** The config's density in dpi, 0 when it does not name one. */
        val density: Int
            get() = if (config.size < 16) 0
            else wrap(config).getShort(14).toInt() and 0xFFFF

        val sdk: Int
            get() = if (config.size < 26) 0
            else wrap(config).getShort(24).toInt() and 0xFFFF
    }

    private class Pool(data: ByteArray, start: Int) {
        val utf8: Boolean
        val count: Int
        val end: Int
        private val base: Int
        private val offsets: IntArray

        init {
            val buf = wrap(data)
            if (buf.getShort(start).toInt() and 0xFFFF != RES_STRING_POOL) {
                throw ArscError("expected a string pool at $start")
            }
            val headerSize = buf.getShort(start + 2).toInt() and 0xFFFF
            val size = buf.getInt(start + 4)
            count = buf.getInt(start + 8)
            val flags = buf.getInt(start + 16)
            utf8 = flags and UTF8 != 0
            base = start + buf.getInt(start + 20)
            end = start + size
            offsets = IntArray(count) { buf.getInt(start + headerSize + it * 4) }
        }

        fun get(data: ByteArray, index: Int): String? {
            if (index < 0 || index >= count) return null
            return readString(data, base + offsets[index], utf8)
        }

        companion object {
            const val UTF8 = 1 shl 8
        }
    }

    private class Package(val id: Int, val name: String) {
        var typeNames: Pool? = null
        // type id -> RES_TABLE_TYPE chunk starts
        val types = HashMap<Int, MutableList<Int>>()
    }

    private val data: ByteArray = data
    private val strings: Pool
    private val packages = ArrayList<Package>()

    init {
        val buf = wrap(data)
        if (buf.getShort(0).toInt() and 0xFFFF != RES_TABLE) {
            throw ArscError("not a resource table")
        }
        val headerSize = buf.getShort(2).toInt() and 0xFFFF
        strings = Pool(data, headerSize)
        var pos = strings.end
        while (pos + 8 <= data.size) {
            val ctype = buf.getShort(pos).toInt() and 0xFFFF
            val chdr = buf.getShort(pos + 2).toInt() and 0xFFFF
            val csize = buf.getInt(pos + 4)
            if (csize < 8) throw ArscError("chunk 0x${ctype.toString(16)} at $pos has size $csize")
            if (ctype == RES_TABLE_PACKAGE) parsePackage(pos, chdr, csize)
            pos += csize
        }
    }

    private fun parsePackage(start: Int, headerSize: Int, size: Int) {
        val buf = wrap(data)
        val pid = buf.getInt(start + 8)
        val name = String(data, start + 12, 256, Charset.forName("UTF-16LE")).split('\u0000')[0]
        val typeStrings = buf.getInt(start + 268)
        val pkg = Package(pid, name)
        if (typeStrings != 0) pkg.typeNames = Pool(data, start + typeStrings)
        var pos = start + headerSize
        val end = start + size
        while (pos + 8 <= end) {
            val ctype = buf.getShort(pos).toInt() and 0xFFFF
            val csize = buf.getInt(pos + 4)
            if (csize < 8) break
            if (ctype == RES_TABLE_TYPE) {
                val typeId = data[pos + 8].toInt() and 0xFF
                pkg.types.getOrPut(typeId) { ArrayList() }.add(pos)
            }
            pos += csize
        }
        packages.add(pkg)
    }

    /** Every configuration's value for [resId], in table order. */
    fun values(resId: Int): List<Value> {
        val pid = resId ushr 24 and 0xFF
        val typeId = resId ushr 16 and 0xFF
        val entryId = resId and 0xFFFF
        val pkg = packages.firstOrNull { it.id == pid } ?: return emptyList()
        val typeName = pkg.typeNames?.get(data, typeId - 1) ?: "?"
        val out = ArrayList<Value>()
        for (chunk in pkg.types[typeId] ?: return emptyList()) {
            entryValue(chunk, entryId, typeName)?.let(out::add)
        }
        return out
    }

    private fun entryValue(chunk: Int, entryId: Int, typeName: String): Value? {
        val buf = wrap(data)
        val headerSize = buf.getShort(chunk + 2).toInt() and 0xFFFF
        val flags = data[chunk + 9].toInt() and 0xFF
        val entryCount = buf.getInt(chunk + 12)
        val entriesStart = buf.getInt(chunk + 16)
        val configSize = buf.getInt(chunk + 20)
        val config = data.copyOfRange(chunk + 20, chunk + 20 + configSize)

        val table = chunk + headerSize
        val offset: Int = when {
            flags and FLAG_SPARSE != 0 -> {
                var found: Int? = null
                for (i in 0 until entryCount) {
                    val idx = buf.getShort(table + i * 4).toInt() and 0xFFFF
                    val off = buf.getShort(table + i * 4 + 2).toInt() and 0xFFFF
                    if (idx == entryId) { found = off * 4; break }
                }
                found ?: return null
            }
            flags and FLAG_OFFSET16 != 0 -> {
                if (entryId >= entryCount) return null
                val off = buf.getShort(table + entryId * 2).toInt() and 0xFFFF
                if (off == NO_ENTRY16) return null
                off * 4
            }
            else -> {
                if (entryId >= entryCount) return null
                val off = buf.getInt(table + entryId * 4)
                if (off == -1) return null
                off
            }
        }

        val entry = chunk + entriesStart + offset
        val sizeOrKey = buf.getShort(entry).toInt() and 0xFFFF
        val entryFlags = buf.getShort(entry + 2).toInt() and 0xFFFF
        if (entryFlags and ENTRY_FLAG_COMPACT != 0) {
            val kind = entryFlags shr 8
            val value = buf.getInt(entry + 4)
            return Value(typeName, config, kind, value)
        }
        if (entryFlags and ENTRY_FLAG_COMPLEX != 0) return null // a bag: style, array, attr
        val at = entry + sizeOrKey
        val kind = data[at + 3].toInt() and 0xFF
        val value = buf.getInt(at + 4)
        return Value(typeName, config, kind, value)
    }

    /** The file a value points at, when it is a file at all. */
    fun filePath(value: Value): String? {
        if (value.kind != TYPE_STRING) return null
        return strings.get(data, value.data)
    }

    companion object {
        const val RES_STRING_POOL = 0x0001
        const val RES_TABLE = 0x0002
        const val RES_TABLE_PACKAGE = 0x0200

        /**
         * The table with package chunks renamed from [oldName] to [newName].
         * Apps look resource ids up by package name (Resources.getIdentifier
         * with the running package), so renaming the manifest without
         * renaming the table leaves every such lookup returning 0. Only the
         * app's own family is renamed (feature split packages keep their
         * suffixes, foreign package names stay); the name lives in a fixed
         * 128 UTF-16 field, so the file keeps its size and offsets.
         */
        fun renamed(data: ByteArray, oldName: String, newName: String): ByteArray {
            val out = data.copyOf()
            val buf = wrap(data)
            var pos = buf.getShort(2).toInt() and 0xFFFF
            while (pos + 8 <= data.size) {
                val ctype = buf.getShort(pos).toInt() and 0xFFFF
                val csize = buf.getInt(pos + 4)
                if (csize < 8) throw ArscError("chunk 0x${ctype.toString(16)} at $pos has size $csize")
                if (ctype == RES_TABLE_PACKAGE) {
                    val name = String(data, pos + 12, 256, Charset.forName("UTF-16LE"))
                        .split('\u0000')[0]
                    if (name == oldName || name.startsWith("$oldName.")) {
                        val moved = newName + name.substring(oldName.length)
                        val chars = moved.toCharArray()
                        if (chars.size > 127)
                            throw ArscError("package name too long for the resource table")
                        for (i in 0 until 128) {
                            val c = if (i < chars.size) chars[i].code else 0
                            out[pos + 12 + i * 2] = (c and 0xFF).toByte()
                            out[pos + 12 + i * 2 + 1] = (c ushr 8).toByte()
                        }
                    }
                }
                pos += csize
            }
            return out
        }
        const val RES_TABLE_TYPE = 0x0201

        const val TYPE_STRING = 0x03
        const val TYPE_REFERENCE = 0x01

        const val FLAG_SPARSE = 0x01
        const val FLAG_OFFSET16 = 0x02
        const val ENTRY_FLAG_COMPLEX = 0x0001
        const val ENTRY_FLAG_COMPACT = 0x0008
        const val NO_ENTRY16 = 0xFFFF

        private fun wrap(data: ByteArray): ByteBuffer =
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        private fun readLen(data: ByteArray, pos: Int, utf8: Boolean): Pair<Int, Int> {
            val buf = wrap(data)
            if (utf8) {
                val first = data[pos].toInt() and 0xFF
                if (first and 0x80 != 0) return ((first and 0x7F) shl 8) or (data[pos + 1].toInt() and 0xFF) to pos + 2
                return first to pos + 1
            }
            val first = buf.getShort(pos).toInt() and 0xFFFF
            if (first and 0x8000 != 0) {
                val second = buf.getShort(pos + 2).toInt() and 0xFFFF
                return ((first and 0x7FFF) shl 16) or second to pos + 4
            }
            return first to pos + 2
        }

        private fun readString(data: ByteArray, pos: Int, utf8: Boolean): String {
            if (utf8) {
                val (_, p1) = readLen(data, pos, true)
                val (nbytes, p2) = readLen(data, p1, true)
                return String(data, p2, nbytes, Charsets.UTF_8)
            }
            val (nchars, p) = readLen(data, pos, false)
            return String(data, p, nchars * 2, Charset.forName("UTF-16LE"))
        }
    }
}
