package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.data.NoteFieldStore
import com.example.newproject.controller.NoteFieldController
import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteField
import com.example.newproject.model.NoteFieldClassification
import com.example.newproject.model.NoteFieldStateWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分野判定の Controller を固定する（→ `docs/dev/features/note_field_color.md` 判断8・判断16）。
 *
 * **見ているのは「いつ呼ぶか」と「何を書くか」だけ。** 分類の当たり外れは主観なので測らない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteFieldControllerTest {

    private val ref = DocumentRef("content://note/1")

    private class RecordingWriter : NoteFieldStateWriter {
        var value: Map<DocumentRef, NoteFieldClassification> = emptyMap()

        /** **書き込み回数。** 結果が同じでも、書けば画面は再描画される（色が点滅する）。 */
        var updates = 0
        override val current get() = value
        override fun update(
            transform: (Map<DocumentRef, NoteFieldClassification>) -> Map<DocumentRef, NoteFieldClassification>
        ) {
            updates++
            value = transform(value)
        }
    }

    /** 永続の代わり。**中身を数えたいだけ**なので、素の地図で足りる。 */
    private class RecordingStore : NoteFieldStore {
        val saved = LinkedHashMap<String, NoteFieldClassification.Confirmed>()
        var cleared = 0
        override fun load(vaultKey: String) = saved.toMap()
        override fun save(vaultKey: String, pathKey: String, confirmed: NoteFieldClassification.Confirmed) {
            saved[pathKey] = confirmed
        }
        override fun clear(vaultKey: String) {
            saved.clear()
            cleared++
        }
    }

    private fun controller(
        scope: TestScope,
        client: FakeAiClient,
        writer: RecordingWriter,
        store: NoteFieldStore? = null
    ) = NoteFieldController(
        scope = scope,
        aiClient = client,
        state = writer,
        excerptDispatcher = StandardTestDispatcher(scope.testScheduler),
        store = store,
        vaultKey = { "vault" }
    )

    /** 確定は永続する。**保存はこの機能の前提条件**（→ 判断3）。 */
    @Test
    fun `確定は永続される`() = runTest {
        val store = RecordingStore()
        val writer = RecordingWriter()

        controller(this, FakeAiClient(onGenerate = { "F1" }), writer, store)
            .classify(ref, "本文", "技術/Flow.md")
        advanceUntilIdle()

        assertEquals(1, store.saved.size)
        assertEquals(NoteField.Technical, store.saved.values.single().field)
    }

    /** **暫定は永続しない。** 走査で作り直せる導出値である（→ 判断14）。 */
    @Test
    fun `読めなかったときは永続しない`() = runTest {
        val store = RecordingStore()
        val writer = RecordingWriter()
        writer.value = mapOf(ref to NoteFieldClassification.Provisional(NoteField.Living))

        controller(this, FakeAiClient(onGenerate = { "散文" }), writer, store)
            .classify(ref, "本文", "料理/カレー.md")
        advanceUntilIdle()

        assertTrue(store.saved.isEmpty())
    }

    /**
     * **鍵が作れないものは永続しない。**
     *
     * さがす経由で相対パスが取れないことがある。無理に保存すると、
     * 次回どのノートの結果か分からなくなる（表示はメモリで効く）。
     */
    @Test
    fun `相対パスが無ければ永続しない`() = runTest {
        val store = RecordingStore()
        val writer = RecordingWriter()

        controller(this, FakeAiClient(onGenerate = { "F1" }), writer, store)
            .classify(ref, "本文", "")
        advanceUntilIdle()

        assertTrue("メモリには載る", writer.value.containsKey(ref))
        assertTrue("永続はしない", store.saved.isEmpty())
    }

    @Test
    fun `選ばれたIDが確定として索引へ入る`() = runTest {
        val client = FakeAiClient(onGenerate = { "F1" })
        val writer = RecordingWriter()

        controller(this, client, writer).classify(ref, "本文", "技術/Flow.md")
        advanceUntilIdle()

        val stored = writer.value[ref] as NoteFieldClassification.Confirmed
        assertEquals(NoteField.Technical, stored.field)
        assertTrue("入力版が空だと、同じ入力かどうかを判定できない", stored.inputVersion.isNotEmpty())
    }

    /** **「該当なし」は正常な確定。** 無彩色になるが、未判定とは違って再判定しない。 */
    @Test
    fun `NONE は分野なしの確定として入る`() = runTest {
        val client = FakeAiClient(onGenerate = { "NONE" })
        val writer = RecordingWriter()

        controller(this, client, writer).classify(ref, "本文", "雑記.md")
        advanceUntilIdle()

        val stored = writer.value[ref] as NoteFieldClassification.Confirmed
        assertNull(stored.field)
    }

    /** 読めなかった応答は**保存しない**。暫定のまま据え置く。 */
    @Test
    fun `読めなかった応答は何も書かない`() = runTest {
        val client = FakeAiClient(onGenerate = { "たぶん技術だと思います" })
        val writer = RecordingWriter()
        writer.value = mapOf(ref to NoteFieldClassification.Provisional(NoteField.Living))

        controller(this, client, writer).classify(ref, "本文", "料理/カレー.md")
        advanceUntilIdle()

        assertEquals(NoteFieldClassification.Provisional(NoteField.Living), writer.value[ref])
    }

    /**
     * **同じ入力版の確定があれば AI を呼ばない。**
     *
     * ここが効かないと、ノートを開くたびに Nano が走って待ち行列が伸び続ける
     * （保存はこの機能の前提条件である → 判断3）。
     */
    @Test
    fun `同じ入力版の確定があれば生成しない`() = runTest {
        var calls = 0
        val client = FakeAiClient(onGenerate = { calls++; "F1" })
        val writer = RecordingWriter()
        val target = controller(this, client, writer)

        target.classify(ref, "本文", "技術/Flow.md")
        advanceUntilIdle()
        target.classify(ref, "本文", "技術/Flow.md")
        advanceUntilIdle()

        assertEquals("2回目は索引Aの確定で足りる", 1, calls)
    }

    /**
     * **本文が変われば入力版が変わり、再判定する。**
     *
     * 「一方向」なのは同じ入力版の中だけである。
     */
    @Test
    fun `本文が変われば再判定する`() = runTest {
        var calls = 0
        val client = FakeAiClient(onGenerate = { calls++; "F1" })
        val writer = RecordingWriter()
        val target = controller(this, client, writer)

        target.classify(ref, "本文", "技術/Flow.md")
        advanceUntilIdle()
        target.classify(ref, "書き換えた本文", "技術/Flow.md")
        advanceUntilIdle()

        assertEquals(2, calls)
    }

    /**
     * **索引Bに当たれば AI を呼ばない。**
     *
     * 同じ入力が別のノートで現れたとき（複製、**同名のままの移動**）に効く自己修復でもある。
     * 改名は入力が変わるので当たらない — タイトルはプロンプトへ載るためである。
     */
    @Test
    fun `同じ入力の別ノートは索引Bから復元する`() = runTest {
        var calls = 0
        val client = FakeAiClient(onGenerate = { calls++; "F2" })
        val writer = RecordingWriter()
        val target = controller(this, client, writer)
        val other = DocumentRef("content://note/2")

        target.classify(ref, "同じ本文", "試験/A.md")
        advanceUntilIdle()
        target.classify(other, "同じ本文", "試験/過去問/A.md")
        advanceUntilIdle()

        assertEquals("入力が同じなら結果も同じ。生成は1回でよい", 1, calls)
        assertEquals(
            NoteField.Learning,
            (writer.value[other] as NoteFieldClassification.Confirmed).field
        )
    }

    /**
     * **「該当なし」も索引Bへ載る。**
     *
     * 値の null を「不在」と読むと、`NONE` を返したノートだけ毎回 Nano を呼び直すことになる。
     * 該当なしは正常な確定であって、判定していないことではない。
     */
    @Test
    fun `該当なしの結果も索引Bから復元する`() = runTest {
        var calls = 0
        val client = FakeAiClient(onGenerate = { calls++; "NONE" })
        val writer = RecordingWriter()
        val target = controller(this, client, writer)
        val other = DocumentRef("content://note/2")

        target.classify(ref, "同じ本文", "雑記/A.md")
        advanceUntilIdle()
        target.classify(other, "同じ本文", "雑記/古い/A.md")
        advanceUntilIdle()

        assertEquals("2件目は索引Bで足りる", 1, calls)
        val stored = writer.value[other] as NoteFieldClassification.Confirmed
        assertNull("該当なしとして復元される", stored.field)
    }

    /**
     * **永続の確定から索引Bを作り直せる（P2-5）。**
     *
     * 索引Bを永続しないという判断は、これがあって初めて成立する。呼ばないと、
     * **移動・複製した先で保存済みの結果を再利用できず、再起動のたびに生成し直す。**
     */
    @Test
    fun `永続の確定から索引Bを作り直すと生成しない`() = runTest {
        var calls = 0
        val client = FakeAiClient(onGenerate = { calls++; "F1" })
        val writer = RecordingWriter()
        val target = controller(this, client, writer)

        // 1回目のセッションで確定し、その入力版を控える。
        target.classify(ref, "同じ本文", "技術/A.md")
        advanceUntilIdle()
        val confirmed = writer.value[ref] as NoteFieldClassification.Confirmed
        assertEquals(1, calls)

        // 新しいセッション相当。索引Bは空だが、永続の確定から作り直す。
        target.clearVaultScoped()
        writer.value = emptyMap()
        target.restoreAnswers(listOf(confirmed))

        // 移動先を開く。**ファイル名は同じ**（タイトルは入力の一部なので、
        // 改名していたら入力が変わり、再判定になるのが正しい）。
        target.classify(DocumentRef("content://note/2"), "同じ本文", "技術/古い/A.md")
        advanceUntilIdle()

        assertEquals("索引Bから復元できるので生成しない", 1, calls)
    }

    /** **「該当なし」も作り直せる。** 値の null を不在と読むと、ここだけ毎回生成し直す。 */
    @Test
    fun `該当なしの確定からも索引Bを作り直せる`() = runTest {
        var calls = 0
        val client = FakeAiClient(onGenerate = { calls++; "NONE" })
        val writer = RecordingWriter()
        val target = controller(this, client, writer)

        target.classify(ref, "同じ本文", "雑記/A.md")
        advanceUntilIdle()
        val confirmed = writer.value[ref] as NoteFieldClassification.Confirmed

        target.clearVaultScoped()
        writer.value = emptyMap()
        target.restoreAnswers(listOf(confirmed))
        target.classify(DocumentRef("content://note/2"), "同じ本文", "雑記/古い/A.md")
        advanceUntilIdle()

        assertEquals(1, calls)
        assertNull((writer.value[DocumentRef("content://note/2")] as NoteFieldClassification.Confirmed).field)
    }

    /**
     * **失効を見つけたら、AIが使えなくても古い確定を残さない（P2-4）。**
     *
     * 本文が変わって版が失効したのに、AI未取得・失敗のときに古い色とラベルが残っていた。
     * 「失敗したらヒントへ縮退する」という仕様（判断8）と食い違う。
     */
    @Test
    fun `失効した確定はAIが使えなくてもヒントへ落ちる`() = runTest {
        val client = FakeAiClient(onGenerate = { "F1" })
        client.availability = AiAvailability.NeedsDownload
        val writer = RecordingWriter()
        writer.value = mapOf(
            ref to NoteFieldClassification.Confirmed(NoteField.Technical, inputVersion = "旧い版")
        )

        controller(this, client, writer).classify(ref, "料理の本文", "料理/カレー.md")
        advanceUntilIdle()

        assertEquals(
            "ヒント由来の暫定へ落ちる",
            NoteFieldClassification.Provisional(NoteField.Living),
            writer.value[ref]
        )
    }

    /** ヒントが無ければ未判定（無彩色）へ落とす。**古い色を残さない。** */
    @Test
    fun `失効した確定はヒントが無ければ未判定へ落ちる`() = runTest {
        val client = FakeAiClient(onGenerate = { "F1" })
        client.availability = AiAvailability.Unsupported
        val writer = RecordingWriter()
        writer.value = mapOf(
            ref to NoteFieldClassification.Confirmed(NoteField.Technical, inputVersion = "旧い版")
        )

        controller(this, client, writer).classify(ref, "本文", "0500_000F/0500_000F_B006.md")
        advanceUntilIdle()

        assertNull("索引から外れる", writer.value[ref])
    }

    /**
     * **有効な確定のときは索引Aへ書かない。**
     *
     * 落として索引Bから戻すと**結果は同じ**だが、書けば画面は再描画される —
     * ノートを開くたびに**色が一度ヒントへ点滅する。**
     * だから「値が変わらないこと」ではなく**「書かないこと」で見る。**
     */
    @Test
    fun `有効な確定のときは索引へ書かない`() = runTest {
        val client = FakeAiClient(onGenerate = { "F1" })
        val writer = RecordingWriter()
        val target = controller(this, client, writer)

        target.classify(ref, "本文", "技術/Flow.md")
        advanceUntilIdle()
        val confirmed = writer.value[ref]

        val updatesAfterFirst = writer.updates

        client.availability = AiAvailability.Unsupported
        target.classify(ref, "本文", "技術/Flow.md")
        advanceUntilIdle()

        assertEquals("値は変わらない", confirmed, writer.value[ref])
        assertEquals("そもそも書かない", updatesAfterFirst, writer.updates)
    }

    /** 恒久非対応の端末では**呼ばない**。ヒント由来の暫定がそのまま残る。 */
    @Test
    fun `恒久非対応なら生成しない`() = runTest {
        var calls = 0
        val client = FakeAiClient(onGenerate = { calls++; "F1" })
        client.availability = AiAvailability.Unsupported
        val writer = RecordingWriter()

        controller(this, client, writer).classify(ref, "本文", "技術/Flow.md")
        advanceUntilIdle()

        assertEquals(0, calls)
        assertTrue("非対応でも索引を汚さない", writer.value.isEmpty())
    }

    /** 未DLでも**DLを始めない**。色が付くのが遅れるだけなので、待ってまで通さない。 */
    @Test
    fun `モデル未取得ならDLを始めずに諦める`() = runTest {
        var calls = 0
        val client = FakeAiClient(onGenerate = { calls++; "F1" })
        client.availability = AiAvailability.NeedsDownload
        val writer = RecordingWriter()

        controller(this, client, writer).classify(ref, "本文", "技術/Flow.md")
        advanceUntilIdle()

        assertEquals(0, calls)
    }

    /**
     * **連続失敗は止まるが、永久には止まらない。**
     *
     * 止まらないと散文しか返さないモデルで呼び続ける。永久に止まると、
     * 前回の「永久確定」を別の形で残すことになる（→ 判断8）。
     */
    @Test
    fun `連続失敗で抑制され、可用性の回復で解ける`() = runTest {
        var calls = 0
        val client = FakeAiClient(onGenerate = { calls++; "読めない応答" })
        val writer = RecordingWriter()
        val target = controller(this, client, writer)

        repeat(4) {
            target.classify(ref, "本文", "技術/Flow.md")
            advanceUntilIdle()
        }
        assertEquals("2回で抑制へ入る", 2, calls)

        // 可用性が一度落ちて戻ると、抑制が解ける。
        client.availability = AiAvailability.NeedsDownload
        target.classify(ref, "本文", "技術/Flow.md")
        advanceUntilIdle()
        client.availability = AiAvailability.Ready
        target.classify(ref, "本文", "技術/Flow.md")
        advanceUntilIdle()

        assertEquals("回復したので、また試せる", 3, calls)
    }

    /** Vault切替で索引Bと抑制を捨てる。**別Vaultの結果と失敗回数を持ち越さない。** */
    @Test
    fun `Vault切替で索引Bを捨てる`() = runTest {
        var calls = 0
        val client = FakeAiClient(onGenerate = { calls++; "F1" })
        val writer = RecordingWriter()
        val target = controller(this, client, writer)

        target.classify(ref, "本文", "技術/Flow.md")
        advanceUntilIdle()
        target.clearVaultScoped()
        writer.value = emptyMap()
        target.classify(ref, "本文", "技術/Flow.md")
        advanceUntilIdle()

        assertEquals("索引Bを捨てたので、もう一度生成する", 2, calls)
    }
}
