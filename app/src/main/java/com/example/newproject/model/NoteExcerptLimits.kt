package com.example.newproject.model

/**
 * 用途別のノート本文抜粋上限（UTF-16文字数）。
 *
 * プロンプト全体の入力予算やトークン上限ではない。タイトル・指示文・候補一覧などは
 * この上限に含まれず、実トークン数の調整は実機計測を伴う別判断とする。
 */
object NoteExcerptLimits {
    const val SUMMARY = 1200
    const val ANNOTATION = 1500

    /**
     * 関連ノートの現ノート側。他より小さいのは、このプロンプトだけが候補ブロック
     * （最大3,500文字）を併せて載せるため。
     *
     * **2026-08-01 に 600 → 800 へ。実機計測で余裕を確認したうえでの小さな一歩である。**
     * 実測では 2,000 でもトークン上限には収まり、100文字あたりの限界費用は
     * 600〜2,000 の全域でほぼ一定（日本語で約60トークン）だった。
     * **2026-08-22 に基準線を取り直しても結論は変わっていない**（2,000字での余裕は844）。
     * 取り直したのは、プロンプトの字下げが全行に残る不具合を直して入力が減ったため。
     * **つまり「トークン効率が落ちるからここまで」という自然な停止点は存在しない。**
     * それでも 800 に留めるのは、判断軸がトークンではなく次の2つだからである。
     *
     * - 生成時間: 関連ノートはノートを開いた瞬間に自動起動し、Mutexで直列化される
     * - 推薦品質: 予算を増やすと圧縮が緩む。[ai_input_excerpt] §9.1 の
     *   「同じ予算なら構造を伝える不連続な断片のほうが強い」に照らすと、増やせば良いとは限らない
     *
     * 次に動かすときは `PromptTokenBudgetTest` の掃引を回し、**体感と品質を見てから**決める。
     */
    const val RELATED = 800
    const val SECTION = 1500
    const val QUIZ = 1200

    const val ABRIDGED_NOTICE =
        "The following content has been compacted and may combine a note outline with excerpts " +
            "from the beginning and ending. A (omitted) marker denotes a skipped span when present. " +
            "Do not assume the content is continuous or complete."

    /** 注意書きと本文をつなぐ改行まで含め、抜粋予算と描画で共有する。 */
    const val ABRIDGED_NOTICE_PREFIX = ABRIDGED_NOTICE + "\n"
}
