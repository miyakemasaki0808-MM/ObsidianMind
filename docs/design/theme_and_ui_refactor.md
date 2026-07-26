# 設計思想 — テーマ基盤とUI構造のリファクタ（R-1〜R-4）

**対象:** Vigilith 周辺のリファクタ3件（状態導出の重複・巨大化した `NoteReaderTab`・`Color(0x…)` 直書きの散在）＋ ダークモードの土台
**初版:** 2026-07-26
**関連:** [dark_mode](dark_mode.md)・[vigilith_in_app](vigilith_in_app.md)・[note_fullscreen](note_fullscreen.md)・[architecture](architecture.md)
**状態:** R-1〜R-4 実装済み（2026-07-26・372テスト通過／実機の目視確認待ち）。

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
