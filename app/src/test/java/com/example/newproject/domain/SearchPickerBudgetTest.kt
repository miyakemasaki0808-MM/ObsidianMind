package com.example.newproject.domain

import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.PromptLimits
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **AIピッカーの「提示集合」と「許可集合」を一致させる。**
 *
 * 候補一覧は入力予算内へ収める都合で、末尾の行がプロンプトから落ちる。
 * 一方で応答の照合表を**予算適用前の全候補**から作ると、
 * **見せていないノートをモデルが指しても正規のAI結果として通ってしまう。**
 * 「候補一覧からだけ選ぶ」というプロンプト契約と、アプリが受理する集合がずれる。
 *
 * 蒸留が `DistillPrompt.validIds` で最初から持っていた契約と同じものを、こちらでも守る。
 */
class SearchPickerBudgetTest {

    @Test
    fun `予算で提示から落ちた候補のIDは応答で返ってきても受理しない`() = runBlocking {
        // タイトルは `LABEL_CHARACTERS` で切られるので、切った長さの行を並べて予算を溢れさせる。
        // クエリと語が重ならない名前にする。**キーワード一致のフォールバックと混同しないため** —
        // ここで見たいのは「AIが返したから受理された」経路だけである。
        val notes = ('A'..'J').map { note(it.toString().repeat(PromptLimits.LABEL_CHARACTERS)) }
        val client = FakeAiClient(onGenerate = { "P10" })

        val result = SearchPickerUseCase(client).pick("探して", notes)

        val prompt = requireNotNull(client.lastPrompt)
        assertFalse("前提が崩れている: 10件目がプロンプトに載っている", prompt.contains("P10 |"))
        assertTrue(prompt.length <= PromptLimits.MAX_PROMPT_CHARACTERS)

        val success = result as PickerResult.Success
        assertFalse(
            "提示していないノートがAI結果として受理された",
            success.notes.any { it.ref == notes.last().ref }
        )
    }

    @Test
    fun `長いタイトルは切って提示し、選ばれたら正式名のノートへ戻す`() = runBlocking {
        // 照合キーはIDなので、タイトルを切っても結果は落ちない。
        // 切らずに行ごと落とすと、長いタイトルのノートは永久に選ばれなくなる。
        val long = "Z".repeat(2_500)
        val notes = listOf(note(long)) + (1..5).map { note("候補$it") }
        val client = FakeAiClient(onGenerate = { "P01" })

        val success = SearchPickerUseCase(client).pick("探して", notes) as PickerResult.Success

        val prompt = requireNotNull(client.lastPrompt)
        assertTrue(prompt.contains("P01 | " + "Z".repeat(PromptLimits.LABEL_CHARACTERS)))
        assertFalse(prompt.contains("Z".repeat(PromptLimits.LABEL_CHARACTERS + 1)))
        assertEquals(listOf(long), success.notes.map { it.title })
    }

    private fun note(name: String) = NoteFile(name = name, ref = DocumentRef("doc://$name"))
}
