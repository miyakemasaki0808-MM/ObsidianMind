package com.example.newproject.domain

import com.google.mlkit.genai.common.GenAiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 失敗を画面の文へ変える1か所（→ `docs/dev/system/ui_copy.md` §2・AI の失敗の見せ方）。 */
class FailureTextTest {

    private val busy = GenAiException(RuntimeException("Quota exceeded"), GenAiException.ErrorCode.BUSY)
    private val other = IllegalStateException("java.io.IOException: EACCES (Permission denied)")

    @Test
    fun `回数制限で断られたら、要約・節の要約・蒸留のどれでも日本語で待てば使えると言う`() {
        listOf(FailureScene.Summary, FailureScene.SectionSummary, FailureScene.DistillStart).forEach { scene ->
            val text = failureText(busy, scene)
            assertTrue("$scene: $text", text.contains("混み合っています"))
            assertFalse("$scene: $text", text.contains("Quota"))
        }
        assertTrue("要約は開き直せることを言う", failureText(busy, FailureScene.Summary).contains("開き直せます"))
    }

    @Test
    fun `ほかの失敗は場面の文だけを返し、例外の文を出さない`() {
        FailureScene.entries.forEach { scene ->
            val text = failureText(other, scene)
            assertEquals(scene.text, text)
            // 「AI」のほかの英字（例外の文や SDK の語）を出さない。
            assertFalse("$scene: $text", text.replace("AI", "").any { it in 'A'..'Z' || it in 'a'..'z' })
        }
    }

    /** 例外の文をそのまま画面の状態へ入れる書き方を、失敗の文の経路から落とす。 */
    @Test
    fun `失敗の文の経路は例外の文と Unknown error を画面の状態へ入れない`() {
        val root = File("src/main/java/com/example/newproject")
        val unknown = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("\"Unknown error\"") }.map { it.name }.toList()
        assertTrue("Unknown error が残っている: $unknown", unknown.isEmpty())

        // 例外の文を状態へ入れる書き方（`e.message ?: …`）を、画面の状態を作る層から落とす。データの層は判定や記録にも使うので見ない。
        val stateLayers = listOf("controller", "domain").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        } + File(root, "NoteViewModel.kt") + File(root, "data/DistillWriteRepository.kt")
        stateLayers.forEach { file ->
            assertFalse("${file.name} が例外の文を画面の状態へ入れている", Regex("""\.message\s*\?:""").containsMatchIn(file.readText()))
        }
        // 文字列の中に例外の文や状態の文を埋め込む書き方（`"…: ${e.message}"`）を落とす。前置きを足すと失敗の文が二重になる。
        val embedding = Regex(""""[^"\n]*\$\{[A-Za-z.?]*message[^}]*\}""")
        val embedded = listOf("controller", "domain", "ui").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.filter { embedding.containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("文字列に失敗の文を埋め込んでいる: $embedded", embedded.isEmpty())
    }
}
