# 16. サイト構成(運用者向け Web UI の情報設計)

状態: 改訂 2(プロジェクトを最上位にする)。**正は Compose Wasm 版(`/ui/compose`)**。vanilla JS 版は撤去済みで、`/ui` と `/ui/` は `/ui/compose` へ 302 で転送する。画面・URL・ナビゲーションの構成を定める。

改訂 1(旧版)は「データ種別(タスク・開発目標・order・承認・予算・記憶・担当)で並べ、プロジェクトは絞り込み」だった。改訂 2 では、**プロジェクトを最上位の概念**にし、プロジェクトの中にすべてを置く。

改訂 2 の追補(未分類): `project_id` が NULL のデータ(PR-F までに残る既存の Run・開発目標・order・記憶)は、**疑似プロジェクト「未分類」**(固定 id `unassigned`)として扱う。§2・§3・§6・§8 の「未分類」を参照。

## 1. 前提と方針

- 利用者は運用者(kigawa とその担当 Assignee)。公開サイトではない。認証は `KIVRA_AUTH_MODE` 有効時のみ UI を出す(現行どおり)。
- **入口はプロジェクト一覧。** プロジェクトを選ぶと、ダッシュボード・タスク・開発目標・order・承認・記憶・担当・予算が、すべてそのプロジェクトの中のページになる。
- **全体**(プロジェクトを持たないもの)は別にまとめる。全体の予算・FX、自動化(Autopilot・自動マージ・kill-switch)、全プロジェクト横断の担当(Assignee・トークン)。
- **ホーム**は全プロジェクト横断の要対応サマリ。各項目から該当プロジェクトのページへ行く。
- URL はハッシュルーティング(ビルド工程なし方針を維持)。ハッシュをブラウザ履歴と同期させ、リンクを共有できるようにする。
- 旧 URL(プロジェクトを持たない `#/goals` など)は壊さない(§4)。

## 2. サイトマップ

```
/ui/compose
├─ ホーム(#/)                          … 全プロジェクト横断の要対応(承認待ち・失敗/解放待ち・実行中・予算の超過)
│
├─ プロジェクト(#/projects)            … 入口。一覧と作成(CREATE_PROJECT の人だけ)。各行に要約
│   └─ プロジェクト(#/projects/<id>) … ダッシュボードへ置き換え
│       ├─ ダッシュボード  #/projects/<id>/dashboard   Run 状態別件数・最近の Run・費用・活動。運用者はリポジトリの対応と振り分け
│       ├─ タスク        #/projects/<id>/tasks        このプロジェクトの Run の一覧 + Task の投入(自己開発はこのプロジェクトに入る)
│       │   └─ Run 詳細  #/projects/<id>/runs/<runId>
│       ├─ 開発目標      #/projects/<id>/goals        Goal の一覧 + 投入(自律運転が有効なとき)
│       │   └─ 詳細      #/projects/<id>/goals/<taskId>
│       ├─ order         #/projects/<id>/orders       order の一覧 + 作成
│       │   ├─ 詳細      #/projects/<id>/orders/<orderId>
│       │   └─ 周の一覧  #/projects/<id>/orders/<orderId>/cycles
│       ├─ 承認          #/projects/<id>/approvals    停止要求の承認待ちキュー
│       ├─ 記憶          #/projects/<id>/memory       selfdev memory(ADR 0016)の検索・削除
│       ├─ 担当          #/projects/<id>/assignees    このプロジェクトのメンバー(ADR 0020)
│       └─ 予算          #/projects/<id>/budget       今月の上限と使用量(運用者だけ。設定と解除)

未分類(疑似プロジェクト・id は `unassigned`。プロジェクトの実体が無いので、一覧のデータだけ)
    ├─ タスク        #/projects/unassigned/tasks        project_id が NULL の Run の一覧(作成なし)
    │   └─ Run 詳細  #/projects/unassigned/runs/<runId>
    ├─ 開発目標      #/projects/unassigned/goals        NULL の Goal の一覧(作成なし)
    │   └─ 詳細      #/projects/unassigned/goals/<taskId>
    ├─ order         #/projects/unassigned/orders       NULL の order の一覧(作成なし)
    │   ├─ 詳細      #/projects/unassigned/orders/<orderId>
    │   └─ 周の一覧  #/projects/unassigned/orders/<orderId>/cycles
    ├─ 承認          #/projects/unassigned/approvals    NULL の order の停止要求の承認待ち
    └─ 記憶          #/projects/unassigned/memory       project_id が NULL の記憶
│
└─ 全体(プロジェクトを持たない。運用者向け)
    ├─ 予算・FX          #/budget                     全体の予算と為替(運用者だけ)
    ├─ 自動化            #/autopilot                  自律運転・自動マージ・kill-switch(自律運転が有効なときだけ出す)
    └─ 担当(全体)        #/assignees                  Assignee・トークン(全プロジェクト横断)
```

- 「担当」は全体と配下の両方にある。全体は Assignee とトークンの管理(横断)、配下はそのプロジェクトのメンバー。同じ画面の部品を、絞り込みの有無で使い分ける。
- 「承認」は配下にあり、件数バッジはヘッダーとサイドナビに出す(全体の件数)。
- 「未分類」の項目は、プロジェクトの項目のうち、タスク・開発目標・order・承認・記憶だけ。ダッシュボード・担当・予算・リポジトリの対応は、プロジェクトの実体が要るので出さない。

## 3. URL 体系

| 種別 | 形 | 例 |
|---|---|---|
| ホーム | `#/` | `#/` |
| 全体の画面 | `#/<項目>`(`projects` `budget` `autopilot` `assignees`) | `#/budget` |
| プロジェクト(入口) | `#/projects` | |
| プロジェクト配下のページ | `#/projects/<id>/<page>`(`dashboard` `tasks` `goals` `orders` `approvals` `memory` `assignees` `budget`) | `#/projects/01J…/orders` |
| プロジェクト配下の詳細 | `#/projects/<id>/goals/<taskId>`、`…/orders/<orderId>`、`…/orders/<orderId>/cycles`、`…/runs/<runId>` | `#/projects/01J…/runs/01K…` |
| 旧い詳細(互換) | `#/goals/<id>`、`#/orders/<id>`、`#/orders/<id>/cycles`、`#/runs/<id>` | 同じ画面を開く。プロジェクトは URL に入らない |
| 旧い一覧(互換) | `#/tasks` `#/goals` `#/orders` `#/approvals` `#/memory` `#/runs` | §4 のとおり置き換える |

- `<id>` は ULID を `encodeURIComponent` 相当で符号化する。壊れた符号化・余分な区切りは「リンクが正しくありません」。
- 未分類の id は `unassigned`(ULID ではない)。`#/projects/unassigned` は未分類のタスク。`#/projects/unassigned/dashboard`・`…/assignees`・`…/budget` は解釈できないリンク(「リンクが正しくありません」)。
- `#/projects/<id>` はダッシュボードと同じ画面を開く。

## 4. 旧 URL との互換

| 旧 | 新 | 動き |
|---|---|---|
| `#/` | `#/`(ホーム) | 変えない(旧 `#/` は tasks だったが、ホームに移した) |
| `#/tasks` `#/goals` `#/orders` `#/approvals` `#/memory` `#/runs` | プロジェクトの同じページ | **置き換え**(§4.1) |
| `#/budget` | `#/budget`(全体の予算・FX) | 変えない(旧版でも全体の予算だった。プロジェクトの予算は `#/projects/<id>/budget`) |
| `#/autopilot` | `#/autopilot` | 変えない |
| `#/assignees` | `#/assignees`(全体の担当) | 変えない |
| `#/projects/<id>/budget` | 予算のページ | 旧版ではダッシュボードへ置き換えていたが、予算は独立したページになった(設計の決定に従う) |
| `#/projects/<id>/dashboard` | 変えない | |
| `#/goals/<id>` `#/orders/<id>` `#/runs/<id>` 等 | 変えない(同じ画面) | プロジェクトは URL に入らない。ブックマークはそのまま開く |

### 4.1 旧い一覧の置き換え(`resolveLegacy`)

旧い一覧の URL は、次の順でプロジェクトを決めて `#/projects/<id>/<page>` に置き換える(履歴に残さない)。

1. **最後に選んだプロジェクト**(この端末のブラウザに保存。プロジェクトの中の画面を開くたびに更新。保存値は ULID か `unassigned` の形を検証してから使う)が、今のプロジェクト一覧にあれば、それ。未分類(`unassigned`)は一覧に依らず有効。
2. なければ、**プロジェクトが 1 つだけ**ならそれ。
3. どちらでもなければ、**プロジェクト一覧**(`ProjectChooser`)を出す。案内文「「開発目標」はプロジェクトごとのページです。プロジェクトを選んでください。」を添える。URL は `#/projects` になる。
4. 一覧の取得に失敗したときは、保存しているプロジェクトだけを使う(なければ 3)。

置き換えるまでは「読み込み中…」を出す。

## 5. ナビゲーション

- **ヘッダー(常設)**: パンくず / 今いるプロジェクトの切替 / 検索 / 承認待ちの件数 / 役割(`/api/selfdev/me`)/ 更新。
  - 切替は「絞り込み」ではなく**「今いるプロジェクト」の切替**。選ぶと、今の画面と同じ種類の画面をそのプロジェクトで開く(例: `#/projects/A/orders` → `#/projects/B/orders`)。全体の画面からは、そのプロジェクトのダッシュボードを開く。「プロジェクト一覧へ」も選べる。
  - 承認待ちの件数は、プロジェクトの中ならそのプロジェクトの承認へ、全体ならホームへ行く。
- **サイドナビ(2 層)**:
  - 常に: ホーム、プロジェクト。
  - プロジェクトを選んでいるとき: そのプロジェクト名の節(ダッシュボード・タスク・開発目標・order・承認・記憶・担当・予算)。
  - 常に最後: 「全体」の節(予算・FX、自動化、担当)。プロジェクトを選んでいても、ここから全体へ出られる。
  - 権限や API の結果で隠す項目は、項目ごと隠す(§7)。残りのない節は見出しごと出さない。
- **パンくず**: 「プロジェクト › <プロジェクト名> › <ページ> › <詳細>」。
  - 全体の画面: 「全体 › <項目>」(プロジェクト一覧だけは「プロジェクト」)。
  - 旧い詳細(プロジェクトを持たない URL): 「プロジェクト › <ページ> › <詳細>」(ページはプロジェクトの一覧へ置き換えて開く)。
- 狭い画面ではサイドナビをドロワー化する(現行の方針を継続)。

## 6. ホームとプロジェクト一覧、プロジェクト未選択のとき

- **ホーム(全体)**: 全プロジェクトの要約を足した件数(承認待ち・失敗/解放待ち・実行中・予算の超過)。その下に、プロジェクトごとの行。行の各ボタンは、そのプロジェクトの該当ページへ行く(承認 → 承認、失敗・解放待ち/実行中 → タスク、予算 → 予算、「開く」→ ダッシュボード)。
- **プロジェクト一覧(全体)**: 一覧と作成。各行に要約(承認待ち・実行中・失敗/解放待ち・今月の費用と上限)。読めなかった項目は「取得できません」と出し、他の項目は出す。
- **未分類の行**: ホームの「プロジェクト別」の最後と、プロジェクト一覧の一覧の上に出す(プロジェクトが 1 つも無くても出す)。「開く」はタスク(Run の一覧)。データが 0 件なら薄く出し、「未分類のデータはありません」と添える。予算の行は無い。
- **プロジェクトを選んでいないとき**: サイドナビは「ホーム・プロジェクト・全体」だけ。ホームとプロジェクト一覧が入口。プロジェクト配下のページは、旧 URL からだけ開く(§4.1)。

要約は既存の API だけで作る(新しい API は足さない)。承認待ちは `/api/projects/{id}/approvals?state=PENDING`、Run の件数と予算はダッシュボード(`/api/projects/{id}/dashboard`)。失敗は `FAILED` と `UNKNOWN`(運用者の解放待ち、ADR 0009)、実行中は終わった状態(`FAILED` `COMPLETED` `CANCELLED` `NOT_EXECUTED`)以外。

未分類の要約はダッシュボードが無いので、一覧の API から数える: 承認待ちは `GET /api/selfdev/stop-requests?state=PENDING&projectId=unassigned`、Run は `GET /api/admin/runs?projectId=unassigned`(運用者)、それ以外は `GET /api/runs?projectId=unassigned`(運用者以外は 403 の後に自分の分)、目標と order は `…?projectId=unassigned`。**件数は一覧の上限(最大 100 件)の範囲**。0 件かどうかは、読めた行の合計(Run・目標・order・承認)で決める。記憶は数えない。

## 7. 権限と見える範囲

- 運用者(ORDER_MANAGE など)は、全プロジェクトのダッシュボード(scope ALL)、予算、リポジトリの対応、全体の項目を見られる。
- 運用者以外は、ダッシュボードが scope OWN(自分の Run だけ)になり、予算・リポジトリの対応・全体の項目は出さない(API が拒否するか、画面が見せない)。
- **目標: 運用者以外が見られるのは、メンバーとして属するプロジェクトだけ。** 担当の登録(`project_members`)を、プロジェクトの一覧と項目の出し分けに使う。
  - 現状: `GET /api/projects` はプロジェクトを絞らない(READ があれば全件を返す)。`project_members` による読み取りの絞りは、まだ入っていない(ADR 0022 PR-D は書き込みの判定まで)。UI だけでは絞れない。**後続の課題**(サーバー変更を伴うため、この PR ではサーバーを変えない)。

## 8. 画面ごとの責務(要点)

| 画面 | 主目的 | 主な操作 | 読む API |
|---|---|---|---|
| ホーム | 今すぐ対処すべきものを、プロジェクト別に | 該当プロジェクトのページへ | 各プロジェクトの approvals・dashboard |
| プロジェクト一覧 | 入口。作成と要約 | 開く・作成 | `/api/projects`、要約(approvals・dashboard) |
| ダッシュボード | 1 プロジェクトの状態 | リポジトリの対応・振り分け(運用者) | `/api/projects/{id}/dashboard`、`…/repos` |
| タスク | このプロジェクトの Run と投入 | 投入(自己開発は このプロジェクトに入る) | `/api/projects/{id}/...` は無いので `GET /api/runs?projectId=`、`POST /api/selfdev/tasks` |
| 開発目標 | Goal の作成・計画承認・中止 | 投入・承認・中止(理由必須) | `/api/projects/{id}/tasks`(別名)、`POST /api/projects/{id}/tasks` |
| order | 継続ループの周回確認・作成 | 作成・一時停止・停止要求 | `/api/projects/{id}/orders`(別名) |
| 承認 | 人の承認が要る停止要求 | 承認・却下 | `/api/projects/{id}/approvals?state=PENDING` |
| 記憶 | selfdev memory の検索と削除 | 検索・削除(理由必須) | `/api/projects/{id}/memory` |
| 担当(配下) | このプロジェクトのメンバー | 一覧 | `/api/projects/{id}/assignees` |
| 予算(配下) | 今月の上限と使用量(運用者) | 上限の設定・解除(確認の後) | dashboard の budget、`PUT/DELETE /api/projects/{id}/budget` |
| 予算・FX(全体) | 全体の予算と為替 | 未確定予約の解除(理由必須) | `/api/admin/budget/*` |
| 自動化(全体) | 自律運転の状態と操作 | 一時停止・再開・ブレーカー | `/api/selfdev/autopilot` |
| 担当(全体) | Assignee・トークン | 追加・失効(運用者) | `/api/selfdev/assignees` |
| Run 詳細 / 目標の詳細 / order の詳細 | 1 件の追跡 | Hold・Cancel・解放、中止、再計画 | `/api/runs/{id}`、`/api/selfdev/goals/{id}`、`/api/selfdev/orders/{id}`(id で読む) |

- 理由入力ダイアログ(解放・中止・予算解除)は共通コンポーネント 1 つに寄せる(`docs/dashboard-audit.md` 課題 2)。
- 作成フォーム(開発目標・order・Task の自己開発)の作成先は、そのプロジェクトに固定する。選ばせない。表示だけする。
- **未分類では作成フォームを出さない**(開発目標・order・タスク)。代わりに、画面の一文で理由を示す(「未分類は、プロジェクトに属さない既存のデータの一覧です。…作成フォームは出しません」)。未分類にデータを増やさないため。

## 9. 実装への落とし込み(Compose 版)

- `Navigation.kt`: `NavDestination` は全体(`globalPages`)とプロジェクト配下(`projectPages`)に分ける。`sidebarSections(projectId, projectName)` が 2 層のサイドナビを作る。
- `Route.kt`: `Route.Page`(全体)、`Route.ProjectPage`(配下)、`Route.Legacy`(旧い一覧の中間状態)、`Route.ProjectChooser`(置き換えの結果、決まらないとき)、詳細(`RunDetail` `GoalDetail` `OrderDetail` `OrderCycles`、`projectId` は null 可)、`Route.Invalid`。純粋関数: `parseRoute` / `toHash` / `resolveLegacy` / `withProjectOf` / `inProjectContext` / `breadcrumbs`。
- `Unassigned.kt`: 未分類の規則。`isUnassigned` / `pagesOf`(未分類は `NavDestination.unassignedPages`)/ `landingPageOf` / `projectLabel` / `validLastProjectId`。
- `ProjectApi.kt`: 未分類の一覧は旧ルート + `?projectId=unassigned`(`withQuery` で query を続ける)。未分類への作成の path は作らない(`require` で拒否)。
- `App.kt`: 2 層のサイドナビ、今いるプロジェクトの切替、旧い一覧の置き換え(`LaunchedEffect`)、最後に選んだプロジェクトの保存(`localStorage`、読めなければ無視)。
- 件数の判定は `ProjectSummaryRules.kt`(純粋)。読み込みは `screens/ProjectSummary.kt`。
- サーバーの API は変えない。既存の `/api/projects/{id}/...` と、`GET /api/runs?projectId=`・`GET /api/admin/runs?projectId=`(既存の projectId フィルタ)を使う。未分類は、別名ルート(ULID 専用)ではなく旧ルートの `projectId=unassigned` を使う。

## 10. 判断した点と未決事項

判断した点(この改訂で決めたこと):

1. 「担当」は全体と配下の両方に置く(同じ `NavDestination.Assignees`。配下は `projectId` で絞る)。全体のトークン管理を残すため。
2. `#/budget` は全体のまま(旧版の意味を変えない)。プロジェクトの予算は `#/projects/<id>/budget` の独立したページにした(旧版のダッシュボードへの置き換えは、ユーザーの決定「予算もプロジェクトのページ」により取り下げ)。
3. 詳細 URL(Run・開発目標・order)は、プロジェクトを持つ形(`#/projects/<id>/…`)と、持たない旧い形の両方を解釈する。画面から開くときは、今のプロジェクトを引き継ぐ。旧い詳細は URL を変えない。
4. 旧い一覧の置き換えは、最後に選んだプロジェクト → 1 つだけのプロジェクト → プロジェクト一覧 の順。最後に選んだプロジェクトは端末ごとの記憶(サーバーには持たない)。
5. ホームの件数は、プロジェクトごとの要約を足したもの(新しい API は足さない)。全体の予算(運用者)はホームに出さず、全体の「予算・FX」にだけ置く。
6. タスクの投入で「シミュレーション」は、プロジェクトに属さないまま残す(旧 UI の既存の動作を変えない)。自己開発はプロジェクトに入る。
7. `project_members` の画面(メンバーの登録)は、ダッシュボードの運用者欄に残す(担当のページは一覧)。
8. 未分類は疑似プロジェクト(固定 id `unassigned`)として、プロジェクトの一覧・ホーム・ヘッダーの切替に出す。作成はしない。API は既存の `projectId=unassigned`(サーバーは変えない)。

未決事項(後続):

- 一覧 API(`GET /api/projects`)を、メンバーのプロジェクトだけに絞るか(§7)。サーバー変更が要るため、この改訂では行わない。
- 旧い詳細(`#/goals/<id>` など)を、開いたときに所属のプロジェクトの URL へ置き換えるか。今は URL を変えない。
- 「システム」画面(health・metrics のリンク)は、フッターで足りるか。
- 未分類の記憶の件数は、ホームとプロジェクト一覧の要約に入れていない(記憶の一覧は API が件数を返さないため。画面で開けば見える)。
