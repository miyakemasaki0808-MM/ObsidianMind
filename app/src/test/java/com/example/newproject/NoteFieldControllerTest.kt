package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.data.NoteFieldStore
import com.example.newproject.controller.NoteFieldController
import com.example.newproject.domain.indexNoteFieldHints
import com.example.newproject.domain.noteFieldHint
import com.example.newproject.domain.noteFieldInputVersion
import com.example.newproject.domain.noteFieldPathKey
import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteField
import com.example.newproject.model.NoteFieldClassification
import com.example.newproject.model.NoteFieldStateWriter
import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.NoteFile
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
     * **正常な行のあとに否定文が続く応答も、確定にしない（P2-2）。**
     *
     * 純関数側の検査だけでは「Controllerが同じ判定を通っているか」が分からない。
     * ここが落ちると、**否定されたほうの分野が永続され、同じ入力版では二度と直らない。**
     */
    @Test
    fun `否定文が続く応答は確定も永続もしない`() = runTest {
        val store = RecordingStore()
        val writer = RecordingWriter()
        writer.value = mapOf(ref to NoteFieldClassification.Provisional(NoteField.Living))

        controller(this, FakeAiClient(onGenerate = { "F1\nF1 ではなく F3 が適切です" }), writer, store)
            .classify(ref, "本文", "料理/カレー.md")
        advanceUntilIdle()

        assertTrue("永続しない", store.saved.isEmpty())
        assertEquals(
            "暫定のまま動かさない",
            NoteFieldClassification.Provisional(NoteField.Living),
            writer.value[ref]
        )
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
        target.restorePersisted(mapOf(noteFieldPathKey("技術/A.md") to confirmed))

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
        target.restorePersisted(mapOf(noteFieldPathKey("雑記/A.md") to confirmed))
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
     * **索引Bを失った後に古い入力へ戻すと、生成し直す。**
     *
     * これが索引Bを永続しない判断の**唯一の代償**である（→ 判断10・§6）。
     * 永続の索引Aは1ノート1レコードなので、H2で確定した時点でH1の答えは残らない。
     * **同じセッションのあいだは索引BにH1が残る**（上の検査）ので、ここと混同しない。
     */
    @Test
    fun `索引Bを失った後に古い入力へ戻すと生成し直す`() = runTest {
        var calls = 0
        val client = FakeAiClient(onGenerate = { calls++; "F1" })
        val writer = RecordingWriter()
        val store = RecordingStore()
        val target = controller(this, client, writer, store)

        target.classify(ref, "元の本文", "技術/Flow.md")
        advanceUntilIdle()
        // H2 で確定すると、**永続の索引Aは H2 で上書きされる。**
        target.classify(ref, "新しい本文", "技術/Flow.md")
        advanceUntilIdle()
        assertEquals(2, calls)

        // 再起動相当。メモリの索引Bを失い、永続から作り直す。
        target.clearVaultScoped()
        writer.value = emptyMap()
        target.restorePersisted(store.saved.toMap())

        target.classify(ref, "元の本文", "技術/Flow.md")
        advanceUntilIdle()

        assertEquals("作り直した索引BにはH2しか無い", 3, calls)
    }

    /**
     * **降格したノートは、次の走査でも旧確定へ戻らない（P2-1）。**
     *
     * 索引Aを暫定へ落としても、走査の補完材料に旧確定が残っていると
     * **「メモリに確定が無いノート」として補完され、失効を確認済みの色が戻る。**
     * ストアの再読込もAIの追加生成も要らずに起きるので、**降格単体の検査では捕まらない。**
     */
    @Test
    fun `降格したノートは再走査でも旧確定へ戻らない`() = runTest {
        val client = FakeAiClient(onGenerate = { "F1" })
        client.availability = AiAvailability.NeedsDownload
        val writer = RecordingWriter()
        val target = controller(this, client, writer)
        val stale = NoteFieldClassification.Confirmed(NoteField.Creative, inputVersion = "旧い版")
        val note = NoteFile(
            name = "カレー.md",
            ref = ref,
            vaultRelativePath = "料理/カレー.md"
        )
        // 起動復元→走査で、保存済みの確定が索引Aへ載っている状態を作る。
        target.restorePersisted(mapOf(noteFieldPathKey("料理/カレー.md") to stale))
        writer.value = indexNoteFieldHints(writer.value, listOf(note), target.restorableFields())
        assertEquals(stale, writer.value[ref])

        // 本文が変わっているのでAIを呼びたいが、モデルが無いので降格だけが起きる。
        target.classify(ref, "新しい本文", "料理/カレー.md")
        advanceUntilIdle()
        assertEquals(NoteFieldClassification.Provisional(NoteField.Living), writer.value[ref])

        // TTL失効後の再走査。**ここで戻らないこと。**
        writer.value = indexNoteFieldHints(writer.value, listOf(note), target.restorableFields())

        assertEquals(
            "失効を確認済みなので、ヒントの暫定のまま",
            NoteFieldClassification.Provisional(NoteField.Living),
            writer.value[ref]
        )
    }

    /**
     * **復元材料から外しても、永続の答えは索引Bに残る。**
     *
     * 失効した確定を「表示してよいもの」から外すことと、「その入力版での答え」を捨てることは別である。
     * ここが落ちると、**本文を元へ戻したときに生成し直す。**
     */
    @Test
    fun `本文を元へ戻せば生成せずに確定へ戻る`() = runTest {
        var calls = 0
        val client = FakeAiClient(onGenerate = { calls++; "F1" })
        client.availability = AiAvailability.NeedsDownload
        val writer = RecordingWriter()
        val target = controller(this, client, writer)
        val hint = noteFieldHint("料理/カレー.md")
        val original = noteFieldInputVersion(
            "カレー.md",
            NoteExcerpt("元の本文", isAbridged = false),
            hint
        )
        val stored = NoteFieldClassification.Confirmed(NoteField.Creative, original)
        target.restorePersisted(mapOf(noteFieldPathKey("料理/カレー.md") to stored))
        writer.value = mapOf(ref to stored)

        // 別の本文で開いて降格させる（AIは使えない）。
        target.classify(ref, "別の本文", "料理/カレー.md")
        advanceUntilIdle()
        assertEquals(NoteFieldClassification.Provisional(NoteField.Living), writer.value[ref])

        // 本文を元へ戻す。**索引Bが当たるので生成しない。**
        target.classify(ref, "元の本文", "料理/カレー.md")
        advanceUntilIdle()

        assertEquals("索引Bから戻すので生成しない", 0, calls)
        assertEquals(stored, writer.value[ref])
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

    /**
     * Vault切替で**復元材料も**捨てる。
     *
     * 残すと、**別Vaultの同名パスへ旧Vaultの確定が当たる**（鍵は相対パスのハッシュなので衝突する）。
     */
    @Test
    fun `Vault切替で復元材料も捨てる`() = runTest {
        val target = controller(this, FakeAiClient(onGenerate = { "F1" }), RecordingWriter())
        val note = NoteFile(
            name = "カレー.md",
            ref = ref,
            vaultRelativePath = "料理/カレー.md"
        )
        target.restorePersisted(
            mapOf(
                noteFieldPathKey("料理/カレー.md") to
                    NoteFieldClassification.Confirmed(NoteField.Creative, inputVersion = "旧Vault")
            )
        )

        target.clearVaultScoped()

        assertEquals(
            "旧Vaultの確定は補完に使わない",
            NoteFieldClassification.Provisional(NoteField.Living),
            indexNoteFieldHints(emptyMap(), listOf(note), target.restorableFields())[ref]
        )
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
