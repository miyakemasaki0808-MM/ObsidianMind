package com.example.newproject.controller

/**
 * 読書時間の計測を止めている理由。
 *
 * **真偽1つで持たない。** 冊子を開いたまま背面へ回ると2つが同時に成り立ち、
 * 片方が解けただけで計測が再開してしまう（→ features/booklet_mode.md 判断3）。
 * 計測は「理由が1つも無いこと」から導出する。
 */
internal enum class ReadingPauseReason {
    /** アプリが背面にある（`onStop` → `onStart`）。 */
    AppBackground,

    /** 冊子ルートが前面にある。**ノートは表示されていない。** */
    Booklet
}
