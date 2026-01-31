package vasyl.titles.widget

import android.util.Log
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Locale

class Lrc {

    fun getId3Info(strFile: String?): Id3Info {
        val id3Info = Id3Info()

        if (!strFile.isNullOrEmpty()) {
            val strLower = strFile.lowercase()
            if (strLower.endsWith(".mp3")) {
                try {
                    if (!GetAPEv2Info(strFile, id3Info) && !GetId3v2Info(strFile, id3Info)) {
                        try {
                            GetId3v1Info(strFile, id3Info)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                try {
                    GetId3v2_APIC(strFile, id3Info)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return id3Info
    }

    fun GetId3v2_APIC(strFile: String, id3Info: Id3Info) {
        var randomFile: RandomAccessFile? = null
        try {
            randomFile = RandomAccessFile(strFile, "r")
            ReadID3v2_APIC(randomFile, id3Info)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                randomFile?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Throws(IOException::class)
    fun ReadID3v2_APIC(randomFile: RandomAccessFile, id3Info: Id3Info) {
        val dwLenFile = randomFile.length()
        val id3v2Header = ByteArray(10)
        randomFile.read(id3v2Header, 0, 10)

        if (id3v2Header[0] == 73.toByte() && id3v2Header[1] == 68.toByte() &&
            id3v2Header[2] == 51.toByte() && id3v2Header[4] != 2.toByte()
        ) {
            ParseFramesV23_APIC(randomFile, (dwLenFile - randomFile.filePointer).toInt(), id3Info)
        }
    }

    fun IsValidFrameV23(FrameID: ByteArray): Boolean {
        for (i in 0..3) {
            val b = FrameID[i]
            if ((b < 65 || b > 90) && (b < 48 || b > 57)) return false
        }
        return true
    }

    @Throws(IOException::class)
    fun ParseFramesV23_APIC(randomFile: RandomAccessFile, length: Int, id3Info: Id3Info) {
        var len = length
        var bParse = false
        val tmp = ByteArray(1)
        val fhV23 = ByteArray(10)

        while (len > 10) {
            randomFile.read(tmp, 0, 1)
            if (tmp[0] == 0.toByte()) {
                len--
            } else {
                randomFile.read(tmp, 0, 1)
                if (tmp[0] == 0.toByte()) {
                    len--
                } else {
                    randomFile.seek(randomFile.filePointer - 2)
                    val nReadByte = randomFile.read(fhV23, 0, 10)

                    val nFrameLength = getInt_way2(fhV23, 4)
                    if (nReadByte == 10 && IsValidFrameV23(fhV23) &&
                        nFrameLength >= 0 && nFrameLength <= len
                    ) {

                        val dwPosNext = randomFile.filePointer + nFrameLength

                        // APIC frame
                        if (fhV23[0] == 65.toByte() && fhV23[1] == 80.toByte() &&
                            fhV23[2] == 73.toByte() && fhV23[3] == 67.toByte()
                        ) {
                            bParse = true

                            if (nFrameLength > 13) {
                                randomFile.seek(randomFile.filePointer + 13)
                                val iLen = nFrameLength - 13
                                val data = ByteArray(iLen)

                                randomFile.read(data, 0, iLen)

                                val imgStart = ByteUtil.indexOf(byteArrayOf(-1, -40), data, data.size)
                                val imgEnd = ByteUtil.lastIndexOf(byteArrayOf(-1, -39), data, data.size) + 2

                                id3Info.dataPic = ByteUtil.cutBytes(imgStart, imgEnd, data)
                            }
                        }

                        if (!bParse) {
                            randomFile.seek(dwPosNext)
                            len -= nFrameLength + 10
                        } else return
                    } else return
                }
            }
        }
    }

    @Throws(IOException::class)
    fun ReadID3v1(randomFile: RandomAccessFile, id3Info: Id3Info): Boolean {
        val len = randomFile.length()
        if (len <= 128) return false

        randomFile.seek(len - 128)
        val chIdent = ByteArray(3)
        randomFile.read(chIdent, 0, 3)

        if (!(chIdent[0] == 'T'.code.toByte() &&
                    chIdent[1] == 'A'.code.toByte() &&
                    chIdent[2] == 'G'.code.toByte())
        ) return false

        val chTitle = ByteArray(30)
        val chArtist = ByteArray(30)
        val chAlbum = ByteArray(30)

        randomFile.read(chTitle, 0, 30)
        randomFile.read(chArtist, 0, 30)
        randomFile.read(chAlbum, 0, 30)

        chTitle[29] = 0
        chArtist[29] = 0
        chAlbum[29] = 0

        for (i in 29 downTo 0) {
            if (chArtist[i] == 32.toByte()) chArtist[i] = 0 else break
        }

        if (chArtist[0] == 0.toByte()) return false

        id3Info.strTitle = GetBufferW(chTitle, 30, 0).trim()
        id3Info.strArtist = GetBufferW(chArtist, 30, 0).trim()
        id3Info.strAlbum = GetBufferW(chAlbum, 30, 0).trim()

        return true
    }

    fun GetBufferW(pStream: ByteArray, iLenBuf: Int, iTypeEncodeFile: Int): String {
        return try {
            String(pStream, getCharSet(iTypeEncodeFile))
        } catch (e: Exception) {
            ""
        }
    }

    fun getCharSet(iTypeEncodeFile: Int): Charset {
        return when (iTypeEncodeFile) {
            1 -> Charset.forName("UTF-16LE")
            2 -> Charset.forName("UTF-16BE")
            3 -> StandardCharsets.UTF_8
            else -> Charset.forName(FuncUtils.getCharset(Locale.getDefault()))
        }
    }

    fun GetId3v1Info(strFile: String, id3Info: Id3Info): Boolean {
        var randomFile: RandomAccessFile? = null
        return try {
            randomFile = RandomAccessFile(strFile, "r")
            ReadID3v1(randomFile, id3Info)
        } catch (e: IOException) {
            Log.e("Lrc", "Error accessing ID3v1: $strFile", e)
            false
        } finally {
            try {
                randomFile?.close()
            } catch (e: IOException) {
                Log.e("Lrc", "Error closing ID3v1 file: $strFile", e)
            }
        }
    }

    @Throws(IOException::class)
    fun ReadAPEv2(randomFile: RandomAccessFile, id3Info: Id3Info): Boolean {
        if (randomFile.length() < 128) return false

        val footer = ByteArray(32)

        randomFile.seek(randomFile.length() - 32)
        randomFile.read(footer, 0, 32)

        val sig = "APETAGEX".toByteArray()
        if (!footer.copyOfRange(0, 8).contentEquals(sig)) return false

        val nFieldSize = getInt(footer, 12) - 32
        val nFieldFooter = getInt(footer, 16)

        randomFile.seek(randomFile.length() - (nFieldSize + 32))

        ParseFields(randomFile, nFieldSize, nFieldFooter, id3Info)
        return id3Info.strArtist.isNotEmpty()
    }

    fun GetAPEv2Info(strFile: String, id3Info: Id3Info): Boolean {
        var randomFile: RandomAccessFile? = null
        return try {
            randomFile = RandomAccessFile(strFile, "r")
            ReadAPEv2(randomFile, id3Info)
        } catch (e: IOException) {
            Log.e("Lrc", "Error reading APEv2: $strFile", e)
            false
        } finally {
            try {
                randomFile?.close()
            } catch (e: IOException) {
                Log.e("Lrc", "Error closing APEv2 file: $strFile", e)
            }
        }
    }

    fun GetId3v2Info(strFile: String, id3Info: Id3Info): Boolean {
        var randomFile: RandomAccessFile? = null
        return try {
            randomFile = RandomAccessFile(strFile, "r")
            ReadID3v2(randomFile, id3Info)
        } catch (e: IOException) {
            Log.e("Lrc", "Error reading ID3v2: $strFile", e)
            false
        } finally {
            try {
                randomFile?.close()
            } catch (e: IOException) {
                Log.e("Lrc", "Error closing ID3v2 file: $strFile", e)
            }
        }
    }

    fun getInt_way2(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 255) shl 24) +
                ((buf[offset + 1].toInt() and 255) shl 16) +
                ((buf[offset + 2].toInt() and 255) shl 8) +
                (buf[offset + 3].toInt() and 255)
    }

    fun getInt(buf: ByteArray, offset: Int): Int {
        return ((buf[offset + 3].toInt() and 255) shl 24) +
                ((buf[offset + 2].toInt() and 255) shl 16) +
                ((buf[offset + 1].toInt() and 255) shl 8) +
                (buf[offset].toInt() and 255)
    }

    @Throws(IOException::class)
    fun ReadAsciiText(
        randomFile: RandomAccessFile,
        iLenMax: Int,
        iLenActual: IntArray,
        iEncode: Int,
        bDetectEncode: Boolean
    ): String? {
        var maxLen = iLenMax
        var encode = iEncode
        iLenActual[0] = 0

        if (maxLen <= 0 || maxLen >= 1024) return null

        val pos = randomFile.filePointer

        if (bDetectEncode && maxLen >= 3) {
            val header = ByteArray(3)
            randomFile.read(header, 0, 3)
            val b0 = header[0].toInt() and 255
            val b1 = header[1].toInt() and 255
            val b2 = header[2].toInt() and 255

            when {
                b0 == 255 && b1 == 254 -> {
                    encode = 1
                    randomFile.seek(randomFile.filePointer - 1)
                    maxLen -= 2
                }
                b0 == 254 && b1 == 255 -> {
                    encode = 2
                    randomFile.seek(randomFile.filePointer - 1)
                    maxLen -= 2
                }
                b0 == 239 && b1 == 187 && b2 == 191 -> {
                    encode = 3
                    maxLen -= 3
                }
                else -> randomFile.seek(randomFile.filePointer - 3)
            }
        }

        val isUnicode = encode == 1 || encode == 2
        val ch = ByteArray(1)
        val pStream = ByteArray(maxLen + 2)
        Arrays.fill(pStream, 0.toByte())

        var iTemp = maxLen
        while (iTemp > 0) {
            randomFile.read(ch, 0, 1)
            pStream[iLenActual[0]] = ch[0]
            iLenActual[0]++
            iTemp--

            if (iTemp > 0 && ch[0] == 0.toByte()) {
                if (!isUnicode) break

                randomFile.read(ch, 0, 1)
                pStream[iLenActual[0]] = ch[0]
                iLenActual[0]++
                iTemp--
            }
        }

        iLenActual[0] = (randomFile.filePointer - pos).toInt()
        randomFile.seek(randomFile.filePointer + iTemp)

        return GetBufferW(pStream, maxLen + 2, encode)
    }

    @Throws(IOException::class)
    fun ParseFields(
        randomFile: RandomAccessFile,
        size: Int,
        count: Int,
        id3Info: Id3Info
    ) {
        var nFieldsSize = size
        val parsed = BooleanArray(3)
        val nValueSize = ByteArray(4)
        val flags = ByteArray(4)

        for (i in 0 until count) {
            if (nFieldsSize <= 10) return

            randomFile.read(nValueSize, 0, 4)
            randomFile.read(flags, 0, 4)

            val len = IntArray(1)
            val fieldTextSize = nFieldsSize - 8 - getInt(nValueSize, 0)
            val strName = ReadAsciiText(randomFile, fieldTextSize, len, 3, true)

            if (strName != null) {
                randomFile.seek(randomFile.filePointer - (fieldTextSize - len[0]))

                val valueLen = getInt(nValueSize, 0)
                val tmpLen = IntArray(1)

                when {
                    strName.startsWith("Artist") -> {
                        parsed[0] = true
                        id3Info.strArtist =
                            ReadAsciiText(randomFile, valueLen, tmpLen, 3, true)?.trim().toString()
                    }
                    strName.startsWith("Album") -> {
                        parsed[1] = true
                        id3Info.strAlbum =
                            ReadAsciiText(randomFile, valueLen, tmpLen, 3, true)?.trim().toString()
                    }
                    strName.startsWith("Title") -> {
                        parsed[2] = true
                        id3Info.strTitle =
                            ReadAsciiText(randomFile, valueLen, tmpLen, 3, true)?.trim().toString()
                    }
                    else -> {
                        ReadAsciiText(randomFile, valueLen, tmpLen, 3, true)
                    }
                }
            }

            if (!parsed.all { it }) {
                nFieldsSize = nFieldsSize - 8 - len[0]
            } else return
        }
    }

    @Throws(IOException::class)
    fun ReadID3v2(randomFile: RandomAccessFile, id3Info: Id3Info): Boolean {
        val dwLenFile = randomFile.length()
        val id3v2Header = ByteArray(10)
        randomFile.read(id3v2Header, 0, 10)

        if (!(id3v2Header[0] == 73.toByte() &&
                    id3v2Header[1] == 68.toByte() &&
                    id3v2Header[2] == 51.toByte())
        ) return false

        if (id3v2Header[4] == 2.toByte()) {
            ParseFramesV22(randomFile, (dwLenFile - randomFile.filePointer).toInt(), id3Info)
        } else {
            ParseFramesV23(randomFile, (dwLenFile - randomFile.filePointer).toInt(), id3Info)
        }

        return id3Info.strArtist.isNotEmpty()
    }

    fun IsValidFrameV22(fhV22: ByteArray): Boolean {
        for (i in 0..2) {
            val b = fhV22[i]
            if ((b < 65 || b > 90) && (b < 48 || b > 57)) return false
        }
        return true
    }

    @Throws(IOException::class)
    fun ParseFramesV22(randomFile: RandomAccessFile, length: Int, id3Info: Id3Info) {
        var len = length
        val parsed = BooleanArray(3)
        val tmp = ByteArray(1)
        val fhV22 = ByteArray(6)

        while (len > 6) {
            randomFile.read(tmp, 0, 1)
            if (tmp[0] == 0.toByte()) {
                len--
            } else {
                randomFile.seek(randomFile.filePointer - 1)
                val nReadByte = randomFile.read(fhV22, 0, 6)

                val frameLen =
                    ((fhV22[3].toInt() and 255) shl 16) +
                            ((fhV22[4].toInt() and 255) shl 8) +
                            (fhV22[5].toInt() and 255)

                if (nReadByte == 6 && IsValidFrameV22(fhV22) &&
                    frameLen in 1..len && frameLen <= 100
                ) {
                    val iEncode = ByteArray(1)
                    val iLenTemp = IntArray(1)

                    val dwPosNext = randomFile.filePointer + frameLen

                    when {
                        fhV22[0] == 84.toByte() && fhV22[1] == 80.toByte() && fhV22[2] == 49.toByte() -> {
                            randomFile.read(iEncode, 0, 1)
                            parsed[0] = true
                            id3Info.strArtist =
                                ReadAsciiText(randomFile, frameLen - 1, iLenTemp, iEncode[0].toInt(), true)?.trim()
                                    .toString()
                        }
                        fhV22[0] == 84.toByte() && fhV22[1] == 65.toByte() && fhV22[2] == 76.toByte() -> {
                            randomFile.read(iEncode, 0, 1)
                            parsed[1] = true
                            id3Info.strAlbum =
                                ReadAsciiText(randomFile, frameLen - 1, iLenTemp, iEncode[0].toInt(), true)?.trim()
                                    .toString()
                        }
                        fhV22[0] == 84.toByte() && fhV22[1] == 84.toByte() && fhV22[2] == 50.toByte() -> {
                            randomFile.read(iEncode, 0, 1)
                            parsed[2] = true
                            id3Info.strTitle =
                                ReadAsciiText(randomFile, frameLen - 1, iLenTemp, iEncode[0].toInt(), true)?.trim()
                                    .toString()
                        }
                    }

                    if (!parsed.all { it }) {
                        randomFile.seek(dwPosNext)
                        len -= frameLen + 6
                    } else return
                } else return
            }
        }
    }

    @Throws(IOException::class)
    fun ParseFramesV23(randomFile: RandomAccessFile, length: Int, id3Info: Id3Info) {
        var len = length
        val parsed = BooleanArray(3)
        val tmp = ByteArray(1)
        val fhV23 = ByteArray(10)

        while (len > 10) {
            randomFile.read(tmp, 0, 1)
            if (tmp[0] == 0.toByte()) {
                len--
            } else {
                randomFile.read(tmp, 0, 1)
                if (tmp[0] == 0.toByte()) {
                    len--
                } else {
                    randomFile.seek(randomFile.filePointer - 2)
                    val nReadByte = randomFile.read(fhV23, 0, 10)
                    val frameLen = getInt_way2(fhV23, 4)

                    if (nReadByte == 10 && IsValidFrameV23(fhV23) &&
                        frameLen >= 0 && frameLen <= len
                    ) {
                        val iEncode = ByteArray(1)
                        val iLenTemp = IntArray(1)
                        val dwPosNext = randomFile.filePointer + frameLen

                        when {
                            fhV23[0] == 84.toByte() && fhV23[1] == 80.toByte() &&
                                    fhV23[2] == 69.toByte() && fhV23[3] == 49.toByte() -> {
                                parsed[0] = true
                                randomFile.read(iEncode, 0, 1)
                                id3Info.strArtist =
                                    ReadAsciiText(randomFile, frameLen - 1, iLenTemp, iEncode[0].toInt(), true)?.trim()
                                        .toString()
                            }
                            fhV23[0] == 84.toByte() && fhV23[1] == 65.toByte() &&
                                    fhV23[2] == 76.toByte() && fhV23[3] == 66.toByte() -> {
                                parsed[1] = true
                                randomFile.read(iEncode, 0, 1)
                                id3Info.strAlbum =
                                    ReadAsciiText(randomFile, frameLen - 1, iLenTemp, iEncode[0].toInt(), true)?.trim()
                                        .toString()
                            }
                            fhV23[0] == 84.toByte() && fhV23[1] == 73.toByte() &&
                                    fhV23[2] == 84.toByte() && fhV23[3] == 50.toByte() -> {
                                parsed[2] = true
                                randomFile.read(iEncode, 0, 1)
                                id3Info.strTitle =
                                    ReadAsciiText(randomFile, frameLen - 1, iLenTemp, iEncode[0].toInt(), true)?.trim()
                                        .toString()
                            }
                        }

                        if (!parsed.all { it }) {
                            randomFile.seek(dwPosNext)
                            len -= frameLen + 10
                        } else return
                    } else return
                }
            }
        }
    }
}
