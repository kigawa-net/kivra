package net.kigawa.kivra.agent.workdir

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** The entry kinds the preparer accepts. Anything else is refused by [TarReader] before its data is read. */
internal enum class TarKind { REGULAR, DIRECTORY }

internal class TarEntry(val name: String, val kind: TarKind, val size: Long, val mode: Int)

/**
 * A reader for the subset of tar that `git archive --format=tar` writes: ustar headers, pax extended headers (`x`, applied to the next
 * entry) and pax global headers (`g`, skipped). Checksums are verified. Everything else is refused with [WorkDirReason.ARCHIVE_ENTRY_TYPE]
 * or [WorkDirReason.ARCHIVE_INVALID]: symbolic and hard links, devices, FIFOs, GNU long names. Nothing is written here.
 */
internal class TarReader(private val input: InputStream, private val maxEntries: Int) {
    private val block = ByteArray(BLOCK)
    private var entries = 0

    /** Calls [onEntry] for each entry. The stream it gets is limited to the entry's data. Unread data is skipped afterwards. */
    fun forEachEntry(onEntry: (TarEntry, InputStream) -> Unit) {
        var paxPath: String? = null
        var paxSize: Long? = null
        while (true) {
            readFully(block)
            if (block.all { it == 0.toByte() }) return
            verifyChecksum(block)
            val type = block[TYPE_OFFSET].toInt().toChar()
            val headerSize = parseOctal(block, SIZE_OFFSET, 12)
            when (type) {
                'x' -> {
                    val pax = parsePax(readData(headerSize))
                    skip(padding(headerSize))
                    paxPath = pax["path"]
                    paxSize = pax["size"]?.let { parseDecimal(it) }
                    continue
                }

                'g' -> {
                    skip(headerSize + padding(headerSize))
                    continue
                }
            }

            val kind = when (type) {
                '0', '\u0000', '7' -> TarKind.REGULAR
                '5' -> TarKind.DIRECTORY
                else -> throw WorkDirException(WorkDirReason.ARCHIVE_ENTRY_TYPE)
            }
            val name = paxPath ?: ustarName(block)
            val size = if (kind == TarKind.REGULAR) paxSize ?: headerSize else 0L
            val mode = parseOctal(block, MODE_OFFSET, 8).toInt()
            paxPath = null
            paxSize = null

            entries++
            if (entries > maxEntries) throw WorkDirException(WorkDirReason.ARCHIVE_TOO_MANY_ENTRIES)
            val data = BoundedInput(input, size)
            onEntry(TarEntry(name, kind, size, mode), data)
            if (data.remaining > 0) skip(data.remaining)
            skip(padding(size))
        }
    }

    private fun readFully(buffer: ByteArray) {
        var off = 0
        while (off < buffer.size) {
            val n = try {
                input.read(buffer, off, buffer.size - off)
            } catch (e: IOException) {
                throw WorkDirException(WorkDirReason.ARCHIVE_TRUNCATED)
            }
            if (n < 0) throw WorkDirException(WorkDirReason.ARCHIVE_TRUNCATED)
            off += n
        }
    }

    private fun readData(size: Long): ByteArray {
        if (size < 0 || size > MAX_PAX_BYTES) throw WorkDirException(WorkDirReason.ARCHIVE_INVALID)
        val data = ByteArray(size.toInt())
        readFully(data)
        return data
    }

    private fun skip(count: Long) {
        var left = count
        val scratch = ByteArray(BLOCK)
        while (left > 0) {
            val want = minOf(left, scratch.size.toLong()).toInt()
            val n = input.read(scratch, 0, want)
            if (n < 0) throw WorkDirException(WorkDirReason.ARCHIVE_TRUNCATED)
            left -= n
        }
    }

    private fun verifyChecksum(header: ByteArray) {
        val expected = parseOctal(header, CHECKSUM_OFFSET, 8)
        var sum = 0L
        for (i in header.indices) {
            sum += if (i in CHECKSUM_OFFSET until CHECKSUM_OFFSET + 8) ' '.code.toLong() else (header[i].toLong() and 0xFF)
        }
        if (sum != expected) throw WorkDirException(WorkDirReason.ARCHIVE_INVALID)
    }

    private fun ustarName(header: ByteArray): String {
        val name = cString(header, 0, 100)
        val magic = String(header, MAGIC_OFFSET, 5, StandardCharsets.US_ASCII)
        if (magic != "ustar") return name
        val prefix = cString(header, 345, 155)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun cString(buffer: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && buffer[end] != 0.toByte()) end++
        return String(buffer, offset, end - offset, StandardCharsets.UTF_8)
    }

    private fun padding(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

    private companion object {
        const val BLOCK = 512
        const val MAX_PAX_BYTES = 64L * 1024
        const val TYPE_OFFSET = 156
        const val SIZE_OFFSET = 124
        const val MODE_OFFSET = 100
        const val CHECKSUM_OFFSET = 148
        const val MAGIC_OFFSET = 257

        /** A NUL-or-space padded octal field. A base-256 field (high bit set) is refused. */
        fun parseOctal(buffer: ByteArray, offset: Int, length: Int): Long {
            if (buffer[offset].toInt() and 0x80 != 0) throw WorkDirException(WorkDirReason.ARCHIVE_INVALID)
            var value = 0L
            var seenDigit = false
            for (i in offset until offset + length) {
                val c = buffer[i].toInt()
                if (c == 0 || c == ' '.code) {
                    if (seenDigit) break
                    continue
                }
                if (c < '0'.code || c > '7'.code) throw WorkDirException(WorkDirReason.ARCHIVE_INVALID)
                seenDigit = true
                value = value * 8 + (c - '0'.code)
            }
            return value
        }

        fun parseDecimal(text: String): Long {
            if (text.isEmpty() || text.length > 19 || !text.all { it in '0'..'9' }) {
                throw WorkDirException(WorkDirReason.ARCHIVE_INVALID)
            }
            return text.toLong()
        }

        /** pax records: `<length> <key>=<value>\n`. Only the last value of a key is kept. */
        fun parsePax(data: ByteArray): Map<String, String> {
            val result = HashMap<String, String>()
            var pos = 0
            while (pos < data.size) {
                var space = pos
                while (space < data.size && data[space] != ' '.code.toByte()) space++
                if (space == pos || space >= data.size) throw WorkDirException(WorkDirReason.ARCHIVE_INVALID)
                val length = parseDecimal(String(data, pos, space - pos, StandardCharsets.US_ASCII)).toInt()
                if (length <= space - pos + 1 || pos + length > data.size) throw WorkDirException(WorkDirReason.ARCHIVE_INVALID)
                if (data[pos + length - 1] != '\n'.code.toByte()) throw WorkDirException(WorkDirReason.ARCHIVE_INVALID)
                val record = String(data, space + 1, pos + length - 1 - (space + 1), StandardCharsets.UTF_8)
                val eq = record.indexOf('=')
                if (eq <= 0) throw WorkDirException(WorkDirReason.ARCHIVE_INVALID)
                result[record.substring(0, eq)] = record.substring(eq + 1)
                pos += length
            }
            return result
        }
    }
}

/** The data of one entry: at most [remaining] bytes are read from the archive. A short archive is detected by the caller. */
internal class BoundedInput(input: InputStream, size: Long) : FilterInputStream(input) {
    var remaining: Long = size
        private set

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun skip(n: Long): Long {
        val k = minOf(n, remaining)
        val skipped = super.skip(k)
        remaining -= skipped
        return skipped
    }

    override fun close() {
        // The archive stream belongs to the caller.
    }
}
