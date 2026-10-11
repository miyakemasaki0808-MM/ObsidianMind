package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 頭の情報の塊（[headMetadataRun]）と、それを組み立てるラベル行（[isLabelLine]）を固定する。
 * 共有の前処理なので、**用途に依らない形**だけをここで見る（扉での効き方は `BookletCoverLineTest`）。
 */
class HeadMetadataRunTest {

    @Test
    fun `最初の読める行から3行続くラベル行を塊にする`() {
        val lines = listOf("", "開始日　　：2025/05/23", "現在位置　：第2章から", "ステータス：読書中", "", "人材活用")

        assertEquals(1..3, headMetadataRun(lines))
    }

    @Test
    fun `2行のラベル行は塊にしない`() {
        assertEquals(IntRange.EMPTY, headMetadataRun(listOf("日付: 2026-04-29", "プロジェクト: 天気概況")))
    }

    @Test
    fun `本文の行より後ろのラベル行は頭の塊にしない`() {
        val lines = listOf("本文の文。", "用語A：説明", "用語B：説明", "用語C：説明")

        assertEquals(IntRange.EMPTY, headMetadataRun(lines))
    }

    @Test
    fun `空行は塊を切る`() {
        assertEquals(IntRange.EMPTY, headMetadataRun(listOf("a：1", "b：2", "", "c：3")))
    }

    @Test
    fun `値の無いラベル行もラベル行である`() {
        assertTrue("現在位置　：".isLabelLine())
        assertTrue("状態: 実装済み".isLabelLine())
    }

    @Test
    fun `時刻とURLと長すぎる前置きはラベル行にしない`() {
        assertFalse("07:00 起床".isLabelLine())
        assertFalse("https://example.com/a".isLabelLine())
        assertFalse("これは二十字を超える長い前置きの文になっていて、そのあとに：説明が続く".isLabelLine())
    }
}
