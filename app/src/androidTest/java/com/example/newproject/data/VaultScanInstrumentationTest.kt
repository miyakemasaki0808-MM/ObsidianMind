package com.example.newproject.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.newproject.model.DocumentRef
import com.example.newproject.testing.FakeVaultDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **実物のSAFを通す**走査と補記の読み書き（→ instrumentation_testing 段階2）。
 *
 * ## JVMでは書けない理由
 *
 * `NoteRepository` は `ContentResolver` と `DocumentsContract` を直に叩く。
 * 素のJVMでは `Uri` がスタブで例外を投げ、`ContentResolver` も作れない。
 * ここでは [FakeVaultDocumentsProvider]（debug ソースセットの `DocumentsProvider`）を
 * 相手に、**本番の `NoteRepository` と `SafVaultBrowser` をそのまま**動かす。
 *
 * ## 守っている一点
 *
 * **「ノートが無い」と「そのフォルダを読めなかった」を混ぜない。**
 * 痕跡の孤児判定は不在を根拠に削除するので、この区別が崩れると生きた痕跡を消す。
 * 実物のSAFでは読取失敗を意図的に起こせないため、この経路は
 * これまで実機の手動確認でしか触れなかった。
 */
@RunWith(AndroidJUnit4::class)
class VaultScanInstrumentationTest {

    private val contentResolver =
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    private fun browser() = SafVaultBrowser(
        contentResolver = contentResolver,
        repository = NoteRepository(),
        vaultUri = { FakeVaultDocumentsProvider.treeUri }
    )

    /** 実物の `ContentResolver` 経由で読む（偽Vaultの内部状態ではなくSAFの出力を見る）。 */
    private fun readText(ref: DocumentRef): String =
        requireNotNull(contentResolver.openInputStream(ref.toUri())) {
            "ストリームを開けなかった: $ref"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    @Before
    fun setUp() {
        FakeVaultDocumentsProvider.cacheRootHolder =
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        FakeVaultDocumentsProvider.reset()
    }

    @Test
    fun 入れ子のノートを相対パスつきで集める() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putFile("root.md")
        FakeVaultDocumentsProvider.putFile("ideas/habit.md")
        FakeVaultDocumentsProvider.putFile("ideas/2026/deep.md")
        FakeVaultDocumentsProvider.putFile("ideas/notes.txt")

        val scan = requireNotNull(browser().current()).collectAllNotes()

        assertEquals(
            listOf("ideas/2026/deep.md", "ideas/habit.md", "root.md"),
            scan.notes.map { it.vaultRelativePath }.sorted()
        )
        assertTrue("読めなかったフォルダは無いはず", scan.unreadableFolderPaths.isEmpty())
    }

    /**
     * 読めなかったフォルダは、**空フォルダとしてではなく不完全として**返る。
     *
     * ここが崩れると、同期途中のVaultで痕跡がまとめて削除候補になる。
     */
    @Test
    fun 読めなかったフォルダは不在ではなく不完全として返る() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putFile("root.md")
        FakeVaultDocumentsProvider.putFile("ideas/habit.md")
        FakeVaultDocumentsProvider.makeUnreadable("ideas")

        val scan = requireNotNull(browser().current()).collectAllNotes()

        assertEquals(listOf("root.md"), scan.notes.map { it.vaultRelativePath })
        assertEquals(setOf("ideas"), scan.unreadableFolderPaths)
    }

    /** ルートが読めなければ、Vault全体が不完全になる（1件も見えないのに「不在」と言わない）。 */
    @Test
    fun ルートが読めなければVault全体が不完全になる() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putFile("root.md")
        FakeVaultDocumentsProvider.makeUnreadable("")

        val scan = requireNotNull(browser().current()).collectAllNotes()

        assertTrue("1件も見えないはず", scan.notes.isEmpty())
        assertFalse("不完全であることが伝わっていない", scan.unreadableFolderPaths.isEmpty())
    }

    @Test
    fun 第一階層のフォルダだけを名前順に返す() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putFolder("journal")
        FakeVaultDocumentsProvider.putFolder("ideas")
        FakeVaultDocumentsProvider.putFolder("ideas/2026")
        FakeVaultDocumentsProvider.putFile("root.md")

        val folders = requireNotNull(browser().current()).listTopLevelFolders()

        assertEquals(listOf("ideas", "journal"), folders.map { it.name })
    }

    /**
     * **区切りだけが違うパスが、同じ実体を指さない。**
     *
     * 偽Vaultは document ID から実体ファイル名を作る。`/` を `_` へ置換していたため、
     * `a_b.md`（ID `root/a_b.md`）と `a/b.md`（ID `root/a/b.md`）が
     * **同じファイルへ潰れて後勝ちで上書き**されていた。
     * 現行のテストデータでは顕在化していなかったが、
     * **実プロバイダには無い衝突を観測する**土台になっていた。
     */
    @Test
    fun 区切りだけが違うパスは別の実体として扱われる() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putFile("a_b.md", content = "フラット側の本文")
        FakeVaultDocumentsProvider.putFile("a/b.md", content = "入れ子側の本文はこちらのほうが長い")

        val notes = requireNotNull(browser().current()).collectAllNotes().notes
            .associateBy { it.vaultRelativePath }

        val flat = requireNotNull(notes["a_b.md"]) { "フラット側が見つからない" }
        val nested = requireNotNull(notes["a/b.md"]) { "入れ子側が見つからない" }
        assertEquals(
            "区切りだけが違うパスが同じ実体を指している",
            "フラット側の本文",
            readText(flat.ref)
        )
        assertEquals("入れ子側の本文はこちらのほうが長い", readText(nested.ref))
    }

    /**
     * **同じパスを2回置いても列挙は1行のまま。**
     *
     * `nodes` は置換されるのに親の `childIds` へ無条件に足していたため、
     * 再投入すると**列挙だけが重複**していた。内容と更新日時だけが新しくなるのが正しい。
     */
    @Test
    fun 同じパスへ2回置いても列挙は重複しない() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putFile("ideas/habit.md", content = "1回目")
        FakeVaultDocumentsProvider.putFile("ideas/habit.md", content = "2回目の本文")

        val notes = requireNotNull(browser().current()).collectAllNotes().notes

        assertEquals("列挙が重複している", 1, notes.count { it.vaultRelativePath == "ideas/habit.md" })
        assertEquals("2回目の本文", readText(notes.single().ref))
    }

    /**
     * 旧補記ファイルの一覧と削除が実物のSAFで通る。
     *
     * **作成経路はもう無い。** 「AI補記メモ」は「ノートへのひとこと」へ作り直され、
     * 保存先は読書痕跡サイドカーへ移った（→ design/reflect_remark.md）。
     * 残っているのは、作り直す前に生成された `.md` を片付ける導線だけなので、
     * ここもファイルを**直接置いて**から一覧・削除を確かめる形にしてある。
     */
    @Test
    fun 旧補記ファイルを一覧に出して削除できる() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putFile(
            "_AI補記/習慣について__補記_20260808_2015.md",
            "# 補記\n\n本文です。"
        )
        val handle = requireNotNull(browser().current())

        val listed = handle.listAnnotationFiles()

        assertEquals(1, listed.size)
        assertEquals("習慣について__補記_20260808_2015.md", listed.single().name)
        assertTrue("削除に失敗した", handle.deleteDocument(listed.single().ref))
        assertTrue("削除後も一覧に残っている", handle.listAnnotationFiles().isEmpty())
    }

    /** 一覧はタイムスタンプの新しい順。ファイル名の辞書順ではない。 */
    @Test
    fun 旧補記の一覧はタイムスタンプの新しい順に並ぶ() = runBlocking<Unit> {
        FakeVaultDocumentsProvider.putFile("_AI補記/z__補記_20260101_0900.md", "古い")
        FakeVaultDocumentsProvider.putFile("_AI補記/a__補記_20260808_2015.md", "新しい")
        val handle = requireNotNull(browser().current())

        assertEquals(
            listOf("a__補記_20260808_2015.md", "z__補記_20260101_0900.md"),
            handle.listAnnotationFiles().map { it.name }
        )
    }

    /** フォルダが無ければ空。作る経路が無くなったので、これが通常の状態になる。 */
    @Test
    fun 補記フォルダが無ければ一覧は空になる() = runBlocking<Unit> {
        val handle = requireNotNull(browser().current())

        assertTrue(handle.listAnnotationFiles().isEmpty())
    }
}
