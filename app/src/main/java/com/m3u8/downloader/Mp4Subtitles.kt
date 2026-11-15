package com.m3u8.downloader

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** MP4 에 넣을 자막 트랙 1개. cue 시간은 "영상 시작 = 0" 기준 ms */
class TextTrack(val lang: String, val name: String, val cues: List<Cue>, val isDefault: Boolean)

/**
 * 이미 만들어진 MP4 에 자막(tx3g = mov_text) 트랙을 추가한다. (재인코딩 없음, 영상/오디오 데이터 그대로)
 *
 * MediaMuxer 는 자막 트랙을 지원하지 않으므로 MP4 박스를 직접 수정한다.
 *  - moov 가 파일 끝에 있으면(MediaMuxer 기본): 그 자리에 [자막 mdat][새 moov] 를 덧붙임 (제자리 수정)
 *  - moov 가 앞에 있으면(faststart): 새 파일로 다시 쓰면서 기존 stco/co64 오프셋을 보정
 */
object Mp4Subtitles {

    private class Box(val type: String, val start: Long, val size: Long, val header: Int)

    /** 성공하면 true. 실패 시 false 이며 파일은 원본 그대로 유지된다. */
    fun embed(file: File, tracksIn: List<TextTrack>): Boolean {
        return try {
            doEmbed(file, tracksIn)
        } catch (e: Throwable) {
            false
        }
    }

    private fun doEmbed(file: File, tracksIn: List<TextTrack>): Boolean {
        // 기본 트랙을 맨 앞으로
        val tracks = tracksIn.filter { it.cues.isNotEmpty() }
            .sortedByDescending { it.isDefault }
        if (tracks.isEmpty()) return false

        val fileLen = file.length()
        RandomAccessFile(file, "r").use { raf ->
            val boxes = scanTopLevel(raf, fileLen) ?: return false
            val moov = boxes.firstOrNull { it.type == "moov" } ?: return false
            if (boxes.none { it.type == "mdat" }) return false
            if (moov.size > 256L * 1024 * 1024) return false

            val moovBytes = ByteArray((moov.size - moov.header).toInt())
            raf.seek(moov.start + moov.header)
            raf.readFully(moovBytes)

            val mvhd = findChild(moovBytes, 0, moovBytes.size, "mvhd") ?: return false
            val mvhdVer = moovBytes[mvhd.first].toInt()
            val movieTs = readU32(moovBytes, mvhd.first + if (mvhdVer == 1) 4 + 16 else 4 + 8).toInt()
            val movieDurTicks = if (mvhdVer == 1) readU64(moovBytes, mvhd.first + 4 + 20)
            else readU32(moovBytes, mvhd.first + 4 + 12)
            val nextIdPos = mvhd.first + if (mvhdVer == 1) 4 + 8 + 8 + 4 + 8 + 4 + 2 + 2 + 8 + 36 + 24
            else 4 + 4 + 4 + 4 + 4 + 4 + 2 + 2 + 8 + 36 + 24
            if (movieTs <= 0) return false

            // 기존 트랙 ID 중 최댓값
            var maxId = readU32(moovBytes, nextIdPos) - 1
            forEachChild(moovBytes, 0, moovBytes.size) { type, bodyStart, bodyEnd ->
                if (type == "trak") {
                    val tkhd = findChild(moovBytes, bodyStart, bodyEnd, "tkhd")
                    if (tkhd != null) {
                        val v = moovBytes[tkhd.first].toInt()
                        val id = readU32(moovBytes, tkhd.first + if (v == 1) 4 + 16 else 4 + 8)
                        if (id > maxId) maxId = id
                    }
                }
            }

            // 영상 길이보다 뒤에 있는 자막은 잘라냄
            val movieDurMs = if (movieDurTicks > 0) movieDurTicks * 1000L / movieTs else Long.MAX_VALUE

            // 자막 샘플 데이터 구성
            class Built(val track: TextTrack, val sizes: List<Int>, val durs: List<Long>, val data: ByteArray, val totalMs: Long)

            val built = ArrayList<Built>()
            for (t in tracks) {
                val flat = SubtitleParser.flatten(
                    t.cues.filter { it.startMs < movieDurMs }
                        .map { if (it.endMs > movieDurMs) Cue(it.startMs, movieDurMs, it.text) else it }
                )
                if (flat.isEmpty()) continue
                val bos = ByteArrayOutputStream()
                val sizes = ArrayList<Int>()
                val durs = ArrayList<Long>()
                var now = 0L
                fun sample(text: String, dur: Long) {
                    var bytes = text.toByteArray(Charsets.UTF_8)
                    if (bytes.size > 65535) bytes = bytes.copyOf(65535)
                    bos.write(bytes.size ushr 8)
                    bos.write(bytes.size and 0xff)
                    bos.write(bytes)
                    sizes.add(bytes.size + 2)
                    durs.add(dur)
                }
                for (c in flat) {
                    if (c.startMs > now) sample("", c.startMs - now)
                    sample(c.text, c.endMs - c.startMs)
                    now = c.endMs
                }
                // 마지막 자막 뒤에 빈 샘플을 둬서 플레이어가 마지막 자막을 영상 끝까지 늘리지 않게 한다
                sample("", if (movieDurMs != Long.MAX_VALUE && movieDurMs > now) movieDurMs - now else 1000L)
                now += durs.last()
                built.add(Built(t, sizes, durs, bos.toByteArray(), now))
            }
            if (built.isEmpty()) return false

            val payload = ByteArrayOutputStream()
            val relOffsets = ArrayList<Long>()
            for (b in built) {
                relOffsets.add(payload.size().toLong())
                payload.write(b.data)
            }
            val payloadBytes = payload.toByteArray()
            val mdatHeader = if (payloadBytes.size + 8L > 0xFFFFFFFFL) 16 else 8

            val moovLast = boxes.last().type == "moov"
            val use64 = fileLen + payloadBytes.size + moov.size * 2 + (1L shl 20) > 0xFFFFFFFFL

            fun buildTraks(payloadStart: Long): ByteArray {
                val o = ByteArrayOutputStream()
                built.forEachIndexed { i, b ->
                    o.write(
                        textTrak(
                            id = maxId + 1 + i, movieTs = movieTs, track = b.track,
                            enabled = i == 0, sizes = b.sizes, durs = b.durs, totalMs = b.totalMs,
                            chunkOffset = payloadStart + relOffsets[i], use64 = use64
                        )
                    )
                }
                return o.toByteArray()
            }

            // moov 크기는 오프셋 값과 무관 -> 먼저 크기를 계산
            val trakSize = buildTraks(0).size
            val oldBodySize = moovBytes.size
            val newMoovSize = moov.header + oldBodySize + trakSize
            val delta = (newMoovSize - moov.size)

            val newBody = moovBytes.copyOf()
            // next_track_ID 갱신
            writeU32(newBody, nextIdPos, maxId + 1 + built.size)

            val newMdatStart: Long
            if (moovLast) {
                newMdatStart = moov.start
            } else {
                // moov 뒤쪽 데이터가 delta 만큼 밀리므로 기존 오프셋 보정
                if (!shiftChunkOffsets(newBody, moov.start + moov.size, delta)) return false
                newMdatStart = fileLen + delta
            }
            val traks = buildTraks(newMdatStart + mdatHeader)
            val moovOut = ByteArrayOutputStream()
            DataOutputStream(moovOut).use { d ->
                if (moov.header == 16) {
                    d.writeInt(1); d.writeBytes("moov"); d.writeLong(newMoovSize.toLong())
                } else {
                    d.writeInt(newMoovSize.toInt()); d.writeBytes("moov")
                }
                d.write(newBody)
                d.write(traks)
            }
            val moovOutBytes = moovOut.toByteArray()
            val mdatOut = ByteArrayOutputStream()
            DataOutputStream(mdatOut).use { d ->
                if (mdatHeader == 16) {
                    d.writeInt(1); d.writeBytes("mdat"); d.writeLong(16L + payloadBytes.size)
                } else {
                    d.writeInt(8 + payloadBytes.size); d.writeBytes("mdat")
                }
                d.write(payloadBytes)
            }
            val mdatOutBytes = mdatOut.toByteArray()

            if (moovLast) {
                // 제자리 수정. 쓰기에 실패하면 원래 moov 를 복원
                val oldMoovFull = ByteArray(moov.size.toInt())
                raf.seek(moov.start)
                raf.readFully(oldMoovFull)
                RandomAccessFile(file, "rw").use { w ->
                    try {
                        w.setLength(moov.start)
                        w.seek(moov.start)
                        w.write(mdatOutBytes)
                        w.write(moovOutBytes)
                    } catch (e: Throwable) {
                        try {
                            w.setLength(moov.start)
                            w.seek(moov.start)
                            w.write(oldMoovFull)
                        } catch (_: Throwable) {
                        }
                        throw e
                    }
                }
            } else {
                val tmp = File(file.parentFile, file.name + ".sub.tmp")
                try {
                    RandomAccessFile(tmp, "rw").use { w ->
                        w.setLength(0)
                        copyRange(raf, w, 0, moov.start)
                        w.write(moovOutBytes)
                        copyRange(raf, w, moov.start + moov.size, fileLen)
                        w.write(mdatOutBytes)
                    }
                } catch (e: Throwable) {
                    tmp.delete()
                    throw e
                }
                raf.close()
                if (!tmp.renameTo(file)) {
                    file.delete()
                    if (!tmp.renameTo(file)) {
                        tmp.delete()
                        throw java.io.IOException("파일 교체 실패")
                    }
                }
            }
        }
        return true
    }

    private fun copyRange(src: RandomAccessFile, dst: RandomAccessFile, from: Long, to: Long) {
        val buf = ByteArray(1 shl 20)
        var pos = from
        src.seek(from)
        while (pos < to) {
            val n = src.read(buf, 0, minOf(buf.size.toLong(), to - pos).toInt())
            if (n < 0) throw java.io.IOException("unexpected EOF")
            dst.write(buf, 0, n)
            pos += n
        }
    }

    // ----------------------------------------------------------------- 박스 읽기
    private fun scanTopLevel(raf: RandomAccessFile, len: Long): List<Box>? {
        val out = ArrayList<Box>()
        var pos = 0L
        val h = ByteArray(16)
        while (pos + 8 <= len) {
            raf.seek(pos)
            raf.readFully(h, 0, 8)
            var size = readU32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (pos + 16 > len) return null
                raf.readFully(h, 8, 8)
                size = readU64(h, 8)
                header = 16
            } else if (size == 0L) {
                size = len - pos
            }
            if (size < header || pos + size > len) return null
            out.add(Box(type, pos, size, header))
            pos += size
        }
        return if (pos == len && out.isNotEmpty()) out else null
    }

    /** [from,to) 범위의 직속 자식 박스 중 type 의 본문 (start,end) */
    private fun findChild(b: ByteArray, from: Int, to: Int, type: String): Pair<Int, Int>? {
        var res: Pair<Int, Int>? = null
        forEachChild(b, from, to) { t, s, e -> if (res == null && t == type) res = s to e }
        return res
    }

    private fun forEachChild(b: ByteArray, from: Int, to: Int, f: (String, Int, Int) -> Unit) {
        var p = from
        while (p + 8 <= to) {
            var size = readU32(b, p)
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                size = readU64(b, p + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            if (size < header || p + size > to) return
            f(type, p + header, (p + size).toInt())
            p += size.toInt()
        }
    }

    /** trak/mdia/minf/stbl 의 stco/co64 에서 [threshold] 이상의 오프셋에 delta 를 더한다 */
    private fun shiftChunkOffsets(moov: ByteArray, threshold: Long, delta: Long): Boolean {
        if (delta == 0L) return true
        var ok = true
        fun walk(from: Int, to: Int, path: List<String>) {
            forEachChild(moov, from, to) { type, s, e ->
                when (type) {
                    "trak", "mdia", "minf", "stbl" -> walk(s, e, path + type)
                    "stco" -> {
                        val n = readU32(moov, s + 4).toInt()
                        for (i in 0 until n) {
                            val p = s + 8 + i * 4
                            val v = readU32(moov, p)
                            if (v >= threshold) {
                                val nv = v + delta
                                if (nv > 0xFFFFFFFFL || nv < 0) ok = false else writeU32(moov, p, nv)
                            }
                        }
                    }
                    "co64" -> {
                        val n = readU32(moov, s + 4).toInt()
                        for (i in 0 until n) {
                            val p = s + 8 + i * 8
                            val v = readU64(moov, p)
                            if (v >= threshold) writeU64(moov, p, v + delta)
                        }
                    }
                }
            }
        }
        walk(0, moov.size, emptyList())
        return ok
    }

    private fun readU32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xff) shl 24) or ((b[p + 1].toLong() and 0xff) shl 16) or
            ((b[p + 2].toLong() and 0xff) shl 8) or (b[p + 3].toLong() and 0xff)

    private fun readU64(b: ByteArray, p: Int): Long = (readU32(b, p) shl 32) or readU32(b, p + 4)

    private fun writeU32(b: ByteArray, p: Int, v: Long) {
        b[p] = (v shr 24).toByte(); b[p + 1] = (v shr 16).toByte()
        b[p + 2] = (v shr 8).toByte(); b[p + 3] = v.toByte()
    }

    private fun writeU64(b: ByteArray, p: Int, v: Long) {
        writeU32(b, p, v shr 32)
        writeU32(b, p + 4, v and 0xFFFFFFFFL)
    }

    // ----------------------------------------------------------------- 박스 쓰기
    private fun box(type: String, build: DataOutputStream.() -> Unit): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply(build).flush()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(body.size() + 8)
            writeBytes(type)
            write(body.toByteArray())
        }
        return out.toByteArray()
    }

    /** 3글자 ISO-639-2 언어 코드(없으면 und) */
    private fun iso3(lang: String): String {
        val base = lang.substringBefore('-').substringBefore('_').lowercase()
        if (base.length == 3 && base.all { it in 'a'..'z' }) return base
        if (base.length == 2) {
            try {
                val c = Locale(base).isO3Language
                if (c.length == 3) return c
            } catch (_: Exception) {
            }
        }
        return "und"
    }

    private fun textTrak(
        id: Long, movieTs: Int, track: TextTrack, enabled: Boolean,
        sizes: List<Int>, durs: List<Long>, totalMs: Long, chunkOffset: Long, use64: Boolean
    ): ByteArray {
        val lang = iso3(track.lang)
        val langCode = ((lang[0].code - 0x60) shl 10) or ((lang[1].code - 0x60) shl 5) or (lang[2].code - 0x60)
        val name = (track.name.ifBlank { track.lang.ifBlank { "Subtitle" } }).take(60)
        val trackDur = totalMs * movieTs / 1000

        val tkhd = box("tkhd") {
            writeInt(if (enabled) 3 else 2) // version 0 + flags(enabled | in_movie)
            writeInt(0); writeInt(0)        // creation / modification
            writeInt(id.toInt())
            writeInt(0)
            writeInt(trackDur.coerceAtMost(0xFFFFFFFFL).toInt())
            writeInt(0); writeInt(0)        // reserved
            writeShort(0)                   // layer
            writeShort(2)                   // alternate_group (자막끼리 택1)
            writeShort(0)                   // volume
            writeShort(0)
            for (v in intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)) writeInt(v)
            writeInt(0); writeInt(0)        // width / height
        }
        val mdhd = box("mdhd") {
            writeInt(0); writeInt(0); writeInt(0)
            writeInt(1000)
            writeInt(totalMs.toInt())
            writeShort(langCode)
            writeShort(0)
        }
        val hdlr = box("hdlr") {
            writeInt(0); writeInt(0)
            writeBytes("sbtl")
            writeInt(0); writeInt(0); writeInt(0)
            write(name.toByteArray(Charsets.UTF_8)); writeByte(0)
        }
        val nmhd = box("nmhd") { writeInt(0) }
        val dinf = box("dinf") {
            write(box("dref") {
                writeInt(0); writeInt(1)
                write(box("url ") { writeInt(1) })
            })
        }
        val tx3g = box("tx3g") {
            write(ByteArray(6)); writeShort(1)           // reserved, data_reference_index
            writeInt(0)                                   // displayFlags
            writeByte(1); writeByte(-1)                   // h-justify(center), v-justify(bottom)
            writeInt(0)                                   // background rgba
            writeShort(0); writeShort(0); writeShort(0); writeShort(0) // default text box
            writeShort(0); writeShort(0); writeShort(1)   // style: start, end, fontID
            writeByte(0); writeByte(18)                   // face, size
            writeInt(-1)                                  // text color = white
            write(box("ftab") {
                writeShort(1); writeShort(1)
                val fn = "Sans-Serif".toByteArray(); writeByte(fn.size); write(fn)
            })
        }
        val stsd = box("stsd") { writeInt(0); writeInt(1); write(tx3g) }
        val stts = box("stts") {
            writeInt(0)
            val runs = ArrayList<Pair<Int, Long>>()
            for (d in durs) {
                val last = runs.lastOrNull()
                if (last != null && last.second == d) runs[runs.size - 1] = (last.first + 1) to d
                else runs.add(1 to d)
            }
            writeInt(runs.size)
            for ((n, d) in runs) { writeInt(n); writeInt(d.toInt()) }
        }
        val stsc = box("stsc") { writeInt(0); writeInt(1); writeInt(1); writeInt(sizes.size); writeInt(1) }
        val stsz = box("stsz") {
            writeInt(0); writeInt(0); writeInt(sizes.size)
            for (s in sizes) writeInt(s)
        }
        val stco = if (use64) box("co64") { writeInt(0); writeInt(1); writeLong(chunkOffset) }
        else box("stco") { writeInt(0); writeInt(1); writeInt(chunkOffset.toInt()) }

        val stbl = box("stbl") { write(stsd); write(stts); write(stsc); write(stsz); write(stco) }
        val minf = box("minf") { write(nmhd); write(dinf); write(stbl) }
        val mdia = box("mdia") { write(mdhd); write(hdlr); write(minf) }
        return box("trak") { write(tkhd); write(mdia) }
    }
}
