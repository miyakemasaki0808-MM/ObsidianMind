package com.example.newproject.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.newproject.testing.FakeVaultDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Before
    fun setUp() {
        FakeVaultDocumentsProvider.cacheRootHolder =
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        FakeVaultDocumentsProvider.reset()
    }

    @Test
    fun 入れ子のノートを相対パスつきで集める() = runBlocking {
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
    fun 読めなかったフォルダは不在ではなく不完全として返る() = runBlocking {
        FakeVaultDocumentsProvider.putFile("root.md")
        FakeVaultDocumentsProvider.putFile("ideas/habit.md")
        FakeVaultDocumentsProvider.makeUnreadable("ideas")

        val scan = requireNotNull(browser().current()).collectAllNotes()

        assertEquals(listOf("root.md"), scan.notes.map { it.vaultRelativePath })
        assertEquals(setOf("ideas"), scan.unreadableFolderPaths)
    }

    /** ルートが読めなければ、Vault全体が不完全になる（1件も見えないのに「不在」と言わない）。 */
    @Test
    fun ルートが読めなければVault全体が不完全になる() = runBlocking {
        FakeVaultDocumentsProvider.putFile("root.md")
        FakeVaultDocumentsProvider.makeUnreadable("")

        val scan = requireNotNull(browser().current()).collectAllNotes()

        assertTrue("1件も見えないはず", scan.notes.isEmpty())
        assertFalse("不完全であることが伝わっていない", scan.unreadableFolderPaths.isEmpty())
    }

    @Test
    fun 第一階層のフォルダだけを名前順に返す() = runBlocking {
        FakeVaultDocumentsProvider.putFolder("journal")
        FakeVaultDocumentsProvider.putFolder("ideas")
        FakeVaultDocumentsProvider.putFolder("ideas/2026")
        FakeVaultDocumentsProvider.putFile("root.md")

        val folders = requireNotNull(browser().current()).listTopLevelFolders()

        assertEquals(listOf("ideas", "journal"), folders.map { it.name })
    }

    /** 補記は作成→一覧→削除まで実物のSAFで通る。 */
    @Test
    fun 補記を保存して一覧に出し削除できる() = runBlocking {
        val handle = requireNotNull(browser().current())

        val saved = handle.createAnnotationFile(
            sanitizedTitle = "習慣について",
            timestamp = "20260808_2015",
            content = "# 補記\n\n本文です。"
        )
        assertNotNull(saved.ref)

        val listed = handle.listAnnotationFiles()
        assertEquals(1, listed.size)
        assertEquals(
            "保存後の実名が一覧と一致しない",
            saved.displayName,
            listed.single().name
        )

        assertTrue("削除に失敗した", handle.deleteDocument(saved.ref))
        assertTrue("削除後も一覧に残っている", handle.listAnnotationFiles().isEmpty())
    }

    /**
     * 同じノートを同じ分に2回保存すると、**プロバイダが名前を変える**。
     *
     * `SavedAnnotation.displayName` が予測値ではなく保存後の実名を返す契約は、
     * これが理由（→ NoteRepository の `SavedAnnotation`）。
     */
    @Test
    fun 同名の補記は実名が変わり一覧と一致する() = runBlocking {
        val handle = requireNotNull(browser().current())

        val first = handle.createAnnotationFile("習慣について", "20260808_2015", "1本目")
        val second = handle.createAnnotationFile("習慣について", "20260808_2015", "2本目")

        assertFalse("同じ名前で2件作られている", first.displayName == second.displayName)
        assertEquals(
            listOf(first.displayName, second.displayName).sorted(),
            handle.listAnnotationFiles().map { it.name }.sorted()
        )
    }
}
