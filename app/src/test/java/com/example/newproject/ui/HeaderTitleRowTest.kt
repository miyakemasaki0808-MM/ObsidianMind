package com.example.newproject.ui

import com.example.newproject.ui.component.headerTrailingBesideTitle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 見出しの丸ボタンを題名の行に並べるか。**題名を1行で置いた幅とボタンが収まらないときだけ、ボタンを題名の下の行へ回す**
 * （大きい文字の左右2列で、題名が語の途中で割れた → system/theme_and_ui_refactor.md）。
 */
class HeaderTitleRowTest {

    @Test
    fun `題名と間とボタンがちょうど収まれば題名の行に並べる`() {
        assertTrue(headerTrailingBesideTitle(rowWidth = 300, titleWidth = 150, leadingWidth = null, trailingWidth = 138, gap = 12))
    }

    @Test
    fun `1つでもはみ出せばボタンを下の行へ回す`() {
        assertFalse(headerTrailingBesideTitle(rowWidth = 299, titleWidth = 150, leadingWidth = null, trailingWidth = 138, gap = 12))
    }

    @Test
    fun `左の部品があれば、その幅と間も数える`() {
        assertTrue(headerTrailingBesideTitle(rowWidth = 352, titleWidth = 150, leadingWidth = 40, trailingWidth = 138, gap = 12))
        assertFalse(headerTrailingBesideTitle(rowWidth = 351, titleWidth = 150, leadingWidth = 40, trailingWidth = 138, gap = 12))
    }
}
