package world.yemyat.slicepilot.model

import android.util.Base64
import java.io.File

object GcodeThumbnail {

    const val MARKER = "THUMBNAIL_BLOCK_START"

    private const val ROW_LENGTH = 78
    private const val ANCHOR = "; EXECUTABLE_BLOCK_START"

    fun buildBlock(png: ByteArray, width: Int, height: Int): String {
        val encoded = Base64.encodeToString(png, Base64.NO_WRAP)
        val sb = StringBuilder(encoded.length + 256)
        sb.append("\n\n")
        sb.append("; ").append(MARKER).append('\n')
        sb.append("\n;\n")
        sb.append("; thumbnail begin ").append(width).append('x').append(height).append(' ').append(encoded.length).append('\n')
        var i = 0
        while (i < encoded.length) {
            val end = minOf(i + ROW_LENGTH, encoded.length)
            sb.append("; ").append(encoded, i, end).append('\n')
            i = end
        }
        sb.append("; thumbnail end\n")
        sb.append("; THUMBNAIL_BLOCK_END\n\n")
        return sb.toString()
    }

    fun hasThumbnail(file: File): Boolean {
        return try {
            file.bufferedReader().useLines { lines ->
                lines.take(80).any { it.contains(MARKER) }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun inject(file: File, png: ByteArray, width: Int, height: Int): Boolean {
        if (file.length() == 0L || hasThumbnail(file)) return false
        val block = buildBlock(png, width, height).toByteArray(Charsets.US_ASCII)
        val tmp = File(file.parentFile, file.name + ".thumb.tmp")
        return try {
            val anchorFound = try {
                file.bufferedReader().useLines { lines ->
                    lines.any { it.startsWith(ANCHOR) }
                }
            } catch (_: Exception) {
                false
            }

            tmp.outputStream().buffered().use { raw ->
                val out = java.io.BufferedOutputStream(raw, 1 shl 16)
                var inserted = !anchorFound
                if (inserted) out.write(block)
                file.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!inserted && line.startsWith(ANCHOR)) {
                            out.write(block)
                            inserted = true
                        }
                        out.write(line.toByteArray(Charsets.UTF_8))
                        out.write('\n'.code)
                    }
                }
                out.flush()
            }

            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }
}
