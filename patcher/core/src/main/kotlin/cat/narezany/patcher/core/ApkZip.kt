package cat.narezany.patcher.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The apk as a zip, rewritten entry by entry. A port of margyt/apkzip.py,
 * plus what LSPatch needs: an apk nested whole as a stored entry, and outer
 * entries that share their bytes with it instead of carrying a second copy.
 *
 * Untouched entries are copied across still compressed -- never inflated,
 * never deflated again. Stored entries keep their alignment: 4 bytes for
 * anything, 4096 for a native library the platform maps straight out.
 */
class ApkZip(val file: File) : AutoCloseable {

    class ZipError(message: String) : IOException(message)

    companion object {
        const val STORED = 0
        const val DEFLATED = 8

        private const val LOCAL_SIG = 0x04034B50
        private const val CENTRAL_SIG = 0x02014B50
        private const val EOCD_SIG = 0x06054B50

        /** The old signature, which is about to stop being true. */
        private val SIGNATURE_FILES = arrayOf(".SF", ".RSA", ".DSA", ".EC")

        fun isSignatureEntry(name: String): Boolean {
            val upper = name.uppercase()
            return upper == "META-INF/MANIFEST.MF" ||
                (upper.startsWith("META-INF/") && SIGNATURE_FILES.any { upper.endsWith(it) })
        }

        private fun dosTime(): Pair<Int, Int> = 0x21 to 0x21
    }

    class Entry(
        val name: String,
        var method: Int,
        var crc: Long,
        var csize: Long,
        var usize: Long,
        var dosTime: Int,
        var dosDate: Int,
        /** where the compressed bytes live in the input, or null for new data */
        var sourceOffset: Long?,
        var data: ByteArray? = null,
        /**
         * An entry that carries no bytes of its own: its central directory
         * record points at a local header inside [linkBase]'s data -- the
         * nested original apk -- at [linkInner] bytes from where that data
         * starts. This is how the original apk lives inside the patched one
         * without its size being paid twice.
         */
        var linkBase: Entry? = null,
        var linkInner: Long = 0,
        var align: Int = 4,
        /** a stored entry whose bytes are streamed from a file, never held whole */
        var file: File? = null,
    )

    private val input = RandomAccessFile(file, "r")
    val entries = ArrayList<Entry>()
    val index = HashMap<String, Entry>()

    init {
        readCentralDirectory()
    }

    // ------------------------------------------------------------- read

    private fun readCentralDirectory() {
        val size = input.length()
        val tail = minOf(size, 66000).toInt()
        input.seek(size - tail)
        val blob = ByteArray(tail)
        input.readFully(blob)
        var at = -1
        for (i in blob.size - 22 downTo 0) {
            if (blob[i] == 0x50.toByte() && blob[i + 1] == 0x4B.toByte() &&
                blob[i + 2] == 0x05.toByte() && blob[i + 3] == 0x06.toByte()) { at = i; break }
        }
        if (at < 0) throw ZipError("no end-of-central-directory record: not a zip")
        val eocd = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val count = eocd.getShort(at + 10).toInt() and 0xFFFF
        val cdSize = eocd.getInt(at + 12)
        val cdOffset = eocd.getInt(at + 16)
        if (cdOffset == -1) throw ZipError("zip64 apks are not supported")

        input.seek(cdOffset.toLong())
        val cd = ByteArray(cdSize)
        input.readFully(cd)
        val buf = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        repeat(count) {
            if (buf.getInt(pos) != CENTRAL_SIG) throw ZipError("bad central directory entry at $pos")
            val method = buf.getShort(pos + 10).toInt() and 0xFFFF
            val dosTime = buf.getShort(pos + 12).toInt() and 0xFFFF
            val dosDate = buf.getShort(pos + 14).toInt() and 0xFFFF
            val crc = buf.getInt(pos + 16).toLong() and 0xFFFFFFFFL
            val csize = buf.getInt(pos + 20).toLong() and 0xFFFFFFFFL
            val usize = buf.getInt(pos + 24).toLong() and 0xFFFFFFFFL
            val nameLen = buf.getShort(pos + 28).toInt() and 0xFFFF
            val extraLen = buf.getShort(pos + 30).toInt() and 0xFFFF
            val commentLen = buf.getShort(pos + 32).toInt() and 0xFFFF
            val offset = buf.getInt(pos + 42).toLong() and 0xFFFFFFFFL
            val name = String(cd, pos + 46, nameLen, Charsets.UTF_8)
            pos += 46 + nameLen + extraLen + commentLen
            val entry = Entry(name, method, crc, csize, usize, dosTime, dosDate, offset)
            entries.add(entry)
            index[name] = entry
        }
    }

    /** The entry's bytes exactly as stored, compression and all. */
    fun raw(entry: Entry): ByteArray {
        entry.file?.let { return it.readBytes() }
        entry.data?.let { return compress(it, entry.method) }
        val offset = entry.sourceOffset ?: throw ZipError("entry has no bytes: ${entry.name}")
        input.seek(offset)
        val head = ByteArray(30)
        input.readFully(head)
        val buf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt(0) != LOCAL_SIG) throw ZipError("bad local header for ${entry.name}")
        val nameLen = buf.getShort(26).toInt() and 0xFFFF
        val extraLen = buf.getShort(28).toInt() and 0xFFFF
        input.seek(offset + 30 + nameLen + extraLen)
        val raw = ByteArray(entry.csize.toInt())
        input.readFully(raw)
        return raw
    }

    fun read(name: String): ByteArray {
        val entry = index[name] ?: throw ZipError("no such entry: $name")
        entry.data?.let { return it }
        val raw = raw(entry)
        if (entry.method == STORED) return raw
        val inflater = Inflater(true)
        try {
            inflater.setInput(raw)
            val out = ByteArray(entry.usize.toInt())
            var at = 0
            while (at < out.size) {
                val got = inflater.inflate(out, at, out.size - at)
                if (got == 0 && (inflater.finished() || inflater.needsInput())) break
                at += got
            }
            if (at != out.size) throw ZipError("cannot inflate ${entry.name}")
            return out
        } finally {
            inflater.end()
        }
    }

    fun has(name: String): Boolean = name in index

    // ------------------------------------------------------------ write

    fun replace(name: String, data: ByteArray, method: Int? = null) {
        val entry = index[name] ?: throw ZipError("no such entry: $name")
        entry.data = data
        entry.usize = data.size.toLong()
        val crc = CRC32(); crc.update(data)
        entry.crc = crc.value
        entry.sourceOffset = null
        if (method != null) entry.method = method
    }

    fun add(name: String, data: ByteArray, method: Int = DEFLATED, align: Int = 4): Entry {
        index[name]?.let {
            replace(it.name, data, method)
            it.align = align
            return it
        }
        val crc = CRC32(); crc.update(data)
        val (t, d) = dosTime()
        val entry = Entry(name, method, crc.value, 0, data.size.toLong(), t, d, null, data, align = align)
        entries.add(entry)
        index[name] = entry
        return entry
    }

    /** A stored entry whose bytes are streamed from [file] when the zip is written. */
    fun addFile(name: String, file: File, align: Int = 4): Entry {
        index[name]?.let { remove(it.name) }
        val crc = CRC32()
        file.inputStream().use { input ->
            val block = ByteArray(1 shl 16)
            while (true) {
                val got = input.read(block)
                if (got < 0) break
                crc.update(block, 0, got)
            }
        }
        val (t, d) = dosTime()
        val entry = Entry(name, STORED, crc.value, file.length(), file.length(), t, d,
            null, null, align = align, file = file)
        entries.add(entry)
        index[name] = entry
        return entry
    }

    /** An outer entry that shares its bytes with one inside the nested apk. */
    fun addLink(name: String, base: Entry, inner: Entry): Entry {
        if (name in index) return index.getValue(name)
        val entry = Entry(name, inner.method, inner.crc, inner.csize, inner.usize,
            inner.dosTime, inner.dosDate, null, null,
            linkBase = base, linkInner = inner.sourceOffset ?: 0)
        entries.add(entry)
        index[name] = entry
        return entry
    }

    fun remove(name: String) {
        val entry = index.remove(name) ?: return
        entries.remove(entry)
    }

    /** Throw away the signature the apk arrived with. */
    fun dropSignature() {
        for (name in index.keys.filter { isSignatureEntry(it) }) remove(name)
    }

    /**
     * Write the apk. [linkBase] is the entry whose data the linked entries
     * point into -- the nested original apk, which has to be written stored
     * and early enough that every link lands after its start.
     */
    fun write(out: File) {
        out.parentFile?.mkdirs()
        val ready = HashMap<Entry, ByteArray>()
        java.io.BufferedOutputStream(out.outputStream(), 1 shl 20).use { stream ->
            val written = HashMap<Entry, Long>() // entry -> offset of its local header
            val dataAt = HashMap<Entry, Long>()  // entry -> offset of its data
            var at = 0L
            fun put(bytes: ByteArray) { stream.write(bytes); at += bytes.size }

            for (entry in entries) {
                if (entry.linkBase != null) continue // linked entries have no local record of their own
                val raw = ready.getOrPut(entry) { raw(entry) }
                if (entry.data != null) entry.csize = raw.size.toLong()
                val name = entry.name.toByteArray(Charsets.UTF_8)
                val flag = if (entry.name.all { it.code < 128 }) 0 else 0x800
                var pad = 0
                if (entry.method == STORED) {
                    val dataStart = at + 30 + name.size
                    pad = (((-dataStart) % entry.align + entry.align) % entry.align).toInt()
                }
                written[entry] = at
                val head = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                head.putInt(LOCAL_SIG); head.putShort(20); head.putShort(flag.toShort())
                head.putShort(entry.method.toShort())
                head.putShort(entry.dosTime.toShort()); head.putShort(entry.dosDate.toShort())
                head.putInt(entry.crc.toInt()); head.putInt(entry.csize.toInt()); head.putInt(entry.usize.toInt())
                head.putShort(name.size.toShort()); head.putShort(pad.toShort())
                put(head.array()); put(name)
                if (pad > 0) put(ByteArray(pad))
                dataAt[entry] = at
                val source = entry.file
                if (source != null) {
                    source.inputStream().use { input ->
                        val block = ByteArray(1 shl 20)
                        while (true) {
                            val got = input.read(block)
                            if (got < 0) break
                            stream.write(block, 0, got)
                            at += got
                        }
                    }
                } else {
                    put(raw)
                }
            }

            val cdOffset = at
            for (entry in entries) {
                val name = entry.name.toByteArray(Charsets.UTF_8)
                val flag = if (entry.name.all { it.code < 128 }) 0 else 0x800
                val base = entry.linkBase
                val offset = if (base != null) {
                    // into the nested apk's data, where the original's local header sits
                    (dataAt[base] ?: throw ZipError("link base was not written: ${base.name}")) + entry.linkInner
                } else {
                    written[entry] ?: 0L
                }
                val head = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
                head.putInt(CENTRAL_SIG); head.putShort(20); head.putShort(20)
                head.putShort(flag.toShort()); head.putShort(entry.method.toShort())
                head.putShort(entry.dosTime.toShort()); head.putShort(entry.dosDate.toShort())
                head.putInt(entry.crc.toInt()); head.putInt(entry.csize.toInt()); head.putInt(entry.usize.toInt())
                head.putShort(name.size.toShort())
                head.putShort(0); head.putShort(0); head.putShort(0); head.putShort(0)
                head.putInt(0); head.putInt(offset.toInt())
                put(head.array()); put(name)
            }
            val cdSize = at - cdOffset
            val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            eocd.putInt(EOCD_SIG); eocd.putShort(0); eocd.putShort(0)
            eocd.putShort(entries.size.toShort()); eocd.putShort(entries.size.toShort())
            eocd.putInt(cdSize.toInt()); eocd.putInt(cdOffset.toInt()); eocd.putShort(0)
            put(eocd.array())
        }
    }

    override fun close() = input.close()
}

fun compress(data: ByteArray, method: Int): ByteArray {
    if (method == ApkZip.STORED) return data
    val deflater = Deflater(9, true)
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val block = ByteArray(1 shl 16)
        while (!deflater.finished()) {
            val got = deflater.deflate(block)
            out.write(block, 0, got)
        }
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}
