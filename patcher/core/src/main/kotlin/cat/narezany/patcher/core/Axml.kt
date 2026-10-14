package cat.narezany.patcher.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Binary XML (AXML) read and write. A port of margyt/axml.py.
 *
 * Android's compiled XML is a handful of chunks: a string pool, a map from
 * attribute name to framework resource id, and a flat stream of start/end
 * nodes. This parses that into objects, lets you edit them, and writes it
 * back. Strings are appended to the pool, never reordered, so every index
 * already in use keeps pointing where it did.
 */
class Axml private constructor(
    val pool: StringPool,
    val resourceMap: MutableList<Long>,
    val nodes: MutableList<Node>,
) {
    class AxmlError(message: String) : IOException(message)

    /** The pool of every string the file mentions, in its original order. */
    class StringPool(
        val strings: MutableList<String>,
        flags: Int,
        val styles: ByteArray,
        val styleOffsets: List<Long>,
    ) {
        val flags = flags and SORTED.inv()

        val utf8: Boolean get() = flags and UTF8 != 0

        /** The index of [value], appending it to the pool if it is new. */
        fun index(value: String): Int {
            val have = strings.indexOf(value)
            if (have >= 0) return have
            strings.add(value)
            return strings.size - 1
        }

        fun get(index: Int): String? =
            if (index < 0 || index >= strings.size) null else strings[index]

        fun build(): ByteArray {
            val offsets = ArrayList<Long>(strings.size)
            val blob = java.io.ByteArrayOutputStream()
            val seen = HashMap<String, Int>()
            for (s in strings) {
                val at = seen[s]
                if (at != null) {
                    offsets.add(at.toLong())
                    continue
                }
                seen[s] = blob.size()
                offsets.add(blob.size().toLong())
                blob.write(writeString(s, utf8))
            }
            while (blob.size() % 4 != 0) blob.write(0)

            val headerSize = 28
            val stringsStart = headerSize + 4 * offsets.size + 4 * styleOffsets.size
            val stylesStart = if (styles.isNotEmpty()) stringsStart + blob.size() else 0
            val size = stringsStart + blob.size() + styles.size

            val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            out.putShort(RES_STRING_POOL.toShort()); out.putShort(headerSize.toShort()); out.putInt(size)
            out.putInt(offsets.size); out.putInt(styleOffsets.size); out.putInt(flags)
            out.putInt(stringsStart); out.putInt(stylesStart)
            for (o in offsets) out.putInt(o.toInt())
            for (o in styleOffsets) out.putInt(o.toInt())
            out.put(blob.toByteArray())
            out.put(styles)
            return out.array()
        }

        companion object {
            const val SORTED = 1 shl 0
            const val UTF8 = 1 shl 8

            fun parse(data: ByteArray, start: Int): StringPool {
                val buf = wrap(data)
                val kind = buf.getShort(start).toInt() and 0xFFFF
                if (kind != RES_STRING_POOL) {
                    throw AxmlError("expected a string pool at $start, found chunk 0x${kind.toString(16)}")
                }
                val headerSize = buf.getShort(start + 2).toInt() and 0xFFFF
                val size = buf.getInt(start + 4)
                val count = buf.getInt(start + 8)
                val styleCount = buf.getInt(start + 12)
                val flags = buf.getInt(start + 16)
                val stringsStart = buf.getInt(start + 20)
                val stylesStart = buf.getInt(start + 24)
                val offsets = LongArray(count) { buf.getInt(start + headerSize + it * 4).toLong() and 0xFFFFFFFFL }
                val styleOffsets = List(styleCount) {
                    buf.getInt(start + headerSize + 4 * count + it * 4).toLong() and 0xFFFFFFFFL
                }
                val base = start + stringsStart
                val utf8 = flags and UTF8 != 0
                val strings = offsets.map { readString(data, (base + it).toInt(), utf8) }.toMutableList()
                val styles = if (styleCount > 0) data.copyOfRange(start + stylesStart, start + size) else ByteArray(0)
                return StringPool(strings, flags, styles, styleOffsets)
            }
        }
    }

    class Attribute(var ns: Int, var name: Int, var raw: Int, var kind: Int, var data: Int) {
        fun pack(out: ByteBuffer) {
            out.putInt(ns); out.putInt(name); out.putInt(raw)
            out.putShort(8); out.put(0); out.put(kind.toByte()); out.putInt(data)
        }
    }

    /** One chunk of the node stream: an element, a namespace, or cdata. */
    class Node(var kind: Int) {
        var line = 0
        var comment = NO_ENTRY
        // start/end namespace
        var prefix = 0
        var uri = 0
        // elements
        var ns = NO_ENTRY
        var name = NO_ENTRY
        var idIndex = 0
        var classIndex = 0
        var styleIndex = 0
        var attributes = ArrayList<Attribute>()
        var tail = ByteArray(0) // cdata's Res_value, kept verbatim

        fun pack(): ByteArray {
            val body = java.io.ByteArrayOutputStream()
            val tmp = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            fun ints(a: Int, b: Int) {
                tmp.clear(); tmp.putInt(a); tmp.putInt(b); body.write(tmp.array())
            }
            when (kind) {
                RES_XML_START_NAMESPACE, RES_XML_END_NAMESPACE -> ints(prefix, uri)
                RES_XML_START_ELEMENT -> {
                    ints(ns, name)
                    tmp.clear()
                    tmp.putShort(20); tmp.putShort(20); tmp.putShort(attributes.size.toShort())
                    tmp.putShort(idIndex.toShort())
                    body.write(tmp.array(), 0, 8)
                    tmp.clear()
                    tmp.putShort(classIndex.toShort()); tmp.putShort(styleIndex.toShort())
                    body.write(tmp.array(), 0, 4)
                    val attr = ByteBuffer.allocate(attributes.size * 20).order(ByteOrder.LITTLE_ENDIAN)
                    for (a in attributes) a.pack(attr)
                    body.write(attr.array())
                }
                RES_XML_END_ELEMENT -> ints(ns, name)
                RES_XML_CDATA -> {
                    tmp.clear(); tmp.putInt(name); body.write(tmp.array(), 0, 4)
                    body.write(tail, 0, tail.size)
                }
                else -> throw AxmlError("cannot write node 0x${kind.toString(16)}")
            }
            val size = 16 + body.size()
            val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            head.putShort(kind.toShort()); head.putShort(16); head.putInt(size)
            head.putInt(line); head.putInt(comment)
            return head.array() + body.toByteArray()
        }
    }

    // -- reading

    fun string(index: Int): String? = pool.get(index)

    fun elements(name: String): List<Node> =
        nodes.filter { it.kind == RES_XML_START_ELEMENT && pool.get(it.name) == name }

    fun attr(node: Node, name: String): Attribute? {
        for (a in node.attributes) {
            val ns = pool.get(a.ns)
            if (pool.get(a.name) == name && (ns == ANDROID_NS || ns == null)) return a
        }
        return null
    }

    fun attrString(node: Node, name: String): String? {
        val a = attr(node, name) ?: return null
        if (a.kind == TYPE_STRING) return pool.get(a.data)
        return pool.get(a.raw)
    }

    // -- editing

    /**
     * The pool index for an `android:` attribute name. The resource map runs
     * parallel to the first entries of the pool, so a name the file does not
     * use yet is inserted at the end of the map and every index above is
     * shifted up to match.
     */
    fun attributeNameIndex(name: String): Int {
        val wanted = ATTR_IDS[name] ?: throw AxmlError("unknown framework attribute: $name")
        for (i in resourceMap.indices) {
            if (resourceMap[i] == wanted) return i
        }
        val at = resourceMap.size
        pool.strings.add(at, name)
        resourceMap.add(wanted)
        shiftIndices(at)
        return at
    }

    private fun shiftIndices(at: Int) {
        fun bump(index: Int): Int = if (index != NO_ENTRY && index >= at) index + 1 else index
        for (node in nodes + pending) {
            if (node.kind == RES_XML_START_NAMESPACE || node.kind == RES_XML_END_NAMESPACE) {
                node.prefix = bump(node.prefix); node.uri = bump(node.uri); continue
            }
            node.ns = bump(node.ns); node.name = bump(node.name)
            for (a in node.attributes) {
                a.ns = bump(a.ns); a.name = bump(a.name); a.raw = bump(a.raw)
                if (a.kind == TYPE_STRING) a.data = bump(a.data)
            }
            if (node.kind == RES_XML_CDATA && node.tail.size == 8) {
                val t = wrap(node.tail)
                val kind = t.get(3).toInt() and 0xFF
                if (kind == TYPE_STRING) {
                    val data = t.getInt(4)
                    t.putInt(4, bump(data))
                }
            }
        }
    }

    /** Set an `android:` attribute, replacing it if the element has it. */
    fun setAttr(node: Node, name: String, kind: Int, data: Int, raw: Int = NO_ENTRY) {
        val nameIndex = attributeNameIndex(name)
        val nsIndex = pool.index(ANDROID_NS)
        val existing = attr(node, name)
        if (existing != null) {
            existing.ns = nsIndex; existing.name = nameIndex; existing.raw = raw
            existing.kind = kind; existing.data = data
            return
        }
        val attribute = Attribute(nsIndex, nameIndex, raw, kind, data)
        // attributes are expected in ascending resource-id order
        val mine = ATTR_IDS.getValue(name)
        var where = node.attributes.size
        for (i in node.attributes.indices) {
            val rid = ATTR_IDS[pool.get(node.attributes[i].name)] ?: continue
            if (rid > mine) { where = i; break }
        }
        node.attributes.add(where, attribute)
    }

    fun setAttrString(node: Node, name: String, value: String) {
        // the name goes in first: adding one renumbers the pool, and a value
        // index taken before that would point one string to the left
        attributeNameIndex(name)
        val index = pool.index(value)
        setAttr(node, name, TYPE_STRING, index, raw = index)
    }

    fun setAttrBool(node: Node, name: String, value: Boolean) =
        setAttr(node, name, TYPE_INT_BOOLEAN, if (value) -1 else 0)

    fun setAttrInt(node: Node, name: String, value: Int) =
        setAttr(node, name, TYPE_INT_DEC, value)

    fun setAttrRef(node: Node, name: String, resId: Int) =
        setAttr(node, name, TYPE_REFERENCE, resId)

    fun makeElement(name: String): Node {
        val node = Node(RES_XML_START_ELEMENT)
        node.name = pool.index(name)
        pending.add(node)
        return node
    }

    fun closeElement(start: Node): Node {
        val node = Node(RES_XML_END_ELEMENT)
        node.ns = start.ns
        node.name = start.name
        pending.add(node)
        return node
    }

    /** Put a subtree in as the last child of [parent]. */
    fun insertInto(parent: Node, subtree: List<Node>) {
        var depth = 0
        val at = nodes.indexOf(parent)
        for (i in at + 1 until nodes.size) {
            val node = nodes[i]
            if (node.kind == RES_XML_START_ELEMENT) depth++
            else if (node.kind == RES_XML_END_ELEMENT) {
                if (depth == 0) {
                    nodes.addAll(i, subtree)
                    pending.removeAll(subtree.toSet())
                    return
                }
                depth--
            }
        }
        throw AxmlError("element has no end tag")
    }

    fun build(): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        body.write(pool.build())
        if (resourceMap.isNotEmpty()) {
            val size = 8 + 4 * resourceMap.size
            val head = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            head.putShort(RES_XML_RESOURCE_MAP.toShort()); head.putShort(8); head.putInt(size)
            for (id in resourceMap) head.putInt(id.toInt())
            body.write(head.array())
        }
        for (node in nodes) body.write(node.pack())
        val all = body.toByteArray()
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        head.putShort(RES_XML.toShort()); head.putShort(8); head.putInt(8 + all.size)
        return head.array() + all
    }

    val pending = ArrayList<Node>()

    companion object {
        const val RES_STRING_POOL = 0x0001
        const val RES_XML = 0x0003
        const val RES_XML_START_NAMESPACE = 0x0100
        const val RES_XML_END_NAMESPACE = 0x0101
        const val RES_XML_START_ELEMENT = 0x0102
        const val RES_XML_END_ELEMENT = 0x0103
        const val RES_XML_CDATA = 0x0104
        const val RES_XML_RESOURCE_MAP = 0x0180

        const val TYPE_NULL = 0x00
        const val TYPE_REFERENCE = 0x01
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val TYPE_INT_BOOLEAN = 0x12

        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val NO_ENTRY = -1 // 0xFFFFFFFF

        /** The framework attributes we write, by their fixed public ids. */
        val ATTR_IDS = mapOf(
            "theme" to 0x01010000L,
            "label" to 0x01010001L,
            "icon" to 0x01010002L,
            "name" to 0x01010003L,
            "permission" to 0x01010006L,
            "debuggable" to 0x0101000FL,
            "exported" to 0x01010010L,
            "authorities" to 0x01010018L,
            "grantUriPermissions" to 0x0101001BL,
            "minSdkVersion" to 0x0101020CL,
            "versionCode" to 0x0101021BL,
            "targetSdkVersion" to 0x01010270L,
            "value" to 0x01010024L,
            "resource" to 0x01010025L,
            "appComponentFactory" to 0x0101057AL,
            "extractNativeLibs" to 0x010104EAL,
            "usesCleartextTraffic" to 0x010104ECL,
            "enabled" to 0x0101000EL,
            "targetActivity" to 0x01010202L,
        )

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

        private fun writeLen(value: Int, utf8: Boolean): ByteArray {
            if (utf8) {
                if (value > 0x7F) return byteArrayOf((0x80 or ((value shr 8) and 0x7F)).toByte(), (value and 0xFF).toByte())
                return byteArrayOf(value.toByte())
            }
            if (value > 0x7FFF) {
                return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((0x8000 or ((value shr 16) and 0x7FFF)).toShort())
                    .putShort((value and 0xFFFF).toShort()).array()
            }
            return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
        }

        private fun writeString(value: String, utf8: Boolean): ByteArray {
            if (utf8) {
                val raw = value.toByteArray(Charsets.UTF_8)
                val chars = value.toByteArray(Charset.forName("UTF-16LE")).size / 2
                return writeLen(chars, true) + writeLen(raw.size, true) + raw + byteArrayOf(0)
            }
            val raw = value.toByteArray(Charset.forName("UTF-16LE"))
            return writeLen(raw.size / 2, false) + raw + byteArrayOf(0, 0)
        }

        fun parse(data: ByteArray): Axml {
            val buf = wrap(data)
            if (buf.getShort(0).toInt() and 0xFFFF != RES_XML) {
                throw AxmlError("not binary XML (first chunk is 0x${(buf.getShort(0).toInt() and 0xFFFF).toString(16)})")
            }
            val headerSize = buf.getShort(2).toInt() and 0xFFFF
            var pos = headerSize
            var pool: StringPool? = null
            val resourceMap = ArrayList<Long>()
            val nodes = ArrayList<Node>()
            while (pos + 8 <= data.size) {
                val ctype = buf.getShort(pos).toInt() and 0xFFFF
                val chdr = buf.getShort(pos + 2).toInt() and 0xFFFF
                val csize = buf.getInt(pos + 4)
                if (csize < 8) throw AxmlError("chunk 0x${ctype.toString(16)} at $pos has size $csize")
                when (ctype) {
                    RES_STRING_POOL -> pool = StringPool.parse(data, pos)
                    RES_XML_RESOURCE_MAP -> {
                        val count = (csize - chdr) / 4
                        for (i in 0 until count) {
                            resourceMap.add(buf.getInt(pos + chdr + i * 4).toLong() and 0xFFFFFFFFL)
                        }
                    }
                    RES_XML_START_NAMESPACE, RES_XML_END_NAMESPACE,
                    RES_XML_START_ELEMENT, RES_XML_END_ELEMENT, RES_XML_CDATA ->
                        nodes.add(parseNode(data, pos, ctype, chdr, csize))
                }
                pos += csize
            }
            return Axml(pool ?: throw AxmlError("no string pool"), resourceMap, nodes)
        }

        private fun parseNode(data: ByteArray, pos: Int, ctype: Int, chdr: Int, csize: Int): Node {
            val buf = wrap(data)
            val node = Node(ctype)
            node.line = buf.getInt(pos + 8)
            node.comment = buf.getInt(pos + 12)
            val body = pos + chdr
            when (ctype) {
                RES_XML_START_NAMESPACE, RES_XML_END_NAMESPACE -> {
                    node.prefix = buf.getInt(body); node.uri = buf.getInt(body + 4)
                }
                RES_XML_START_ELEMENT -> {
                    node.ns = buf.getInt(body); node.name = buf.getInt(body + 4)
                    val attrStart = buf.getShort(body + 8).toInt() and 0xFFFF
                    val attrSize = buf.getShort(body + 10).toInt() and 0xFFFF
                    val attrCount = buf.getShort(body + 12).toInt() and 0xFFFF
                    node.idIndex = buf.getShort(body + 14).toInt() and 0xFFFF
                    node.classIndex = buf.getShort(body + 16).toInt() and 0xFFFF
                    node.styleIndex = buf.getShort(body + 18).toInt() and 0xFFFF
                    for (i in 0 until attrCount) {
                        val at = body + attrStart + i * attrSize
                        node.attributes.add(Attribute(
                            buf.getInt(at), buf.getInt(at + 4), buf.getInt(at + 8),
                            buf.get(at + 15).toInt() and 0xFF, buf.getInt(at + 16),
                        ))
                    }
                }
                RES_XML_END_ELEMENT -> {
                    node.ns = buf.getInt(body); node.name = buf.getInt(body + 4)
                }
                RES_XML_CDATA -> {
                    node.name = buf.getInt(body)
                    node.tail = data.copyOfRange(body + 4, pos + csize)
                }
            }
            return node
        }
    }
}
