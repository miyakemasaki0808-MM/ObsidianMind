package com.example.newproject

import com.example.newproject.controller.AnnotationController
import com.example.newproject.model.state.AnnotationListState
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧補記ファイルの一覧・削除まわりを固定する（生成は [com.example.newproject.controller.RemarkController] へ移った）。
 *
 * **以前は一覧の世代照合と削除の失敗件数を検証できなかった。** 非nullの `Uri` と
 * `ContentResolver` を要するため素のJVMでは書けず、実機確認だけが担保だった。
 * [FakeVaultBrowser]（N-7 段階7）で外れたので、ここで押さえる。
 */
class AnnotationControllerTest {

    @Test
    fun `Vault切替で補記一覧が破棄される`() = runTest {
        val state = NoteUiStateStore(
            NoteUiState(annotationListState = AnnotationListState.Loading)
        )
        val controller = controller(state)

        controller.onVaultChanged()

        assertTrue(state.value.annotationListState is AnnotationListState.Idle)
    }

    @Test
    fun `補記一覧が読み込まれる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(annotationFiles = listOf(noteFile("習慣__補記_20260801_1200.md")))

        controller(state, FakeVaultBrowser(handle)).loadList()

        val success = state.value.annotationListState as AnnotationListState.Success
        assertEquals(listOf("習慣__補記_20260801_1200.md"), success.files.map { it.name })
        assertEquals(0, success.deleteFailureCount)
    }

    // さがす側は黙って返るが、補記は明示的に伝える。この差は意図的なので固定する。
    @Test
    fun `Vault未選択なら補記一覧はエラーを出す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state, FakeVaultBrowser(handle = null)).loadList()

        val error = state.value.annotationListState as AnnotationListState.Error
        assertEquals("Vault が選択されていません。", error.message)
    }

    /**
     * `cancel()` だけでは足りない経路。SAF列挙から戻った**直後**に切替が起きると、
     * 旧Vaultの補記が新Vaultの一覧として並ぶ。
     */
    @Test
    fun `列挙から戻る直前にVaultが変わったら一覧を書き換えない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        var generation = 0L
        val handle = FakeVaultHandle(
            annotationFiles = listOf(noteFile("旧Vaultの補記.md")),
            beforeEachCall = { generation++ }
        )

        controller(state, FakeVaultBrowser(handle), vaultGeneration = { generation }).loadList()

        assertFalse(state.value.annotationListState is AnnotationListState.Success)
    }

    @Test
    fun `一覧の取得に失敗したらエラー状態になる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(failure = IllegalStateException("列挙失敗"))

        controller(state, FakeVaultBrowser(handle)).loadList()

        assertEquals("列挙失敗", (state.value.annotationListState as AnnotationListState.Error).message)
    }

    // ── 削除 ─────────────────────────────────────────────────────────────────

    @Test
    fun `削除すると一覧を読み直す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val target = noteFile("消す補記.md")
        val handle = FakeVaultHandle(annotationFiles = emptyList())

        controller(state, FakeVaultBrowser(handle)).delete(target.ref)

        assertEquals(listOf(target.ref), handle.deletedRefs)
        val success = state.value.annotationListState as AnnotationListState.Success
        assertEquals(0, success.deleteFailureCount)
    }

    /**
     * 削除はSAFプロバイダの都合で失敗し得る。失敗を握りつぶすと利用者が
     * 「消えていない」ことに気づけないので、件数を一覧に添える。
     */
    @Test
    fun `削除に失敗したら件数が一覧に添えられる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(deleteSucceeds = false)

        controller(state, FakeVaultBrowser(handle)).delete(noteFile("消せない補記.md").ref)

        val success = state.value.annotationListState as AnnotationListState.Success
        assertEquals(1, success.deleteFailureCount)
    }

    @Test
    fun `一括削除は表示中の全件を消して失敗数を数える`() = runTest {
        val files = listOf(noteFile("a.md"), noteFile("b.md"), noteFile("c.md"))
        val state = NoteUiStateStore(
            NoteUiState(annotationListState = AnnotationListState.Success(files))
        )
        val handle = FakeVaultHandle(annotationFiles = emptyList(), deleteSucceeds = false)

        controller(state, FakeVaultBrowser(handle)).deleteAll()

        assertEquals(files.map { it.ref }, handle.deletedRefs)
        assertEquals(3, (state.value.annotationListState as AnnotationListState.Success).deleteFailureCount)
    }

    /**
     * 永続URI権限が残っている端末では、切替後も旧VaultのURIが有効なまま消せてしまう。
     * 1件ごとに世代を見ているのはそのため。
     */
    @Test
    fun `一括削除の途中でVaultが変わったら以降を消さない`() = runTest {
        val files = listOf(noteFile("a.md"), noteFile("b.md"), noteFile("c.md"))
        val state = NoteUiStateStore(
            NoteUiState(annotationListState = AnnotationListState.Success(files))
        )
        var generation = 0L
        val handle = FakeVaultHandle(beforeEachCall = { generation++ })

        controller(state, FakeVaultBrowser(handle), vaultGeneration = { generation }).deleteAll()

        assertEquals(1, handle.deletedRefs.size)
    }

    /**
     * [VaultHandle] は「処理の開始時に1回だけ取り、その1つを最後まで使う」規約。
     *
     * 削除は「消す → 一覧を読み直す」の2段だが、途中で引き直すと
     * **削除は旧Vaultへ・読み直しは新Vaultへ**という食い違いが起こる。
     * 表示は世代照合で弾かれるものの、切り替えた先へ無駄なSAF列挙が飛ぶ。
     */
    @Test
    fun `削除から一覧の読み直しまでVaultハンドルを取り直さない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val vault = FakeVaultBrowser(FakeVaultHandle())

        controller(state, vault).delete(noteFile("消す補記.md").ref)

        assertEquals(1, vault.currentCount)
    }

    private fun controller(
        state: NoteUiStateStore,
        vault: FakeVaultBrowser = FakeVaultBrowser(handle = null),
        vaultGeneration: () -> Long = { 0L }
    ) = AnnotationController(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        vault = vault,
        state = state.annotationListWriter,
        vaultGeneration = vaultGeneration
    )
}
