# ADR 0019: Goal の投入と中止を MCP からも行えるようにするか(提案。段階導入、既定 off)

- ステータス: 提案(Proposed)。ユーザーの承認待ち。承認まで実装は始めない
- 日付: 2026-10-20
- 関連: Issue #181(MCP サーバー)、#233(オートパイロット)、ADR 0011(Goal の入力は /ui の Goal フォームだけ、と決めた)、ADR 0012(Run の取消し。PR 5 が未実装)、ADR 0013(MCP サーバー。書き込みの保護)、ADR 0009(UNKNOWN の Run の解放)、ADR 0015(order。AI の主体は停止を要求し、人が承認する)、ADR 0016(記憶)

## 背景

### 1. きっかけ

オートパイロットの Run が詰まった。Goal を中止して再投入する必要があったが、AI(MCP クライアント)からは操作できず、運用者が `/ui` で操作するしかなかった。

### 2. 現状(コードと ADR で確認した事実)

- **Goal の投入と中止は、HTTP(`/ui` のフォームから呼ぶ経路を含む)だけ。** MCP には Goal のツールが無い。
  - `POST /api/selfdev/goals`(投入)、`POST /api/selfdev/goals/{id}/stop`(中止)。`kivra-app/src/main/kotlin/net/kigawa/kivra/routes/SelfDevGoalRoutes.kt`。
  - 認可は `requireOperator`(`OperatorAuth`)。エージェントの主体は 403。書き込みは `requireJsonSameOrigin`(CSRF)。無効時は 503。
  - 中止の理由は 1〜500 文字で、制御文字を含めない。ただし**保存もログへの出力もしない**(コード内のコメント。ADR 0011 U-18)。
- **Goal の入力の決定(ADR 0011 §1)。** 「### 1. 入力は、ダッシュボードの Goal フォームだけにする」の本文は次のとおり。
  > 入力は、既存の `/ui`(運用者のダッシュボード)に追加する **Goal フォーム** から行う。GitHub Issue のラベル、本文、コメントを計画の入力にしない。外部から書き込める経路を作らないためである。
  - この決定は「外部から書き込める経路を作らない」が理由で、MCP を明示的に除外する文は無い。ただし、MCP を入力経路として許す場合は、この決定の改訂が要る。
- **MCP の書き込みは 2 つだけ。**
  - `kivra_selfdev_start`(Run の投入。スコープ `kivra.selfdev.start`。`kivra-app/src/main/kotlin/net/kigawa/kivra/mcp/McpWriteTools.kt`)。既定 off(`KIVRA_MCP_WRITE_ENABLED`)。キルスイッチ(`KIVRA_SELFDEV_ENABLED`)が off のときは一覧に出ない。
  - `kivra_order_request_stop`(order の停止の**要求**だけ。スコープ `kivra.selfdev.stop`。`mcp/McpOrderTools.kt`)。承認、却下、作成、開始、一時停止、再開は MCP に無い(ADR 0015 U-9)。
- **MCP の読み取りは Run と order だけ。** `kivra_run_list`、`kivra_run_get`、`kivra_order_list`、`kivra_order_get`。Goal の読み取りは無い。
- **MCP の認可は、既に運用者の role を要求している。** `mcp/McpJwtVerifier.kt:80` は `OidcJwtConfig` の必須 role(`KIVRA_OIDC_REQUIRED_ROLE`。ADR 0011 では `admin-panel:admin`)を検査する。そのため、MCP を呼べるのは、役割の上では運用者だけである。エージェントの token は audience の検査で 401 になり、`KIVRA_AGENT_REQUIRED_ROLE` で 403 にもできる(ADR 0013 §4)。
- **MCP のレート制限は、書き込みを 1 本にまとめている。** `McpWiring.kt` で、`McpDispatcher` が書き込みツール全体に 1 つの `McpRateLimiter(writeRatePerMinute)` を使う。既定は 1 分 5 件(`KIVRA_MCP_WRITE_RATE_PER_MINUTE`)。新しい書き込みツールを足すと、この上限を共有する。
- **Goal の REST にはレート制限が無い**(`SelfDevGoalRoutes.kt` に該当の検査が無いことを確認)。
- **Goal の REST は AI の主体を区別していない。** `SelfDevGoalRoutes.kt` には `aiPrincipal` の検査が無い。order の REST(`SelfDevOrderRoutes.kt`)は、AI の主体が承認、取消し、作成などを拒否する検査を持つ(ADR 0015 §4.6、U-29)。したがって、AI の主体に運用者の role を与えると、**現状でも AI が Goal を REST で投入、中止できる**(コードの読みによる。実際の dev での確認は未確認)。
- **Goal の中止は、投入済みの Run を止めない。** `db/SelfDevOperatorRepository.kt` の `stopGoal` は、Goal を `STOPPED` にし、未投入のタスク(`PENDING_APPROVAL`、`QUEUED`)を `SKIPPED` にする。投入済みの Run と作成済みの PR には触らない。`docs/runbook-selfdev.md` §7.4 にも同じ記述がある。
- **Run を止める HTTP の経路が無い。** `CodingRuntimeService.cancel`(`coding/CodingRuntimeService.kt:224`)の呼び出し元は、kivra-app の本体のコード(`routes/` を含む)には見つからなかった。取消しの HTTP(`POST /api/admin/runs/{id}/cancel`)は ADR 0012 の PR 5 で、未実装。
  - 既存の解放は、`UNKNOWN` の Run だけを `CANCELLED` にする(`POST /api/admin/selfdev/runs/{id}/release`。ADR 0009)。
  - キルスイッチ(`KIVRA_SELFDEV_ENABLED=false`)は、進行と新規の投入を止めるが、実行中の Run の取消しではない(`docs/runbook-selfdev.md` §4.4)。
- **同時 Run の上限。** 既定 1(`KIVRA_SELFDEV_MAX_ACTIVE_RUNS`)。上限に達している間、オートパイロットは新しいタスクを投入せず待つ(ADR 0011 の表。「状態は変えず、待つ」)。

### 3. 含意

- Goal を中止しても、詰まった Run は止まらない。Run は同時 Run 上限を占有し続けるため、再投入した Goal のタスクも待つことになる。**Goal の MCP ツールを足すだけでは、きっかけの問題は解決しない。** 詰まった Run を止める経路(ADR 0012 PR 5 の取消し)が、中止の実効性の前提になる。
- 詰まった Run の原因は、このコードの読みからは**確認できなかった**(未確認)。どの状態(`DEVELOPING`、`UNKNOWN`、`PULL_REQUEST` など)で詰まったかを確かめてから、必要な経路を決めること。`UNKNOWN` なら既存の解放(ADR 0009)で足りる可能性がある。

## 決定(推奨)

### 1. 追加するツール

| ツール | 種別 | スコープ | 入力 | 出力 |
|---|---|---|---|---|
| `kivra_goal_list` | 読み取り | `kivra.runs.read` | なし(最大 50 件、古い順。order の一覧と同じ) | 各 Goal の `goal_id`、`state`、`state_reason`、`version`、時刻。**題名と本文は返さない** |
| `kivra_goal_get` | 読み取り | `kivra.runs.read` | `goal_id`(ULID 形) | 上の項目と Plan のタスクの状態(題名と本文は返さない)。無い Goal は `NOT_FOUND` |
| `kivra_goal_stop` | 書き込み | `kivra.selfdev.stop` | `goal_id`、`version`、`reason`(固定のコードの列挙。推奨。U-4) | `state`、`reason_code` |
| `kivra_goal_submit` | 書き込み | `kivra.selfdev.start`(**段階 2 以降**) | `title`、`body`(任意)、`idempotency_key` | `goal_id`、`state`、`replayed` |

- 読み取りの出力は、order の読み取りと同じく、**題名と本文を返さない**。REST の詳細は本文を投入者と運用者だけに返すが、MCP では返さない(order の `McpOrderTools.kt` と同じ規則)。
- `kivra_goal_get` が返す `version` は、`kivra_goal_stop` の `version` になる(order と同じ楽観的ロック)。

### 2. 認可の方針(中核の決定)

MCP の呼び出し主体は、token の `iss` と `sub` から作る owner key である。主体は次の 2 種類に分ける。

- **運用者本人**(owner key が `KIVRA_SELFDEV_ROOT_APPROVER` と同じ、または運用者の role を持つ通常の主体)。
- **AI の主体**(owner key が `KIVRA_SELFDEV_AI_PRINCIPAL` と同じ。ADR 0015 §4.6)。

推奨は次のとおり。

1. **運用者本人の MCP 呼び出しは、`/ui` と同じ権限、同じ入力検証、同じ上限、同じ監査を通す。** 具体的には、`SelfDevGoalRepository.submit` と `SelfDevOperatorRepository.stopGoal` を呼ぶ薄いアダプターにする(order の `McpOrderTools` と同じ形)。新しい業務ロジックを MCP の側に作らない。
   - 認可の検査: 既存の `requireOperator` と同じ(role の検査は MCP のトークン検証で済んでいる。加えて、AI の主体ではないこと)。
   - 入力検証: `validatedText`(題名 200 字、冪等キー 128 字、制御文字なし)、`validatedBody`(8000 字、改行とタブだけ許す)。REST と同じ関数を使う。
   - 上限: 自己開発の自動投入を止める既存の検査(キルスイッチ、オートパイロットの有効)。`requireEnabled` と同じ 503 相当の固定コード。
   - 監査: 構造化ログ `net.kigawa.kivra.selfdev.autopilot.operator` に、`via=mcp` を付けて同じ形の行を出す(操作名、Goal ID、固定の理由コード、主体のダイジェスト)。本文、題名、理由の文は出さない。
2. **AI の主体は、段階 1 は読み取りのみ。段階 3 で、Goal の中止の「要求」だけ行える。** 要求は人が `/ui` で承認して初めて `STOPPED` になる(ADR 0015 の order の流れと同じ)。AI の主体は、承認も、投入も、一時停止も、再開もできない。
3. **AI の主体による Goal の投入は、この ADR では決めない**(U-3)。推奨は「段階 3 では行わない」。投入は、AI が直接 Plan の入力を作る経路になるため、人の承認を挟む仕組みが要る。

**この推奨の評価(実装の事実に基づく)**

- 運用者本人の投入と中止を MCP に出すこと: **小さい。** 既存の `submit` と `stopGoal` を呼ぶアダプターと、読み取りの 2 ツールで足りる。`McpOrderTools.kt` が同じ形の先例。新しいテーブルも migration も要らない。
- 追加で必要な仕組み:
  - Goal の版(`version`)を MCP から読めること(既存の Goal の行に版がある。読み取りツールで返すだけ)。
  - 中止の理由のコード化(U-4)。REST は自由文を受けるが、MCP は固定コードだけにするのが安全。
  - Goal 用のフラグ(U-5)。
- AI の主体による**中止の要求**(段階 3): **中程度。** order の停止要求(`selfdev_orders` と停止要求の表、`SelfDevOrderRoutes.kt` の `stop-requests` と `approve`)が先例になる。Goal には要求の表が無いので、(a)migration で要求の表を作る、(b)`/ui` に承認の画面を足す、(c)期限と「木に未決の要求は 1 件」に相当する一意制約を決める、が要る。
- AI の主体による**投入の要求**: **大きい。** Goal の投入を「保留」の状態にする新しい状態機械が要り、承認の画面と、保留中の Goal の本文の扱いを決める必要がある。推奨は行わないこと。
- 既存の REST の欠陥(AI の主体を区別していない)は、この ADR とは別に直すべき。推奨は、`SelfDevGoalRoutes.kt` の投入と中止の経路で、`aiPrincipal` の主体を拒否する検査を、MCP の追加より**先に**入れること(段階 0 の前提。U-6)。

### 3. Goal の本文は信頼できない入力として扱う

- AI が書いた題名と本文は、Planner の入力になる。Planner は `<goal-data>` の要素で囲み、nonce を付けて、データとして渡す(`selfdev/PlannerPrompt.kt`)。ただし、コードのコメントが示すとおり、この防御は**ソフトな防御**であり、値はエスケープされない。硬い防御は、厳格な出力のスキーマと `PlanValidator` である。
- Planner の出力(Plan のタスクの本文)は、Developer の入力(`taskBody`、`UNTRUSTED`)になる。つまり、**Goal の本文 → Plan のタスクの本文 → Run の本文**という経路で、指示が持続する可能性がある。
- 対策は次のとおり。
  - MCP でも、`validatedText` と `validatedBody` を REST と同じに適用する(制御文字の拒否、長さ上限)。
  - 本文は、読み取りの出力に返さない(AI が自分の書いた本文を読み直して、その内容に従うことを防ぐ)。
  - 段階 1 と段階 2 の Plan は、人が承認する(ADR 0011 の `PENDING_APPROVAL`)。段階 2 の自動の投入の条件は、ADR 0011 の段階 2 の規則に従う。
  - MCP の出力に、Planner の出力や Run の本文を含めない(既存の `McpRunTools` の規則を維持する)。

### 4. 既定 off(フラグ)

- Goal のツールは、書き込みの MCP(`KIVRA_MCP_WRITE_ENABLED`)とは**別のフラグ**で既定 off にする(U-5)。推奨は次のとおり。
  - `KIVRA_MCP_GOAL_READ_ENABLED`(読み取り。既定 off。MCP が有効で、オートパイロットが有効なときだけ登録する)。
  - `KIVRA_MCP_GOAL_WRITE_ENABLED`(中止と投入。既定 off。`KIVRA_MCP_WRITE_ENABLED=true` と、オートパイロットの有効化を前提にする。どちらか欠けたら起動を止める)。
- 段階ごとに、フラグを分ける。投入のフラグは段階 2 で別に入れる(U-5)。
- dev での有効化は、共有基盤の変更として**ユーザーの承認を経る**。承認前に env を dev に入れない。

### 5. 監査、レート制限、冪等、版、エラー

- **監査**: 構造化ログのみ(Goal の監査の表は U-18 のとおり未決)。出すのは、経路(`via=mcp` か `via=ui`)、操作名、Goal ID、固定の理由コード、主体のダイジェスト(SHA-256 の先頭 12 文字)。本文、題名、理由の文、生の主体 ID は出さない。
- **レート制限**: 書き込みは既存の書き込み用の上限(1 分 5 件、主体ごと)に乗る。Goal の投入は、これとは別に**1 日の上限**を持つ(U-7。暫定 10 件)。REST の投入は現状レート制限が無いので、MCP と揃えるかを決める。
- **冪等**: 投入は `(主体, idempotency_key)` で同一性を判定する(既存の `SelfDevGoalRepository.submit` の規則)。同じ内容の再送は、既存の Goal を返す(`replayed: true`)。内容が違えば `IDEMPOTENCY_CONFLICT` で、既存の Goal の ID は返さない。
- **版(stale version)**: 中止は `version` を必須とする。版が違えば `STALE_VERSION`(HTTP の 409 に当たる)。本文を返す必要はない。
- **エラーの文言**: 固定の文と固定のコードだけを返す。例: `ORDER_NOT_FOUND` の形にならい `GOAL_NOT_FOUND`、`AUTOPILOT_DISABLED`、`STALE_VERSION`、`IDEMPOTENCY_CONFLICT`、`INVALID_INPUT`。内部の例外の文は返さない。

### 6. Goal の中止の意味(タスクの途中の Run に何が起きるか)

- 中止は、Goal の状態を `STOPPED` にし、未投入のタスクを `SKIPPED` にする。**投入済みの Run は止まらず、PR も閉じない。**(`SelfDevOperatorRepository.stopGoal`)
- `SUBMITTING` のタスクは、中止の後も残る(ADR 0011 の「残る点」。運用者が判断する)。
- 実行中の Run を止めるには、ADR 0012 PR 5 の取消し(未実装)か、`UNKNOWN` の解放(ADR 0009)が要る。
- したがって、この ADR の中止ツールは、**実行中の Run に対しては「計画の打ち切り」しかできない**。詰まった Run そのものを止める効果は無い。この点を、利用者(運用者と AI)に結果の文で明記する(例: `run_still_active: true` のような固定の項目を返すことを推奨。U-8)。

## 脅威の検討

| 脅威 | 経路 | 緩和 |
|---|---|---|
| AI の誤操作(誤った Goal の中止や投入) | AI が意図しない `goal_id` を指定する | 段階 1 は AI に書き込みを出さない。中止は版と理由コードを必須にする。投入は段階 2 以降で、運用者本人の主体だけ。冪等キーと 1 日の上限 |
| 注入で Goal が投入される | AI が外部の文面(Issue、PR のコメント)を読み、その指示で投入する | AI の主体による投入は段階 3 でも行わない(推奨)。運用者本人の投入は、運用者が AI の出力を確かめてから行う運用にする(手順書に書く) |
| 注入で Goal が中止される | AI が詰まった状況の文面を読み、中止を要求する | AI の主体の中止は**要求**だけで、人が承認する(段階 3)。承認の画面に、要求者の主体と理由コードを出す |
| 本文経由の持続的な注入 | Goal の本文 → Plan のタスク → Run の本文 | 本文を読み取りに返さない。`<goal-data>` で囲む(ソフト)とスキーマと `PlanValidator`(硬い)。Plan は人が承認する(段階 1、2) |
| 資格情報の漏洩 | 出力に token、`sub`、本文、ダイジェストの生の値が出る | 出力は固定の項目だけ。ログは `via`、操作名、ID、コード、ダイジェストのみ。テストで、出力とログに題名の文字列が出ないことを確かめる |
| なりすまし(AI が運用者の token を使う) | AI が運用者の token を使い、要求者と承認者が同じ主体になる | AI の主体は別のユーザーとして作り、owner key で区別する(ADR 0015 §4.6)。要求者と承認者の一致は、DB の制約とアプリの検査の両方で拒否する |
| 既存の REST の抜け(AI の主体が運用者の role を持つ) | `SelfDevGoalRoutes.kt` に AI の主体の検査が無い | 段階 0 で、Goal の REST に AI の主体の拒否を入れる(U-6) |
| 連打による費用 | 投入を繰り返し、Planner の呼び出しと予算を使う | 冪等キー、書き込みの上限(1 分 5 件)、1 日の上限(U-7)、予算の既存の判定(ADR 0004、0011) |

## 段階的な導入

| 段階 | 内容 | 進む条件(暫定の数値) |
|---|---|---|
| S0 | この ADR の承認。REST の Goal 経路に AI の主体の拒否を入れる(U-6)。Run の取消し(ADR 0012 PR 5)の優先度を決める | 承認。U-6 の修正が main にマージされ、dev で AI の主体の REST 投入が 403 になることを確かめる |
| S1 | 読み取り(`kivra_goal_list`、`kivra_goal_get`、本文なし)。運用者本人の中止(`kivra_goal_stop`、固定理由コード、版必須)。既定 off。AI の主体は読み取りのみ | dev で 2 週間、誤った中止が 0 件。中止の結果の文(`run_still_active`)を運用者が確認している。Run の取消し(ADR 0012 PR 5)が入っているか、詰まりの原因が `UNKNOWN` で解放できると確かめている |
| S2 | 運用者本人の投入(`kivra_goal_submit`、冪等キー必須、1 日の上限)。既定 off | S1 で、投入の経路の監査ログに欠けが無い。冪等の再送テストが通る。dev で 5 件の投入を運用者が確認し、誤りが 0 件 |
| S3 | AI の主体による**中止の要求**(保留の表、`/ui` の承認の画面、期限、木の未決の要求は 1 件)。AI の主体による投入は行わない(U-3) | S2 の期間中に、要求と承認の流れを dev で 3 回通す。要求者と承認者が同じ主体の要求が DB とアプリの両方で拒否される |

## 未決の点

- **U-1 投入を MCP で許すか。** 推奨: S2 で、運用者本人の主体だけに許す。許さない場合は、中止と読み取りだけで止める(S1 まで)。
- **U-2 AI の主体に許す範囲。** 推奨: S1 は読み取りだけ。S3 は中止の要求だけ。投入は行わない。
- **U-3 AI の主体による投入の要求を、将来の段階で持つか。** 推奨: 持たない(この ADR の範囲外)。持つ場合は、保留の状態と承認の画面を別の ADR で決める。
- **U-4 中止の理由。** 推奨: MCP は固定の理由コードの列挙だけを受ける(order の `StopRequestReason` と揃える。例: `OPERATOR_REQUEST`、`RUNAWAY_SUSPECTED`、`BUDGET_CONCERN`、`SCOPE_CONCERN`)。REST の自由文(1〜500 字)は変えない。ただし、REST も理由を保存しないので、理由は監査の行が無い間は意味が小さい(U-18 と合わせて決める)。
- **U-5 フラグの粒度。** 推奨: 読み取りと書き込み(中止、投入)を分ける。投入のフラグは S2 で別に入れる。
- **U-6 REST の AI の主体の拒否を、この ADR の前提にするか。** 推奨: はい。MCP を追加するより先に、REST の Goal 経路で AI の主体を拒否する。
- **U-7 レート制限と 1 日の上限。** 推奨: 書き込みは既存の 1 分 5 件を共有し、投入は 1 日 10 件(暫定)。REST にもレート制限を入れるかは別に決める。
- **U-8 Run の状態を結果に含めるか。** 推奨: 中止の結果に `run_still_active`(その Goal の投入済みの Run が終端でないか)を入れる。実行中の Run を止めないことを、利用者に明示するため。
- **U-9 Run の取消しの優先度。** 推奨: ADR 0012 PR 5(取消し)を、Goal の中止の MCP より先に進めるかを決める。取消しが無いと、中止の効果は計画の打ち切りだけになる。
- **U-10 AI の主体の作り方。** ADR 0015 §4.6 の方針(専用の Keycloak の service account を作らない)を、Goal にもそのまま使うかを確かめる。
- **U-11 dev での有効化の時期。** 共有基盤の変更のため、S1 の有効化の前にユーザーの承認を得る(この ADR の決定 4)。

## 検討した代替案

- **A. MCP に Goal のツールを出さない(現状維持)。** 利点: 変更が無い。外部の経路を増やさない。欠点: 詰まった Goal を AI が扱えない。ADR 0011 の決定をそのまま守れる。**却下しない。** S0 と S1 の間で、運用の負担と比べて決める案として残す。
- **B. 運用者本人の MCP の投入と中止を、段階無しに一度に出す。** 却下。投入と中止の両方が、既定 off の 1 フラグで一度に出ると、誤操作の範囲が広い。段階に分ける。
- **C. AI の主体に投入と中止を直接許す(承認なし)。** 却下。ADR 0015 の原則(要求と承認の分離、AI は単独で止められない)に反する。
- **D. AI の主体の要求を、order の停止要求の表と共用する。** 検討。Goal と order は別の概念(ADR 0015 §1)のため、表は分ける。ただし、要求の流れ(保留、承認、期限、取消し)は order の実装を参考にする。
- **E. 中止を、Run の取消しと一体で行う(Goal の中止で実行中の Run も止める)。** 保留。Run の取消しが実装されてから決める(U-9)。現状の `stopGoal` は Run を止めない設計で、ADR 0011 §5.4 がそれを定めている。

## 結果(この ADR を承認した場合)

- 運用者は、MCP から Goal の状態を読み、中止できるようになる(S1)。投入は S2 の後。
- AI の主体は、段階 1 と 2 では読むだけになる。段階 3 で、中止の要求ができる。
- REST の AI の主体の検査(U-6)は、MCP に依らず必要な修正として残る。
- Goal の中止の効果は、計画の打ち切りだけであることを、利用者の結果の文で示す。実行中の Run は、取消しの実装(ADR 0012 PR 5)まで止まらない。

## 確認できなかったこと

- 詰まった Run の具体的な状態(`DEVELOPING`、`UNKNOWN`、`PULL_REQUEST` など)と原因。このコードの読みからは確認できない。
- dev の実環境で、AI の主体が運用者の role を持ち、REST で Goal を投入できるかどうか(コードの読みによる推定)。
- `KIVRA_OIDC_REQUIRED_ROLE` が dev と本番でどの値か(MCP の role 検査の実効値)。
- `CodingRuntimeService.cancel` が、テストや他のモジュールから呼ばれているかどうか(本体のコードの検索では、HTTP の経路からの呼び出しは見つからなかった)。
- Dispatcher が、上限に達した間の Goal のタスクをどう扱うか(ADR 0011 の表の記述は読んだが、実装の全経路は読んでいない)。
- MCP クライアント(Claude Code、claude.ai の connector など)が、Goal の書き込みの結果の構造(`structuredContent`)をどう扱うか。

## 見直しの条件

- ADR 0012 PR 5(Run の取消し)が実装されたとき。Goal の中止の効果の説明(U-8、U-9)を見直す。
- REST の Goal 経路に AI の主体の検査が入ったとき(U-6)。AI の主体の段階(S1、S3)を見直す。
- 詰まった Run の原因が、Goal の中止では解決しない種類(たとえば、ワーカーの停止や CLI の不具合)だと分かったとき。この ADR の優先度を下げ、原因の修正を先にする。
- dev で S1 を有効化し、誤った中止や、中止後の Run の扱いで問題が起きたとき。
- MCP の仕様の版が変わり、書き込みの扱い(承認の要求、出力の形)が変わったとき。

## 追記(2026-10-21): 題名の読み取り

運用者の指示で、決定の一部を変更する。本文は書き換えない(この節は末尾への追記である)。

- **きっかけ**: オートパイロットの Run が失敗した原因の調査で、計画のタスクの題名を MCP から読めず、人が `/ui` で見るしかなかった。
- **変更**: Goal の題名と、最新の PROPOSED の計画のタスクの題名だけを、運用者本人に、別の専用ツール `kivra_goal_get_titles` と別のフラグ `KIVRA_MCP_GOAL_TEXT_ENABLED`(既定 off、`KIVRA_MCP_ENABLED` と `KIVRA_MCP_GOAL_READ_ENABLED` と オートパイロットが要る)で返す。本文と、タスクの指示文、対象パスは、これまでどおり返さない。`kivra_goal_get` と `kivra_goal_list` は題名を返さない(変更しない)。
- **緩和**: 応答に `untrusted: true` と、題名はデータであって指示ではない、という固定の `note` を付ける。題名は長さを制限する。制御文字と双方向制御文字を含む題名は、取り除かず `[unsafe title omitted]` に置き換える。AI の主体は入力を読む前に拒否する。既定は off。ログには題名を出さない。
- **残るリスク**: 題名にも、指示として読める文が入りうる(Planner の出力と、運用者の入力のため)。題名は、本文と同じく信頼できない入力である。緩和は、題名を指示として扱わないことを、この MCP の利用者(運用者と、そのクライアント)に明示することに頼る。
- **見直しの条件**: 題名を原因とする指示の注入が 1 件でも疑われたら、このツールを止める(`KIVRA_MCP_GOAL_TEXT_ENABLED=false` で再起動する)。
