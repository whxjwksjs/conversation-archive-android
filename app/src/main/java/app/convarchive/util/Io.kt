package app.convarchive.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Small I/O helpers. Pure JVM, no android.* imports. */
object Io {
    fun InputStream.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Read every entry of a zip archive as name -> bytes. */
    fun readZipEntries(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry: ZipEntry? = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out += entry.name to zin.readAll()
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        return out
    }

    /** Build a zip archive from name -> bytes entries. */
    fun writeZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zout ->
            for ((name, bytes) in entries) {
                zout.putNextEntry(ZipEntry(name))
                zout.write(bytes)
                zout.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
