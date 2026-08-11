package com.example.newproject.model

/**
 * ノート本文を載せる「紙」の地色の段階。
 *
 * 表すのは**作成からの経過ではなく、最後に手を入れてからの経過**（＝放置期間）。
 * SAF は作成日を持たず `lastModified` しか返さないが、これは欠陥ではなく仕様として採った意味づけで、
 * 手を入れたノートの紙が白へ戻るのは手触りとして正しい。→ `docs/dev/features/note_age_paper.md`
 *
 * 段階の割り当ては絶対年数ではなく **Vault 内の相対順位**で行う（→ [NotePaperTone] を返す
 * `com.example.newproject.domain.notePaperTone`）。絶対閾値だと、若いVaultは全部 [Fresh]、
 * 古いVaultは全部 [Weathered] になって差が消える。
 *
 * **段階を増やすときは色と一緒に増やす。** 色は `ui.theme.NotePaperTones` が持ち、
 * `AppColorContrastTest` が全段階の比を強制する。
 */
enum class NotePaperTone {
    /** 最も新しい四半分。現行のパネル色そのままで、見た目は変わらない。 */
    Fresh,

    /** 新しい側から2番目の四半分。 */
    Settling,

    /** 古い側から2番目の四半分。 */
    Aged,

    /** 最も古い四半分。生成り色が最も強く出る。 */
    Weathered
}
