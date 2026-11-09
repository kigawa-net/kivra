# 16. サイト構成(運用者向け Web UI の情報設計)

状態: 段階 4 完了(旧版撤去)。**正は Compose Wasm 版(`/ui/compose`)**。vanilla JS 版は撤去済みで、`/ui` と `/ui/` は `/ui/compose` へ 302 で転送する。画面・URL・ナビゲーションの構成を定める。

## 1. 前提と方針

- 利用者は運用者(kigawa とその担当 Assignee)。公開サイトではない。認証は `KIVRA_AUTH_MODE` 有効時のみ UI を出す(現行どおり)。
- 現行の 7 タブ(タスク / 開発目標 / order / 承認 / 予算 / 記憶 / 担当)は「データ種別」で並んでおり、運用者の「やりたいこと」と対応していない。**目的別に 4 グループへ再編**し、タブ数を増やさずに階層で整理する。
- プロジェクト(`/api/projects`)を最上位の絞り込み軸にする(現行サイドの `data-project-filter` を踏襲)。
- URL は引き続きハッシュルーティング(ビルド工程なし方針を維持)。Compose 版が正で、ハッシュ(`#/goals` 等)をブラウザ履歴と同期させてリンクを共有可能にする。旧 `/ui` のハッシュ URL は Compose 側が同じ形式を解釈して互換を保つ。

## 2. サイトマップ

```
/ui
├─ ホーム(#/)                    … 要対応サマリ(承認待ち・停止要求・失敗 Run・予算警告)
│
├─ 作業 (Work)
│   ├─ タスク      #/tasks             一覧 + 投入フォーム
│   ├─ 開発目標    #/goals             Goal 一覧
│   │   └─ 詳細   #/goals/<id>        計画・承認・中止
│   ├─ order       #/orders            一覧(周回状況)
│   │   └─ 詳細   #/orders/<id>       cycles(#/orders/<id>/cycles)
│   └─ Run        #/runs              一覧
│       └─ 詳細   #/runs/<id>         イベント(ライブ監視)・Operation・解放
│
├─ 判断 (Decide)
│   ├─ 承認        #/approvals         承認待ちキュー(バッジ表示)
│   └─ 停止要求    #/stop-requests     LLM 停止承認(ADR 0021)
│
├─ 資源 (Resources)
│   ├─ 予算        #/budget            状態・FX・未確定予約の解除(運用者のみ)
│   ├─ 記憶        #/memory            selfdev memory(ADR 0016)
│   └─ プロジェクト #/projects          一覧・作成・Run との紐付け
│
└─ 管理 (Admin)
    ├─ 担当        #/assignees         Assignee / メンバー / トークン(ADR 0020)
    ├─ 自動化      #/autopilot         Autopilot・自動マージ(ADR 0011 / 0014)・kill-switch
    └─ システム    #/system            health / ready / metrics リンク、バージョン
```

### 旧 URL との対応(互換)

| 旧 | 新 |
|---|---|
| `#/`(タスク) | `#/tasks`(`#/` はホームへ) |
| `#/goals` `#/orders` `#/approvals` `#/budget` `#/memory` `#/assignees` | 変更なし |
| `#/runs/<id>` | 変更なし |

旧 `#/` はタスク画面だったため、ホーム導入時はブックマーク影響を避けて、ホームが空(要対応 0 件)のときのみタスク一覧を併記する。

## 3. ナビゲーション

- **ヘッダー(常設)**: ロゴ / グローバル検索(Run id・Goal・order) / 承認待ちバッジ(`#/approvals` へ) / ユーザー(`/api/auth/me`)。
- **サイドナビ**: 4 グループ見出し + 配下項目。API で無効なセクション(自律運転 off、運用者以外の予算 等)は項目ごと非表示(現行方針を継続)。
- **プロジェクトフィルタ**: サイド最上部。選択はクエリ `?project=<id>` で保持し、全一覧に適用。
- **パンくず**: 詳細画面のみ(例: 作業 › order › #12 › cycle 3)。
- 狭い画面ではサイドナビをドロワー化(現行 371 行目のブレークポイントを流用)。

## 4. 画面ごとの責務(要点)

| 画面 | 主目的 | 主な操作 | 主 API |
|---|---|---|---|
| ホーム | 今すぐ対処すべきものを 1 画面で | 各項目から該当詳細へ | status / approvals / stop-requests / budget status |
| タスク | Task 投入と状況把握 | 投入(idempotencyKey) | `/api/dev/tasks` `/api/runs` |
| 開発目標 | Goal の作成・計画承認・中止 | 承認 / 中止(理由必須) | `/api/selfdev/goals` |
| order | 継続ループの周回確認 | 停止要求 | `/api/selfdev/orders` `…/cycles` |
| Run 詳細 | 1 Run の追跡 | Hold / Cancel / 解放(理由必須) | `/api/runs/{id}` `…/operations` |
| 承認 | 人の承認が要る操作のキュー | 承認 / 却下 | approvals |
| 予算 | 支出の把握と例外処理 | 未確定予約の解除(理由必須) | `/api/admin/budget/*` |
| 担当 | 役割・トークン管理 | 追加 / 失効 | `/api/selfdev/assignees` |

理由入力ダイアログ(解放・中止・予算解除)は共通コンポーネント 1 つに寄せる(`docs/dashboard-audit.md` 課題 2)。
プロジェクト詳細の運用者欄にある「今月の予算」(`PUT` / `DELETE /api/projects/{id}/budget`)は、上限の設定と解除を確認の後だけ送る(理由は取らない)。

## 5. 実装への落とし込み(Compose 版が対象)

1. `Navigation.kt` の `NavDestination` に `group` を追加し、サイドナビを 4 グループ描画へ。`fromRoute` の既定をホームに。
2. `NavDestination` に `Projects` / `StopRequests` / `Autopilot` / `System` を追加し、`screens/` に対応画面を足す。ハッシュと選択状態の双方向同期を `App.kt` に入れる。
3. ホームは新規 API を作らず、既存の status・approvals・stop-requests・budget の読み取りを束ねる(`kivra-ui-api` の ReadModel に `HomeSummary` を足す場合は別 PR)。
4. 旧 `/ui` 側は機能追加せず、Compose 版が同等になった時点で `/ui` を `/ui/compose` へリダイレクトして撤去する。`dashboard-audit.md` の課題(`app.js` 肥大・ダイアログ重複)は撤去で解消する扱いとし、旧版は修正しない。(完了: 旧版の `index.html`、`app.js`、`app.css` とそのテストを削除し、`/ui` と `/ui/` を `/ui/compose` への転送にした)
5. 段階: ① ナビ再編 → ② ホーム → ③ 検索(クライアント側絞り込み。結果は詳細画面へ遷移。Run・Goal・order の読み取り詳細とパンくずは Compose 版に実装済み) → ④ 旧 `/ui` 撤去(完了)。

## 6. 未決事項

- ~~旧 `/ui` の撤去条件~~(解決: 撤去済み。撤去は Compose 版の機能移植の完了後に行った。承認・予算解除・解放などの理由必須ダイアログも Compose 版に移植済み)。
- 「システム」画面は metrics リンクだけなら独立画面にせず、フッターで足りるか。
- Run を独立項目にするか、タスク配下に含めるか。
