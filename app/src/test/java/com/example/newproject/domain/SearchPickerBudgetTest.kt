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
 * 候補タイトル一覧は入力予算内へ収める都合で、長いタイトルの行がプロンプトから落ちる。
 * 一方で応答の照合表を**予算適用前の全候補**から作ると、
 * **見せていないノートをモデルが返しても正規のAI結果として通ってしまう。**
 * 「候補一覧からだけ選ぶ」というプロンプト契約と、アプリが受理する集合がずれる。
 *
 * 蒸留が `DistillPrompt.validIds` で最初から持っていた契約と同じものを、こちらでも守る。
 */
class SearchPickerBudgetTest {

    @Test
    fun `予算で提示から落ちたタイトルは応答で返ってきても受理しない`() = runBlocking {
        // 単独で候補予算（PICKER_CANDIDATES_CHARACTERS）を超える長さにする。
        // 位置や他候補の数に依存させず、必ず提示から落ちるようにするため。
        // クエリと語が重ならない名前にする。**キーワード一致のフォールバックと混同しないため** —
        // ここで見たいのは「AIが返したから受理された」経路だけである。
        val dropped = "Z".repeat(2_500)
        // 候補が上限（40件）以下なら再現率カットが働かないので、並びが安定する。
        val notes = listOf(note(dropped)) + (1..10).map { note("短い候補$it") }

        val client = FakeAiClient(onGenerate = { dropped })
        val result = SearchPickerUseCase(client).pick("短い候補を探して", notes)

        val prompt = requireNotNull(client.lastPrompt)
        assertFalse("前提が崩れている: 長いタイトルがプロンプトに載っている", prompt.contains(dropped))
        assertTrue(prompt.length <= PromptLimits.MAX_PROMPT_CHARACTERS)

        val success = result as PickerResult.Success
        assertFalse(
            "提示していないノートがAI結果として受理された",
            success.notes.any { it.title == dropped }
        )
    }

    @Test
    fun `提示したタイトルは従来どおり解決される`() = runBlocking {
        val notes = (1..5).map { note("候補$it") }
        val client = FakeAiClient(onGenerate = { "候補2\n候補4" })

        val success = SearchPickerUseCase(client).pick("探して", notes) as PickerResult.Success

        assertEquals(listOf("候補2", "候補4"), success.notes.map { it.title })
        assertTrue(success.isAiAssisted)
    }

    private fun note(name: String) = NoteFile(name = name, ref = DocumentRef("doc://$name"))
}
