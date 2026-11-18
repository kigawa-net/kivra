# ADR 0009: UNKNOWN の自己開発 Run は、運用者専用の経路だけで CANCELLED に解放する

> 注記(2026-11-09): 旧 vanilla UI(`/ui`、`kivra-app/src/main/resources/ui/`)は撤去済み。現在の画面は Compose UI(`/ui/compose`)。本文の旧 UI への記述は当時の記録として残す。

- ステータス: 採用(Accepted)
- 日付: 2026-10-16
- 関連: Issue #56(Self-Development Integration Runner)、ADR 0005(結果の分類と UNKNOWN)、ADR 0008(PR 作成までの自己開発ループ)、ADR 0003(UNKNOWN_COST の acknowledge)

## 背景

ADR 0008 の自己開発 Run は、外部結果が不明なとき `UNKNOWN` になる。`UNKNOWN` は有効な Run(terminal ではない)なので、同時 Run 上限(`KIVRA_SELFDEV_MAX_ACTIVE_RUNS`、既定 1)を占有し続ける。
運用者が解放する手段は無かった。そのため、1 件の UNKNOWN で新規の提出が 429 になり続けた。

状態機械(`kivra-core` の `DevelopmentRunState`)では、`UNKNOWN` の通常の遷移先は空で、証拠付きの遷移先は `PULL_REQUEST` と `FAILED` だけである。
`UNKNOWN -> CANCELLED` は、どちらにも無いので、汎用の `transition()` は拒否する。`StatesTest` がこの期待を固定している。

## 決定

1. **通常の遷移ルールは変えない。** `DevelopmentRunState.next`、`evidenceGated`、`transition()` は従来どおり、`UNKNOWN` からの遷移を拒否する。`kivra-core` は変更しない。
2. **解放は運用者専用の経路だけで行う。** `DevelopmentRunRepository.releaseUnknownSelfDev` が、`UNKNOWN -> CANCELLED` だけを行う。対象は自己開発の Run(`taskId` が `selfdev:` で始まるもの)に限る。
3. **1 トランザクションで書く。** 行をロックし、`state = UNKNOWN` を条件にして更新する。同じトランザクションで `RUN_RELEASED` の監査と、`RunStateChanged` の outbox を書く。どれかが失敗すれば、すべて戻る。
4. **理由は監査の payload にだけ入れる。** 運用者の理由(1〜500 文字)は、監査の payload にだけ入る。Run の `state_reason` は固定コード `OPERATOR_RELEASED` である。ログには出さない。
5. **認可は予算の acknowledge と同じ。** `TaskApiIdentity` の主体を運用者とする。未認証は 401、存在しない Run と自己開発でない Run は 404、`UNKNOWN` でない Run は 409(解放済みを含む)。
6. **解放すると上限の計数から外れる。** 計数は terminal 状態を除外しているので、`CANCELLED` になった Run は同時 Run 上限を占有しない。

## 結果

- PR_ONLY の Run の副作用は、GitHub の branch と PR だけである。人がマージする。kivra は PR を閉じず、マージもしない(ADR 0008)。解放しても PR は残る。その扱いは人が決める。
- 解放は、UNKNOWN の原因を直さない。同じ原因が続けば、次の Run も UNKNOWN になる。原因の再発防止は別の作業である。
- 解放の経路が増えるのは、この 1 つの状態遷移だけである。汎用の遷移ルールは、今後も証拠が無ければ UNKNOWN から出られない。
- 将来、証拠(reconciliation)に基づく自動の解放を入れるときは、この ADR を置き換える。

## 追補(2026-10-17): 束縛の無い PULL_REQUEST の Run の解放

対象: #56 のマージ検証 ⑩ の PR4b。

### 背景

束縛(binding)の記録(PR2)より前に作られた自己開発 Run は、`PULL_REQUEST` で止まり、binding を持たない。PR がマージされても、
`SelfDevRunner` は `BINDING_MISSING` で待つ。kivra は binding を GitHub の値から推測しないため、自動では完了しない。
この Run は同時 Run 上限(`KIVRA_SELFDEV_MAX_ACTIVE_RUNS`)を占有し続ける。UNKNOWN 専用の解放(本 ADR の決定 2)は `PULL_REQUEST` に使えない。

### 決定(追補)

1. **UNKNOWN の解放とは別の経路にする。** `DevelopmentRunRepository.releaseUnboundPullRequestSelfDev` が、
   `PULL_REQUEST -> CANCELLED` だけを行う。通常の遷移表(`DevelopmentRunState.next`)と `transition()`、`releaseUnknownSelfDev` は変えない。
   `kivra-core` は変更しない。
2. **対象は限定する。** 自己開発の Run(`taskId` が `selfdev:` で始まる)で、状態が `PULL_REQUEST`、かつ binding が無いもの
   (`toPullRequestBindingOrNull` が null。`loadPullRequestBinding` と同じ判定で、行ロックの下で読む)。
   binding がある `PULL_REQUEST` は対象外で、409 になる。マージされれば従来どおり自動で `COMPLETED` になる。
3. **エンドポイントは別のパスにする: `POST /api/admin/selfdev/runs/{id}/release-unbound-pr`。**
   既存の `POST .../release` は拡張しない。理由: (a) 既存のエンドポイントは「UNKNOWN だけを解放する」という契約として、
   ADR 0009、runbook §4.1、既存のテスト(`SelfDevAdminRoutesTest`、`RequestGuardsTest`)が固定している。状態で分岐させると、
   既存の 409 の意味が変わる。(b) 別パスにすると、運用者が「束縛の無い PR」を明示して選ぶことになり、
   UNKNOWN の経路で誤って PR の Run を解放する事故が起きない。
   認可、CSRF(`requireJsonSameOrigin`)、本文 `{"reason": 1〜500 文字}`(厳格。ほかのフィールドは 400)、未認証 401、
   該当なし 404(不存在、`api:` の Run を含む)、対象外の状態・binding がある 409 は、既存の `release` と同じ。
   リクエストの本文の解析は共通の関数にまとめた。
4. **遷移と書き込み。** 行をロック(`FOR UPDATE`)し、`state = PULL_REQUEST` と `version` を条件にして更新する。
   同じトランザクションで `RUN_RELEASED` の監査(actor は認証済みの運用者、payload に `from`/`to`/`reasonCode` と `reason`)と、
   `RunStateChanged` の outbox(理由コードのみ)を書く。どれかが失敗すれば、すべて戻る。
   Run の `state_reason` は固定コード `OPERATOR_RELEASED_UNBOUND_PR`(27 文字。`state_reason` は `varchar(64)`)。
   理由(自由記述)は監査の payload にだけ入る。ログと outbox には出さない。
5. **GitHub には何もしない。** kivra は PR を閉じず、マージもしない。`GitHubPort` に close / merge を足さない。
   解放後、PR は GitHub に残る。人がマージしても、Run は `CANCELLED` のままで、kivra はそれを追跡しない。
   PR の扱い(閉じる、そのまま残す)は人が決める。
6. **解放後は上限の計数に入らない。** `countActiveSelfDev` と `activeSelfDevRunIds` は terminal 状態を除外する。
   `CANCELLED` は terminal なので、解放後の Run は上限を占有しない。テストで確認する。

### 未対応(この追補で扱わないこと)

- **binding がある `PULL_REQUEST` の Run を人が止める経路は作らない。** 放置したい PR(マージされる見込みが無いもの)を止めるには、
  別の決定が要る(例: 作者が kivra の App ではないと分かっている PR の扱い、PR を閉じるかどうか)。現時点では、
  binding がある Run は `PR_ABANDONED`(PR が閉じられた、または `KIVRA_SELFDEV_PR_REVIEW_TIMEOUT_DAYS` を過ぎた)でだけ止まる。
  それ以外は、人がマージするか、PR を閉じるまで待つ。この経路が必要になったら、別の ADR で決める。
- **解放時に、Run の lease を強制的に解く処理は行わない。** worker が lease を持っていれば、その worker の次の fenced 書き込みは、
  状態と version の条件で失敗する。UNKNOWN の解放と同じ扱いである。
- **原因の再発防止は含まない。** 同じ原因で次の Run が再び止まれば、同じ経路で解放する。
- **画面(`app.js` など)の対応は含まない。** 別の PR で扱う。

### 結果(追補)

- 解放の経路は、2 つになる。UNKNOWN(ADR 0009 本文)と、束縛の無い PULL_REQUEST(本追補)。どちらも、運用者が認証された主体として行い、
  監査と outbox を同じトランザクションで書く。
- 汎用の遷移ルールは変わらない。`PULL_REQUEST -> CANCELLED` は、`next` に従来からあるが、この経路は `transition()` を通らない。
  運用者の経路で、束縛の有無を行ロックの下で確認する点が、汎用の遷移と異なる。

## 追補(2026-11-11): 止まった DEVELOPING の Run の解放

### 背景

自己開発の Run が `DEVELOPING` のまま止まることがある。実例: 作成から 3 秒後を最後に更新が無く、coding attempt が 1 件も投入されず、
エージェントの claim も無い Run。この Run が同時 Run 上限(`KIVRA_SELFDEV_MAX_ACTIVE_RUNS`)の枠を占有し、Order の自動の周が始まらなかった。
これまでの解放は `UNKNOWN`(本文)と、束縛の無い `PULL_REQUEST`(2026-10-17 の追補)だけで、`DEVELOPING` を取り消す手段が無かった。

### 決定(追補)

1. **経路を足す。** `POST /api/admin/selfdev/runs/{id}/release-stuck-developing`。認証・CSRF・権限(`BUDGET_OVERRIDE`)・本文の検査は、
   既存の 2 つの解放と同じ。理由は 1〜500 文字で、監査の payload にだけ入る。汎用の `transition()` と遷移表は変えない。
2. **遷移は `DEVELOPING` → `CANCELLED` だけ。** `state_reason` は固定コード `OPERATOR_RELEASED_STUCK`(`varchar(64)` に収まる)。
3. **動いているかもしれない Run は解放しない。** 行をロック(`FOR UPDATE`)した下で、次の順に確認し、満たさなければ何も書かず 409 を返す。
   応答は `{"error","code"}` で、`code` は `NOT_DEVELOPING`、`PR_BOUND`、`ACTIVE_ATTEMPT`、`LEASE_HELD`、`TOO_RECENT`。
   - 状態が `DEVELOPING`(`NOT_DEVELOPING`)。
   - PR が結び付いていない(`PR_BOUND`)。この経路は PR に触れない。
   - `QUEUED` / `CLAIMED` / `RUNNING` の coding attempt が無い(`ACTIVE_ATTEMPT`)。エージェントが動いているかもしれない。
     終端(`RESULT_RECEIVED`、`FAILED`、`ORPHANED`、`EXPIRED`、`CANCELLED`)だけなら妨げない。
   - worker の lease が切れているか、誰も持っていない(`LEASE_HELD`)。DB の時計で判定する(他の fence と同じ)。
   - 最終更新から 10 分以上たっている(`TOO_RECENT`)。定数 `STUCK_DEVELOPING_MIN_IDLE`。生きている Run を誤って止めないための余裕で、
     環境変数にはしない(運用で変える理由が見つかったら別に決める)。
   更新は `state` と `version` を条件にし、`RUN_RELEASED` の監査(`from` = `DEVELOPING`)と `RunStateChanged` の outbox を同じトランザクションで書く。
4. **GitHub には何もしない。** PR は閉じず、マージもしない。`DEVELOPING` の Run に PR は無いはずだが、結び付いていれば拒否する。
5. **解放後は上限の計数に入らない。** `countActiveSelfDev` は終端状態を除外し、`CANCELLED` は終端。テストで、解放の前は新しい投入が上限で断られ、
   後は受け付けられることを確認する。
6. **予算の予約と Order の周は、`UNKNOWN` の解放と同じ扱い。** 解放はどちらにも書かない。予算の予約は既存の経路(期限・acknowledge)で扱う。
   Order の周は、`CANCELLED` の Run を失敗として見る(`UNKNOWN` が周の安全装置になる点は、`CANCELLED` では外れる)。
7. **画面。** 状態が `DEVELOPING` で PR の無い Run の詳細に「運用者の解放（止まった Run）…」を出す。表示条件は純関数(`releaseKindOf`)。
   画面は attempt・lease・最終更新を見ないので、状態だけで出し、解放できるかはサーバーが決める。断られた理由は `code` ごとの文言で出す。
8. **マイグレーションは足さない。**

### 未対応(この追補で扱わないこと)

- **自動の解放は含まない。** 止まった Run を kivra 自身が見つけて閉じる処理(止まる原因の調査と再発防止を含む)は、別に決める。
- **`QUEUED` のまま期限が切れた attempt の掃除は含まない。** 期限切れは既存の reconcile が `EXPIRED` にする。それまでは `ACTIVE_ATTEMPT` で拒否する。
- **worker の lease を強制的に解く処理は行わない。** lease が切れていることを条件にする。

### 結果(追補)

- 解放の経路は 3 つになる。`UNKNOWN`、束縛の無い `PULL_REQUEST`、止まった `DEVELOPING`。いずれも運用者が認証された主体として行い、監査と outbox を同じトランザクションで書く。
- `DEVELOPING` の解放は条件が最も厳しい(動いている最中の Run を止めうるため)。運用手順は runbook §4.1c。

## 追補(2026-11-16): 実行の lease 切れ(`CODING_ORPHANED`)の UNKNOWN を自動で解放する

### 背景

dev で agent の Pod が 13 時間 Unknown になり、coding attempt の lease が切れて ORPHANED になった。Run(01M7GZ61TWRRG5WTJM717JZC2E)は
`UNKNOWN` / `CODING_ORPHANED`(PR なし)のまま残り、`SelfDevWorker` は `reason=AWAITING_OPERATOR` を出し続け、同時 Run 上限の枠を占有した。
解放の手段は本文の手動解放(`POST /api/admin/selfdev/runs/{id}/release`)だけだった。

### 決定(追補)

**ユーザーの決定(2026-11-16)**: 自動で解放する。本 ADR の「将来、証拠に基づく自動の解放を入れるときは、この ADR を置き換える」は、置き換えずに
次の形に限った例外として追補する。手動の解放はそのまま残す。

1. **対象は原因が明確で副作用が無いものだけ。** 行をロック(`FOR UPDATE`)した下で、次をすべて満たす Run だけを解放する。満たさなければ何も書かない。
   - 自己開発の Run(`taskId` が `selfdev:` で始まる)。
   - 状態が `UNKNOWN` で、`state_reason` が `CODING_ORPHANED`。
   - PR の列(`pr_number`、`pr_repository`、`pr_head_sha`、`pr_node_id`)がどれも空(束縛が無く、部分的な記録も無い)。
   - 監査に `PATCH_READY` / `PULL_REQUEST` への遷移が無い。
   - `QUEUED` / `CLAIMED` / `RUNNING` の coding attempt が無く、worker の lease が切れているか誰も持っていない(DB の時計)。
   - 最終更新から猶予(`KIVRA_SELFDEV_ORPHAN_AUTO_RELEASE_GRACE_SECONDS`、既定 600 秒、下限 60 秒)が過ぎている。
2. **GitHub への書き込みが無いことの根拠(コード上の事実)。** Run の GitHub への書き込み(ブランチ、コミット、PR)は、すべて `PATCH_READY` を
   記録した後に行う(`SelfDevRunner`: 「PATCH_READY is committed before the first GitHub write」)。CLI と agent は GitHub の資格情報を持たず、
   push しない(ADR 0010 決定 4)。`CODING_ORPHANED` は、`DEVELOPING` の Run に対して coding runtime の reconcile(`holdForOperator`、
   `expectedFrom = DEVELOPING`)か、runner の advance が付ける。runner の再開(`PATCH_READY` からの advance)でも理論上は付きうるので、
   `PATCH_READY` を一度でも通った Run は監査で見分けて除外する。こうして、対象の Run は GitHub に何も書いていない。
   LLM(Claude Code CLI)の費用は、Run の解放とは別に、予算の予約(attempt の上限額で計上、ADR 0003 追補 2026-11-15)で扱われる。
3. **他の UNKNOWN は対象外(手動のまま)。** `PR_NOT_FOUND` / `PR_AMBIGUOUS`(GitHub 上に PR やブランチがあるかもしれない)、
   `GITHUB_UNKNOWN_OUTCOME`・`COMMIT_NOT_APPLIED_TWICE`(GitHub への書き込みの結果が不明)、`DEVELOPER_*`(LLM の呼び出し結果が不明で、
   費用や結果の扱いを人が確かめる)、`CODING_QUEUE_EXPIRED`(agent が 24 時間来なかった。設定や配備の問題で、解放しても次も同じになる)、
   `CODING_AUTH_REQUIRED` / `CODING_NEEDS_HUMAN`(人の対処が要ると明示されたもの)。どれも、原因か副作用を人が確かめる必要がある。
   `CODING_ORPHANED` は「実行環境が消えて結果が返らない」ことが確定しており、GitHub への副作用も無いので、取り消しても失うものが無い。
4. **同じ記録を同じトランザクションで書く。** `DevelopmentRunRepository.autoReleaseOrphanedSelfDev` が `UNKNOWN` → `CANCELLED` を行い、
   `RUN_RELEASED` の監査と `RunStateChanged` の outbox を同じトランザクションで書く。操作者は固定の `system:auto-release`(運用者の owner key は
   `system:` で始まらない。予算の自動解除と同じ主体)。`state_reason` は `AUTO_RELEASED_ORPHAN`(`OPERATOR_RELEASED` と区別できる。
   `varchar(64)` に収まり、`development_runs.state_reason` に CHECK 制約は無い)。監査の payload の `reason` は固定コード `GRACE_ELAPSED`(自由文は無い)。
   outbox の payload には `reasonCode` を入れる(束縛の無い PR・止まった DEVELOPING の解放と同じ)。
5. **遷移表は変えない。** 汎用の `transition()`・`DevelopmentRunState` は変えず、`kivra-core` も変更しない。経路は本文の手動解放と同じく専用の関数だけ。
   オートパイロットは `CANCELLED` の Run を従来どおり `RUN_RELEASED` として扱い、Order の周は失敗として見る。予算の予約には何も書かない。
6. **スイッチと安全弁。** `KIVRA_SELFDEV_ORPHAN_AUTO_RELEASE_ENABLED`。コードの既定は false(有効化は k8s のマニフェスト)。不正値は起動失敗で、
   値は出さない。自己開発が無効のときはこの設定を読まず(自己開発の変数はスイッチ以外読まない、という既存の不変条件)、動かない。60 秒ごとの 1 回で最大 20 件、古い順。各 Run は個別のトランザクションで、状態と版を条件に更新するので、複数の Pod が同時に動いても
   二重に解放しない(後の側は対象外になるだけ)。失敗した Run は件数をログに出し(`failed=<件数>`、WARN)、次の回に再試行する。ログは件数と
   固定コードだけで、Run の id・依頼文・理由の本文は出さない。
7. **画面。** 理由 `AUTO_RELEASED_ORPHAN` の説明文を Compose UI に足す。監査の操作の表示名(`RUN_RELEASED` = 「運用者が解放」)は変えていない
   (操作者の列が `system:auto-release` になる)。

### 結果(追補)

- `CODING_ORPHANED` の Run は、猶予の後に自動で `CANCELLED` になり、同時 Run 上限の枠が空く。原因(agent の停止)は直さないので、
  agent が止まったままなら次の Run も同じ経過をたどる。agent の監視は別の作業。
- 他の UNKNOWN は従来どおり運用者の解放を待つ。運用手順は runbook §4.1 の「自動の解放」。

## 追補(2026-11-18): `CODING_NEEDS_HUMAN` の UNKNOWN も自動で解放する

### 背景

dev で Run 01M7EK9Y9NJNKZRHMTB20MVNKH が `UNKNOWN` / `CODING_NEEDS_HUMAN`(PR なし)のまま 2026-11-15 から残り、`AWAITING_OPERATOR` を
出し続けた。2026-11-16 の追補は `CODING_NEEDS_HUMAN` を「人の対処が要ると明示されたもの」として対象外にしていた。

### 決定(追補)

**ユーザーの決定(2026-11-18)**: `CODING_NEEDS_HUMAN` も自動で解放する。2026-11-16 の追補の決定 3 のうち、`CODING_NEEDS_HUMAN` を対象外とした部分を変更する。

1. **`CODING_NEEDS_HUMAN` が付く経路(コード上の事実)。** agent(`kivra-agent`)が attempt を `fail(NEEDS_HUMAN)` で終えたときだけ。
   - stub の executor(`KIVRA_AGENT_EXECUTOR` 未設定・`stub`): 何も実行せずに返す。
   - claim のパスのパターンが不正: CLI を起動する前に返す。
   - executor の例外(`AgentLoop.executeOrNeedsHuman`): 準備(git、展開)、docker の引数の検査、kivra の 5xx や通信の失敗、その他の I/O の失敗。
     409 / 404(lease を失った)は fail を送らない。
   kivra 側(`CodingAttemptPatchSource.failedWith`)は、`DEVELOPING` の Run でこの失敗を読むと `UNKNOWN` / `CODING_NEEDS_HUMAN` にする。
   `PATCH_READY` からの再開では、どの失敗も `CODING_<code>` の `UNKNOWN` になるので、同じ理由が `PATCH_READY` の Run にも付きうる。
2. **副作用が残らないこと。** 2026-11-16 の追補と同じ条件で絞る(PR の列が空、監査に `PATCH_READY` / `PULL_REQUEST` が無い、
   `QUEUED` / `CLAIMED` / `RUNNING` の attempt が無い、lease が無い、猶予が過ぎた)。`PATCH_READY` からの再開で付いた Run は、監査の条件で外れる。
   - GitHub: agent は GitHub の資格情報を持たず(ADR 0010)、`DEVELOPING` までの Run は GitHub に何も書いていない。
   - attempt: `NEEDS_HUMAN` の attempt は `FAILED`(終端)で、lease も無い。
   - 予算: 費用は `fail` の時点で、kivra(`CodingRuntimeService.fail`)が計上済み(`usage` があればその額、無ければ 1 attempt の予約額を費用不明として)。
     解放は予算に何も書かない。手動の解放と同じ。費用不明の分は ADR 0003 追補 2026-11-15 の自動解除で扱われる。
   - 結果: agent が結果を送れず `NEEDS_HUMAN` になった場合、作業の結果は失われるが、外部への書き込みは無い。
3. **区別できる理由コード。** `state_reason` は `AUTO_RELEASED_NEEDS_HUMAN`(`varchar(64)` に収まる)。`CODING_ORPHANED` は従来どおり
   `AUTO_RELEASED_ORPHAN`。監査・outbox・操作者(`system:auto-release`)・トランザクションは 2026-11-16 の追補と同じ。
4. **スイッチと猶予は共通。** 同じ worker、同じ `KIVRA_SELFDEV_ORPHAN_AUTO_RELEASE_ENABLED`、同じ猶予。`NEEDS_HUMAN` は attempt が終端になった時点で
   確定していて、`ORPHANED` のように遅れて結果が届く余地を見る必要は無い。猶予は運用者が気づいて見る時間として、同じ値で足りる。変数名は最初の版のまま。
5. **繰り返しの歯止め(ブレーカー)。** `NEEDS_HUMAN` はホスト側の設定や環境の異常で起き、直るまで次の attempt も同じになりやすい。これまでは
   `UNKNOWN` が同時 Run 上限の枠を占有して、事実上それ以上進まなかった。自動で解放すると、オートパイロットはキューの次の task を投入し、それも
   同じく失敗して取り消される(task が 1 件ずつ失われ、1 attempt の予約額が積み上がる)。そこで、**自動で解放された Run(`AUTO_RELEASED_ORPHAN`・
   `AUTO_RELEASED_NEEDS_HUMAN`)は、オートパイロットのサーキットブレーカーで失敗として数える**(`AutopilotDispatcher`)。task の状態は従来どおり
   `CANCELLED`(`RUN_RELEASED`)。運用者の解放と中止は従来どおり数えない。Order の周は従来から `CANCELLED` を失敗として数え、連続の失敗で止まる。
6. **対象外のまま。** `PR_NOT_FOUND` / `PR_AMBIGUOUS`、`GITHUB_UNKNOWN_OUTCOME`・`COMMIT_NOT_APPLIED_TWICE`、`DEVELOPER_*`、`CODING_QUEUE_EXPIRED`、
   `CODING_AUTH_REQUIRED`(認証の失敗。人が資格情報を直すまで同じになる。ユーザーの決定の範囲外)、そのほかの結果不明の理由。理由は 2026-11-16 の追補の決定 3 と同じ。

### 結果(追補)

- `CODING_NEEDS_HUMAN` の Run も、猶予の後に `CANCELLED` になり、同時 Run 上限の枠が空く。原因(ホスト側の異常)は直さない。
- 同じ異常が続くときは、オートパイロットのブレーカーが連続の失敗で開いて止まる(`CODING_ORPHANED` にも同じ歯止めが入る)。
- 運用手順は runbook §4.1a。
