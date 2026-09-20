package com.example.newproject

import com.example.newproject.data.ReadingTraceFolderStatus
import com.example.newproject.data.ReadingTraceKeyListing
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.ReadingTraceSaveResult
import com.example.newproject.data.ReadingTraceStore
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.ReunionKind

// 読書痕跡の2つの Controller（訪問と再会カード）のテストが共有する足場。
/** 現在選択中のVault。切替を再現するために書き換えられる。 */
internal class FakeVault(var key: String? = VAULT_A)

internal const val VAULT_A = "content://vault-a"
internal const val VAULT_B = "content://vault-b"

internal const val AI_SUMMARY = "これまで2回開いて、いずれも前半で止まっています。"

/** テスト用の余白メモ。日時は明示できる（合流の重複排除が日時を見るため）。 */
internal fun memoOf(text: String, at: Long = 1_000L, section: String? = null) =
    MarginMemo(text = text, writtenAtEpochMillis = at, sectionTitle = section)

/** 訪問 [count] 件を持つ痕跡。件数が2以上だとAI俯瞰要約の対象になる。 */
internal fun storedTrace(
    count: Int,
    path: String = "ideas/habit.md",
    aiSummary: String? = null,
    aiSummaryVisitCount: Int? = null
) = ReadingTrace(
    vaultRelativePath = path,
    noteTitle = "習慣について",
    documentId = "doc-1",
    visits = (1..count).map { ReadingVisit(it * 1_000L, "導入", 10 * it) },
    aiSummary = aiSummary,
    aiSummaryVisitCount = aiSummaryVisitCount,
    // 種別は「最後に試みた生成」に付く（→ validateReadingTrace）。
    aiSummaryKind = aiSummaryVisitCount?.let { ReunionKind.Overview }
)

internal class TestClock(private var current: Long = 1_000_000L) {
    fun now(): Long = current
    fun advance(millis: Long) {
        current += millis
    }
}

internal class FakePersistence : ReadingTracePersistence {
    val saved = mutableListOf<ReadingTrace>()
    val savedVaultKeys = mutableListOf<String>()
    val corruptPaths = mutableSetOf<String>()

    /**
     * 実体はあるのに読み取りだけ失敗する経路。
     *
     * **実Storeの `None` は不在と読み取り失敗の両方で返る**（gateway が null を返す
     * 経路が2つある）ので、「無い」と区別できないことをテストでも再現する。
     */
    val unreadablePaths = mutableSetOf<String>()
    var failSave = false

    /** この回数目の保存だけを失敗させる（1始まり）。先行・後続の順序が要る検証用。 */
    var failSaveOnAttempt: Int? = null
    var saveAttempts = 0
        private set

    private val files = mutableMapOf<String, ReadingTrace>()

    fun put(trace: ReadingTrace) {
        files[trace.vaultRelativePath] = trace
    }

    fun stored(path: String): ReadingTrace? = files[path]

    override fun folderStatus(): ReadingTraceFolderStatus = ReadingTraceFolderStatus.Ready

    /**
     * 読込の**最中**に割り込むための口。
     *
     * `appendMemo` の読込は同期I/Oで、**戻る頃には別のノートを開いている**ことがある。
     * その順序をテストで作るには、読込そのものの中で切り替えるのが最も確実である。
     */
    var onLoad: (() -> Unit)? = null

    override fun load(vaultRelativePath: String, vaultKey: String): ReadingTraceReadResult = run {
        onLoad?.invoke()
        loadInternal(vaultRelativePath)
    }

    private fun loadInternal(vaultRelativePath: String): ReadingTraceReadResult = when {
        vaultRelativePath in corruptPaths -> ReadingTraceReadResult.Corrupt("壊れています")
        vaultRelativePath in unreadablePaths -> ReadingTraceReadResult.None
        else -> files[vaultRelativePath]
            ?.let { ReadingTraceReadResult.Valid(it) }
            ?: ReadingTraceReadResult.None
    }

    override fun listKeys(vaultKey: String): ReadingTraceKeyListing =
        ReadingTraceKeyListing.Available(files.keys.map { ReadingTraceStore.keyFor(it) }.toSet())

    override fun loadByKey(key: String, vaultKey: String): ReadingTraceReadResult =
        files.keys.firstOrNull { ReadingTraceStore.keyFor(it) == key }
            ?.let { load(it, vaultKey) }
            ?: ReadingTraceReadResult.None

    override fun deleteByKey(key: String, vaultKey: String): Boolean =
        files.keys.firstOrNull { ReadingTraceStore.keyFor(it) == key }
            ?.let { files.remove(it) != null } ?: false

    override fun save(trace: ReadingTrace, vaultKey: String): ReadingTraceSaveResult {
        saveAttempts++
        savedVaultKeys += vaultKey
        if (failSave || failSaveOnAttempt == saveAttempts) {
            return ReadingTraceSaveResult.Failure("書き込めませんでした")
        }
        saved += trace
        files[trace.vaultRelativePath] = trace
        return ReadingTraceSaveResult.Success
    }
}
