# ダッシュボード（`/ui`）の現状調査

対象は、運用者向けの最小 Web UI（`/ui`）である。ソースコードは変更していない。行番号は調査時点のもの。

## 対象ファイル

| ファイル | 行数 | 役割 |
|---|---|---|
| `kivra-app/src/main/resources/ui/index.html` | 208 | 画面の骨組み。カード、ダイアログ、凡例 |
| `kivra-app/src/main/resources/ui/app.js` | 1968 | 画面の全ロジック（単一の即時関数） |
| `kivra-app/src/main/resources/ui/app.css` | 546 | スタイル。ダークモードは 18 行目、狭い画面は 371 行目 |
| `kivra-app/src/main/kotlin/net/kigawa/kivra/routes/UiRoutes.kt` | 58 | `/`、`/ui`、`/ui/`、`/ui/app.js`、`/ui/app.css` を classpath から配信 |
| `kivra-app/src/test/js/ui-behavior.test.js` | 1013 | 画面の振る舞いのテスト（テスト名は振る舞いの説明） |

画面が呼ぶ `/api/*` のエンドポイントは、この調査では読んでいない。

## ルーティングと有効化

- `UiRoutes.kt` は静的ファイルを返すだけで、認証は掛けていない。`/api/*` は Task API の認証で保護される（README「UI」節）。
- 登録されるのは `KIVRA_AUTH_MODE` が設定されているときだけ。未設定なら `/ui` は 404。
- 画面内のルーティングはハッシュ（`#/`、`#/runs/<id>` 形式）。`parseRoute()`（809 行目）と `route()`（820 行目）が一覧と詳細を切り替える。

## 画面の構成（index.html）

1. Task 投入カード（`#submit-form`）
2. 開発目標（Goal）カード（`#goal-section`）。自律運転 API が有効なときだけ表示
3. Goal の一覧と承認（`#plans-section`）。同じく自律運転が有効なときだけ表示
4. Run の一覧（`#runs-list`）
5. 予算カード（`#budget-section`）。運用者だけに表示
6. Run の詳細（`#view-detail`）
7. ダイアログ 4 つ: 解放（`#release-dialog`）、Goal の中止（`#stop-dialog`）、予算の未確定予約の解除（`#budget-dialog`）

## 課題

### 1. `app.js` が 1968 行の 1 ファイルに機能をすべて詰めている

1 つの即時関数の中に次の機能が混在している。

- API クライアント（`apiFetch` 332 行目、`fixedCode` 316 行目）
- Task 投入（`onSubmit` 468〜534 行目、約 66 行）
- Run 一覧（`renderRuns` 563 行目、`refreshRuns` 612 行目）
- 状態とヘルスチェック（`refreshStatus` 660 行目、`checkHealth` 687 行目）
- Run 詳細（`renderDetail` 728 行目、`loadDetail` 779 行目）
- ルーティング（809〜854 行目）
- 解放（855〜987 行目。`submitRelease` は約 77 行）
- 予算（988〜1393 行目）
- Goal と自律運転（1395〜1900 行目付近）
- タイマー（1896 行目以降）

機能ごとに分割すると、変更の影響範囲が追いやすくなる。ビルド工程を持たない方針（README）を保つなら、ファイルを分けるか、`window` を汚さない名前空間を機能ごとに分けるかの二択になる。

### 2. 理由入力ダイアログのロジックが 3 か所に重複している

解放、Goal の中止、予算の解除の 3 つが、同じ形のロジックを持つ。

- 入力の取得と trim: `releaseReasonText`（871 行目）、`stopReasonText`（1815 行目）、予算は `budgetFormState`（1164 行目）
- 検証: `reasonIsValid`（875 行目）、`stopReasonValid`（1819 行目）、予算の検証（1171 行目付近）。上限値だけが違う（`RELEASE_MAX` 35 行目、`GOAL_STOP_MAX`、`BUDGET_TEXT_MAX` 962 行目）
- エラー表示: `showReleaseError`（880 行目）、`showStopError`（1824 行目）、`showBudgetError`（1183 行目）。本体は同じ（`textContent` を入れて `hidden` を切り替える）
- 送信ボタンの制御: `updateReleaseControls`（887 行目）、`updateGoalControls`（1416 行目）、`updateBudgetControls`（1205 行目）

共通化の候補は、検証関数、エラー表示、文字数カウンタの 3 つ。

### 3. 文字数カウンタの更新が 5 か所に散らばっている

`Array.from(x).length + ' / ' + MAX` という同じ式が次の箇所にある。

- `updateCounter`（450 行目）
- `updateBodyCounter`（396 行目）
- `updateGoalCounters`（1427 行目）
- `updateBudgetCounts`（1199 行目）
- `updateReleaseControls` の 889 行目

1 つのヘルパー（要素 ID、上限値を受け取る）にまとめられる。

### 4. 取得処理と更新間隔の管理が機能ごとに手書きされている

`refreshRuns`、`refreshStatus`、`refreshBudget`（1021 行目）、`refreshAutopilot`（1507 行目）、`refreshGoals`（1557 行目）が、それぞれ読み込み中フラグ、最終試行時刻、成功時刻を個別に持つ。`tick()`（1896 行目）も `if (now - state.xxxCheckedAt >= REFRESH_MS)` を 5 つ並べている。

`state` は約 50 個のフィールドを持つ単一オブジェクトで、どの値をいつ初期化するかが読み取りにくい。取得対象ごとの状態をまとめると見通しが良くなる。

### 5. ボタンの有効・無効の制御が 1 か所に集まっていない

`updateGoalControls`（1416 行目）は、Goal の入力、自律運転の操作、中止ダイアログの送信ボタンを同時に扱う。関心の異なる制御が 1 つの関数にある。中止ダイアログの制御は、中止用の関数に移すほうが分かりやすい。

### 6. 表示文言と検証メッセージが `app.js` の中に散らばっている

`MESSAGES`（18 行目）、`RELEASE_TEXT`（837 行目）、各ダイアログの説明文など、日本語の文言が定数とハードコードの両方にある。`index.html` にも文言がある。最小 UI という方針なら i18n は不要だが、文言の置き場所は 1 つに決めたほうがよい。

### 7. 固定の文字列がある

- `index.html` の 12 行目 `環境: dev` は固定。dev 以外の環境でも同じ表示になる。
- `#mode-note` の初期文言（「自己開発の設定を確認しています…」）は、取得前の表示として妥当か確認が要る。

### 8. CSS が 1 ファイルに追記されている

`app.css` は基本のスタイルの後に、機能ごとのブロックを追記する形になっている（438 行目「Autopilot」、516 行目「Budget」）。ブロックごとの重複セレクタや未使用ルールは、この調査では確認していない。`.card`、`.meta`、`.error` などの共通部品と機能固有のスタイルは、分けて読むと整理しやすい。

### 9. テストの確認範囲

`ui-behavior.test.js` は約 1000 行の 1 ファイルで、振る舞いごとのテストが並ぶ。Goal、解放、予算の各機能のテストがあることは確認した。`.github/workflows/ci.yml` に Node や npm の手順は見当たらず、README の CI 説明も `./gradlew test` と MariaDB のテストだけに触れている。この JS テストが CI で実行されているかは確認できていない。

## 整理の優先順位（提案）

1. 文字数カウンタとエラー表示のヘルパーを作る（課題 2、3）。挙動を変えず、差分が小さい
2. 理由入力ダイアログの検証と制御を共通化する（課題 2、5）
3. 取得処理と `tick()` の状態管理をまとめる（課題 4）
4. `app.js` を機能ごとに分割する（課題 1）。最後に行う。変更範囲が最も大きい
5. 固定文字列、CSS、テストの実行確認（課題 6〜9）

## 未確認事項

- 画面が呼ぶ `/api/*` のエンドポイントと、そのレスポンスの形
- `app.css` の重複セレクタと未使用ルール
- JS テストの CI 実行の有無（ci.yml を読んだ範囲では見当たらない）
