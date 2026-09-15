package com.example.newproject.domain

import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **AIピッカーは候補をIDで受ける。** 関連ノート・再会カード・蒸留と同じ契約。
 *
 * タイトルで照合すると、AIが記号を足す・言い換える・翻訳した瞬間に候補が**黙って**落ち、
 * 「3件選ばせたのに1件しか出ない」が原因不明のまま起きる。
 * クエリはどの候補ともキーワードが重ならない語にしてある — 結果が出たら、それはAIの選択が受理されたからである。
 */
class SearchPickerIdContractTest {

    private val query = "探して"

    @Test
    fun `装飾されたIDの応答でも件数が減らない`() = runBlocking {
        val notes = (1..5).map { note("候補$it") }
        val response = "1. P02\n- P04 | 候補4\n`P01`"

        val success = pick(notes, response)

        assertEquals(listOf("候補2", "候補4", "候補1"), success.notes.map { it.title })
        assertTrue(success.isAiAssisted)
    }

    @Test
    fun `候補外のIDは破棄する`() = runBlocking {
        val notes = (1..5).map { note("候補$it") }

        val success = pick(notes, "P99\nP03\nC01")

        assertEquals(listOf("候補3"), success.notes.map { it.title })
    }

    @Test
    fun `タイトルで返されても受理しない`() = runBlocking {
        // 照合を2つ持つと契約が割れる。IDが1件も取れなければキーワード一致へ回る（ここでは0件）。
        val notes = (1..5).map { note("候補$it") }

        val success = pick(notes, "候補2\n候補4")

        assertEquals(emptyList<String>(), success.notes.map { it.title })
    }

    @Test
    fun `同名の別ノートをIDで区別する`() = runBlocking {
        val first = NoteFile(name = "メモ", ref = DocumentRef("doc://a/メモ"))
        val second = NoteFile(name = "メモ", ref = DocumentRef("doc://b/メモ"))

        val success = pick(listOf(first, second), "P02")

        assertEquals(listOf(second.ref), success.notes.map { it.ref })
    }

    @Test
    fun `記号や区切り文字を含むタイトルも解決する`() = runBlocking {
        val notes = listOf(note("C++ | Kotlin: 比較 [draft]"), note("P2P通信"), note("「引用」— 注記"))

        val success = pick(notes, "P03\nP01\nP02")

        assertEquals(
            listOf("「引用」— 注記", "C++ | Kotlin: 比較 [draft]", "P2P通信"),
            success.notes.map { it.title }
        )
    }

    @Test
    fun `IDに似たタイトル断片をIDと読まない`() = runBlocking {
        // `P2P通信` の先頭 `P2` を `P02` と読むと、モデルが返していない別の候補がAIの選択として出る。
        val notes = listOf(note("P2P通信"), note("料理の献立"), note("音楽理論"))

        assertEquals(emptyList<String>(), pick(notes, "P2P通信").notes.map { it.title })
        assertEquals(emptyList<String>(), pick(notes, "P01通信").notes.map { it.title })
        assertEquals(emptyList<String>(), pick(notes, "P012").notes.map { it.title })
        assertEquals(emptyList<String>(), pick(notes, "P99\nC01").notes.map { it.title })
    }

    @Test
    fun `IDに似たタイトルが候補にあっても正常なIDは解決する`() = runBlocking {
        val notes = listOf(note("P2P通信"), note("料理の献立"), note("音楽理論"))

        assertEquals(listOf("P2P通信"), pick(notes, "P01").notes.map { it.title })
        assertEquals(listOf("P2P通信"), pick(notes, "p1").notes.map { it.title })
        assertEquals(
            listOf("料理の献立", "音楽理論", "P2P通信"),
            pick(notes, "1. P02\n- P03 | 音楽理論\n`P01`").notes.map { it.title }
        )
    }

    private suspend fun pick(notes: List<NoteFile>, response: String): PickerResult.Success =
        SearchPickerUseCase(FakeAiClient(onGenerate = { response })).pick(query, notes) as PickerResult.Success

    private fun note(name: String) = NoteFile(name = name, ref = DocumentRef("doc://$name"))
}
