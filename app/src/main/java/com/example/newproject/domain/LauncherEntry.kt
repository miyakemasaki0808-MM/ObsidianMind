package com.example.newproject.domain

/**
 * ランチャーのアイコンが投げた Intent が、**既存タスクの上へ積まれた重複起動**かを判定する。
 *
 * ## なぜ要るか
 *
 * `MainActivity` は `launchMode` を持たない（＝`standard`）。タスクの基点Intentが
 * `MAIN`＋`LAUNCHER` **以外**で作られていると、ランチャーのアイコンが投げるIntentは
 * 既存タスクと一致しないと判定され、**新しい `MainActivity` が上へ積まれる**。
 * 積まれるたびに `onCreate(null)` が走るのでOPが再生され、戻るボタンでは同じ画面が
 * 1枚ずつ剥がれるだけで**アプリを抜けられなくなる**。
 * → `docs/dev/features/opening_animation.md` 判断7
 *
 * ## 判定の形
 *
 * - [isTaskRoot] が真なら**そのタスクの最初の1枚**なので、常に組み立てる。
 *   ここを落とすと通常のコールド起動まで畳んでアプリが開かなくなる。
 * - **ランチャー以外の入口は畳まない。** 将来の通知・ディープリンクまで畳むと、
 *   「アプリが既に開いているときだけリンクが効かない」という別の欠陥に化ける。
 *
 * ## なぜ照合先まで引数で受け取るのか
 *
 * `domain` は Android に依存しない（→ `docs/dev/system/architecture.md` 判断5）。
 * 依存の禁止は import だけでなく **`android.` で始まる文字列を書くこと**にも及ぶ
 * （`PackageDependencyTest` はコメントを落としたソースから走査する）。
 * そこで `Intent.ACTION_MAIN` / `Intent.CATEGORY_LAUNCHER` は呼び出し側がSDKの定数から
 * 渡す。**書き写しが無いので、値がずれる余地そのものが無い。**
 *
 * @param isTaskRoot `Activity.isTaskRoot`。そのタスクの最初の1枚か。
 * @param action 受け取った Intent の action。取得できなければ null。
 * @param categories 受け取った Intent の category 集合。付いていなければ null。
 * @param launcherAction ランチャーが投げる action（`Intent.ACTION_MAIN`）。
 * @param launcherCategory ランチャーが投げる category（`Intent.CATEGORY_LAUNCHER`）。
 */
fun isDuplicateLauncherLaunch(
    isTaskRoot: Boolean,
    action: String?,
    categories: Set<String>?,
    launcherAction: String,
    launcherCategory: String
): Boolean = !isTaskRoot &&
    action == launcherAction &&
    categories?.contains(launcherCategory) == true
