package my.torrstream.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineSubtitlesTest {

    @Test
    fun parsesSrtWithTagsAndBom() {
        val srt = "﻿1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>Привет</i>,\r\nмир\r\n\r\n" +
                "2\r\n01:02:03,004 --> 01:02:05,000\r\n{\\an8}Сверху\r\n"
        val cues = OnlineSubtitles.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(OnlineSubtitles.Cue(1500, 3000, "Привет,\nмир"), cues[0])
        assertEquals(OnlineSubtitles.Cue(3723004, 3725000, "Сверху"), cues[1])
    }

    @Test
    fun parsesVttShortTimes() {
        val vtt = "WEBVTT\n\n00:05.250 --> 00:07.000 line:90%\nТекст\n"
        assertEquals(listOf(OnlineSubtitles.Cue(5250, 7000, "Текст")), OnlineSubtitles.parse(vtt))
    }

    @Test
    fun parsesAssDialogueWithCommasInText() {
        val ass = "[Script Info]\n\n[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\\i1}Привет,{\\b1} мир\\Nвторая\n"
        assertEquals(listOf(OnlineSubtitles.Cue(1500, 3000, "Привет, мир\nвторая")), OnlineSubtitles.parse(ass))
    }

    @Test
    fun decodesUtf8EvenWhenDeclaredCp1251() {
        val text = "Привет"
        assertEquals(text, OnlineSubtitles.decode(text.toByteArray(Charsets.UTF_8), "CP1251"))
    }

    @Test
    fun fallsBackToCp1251ForBrokenUtf8() {
        val text = "Привет"
        assertEquals(text, OnlineSubtitles.decode(text.toByteArray(charset("windows-1251")), ""))
    }

    @Test
    fun textAtShowsOverlappingCues() {
        val cues = listOf(
            OnlineSubtitles.Cue(1000, 5000, "a"),
            OnlineSubtitles.Cue(2000, 3000, "b"),
            OnlineSubtitles.Cue(6000, 7000, "c"),
        )
        assertEquals("", OnlineSubtitles.textAt(cues, 500))
        assertEquals("a\nb", OnlineSubtitles.textAt(cues, 2500))
        assertEquals("a", OnlineSubtitles.textAt(cues, 4000))
        assertEquals("", OnlineSubtitles.textAt(cues, 5500))
        assertEquals("c", OnlineSubtitles.textAt(cues, 6000))
    }
}
