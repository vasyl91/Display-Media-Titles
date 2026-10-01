package vasyl.titles.widget

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Reads title / artist / album and the embedded cover from audio file tags.
 *
 * Supported: ID3v2.2 / 2.3 / 2.4 (synchsafe sizes, unsynchronisation, extended header, compressed
 * frames, APIC and PIC with JPEG or PNG), APEv2 and ID3v1.
 * Text is taken from ID3v2 first, missing fields are filled from APEv2 and then ID3v1.
 *
 * The previous implementation read the file byte by byte, ignored ID3v2.4 synchsafe sizes, the
 * extended header and unsynchronisation, and only recognised JPEG covers.
 */
class Lrc {

    fun getId3Info(strFile: String?): Id3Info {
        val info = Id3Info()
        if (strFile.isNullOrBlank()) return info
        val file = File(strFile)
        if (!file.isFile) return info

        try {
            RandomAccessFile(file, "r").use { raf ->
                val fileLength = raf.length()
                val v2 = readId3v2(raf, fileLength)

                val needApe = v2 == null || v2.picture == null || !v2.hasAllText()
                val ape = if (needApe) readApeV2(raf, fileLength) else null

                val title = firstNonBlank(v2?.title, ape?.title)
                val artist = firstNonBlank(v2?.artist, v2?.albumArtist, ape?.artist, ape?.albumArtist)
                val album = firstNonBlank(v2?.album, ape?.album)
                val v1 = if (title == null || artist == null || album == null) readId3v1(raf, fileLength) else null

                info.strTitle = title ?: v1?.title.orEmpty()
                info.strArtist = artist ?: v1?.artist.orEmpty()
                info.strAlbum = album ?: v1?.album.orEmpty()
                info.dataPic = v2?.picture ?: ape?.picture
            }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read tags of $strFile", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Malformed tags in $strFile", e)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Tag too large in $strFile", e)
        }
        return info
    }

    // ---------------------------------------------------------------------------------------------
    // ID3v2
    // ---------------------------------------------------------------------------------------------

    private fun readId3v2(raf: RandomAccessFile, fileLength: Long): TagData? {
        if (fileLength < ID3V2_HEADER_SIZE) return null
        raf.seek(0)
        val header = ByteArray(ID3V2_HEADER_SIZE)
        raf.readFully(header)
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return null
        }
        val major = header[3].toInt() and 0xFF
        if (major !in 2..4) return null
        val flags = header[5].toInt() and 0xFF
        val tagSize = synchsafe(header, 6)
        if (tagSize <= 0) return null

        val readSize = minOf(tagSize.toLong(), fileLength - ID3V2_HEADER_SIZE, MAX_TAG_BYTES.toLong()).toInt()
        if (readSize <= 0) return null
        var data = ByteArray(readSize)
        raf.readFully(data)

        val tagUnsynchronised = flags and 0x80 != 0
        // Up to v2.3 the whole tag (after the header) is unsynchronised; v2.4 does it per frame.
        if (tagUnsynchronised && major < 4) data = removeUnsynchronisation(data)

        var pos = 0
        if (flags and 0x40 != 0) {
            if (major == 2) return null // v2.2: bit 6 means "compressed tag", no scheme was ever defined
            if (data.size < 4) return null
            // v2.3: size excludes its own 4 bytes, v2.4: synchsafe size includes them.
            val extendedSize = if (major == 3) int32(data, 0) + 4 else synchsafe(data, 0)
            if (extendedSize < 4 || extendedSize > data.size) return null
            pos = extendedSize
        }

        return if (major == 2) parseFramesV22(data, pos) else parseFramesV23(data, pos, major, tagUnsynchronised)
    }

    private fun parseFramesV22(data: ByteArray, start: Int): TagData {
        val result = TagData()
        val pictures = ArrayList<Picture>()
        var pos = start
        while (pos + 6 <= data.size) {
            if (data[pos] == 0.toByte()) break // padding
            if (!isValidFrameId(data, pos, 3)) break
            val id = String(data, pos, 3, StandardCharsets.ISO_8859_1)
            val size = int24(data, pos + 3)
            val bodyStart = pos + 6
            if (size <= 0 || bodyStart + size > data.size) break
            handleFrame(id, data.copyOfRange(bodyStart, bodyStart + size), result, pictures)
            pos = bodyStart + size
        }
        result.picture = choosePicture(pictures)
        return result
    }

    private fun parseFramesV23(data: ByteArray, start: Int, major: Int, tagUnsynchronised: Boolean): TagData {
        val result = TagData()
        val pictures = ArrayList<Picture>()
        var pos = start
        while (pos + 10 <= data.size) {
            if (data[pos] == 0.toByte()) break // padding
            if (!isValidFrameId(data, pos, 4)) break
            val id = String(data, pos, 4, StandardCharsets.ISO_8859_1)
            val size = if (major == 4) frameSizeV24(data, pos) else int32(data, pos + 4)
            val formatFlags = data[pos + 9].toInt() and 0xFF
            val bodyStart = pos + 10
            if (size <= 0 || bodyStart + size > data.size) break
            pos = bodyStart + size

            if (id != "TIT2" && id != "TPE1" && id != "TPE2" && id != "TALB" && id != "APIC") continue
            var body = data.copyOfRange(bodyStart, bodyStart + size)

            if (major == 3) {
                val compressed = formatFlags and 0x80 != 0
                val encrypted = formatFlags and 0x40 != 0
                val grouped = formatFlags and 0x20 != 0
                if (encrypted) continue
                val skip = (if (compressed) 4 else 0) + (if (grouped) 1 else 0)
                if (skip >= body.size) continue
                if (skip > 0) body = body.copyOfRange(skip, body.size)
                if (compressed) body = inflate(body) ?: continue
            } else {
                val grouped = formatFlags and 0x40 != 0
                val compressed = formatFlags and 0x08 != 0
                val encrypted = formatFlags and 0x04 != 0
                val unsynchronised = formatFlags and 0x02 != 0 || tagUnsynchronised
                val hasDataLength = formatFlags and 0x01 != 0
                if (encrypted) continue
                if (unsynchronised) body = removeUnsynchronisation(body)
                val skip = (if (grouped) 1 else 0) + (if (hasDataLength) 4 else 0)
                if (skip >= body.size) continue
                if (skip > 0) body = body.copyOfRange(skip, body.size)
                if (compressed) body = inflate(body) ?: continue
            }
            handleFrame(id, body, result, pictures)
        }
        result.picture = choosePicture(pictures)
        return result
    }

    /** ID3v2.4 sizes are synchsafe, but some encoders (old iTunes) wrote plain integers. */
    private fun frameSizeV24(data: ByteArray, framePos: Int): Int {
        val sizeOffset = framePos + 4
        val plain = int32(data, sizeOffset)
        val notSynchsafe = (0..3).any { data[sizeOffset + it].toInt() and 0x80 != 0 }
        if (notSynchsafe) return plain
        val safe = synchsafe(data, sizeOffset)
        if (safe == plain || plain < 0) return safe
        val next = framePos + 10
        return if (!isFrameBoundary(data, next + safe) && isFrameBoundary(data, next + plain)) plain else safe
    }

    private fun isFrameBoundary(data: ByteArray, pos: Int): Boolean = when {
        pos == data.size -> true
        pos < 0 || pos > data.size -> false
        data[pos] == 0.toByte() -> true
        else -> pos + 4 <= data.size && isValidFrameId(data, pos, 4)
    }

    private fun handleFrame(id: String, body: ByteArray, result: TagData, pictures: MutableList<Picture>) {
        when (id) {
            "TIT2", "TT2" -> if (result.title == null) result.title = decodeTextFrame(body)
            "TPE1", "TP1" -> if (result.artist == null) result.artist = decodeTextFrame(body)
            "TPE2", "TP2" -> if (result.albumArtist == null) result.albumArtist = decodeTextFrame(body)
            "TALB", "TAL" -> if (result.album == null) result.album = decodeTextFrame(body)
            "APIC" -> parseApic(body)?.let(pictures::add)
            "PIC" -> parsePic(body)?.let(pictures::add)
        }
    }

    private fun decodeTextFrame(body: ByteArray): String? {
        if (body.size < 2) return null
        val encoding = body[0].toInt() and 0xFF
        // v2.4 may contain several null separated values: only the first one is used.
        val end = terminatorIndex(body, 1, encoding)
        return decode(body, 1, end, encoding).trim().ifEmpty { null }
    }

    /** APIC: encoding, MIME type, picture type, description, picture data. */
    private fun parseApic(body: ByteArray): Picture? {
        if (body.size < 4) return null
        val encoding = body[0].toInt() and 0xFF
        var mimeEnd = 1
        while (mimeEnd < body.size && body[mimeEnd] != 0.toByte()) mimeEnd++
        if (mimeEnd + 2 > body.size) return null
        val pictureType = body[mimeEnd + 1].toInt() and 0xFF
        val descriptionStart = mimeEnd + 2
        val dataStart = afterTerminator(body, descriptionStart, encoding)
        return extractPicture(body, descriptionStart, dataStart, pictureType)
    }

    /** PIC (v2.2): encoding, 3 character image format, picture type, description, picture data. */
    private fun parsePic(body: ByteArray): Picture? {
        if (body.size < 6) return null
        val encoding = body[0].toInt() and 0xFF
        val pictureType = body[4].toInt() and 0xFF
        val dataStart = afterTerminator(body, 5, encoding)
        return extractPicture(body, 5, dataStart, pictureType)
    }

    private fun extractPicture(body: ByteArray, descriptionStart: Int, dataStart: Int, type: Int): Picture? {
        var start = dataStart
        if (start >= body.size || !isImageSignature(body, start)) {
            // Broken description terminators are common: look for the image signature instead.
            start = findImageSignature(body, descriptionStart) ?: start
        }
        if (start >= body.size) return null
        return Picture(type, body.copyOfRange(start, body.size))
    }

    private fun choosePicture(pictures: List<Picture>): ByteArray? =
        (pictures.firstOrNull { it.type == PICTURE_TYPE_FRONT_COVER } ?: pictures.firstOrNull())?.data

    // ---------------------------------------------------------------------------------------------
    // APEv2
    // ---------------------------------------------------------------------------------------------

    private fun readApeV2(raf: RandomAccessFile, fileLength: Long): TagData? {
        // The APE footer is either at the very end or right before an ID3v1 tag.
        for (footerPos in longArrayOf(fileLength - APE_FOOTER_SIZE, fileLength - ID3V1_SIZE - APE_FOOTER_SIZE)) {
            if (footerPos < 0) continue
            raf.seek(footerPos)
            val footer = ByteArray(APE_FOOTER_SIZE)
            raf.readFully(footer)
            if (!startsWith(footer, APE_PREAMBLE)) continue

            val tagSize = int32Le(footer, 12) // items + footer, header excluded
            val itemCount = int32Le(footer, 16)
            val itemsSize = tagSize - APE_FOOTER_SIZE
            if (itemsSize <= 0 || itemsSize > MAX_TAG_BYTES || itemsSize > footerPos) return null

            raf.seek(footerPos - itemsSize)
            val items = ByteArray(itemsSize)
            raf.readFully(items)
            return parseApeItems(items, itemCount)
        }
        return null
    }

    private fun parseApeItems(items: ByteArray, itemCount: Int): TagData {
        val result = TagData()
        var pos = 0
        repeat(itemCount.coerceIn(0, 1024)) {
            if (pos + 8 > items.size) return result
            val valueSize = int32Le(items, pos)
            val itemFlags = int32Le(items, pos + 4)
            pos += 8
            var keyEnd = pos
            while (keyEnd < items.size && items[keyEnd] != 0.toByte()) keyEnd++
            if (keyEnd >= items.size) return result
            val key = String(items, pos, keyEnd - pos, StandardCharsets.US_ASCII).lowercase(Locale.ROOT)
            pos = keyEnd + 1
            if (valueSize < 0 || pos + valueSize > items.size) return result

            val isText = (itemFlags shr 1) and 0x3 == 0
            when {
                isText && key == "title" -> result.title = apeText(items, pos, valueSize)
                isText && key == "artist" -> result.artist = apeText(items, pos, valueSize)
                isText && key == "album artist" -> result.albumArtist = apeText(items, pos, valueSize)
                isText && key == "album" -> result.album = apeText(items, pos, valueSize)
                !isText && key == "cover art (front)" -> {
                    // Binary item: "file name\0" followed by the image.
                    var dataStart = pos
                    while (dataStart < pos + valueSize && items[dataStart] != 0.toByte()) dataStart++
                    dataStart++
                    if (dataStart < pos + valueSize) result.picture = items.copyOfRange(dataStart, pos + valueSize)
                }
            }
            pos += valueSize
        }
        return result
    }

    private fun apeText(items: ByteArray, start: Int, size: Int): String? {
        var end = start
        while (end < start + size && items[end] != 0.toByte()) end++ // first of several values
        return decodeLegacy(items, start, end).trim().ifEmpty { null }
    }

    // ---------------------------------------------------------------------------------------------
    // ID3v1
    // ---------------------------------------------------------------------------------------------

    private fun readId3v1(raf: RandomAccessFile, fileLength: Long): TagData? {
        if (fileLength < ID3V1_SIZE) return null
        raf.seek(fileLength - ID3V1_SIZE)
        val tag = ByteArray(ID3V1_SIZE)
        raf.readFully(tag)
        if (tag[0] != 'T'.code.toByte() || tag[1] != 'A'.code.toByte() || tag[2] != 'G'.code.toByte()) return null
        return TagData().apply {
            title = id3v1Field(tag, 3)
            artist = id3v1Field(tag, 33)
            album = id3v1Field(tag, 63)
        }
    }

    private fun id3v1Field(tag: ByteArray, offset: Int): String? {
        var end = offset
        while (end < offset + 30 && tag[end] != 0.toByte()) end++
        return decodeLegacy(tag, offset, end).trim().ifEmpty { null }
    }

    // ---------------------------------------------------------------------------------------------
    // Text decoding
    // ---------------------------------------------------------------------------------------------

    private fun decode(bytes: ByteArray, from: Int, to: Int, encoding: Int): String {
        if (to <= from) return ""
        return when (encoding) {
            ENCODING_UTF16 -> decodeUtf16WithBom(bytes, from, to)
            ENCODING_UTF16BE -> String(bytes, from, to - from, StandardCharsets.UTF_16BE)
            ENCODING_UTF8 -> String(bytes, from, to - from, StandardCharsets.UTF_8)
            else -> decodeLegacy(bytes, from, to)
        }
    }

    private fun decodeUtf16WithBom(bytes: ByteArray, from: Int, to: Int): String {
        if (to - from >= 2) {
            val b0 = bytes[from].toInt() and 0xFF
            val b1 = bytes[from + 1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, from + 2, to - from - 2, StandardCharsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, from + 2, to - from - 2, StandardCharsets.UTF_16BE)
        }
        return String(bytes, from, to - from, StandardCharsets.UTF_16LE) // BOM missing: LE is most common
    }

    /**
     * "ISO-8859-1" frames and ID3v1 are in practice written in the local code page:
     * ASCII -> as is, valid UTF-8 -> UTF-8, otherwise the code page for the current locale.
     */
    private fun decodeLegacy(bytes: ByteArray, from: Int, to: Int): String {
        val length = to - from
        if (length <= 0) return ""
        var ascii = true
        for (i in from until to) {
            if (bytes[i] < 0) {
                ascii = false
                break
            }
        }
        if (ascii) return String(bytes, from, length, StandardCharsets.US_ASCII)
        decodeStrict(bytes, from, length, StandardCharsets.UTF_8)?.let { return it }
        return String(bytes, from, length, localCharset())
    }

    private fun decodeStrict(bytes: ByteArray, from: Int, length: Int, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, from, length))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun localCharset(): Charset {
        val locale = Locale.getDefault()
        cachedCharset?.let { (cachedLocale, charset) -> if (cachedLocale == locale) return charset }
        val charset = try {
            Charset.forName(FuncUtils.getCharset(locale))
        } catch (e: IllegalArgumentException) { // unsupported / illegal charset name
            StandardCharsets.ISO_8859_1
        }
        cachedCharset = locale to charset
        return charset
    }

    /** Index where the (null terminated) string starting at [from] ends. */
    private fun terminatorIndex(bytes: ByteArray, from: Int, encoding: Int): Int {
        if (encoding == ENCODING_UTF16 || encoding == ENCODING_UTF16BE) {
            var i = from
            while (i + 1 < bytes.size) {
                if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) return i
                i += 2
            }
            return bytes.size - ((bytes.size - from) and 1) // ignore a dangling odd byte
        }
        var i = from
        while (i < bytes.size && bytes[i] != 0.toByte()) i++
        return i
    }

    /** Index right after the terminator of the string starting at [from]. */
    private fun afterTerminator(bytes: ByteArray, from: Int, encoding: Int): Int {
        val end = terminatorIndex(bytes, from, encoding)
        val terminatorLength = if (encoding == ENCODING_UTF16 || encoding == ENCODING_UTF16BE) 2 else 1
        return minOf(end + terminatorLength, bytes.size)
    }

    // ---------------------------------------------------------------------------------------------
    // Binary helpers
    // ---------------------------------------------------------------------------------------------

    private class TagData {
        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var picture: ByteArray? = null

        fun hasAllText(): Boolean = title != null && (artist != null || albumArtist != null) && album != null
    }

    private class Picture(val type: Int, val data: ByteArray)

    private companion object {
        const val TAG = "Lrc"
        const val ID3V2_HEADER_SIZE = 10
        const val ID3V1_SIZE = 128
        const val APE_FOOTER_SIZE = 32
        const val APE_PREAMBLE = "APETAGEX"
        const val MAX_TAG_BYTES = 16 * 1024 * 1024
        const val PICTURE_TYPE_FRONT_COVER = 3
        const val ENCODING_UTF16 = 1
        const val ENCODING_UTF16BE = 2
        const val ENCODING_UTF8 = 3

        private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

        @Volatile
        var cachedCharset: Pair<Locale, Charset>? = null

        fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

        fun isValidFrameId(data: ByteArray, pos: Int, length: Int): Boolean {
            for (i in pos until pos + length) {
                val c = data[i].toInt()
                val valid = c in 'A'.code..'Z'.code || c in '0'.code..'9'.code
                if (!valid) return false
            }
            return true
        }

        fun isImageSignature(data: ByteArray, pos: Int): Boolean =
            startsWithAt(data, pos, JPEG) || startsWithAt(data, pos, PNG)

        fun findImageSignature(data: ByteArray, from: Int): Int? {
            val limit = minOf(data.size - 4, from + 512)
            for (i in from.coerceAtLeast(0)..limit) {
                if (isImageSignature(data, i)) return i
            }
            return null
        }

        fun startsWith(data: ByteArray, text: String): Boolean =
            startsWithAt(data, 0, text.toByteArray(StandardCharsets.US_ASCII))

        fun startsWithAt(data: ByteArray, pos: Int, prefix: ByteArray): Boolean {
            if (pos < 0 || pos + prefix.size > data.size) return false
            for (i in prefix.indices) if (data[pos + i] != prefix[i]) return false
            return true
        }

        fun synchsafe(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0x7F) shl 21) or
                ((b[off + 1].toInt() and 0x7F) shl 14) or
                ((b[off + 2].toInt() and 0x7F) shl 7) or
                (b[off + 3].toInt() and 0x7F)

        fun int32(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)

        fun int24(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 16) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                (b[off + 2].toInt() and 0xFF)

        fun int32Le(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)

        /** Reverts ID3 unsynchronisation: every 0xFF 0x00 pair becomes 0xFF. */
        fun removeUnsynchronisation(data: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            var o = 0
            var i = 0
            while (i < data.size) {
                val b = data[i]
                out[o++] = b
                i++
                if (b == 0xFF.toByte() && i < data.size && data[i] == 0.toByte()) i++
            }
            return if (o == data.size) out else out.copyOf(o)
        }

        fun inflate(data: ByteArray): ByteArray? {
            val inflater = Inflater()
            return try {
                inflater.setInput(data)
                val buffer = ByteArray(8192)
                val out = java.io.ByteArrayOutputStream(data.size * 2)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_TAG_BYTES) return null
                }
                out.toByteArray()
            } catch (e: DataFormatException) {
                null
            } finally {
                inflater.end()
            }
        }
    }
}
