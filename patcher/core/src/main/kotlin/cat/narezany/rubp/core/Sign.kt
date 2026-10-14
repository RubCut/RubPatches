package cat.narezany.rubp.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.ArrayList
import java.util.Base64

/**
 * Signing an apk the v1 way: a MANIFEST.MF with every entry's digest, a
 * CERT.SF with the manifest's, and a CERT.RSA with a PKCS#7 over it.
 *
 * The key is the one bundled with the patcher, so a mod one person patches
 * installs over a mod another one patched. The PKCS#7 is written out by hand:
 * the phone has no BouncyCastle to lean on, and the desktop build checks the
 * result with apksigner.
 */
object Sign {

    private const val KEYSTORE = "rubp.p12"
    // the keystore password is key material, not branding: renaming it breaks
    // every install that updates over a previous build. Hands off.
    private const val PASSWORD = "margyt"
    private const val ALIAS = "margy"

    class Key(val cert: X509Certificate, val private: java.security.PrivateKey)

    /** The signing key, loaded from the patcher's own resources. */
    fun key(): Key {
        val stream = Sign::class.java.classLoader?.getResourceAsStream(KEYSTORE)
            ?: throw IOException("the signing key is missing from this build")
        val store = KeyStore.getInstance("PKCS12")
        stream.use { store.load(it, PASSWORD.toCharArray()) }
        val entry = store.getEntry(ALIAS, KeyStore.PasswordProtection(PASSWORD.toCharArray()))
            as KeyStore.PrivateKeyEntry
        return Key(entry.certificate as X509Certificate, entry.privateKey)
    }

    /**
     * Add the v1 signature entries to [zip]. [contents] gives the uncompressed
     * bytes of any entry, so entries whose data is shared with the nested
     * original can be hashed too.
     */
    fun sign(zip: ApkZip, key: Key, contents: (ApkZip.Entry) -> ByteArray) {
        zip.dropSignature()

        val digest = MessageDigest.getInstance("SHA-256")
        val main = linkedMapOf("Manifest-Version" to "1.0", "Created-By" to "RubP")
        val sections = java.util.LinkedHashMap<String, LinkedHashMap<String, String>>()
        for (entry in zip.entries) {
            if (isSignatureEntry(entry.name) || entry.name.endsWith("/")) continue
            digest.reset()
            val hash = digest.digest(contents(entry))
            sections[entry.name] = linkedMapOf(
                "SHA-256-Digest" to Base64.getEncoder().encodeToString(hash))
        }
        val manifestBytes = render(main, sections)

        val sfMain = linkedMapOf(
            "Signature-Version" to "1.0",
            "Created-By" to "RubP",
            "SHA-256-Digest-Manifest" to Base64.getEncoder().encodeToString(digest.digest(manifestBytes)),
        )
        val sfSections = java.util.LinkedHashMap<String, LinkedHashMap<String, String>>()
        for ((name, section) in sections) {
            val part = render(null, linkedMapOf(name to section), firstIsName = name)
            sfSections[name] = linkedMapOf(
                "SHA-256-Digest" to Base64.getEncoder().encodeToString(digest.digest(part)))
        }
        val sfBytes = render(sfMain, sfSections)

        zip.add("META-INF/MANIFEST.MF", manifestBytes)
        zip.add("META-INF/CERT.SF", sfBytes)
        zip.add("META-INF/CERT.RSA", pkcs7(sfBytes, key), ApkZip.STORED)
    }

    private fun isSignatureEntry(name: String): Boolean {
        val upper = name.uppercase()
        return upper == "META-INF/MANIFEST.MF" || (upper.startsWith("META-INF/") &&
            (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")))
    }

    // -- manifest text, wrapped at 72 bytes the way the spec asks

    private fun render(
        main: LinkedHashMap<String, String>?,
        sections: LinkedHashMap<String, LinkedHashMap<String, String>>,
        firstIsName: String? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        if (firstIsName != null) {
            writeLine(out, "Name", firstIsName)
            for ((key, value) in sections.getValue(firstIsName)) writeLine(out, key, value)
            out.write('\r'.code); out.write('\n'.code)
            return out.toByteArray()
        }
        if (main != null) {
            for ((key, value) in main) writeLine(out, key, value)
            out.write('\r'.code); out.write('\n'.code)
        }
        for ((name, section) in sections) {
            writeLine(out, "Name", name)
            for ((key, value) in section) writeLine(out, key, value)
            out.write('\r'.code); out.write('\n'.code)
        }
        return out.toByteArray()
    }

    private fun writeLine(out: ByteArrayOutputStream, key: String, value: String) {
        var line = "$key: $value".toByteArray(Charsets.UTF_8)
        var first = true
        while (true) {
            val room = if (first) 72 else 71
            if (line.size <= room) {
                if (!first) out.write(' '.code)
                out.write(line)
                break
            }
            if (!first) out.write(' '.code)
            out.write(line, 0, room)
            line = line.copyOfRange(room, line.size)
            first = false
            out.write('\r'.code); out.write('\n'.code)
        }
        out.write('\r'.code); out.write('\n'.code)
    }

    // -- DER, the smallest useful piece of it

    private class Der {
        val out = ByteArrayOutputStream()

        private fun tagged(tag: Int, body: ByteArray): ByteArray {
            val result = ByteArrayOutputStream()
            result.write(tag)
            val length = body.size
            when {
                length < 128 -> result.write(length)
                length < 256 -> { result.write(0x81); result.write(length) }
                length < 65536 -> { result.write(0x82); result.write(length shr 8); result.write(length and 0xFF) }
                else -> {
                    result.write(0x83)
                    result.write(length shr 16 and 0xFF); result.write(length shr 8 and 0xFF)
                    result.write(length and 0xFF)
                }
            }
            result.write(body)
            return result.toByteArray()
        }

        fun sequence(body: ByteArray) = tagged(0x30, body)
        fun set(body: ByteArray) = tagged(0x31, body)
        fun integer(value: Int): ByteArray {
            var bytes = byteArrayOf(
                (value shr 24).toByte(), (value shr 16).toByte(),
                (value shr 8).toByte(), value.toByte())
            var start = 0
            while (start < bytes.size - 1 && bytes[start] == 0.toByte() && bytes[start + 1] >= 0) start++
            bytes = bytes.copyOfRange(start, bytes.size)
            return tagged(0x02, bytes)
        }

        fun integer(raw: ByteArray): ByteArray {
            var bytes = raw
            while (bytes.size > 1 && bytes[0] == 0.toByte() && bytes[1] >= 0) bytes = bytes.copyOfRange(1, bytes.size)
            if (bytes[0] < 0) bytes = byteArrayOf(0) + bytes
            return tagged(0x02, bytes)
        }

        fun oid(id: String): ByteArray {
            val parts = id.split('.').map { it.toLong() }
            val body = ByteArrayOutputStream()
            body.write((parts[0] * 40 + parts[1]).toInt())
            for (part in parts.drop(2)) {
                var value = part
                val stack = ArrayList<Int>()
                stack.add((value and 0x7F).toInt())
                value = value shr 7
                while (value > 0) {
                    stack.add((value and 0x7F).toInt() or 0x80)
                    value = value shr 7
                }
                for (i in stack.indices.reversed()) body.write(stack[i])
            }
            return tagged(0x06, body.toByteArray())
        }

        fun null_() = byteArrayOf(0x05, 0x00)
        fun octetString(body: ByteArray) = tagged(0x04, body)
        fun context(number: Int, body: ByteArray, explicit: Boolean) =
            tagged(0xA0 + number or if (explicit) 0x20 else 0x00, body)
        fun algorithm(id: String) = sequence(oid(id) + null_())
    }

    /**
     * A detached PKCS#7 SignedData over [content], signed by [key]'s RSA key.
     * Authenticated attributes carry the content type and the message digest;
     * the signature itself is over their SET OF encoding, as the RFC asks.
     */
    fun pkcs7(content: ByteArray, key: Key): ByteArray {
        val der = Der()
        val sha256 = "2.16.840.1.101.3.4.2.1"
        val rsa = "1.2.840.113549.1.1.1"
        val dataOid = "1.2.840.113549.1.7.1"
        val signedDataOid = "1.2.840.113549.1.7.2"

        val digest = MessageDigest.getInstance("SHA-256").digest(content)

        // the two authenticated attributes, in DER SET order (contentType sorts first)
        val attrContentType = der.sequence(
            der.oid("1.2.840.113549.1.9.3") + der.set(der.oid(dataOid)))
        val attrMessageDigest = der.sequence(
            der.oid("1.2.840.113549.1.9.4") + der.set(der.octetString(digest)))
        val attributesForSigning = der.set(attrContentType + attrMessageDigest)
        val attributesInPlace = der.context(0, attrContentType + attrMessageDigest, explicit = false)

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(key.private)
        signer.update(attributesForSigning)
        val signed = signer.sign()

        val certDer = key.cert.encoded
        val (issuer, serial) = issuerAndSerial(certDer)

        val signerInfo = der.sequence(
            der.integer(1) +
                der.sequence(issuer + der.integer(serial)) +
                der.algorithm(sha256) +
                attributesInPlace +
                der.algorithm(rsa) +
                der.octetString(signed))

        val signedData = der.sequence(
            der.integer(1) +
                der.set(der.algorithm(sha256)) +
                der.sequence(der.oid(dataOid)) +
                der.context(0, certDer, explicit = false) +
                der.set(signerInfo))

        return der.sequence(der.oid(signedDataOid) + der.context(0, signedData, explicit = true))
    }

    /** The certificate's issuer name (whole TLV) and serial number (raw bytes). */
    private fun issuerAndSerial(cert: ByteArray): Pair<ByteArray, ByteArray> {
        var pos = 0

        /** Reads one TLV header; returns (tagStart, bodyStart, bodyEnd) and moves past the body. */
        fun tlv(): Triple<Int, Int, Int> {
            val start = pos
            pos++ // tag
            var length = cert[pos].toInt() and 0xFF
            pos++
            if (length and 0x80 != 0) {
                val count = length and 0x7F
                length = 0
                repeat(count) { length = (length shl 8) or (cert[pos++].toInt() and 0xFF) }
            }
            val bodyStart = pos
            pos += length
            return Triple(start, bodyStart, pos)
        }

        val certBody = tlv()                     // Certificate
        pos = certBody.second                    // walk into it
        val tbs = tlv()                          // TBSCertificate
        pos = tbs.second                         // walk into it
        var field = tlv()                        // [0] version, or the serial
        if (cert[field.first].toInt() and 0xFF == 0xA0) field = tlv()
        val serial = cert.copyOfRange(field.second, field.third)
        tlv()                                    // signature algorithm
        val issuer = tlv()
        return cert.copyOfRange(issuer.first, issuer.third) to serial
    }

    // ------------------------------------------ APK signature scheme v2

    private val V2_MAGIC = "APK Sig Block 42".toByteArray()
    private const val V2_ID = 0x7109871a
    private const val ALG_SHA256_RSA = 0x0103

    private fun lp(bytes: ByteArray): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array() + bytes

    /**
     * Sign [apk] with APK Signature Scheme v2: the signing block goes between
     * the zip's contents and its central directory, and the EOCD's central
     * directory offset moves past it. Rebuilt through a sibling temp file.
     */
    fun v2(apk: File, key: Key) {
        val cdOffset: Long
        val cdSize: Long
        val eocd: ByteArray
        RandomAccessFile(apk, "r").use { file ->
            val tail = ByteArray(minOf(file.length(), 66000).toInt())
            file.seek(file.length() - tail.size)
            file.readFully(tail)
            var at = -1
            for (i in tail.size - 22 downTo 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4B.toByte() &&
                    tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()) {
                    // the real EOCD ends exactly at the end of the file
                    val comment = (tail[i + 20].toInt() and 0xFF) or
                        ((tail[i + 21].toInt() and 0xFF) shl 8)
                    if (i + 22 + comment == tail.size) { at = i; break }
                }
            }
            if (at < 0) throw IOException("not a zip")
            val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            cdSize = buf.getInt(at + 12).toLong() and 0xFFFFFFFFL
            cdOffset = buf.getInt(at + 16).toLong() and 0xFFFFFFFFL
            val commentLen = buf.getShort(at + 20).toInt() and 0xFFFF
            eocd = tail.copyOfRange(at, at + 22 + commentLen)
        }

        /**
         * The one content digest: chunk digests of all three sections in order
         * (contents before the block, central directory, EOCD). Verifiers
         * digest the EOCD with its central directory offset set to the signing
         * block's start — which is exactly the offset the EOCD already stores.
         */
        fun contentDigest(): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            val chunks = ArrayList<ByteArray>()
            RandomAccessFile(apk, "r").use { file ->
                for (section in 0..2) {
                    if (section < 2) {
                        var left = if (section == 0) cdOffset else cdSize
                        file.seek(if (section == 0) 0L else cdOffset)
                        val block = ByteArray(1 shl 20)
                        while (left > 0) {
                            val want = minOf(left, block.size.toLong()).toInt()
                            file.readFully(block, 0, want)
                            digest.reset()
                            digest.update(0xa5.toByte())
                            digest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(want).array())
                            digest.update(block, 0, want)
                            chunks.add(digest.digest())
                            left -= want
                        }
                    } else {
                        digest.reset()
                        digest.update(0xa5.toByte())
                        digest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(eocd.size).array())
                        digest.update(eocd)
                        chunks.add(digest.digest())
                    }
                }
            }
            digest.reset()
            digest.update(0x5a.toByte())
            digest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunks.size).array())
            for (chunk in chunks) digest.update(chunk)
            return digest.digest()
        }

        fun buildBlock(): ByteArray {
            // one digest entry per algorithm, over all three sections at once
            val digestEntry = lp(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(ALG_SHA256_RSA).array() + lp(contentDigest()))
            val signedDataValue = lp(digestEntry) + lp(lp(key.cert.encoded)) + lp(ByteArray(0))

            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(key.private)
            signer.update(signedDataValue)
            val signatureEntry = lp(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(ALG_SHA256_RSA).array() + lp(signer.sign()))

            val signerBlock = lp(lp(signedDataValue) + lp(signatureEntry) + lp(key.cert.publicKey.encoded))
            // the one 64-bit length in the whole block: a pair's
            val pairValue = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(V2_ID).array() + lp(signerBlock)
            val pair = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(pairValue.size.toLong()).array() + pairValue
            val body = pair.size + 8 + V2_MAGIC.size
            val whole = ByteBuffer.allocate(body + 8).order(ByteOrder.LITTLE_ENDIAN)
            whole.putLong(body.toLong())
            whole.put(pair)
            whole.putLong(body.toLong())
            whole.put(V2_MAGIC)
            return whole.array()
        }

        val block = buildBlock()

        val signed = File(apk.parentFile, apk.name + ".signing")
        RandomAccessFile(apk, "r").use { file ->
            signed.outputStream().buffered(1 shl 20).use { out ->
                val chunk = ByteArray(1 shl 20)
                var left = cdOffset
                while (left > 0) {
                    val want = minOf(left, chunk.size.toLong()).toInt()
                    file.readFully(chunk, 0, want)
                    out.write(chunk, 0, want)
                    left -= want
                }
                out.write(block)
                left = cdSize
                while (left > 0) {
                    val want = minOf(left, chunk.size.toLong()).toInt()
                    file.readFully(chunk, 0, want)
                    out.write(chunk, 0, want)
                    left -= want
                }
                val fixed = eocd.copyOf()
                ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(16, (cdOffset + block.size).toInt())
                out.write(fixed)
            }
        }
        if (!signed.renameTo(apk)) {
            apk.delete()
            if (!signed.renameTo(apk)) throw IOException("could not put the signed apk in place")
        }
    }
}
