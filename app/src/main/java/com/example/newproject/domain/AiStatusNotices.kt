package com.example.newproject.domain

import com.example.newproject.ai.AiAvailability
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.AiStatusNotice

/**
 * AI状態を、ユーザーへ見せる1文と導線へ変換する。
 *
 * **「黙るかどうか」はこの関数が決めない。** 決めるのは呼び出し側で、判定軸は起動契機である
 * （ユーザーが押した機能はその場で理由を説明し、自動で走る機能は黙って劣化する）。
 * ここは「言うとしたら何を言うか」だけを答える全域関数に保つ。沈黙の判断を混ぜると、
 * 同じ状態に対する文言が呼び出し側ごとにまた散らばる。
 *
 * @param featureLabel 「蒸留」「Q&A」など、文中へそのまま埋め込める機能名
 * @return [AiAvailability.Ready] のときだけ null（説明することが無い）
 */
fun aiStatusNotice(availability: AiAvailability, featureLabel: String): AiStatusNotice? =
    when (availability) {
        AiAvailability.Ready -> null
        AiAvailability.NeedsDownload -> AiStatusNotice(
            message = "${featureLabel}にはGemini Nanoのダウンロードが必要です。" +
                "通信量を確認してから開始してください。",
            action = AiNoticeAction.Download,
            canTryAgainLater = true
        )
        // **CTAを重ねない。** 走行中のDLへ合流する手段が無いので、押しても始まるものが無い
        // （→ AiAvailability.Downloading）。完了後にもう一度押してもらう。
        AiAvailability.Downloading -> AiStatusNotice(
            message = "Gemini Nanoをダウンロード中です。完了すると${featureLabel}を使えます。",
            action = AiNoticeAction.None,
            // **入口は閉じない。** 閉じるとDL完了後に押し直せず、同じノートで二度と使えなくなる。
            canTryAgainLater = true
        )
        // AICoreが無い（古い）端末。何度押しても同じ答えが返るので再試行導線を出さない。
        AiAvailability.Unsupported -> AiStatusNotice(
            message = "この端末では${featureLabel}を利用できません。",
            action = AiNoticeAction.None,
            // AICoreが無い（古い）端末。**ここだけが恒久で、入口を閉じてよい唯一の値。**
            canTryAgainLater = false
        )
        // **「できませんでした」と過去形で言わない。** 構成の取得待ちのこともあるので、
        // 失敗ではなく「いまは使えない」と伝える。
        // `cause` を文言へ混ぜない — SDKの message は英語か null で、
        // ユーザーの次の行動を1文字も助けない。原因は診断のために型が運ぶだけにする。
        is AiAvailability.TemporarilyUnavailable -> AiStatusNotice(
            message = "${featureLabel}をいま利用できません。時間をおいて試してください。",
            action = AiNoticeAction.Retry,
            canTryAgainLater = true
        )
    }
