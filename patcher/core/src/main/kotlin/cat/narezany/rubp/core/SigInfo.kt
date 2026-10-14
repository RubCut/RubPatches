package cat.narezany.rubp.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The signature an apk arrived with, as the hex of its first signing
 * certificate. LSPatch's signature bypass answers the app's own checks with
 * this, so the hex has to be the one the apk actually carried -- v2 when it
 * is there, v1 otherwise. A port of LSPatch's ApkSignatureHelper.
 */
object SigInfo {

    private val V2_MAGIC = "APK Sig Block 42".toByteArray()

    fun of(apk: File): String? = try {
        v2(apk) ?: v1(apk)
    } catch (failed: Exception) {
        v1(apk)
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            out.append(Character.forDigit((byte.toInt() shr 4) and 0xF, 16))
            out.append(Character.forDigit(byte.toInt() and 0xF, 16))
        }
        return out.toString()
    }

    private fun v2(apk: File): String? {
        RandomAccessFile(apk, "r").use { file ->
            val tail = ByteArray(24)
            file.seek(file.length() - 6)
            file.readFully(tail, 0, 6)
            var buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val cdOffset = buf.getInt(0)
            if (buf.getShort(4).toInt() != 0) return null

            file.seek(cdOffset - 16L)
            file.readFully(tail, 0, 16)
            if (!tail.copyOfRange(0, 16).contentEquals(V2_MAGIC)) return null

            file.seek(cdOffset - 24L)
            file.readFully(tail, 0, 8)
            buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val size = buf.getLong(0).toInt()

            val block = ByteArray(size + 8)
            file.seek(cdOffset.toLong() - block.size)
            file.readFully(block)
            val data = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
            if (data.getLong() != size.toLong()) return null

            var certSize = -1
            while (data.remaining() > 24) {
                val entrySize = data.getLong().toInt()
                if (data.getInt() == 0x7109871a) {
                    // signer-sequence length, signer length, signed data length
                    data.position(data.position() + 12)
                    val digestsSize = data.getInt()
                    // digests, then certificates length
                    data.position(data.position() + digestsSize + 4)
                    certSize = data.getInt()
                    break
                } else {
                    data.position(data.position() + entrySize - 4)
                }
            }
            if (certSize <= 0) return null
            val cert = ByteArray(certSize)
            data.get(cert)
            return hex(cert)
        }
    }

    private fun v1(apk: File): String? {
        return try {
            java.util.jar.JarFile(apk).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                    jar.getInputStream(entry).use { input ->
                        val block = ByteArray(1 shl 16)
                        while (input.read(block) >= 0) { /* read it whole */ }
                    }
                    val certs = entry.certificates ?: continue
                    if (certs.isNotEmpty()) return hex(certs[0].encoded)
                }
                null
            }
        } catch (failed: Throwable) {
            null
        }
    }
}
