package com.example.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudiobookLogicTest {

    @Test
    fun naturalSort_ordersMultiDigitNumbersCorrectly() {
        val raw = listOf("Chapter 10", "chapter 2", "Chapter 1", "Chapter 20", "Chapter 02")
        val sorted = raw.sortedWith { a, b -> compareNatural(a, b) }
        assertEquals("Chapter 1", sorted[0])
        assertTrue(sorted.indexOf("chapter 2") < sorted.indexOf("Chapter 10"))
        assertTrue(sorted.indexOf("Chapter 10") < sorted.indexOf("Chapter 20"))
    }

    @Test
    fun calculateSmartRewindPos_appliesExpectedRewindThresholds() {
        val now = 1_000_000_000L
        val oneHourAgo = now - (1L * 3600_000L)
        val fiveHoursAgo = now - (5L * 3600_000L)
        val twoDaysAgo = now - (48L * 3600_000L)

        // Under 2 hours -> 0s rewind
        assertEquals(50_000L, calculateSmartRewindPos(50_000L, oneHourAgo, now))
        // Under 24 hours -> 15s rewind
        assertEquals(35_000L, calculateSmartRewindPos(50_000L, fiveHoursAgo, now))
        // 24+ hours -> 30s rewind
        assertEquals(20_000L, calculateSmartRewindPos(50_000L, twoDaysAgo, now))
        // Never below 0
        assertEquals(0L, calculateSmartRewindPos(10_000L, twoDaysAgo, now))
    }

    @Test
    fun getCurrentChapterIndex_handlesSingleAndMultiTrackBooks() {
        val singleFileChapters = listOf(
            Chapter("Intro", 0L, 10_000L, 0),
            Chapter("Chapter 1", 10_000L, 20_000L, 0),
            Chapter("Chapter 2", 30_000L, 20_000L, 0)
        )
        assertEquals(0, getCurrentChapterIndex(singleFileChapters, 5_000L, 0))
        assertEquals(1, getCurrentChapterIndex(singleFileChapters, 15_000L, 0))
        assertEquals(2, getCurrentChapterIndex(singleFileChapters, 45_000L, 0))

        val multiTrackChapters = listOf(
            Chapter("Part 1", 0L, 60_000L, 0),
            Chapter("Part 2 - A", 0L, 30_000L, 1),
            Chapter("Part 2 - B", 30_000L, 30_000L, 1),
            Chapter("Part 3", 0L, 60_000L, 2)
        )
        assertEquals(1, getCurrentChapterIndex(multiTrackChapters, 10_000L, 1))
        assertEquals(2, getCurrentChapterIndex(multiTrackChapters, 35_000L, 1))
        assertEquals(3, getCurrentChapterIndex(multiTrackChapters, 5_000L, 2))
    }

    @Test
    fun bookDurationAndOffset_computesMultiTrackTotals() {
        val tracks = listOf(100_000L, 200_000L, 300_000L)
        assertEquals(600_000L, bookDurationMs(tracks))
        assertEquals(25_000L, bookOffsetMs(tracks, 0, 25_000L))
        assertEquals(150_000L, bookOffsetMs(tracks, 1, 50_000L))
        assertEquals(375_000L, bookOffsetMs(tracks, 2, 75_000L))
    }

    @Test
    fun parseChplBytes_matchesFfmpegMovReadChplLayout() {
        val title1 = "Prologue".toByteArray(Charsets.UTF_8)
        val title2 = "Chapter 1: The Forest".toByteArray(Charsets.UTF_8)

        // Version 1 layout: u8 version (1), u24 flags (0), u32 reserved (0), u8 count (2)
        val buf = ByteBuffer.allocate(9 + (9 + title1.size) + (9 + title2.size)).order(ByteOrder.BIG_ENDIAN)
        buf.put(1.toByte()) // version = 1
        buf.put(0.toByte()); buf.put(0.toByte()); buf.put(0.toByte()) // flags
        buf.putInt(0) // 4-byte reserved in v1
        buf.put(2.toByte()) // chapter count = 2 (single u8)

        // Chapter 1 at 0 ms (0 ticks)
        buf.putLong(0L)
        buf.put(title1.size.toByte())
        buf.put(title1)

        // Chapter 2 at 65,000 ms (650,000,000 ticks in 100ns units)
        buf.putLong(650_000_000L)
        buf.put(title2.size.toByte())
        buf.put(title2)

        val chapters = parseChplBytes(buf.array(), trackIndex = 0)
        assertEquals(2, chapters.size)
        assertEquals("Prologue", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals("Chapter 1: The Forest", chapters[1].title)
        assertEquals(65_000L, chapters[1].startMs)
    }

    @Test
    fun decodeTx3gTitle_readsTwoByteLengthPrefixAndUtf8() {
        val rawTitle = "Chapter 7: Golden Leaf"
        val utf8 = rawTitle.toByteArray(Charsets.UTF_8)
        val sample = ByteBuffer.allocate(2 + utf8.size + 12).order(ByteOrder.BIG_ENDIAN)
        sample.putShort(utf8.size.toShort())
        sample.put(utf8)
        // Trailing tx3g style atom bytes that should be ignored
        sample.putInt(12)
        sample.put("styl".toByteArray(Charsets.ISO_8859_1))
        sample.putInt(0)

        val decoded = decodeTx3gTitle(sample.array(), sample.capacity(), 7)
        assertEquals(rawTitle, decoded)
    }
}
// END OF FILE
