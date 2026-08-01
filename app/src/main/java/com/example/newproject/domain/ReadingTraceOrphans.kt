package com.example.newproject.domain

// ---------------------------------------------------------------------------
// 読書痕跡の孤児判定。Android型を持たない純粋部分なので素のJVMテストで固定する。
//
// **この判定が守るべき一点は「不在は証明ではない」こと。**
// 痕跡は `_ReadingTraces/<sha256(相対パス)>.json` に置かれ、対応するノートが
// Vault走査に現れなければ孤児に見える。しかし SAF の列挙は**フォルダ単位で失敗する**
// （プロバイダが子を返さない）ため、「見つからない」は「削除された」と
// 「そのフォルダが読めなかった」の両方を意味しうる。
//
// したがって遮断器も**フォルダ単位**に置く。故障の粒度と判定の粒度を合わせるのが要点で、
// 観測回数を重ねるだけでは持続的な列挙失敗を排除できない（毎回同じフォルダが失敗すれば
// 何回数えても欠け続ける）。
//
// **代償を明記する。** フォルダごと削除された場合も「同じフォルダから複数件が欠けた」に
// 見えるので、**まとめて消したノートの痕跡は掃除されない**。これは意図した割り切りで、
// この機能が狙うのは日常的に発生する単発の孤児のほう。安全側に倒している。
// ---------------------------------------------------------------------------

/** 判定の上限。既定値は「安全側」に寄せてある。 */
data class OrphanLimits(
    /**
     * 1フォルダから出してよい候補の数。既定 1 は「同じフォルダで2件以上欠けたら保留」。
     *
     * **既知の穴:** ノートが1件しかないフォルダの列挙が失敗すると、欠落も1件なので
     * この遮断器を通過する。痕跡を1件失うだけで `.md` は無傷なので受け入れる
     * （→ reflect_reading_trace §4「シンプル最優先」）。
     */
    val maxCandidatesPerFolder: Int = 1,

    /**
     * 全体の候補数の上限。超えたら判定ごと見送る。
     *
     * 安全ガードであると同時に**コスト上限**でもある。候補のパスは痕跡ファイルを
     * 読まないと分からないため、ここで打ち切らないと「Vaultが丸ごと見えない」状況で
     * 全ファイルを読みにいくことになる。
     */
    val maxTotalCandidates: Int = 100
)

/** 痕跡ファイルから読み出した、判定と表示に要る中身。 */
data class OrphanTraceInfo(
    val vaultRelativePath: String,
    val noteTitle: String,
    val totalVisitCount: Int,
    val lastVisitAtEpochMillis: Long?
)

/** 削除候補1件。画面にはこの単位で出す。 */
data class OrphanCandidate(
    val key: String,
    val vaultRelativePath: String,
    val noteTitle: String,
    val totalVisitCount: Int,
    val lastVisitAtEpochMillis: Long?
)

/** 候補から外した理由。**シャドーモードではこれ自体が観測対象になる。** */
enum class OrphanWithholdReason {
    /** 同じフォルダから複数件が同時に欠けた。フォルダ列挙の失敗と区別できない。 */
    FOLDER_WIDE_ABSENCE,

    /** そのフォルダの列挙自体が失敗している（走査が不完全）。 */
    UNREADABLE_FOLDER,

    /** 痕跡ファイルを読めず、何を消すことになるのか確認できない。 */
    UNRESOLVABLE
}

/** 判定ごと見送った理由。 */
enum class OrphanBlockReason {
    /** Vaultルートの列挙に失敗している。この状態では全ノートが不在に見える。 */
    VAULT_ROOT_UNREADABLE,

    /** 候補が多すぎる。通常運用では起こらないので、外部要因を疑う。 */
    TOO_MANY_CANDIDATES
}

/** 保留した一群。フォルダ単位でまとめて画面に理由を出す。 */
data class WithheldOrphans(
    val folderPath: String,
    val count: Int,
    val reason: OrphanWithholdReason
)

sealed interface OrphanAssessment {
    /**
     * 判定そのものを見送った。**候補はゼロ件ではなく「分からない」。**
     * ここを空の [Assessed] で代用すると「孤児は無かった」と読めてしまう。
     */
    data class Blocked(val reason: OrphanBlockReason, val candidateCount: Int) : OrphanAssessment

    data class Assessed(
        val orphans: List<OrphanCandidate>,
        val withheld: List<WithheldOrphans>
    ) : OrphanAssessment
}

/**
 * 保存済み痕跡のキー集合と、Vault走査の結果から孤児候補を割り出す。
 *
 * [noteKeys] は現存ノートの相対パスをキー化したもの。**集合差だけならファイルを
 * 1つも読まずに済む**（キーは相対パスのハッシュなので突き合わせは文字列比較で足りる）。
 * [resolve] を呼ぶのは差集合に残った候補だけで、しかも [OrphanLimits.maxTotalCandidates]
 * で件数が縛られている。
 *
 * [unreadableFolderPaths] は走査で列挙に失敗したフォルダの相対パス（ルートなら空文字）。
 *
 * [resolve] が null を返した候補は「中身を確認できなかった」として保留する。**消さない。**
 */
fun assessReadingTraceOrphans(
    traceKeys: Set<String>,
    noteKeys: Set<String>,
    unreadableFolderPaths: Set<String>,
    limits: OrphanLimits = OrphanLimits(),
    resolve: (key: String) -> OrphanTraceInfo?
): OrphanAssessment {
    // ルートが読めていないなら全ノートが不在に見える。候補を数える意味がない。
    if (ROOT_PATH in unreadableFolderPaths) {
        return OrphanAssessment.Blocked(OrphanBlockReason.VAULT_ROOT_UNREADABLE, candidateCount = 0)
    }

    val candidateKeys = traceKeys - noteKeys
    // 件数の判定は resolve の前に行う。後ろに置くと、異常時ほど大量に読むことになる。
    if (candidateKeys.size > limits.maxTotalCandidates) {
        return OrphanAssessment.Blocked(
            OrphanBlockReason.TOO_MANY_CANDIDATES,
            candidateCount = candidateKeys.size
        )
    }

    val resolved = mutableListOf<OrphanCandidate>()
    var unresolvable = 0
    // 並びを決定的にしておく（画面の順序と、テストの安定のため）。
    for (key in candidateKeys.sorted()) {
        val info = resolve(key)
        if (info == null) {
            unresolvable++
            continue
        }
        resolved += OrphanCandidate(
            key = key,
            vaultRelativePath = info.vaultRelativePath,
            noteTitle = info.noteTitle,
            totalVisitCount = info.totalVisitCount,
            lastVisitAtEpochMillis = info.lastVisitAtEpochMillis
        )
    }

    val orphans = mutableListOf<OrphanCandidate>()
    val withheld = mutableListOf<WithheldOrphans>()
    resolved.groupBy { parentVaultPath(it.vaultRelativePath) }
        .forEach { (folderPath, items) ->
            when {
                isUnderUnreadableFolder(folderPath, unreadableFolderPaths) ->
                    withheld += WithheldOrphans(
                        folderPath, items.size, OrphanWithholdReason.UNREADABLE_FOLDER
                    )
                // 故障はフォルダ単位で起きるので、まとまって欠けたら列挙失敗を疑う。
                items.size > limits.maxCandidatesPerFolder ->
                    withheld += WithheldOrphans(
                        folderPath, items.size, OrphanWithholdReason.FOLDER_WIDE_ABSENCE
                    )
                else -> orphans += items
            }
        }
    if (unresolvable > 0) {
        withheld += WithheldOrphans(ROOT_PATH, unresolvable, OrphanWithholdReason.UNRESOLVABLE)
    }

    return OrphanAssessment.Assessed(
        orphans = orphans.sortedBy { it.vaultRelativePath },
        withheld = withheld.sortedWith(compareBy({ it.reason.ordinal }, { it.folderPath }))
    )
}

/** Vaultルートを表す相対パス。ルート直下のノートの親はこれになる。 */
private const val ROOT_PATH = ""

/** `ideas/2026/habit.md` → `ideas/2026`、`habit.md` → `""`（ルート）。 */
internal fun parentVaultPath(vaultRelativePath: String): String =
    vaultRelativePath.substringBeforeLast('/', ROOT_PATH)

/**
 * [folderPath] が、列挙に失敗したフォルダ自身かその配下にあるか。
 *
 * 区切りを足して比較するのは前方一致の取り違えを避けるため
 * （`ideas2` が `ideas` の配下と見なされないように）。
 */
internal fun isUnderUnreadableFolder(folderPath: String, unreadableFolderPaths: Set<String>): Boolean =
    unreadableFolderPaths.any { unreadable ->
        folderPath == unreadable || folderPath.startsWith("$unreadable/")
    }
