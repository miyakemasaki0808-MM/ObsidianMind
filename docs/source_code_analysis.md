# ソースコード解析書

**プロジェクト:** Obsidian Mind

**解析日:** 2026-07-19

**対象ブランチ:** `feature/New_Additional_Function`

**対象コミット:** `4ee7dc6`（Merge pull request #21）

**対象範囲:** `app/src/main`、`app/src/test`、Gradle設定

**検証結果:** `testDebugUnitTest` 成功

---

## 1. エグゼクティブサマリー

Obsidian Mind は、Android の Storage Access Framework（SAF）でユーザーが選択した Obsidian Vault を読み込み、Markdown ノートの閲覧・検索・関連ノート抽出・復習支援を行う Jetpack Compose アプリである。

AI機能はクラウドAPIではなく、ML Kit GenAI Prompt API を通じて端末内の Gemini Nano を使用する。現在実装されているAI機能は次のとおり。

- ノート全体の要約
- wikilink・ファイル名規則と組み合わせた関連ノート推薦
- 自然文によるノート選択（AIピッカー）
- ノート内容からの4択問題生成
- ノートを成長させるためのAI補記メモ生成・Vaultへの保存
- 表示中セクションの要約、質問候補生成、セクション限定Q&A

アーキテクチャは「単一 Activity + Compose Navigation + 単一 ViewModel」を入口としつつ、肥大化を避けるため検索・補記・セクションチャットを機能別 Controller に分割している。ファイルI/Oは `NoteRepository`、AI判定を含む主要ロジックは UseCase、AI接続は `AiClient`、Markdown生成・応答パースは純粋ロジックへ分離されている。

現時点の総評は次のとおり。

- 主要責務の分割、状態の一元管理、古いAI処理のキャンセル、生成タイムアウト、SAF走査キャッシュが実装され、継続的な機能追加に耐えやすい構造になっている。
- Markdownパーサー、補記Markdown生成、クイズ応答パーサーなど、壊れやすい純粋ロジックにはユニットテストが整備されている。
- 一方で、検索・クイズ・補記の一部ジョブは要求単位で追跡されず、連続操作時の競合余地が残る。
- SAF、Compose Navigation、Gemini Nano を組み合わせた統合テストはなく、実端末依存の動作はユニットテストだけでは保証されない。
- 「AI非対応時はキーワード一致で表示」という検索画面の文言と、候補40件以下で単純に先頭3件を返す実装には差がある。

---

## 2. プロジェクト規模と技術構成

### 2.1 コード規模

| 区分 | ファイル数 | 行数・件数 |
|---|---:|---:|
| 本番 Kotlin | 31ファイル | 約4,966行 |
| ユニットテスト Kotlin | 5ファイル | 約423行、41テスト |
| Androidモジュール | 1 | `:app` |

行数は空行・コメントを含む `wc -l` ベースであり、生成物とGradleスクリプトは含まない。

### 2.2 ビルド・プラットフォーム

| 項目 | 現在値 |
|---|---|
| Android Gradle Plugin | 9.1.1 |
| Kotlin Compose Plugin | 2.0.21 |
| compileSdk | Android 36.1 |
| targetSdk | Android 36 |
| minSdk | Android 26 |
| Java互換性 | Java 8 |
| Compose BOM | 2024.09.03 |
| Navigation Compose | 2.7.7 |
| Lifecycle | 2.8.7 |
| Coroutines | 1.9.0 |
| ML Kit GenAI Prompt | 1.0.0-beta2 |
| JUnit | 4.13.2 |

### 2.3 外部依存の特徴

- UIは View/XML を使わず Jetpack Compose で構成する。
- Vaultアクセスは Android 標準の SAF と `DocumentsContract` を使用し、ストレージ権限を Manifest に要求しない。
- AI生成は `com.google.mlkit:genai-prompt` を通じて Gemini Nano を使用する。
- DIフレームワーク、データベース、HTTPクライアント、画像読み込みライブラリは導入していない。
- 本番コードは `AICoreClient` を直接生成する。`StubAiClient` は手動差し替え用として残されている。

---

## 3. 現在のファイル構成

```text
app/src/
├── main/
│   ├── AndroidManifest.xml
│   ├── java/com/example/newproject/
│   │   ├── MainActivity.kt                 # Activity、Vault選択、NavHost、画面イベント接続
│   │   ├── NoteViewModel.kt                # 状態の統合、ノート読込、要約・関連・クイズの調停
│   │   ├── NoteUiState.kt                  # 全UI状態と各sealed state
│   │   ├── NoteRepository.kt               # SAF走査・読書き・メタデータ解析
│   │   ├── NoteTitleNormalizer.kt          # Obsidianタイトル正規化
│   │   ├── SearchController.kt             # フォルダ検索・スコープキャッシュ
│   │   ├── SectionChatController.kt        # セクション要約・質問・Q&A
│   │   ├── AnnotationController.kt         # AI補記の生成・保存・一覧・削除
│   │   ├── AnnotationComposer.kt           # 補記Markdown検証・整形（純粋ロジック）
│   │   ├── QuizResponseParser.kt           # AIクイズ応答パース（純粋ロジック）
│   │   ├── ai/
│   │   │   ├── AICoreClient.kt             # AiClient、Gemini Nano接続、Mutex、タイムアウト
│   │   │   └── PromptBuilder.kt            # 7種類のプロンプト構築
│   │   ├── domain/
│   │   │   ├── SummarizeUseCase.kt         # 要約ユースケース
│   │   │   ├── RelatedNotesUseCase.kt      # 規則ベース＋AI関連ノート抽出
│   │   │   ├── SearchPickerUseCase.kt      # 自然文検索による3件選定
│   │   │   └── AiResponseParsing.kt        # AI返却タイトルの共通正規化
│   │   └── ui/
│   │       ├── AppScaffold.kt              # 5タブ、NavigationBar/Rail切替
│   │       ├── NoteReaderTab.kt            # Markdown閲覧、全画面、セクションFAB
│   │       ├── SearchScreen.kt             # AI検索・ランダム抽出
│   │       ├── RelatedTab.kt               # 関連・AI推薦ノート一覧
│   │       ├── AiTab.kt                    # 要約、Q&A、AI補記の入口
│   │       ├── OptionsScreen.kt            # オプション入口
│   │       ├── QuizScreen.kt               # 4択問題UI
│   │       ├── AnnotationResultScreen.kt   # 生成した補記メモ表示
│   │       ├── AnnotationManagerScreen.kt  # 補記一覧・削除
│   │       ├── SectionChatSheet.kt         # セクションAIボトムシート
│   │       ├── markdown/
│   │       │   ├── MarkdownParser.kt       # ブロック・インラインMarkdown解析
│   │       │   ├── MarkdownRenderer.kt     # Compose描画
│   │       │   └── NoteSections.kt         # 見出し単位セクションモデル
│   │       └── theme/AppColors.kt          # 色・グラデーション・ボタン役割
│   └── res/values/                         # app_name、テーマ、最低限の色定義
└── test/java/com/example/newproject/
    ├── NoteRepositoryTest.kt
    ├── MarkdownParserTest.kt
    ├── InlineMarkdownTest.kt
    ├── QuizResponseParserTest.kt
    └── AnnotationComposerTest.kt
```

---

## 4. アーキテクチャ

### 4.1 レイヤーと依存方向

```text
Compose UI / MainActivity
          │ ユーザーイベント、StateFlow購読
          ▼
     NoteViewModel
       ├── SearchController
       ├── SectionChatController
       └── AnnotationController
          │
          ├──────────────► NoteRepository ──► SAF / DocumentsContract
          │
          └──────────────► UseCase ──► AiClient ──► ML Kit / Gemini Nano

純粋ロジック:
MarkdownParser / NoteSections / QuizResponseParser /
AnnotationComposer / NoteTitleNormalizer / AiResponseParsing
```

この構成は厳密なマルチモジュールClean Architectureではない。すべて同一 `:app` モジュール内にあり、Controller が `MutableStateFlow<NoteUiState>` を直接更新する。ただし、責務境界はファイル単位で明示されており、小規模アプリとしては理解しやすい構成である。

### 4.2 状態の単一ソース

`NoteViewModel` が `MutableStateFlow<NoteUiState>` を所有し、UIには読み取り専用の `StateFlow` として公開する。`MainActivity` は `collectAsStateWithLifecycle()` で購読する。

Controller は独自の Flow を作らず、共有された `_uiState` の担当フィールドだけを `copy()` で更新する。

| 担当 | 更新する主な状態 |
|---|---|
| `NoteViewModel` | `noteState`、`summaryState`、`relatedNotesState`、`quizState`、`wikilinkTitles` |
| `SearchController` | `folders`、`selectedFolder`、`searchState` |
| `SectionChatController` | `sectionChat` |
| `AnnotationController` | `annotationState`、`annotationListState` |

### 4.3 状態モデル

`NoteUiState` は次の状態を集約する。

- `vaultSelected`: Vault選択済み表示用フラグ
- `noteState`: Idle / Loading / Success / Empty / Error
- `summaryState`: Idle / Loading / Success / Downloading / AiUnavailable / Error
- `relatedNotesState`: Idle / Loading / Success / Error
- `quizState`: Idle / Loading / Success / Error
- `annotationState`: Idle / Loading / Success / Error
- `annotationListState`: Idle / Loading / Success / Error
- `sectionChat`: シートを閉じている場合は `null`
- `folders`、`selectedFolder`、`searchState`: 検索タブ用
- `wikilinkTitles`: 現在ノートから抽出したリンク先タイトル

ノートまたはVaultの切替時は `resetNoteScopedStates()` により、要約・関連・クイズ・補記結果・セクションチャットを一括リセットする。検索状態はVault切替時だけ別途リセットする。

---

## 5. ナビゲーションと画面構成

### 5.1 ルート

| 種別 | route | 画面 |
|---|---|---|
| トップレベル | `note` | ノート閲覧 |
| トップレベル | `search` | さがす |
| トップレベル | `related` | 関連ノート |
| トップレベル | `ai` | AIアシスト |
| トップレベル | `options` | オプション |
| 全画面 | `quiz` | 4択Q&A |
| 全画面 | `annotation` | AI補記生成結果 |
| 全画面 | `annotation_manager` | AI補記の削除管理 |

`navigateToTab()` は `popUpTo`、`saveState`、`restoreState`、`launchSingleTop` を使い、トップレベルタブのバックスタック増殖を抑えつつ状態を復元する。

### 5.2 画面幅対応

`AppScaffold` は `WindowSizeClass` を参照し、Expanded幅では左側 `NavigationRail`、それ以外では下部 `NavigationBar` を使用する。クイズ・補記結果・補記管理の全画面ルートではタブUIを表示しない。

### 5.3 画面ごとの責務

#### ノートタブ

- Vault選択とランダム表示
- Markdown本文の表示とテキスト選択
- 本文パネルのフェード＋スケール表示
- 明示ボタンによる全画面閲覧
- スクロール位置から現在セクションを判定
- ドラッグ可能な吹き出しからセクションAIを起動
- 読書画面向けの低彩度グラデーションを使用

#### さがすタブ

- Vault第一階層のフォルダを横スクロールChipで選択
- 自然文クエリからAIが3件を選ぶ検索
- 選択スコープ内からAIを使わず3件ランダム抽出
- 結果からノートを開き、ノートタブへ移動
- 更新日を `yyyy/MM/dd` 形式で表示

#### 関連タブ

- 規則ベース関連ノートとAI推薦を別セクションで表示
- wikilink一致ノートに `linked` バッジを表示
- AI利用不可、モデル準備中、AIエラーを状態別に表示
- 結果からノートを開き、ノートタブへ移動

#### AIタブ

- 自動生成されたノート要約を表示
- 4択Q&A生成画面への入口
- AI補記メモ生成画面への入口
- モデルダウンロード時は進捗を表示

#### オプション

- 現在は「AI補記メモを削除」の1項目
- 補記一覧で1件削除・全件削除が可能
- 削除前に確認ダイアログを表示

---

## 6. 主要機能のデータフロー

### 6.1 Vault選択と復元

```text
OpenDocumentTree
  → 読み書き可能な永続URI権限を取得
  → SharedPreferencesへURI文字列を保存
  → 全体ノートキャッシュ・検索キャッシュ・旧状態を破棄
  → ランダムノートを1件読み込む
```

次回起動時は SharedPreferences のURIを復元し、`vaultSelected = true` にする。起動直後にノートを自動読込する処理はなく、ユーザーがランダム表示するか検索結果を開くまで `noteState` は Idle のままである。

### 6.2 ランダムノート表示

```text
loadRandomNote()
  → 旧ノートに属するJobをキャンセル
  → noteState = Loading、ノート依存状態をリセット
  → Vault全体をBFS走査（60秒以内ならキャッシュ利用）
  → _AI補記フォルダを除いた .md から random()
  → UTF-8で本文読込
  → noteState = Success
  ├── fetchSummary()
  └── fetchRelatedNotes()
```

VaultにMarkdownがなければ `NoteState.Empty`、読み込み失敗は `NoteState.Error` となり、ノート画面では一般化したエラー文、同時に Toast で例外メッセージを表示する。

### 6.3 ノート要約とモデルダウンロード

`SummarizeUseCase` はAIの状態を確認する。

| AI状態 | 動作 |
|---|---|
| Available | 先頭1,200文字を含むプロンプトで2〜4文を生成 |
| NeedsDownload | モデルダウンロードを開始し進捗を `SummaryState.Downloading` へ反映 |
| Unavailable | `SummaryState.AiUnavailable`。現在のUIでは要約パネル自体を表示しない |
| 生成失敗 | `SummaryState.Error` |

ダウンロード完了後は保持していたタイトル・本文で要約と関連ノート検索を再実行する。

### 6.4 関連ノート

関連ノートは規則ベースとAIベースの2段構成である。

#### 規則ベース

1. 現在ノート自身を正規化タイトルで除外する。
2. 本文の `[[wikilink]]` と一致するノートを抽出する。
3. ファイル名先頭が4桁16進数の場合、上2桁が同じノートを同一グループとして抽出する。
4. wikilink一致を先、同一グループを後に連結し、URIで重複排除して最大5件返す。

#### AI推薦

1. 4桁プレフィックスがある場合、「上2桁一致 → 上1桁一致 → プレフィックスなし」の順で最大40候補に絞る。
2. 現在本文の先頭600文字と候補タイトルをAIへ渡す。
3. AI返却の箇条書き・連番・引用符・`[linked]` を除去する。
4. 正規化タイトルで実ノートへ戻し、規則ベース結果との重複を除いて最大5件返す。

AIが利用不可またはモデル未準備でも、規則ベース結果は表示できる。AI生成で例外が起きても `RelatedNotesResult.Error` にはせず、規則ベース結果と `AiRecommendationStatus.Error` を返す設計である。

### 6.5 さがす（AIピッカー）

検索スコープは次の仕様である。

| 選択 | 対象 |
|---|---|
| ルート直下 | Vault直下の `.md` のみ。非再帰 |
| 第一階層フォルダ | 選択フォルダ以下を再帰走査 |

検索タブでは `_AI補記` を除外しないため、補記メモも検索・ランダム候補になり得る。

自然文検索では候補が40件を超える場合だけ、クエリとファイル名の文字bigram重複数で上位40件に絞る。その後AIへタイトル一覧を渡し、最大3件を取得する。AIが利用不可・未ダウンロードの場合はフォールバックする。

重要な現状仕様として、候補が40件以下の場合はbigram並べ替えを行わない。したがってAI非対応時のフォールバックは「キーワード一致上位」ではなく、SAFから得た候補リストの先頭3件である。画面文言の「キーワード一致で表示しています」とは厳密には一致しない。

ランダムモードはAIを使用せず、`shuffled().take(3)` で選ぶ。

### 6.6 セクションAI

ノート本文は一度Markdownブロックへパースし、描画とセクション判定で共有する。現在の `LazyColumn` 先頭可視ブロック以前にある最も近い見出しを現在セクションとする。

セクション範囲は、対象見出しから「同レベルまたは上位レベルの次の見出し」の直前までで、配下の小見出しを含む。見出しが存在しない位置ではノート全体を対象にする。

吹き出しを開くと次の順でAIを使用する。

1. セクション先頭1,500文字から要約を生成する。
2. 同じセクションから最大3件の質問候補を生成する。
3. ユーザーが候補をタップすると、セクション本文と会話履歴を渡して回答を生成する。

回答プロンプトは「セクションに書かれていない内容を推測しない」よう制約する。自由入力欄はなく、現在のUIではAIが生成した質問候補のタップだけが質問入力経路である。

### 6.7 4択Q&A

1. 現在ノートの先頭1,200文字をAIへ渡し、5問の4択問題を要求する。
2. `Q:` 行を問題開始として、`A:`〜`D:`、`ANSWER:`、任意の `EXPLANATION:` をパースする。
3. 必須フィールド欠落や正解記号がA〜D以外の問題は捨てる。
4. ユーザー選択後に正誤、正解、解説を表示し、次の問題へ進む。

クイズ生成では事前の `checkAvailability()` を行わず直接 `generate()` するため、AI非対応・モデル未準備は一般的な `QuizState.Error` として扱われる。

### 6.8 AI補記メモ

1. 現在ノート、要約、関連ノート、AI推薦、wikilinkを入力にする。
2. AIへ固定選択肢による「粒度評価」と、具体的な「補記すべき内容」をMarkdownで生成させる。
3. `AnnotationComposer.hasAnnotationBody()` で必須見出し内に本文があるか確認する。
4. Source、Created、Generated by のメタ情報を付ける。
5. Vault直下の `_AI補記` フォルダを検索し、なければ作成する。
6. `{タイトル}__補記_{yyyyMMdd_HHmm}.md` としてUTF-8保存する。

タイトル中の SAF上不適切な記号は `_` へ置換する。生成本文で片方の必須見出しが欠落した場合は、保存時に空見出しを補完する。

補記一覧はファイル名の `__補記_` より後ろのタイムスタンプで降順に並べる。削除は `DocumentsContract.deleteDocument()` を使う。

---

## 7. SAF・Vaultアクセス層

### 7.1 走査方式

`NoteRepository` は `queryChildren()` にカーソル処理を集約し、`ArrayDeque` を用いたBFSでフォルダを再帰走査する。再帰関数ではないため、深いフォルダでコールスタックを消費しない。

取得列は次の4項目。

- document ID
- display name
- MIME type
- last modified

`lastModified` がプロバイダから返らない場合は `null` とし、UIでは更新日を表示しない。

### 7.2 読み書き

- 読み込みは `openInputStream()` と UTF-8 `bufferedReader()`。
- 補記保存は `createDocument()` と `openOutputStream()`。
- ファイル操作は `Dispatchers.IO` 上で実行する。
- Vault URIは SharedPreferences に保存し、SAFの永続URI権限と組み合わせて再利用する。

### 7.3 メタデータ解析

`parseMeta()` は以下を抽出する。

- 先頭YAML frontmatterの `tags`
- 先頭YAML frontmatterの `aliases`
- 本文全体の `[[wikilink]]`

frontmatterは `[a, b]` のインライン形式と、インデントされた `- item` のブロック形式に限定した簡易解析であり、完全なYAMLパーサーではない。現在、関連ノート処理で実際に使われるのは `wikilinkTitles` だけで、`tags` と `aliases` は抽出後の利用箇所がない。

### 7.4 タイトル正規化

Obsidianリンクとの照合時は次を除去する。

- 前後空白
- `|表示名`
- `#見出し`
- `^ブロックID`
- フォルダパス
- `.md` 拡張子（大文字小文字を無視）

照合用にはさらに小文字化する。正規化後タイトルをMapキーにするため、異なるフォルダに同名ノートがある場合は後にMapへ入った一方だけがAI返却タイトルの解決先になる。

---

## 8. AI層

### 8.1 `AiClient`

```kotlin
interface AiClient {
    suspend fun checkAvailability(): AiAvailability
    suspend fun generate(prompt: String): String
    fun downloadModel(): Flow<DownloadStatus>
}
```

AI利用側はこのインターフェースに依存する。実装は本番用 `AICoreClient` と手動UI確認用 `StubAiClient` の2つ。

### 8.2 モデル設定

`AICoreClient` は `ModelPreference.FULL` を指定して `Generation` クライアントを遅延生成する。状態は次のようにアプリ内の3状態へ変換する。

| ML Kit状態 | アプリ状態 |
|---|---|
| AVAILABLE | Available |
| DOWNLOADABLE / DOWNLOADING | NeedsDownload |
| その他・状態確認例外 | Unavailable |

### 8.3 直列化とタイムアウト

`generate()` は companion object の `Mutex` で直列化される。要約、関連推薦、検索、クイズ、補記、セクションAIが同時に要求されても、モデル生成は1件ずつ実行される。

タイムアウト60秒はMutex取得後から計測するため、ロック待ち時間はタイムアウトに含まれない。ML Kitの `TimeoutCancellationException` は `AiTimeoutException` に変換し、通常の画面エラーとして扱えるようにしている。

この設計はモデルへの同時生成を避ける一方、先行生成が長いと後続機能が待たされる。ユーザーから見ると、各機能の60秒に加えてロック待ち時間が発生し得る。

### 8.4 プロンプト入力上限

| 機能 | 本文上限 | 候補上限・出力 |
|---|---:|---|
| 要約 | 1,200文字 | 2〜4文 |
| 関連ノート | 600文字 | タイトル最大80件（実際の呼出側は最大40件）、5件要求 |
| AIピッカー | 本文なし | タイトル最大40件、3件要求 |
| クイズ | 1,200文字 | 4択5問 |
| AI補記 | 2,000文字 | 必須2セクション |
| セクション要約 | 1,500文字 | 2〜4文 |
| セクション質問・Q&A | 1,500文字 | 質問候補最大3件 |

すべて `String.take()` による文字数上限であり、トークン数や意味境界では切っていない。長いノートの後半はAI入力に含まれない。

---

## 9. Markdown解析・描画

### 9.1 対応ブロック

- 見出し H1〜H6
- 段落
- 箇条書き・番号付きリスト
- fenced code block
- 水平線
- 引用
- タスクリスト
- パイプテーブル

番号付きリストは解析時に番号情報を保持せず、描画では通常の箇条書きになる。コードフェンスの言語指定も保持しない。

### 9.2 対応インライン記法

- `***太字イタリック***`
- `**太字**`
- `*イタリック*`
- `~~打ち消し線~~`
- `` `インラインコード` ``
- `[[Obsidianリンク]]`
- `[ラベル](URL)`

リンクは色と下線で装飾するだけで、タップ遷移やURLオープンは実装していない。画像、埋め込み、脚注、HTML、数式などは専用対応していない。

### 9.3 防御的処理

- 先頭の閉じられたYAML frontmatterは描画対象から除外する。
- テーブル中間の空セルを保持して列ずれを防ぐ。
- 強調記号は中身が空でなく、先頭・末尾が空白でない場合だけ成立させる。
- `[label](url)` は最初の `]` の直後が `(` の場合だけリンクとみなし、`arr[0]` などの誤検出を防ぐ。
- CRLFをLFへ正規化する。

### 9.4 描画効率

ノート画面では `buildNoteSectionModel()` が作成した `MarkdownBlock` をレンダラーへ渡し、セクション解析と描画による二重パースを避ける。インラインの `AnnotatedString` もテキスト単位で `remember()` する。

通常表示と全画面表示は同じパース済みブロックを共有するが、それぞれ独立した `LazyListState` を使うためスクロール位置は同期しない。

---

## 10. 並行処理・ライフサイクル・キャッシュ

### 10.1 Job管理

`NoteViewModel` は次のJobを保持する。

- `noteLoadJob`
- `summaryJob`
- `relatedNotesJob`

`SectionChatController` は `openJob` と `answerJob` を保持する。ノート切替・Vault切替・シート閉鎖時にキャンセルし、旧ノートまたは旧セクションのAI応答による上書きを防ぐ。

一方、次の処理は要求単位のJobを保持していない。

- 検索・スコープ内ランダム
- クイズ生成
- 補記作成・補記一覧・削除
- 要約側のモデルダウンロードJob

そのため、短時間に複数要求できる経路では完了順による状態上書きの余地がある。UIでLoading中のボタンを無効化している箇所もあるが、すべての経路を構造的に保護しているわけではない。

### 10.2 CancellationException

要約、関連ノート、セクションAIの主要経路では `CancellationException` を再throwし、キャンセルを一般エラーに変換しない。

一方、`SearchPickerUseCase`、クイズ生成、補記Controllerの一部は広い `Exception` で捕捉する。ViewModel破棄時などのキャンセルがエラー状態へ変換される可能性があり、キャンセル方針は機能間で完全には統一されていない。

### 10.3 キャッシュ

| キャッシュ | キー | TTL | 破棄 |
|---|---|---:|---|
| Vault全体ノート | 現在Vault単位で1件 | 60秒 | Vault切替 |
| 検索スコープノート | `documentId`、ルートはnull | 60秒 | Vault切替 |

キャッシュによりランダム表示や検索のたびのSAF全走査を避ける。外部のObsidian同期・編集結果は最大60秒反映が遅れる。空リストは全体キャッシュで再利用されないため、MarkdownがないVaultでは操作ごとに再走査する。

---

## 11. エラー処理とフォールバック

### 11.1 良い点

- ノート読込失敗、AI生成失敗、モデルダウンロード失敗をsealed stateでUIへ伝える。
- 関連ノートはAI失敗時も規則ベース結果を維持する。
- セクション質問候補の失敗は要約・Q&A本体を壊さない。
- AIの空応答は要約文・チャット回答・補記で一定の防御がある。
- 補記保存前に最低限の必須セクション検証を行う。

### 11.2 注意点

- フォルダ一覧取得失敗は握りつぶされ、ユーザーには通知されない。
- 補記削除は `deleteDocument()` の `Boolean` を確認せず一覧を再読込する。失敗時は対象が残ることで間接的に分かるが、明示エラーは出ない。
- クイズはパース結果が0件でも `QuizState.Success(emptyList())` となり、UI側で「生成できませんでした」と表示する。
- `checkAvailability()` 自体の例外は `Unavailable` にまとめるため、非対応端末と一時的な状態取得失敗を区別できない。
- `ContentResolver.openInputStream()` が `null` の場合は空文字を返し、読込失敗と空ノートを区別しない。

---

## 12. データ保護・プライバシー

- ノート本文はアプリ内で読み取り、AI生成は端末内 Gemini Nano を利用する設計である。
- クラウドAI API、独自サーバー、解析SDKへの送信コードは存在しない。
- 初回モデル取得にはML Kit側のダウンロードが必要になる。
- Vaultへの書き込みは `_AI補記` フォルダの作成と補記Markdown保存、補記削除に限定される。
- `android:allowBackup="true"` であり、SharedPreferencesのVault URI文字列はバックアップ対象になり得る。実際のSAFアクセス可否は端末側の永続URI権限に依存する。
- ログ出力コードはなく、ノート本文やAIプロンプトをLogcatへ明示出力していない。

---

## 13. テスト状況

### 13.1 ユニットテスト内訳

| テストファイル | ケース数 | 主な対象 |
|---|---:|---|
| `NoteRepositoryTest.kt` | 5 | Markdown判定、wikilink・タイトル正規化、補記ファイル名安全化 |
| `MarkdownParserTest.kt` | 7 | frontmatter、テーブル空セル、見出し、コード、CRLF、引用 |
| `InlineMarkdownTest.kt` | 11 | 強調、リンク、コード、打ち消し、誤検出防止 |
| `QuizResponseParserTest.kt` | 10 | 改行揺れ、前置き、欠落項目、不正な正解、説明省略 |
| `AnnotationComposerTest.kt` | 8 | 必須セクション、Markdown組立、インデント混入防止 |
| **合計** | **41** | |

### 13.2 実行結果

```text
./gradlew testDebugUnitTest
BUILD SUCCESSFUL
```

2026-07-19に Android Studio同梱JBRを指定して実行し、コンパイルと全ユニットテストが成功した。

### 13.3 未カバー領域

- `NoteViewModel` と各Controllerの状態遷移
- `RelatedNotesUseCase` と `SearchPickerUseCase` の候補選定・フォールバック
- `PromptBuilder` の出力契約
- SAFのカーソル走査、ファイル作成、削除
- Gemini Nanoの利用可否、ダウンロード、生成、タイムアウト
- Compose UI、NavigationBar/Rail、全画面遷移
- 連続操作時のキャンセルと競合
- 実際のObsidian Vaultを使ったinstrumentation/E2Eテスト

現在の41テストは、Android依存の薄い純粋ロジックの回帰防止には有効だが、アプリ全体の動作保証範囲は限定的である。

---

## 14. コード品質評価

### 14.1 強み

1. **責務分割が明確**

   旧来の巨大ViewModelにすべてを置かず、検索・セクションAI・補記をControllerへ分離している。

2. **UI状態が一元化されている**

   Compose側は `NoteUiState` を読むだけで、画面ごとの状態追跡が分散しにくい。

3. **AI非依存の価値を残している**

   関連ノートはwikilinkとファイル名規則で動作し、検索にはランダムモードと限定的フォールバックがある。

4. **端末負荷への配慮がある**

   AI生成の直列化、60秒タイムアウト、SAF走査キャッシュ、Markdownパース再利用を実装している。

5. **壊れやすい文字列処理が純粋関数化されている**

   クイズパース、補記Markdown、タイトル正規化、Markdown解析をAndroid I/Oから分離し、ユニットテスト可能にしている。

6. **Obsidian固有仕様への配慮がある**

   `.md`、wikilinkの別名・見出し・ブロック参照、frontmatter、補記専用フォルダを扱う。

### 14.2 残る技術的注意点

優先度は「現時点で確認できる影響範囲」に基づく。直ちに障害が起きることを意味しない。

| 優先度 | 項目 | 現状と影響 |
|---|---|---|
| 高 | 検索フォールバックの意味差 | 40件以下ではキーワード順位付けされず先頭3件。UI説明と利用者期待に差が出る |
| 中 | Job管理の不統一 | 検索・クイズ・補記等は要求IDやJobキャンセルがなく、将来UI導線が増えると古い完了結果が上書きし得る |
| 中 | 統合テスト不足 | SAF・端末AI・Navigationの不具合はローカルユニットテストで検出できない |
| 中 | AI入力が先頭固定長 | 長文ノートの中心・結論が後半にある場合、要約・クイズ・補記の品質が落ちる |
| 中 | 同名ノートの曖昧性 | 正規化タイトルMapが同名ノートを1件に上書きし、AI推薦先が不定になり得る |
| 低 | YAML解析が簡易 | 複雑なYAML、引用、ネスト、複数行値には対応しない。現在は未使用のtags/aliasesへの影響が中心 |
| 低 | Markdownが限定実装 | ordered list番号、クリック可能リンク、画像、埋め込み、数式などは未対応 |
| 低 | 削除失敗の通知不足 | 補記削除失敗時に明示メッセージがない |
| 低 | 状態取得失敗と非対応の同一視 | AI状態確認の一時エラーも「利用不可」として扱われる |

---

## 15. 今後の改善候補

本節は現状解析から導かれる候補であり、今回の解析書更新では実装変更していない。

1. `SearchPickerUseCase` のフォールバックを候補数に関係なくbigramスコア順にし、UI文言と一致させる。
2. 検索・クイズ・補記生成にJobまたはrequest IDを導入し、最後の要求だけが状態を更新できるようにする。
3. `CancellationException` の再throw方針を全非同期処理で統一する。
4. `RelatedNotesUseCase`、`SearchPickerUseCase`、`PromptBuilder`、Controller状態遷移のユニットテストを追加する。
5. Fake `ContentResolver` またはinstrumentationテストで、Vault走査・補記保存・削除を検証する。
6. AI入力を単純な先頭切り出しから、見出し・冒頭・末尾・重要語を考慮した抽出へ発展させる。
7. 同名ノートの解決に相対パスまたは一意な候補IDを利用する。
8. AI非対応・モデル未準備・一時エラーのUXを要約・検索・クイズ・補記で統一する。

---

## 16. 解析時の確認事項

- 本解析は現行ソースコードを基準にし、過去の設計書ではなく実装との突合を優先した。
- 対象ワークツリーは解析開始時点で未コミット変更なし。
- 更新対象は本ファイル `docs/source_code_analysis.md` のみ。
- アプリ本体のKotlin、Gradle、Manifest、テストコードには変更を加えていない。
