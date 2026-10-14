package cat.narezany.rubp.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Adding resources to somebody else's resource table. A port of
 * margyt/resadd.py's `add_files`.
 *
 * Everything else this patcher does to `resources.arsc` is an overwrite, but
 * a custom launcher icon needs a resource that does not exist in TikTok's
 * table: the `activity-alias` entries the patcher bakes have to point at
 * something. So the table's string pool gains the new file's path and the
 * table gains a package of its own to hold the entry -- appended, never
 * spliced in, so nothing already in the table moves. The mod's own build
 * does the same for its icon choices (package 0x30); this one takes the
 * first free id at 0x33 and up, so the two never collide.
 *
 * Overwriting TikTok's own icon files is what the patcher used to do, and it
 * cannot work on any modern phone: the launcher reads the adaptive icon
 * (`res/ar/a.xml`, a mint background with a white note drawn over it), not
 * the legacy PNGs, so the custom picture never reached the home screen while
 * the original entry lost its own. A dedicated entry leaves TikTok's icon
 * alone: "the usual one" stays the usual one.
 */
object ResAdd {

    class TableError(message: String) : IOException(message)

    /** The file the custom picture is added as, and the entry's name. */
    const val PATH = "res/rubtt/icon_custom.png"
    const val RES_NAME = "icon_custom"

    class Added(val data: ByteArray, val resId: Int)

    /**
     * The table with one `mipmap` entry for the custom picture, and the id
     * the manifest aliases must point at. The picture bytes themselves go
     * into the apk at [PATH]; the table only carries the path.
     */
    fun addIcon(table: ByteArray): Added {
        var pid = 0x33
        val used = usedPackageIds(table)
        while (pid in used && pid < 0x7F) pid++
        if (pid >= 0x7F || pid in used) throw TableError("no free package id for the icon")
        val grown = addFiles(table, pid, "mipmap",
            listOf(RES_NAME to PATH), "margytcustom")
        return Added(grown, (pid shl 24) or (1 shl 16) or 0)
    }

    fun usedPackageIds(data: ByteArray): Set<Int> {
        val out = HashSet<Int>()
        val buf = wrap(data)
        if (buf.getShort(0).toInt() and 0xFFFF != RES_TABLE) return out
        var pos = buf.getShort(2).toInt() and 0xFFFF
        while (pos + 8 <= data.size) {
            val ctype = buf.getShort(pos).toInt() and 0xFFFF
            val csize = buf.getInt(pos + 4)
            if (csize < 8) break
            if (ctype == RES_TABLE_PACKAGE) out.add(buf.getInt(pos + 8))
            pos += csize
        }
        return out
    }

    fun addFiles(
        data: ByteArray, packageId: Int, typeName: String,
        namesAndPaths: List<Pair<String, String>>, packageName: String,
    ): ByteArray {
        val buf = wrap(data)
        if (buf.getShort(0).toInt() and 0xFFFF != RES_TABLE) {
            throw TableError("not a resource table")
        }
        val header = buf.getShort(2).toInt() and 0xFFFF
        val packageCount = buf.getInt(8)
        val (grown, first) = appendStrings(data, header, namesAndPaths.map { it.second })
        val pkg = pkg(packageId, typeName, namesAndPaths.map { it.first },
            (first until first + namesAndPaths.size).toList(), packageName)
        val out = grown + pkg
        val w = wrap(out)
        w.putInt(8, packageCount + 1)
        w.putInt(4, out.size)
        return out
    }

    private fun appendStrings(
        data: ByteArray, poolAt: Int, strings: List<String>,
    ): Pair<ByteArray, Int> {
        val buf = wrap(data)
        if (buf.getShort(poolAt).toInt() and 0xFFFF != RES_STRING_POOL) {
            throw TableError("the table does not start with its string pool")
        }
        val headerSize = buf.getShort(poolAt + 2).toInt() and 0xFFFF
        val size = buf.getInt(poolAt + 4)
        val count = buf.getInt(poolAt + 8)
        val flags = buf.getInt(poolAt + 16)
        val stringsStart = buf.getInt(poolAt + 20)
        val stylesStart = buf.getInt(poolAt + 24)
        val utf8 = flags and UTF8 != 0
        val styleOffsetsAt = poolAt + headerSize + 4 * count
        val stringsAt = poolAt + stringsStart
        val stylesAt = if (stylesStart != 0) poolAt + stylesStart else poolAt + size
        val endOfPool = poolAt + size
        val stringsLength = stylesAt - stringsAt

        val encoded = ByteArrayOutputStream()
        val added = ArrayList<Int>()
        for (text in strings) {
            added.add(stringsLength + encoded.size())
            encoded.write(encode(text, utf8))
        }
        while ((stringsLength + encoded.size()) % 4 != 0) encoded.write(0)
        val encBytes = encoded.toByteArray()

        val out = ByteArrayOutputStream()
        out.write(data, 0, styleOffsetsAt)
        for (off in added) out.write(intLE(off))
        out.write(data, styleOffsetsAt, stringsAt - styleOffsetsAt)
        out.write(data, stringsAt, stylesAt - stringsAt)
        out.write(encBytes)
        out.write(data, stylesAt, endOfPool - stylesAt)
        out.write(data, endOfPool, data.size - endOfPool)
        val res = out.toByteArray()

        val moved = 4 * strings.size
        val w = wrap(res)
        w.putInt(poolAt + 8, count + strings.size)
        w.putInt(poolAt + 20, stringsStart + moved)
        if (stylesStart != 0) w.putInt(poolAt + 24, stylesStart + moved + encBytes.size)
        w.putInt(poolAt + 4, size + moved + encBytes.size)
        return res to count
    }

    private fun encode(text: String, utf8: Boolean): ByteArray {
        if (utf8) {
            val raw = text.toByteArray(Charsets.UTF_8)
            if (raw.size > 0x7F) throw TableError("a path that long needs the two-byte form")
            // utf-8 pools write the length in characters and then in bytes
            return byteArrayOf(text.length.toByte(), raw.size.toByte()) + raw + byteArrayOf(0)
        }
        val raw = text.toByteArray(Charset.forName("UTF-16LE"))
        if (text.length > 0x7FFF) throw TableError("too long")
        val out = ByteBuffer.allocate(2 + raw.size + 2).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(text.length.toShort())
        out.put(raw)
        out.putShort(0)
        return out.array()
    }

    /** A whole package chunk: its two pools, a type spec, and one type. */
    private fun pkg(
        packageId: Int, typeName: String, keys: List<String>,
        stringIndexes: List<Int>, packageName: String,
    ): ByteArray {
        val typePool = pool(listOf(typeName))
        val keyPool = pool(keys)
        val count = keys.size
        val body = typePool + keyPool + spec(count) + type(count, stringIndexes)
        val headerSize = 288
        val head = ByteArray(headerSize)
        val w = wrap(head)
        w.putShort(0, RES_TABLE_PACKAGE.toShort())
        w.putShort(2, headerSize.toShort())
        w.putInt(4, headerSize + body.size)
        w.putInt(8, packageId)
        val name = packageName.toByteArray(Charset.forName("UTF-16LE"))
        name.copyInto(head, 12, 0, minOf(name.size, 256))
        // typeStrings, lastPublicType, keyStrings, lastPublicKey
        w.putInt(268, headerSize)
        w.putInt(272, 1)
        w.putInt(276, headerSize + typePool.size)
        w.putInt(280, count)
        return head + body
    }

    /** A little utf-8 string pool, the shape aapt writes. */
    private fun pool(strings: List<String>): ByteArray {
        val offsets = ByteArrayOutputStream()
        val body = ByteArrayOutputStream()
        for (text in strings) {
            offsets.write(intLE(body.size()))
            body.write(encode(text, true))
        }
        while (body.size() % 4 != 0) body.write(0)
        val headerSize = 28
        val stringsStart = headerSize + offsets.size()
        val size = stringsStart + body.size()
        val head = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        head.putShort(RES_STRING_POOL.toShort())
        head.putShort(headerSize.toShort())
        head.putInt(size)
        head.putInt(strings.size)
        head.putInt(0)
        head.putInt(UTF8)
        head.putInt(stringsStart)
        head.putInt(0)
        return head.array() + offsets.toByteArray() + body.toByteArray()
    }

    private fun spec(count: Int): ByteArray {
        val headerSize = 16
        val size = headerSize + 4 * count
        val out = ByteArray(size)
        val w = wrap(out)
        w.putShort(0, RES_TABLE_TYPE_SPEC.toShort())
        w.putShort(2, headerSize.toShort())
        w.putInt(4, size)
        out[8] = 1 // type id
        w.putInt(12, count)
        return out
    }

    /** One configuration -- the default one -- holding every entry. */
    private fun type(count: Int, stringIndexes: List<Int>): ByteArray {
        val config = ByteArray(64)
        wrap(config).putInt(0, 64)
        val entries = ByteArrayOutputStream()
        val offsets = ByteArrayOutputStream()
        for (index in stringIndexes) {
            offsets.write(intLE(entries.size()))
            val e = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            e.putShort(8); e.putShort(0); e.putInt(0) // size, flags, key
            e.putShort(8); e.put(0); e.put(TYPE_STRING.toByte()); e.putInt(index)
            entries.write(e.array())
        }
        val headerSize = 20 + config.size
        val entriesStart = headerSize + offsets.size()
        val size = entriesStart + entries.size()
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        head.putShort(RES_TABLE_TYPE.toShort())
        head.putShort(headerSize.toShort())
        head.putInt(size)
        head.put(1) // type id
        head.put(0); head.put(0); head.put(0) // padding: count sits at 12
        head.putInt(count)
        head.putInt(entriesStart)
        head.put(config)
        out.write(head.array())
        out.write(offsets.toByteArray())
        out.write(entries.toByteArray())
        return out.toByteArray()
    }

    private const val RES_TABLE = 0x0002
    private const val RES_STRING_POOL = 0x0001
    private const val RES_TABLE_PACKAGE = 0x0200
    private const val RES_TABLE_TYPE = 0x0201
    private const val RES_TABLE_TYPE_SPEC = 0x0202
    private const val TYPE_STRING = 0x03
    private const val UTF8 = 1 shl 8

    private fun wrap(data: ByteArray): ByteBuffer =
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    private fun intLE(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
