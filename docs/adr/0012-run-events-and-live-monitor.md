# ADR 0012: Run の実行イベントを記録し、運用者が読み取り専用で追える Run ライブモニターを作る(停止は既存の取消し要求を使う)

- ステータス: 提案(Proposed)。PR 1(§8)の実装済み。残りの PR は承認の後に進める
- 日付: 2026-10-18
- 関連: Issue #223(Self-dev Observability: Run Journal)、#224(Run ライブモニター UI)、#220(ClaudeCodeCliRuntime)、#76 / #79 / #85(Workspace UI と BFF)、#225 / #226(Self-healing)、#231(残件。エラーコード)、#157、#174、#181。ADR 0001(Run の正本)、ADR 0009(運用者の操作)、ADR 0010(CLI 経路の実行基盤)、ADR 0011(監査とログの衛生)。設計 `docs/design/15-coding-attempts.md`(`coding_attempts` / `coding_events`)

## 背景

- ADR 0010 の CLI 経路では、Run の進行は attempt の状態(QUEUED / CLAIMED / RUNNING / 終端)でしか見えない。CLI の中身(どのツールを何回呼んだか、何ターンか、費用がいくらか)は、終了後の結果でしか分からない。運用者は、長い Run が進んでいるのか止まっているのかを判断できない。
- 既存の観測の手段は次のとおり。
  - `GET /api/admin/runs/{id}` は、Run の監査イベント(`audit_events` の遷移)を返す(`RunViewRoutes`)。
  - `coding_events`(V14)は、attempt の状態遷移を 1 件ずつ書く。
  - heartbeat の `progress` 引数(`ProgressPhase`: STARTED / EDITING / TESTING / ROUND_COMPLETED / FINALIZING)は、PROGRESS を書く。ただし agent は heartbeat で `progress = null` を送るのが通常で、`FINALIZING` を送るのは `RealAttemptExecutor.submit` の 1 箇所だけ。PROGRESS は attempt あたり 100 件までに制限されている(`CodingAttemptRepository.MAX_PROGRESS_EVENTS`)。
- **CLI の標準出力は、実行中には agent で読まれていない。** `RealAttemptExecutor.attemptOnce` は `DockerRunner.run` の戻り値(コンテナ終了後の stdout 全量の文字列、既定の上限 1 MiB)を受け取り、その後で `StreamJsonParser` に通す。ライブで記録するには、`DockerRunner` を行単位の逐次読みに変える必要がある(本 ADR の PR 2 の前提)。
- 現在の `StreamJsonParser` が読むのは `result` と `system`(subtype `api_retry`)だけである。`assistant` の `tool_use`、`user` の `tool_result`、`system` の init などの形式は、この repo では読んでいない。各イベントの正確な形式は、実 CLI の出力で確認するまで**未確認**である(CLI の版を固定した fixture が要る)。
- 既存の UI(`resources/ui/app.js` の `renderDetail`)は、Run の詳細に監査イベントの一覧(「状態の経過」)を表示する。SSE は、この repo のどこにも無い。Ktor は 3.1.3 だが、`gradle/libs.versions.toml` に `ktor-server-sse` は無い(追加の要否と、同じバージョンで使えるかは**未確認**)。
- 停止の既存の仕組み: `CodingRuntimeService.cancel(runId)` は `DevelopmentRunRepository.requestCancel`(`cancel_requested` を立てる)を呼ぶ。QUEUED の attempt は即座に CANCELLED になる。CLAIMED / RUNNING の attempt は、agent が heartbeat の応答(`status.cancelRequested`)で知る。`AgentLoop.heartbeats` が `CancelRequestedException` を投げ、`RealAttemptExecutor` の停止判定(`!job.isActive || Files.exists(stopFile)`)でコンテナが止まる。`CodingRuntimeService.cancel` を呼ぶ HTTP 経路は、この調査では見つからなかった(**未確認**)。
- Issue #223 は、CLI の stream-json をブラウザへ中継せず、Run に紐づく型付きイベントを永続化することを求める。Issue #224 は、その読み取り専用の UI を求める。本 ADR は、何を記録し、どう送り、どう保存し、どう配信し、どう停止するかを決める。
- ログと保存の衛生は既存の方針に従う(ADR 0010、`docs/design/15-coding-attempts.md` §4 と §6.9、ADR 0011 §7.2)。プロンプト、モデルの出力、標準出力・標準エラー、秘密は、DB にもログにも入れない。agent の `AttemptDebugKeep`(`KIVRA_AGENT_DEBUG_KEEP=1`、失敗時のみ)は stdout 全量を `stream.jsonl` に書くが、**本 ADR の監視経路はこれを再利用しない。**

## 決定

### 1. 何を記録するか

記録するのは、固定のコードと数値だけである。自由記述の文字列は、どのフィールドにも入れない。

| 種別(kind) | 出所 | 記録する値 |
|---|---|---|
| ATTEMPT_STARTED | agent が CLI を起動した時 | 時刻のみ |
| CLI_INIT | stream-json の init(形式は未確認) | モデル id(許可パターンに合うものだけ。U-5)、CLI の版(許可パターンのみ) |
| PHASE | heartbeat の progress(既存の `ProgressPhase` の 5 値) | phase のコード |
| TOOL_USE | `assistant` の `tool_use` ブロック(形式は未確認) | ツール名の列挙(Read / Edit / Write / Bash / Grep / Glob / Task / TodoWrite / OTHER)。**入力は読まない** |
| TOOL_RESULT | `user` の `tool_result` ブロック(形式は未確認) | `is_error` の真偽(件数として集計) |
| TURN | `assistant` メッセージ(形式は未確認) | ターン数の増分 |
| PERMISSION_DENIED | `result` の `permission_denials` | 件数。**実行中に得られるかは未確認**(現在の parser は終了時の `result` からしか読まない) |
| API_RETRY | `system`(subtype `api_retry`) | 既存の `ApiErrorKind`(5 種) |
| COST | `result` の `total_cost_usd`(終了時)、または assistant の usage(**未確認**) | USD の数値(小数 6 桁まで、0 から 100 まで) |
| STOP_REQUESTED | heartbeat の応答で `cancelRequested` を agent が知った時 | 時刻のみ |
| PROCESS_EXIT_CONFIRMED | `DockerRunner` がコンテナの終了を確認した時 | `DockerRunOutcome` の値(EXITED / CANCELLED / OUTPUT_LIMIT など) |
| WARNING | `StreamJsonParser` | 不正行・過大行の件数、出力上限への到達 |
| DROPPED / TRUNCATED | agent の送信側、サーバの上限 | 落とした件数、上限に達した印 |
| 既存の状態遷移 | `coding_events`(V14)に既に書かれるもの | 既存のコードのまま(RESULT_RECEIVED、FAILED、CANCELLED、EXPIRED、ORPHANED など) |

- 「ツールの入力・出力・パス・モデルの応答本文」は、どの kind にも入らない。
- ツール名は CLI が返す名前を許可リストと照合し、一致しなければ OTHER にする。MCP などの任意の名前は通さない。
- 数値は上限を持つ(件数は 0 から 10,000、コストは 0 から 100)。
- 費用の経過について: `total_cost_usd` は終了時にしか無い。実行中の費用は、assistant イベントに usage が含まれるかで決まり、これは未確認である。含まれない場合、実行中は kivra 側の予約額(`coding_attempt_usage` の `ESTIMATED_RESERVE` と同じ計算)を「推定」と明記して表示する(U-6)。

#### 1.1 安全な要約の作り方

- agent 側で、stream-json の 1 行ごとに純粋な関数(仮称 `MonitorEventExtractor`、kivra-agent の `claude` パッケージ)を通す。出力は §1 の kind と、列挙・数値・時刻だけの型である。Kotlin の型として、CLI 由来の自由な文字列を持たせない。
- 抽出器の単体テストでは、秘密の文字列、プロンプト、パスを含む fixture を入れ、出力の JSON にそれらが現れないことを確かめる(ネガティブテスト)。
- 送信の前に、agent は JSON のスキーマ(フィールド名、型、上限)を検査する。kivra 側も同じ規則で再検査する。agent を信用しない。未知のフィールドと文字列の値は 400 で拒否し、何も保存しない。
- 1 イベントは 256 バイト以下とする。

### 2. 経路: agent から kivra へ

#### 2.1 API

- `POST /api/agent/v1/attempts/{attemptId}/events`
- 認証は既存の `AgentIdentity`(JWT の `azp` とロール)。claim と heartbeat と同じ。
- 本文: `{ "generation": <long>, "events": [ { "seq": <int>, "kind": "...", ...固定フィールド } ] }`。1 リクエストは最大 50 件、本文は 8 KiB 以下。
- **順序番号**: `seq` は (attempt, generation) ごとに 1 から連番で振る。サーバは、受理済みの最後の seq の次から連続するものだけを受け付ける。
  - 受理済みの seq と同じ内容: 200 を返し、何もしない(冪等)。
  - 受理済みの seq と内容が違う: 409。
  - 欠番がある: 409 と `lastAcceptedSeq` を返す。agent はその続きから送り直す。
- **世代の fencing**: `generation` が現在の `lease_generation` と一致し、attempt が CLAIMED または RUNNING で、呼び出し元が claim した holder のときだけ受理する。古い世代は 409(`STALE_GENERATION`)。終端の attempt への送信は受理しない。既存の `CodingRefusal` を使う。
- **報告の順序**: agent は、結果(`result`)や失敗(`fail`)を送る前に、未送信の監視イベント(PROCESS_EXIT_CONFIRMED を含む)を送り切る。送り切れない時は、待ち時間を 3 秒までとし、残りを DROPPED として残すか、諦める。終端の後の送信は受理されないため、順序の規則が要る。
- **量の上限**: 1 attempt あたり 2,000 件まで(U-10)。上限に達したら、状態遷移の記録は続け、TRUNCATED の印を 1 件書く。
- **バックプレッシャー**: サーバは、同じ attempt からの送信が 1 秒あたり 5 リクエストを超えたら 429 と `Retry-After` を返す。agent は送信の待ち行列をメモリ上に最大 500 件まで持ち、超えた分は捨てて件数を DROPPED として次の送信に入れる。
- **監視は best effort**: 送信の失敗は attempt の結果を変えない。CLI の実行も止めない(U-11)。heartbeat と結果の経路は、監視の送信とは独立に動く。

#### 2.2 保存先: `coding_events` を使わず、新しい表 `coding_monitor_events`(V20)を作る

判断: 新しい表を V20 で作る(expand のみ)。`coding_events`(V14)は再利用しない。理由は次のとおり。

- `coding_events` の `seq` は、状態遷移の CAS と同じトランザクションで attempt ごとに振られる。高頻度の送信を同じ採番に載せると、遷移と書き込みが競合する。
- `coding_events` には世代(`lease_generation`)の列が無い。agent が世代ごとに振る seq を保持できない。
- `coding_events.payload_json` は VARCHAR(512) で、PROGRESS は 100 件の上限を持つ。監視の上限(2,000 件)と別の保持期間を持たせたい。
- V14 は設計 15 と既存のテストが前提にしている。変えない。
- 読み取り時に 2 つの表を時刻と (generation, seq) で並べ、1 本のタイムラインにする。

V20 の列(案):

| 列 | 型 | 意味 |
|---|---|---|
| `attempt_id` | CHAR(26) | `coding_attempts` への外部キー |
| `lease_generation` | BIGINT | 送った世代 |
| `seq` | INT | (attempt, generation) の中の連番 |
| `kind` | VARCHAR(32) | §1 の固定コード |
| `phase` / `tool_name` / `code` | VARCHAR(32) NULL | 列挙 |
| `count_value` | INT NULL | 件数 |
| `cost_usd` | DECIMAL(12,6) NULL | 費用 |
| `received_at` | DATETIME(6) | サーバの受信時刻(DB の時計) |

- 主キーは `(attempt_id, lease_generation, seq)`。これが冪等性の根拠になる。
- agent の時刻は保存しない。表示は受信時刻を使う(U-15)。
- 固定フィールド以外の JSON は持たない。自由な JSON の列は作らない。

### 3. 保存と保持期間

- 保持期間は既定 30 日(`KIVRA_CODING_MONITOR_RETENTION_DAYS`、1 から 365。`KIVRA_CODING_RESULT_RETENTION_DAYS` と同じ範囲と既定。U-2)。
- 周期ワーカー(`CodingReconcileWorker`)が、保持期間を過ぎた行を、attempt が非活性(QUEUED、CLAIMED、RUNNING でない)かつ Run が終端の attempt からだけ削除する。1 周期で最大 200 attempt まで。attempt 行と `coding_attempt_usage` は残す(設計 15 §6.5.3 と同じ方針)。
- 上限: 1 attempt あたり 2,000 件、1 件あたり 256 バイト以下。1 attempt の最大は約 512 KB。
- 活性の attempt の行は、保持期間の判定に関わらず削除しない。

### 4. 配信: UI への読み取り API

- 権限: 読み取りは GET だけである。状態を変えるのは §6 の停止だけ。
- **REST**
  - `GET /api/admin/runs/{runId}/execution`: スナップショット。Run の状態、attempt の一覧(attemptId、attemptNo、state、lease_generation、最終受信時刻)、集計(ツール呼び出しの回数と種類別の件数、is_error の件数、ターン数、費用。推定と確定を区別する)、停止の段階、`snapshotAt`(サーバの時刻)、`revision`(attempt の version と最後の受信 ID の組)、`freshness`(最終受信からの経過で `LIVE` か `STALE` か。閾値は lease TTL の 2 倍を既定とする)。
  - `GET /api/admin/runs/{runId}/execution/events?attemptId=&afterSeq=&limit=`: 差分。`limit` は最大 200。カーソルは `(lease_generation, seq)`。
- **SSE**: `GET /api/admin/runs/{runId}/execution/stream`
  - `id:` は `<attemptId>:<generation>:<seq>`。
  - `Last-Event-ID` を受けたら、その位置から DB を読み直して再送する。サーバのメモリには依存しない。再起動と複数の Pod でも同じ結果になる。
  - 保持期間で消えた範囲を要求されたら、`event: resync` を送り、クライアントに snapshot の再取得を指示する(gap の検出)。
  - コメント行のハートビートを 15 秒ごとに送る。
  - サーバは接続ごとに DB を 2 秒間隔で読む(通知の仕組みは使わない。複数 Pod でも動く)。同時接続は利用者あたり 5、全体で 50 まで(U-13)。
  - 依存: `ktor-server-sse` の追加が要る(**未確認**: Ktor 3.1.3 で使えるか)。使えない場合は、REST の 2 秒ポーリングで代替する(U-7)。
- **認可**
  - 閲覧できるのは、Run の作成者本人か、運用者(必須ロール `admin-panel:admin`。`SelfDevGoalRoutes` と同じ検査)だけ。
  - 権限の無い Run は 404 を返す(存在を漏らさない)。
  - 既存の `/api/admin/runs` と `/api/admin/runs/{id}` は、認証された principal があれば他人の Run も返す(`RunViewRoutes` の実装)。本 ADR の新しい経路は、より厳しい規則を使う。既存の挙動を変えるかは U-4 で決める。
  - 接続の時に検査し、再接続のたびにも検査する。接続中に権限が無くなった場合は、次の送信の前に切る。
  - ブラウザが SSE で資格情報をどう送るかは**未確認**である。`EventSource` はヘッダーを付けられないため、Cookie の認証が要るかもしれない。PR 3 の前に確認する。
- 応答に含めるのは、固定コード、列挙、数値、時刻だけである。Task の本文、パス、プロンプト、CLI の生の出力は返さない。

### 5. UI(#224)

- 既存の `/ui` の Run 詳細(`renderDetail`)に「実行の経過」の節を足す。新しいページは作らない。
- 表示する内容:
  - 実行の要約: attempt の状態、世代、鮮度(LIVE / STALE)、最終受信時刻(ブラウザのローカル時刻で表示)。
  - 段階: 実際の状態に対応する段階だけを表示する。#224 の「Admission → Planning → Coding → Testing → Draft PR」のうち、**Planning は CLI 経路に対応する状態が無い**ため表示しない(U-9)。Testing は PHASE の TESTING を受けた時だけ「確認された」と表示する。Draft PR は PULL_REQUEST の状態に対応させる。%と ETA は出さない。
  - ツール: 呼び出しの回数、ツール名ごとの件数(列挙だけ)、is_error の件数、ターン数。
  - 費用: 確定の値(終了時の `total_cost_usd`)と、推定の値(予約からの推定)を別のラベルで表示する。
  - 停止: §6 の段階を別々に表示する。停止要求は「停止済み」と表示しない。
  - 警告: API_RETRY、WARNING、DROPPED、TRUNCATED を上部に出す。
- 鮮度と誤認の防止(#224 のとおり):
  - SSE が切れたら「古い表示(最終受信 hh:mm:ss)」を出し、段階を巻き戻さない。古いイベントで状態を戻さない(`revision` で比べる)。
  - SSE の切断、ブラウザの非表示、監視の停止は、Run の状態ではない。「監視が切れている」と表示する。「Run が終わった」「障害が解消した」とは表示しない。SSE が生きていることを、障害の解消の証拠として扱わない。
  - 自動で最新へ追従するのは、画面の先頭に居る時だけ。古いログを読んでいる間は追従せず、「最新へ」を出す。
- スマホでは、タイムラインを 1 ペインの中心にする。
- 文言は `app.js` の既存の対応表(`reasonText` など)に足す。サーバの応答の形は変えない。
- **Self-healing(#225 / #226)の拡張点**: 表示対象の kind を予約する(`SELFHEAL_FINGERPRINT`、`SELFHEAL_DIAGNOSIS`、`SELFHEAL_ATTEMPT`、`SELFHEAL_CI`、`SELFHEAL_NEEDS_HUMAN`)。本 ADR では実装しない。値は固定コードと件数だけにする。表示は既存の Run イベントとして扱い、監視の停止や SSE の切断を解消の証拠にしない。

### 6. 停止(#224 の Stop)

- 既存の仕組みを使う。`CodingRuntimeService.cancel` → `DevelopmentRunRepository.requestCancel`(`cancel_requested` を立てる)。QUEUED の attempt は即座に CANCELLED になる。CLAIMED と RUNNING の attempt は、agent が heartbeat の応答で知り、コンテナを止め、`fail(CANCELLED)` を送る。Run は agent の進行に沿って CANCELLED になる。
- HTTP: `POST /api/admin/runs/{runId}/cancel`(新規)。
  - CSRF は `requireJsonSameOrigin` で防ぐ。`Idempotency-Key` を必須にする。
  - 理由の本文は監査の payload にだけ入れ、ログには出さない(ADR 0011 §7.1 と同じ)。
  - 誰が: 運用者のロールだけ(既定。U-3)。
  - いつ: 受理の時刻を監査に残す。取消しが可能な状態(`canTransitionTo(CANCELLED)`)でだけ受理する。
- 表示の段階: 停止要求 → 停止中(agent の確認待ち) → 停止確認(PROCESS_EXIT_CONFIRMED) → 取消し済み(Run が CANCELLED)。停止要求から一定時間(U-12、既定 5 分)経っても確認が無い場合は「確認待ちが長い」と表示する。状態は自動では変えない。
- PR 4 までは停止ボタンを出さない(#224 の受入条件: Read-only の段階では操作ボタンを有効にしない)。

### 7. 脅威分析

| 脅威 | 経路 | 対策 |
|---|---|---|
| イベント経由の情報漏えい | 送信側が文字列を入れる。CLI の出力をそのまま通す | agent は列挙と数値だけの型に写す。サーバは未知のフィールドと文字列の値を 400 で拒否する。抽出器のネガティブテスト。ログには ID と固定コードだけを出す。`AttemptDebugKeep` の `stream.jsonl` は再利用しない |
| 偽のイベント | 別の attempt、古い世代、侵害された agent | agent 認証、holder の検査、世代の一致、活性の状態の 4 つで受理する。UI は agent の報告を「観測」として表示し、検証済みの証拠(GitHub の状態など)と混同しない |
| 洪水 | 高頻度の送信、ループ | 1 リクエストの件数と大きさ、1 秒 5 リクエスト(429)、attempt あたり 2,000 件、agent 側の待ち行列 500 件と DROPPED、SSE の 2 秒間隔の読み、SSE の接続上限 |
| 古い世代 | 再起動の後も古い CLI が送り続ける | 世代が一致しなければ 409。古い世代のイベントは「前の世代」として別に表示し、現在の表示には混ぜない |
| SSE の認可の迂回 | 接続の後に権限を失う。他人の run_id を推測する。再接続で別の Run を指定する | 接続と再接続の両方で検査する。Run ID で権限を判定し、他人の Run は 404。`Last-Event-ID` から読む前に、attempt と Run の所属を検査する |
| 監視による障害の見逃しや誤認 | SSE の切断を「正常」と扱う | §5 の鮮度表示。SSE の状態は Run の状態に写さない |
| 書き込み経路の増加 | 読み取り UI から状態を変える | 読み取り API は GET だけ。変更は `POST /cancel` だけで、運用者に限る |
| DB の負荷 | 多数の SSE 接続、長い保持 | 接続の上限、2 秒間隔の読み、保持期間と件数の上限、主キーによる範囲読み |

### 8. 実装の分割と順序

前提:
- 最新の migration は V19(`V19__selfdev_approval.sql`)。本 ADR の表は V20 とする(確認済み)。
- #231 の 3 つのエラーコード(`EMPTY_PATCH`、`BUDGET_EXCEEDED`、`OVERLOADED`)は `CodingErrorCode` に存在する(確認済み)。agent がこれらを送る前に、kivra を先にデプロイする(ADR 0010 追補 6 と同じ順序)。本 ADR の監視も同じ順序に従う。
- #220、#181、#157、#174 の現在の状態は**未確認**。#224 の依存として並べられている。

PR の分割:
1. **PR 1: 記録の API とテーブル。** V20、リポジトリ、`POST /api/agent/v1/attempts/{id}/events`、検証と上限、保持の削除を `CodingReconcileWorker` に加える。単体テストと結合テスト、ネガティブテスト(秘密の文字列を含む入力が保存されないこと)。agent は変えない。
2. **PR 2: エージェント側の送信。** `DockerRunner` の逐次読み、`MonitorEventExtractor`、待ち行列、送信、DROPPED、報告の順序(§2.1)。前提は、CLI の実出力の fixture(CLI の版を固定して取ったもの)である。実 CLI で 1 Run の記録を確認する。PR 1 のデプロイの後に出す。
3. **PR 3: 読み取り API と SSE。** REST の snapshot と events、SSE(`Last-Event-ID` と resync)、認可、接続の上限、`ktor-server-sse` の追加(または REST ポーリングへの代替)。
4. **PR 4: UI。** `app.js` の「実行の経過」の節、表示の規則、鮮度、スマホ表示。Fake の fixture で開発できる。停止ボタンは出さない。
5. **PR 5: 停止。** `POST /api/admin/runs/{runId}/cancel`(運用者限定、`Idempotency-Key`、監査)と、停止の段階の表示、確認待ちの表示。

各 PR で、kivra 側を先にデプロイし、agent 側を後にする。

### 9. 未決の点

以下は、ユーザーの確認が要るものである。各項目の暫定の既定値を括弧で示す。異論があれば、実装の前に変更する。

- **U-1 保存先**: 新しい表 V20(既定)か、`coding_events` の拡張(世代の列を足す)か。既定の理由は §2.2。
- **U-2 保持期間と上限**: 30 日(既定)。1 attempt あたり 2,000 件(既定)。
- **U-3 停止の権限**: 運用者のみ(既定)か、Run の作成者も停止できるようにするか。
- **U-4 閲覧の権限**: 作成者と運用者(既定)。既存の `/api/admin/runs` には作成者の検査が無い。既存の挙動を変えるなら、別の PR で行う。
- **U-5 モデル id の表示**: 許可パターンに合う id だけを、運用者に表示する(既定)。表示しないという選択もある。
- **U-6 費用の推移**: assistant の usage が実行中に得られるかは未確認。得られない場合は、実行中の費用を予約からの推定だけで「推定」と表示する(既定)。fixture で確認してから決める。
- **U-7 SSE の採用**: SSE(既定。`ktor-server-sse` を追加する)か、REST の 2 秒ポーリングだけか。
- **U-8 API のパス**: 現行の `/api/admin/...`(既定)か、#85 の `/ui/v1/...` か。#85 の決定に合わせて後で移す。
- **U-9 段階の表示**: CLI 経路に Planning の状態が無い。Planning は表示しない(既定)。#224 の段階の定義を改める必要があるか、確認が要る。
- **U-10 送信の上限**: 1 attempt あたり 2,000 件、1 秒あたり 5 リクエスト、待ち行列 500 件(既定)。
- **U-11 監視の失敗で CLI を止めるか**: 止めない(既定)。
- **U-12 停止の確認待ちの閾値**: 5 分(既定)。heartbeat の既定の間隔は**未確認**。
- **U-13 SSE の接続上限**: 利用者あたり 5、全体で 50(既定)。
- **U-14 既存の PROGRESS(heartbeat の progress)の扱い**: PR 2 の後に、heartbeat による PROGRESS の書き込みを止め、新しい表へ一本化する(既定)。両方を残す案もある。
- **U-15 時刻**: サーバの受信時刻だけを保存する(既定)。agent の時刻は保存しない。
- **U-16 文言**: 固定の日本語の文言を app.js に置く(既定)。

## 検討した代替案

- **A. CLI の stream-json をブラウザへ中継する。** 生の出力が画面に出るため採らない。#223 の要求に反する。
- **B. 外部のログ基盤や監視基盤へ送る。** 運用の部品が増える。Run の正本は kivra の DB にある(ADR 0001)。採らない。
- **C. `coding_events` を拡張する。** §2.2 の理由で採らない(U-1 で再確認する)。
- **D. WebSocket を使う。** 読み取りだけなので双方向は要らない。SSE は HTTP のままで、切断の扱いが単純になる。
- **E. サーバのメモリ上の通知で SSE を流す。** 複数 Pod と再起動に弱い。DB を読み直す方式を採る(§4)。
- **F. Run の状態機械に監視の状態を足す。** ADR 0001 と設計 15 §1 に反する(Run の状態は増やさない)。監視の状態は表示と attempt からの射影だけにする。
- **G. 進捗の % と ETA を出す。** 根拠が無い。#224 の要求に反する。段階とツールの件数だけを出す。

## 結果

- 運用者は、長い Run の進行(ツールの呼び出し、ターン数、費用、停止の段階)を、ログを読まずに追える。
- 記録が増えるので、保持と上限の管理が要る(§3)。監視の送信が失敗しても Run の結果は変わらない(best effort)。
- agent の責務が増える。逐次読み、抽出、待ち行列があり、PR 2 の範囲が広い。
- SSE を採ると、ライブラリの追加と接続の管理が要る(U-7)。
- CLI の出力の形式が変わると、抽出が失敗する。未知の形式は kind に写さず WARNING の件数にする。CLI の版は agent で固定する(既存の CLI の版の検証に従う。詳細は**未確認**)。
- 実行中の費用は、CLI の出力次第で「推定」にとどまる(U-6)。

## 見直しの条件

- CLI の stream-json の形式が変わり、抽出の失敗(WARNING)が続くとき。
- 監視の書き込みが DB の負荷を増やし、Run の lease の更新や結果の保存に遅れを出したとき。
- 読み取りの権限の要件(U-4)が変わったとき。
- Issue #85 の BFF(`/ui/v1`)が決まり、API の置き場所を移すとき(U-8)。

## 実装状況

- **PR 1(記録の API と表、kivra 側)**: 実装済み(ブランチ `feat/monitor-events-api`、Refs #223 #224)。
  - V20 `coding_monitor_events`(expand のみ)、`CodingMonitorEventRepository`(追記、読み取り、保持の削除)。
  - `POST /api/agent/v1/attempts/{attemptId}/events`(`AgentIdentity`、既存の agent API と同じ認可と CSRF/Origin の扱い)。スキーマは `MonitorEventSchema` で厳格に検査する。
  - 保持期間の設定 `KIVRA_CODING_MONITOR_RETENTION_DAYS`(既定 30、1〜365)。`CodingReconcileWorker` の周期で掃除する。
  - 運用の手順と上限は `docs/runbook-selfdev.md` §6.16。
  - agent 側(抽出、送信、DROPPED の計上)は PR 2。読み取りの API は PR 3、画面は PR 4、停止は PR 5。
- **PR 1 で決まった未決の点**(暫定の既定値を採用し、実装で確定した):
  - **U-1(保存先)**: 新しい表 V20 を採用。`coding_events` は変えていない。
  - **U-2(保持と上限)**: 30 日と、1 attempt あたり 2,000 件を採用。上限の印は、件数の上限に達した時に seq 0 の `TRUNCATED` として 1 件書く(seq 0 は kivra 専用。agent の seq は 1 から)。`TRUNCATED` は agent が送れない(400)。保持の掃除は、attempt の最終更新が保持期間より古く、attempt が非活性で、Run が終端のものに限る。
  - **U-10(送信の上限)**: 1 リクエスト 50 件、本文 8 KiB、1 件 256 バイト、1 attempt あたり毎秒 5 リクエスト(429 と `Retry-After: 1`)を採用。毎秒の上限は**プロセスごと**(複数 Pod では合計が倍数になる)。待ち行列 500 件は agent 側(PR 2)。
  - **U-11(監視の失敗で CLI を止めるか)**: kivra 側は確定。監視の失敗は 503 だけを返し、attempt の結果と heartbeat は変えない(テストで確かめている)。CLI を止めるかどうかは agent 側(PR 2)で決める。
  - **U-15(時刻)**: サーバの受信時刻(`received_at`)だけを保存。agent の時刻は保存しない。
- **PR 1 で追加で決めたこと**(ADR 本文には無い):
  - `CLI_INIT` は項目を持たない。モデル名と版は、保存先の列が無いため今は保存しない(U-5 は未決のまま)。
  - 読み取りの位置は (世代, seq) の組で指定する(§4 の `afterSeq` の表記は、世代を含めた組として扱う)。
  - JSON の重複キーは、最後の値が使われる(この kotlinx のバージョンには拒否のスイッチが無い)。使われた値は全て検査するので、重複で自由な文字列は通らない。
  - 保持の掃除は、attempt の最終更新時刻で判定する(イベントの受信時刻ではない)。終了した attempt は、以後イベントを受けないため、これで足りる。
- **未決のまま残る点**: U-3(停止の権限)、U-4(閲覧の権限)、U-5(モデル名の表示)、U-6(実行中の費用)、U-7(SSE)、U-8(API の置き場所)、U-9(段階の表示)、U-12(停止の確認待ち)、U-13(SSE の接続上限)、U-14(heartbeat の PROGRESS の扱い)、U-16(文言)。
- 本 ADR は、PR #252(このファイルの元のブランチ)とは別に、この PR で複製している。#252 をマージするときは、この「実装状況」の節の内容を保つこと。
