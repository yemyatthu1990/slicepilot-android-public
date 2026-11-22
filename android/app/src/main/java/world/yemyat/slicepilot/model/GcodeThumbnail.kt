package world.yemyat.slicepilot.model

import android.util.Base64
import java.io.File

object GcodeThumbnail {
    const val MARKER = "THUMBNAIL_BLOCK_START"

    private const val ROW_LENGTH = 78
    private const val ANCHOR = "; EXECUTABLE_BLOCK_START"

    /** The preview is worth reading up to this size; a bigger payload is not worth a tile. */
    private const val MAX_ENCODED_CHARS = 6 * 1024 * 1024

    /** How far into a g-code to look for a thumbnail header before giving up. */
    private const val HEADER_SCAN_LINES = 400

    /** The comment every slicer writes: `; thumbnail begin 144x144 5123`. */
    private val HEADER =
        Regex("""^;\s*thumbnail(?:\s+begin)?\s+(\d+)x(\d+)\s+(\d+)\s*$""", RegexOption.IGNORE_CASE)
    private val FOOTER = Regex("""^;\s*thumbnail\s+end\s*$""", RegexOption.IGNORE_CASE)

    /**
     * The picture embedded in [file] — the slicer's render of the print, base64'd into a
     * comment block (the same block [buildBlock] writes, and the same one Prusa and Bambu
     * emit) — or null when there is none or it is not a PNG.
     *
     * The scan stops at the first block, and only the header is read: a g-code can be
     * hundreds of megabytes, and the whole file is overkill for a 60 dp list tile.
     */
    fun readPng(file: File): ByteArray? {
        return try {
            file.bufferedReader().use { reader ->
                var header: MatchResult? = null
                var scanned = 0
                while (header == null && scanned < HEADER_SCAN_LINES) {
                    val line = reader.readLine() ?: return null
                    scanned++
                    header = HEADER.find(line.trimEnd())
                }
                val found = header ?: return null
                val declared = found.groupValues[3].toIntOrNull() ?: return null
                if (declared <= 0 || declared > MAX_ENCODED_CHARS) return null

                val encoded = StringBuilder(declared)
                while (encoded.length < declared) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trimEnd()
                    if (FOOTER.matches(trimmed) || !trimmed.startsWith(";")) break
                    encoded.append(trimmed.drop(1).trim())
                }
                val bytes =
                    runCatching { Base64.decode(encoded.toString(), Base64.NO_WRAP) }.getOrNull()
                        ?: return null
                bytes.takeIf { looksLikePng(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun looksLikePng(bytes: ByteArray): Boolean =
        bytes.size > 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()

    fun buildBlock(
        png: ByteArray,
        width: Int,
        height: Int,
    ): String {
        val encoded = Base64.encodeToString(png, Base64.NO_WRAP)
        val sb = StringBuilder(encoded.length + 256)
        sb.append("\n\n")
        sb.append("; ").append(MARKER).append('\n')
        sb.append("\n;\n")
        sb
            .append("; thumbnail begin ")
            .append(width)
            .append('x')
            .append(height)
            .append(' ')
            .append(encoded.length)
            .append('\n')
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

    fun hasThumbnail(file: File): Boolean =
        try {
            file.bufferedReader().useLines { lines ->
                lines
                    .take(HEADER_SCAN_LINES)
                    .any { it.contains(MARKER) || HEADER.matches(it.trimEnd()) }
            }
        } catch (_: Exception) {
            false
        }

    fun inject(
        file: File,
        png: ByteArray,
        width: Int,
        height: Int,
    ): Boolean {
        if (file.length() == 0L || hasThumbnail(file)) return false
        val block = buildBlock(png, width, height).toByteArray(Charsets.US_ASCII)
        val tmp = File(file.parentFile, file.name + ".thumb.tmp")
        return try {
            val anchorFound =
                try {
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
