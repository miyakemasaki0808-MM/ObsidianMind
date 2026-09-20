package com.example.newproject.model

/**
 * 用途別のノート本文抜粋上限（UTF-16文字数）。
 *
 * プロンプト全体の入力予算やトークン上限ではない。タイトル・指示文・候補一覧などは
 * この上限に含まれず、実トークン数の調整は実機計測を伴う別判断とする。
 */
object NoteExcerptLimits {
    const val SUMMARY = 1200

    /**
     * 関連ノートの現ノート側。他より小さいのは、このプロンプトだけが候補ブロック
     * （最大3,500文字）を併せて載せるため。
     *
     * 実測では 2,000 字でもトークン上限に収まり（余裕は844）、100文字あたりの限界費用は
     * 600〜2,000 の全域でほぼ一定（日本語で約60トークン）である。
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

    /**
     * 分野の判定。**他より小さい。**
     *
     * 要約と違って本文全体を把握する必要がなく、**何について書かれたノートかが分かれば足りる**。
     * 抜粋は見出し骨格＋冒頭＋末尾なので、この予算でも主題は載る。
     *
     * **小さくする理由は、この経路がノートを開くたびに全ノートで走ること**にある
     * （→ `docs/dev/features/note_field_color.md` 判断6）。Nano は Mutex 直列なので、
     * 要約・読書痕跡に続く3本目として待ち行列を伸ばす。**予算はそのまま待ち時間になる。**
     *
     * **実機で測っていない。** 動かすときは `PromptTokenBudgetTest` の掃引と体感で決める。
     */
    const val FIELD = 600

    const val ABRIDGED_NOTICE =
        "The following content has been compacted and may combine a note outline with excerpts " +
            "from the beginning and ending. A (omitted) marker denotes a skipped span when present. " +
            "Do not assume the content is continuous or complete."

    /** 注意書きと本文をつなぐ改行まで含め、抜粋予算と描画で共有する。 */
    const val ABRIDGED_NOTICE_PREFIX = ABRIDGED_NOTICE + "\n"
}
