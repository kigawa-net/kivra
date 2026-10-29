---
sessionId: session-261028-000244-f4ff
---

# Requirements

### Overview
現在の HTML/CSS/JS UI（3ファイル）を Compose Multiplatform for Web（Kotlin/Wasm）に置き換えます。Ktor サーバー（API）はそのまま維持し、UI のみを Kotlin で記述する Compose に書き換えます。

### Scope
**In Scope:**
- `kivra-compose-ui` モジュール（Compose UI コード）を追加
- `kivra-web` モジュール（Wasm 構築と配信）を追加
- 7つのページ（タスク、開発目標、order、承認、予算、記憶、担当）を Compose に書き換え
- サイドバー（プロジェクト一覧）とトップヘッダー（ナビゲーションタブ）を Compose に書き換え
- Ktor サーバーから Compose Web アプリを配信（`/ui` パス）
- 既存の REST API（`/api/...`）をそのまま使用

**Out of Scope:**
- Android/iOS/Desktop への展開（後続フェーズ）
- Ktor サーバーの API 変更（変更なし）
- DB 層の変更（変更なし）
- OIDC 認証フローの変更（Cookie ベースを維持）

### Key Constraints
- Compose Multiplatform for Web は **Beta**（2025年9月）。WasmGC 対応ブラウザが必要
- 初期ロードに数秒かかる（Wasm のダウンロード・初期化）
- SEO 未対応（SPA なので問題なし）
- Kotlin 2.1.21 は Compose 1.8 と互換性あり

# Technical Design

### Current Implementation
- **UI**: `kivra-app/src/main/resources/ui/` 配下の 3 ファイル
  - `index.html` (431行) — 7ページの HTML 構造、ダイアログ、フォーム
  - `app.js` (4489行) — 全ロジック（ルーティング、API 呼び出し、状態管理、レンダリング）
  - `app.css` (822行) — 全スタイル
- **ルーティング**: ハッシュベース（`#/`, `#/goals`, `#/orders` など）。`PAGES` 配列で定義
- **API 呼び出し**: `apiFetch()` 関数が `/api/...` を呼び出し、JSON をパース
- **配信**: `UiRoutes.kt` が classpath から静的ファイルを配信（CSP ヘッダー付き）

### Architecture
```
graph LR
    subgraph Browser
        A[Compose Web UI<br/>Wasm]
    end
    subgraph Ktor Server
        B[ApiRoutes<br/>/api/*]
        C[ComposeRoute<br/>/ui]
    end
    subgraph DB
        D[MariaDB]
    end
    A -->|fetch /api/*| B
    C -->|index.html + Wasm| A
    B --> D
```

### Proposed Changes

#### 1. 新しいモジュールを追加
- **`kivra-compose-ui`**: Compose UI コード（commonMain でプラットフォーム非依存）
  - 画面（Screen）コンポーザブル
  - 状態管理（ViewModel）
  - API クライアント（Ktor Client）
  - ナビゲーション
- **`kivra-web`**: wasmJs ターゲット、Compose Web のエントリーポイント
  - `wasmJsBrowserDistribution` でビルド
  - 生成物を Ktor サーバーが配信

#### 2. Ktor サーバーの変更
- `UiRoutes.kt` を更新：静的ファイル配信 → Compose Wasm 配信
- `kivra-web` のビルド成果物（`build/dist/wasmJs/productionExecutable/`）を classpath にコピー
- CSP ヘッダーを更新（Wasm 用）

#### 3. Compose UI の構成
- **ナビゲーション**: `@Composable fun KivraApp()` — ルートコンポーザブル
- **レイアウト**: トップヘッダー（タブ）+ サイドバー（プロジェクト）+ メインコンテンツ
- **画面**: 各ページを Compose 関数に
  - `TasksScreen` — Run 一覧、Task 投入フォーム
  - `GoalsScreen` — Goal 一覧、承認、Autopilot
  - `OrdersScreen` — order 一覧、作成
  - `ApprovalsScreen` — 停止承認
  - `BudgetScreen` — 予算表示
  - `MemoryScreen` — 記憶一覧、絞り込み
  - `AssigneesScreen` — 担当管理
- **ダイアログ**: `ReleaseDialog`, `StopDialog`, `MemoryDialog`, `BudgetDialog` など

#### 4. 状態管理
- `KtorClient` で API 呼び出し（既存エンドポイントを使用）
- `@Stable` data class で API レスポンスを保持
- `remember { mutableStateOf(...) }` で UI 状態
- 後続で ViewModel パターンに改善可能

#### 5. ビルド構成
- `kivra-compose-ui`: KMP モジュール（jvm + wasmJs）
- `kivra-web`: wasmJs のみ、Compose Web 依存
- `kivra-app`: `kivra-web` に依存、ビルド時に Wasm 成果物をコピー

### Key Decisions
1. **Wasm（JS ではなく）**: パフォーマンスと将来性。WasmGC 対応ブラウザが主流
2. **既存 API を維持**: バックエンド変更なし。UI のみ書き換え
3. **Ktor Client で API 呼び出し**: サーバー側と同じ Ktor を使用、コード共有が可能
4. **段階的移行**: まず基本的な画面（タスク一覧）から、徐々に全画面を移行
5. **CSS は Compose Modifier に置き換え**: 外部 CSS ファイル不要

# Testing

### Validation Approach
- `./gradlew :kivra-compose-ui:compileKotlinWasmJs` — Compose UI モジュールのコンパイル確認
- `./gradlew :kivra-web:wasmJsBrowserProductionRun` — ローカルで Wasm サーバー起動してブラウザ確認
- `./gradlew :kivra-app:test` — 既存テストの全テスト合格（API 変更なしなので影響なし）

### Key Scenarios
1. ブラウザで `/ui` にアクセス → Compose UI が表示される
2. ナビゲーションタブでページ遷移
3. プロジェクトサイドバーでフィルタ
4. Run 一覧の表示と詳細表示
5. Task 投入フォームの送信

### Edge Cases
- Wasm ロード中のローディング表示
- API エラー時の表示
- プロジェクトが0件の場合の空状態
- モバイル画面（レスポンシブ）

# Delivery Steps

### ✓ Step 1: Add kivra-compose-ui and kivra-web modules
Compose Multiplatform の基盤モジュールを追加。KMP 構成、依存関係、ビルド設定を整える。
- `settings.gradle.kts` に `kivra-compose-ui` と `kivra-web` を追加
- `gradle/libs.versions.toml` に Compose Multiplatform 1.8.0 と Ktor Client（wasmJs）の依存を追加
- `kivra-compose-ui/build.gradle.kts` を作成（KMP: jvm + wasmJs、Compose 依存）
- `kivra-web/build.gradle.kts` を作成（wasmJs、Compose Web、Ktor Client）
- `kivra-compose-ui/src/commonMain/kotlin/App.kt` に基本の Compose エントリポイントを追加
- `kivra-web/src/wasmJsMain/kotlin/main.kt` に Wasm エントリーポイントを追加
- `./gradlew :kivra-compose-ui:compileKotlinWasmJs` でコンパイル確認

### ✓ Step 2: Implement navigation and layout shell
トップヘッダー（タブ）、サイドバー（プロジェクト）、メインコンテンツのレイアウトとルーティングを実装。
- `KivraApp` ルートコンポーザブルを作成（Scaffold レイアウト）
- トップヘッダー：アプリ名、ナビゲーションタブ（7ページ）、ヘルステータス
- サイドバー：プロジェクト一覧、空状態、作成フォーム
- `NavDestination` sealed class でルーティング定義
- `rememberNavController` でページ遷移
- プロジェクト API（`/api/projects`）を Ktor Client で呼び出し
- ローディング中とエラー時の表示

### ✓ Step 3: Implement Tasks page (Run list and submit form)
タスクページ（Run 一覧と Task 投入フォーム）を Compose で実装。最も重要な画面。
- `TasksScreen`: Run 一覧（`/api/runs`）、スコープ切り替え（自分/全員）
- Run 詳細（クリックで詳細表示、`/api/runs/{id}`）
- Task 投入フォーム（種別、タイトル、本文、送信）
- プロジェクトフィルタ連携
- 解放ダイアログ（Release dialog）
- Run 一覧のステータスバッジとソート

### ✓ Step 4: Implement remaining pages (Goals, Orders, Approvals, Budget, Memory, Assignees)
残りの6ページを Compose で実装。
- `GoalsScreen`: Goal 一覧、Autopilot 制御、昇格条件、Goal 作成フォーム
- `OrdersScreen`: order 一覧、作成フォーム、停止要求
- `ApprovalsScreen`: 停止承認リスト、承認ダイアログ
- `BudgetScreen`: 予算バー、未確定予約、解除ダイアログ
- `MemoryScreen`: 記憶一覧、絞り込み、ページネーション、削除
- `AssigneesScreen`: 担当一覧、作成フォーム、トークン発行
- 各ダイアログ（Stop、Memory、Budget、Order 承認など）

### ✓ Step 5: Wire Compose UI to Ktor server and update UiRoutes
Ktor サーバーが Compose Wasm アプリを配信するように UiRoutes を更新。
- `kivra-app/build.gradle.kts` に `copyComposeWebDist` タスクを追加（`wasmJsBrowserDistribution` の成果物を `resources/ui/compose/` にコピー）
- `UiRoutes.kt` を更新：wasm ファイルを動的に発見して配信（ファイル名ハードコードなし）
- CSP ヘッダーを更新（Wasm 用：`worker-src 'self'` と `child-src 'self'` を追加）
- `RolesCoverageArchitectureTest` を更新：新しいルートパターンを OPEN_ROUTES に追加
- `compose.dev.yml` で確認（ローカル起動して UI が表示されるか）
- `./gradlew :kivra-app:test` で全1998テスト合格確認