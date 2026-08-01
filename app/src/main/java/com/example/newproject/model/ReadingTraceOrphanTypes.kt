package com.example.newproject.model

// ---------------------------------------------------------------------------
// 孤児判定で層をまたいで共有する純データ型。
//
// 判定そのもの（`assessReadingTraceOrphans`）は `domain` にあるが、結果の型は
// `controller` と `ui` の両方が触るためここへ置く。`model` は葉なので何も import せず、
// これにより `ui` は `domain` を経由せずに結果を描ける。
// **`NoteExcerpt`（model）と `buildNoteExcerpt`（domain）と同じ切り分け**
// → architecture.md 2026-07-27。
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
