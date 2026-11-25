# ADR 0025: project と operation の総称を unit とする

- ステータス: 採用
- 日付: 2026-11-19
- 関連: ADR 0022(プロジェクトのスコープ)、ADR 0001(Run の状態機械の正本。Operation Journal の定義を含む)

## 背景

UI の `kivra-compose-ui` には、project と operation の両方を指す総称の型が既にある(`Node.kt` の `NodeKind(PROJECT, OPERATION)`、`NodeRef`、`Node`)。
一方で文書やコードの説明では、この総称に決まった語がなく、「project か operation か」と毎回書き分けている。
また「Operation」は別の意味でも使われている(ADR 0001 などの Operation Journal、`external_operations`、`/api/runs/{id}/operations`)。
総称の語を決めて、どちらの意味の Operation かを区別できるようにする。

## 決定

1. project と operation の総称を **unit(ユニット)** と呼ぶ。
2. これは用語の決定であり、改名はしない。画面の表示(サイドバーの「Projects」「Operation」)、ルート(`#/projects/...`、`#/operations/...`)、API(`/api/projects`、`/api/operations`)、DB、既存の識別子(`NodeKind`、`NodeRef`、`Node`、`ApiOperation` など)は変えない。
3. 新しいコード・文書・UI の文言で総称が必要なときに unit を使う。既存の文言は、このような判断をせず必要になった時点で個別に扱う。

## 定義

- **unit**: UI の `NodeKind` で表される、画面上で親子に並ぶ対象の総称。次の 2 種類からなる。
  - **project**(`NodeKind.PROJECT`): プロジェクト。親を持たない。
  - **operation**(`NodeKind.OPERATION`): UI で「Operation」と呼ぶ対象。親は project または別の operation のどちらもありうる。
- unit は `NodeRef(id, kind)` で指す。親子関係は `Node.parent`(`NodeRef`)で持ち、親の種類(project か operation か)も含めて判定する。
- unit の ID は種類ごとに別の空間にある。同じ文字列の ID でも project と operation は別物として扱う(`NodeRef` の等価判定に従う)。

### 実態との対応(確認した範囲)

- project は `ApiProject`(`projectId`)、operation は `ApiOperation`(`id`)。
- operation の親は `ApiOperation` の中には持たれず、取得時の文脈(`parentId` クエリ、`Route.OperationDetail` の `parentId` と `parentKind`)で決まる。親が無い operation もある(未割当の operation は `projectId` が無い行として扱う)。したがって「operation は必ず project の下にある」とは書かない。
- バックエンドでは、UI の operation は SelfDev の **order**(`/api/selfdev/orders` 系、`parentOrderId` と `depth` を持つ木)に対応する、と読める。UI の語 operation と バックエンドの語 order は、同じ対象を指す。このため unit の定義はバックエンドの語(order)を含まない。
- 検証時点で、UI が呼ぶ `/api/operations`(`ApiClient.getOperations` / `getOperation`)に対応するルートは `origin/main` の `kivra-app` の routes に見当たらなかった。実際のエンドポイントは別途確認が必要。この ADR はその点を決めない。

## 含まない用語

- **Operation Journal**(`external_operations`、`/api/runs/{id}/operations`、状態 `PREPARED / IN_FLIGHT / UNKNOWN / CONFIRMED / RETRYABLE / FAILED / RECONCILIATION_REQUIRED`)の「operation」は、外部副作用の実行記録であり unit ではない。文書では「Operation Journal の operation」「Journal の操作」と書き、unit と呼ばない。
- **order**(バックエンドの SelfDev の依頼単位)は unit ではない。UI の operation に対応する実装上の語として扱い、unit の定義には使わない。
- **goal**(旧 `/api/projects/{id}/tasks` 系の目標)と **task**(Task、プロジェクト内の作業単位)は unit に含めない。UI の `Node` の種類ではない。
- **run**(DevelopmentRun)は unit に含めない。

## 使い方

- 「project と operation」をまとめて指すときは「unit」と書いてよい。最初に出すときは「unit(project と operation の総称)」と定義を添える。
- 画面の文言(「Projects」「Operation」)は今のまま。unit という語を UI の見出しに出さない。
- Operation Journal の operation は、必ず「Operation Journal」「Journal の operation」と書く。unit と書かない。

## 将来の改名

UI の表示、ルート、API、DB の名前を unit に揃えるかどうかは、別の判断として扱う。改名は、利用者への影響(既存のブックマークや API クライアント)と移行の手順を決めてから行う。本 ADR では行わない。
