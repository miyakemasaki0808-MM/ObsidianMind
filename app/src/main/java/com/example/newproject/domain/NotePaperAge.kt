package com.example.newproject.domain

import com.example.newproject.model.NoteFile
import com.example.newproject.model.NotePaperTone
import com.example.newproject.model.RelatedNote

/**
 * ノートの「放置期間」を Vault 内の相対順位で [NotePaperTone] へ写す。
 *
 * [lastModified] より**新しい**ノートが Vault 全体の何割を占めるかで段階を決める。
 * 割合が小さいほど自身が新しい側なので [NotePaperTone.Fresh] に寄る。
 *
 * 絶対年数の閾値を使わないのは、Vault の年齢で結果が壊れるため —
 * 始めたばかりのVaultは全部 [NotePaperTone.Fresh]、10年もののVaultは全部
 * [NotePaperTone.Weathered] になり、どちらも「地層が見えない」という同じ失敗になる。
 * 相対順位なら、**更新日時が複数種類あるVaultなら幅の広狭によらず段階が分かれる**。
 * （1本だけ・全ノート同時刻・全件不明の場合は段階が分かれず全て [NotePaperTone.Fresh] になる。
 * これは失敗ではなく、そもそも古い新しいの差が無いため。）→ `docs/dev/features/note_age_paper.md` 判断2
 *
 * **判定できないときは [NotePaperTone.Fresh] を返す。** Fresh は現行のパネル色そのもので
 * 見た目が変わらないため、材料が揃わないときに「何も起きない」側へ倒せる。
 * SAFプロバイダが `lastModified` を返さない場合と、Vault走査のキャッシュが冷えている場合が該当する。
 *
 * @param lastModified 対象ノートの最終更新（エポックミリ秒）。SAFが返さなければ null。
 * @param vaultLastModified Vault 全体の最終更新の一覧。null は判定材料にならないので除外する。
 *
 * **[lastModified] 自身をこの一覧へ必ず含めること。** 数えるのは「自分より新しいものの件数」だが、
 * 割合の**分母は一覧の件数**なので、自分が入っているかどうかで結果が変わる
 * （例: 対象4・一覧 `[1,2,3,4,5]` は 1/5 = 0.20 で [NotePaperTone.Fresh]、
 * 一覧から自分を抜くと 1/4 = 0.25 で [NotePaperTone.Settling]）。
 * 呼び出し側はVault全体をそのまま渡し、一覧に対象が居ないと分かっている場合だけ1件補う。
 */
fun notePaperTone(lastModified: Long?, vaultLastModified: List<Long?>): NotePaperTone {
    if (lastModified == null) return NotePaperTone.Fresh
    var total = 0
    var newer = 0
    for (value in vaultLastModified) {
        if (value == null) continue
        total++
        if (value > lastModified) newer++
    }
    if (total == 0) return NotePaperTone.Fresh
    // 自分より新しいものが無い（＝最新、または全ノートが同時刻）なら Fresh。
    // 全ノートが同じ時刻のVaultで人工的な段差を作らないための性質でもある。
    val newerFraction = newer.toDouble() / total
    return when {
        newerFraction < 0.25 -> NotePaperTone.Fresh
        newerFraction < 0.50 -> NotePaperTone.Settling
        newerFraction < 0.75 -> NotePaperTone.Aged
        else -> NotePaperTone.Weathered
    }
}

/**
 * さがす・関連ノート経由で開いた候補の紙の地色を決める。
 *
 * [notePaperTone] との違いは、**材料が2箇所に散っている**こと。
 *
 * 1. **最終更新は [RelatedNote] 自身を優先する。** さがすタブは走査キャッシュを温めないので、
 *    キャッシュだけを見ると「候補は値を持っているのに使わない」ことになる
 *    （`SearchController` / `SearchPickerUseCase` は `lastModified` を設定している）。
 *    当日履歴から作られた候補だけは値を持たないので、その場合はキャッシュへ落ちる。
 * 2. **キャッシュに対象が居なければ分布へ1件補う。** 割合の分母は一覧の件数なので、
 *    補わないと分母が1件少ないまま数えることになる（→ [notePaperTone] の契約）。
 *
 * どちらの材料も無ければ [NotePaperTone.Fresh]＝現行のパネル色に落ちる。
 * 分布そのものが空でも、補った1件だけになって同じく `Fresh` になる。
 */
fun notePaperToneForCandidate(note: RelatedNote, cachedNotes: List<NoteFile>): NotePaperTone {
    val cached = cachedNotes.firstOrNull { it.ref == note.ref }
    val lastModified = note.lastModified ?: cached?.lastModified ?: return NotePaperTone.Fresh
    val distribution = cachedNotes.map { it.lastModified }
    return notePaperTone(
        lastModified,
        if (cached != null) distribution else distribution + lastModified
    )
}
