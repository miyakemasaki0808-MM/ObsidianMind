# 設計思想 — テーマ基盤とUI構造のリファクタ（R-1〜R-4）

**対象:** Vigilith 周辺のリファクタ3件（状態導出の重複・巨大化した `NoteReaderTab`・`Color(0x…)` 直書きの散在）＋ ダークモードの土台
**初版:** 2026-07-26
**関連:** [dark_mode](dark_mode.md)・[vigilith_in_app](vigilith_in_app.md)・[note_fullscreen](note_fullscreen.md)・[architecture](architecture.md)
**状態:** R-1〜R-4 実装済み（2026-07-26・372テスト通過／実機の目視確認待ち）。
2026-07-29〜30 に、R-4 で見送ったグレー統合とライト配色のAA是正を実施（D案）。
**面の取り違えで2度差し戻され、3度目で通った**（→ 末尾の追記・判断5／判断6）。
現在は、文字トークンを「実際に載る面」との対応表で総当たり検証し、グラデーション直上の
文字は共通部品が背景ごと持ち、ボタンは輪郭線で境界を出す。
残る既知未達は下部ナビ帯の上のバッジ塗り1件。
**2026-07-31 にエミュレータで5画面＋ダークモードを一巡し、確認の過程で判断7（暗幕→白ヘイズ）と
ボタン位置の是正を行った。ダークは据え置きのとおり変化なし。実機確認はこれで完了。**

> **実装後の追記（設計との差分）**
> - **R-4 のグレー統合は見送った。** 設計では「7段階を3トークンへ集約」としたが、集約は必ず見た目を変える。
>   本リファクタの完了条件は「ライトの見た目が変わらないこと」なので、値を保ったまま5つの役割名を与えるに留めた。
>   統合は暗色値を決める Phase 3 と同時に判断する（無彩色グレー5段階の意味的な区別が薄い件は未解決のまま）。
> - **`MaterialTheme` の `colorScheme` は差し替えていない。** 差し替えると `onPrimary` 等が M3 コンポーネントの
>   既定へ一斉に波及し、ライト側に予期しない変化が出る。写像は実機確認を伴える Phase 3 で行う（[AppTheme.kt](../../app/src/main/java/com/example/newproject/ui/theme/AppTheme.kt) に理由を明記）。
> - **`LocalAppColors` にはまだ参照側がいない。** 画面はトップレベルのトークンを直接読んでいる。
>   切り替えが観測できるようになって初めて移行に意味が出るため、Phase 3 へ回した。
> - **ボタンのラベル色だけは意図的に見た目が変わる**（ピンク・緑の塗りボタンで白→黒）。§R-4 の実装順1の通り。
> - クイズ画面の読めない2色は**直していない**。値の変更は見た目の変更になるため、名前で隔離してテストで固定した（ライト配色のAA未達として別課題に起票済み）。

---

## 0. 着手順の変更（実コードを読んで判明）

初版計画では R-1（ファイル分割）→ R-2（status統一）→ R-3 → R-4 としたが、**R-2 を先にする**。

`SectionFabStatus` は `NoteReaderTab.kt` に `private enum` として定義され、**タブ本体（L192〜207）と全画面（L451〜464）の両方**から使われている。先に R-1 でファイルを割ると、この private enum を `internal` へ昇格させて2ファイルで共有する必要が生じ、**R-2 で消す型をわざわざ公開する**という無駄な往復になる。

**確定順序: R-2 → R-1 → R-3 → R-4。**

| | 内容 | 前提 | 見た目の変化 |
|---|---|---|---|
| R-2 | status統一と導出ロジックの集約 | なし | なし |
| R-1 | `NoteReaderTab.kt` 分割 | R-2 | なし（a11y修正のみ） |
| R-3 | `MainActivity` からVigilith抽出 | なし（R-1と独立） | なし |
| R-4 | 色トークンの意味ベース化＋`AppTheme` | R-1（分割後に適用） | **なし**（ライト側は1pxも変えない） |

---

## R-2. `SectionFabStatus` → `VigilithActionStatus` 統一

### 現状の重複

`SectionFabStatus(Idle/Loading/Ready/Error)` と `VigilithActionStatus(Idle/Working/Ready/Error)` は同一概念。さらに**導出ロジック自体が2箇所に逐語コピーされている**（`NoteReaderTab.kt` L192〜196 と L451〜455 が同一）。型が2つあることより、こちらのほうが実害が大きい。

### 設計

1. `SectionFabStatus`（L346）を**削除**。`VigilithActionStatus` に一本化（`Loading` → `Working`）。
2. 導出を純粋関数として `VigilithMode.kt` に移す。同ファイルは既に `resolveVigilithPresentation()` という「状態→表示の導出」を担い、JVMテスト済みなので置き場所として自然。

```kotlin
// VigilithMode.kt
internal fun sectionChatStatus(chat: SectionChatState?): VigilithActionStatus
internal fun fullscreenAiStatus(chat: SectionChatState?, quiz: QuizState): VigilithActionStatus
```

3. `NoteReaderTab.kt` L204〜207 の変換 `when` を削除。
4. `FullscreenAiFab` の `status` 引数を `VigilithActionStatus` に変更。

### テスト

`VigilithStatusDerivationTest`（新規）で真理値表を固定する。特に `fullscreenAiStatus` は要約とクイズの合成（Loading優先 → Error → Ready → Idle）で、**現状テストが1件もない**分岐。

### 注意

`VigilithActionStatus` は `VigilithMascot.kt` に定義されている。R-1 後は全画面側からも参照するため、**マスコット専用の型ではなく「AI操作の4状態」という一般名の型**になる。定義位置を `VigilithMode.kt` へ移すのが素直（R-2 の中で同時に行う）。

---

## R-1. `NoteReaderTab.kt`（626行）の分割

### 分割案

| 新ファイル | 移すもの | 現在の行 | 目安 |
|---|---|---|---|
| `NoteReaderTab.kt`（残留） | `ReadingProgressReporter` + `NoteReaderTab` | L90〜345 | 約260行 |
| `FullscreenNoteScreen.kt`（新規） | `FullscreenNoteScreen` + `FullscreenAiFab` + `Context.findActivity()` | L383〜585 | 約200行 |
| `NoteComponents.kt`（新規） | `IconPill` + `NoteContentPanel` | L346〜382・L586〜626 | 約80行 |

`NoteContentPanel` は既に `internal`（タブと全画面で共用）なので可視性の変更不要。`IconPill` は `private` → `internal` へ昇格。

### 同時に直すもの（a11yの実バグ）

`IconPill`（`NoteReaderTab.kt:354`）と `IconPillButton`（`AnnotationManagerScreen.kt:230`）は**ほぼ同一の実装だが、後者に `contentDescription` の `semantics` が無い**。補記管理画面の「‹ 戻る」ボタンは **TalkBackで読み上げられない**。

→ `NoteComponents.kt` の `IconPill` に統合し、`fontSize` をパラメータ化（既定18.sp、補記管理は22.spを渡す）。**見た目は変えずにa11yだけ直る。**

### 検証

ロジック変更ゼロなので、`compileDebugKotlin` と既存352件のテストが通ることが完了条件。

---

## R-3. `MainActivity` からのVigilith抽出

### 現状

`MainActivity.kt` の `setContent{}` 内に4つの関心が直書き（L94・L99〜106・L107〜109・L110〜118）:
`vigilithNoteAction` の保持／`resolveVigilithPresentation()` 呼び出し／Noteルート限定の絞り込み／`onVigilithTap` ラムダ構築。

### 設計

`VigilithState.kt`（新規）に状態ホルダを作る。

```kotlin
internal class VigilithUiState(
    val presentation: VigilithPresentation,
    val noteAction: VigilithNoteAction?,
    val onTap: (() -> Unit)?,
    val onNoteActionChanged: (VigilithNoteAction?) -> Unit
)

@Composable
internal fun rememberVigilithState(
    uiState: NoteUiState,
    currentRoute: String?,
    onOpenSection: (NoteSection) -> Unit,
    onShowSectionChat: () -> Unit
): VigilithUiState
```

`vigilithNoteAction` の `mutableStateOf` も**この関数が所有する**。`MainActivity` は `rememberVigilithState(...)` を1回呼び、`AppScaffold` に `state` を、`NoteReaderTab` に `state.onNoteActionChanged` を渡すだけになる（Vigilith関連の記述が4箇所→2箇所）。

### 注意（[bugfix_reports](../bugfix_reports.md) の型）

`onTap` は `uiState.sectionChat` の有無で分岐する。**`uiState` を `remember` のキーに含めずにラムダを固めると stale closure になり、「ボタンを押しても何も起きない」既知の型を踏む**。`onTap` は `remember` せず毎コンポジションで組み直すか、キーに `uiState.sectionChat != null` を明示的に含める。

### テスト

`rememberVigilithState` は `@Composable` のためJVMテスト不可。**判断は全て `resolveVigilithPresentation()`（テスト済み・純粋）側に残し、この関数は配線だけの薄い層に保つ**ことが設計上の制約。

---

## R-4. 色トークンの意味ベース化と `AppTheme` 導入

### 発見1（重要）: `QuizScreen` は既にダークだった

直書き25箇所の正体を読んだ結果、`QuizScreen.kt` は**独自の手作りダークテーマ**だった。

| 役割 | 値 |
|---|---|
| 背景 | `#1A1C2E` |
| カード面 | `#2A2D45` |
| 見出し | `#B0B8FF` |
| 本文 | `#EEEEFF` |
| 正解／誤答 | `#4CAF50` / `#EF5350` |

**帰結が2つ:**

1. **[dark_mode](dark_mode.md) §4 で提案した暗面 `#101A2E` は取り下げ、既存の `#1A1C2E`（背景）/ `#2A2D45`（面）をダークトークンのベースに採る。** 机上の新色より、既に実装され目視確認を通っている配色を土台にするほうが安全。ボタン3役の提案は `#2A2D45` 上でも成立する（Primary 3.95 / Secondary 5.41 / Ai `#8A80FF` 4.24、いずれも非文字3:1を満たす）。
2. **ダーク採用後、QuizScreen は「ライトでだけ浮く画面」になる。** ライト時に他画面と揃えるのか、クイズだけ暗いままにするのか（＝集中画面としての意図的な例外）は**未決の論点**。R-4 では触らず、Phase 3 で判断する。

### 発見2: QuizScreen に読めない文字がある（既存バグ）

暗い背景にライト用の色が紛れ込んでいる。

| 箇所 | 色 | `#1A1C2E` 上のコントラスト |
|---|---|---|
| `QuizScreen.kt:83` エラー文言 | `#CC0000` | **2.86 ❌** |
| `QuizScreen.kt:89` 「問題を生成できませんでした。」 | `#555555` | **2.25 ❌** |
| `:120` 補助文字 | `#777799` | 3.91 ❌ |
| `:189,216` 無効選択肢 | `#555577` | 2.36 ❌ |

上2件は**エラー時にしか出ない文言**なので今まで気づかれていない。R-4 のトークン回収で自動的に直る（`error` トークンがダーク面では `#FF6B6B` になるため）。

### 直書き61箇所の内訳と回収方針

| 分類 | 件数 | 方針 |
|---|---|---|
| **AppColors に同値が既にあるのに直書き**（`#F1F4F8` `#FDFEFF` `#F0F4FF` `#D6DDF5` `#CC0000` `#202124` `#4D3DFF` `#00C2FF` `#FF3D71` `#16B8A6` ほか） | 約22 | **単純置換で即回収。** 判断不要 |
| **無彩色グレー階調**（`#888888`×8, `#555555`×7, `#777777`×4, `#666666`×3, `#AAAAAA`×2, `#CCCCCC`×2, `#999999`） | 約27 | `onSurfaceMuted` / `onSurfaceFaint` / `divider` の**3トークンに集約**。現在7段階あるが意味的な区別はない |
| **意味を持つ新規トークンが要るもの**（`#4CAF50` 正解 / `#EF5350` 誤答） | 6 | `success` / `danger` を新設。既存トークンに無い概念 |
| **QuizScreenのダーク面**（`#1A1C2E` `#2A2D45` `#B0B8FF` `#EEEEFF` ほか） | 約10 | ダークトークンの定義元として昇格（発見1） |

その他、`OptionsScreen.kt:40` が `AppGradient` と同一のグラデーションをローカル再定義しているので集約する。

### テーマの通し方

**独自 `CompositionLocal` を主とし、`MaterialTheme` を併走させる。**

```kotlin
internal val LocalAppColors = staticCompositionLocalOf { LightAppColors }

@Composable
internal fun AppTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkAppColors else LightAppColors
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = colors.toColorScheme(), content = content)
    }
}
```

- **独自ロールを M3 の `colorScheme` に押し込まない。** 「ボタン3役」は M3 の primary/secondary/tertiary と意味が対応しないため、無理に写像すると `onPrimary` 等が全 M3 コンポーネントへ波及する（下記の落とし穴）。
- ただし `Button` / `Surface` / `Snackbar` / `ModalBottomSheet` は `colorScheme` を見るので、**無視はできない**。最小限の写像は行う。

### 実装順（この順序を守ること）

1. **先に全塗りボタンへ `contentColor` を明示する。** 現在ラベル色は `MaterialTheme.colorScheme.onPrimary`（既定 lightColorScheme の純白）に暗黙依存しており、`MaterialTheme` に独自 colorScheme を渡した瞬間に**全ボタンのラベル色が一斉に変わる**。ここで [dark_mode](dark_mode.md) §4-2 のライト側基準割れ（Primary 白ラベル 3.41 / Secondary 2.49）も同時に解消する（Primary・Secondary＝黒、Ai＝白）。
2. トークンを意味名で定義（ライト値は**現行値をそのまま**）
3. `AppTheme{}` を `setContent{}` 直下に1枚挟む（ダーク値はまだ入れない）
4. 直書き61箇所を置換
5. ダーク値の定義は **Phase 3** へ（R-4 の範囲外）

### テスト

- ライト／ダーク両方のトークンが**揃っている**ことを固定（片方だけ定義した漏れの検出）
- [dark_mode](dark_mode.md) §4・§4-2 のコントラスト比をアサーションで固定
- 上記は純粋な値の検証なのでJVMテストで書ける

---

## コミット粒度と検証

1修正1コミット（R-2 / R-1 / R-3 / R-4 で4本。R-4 は「contentColor明示」「トークン定義＋AppTheme」「直書き置換」に割ってもよい）。各コミット前に必ず:

```bash
export JAVA_HOME="/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew compileDebugKotlin testDebugUnitTest --offline
```

**R-1〜R-4 はいずれも「見た目が変わらないこと」が完了条件**なので、実機での目視比較（変更前後のスクリーンショット）を最後に一巡する。

---

## 追記 2026-07-29 — ライト配色のAA是正とグレー階調の統合（D案）

R-4 で見送った「グレー統合」と、[dark_mode](dark_mode.md) 実装時に実測して未修正のまま残した
ライト側のコントラスト未達7件を、まとめて解消した。**R-1〜R-4 の完了条件は「見た目が変わらないこと」
だったが、今回は逆に「見た目を変えることが目的」**なので、同じ作業を続けているわけではない。

### 判断1. 階調の数は好みではなく「AAの床」から決まる

R-4 の時点では「5段階は意味の区別が薄い」という感覚的な理由で統合を保留していた。実測すると、
統合すべき理由は感覚ではなく算術だった。

パネル `#FDFEFF` の上で 4.5:1 を満たす最も薄いグレーは **`#767676`（ちょうど4.50）**。つまり
本文 `#202124` からこの床までが、弱い文字に使える全範囲になる。ここに4段階以上を置くと、
名前は違うのに実質同じ濃さのトークンが生まれ、「どれを使うか」が決められなくなる。

| 旧 | 実測 | 新 |
|---|---:|---|
| `onSurfaceMuted #555555` | 7.38 ✅ | 据え置き（本文に次ぐ） |
| `onSurfaceSubtle #666666` | 5.69 ✅ | 据え置き（弱い） |
| `onSurfaceFaint #777777` | 4.43 ❌ | **`#757575`（4.56）**（最も弱い） |
| `onSurfaceHint #888888` | 3.51 ❌ | 廃止 → Faint へ |
| `onSurfaceDisabled #999999` | 2.82 ❌ | 廃止 → Faint へ |

床が `#767676` なので `#757575` を採った（`#767676` ちょうどでは端数計算で割れる余地が残る）。
ダークは元から全段が基準内だが、**片方だけ段数が増える事故を防ぐため明暗で段数を揃えた**。

**教訓:** 「トークンが多すぎる」型の課題は、減らす根拠を美意識で用意しようとすると決着しない。
先に**制約（この場合はAAの床）を測ると、取りうる段数が自動的に決まる**。

### 判断2. 彩度を持つ色は、色相を動かさずに明度で落とす

見出し2色と更新日時は、いずれも 11sp。**AAの大文字例外（18pt相当／Bold 14pt相当）には入らない**ので
4.5:1 が要る。

| トークン | 旧 | 新 | 変えたもの |
|---|---|---|---|
| `relatedHeading`（関連ノート） | `#7B6FFF` 3.74 | `#6A5FE0` 4.81 | 色相245固定・彩度100→68・明度72→63 |
| `aiHeading`（AI推薦） | `#16B8A6` 2.46 | `#0D8375` 4.60 | 色相173固定・明度40→28 |
| `onSurfaceMetaBlue`（更新日時） | `#8A90A8` 3.13 | `#6D748F` 4.58 | 色相228固定・明度のみ |

**3色とも色相を1度も動かしていない。** 「関連ノート」と「AI推薦」は同じ大きさ・同じ位置の見出しで、
見分けの根拠が色相の差しか無い。ここを動かすと可読性と引き換えに意味が壊れる。

### 判断3. 塗りと文字は別の基準なので、同じ色を共有できない

`buttonSecondary #16B8A6` は白面で 2.46 しかなく、非文字基準の 3:1 を割っていた。塗りには
**下限と上限の両方**がある。

- 塗り vs 面 ≥ 3:1 → 暗いほど有利
- 黒ラベル vs 塗り ≥ 4.5:1 → 明るいほど有利

両立するのは相対輝度 **0.175〜0.297** の帯だけで、その中央付近の `#109384` を採った
（塗り 3.76 ／ 黒ラベル 5.53）。ダークは 6.98 で足りているため据え置き、結果として
**`buttonSecondary` は明暗で別値になった**（それまでは「明暗どちらでも成立する色」として共有していた）。

同じ理由で、`aiHeading #0D8375`（文字用・4.5必要）と `buttonSecondary #109384`（塗り用・3.0必要）は
**同系色だが別値**になる。[dark_mode](dark_mode.md) が `accentText` / `accentSurface` を分けたのと同じ構造で、
今回はそれがライト側にも現れた。

### 判断4. テストを「記録」から「強制」へ移す

従来の `AppColorContrastTest` は、ライトの未達7件を**実測値のまま固定して失敗させない**形だった。
悪化も改善も検出できるが、裏を返すと**テストが全緑でもAA準拠を意味しなかった**。

今回、文字トークンは明暗を1つのテストへ統合して 4.5:1 を強制し、塗りボタンも3役×明暗すべてで
3:1 を強制した。旧値へ戻す変異でテストが落ちることを確認済み。

### 残した未達 — バッジの塗りはナビ帯の上では基準を満たさない

作業中に、課題台帳に載っていなかった1件を見つけた。バッジはパネルではなく**下部ナビ帯**
（ライトは `Indigo #4D3DFF`）に載るため、塗りの輪郭が Success 1.61・Error 1.04 と出ていない。
ライトのIndigoは彩度が高く相対輝度も中位なので、**その上で3:1を取れる塗りがほとんど無い**。

中の記号は対の前景を使って読めるようにした（Success は白の「✓」2.49 → 黒 5.53。Error 側は
[dark_mode](dark_mode.md) の指摘対応で既に対を持っていたが、Success だけ取り残されていた）。
塗りを直すにはナビ帯かバッジの明度に踏み込むことになるので、実測値を固定するテストだけ置いて
判断は分けた。

**教訓の更新:** [dark_mode](dark_mode.md) が残した「塗りには必ず対の前景を持つ」は正しかったが、
**塗り自身が載る面を数え忘れていた**。パネルの上での 3:1 は測っていたのに、同じトークンが
ナビ帯の上にも出ることは見ていない。トークンを検証するときは、値の組ではなく
**「その色が実際に載る面すべて」**を列挙する。

### 判断5. 「面を数える」は教訓を書くだけでは実行されない（同日のレビュー指摘より）

上の教訓を書いた**そのコミットで、直している当のトークンには適用していなかった**。
初版のテストは全色を `panel #FDFEFF` の上で測っており、レビューで次を指摘された。

| 指摘 | 実測 | 実際の面 |
|---|---:|---|
| 見出し2色・更新日時・弱い文字 | 4.19〜4.41 | `panelBlue #F0F4FF`（RelatedTab のカード） |
| 緑ボタンの塗り | 1.21〜1.84 | `AppGradient` / `ReadingGradient` 直上 |

`panel` はアプリで最も明るい面なので、そこで測れば何でも通る。**「基準を強制する形へ移した」
と書いたが、強制していたのは現実に存在しない条件だった。** 検証の対象を間違えたテストは、
無いテストより悪い（通っているという事実が確認を止めてしまう）。

再検証で分かったことが3つある。

1. **弱い文字の基準面は `panelChip #EEF0FF`**（文字を載せる面のうち最も暗い）。ここを基準に
   `onSurfaceFaint #6D6D6D` / `onSurfaceMetaBlue #656C85` / `aiHeading #0C796C` /
   `relatedHeading #6054DE` へ再決定した。面ごとに上限を変えないのは、トークンを別の面へ
   移した瞬間に静かに割れるため。
2. **ボタンは色では解けない。** `AppGradient` の停止色は相対輝度 0.121〜0.458 に散っており、
   全停止色に3:1を満たす塗りは L≤0.007（ほぼ黒）か L≥1.47（存在しない）しかない。
   実測では3役すべてが未達で、**`ButtonAi` は Indigo の停止色と同色（1.00）** だった。
   緑1色の問題ではなく、「彩度の高い広い輝度幅の背景に、色付きの塗りを置く」構図の問題である。
   WCAG 1.4.11 が認める隣接輪郭で解き、`buttonOutlineOnGradient`（ライト=LogoNavy）を新設した。
   ダークは塗りが 4.45〜6.10 で足りているので置かない。「足りているから置かない」ことも
   テストで示す（忘れたのではない、と区別できるようにするため）。
3. **半透明の面は「色の選び直し」では解けない。** AIタブの空状態は `Panel` を22%で
   グラデーションへ重ねており、白文字とのコントラストは下地しだいで 1.82〜4.10 に動いていた。
   不透明化して解消した。一方 `DistillCandidateRow` は親が `PanelBlue` なので実効面は
   #F9FBFF で問題なく、こちらは構造を変えていない。**「半透明だから危ない」ではなく、
   何の上に重なっているかで決まる。**

**教訓:** 判断4で書いた「記録から強制へ」は方向としては正しいが、**強制する条件が実物と
一致しているかは別の検証**である。色に限らず、テストが参照する環境（面・背景・親）を
実コードから引き当てたか、それとも代表値で置いたかを区別する。テーマ6「対策を入れた≠
効いている」の、テスト条件そのものに向けた版にあたる。

### 判断6. 2度目の差し戻し — 「代表値で測る」をやめる（2026-07-30）

判断5で「実際に載る面との対応表」へ移したが、**その対応表に入れていなかった経路がまだ残っていた**。
2度目のレビューで次を指摘された。

| 指摘 | 実測 |
|---|---|
| グラデーション直上の白文字を、最も**有利な**停止色でだけ測っていた | 白 2.07（Aqua）・副題 1.89 |
| 対応表は不透明トークン10個だけが対象で、`copy(alpha=…)` と `onVibrant` を覆えていない | 1.82〜4.31 |
| 停止色がテストと実装で二重管理 | — |

**テストのコメントは「最も明るい停止色（ライトはAqua）」と書きながら、渡していたのは `Indigo`**
だった。Indigo はライトで最も暗い＝白文字にとって最も有利な停止色である。
`onVibrantMuted` に至っては、停止色ですらない `LogoNavy` と比較していた。

原因の一端は二重管理にある。`AppColorScheme` は `Brush` を受け取っていたが `Brush` から色は
取り出せないため、テストは同じ値を自前で書き写すしかなかった。**停止色のリストを受け取って
`Brush` をその場で組み立てる形へ変え、実装とテストが同じリストを見るようにした。**
そのうえで代表の停止色を選ぶのをやめ、総当たりに変えた。どれが最悪かは色を変えれば
入れ替わるので、選んだ時点で同じ間違いの余地が残る。

**教訓:** 判断5で作った「対応表」は正しい方向だったが、**表に載せる範囲を自分で決められる**
以上、載せ忘れは必ず起きる。防ぐには2つ要る — ①検証対象を列挙ではなく総当たりにする、
②表に載らない書き方（画面から直接 `onVibrant`、文字色への任意の `copy(alpha)`）を
**ソース走査で禁じる**。後者は `VibrantTextUsageTest` として実装した。許可するのは背景を
所有する部品だけで、許可リストへ足したらその面の比を `AppColorContrastTest` へ足す決まりにした
（許可だけ増やして検証を増やさないと穴になる）。

### 判断7. 見出しは「暗くする」ではなく「白で霞ませる」（2026-07-30）

グラデーション直上の見出しは、**文字色では絶対に解けない**。白文字は Aqua 停止色で 2.07、
濃い文字にすると今度は Indigo 停止色で 2.62 に落ちる。28sp Bold は大文字扱いで3:1に緩むが、
それすら満たさない。背景を触るしかない。

最初は `LogoNavy` α=0.42 の暗幕を敷いたが、**実機で見ると帯として重く、角丸を付けた時点で
カードにしか見えなかった**（すぐ下に白い本文カードがあるため、濃いカードと白いカードが
積み上がって見える）。実装としては通っていたが、デザインとして成立していない。

解き方を反転し、**白を薄く重ねて停止色を持ち上げ、そこへ濃い文字を置く**形にした。

| | 暗幕（旧） | 白ヘイズ（現） |
|---|---|---|
| 面 | `LogoNavy` α=0.42 | `Panel` α=0.35 |
| 文字 | 白／`onVibrantMuted` | `LogoNavy`／`#202124` |
| 最悪の比 | 5.12／**4.69（下限）** | 6.15／**5.12** |
| 最悪の停止色 | Aqua（最も明るい） | Indigo（最も暗い） |

**白文字と濃い文字では、苦しくなる停止色が逆になる。** 濃い側のほうが余裕があり、
α=0.35 は下限ではない。形も角丸をやめてメイン領域の全幅へ抜き、
「上に乗ったカード」ではなく「上部が霞んだ背景」として読ませる。

ダークは何も敷かない（暗いグラデーションでは白文字が元から基準を満たす）。
**したがって面と文字が明暗で反転する。** 3つ揃いのトークンとして持ち、
反転していること自体もテストで固定した（片方の値をもう片方へコピーする事故は、
この形でしか検出できない）。

**教訓:** 「基準を満たす実装」と「成立しているデザイン」は別で、**前者だけを見ていると
後者で必ず差し戻される**。今回は実機を見るまで気付けなかった。数値で決まるのは
「満たすかどうか」だけで、**どう満たすかは最後まで設計判断**として残る。
