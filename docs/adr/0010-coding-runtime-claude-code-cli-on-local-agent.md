# ADR 0010: 自己開発のコード編集を、ユーザーのローカル PC の常駐エージェントと使い捨てコンテナの Claude Code CLI で行う

- ステータス: 提案(Proposed)。ユーザーの承認待ち。承認まで実装は始めない
- 日付: 2026-10-17
- 関連: Issue #187(Use existing logged-in Claude Code CLI as coding runtime)、#157、#56、#181、#174、#71、#112、#184、#188。ADR 0008(一部改定)、ADR 0009、ADR 0001(Run の正本)

## 背景

ADR 0008 の自己開発は、LLM の 1 回の提案で `PatchSet`(ファイル全文の `Write` / `Delete`)を返させ、その PatchSet を `PatchPolicy` で検査して PR にする。テストは CI に任せ、kivra は生成コードを実行しない。

この方式には限界がある。

- 提案は全文を LLM の出力で返すため、大きなファイルを編集すると出力上限を超える。dev の 2026-10-17 に次の事例が起きた。thinking を無効にした状態で、README.md(46,398 バイト)の 1 箇所の修正を依頼したところ、PatchSet が全文を返す形式のため出力が 8000 トークンの上限に達して途切れた(Run 01M52RC1G2CQYCGYV85W5D4WHV。2 試行とも outputChars=11753 / 11752、startsWithObject=true、endsWithObject=false、finishReason=LENGTH。MALFORMED_JSON となり FAILED)。本 ADR の動機はこの事例である。
- 同じ日の別の事例は、thinking が出力上限を使い切ったもの(outputChars=0、finishReason=LENGTH)である。これは ADR 0008 追補(2026-10-17)の thinking 無効化で対処済み(#184)。#188 で LENGTH の再送を止めた。
- 提案は 1 回の呼び出しで終わる。編集してテストを流し、失敗を直す、という複数ターンの作業ができない。
- 変更の途中経過を見ることができず、失敗時に作業を再開する手段がない。

一方、利用者の手元には、既にログイン済みの Claude Code CLI がある。Issue #187 は、これを自己開発のコーディング実行基盤に使うことを求めている。

#187 の最新のコメント(kigawa01)は、認証について「ユーザーが専用の実行環境で `claude login` を対話的に行い、kivra は資格情報を扱わない」方針を示している。本 ADR は、この方針を Workload Identity Federation(WIF)に置き換える(§決定 6。#187 のコメントとの差は §結果 に記す)。

## 決定

1. **コーディング実行は、`DeveloperPort`(1 回の提案)とは別の `CodingRuntimePort` で抽象化する。** 長時間の作業(開始、観測、取消、再照合)を扱う。既存の `ModelGateway`(計画・予算)、`GitHubPort`(PR 管理)とは役割を分ける。
2. **実行はユーザーのローカル PC の常駐エージェント `kivra-agent` が行う。** kivra は attempt を DB に作るキュー型とし、kivra-agent が outbound で pull(claim)する。kivra は PC に到達しない。kivra-agent と kivra の認証は、PC ごとの Keycloak client(client credentials、client `kivra-agent`)とロール `kivra-agent` で行う。kivra-agent は JVM で動き、専用 OS ユーザー(`kivra-agent`)の `systemd --user` で常駐させる。
3. **Run ごとに使い捨ての docker コンテナで Claude Code CLI(`claude -p`)を実行する。** docker は専用 OS ユーザー `kivra-agent` の**ルートレス docker** を使う。コンテナには、その Run の作業ディレクトリ(rw)だけを mount する。
4. **差分は kivra-agent がホストで作る。** kivra がベースの内容を GitHub から取得して agent に渡す。agent は作業ディレクトリを `git archive` の展開物として作り、ベースとファイル単位で比べて `PatchSet` を作る。ホストは作業ディレクトリに対して `git` を実行しない。kivra は受け取った PatchSet を、自分で取得したベースに対して `PatchPolicy` で再検査し、通ったものだけを既存の `commitFiles`(`kivra/run-<runId>` ブランチ)で反映する。CLI と agent は GitHub の資格情報を持たず、push しない。
5. **隔離の不変条件(7 項目。違反する案は採らない):**
   1. docker.sock を、どのコンテナにも渡さない。
   2. 実行コンテナは `--privileged` を使わず、ホストの PID・NET・IPC 名前空間を使わない。
   3. 実行コンテナにマウントするのは、その Run の作業ディレクトリだけ。ホストの `~/.ssh`、ユーザーの `~/.claude`、ブラウザのプロファイル、他のリポジトリ、kivra-agent の設定(client secret)は、マウントしない。
   4. 実行コンテナは kivra の資格情報も GitHub の資格情報も、Anthropic の資格情報も持たない。kivra と通信するのはホスト上の kivra-agent だけ。
   5. ホストは、エージェントが書き込める作業ツリーに対して `git` を実行しない(`.git/config` の fsmonitor などでホストのコードが動くのを防ぐ)。
   6. 実行コンテナの外向き通信は、内部ネットワークとプロキシの許可リストを経由するものだけ。kivra と GitHub には直接届かない。
   7. 「ログイン済みアカウントはサンドボックスではない」。コンテナ内に読める認証情報を置かないことで、この前提を満たす(認証は §決定 6 のとおりホスト側に置く)。
6. **Anthropic への認証は WIF(案 K')とし、ホスト側のプロキシが付与する。** 具体的には次のとおり。
   - ホスト側のプロキシ(kivra-agent と同じ専用 OS ユーザーで動く)が、Keycloak から JWT を取得する(client credentials、client `kivra-agent`)。
   - そのプロキシが、Anthropic の `POST /v1/oauth/token`(jwt-bearer)で JWT を短命のアクセストークンに交換し、コンテナからの Messages API の呼び出しに `Authorization` ヘッダーとして付与する。コンテナの `claude` は `ANTHROPIC_BASE_URL` でこのプロキシへ向ける。
   - JWT の `jti` は 1 回限りのため、交換のたびに新しい JWT を取得する。アクセストークンはキャッシュし、期限の前に更新する。
   - Anthropic 側は、Keycloak(`https://user.kigawa.net/realms/kigawa-net`、公開の discovery)を federation issuer に登録し、ルールを `azp == kivra-agent` と audience(`https://api.anthropic.com`)で絞る。ルールは専用のサービスアカウントと専用のワークスペースに紐付ける。ワークスペースの支出上限(Console)を最終防壁とする。
   - PC に置く長期の秘密は、Keycloak の client secret だけとする。root 専用ファイル(0600)に置き、ローテーションできる。漏洩時は client を無効化する。
   - コンテナには API キー、トークン、JWT のいずれも置かない。kivra は Anthropic の資格情報を DB・Secret・ログに保存しない。kivra-agent は、起動時と各 Run の前に、コンテナに `ANTHROPIC_API_KEY` などの認証用の環境変数が無いことを検査し、あれば Run を始めない。`--bare` は使わない。
   - サブスクリプション(Claude.ai ログイン、Pro/Max)による方式(案 L)は採用しない。静的な API キーをホストのプロキシに置く方式(案 K)は、WIF に比べて長期の秘密が増えるため採用しない(代替案を参照)。
7. **CLI の実行は fail-closed に扱う。** タスクの本文(タイトル・説明)は非信頼の入力としてデータで渡す。Policy や許可リストを変える指示として扱わない。最終的な防御は `PatchPolicy` と `commitFiles` の構造的な保証であり、プロンプトではない。
8. **単一の attempt 実行。** claim は CAS(`lease_generation` を進める)で行い、結果と heartbeat は `lease_generation` が一致する場合だけ受け付ける(fencing)。PC が停止している間、attempt は QUEUED で待つ。PC は基本的に常時 ON とし、`queued_expires_at` は作成から 24 時間を初期値とする。期限を過ぎた attempt は EXPIRED とし、Run を hold する(古い指示で実行しない)。
9. **予算と上限。** 支出の最終防壁は、専用ワークスペースの Console 側の支出上限。kivra は stream-json の usage を集計して attempt の結果と一緒に記録する(PC 側の報告は改ざんされうるため、正本にはしない)。1 Run の時間、ラウンド数、出力量、差分の件数とサイズには上限を置く(数値は未決)。
10. **`api` 方式(`LlmDeveloper`)は残す。** 設定 `KIVRA_SELFDEV_RUNTIME` で切り替え、既定は `api` とする(承認後の実装で決める)。

## 理由

- 出力上限の問題は、CLI が作業ディレクトリを直接編集することで解消する。全文を LLM の出力で返す必要がなくなる。
- 複数ターンの作業(編集、テスト、修正)は、CLI を使うことで初めて可能になる。
- 差分を既存の `PatchPolicy` に通すため、CLI や agent が侵害されても、GitHub への書き込みは従来の検査を通る。
- pull 型のため、PC が NAT の内側にあっても動く。kivra から PC への経路を作らない。
- 使い捨てコンテナは、CLI の Bash が及ぶ範囲をホストから切り離す。ホストの鍵とファイルが見えない。
- WIF は、PC に長期の Anthropic の資格情報を置かず、短命のトークンだけを使う。コンテナには認証を置かない。Keycloak の client secret は、client 単位で無効化できる。
- 公式は、API(WIF を含む)経由での認証を自動化向けの方式として案内している(事実の範囲。下記の規約の節を参照)。
- k8s の NetworkPolicy は、現行の CNI(Flannel)では効かない。ローカルでは、内部ネットワークとプロキシで外向き通信を強制できる。

## 代替案

- **案 L(サブスクリプションでの `claude login`)**: 採らない。Pro/Max の認証を自動実行に使うことの扱いが公式の記述から明確でなく、規約の曖昧さを残すため。
- **案 K(Anthropic の静的 API キーをホストのプロキシに置く)**: 採らない。動作の仕組みは WIF と同じだが、長期の API キーを PC に置くことになる。WIF は短命のトークンだけを使うため、これより秘密の範囲が小さい。
- **k8s のワーカー Pod(StatefulSet と PVC)**: 採らない。認証を PVC に保持するため、長期の認証情報をクラスタに置くことになる。また、NetworkPolicy が効かず egress を強制しにくい。PC が落ちている間は Run が QUEUED で待つ、という代償を受け入れる。
- **専用ホスト(常時稼働のサーバー、または専用 VM)**: 可用性と隔離の面で有利になりうる。運用の負荷が高いため、本 ADR では採らない。PC での運用が成り立たない場合の次の選択肢として残す。
- **API 方式の部分置換**: 提案を全文の PatchSet から差分だけに変える案。出力上限は避けられるが、複数ターンの作業とテストの実行ができない。採らない。
- **ホストで CLI を直接実行(コンテナなし)**: ホストの秘密が見える。不採用。承認済みのタスクだけに限る一時的な例外としてのみ検討する。
- **CLI に commit と push をさせる**: 資格情報が PC に入り、`PatchPolicy` を迂回する。不採用。
- **認証を Secret や DB に保存する**: 禁止。
- **docker.sock を渡す**: 不変条件 1 に違反する。不採用。

## 結果

- kivra 側: `coding_attempts`(想定 V14。expand のみ)、`CodingRuntimePort`、`/api/agent/*` の認可(JWT のロール・audience・azp を検証し、ヘッダーは信頼しない)、result の受付から `PatchPolicy` を経て既存の `commitAndOpenPullRequest` へつなぐ経路。
- Keycloak: ロール `kivra-agent`、PC ごとの client(client credentials)。ユーザーが管理作業を行う。
- Anthropic Console(ユーザーが実施): federation issuer に Keycloak を登録し、ルール(`azp == kivra-agent`、audience `https://api.anthropic.com`)、専用サービスアカウント、専用ワークスペース(支出上限)を設定する。
- ingress: 現在の公開 HTTPS(kivra-dev.kigawa.net、oauth2-proxy + Keycloak)に、`/api/agent/` の別経路を足す。VPN は使わない。kivra が JWT のロール・audience・azp を検証する。kivra-dev の oauth2-proxy は `--allowed-role=admin-panel:admin` のため、agent の Bearer は通らない。agent 用の経路を別に作る必要がある。
- 新しいコンポーネント: PC 上の `kivra-agent`(JVM。配布、`systemd --user` のユニット、設定、停止の手段)、ホスト側の Anthropic 認証プロキシ(WIF の交換と Authorization の付与)、許可リストのプロキシ、実行イメージ(claude CLI、git、JDK、非 root)。
- **ADR 0008 の改定(受け入れ済み)**: ADR 0008 は「kivra は生成コードを実行しない」「テストは CI のみ」としている。本 ADR により、テストは PC の隔離コンテナで行い、最終確認は CI で行う。ただし kivra 自身(kivra-app)が生成コードを実行することはない。この例外は kivra-agent の隔離コンテナ内の実行に限る。
- **#187 のコメントとの差**: #187 のコメントは「同じ OS ユーザーで `claude login` を行い、kivra は資格情報を扱わない」方式を示している。本 ADR は、この認証を WIF(ホスト側プロキシが Keycloak の JWT を Anthropic の短命トークンに交換)に切り替える。コンテナには認証を置かない点は同じ。
- 残存リスク:
  - 許可したドメインへの送信は防げない。ドメイン単位の許可では、許可先に秘密を入れて送ることを止められない。
  - ルートレス docker でも、コンテナ突破後の影響は専用 OS ユーザーの権限に及ぶ。ホスト上の専用ユーザーのファイルを読めないことは、不変条件 3 と専用ユーザーの設計で担保する。
  - PC 上の Keycloak client secret が漏れると、client を無効化するまで WIF の交換に使われうる。
  - 支出は Console の上限で止まるが、上限に達するまでの支出は発生しうる。
  - 利用規約の解釈(下記)。
- 運用: PC の稼働(常時 ON 前提)、スリープの扱い、client secret のローテーション、支出上限、client の失効、停止手順(STOP ファイル、systemd の停止、kivra 側の STOPPED)。

### 規約について(事実の範囲)

- 公式ドキュメントは、プログラムから組み込む開発者には API 経由の認証を案内している。WIF を含む(研究の要約に基づく。原文の再確認は未実施)。
- OAuth(Claude.ai ログイン、Pro/Max)は「通常の利用」向けという記述があり、開発者が自分のプロダクトからそれを経由させることを許可しないという記述がある。
- 消費者向け規約 第 3 条は、API 経由の場合を除き、automated / non-human な手段でのアクセスを扱っている。
- 本 ADR は、API(WIF を含む)経由での認証を選ぶ。これは公式が自動化向けに案内する方式である。本 ADR は規約上の適法性を断定しない。

## 未決の点

- **プロキシによる認証付与の実機での成立(A3 相当)**: コンテナ内の `claude` が、認証情報を持たずに `ANTHROPIC_BASE_URL` のプロキシを経由して起動し、応答を得られるか。WIF の交換をプロキシで行えるか。PR 0 の試作で検証中。成り立たない場合は、本 ADR の認証の決定を改める。
- **Anthropic Console の設定**: 発行者(Keycloak)、ルール、サービスアカウント、ワークスペースの設定は、ユーザーが実施する。
- **プロキシの許可ドメインの確定**: Gradle と Maven の取得先の正確な一覧。PR 0 で確認する。
- **Run の上限値**: 1 Run の時間(例: 30 分)、ラウンド数、出力量、差分の件数とサイズ。
- **PatchPolicy の上限の食い違い**: `PatchPolicyConfig.maxFiles = 20` と、`DeveloperConstraints.maxFiles` の既定 10 を、PR 9(kivra 側の result 受付)で統一する。確定する実装課題として記載する。
- **実行イメージの詳細**: claude CLI のインストール方法とバージョンの固定、JDK と Gradle のバージョン、配布先(Harbor か GitHub Release)。PR 10 で決める。
- **既存の問題**: `/api/agent/*` を含む Task API がヘッダーを信頼する問題(NetworkPolicy が効かないため、クラスタ内の Pod が偽ヘッダーで操作できる)は別 Issue で扱い、本 ADR の対象外とする。
- **未確認の事実**: `--max-turns` の有無、`claude auth status` の出力、利用上限の挙動、oauth2-proxy の JWT Bearer 用のフラグ。

## 見直しの条件

- PR 0 の試作で、プロキシによる認証付与(WIF の交換を含む)が成り立たないと分かったとき。認証の決定を改める。
- 規約に関する新しい公式の記述が見つかったとき。
- 支出上限を超えて、Run の上限の見直しが必要になったとき。

## 実装状況

- 未着手。本 ADR の承認後、設計案 `design-coding-runtime.md` の §9 の順で、小さな PR に分けて進める。

## 追補(2026-10-17)

1. **CLI 経路は UNSEEN_OVERWRITE を適用しない。** エージェントはベースのリポジトリ全体を扱うため、LLM の文脈(README と docs)の外のファイルも「見た」扱いにする。ADR 0008 の UNSEEN_OVERWRITE は LLM の限られた文脈のための規則で、CLI 経路には当てはめない。最終関門は結果受信時の PatchPolicy(許可・拒否パス、サイズ、秘密らしい内容、symlink などの形)で、これは緩めていない。担保する既存テスト: `CodingRuntimeServiceTest` の「shape checks or the PatchPolicy refuse」(秘密らしい内容、拒否パス、許可外パス、サイズ超過、symlink、submodule、実行ビット、バイナリ、件数超過、形の不正、終了コード、空の要約を拒否)、`SelfDevCodingRuntimeTest` の「refuses against the base snapshot」(縮小と欠けた削除をベースのスナップショットで拒否)。

2. **エージェント API は、運用者の API と認可を分離する(PR 4a, 2026-10-17)。** `/api/agent/v1/*` は、運用者の API(`/api/selfdev/*`、`/api/admin/*`、`/api/runs` など)とは別の認証器(`AgentIdentity`)で認可する。
   - 別のロール: エージェントには `KIVRA_AGENT_REQUIRED_ROLE`(既定の想定は `kivra-agent:agent`)が必要。運用者のロール `admin-panel:admin` は、エージェントの認可を通さない。`KIVRA_AGENT_REQUIRED_ROLE` が運用者のロール(`KIVRA_OIDC_REQUIRED_ROLE`)と同じなら起動に失敗する。
   - 別の client: トークンの `azp` が `KIVRA_AGENT_AZP`(既定の想定は `kivra-agent`)と完全一致すること。audience は `KIVRA_OIDC_AUDIENCE`(kivra)を含むこと。
   - ヘッダーを信頼しない: トークンは `Authorization: Bearer` だけから読む。`X-Forwarded-Access-Token`、cookie、`X-Auth-Request-*` は無視する。`Authorization` が 2 つあれば未認証とする。
   - 401(未認証・不正)と 403(認証は通るが azp かロールが不足)を区別する。理由は本文に出さず、固定の文言だけを返す。
   - agent_id は、検証済みトークンの `sub` だけを使う。リクエストの本文・ヘッダーで名乗らせない。attempt の操作は、その attempt を claim した agent_id と一致する場合だけ許す。他人の attempt は存在しないものと同じ 404 にする。
   - 世代(lease generation)だけでは守られない。世代は小さな整数で推測できるため、所有者の一致を CAS の `WHERE` 句に含める。
   - 既定で無効(`KIVRA_AGENT_API_ENABLED`)。無効のときはルートを登録せず、`CodingRuntimeService` も作らない。`KIVRA_SELFDEV_RUNTIME=cli` の起動拒否は、この PR では解除しない(エージェント本体の完成後の PR で解除する)。※ 解除の条件は追補 3(PR 9)で定めた。
   - ブラウザからの呼び出しを拒否する: `Sec-Fetch-*` か `Origin` があれば 403。`Content-Type` は `application/json` を要求する(415)。本文は 2 MiB を上限とし、超えれば 413。
   - 監査: claim、結果の受理、失敗、結果の拒否を、Run の監査連鎖に書く(actor は agent の sub。内容・パス・理由の本文は入れない)。
   - 担保するテスト: `AgentIdentityTest`(認証と認可の表)、`AgentApiSettingsTest`(設定の fail-closed)、`AgentApiRoutesTest`(HTTP の網羅、他人の attempt、世代、413、422、ログと応答の秘密、監査)、`AgentApiWiringTest`(既定で無効、有効時の起動検査)。
   - ingress(oauth2-proxy)は、エージェント API を通さない。`--allowed-role=admin-panel:admin` のため、エージェントの Bearer は通らない。この経路は PR 4b(Keycloak と ingress の作業)で決める。

3. **CLI の結線と起動の条件(PR 9, 2026-10-17)。** 決定 10 の `cli` を、次の条件つきで有効にする。既定は `api` のまま、エージェント API も既定で無効のまま。
   - **起動の条件**: `KIVRA_SELFDEV_RUNTIME=cli` は `KIVRA_AGENT_API_ENABLED=true` のときだけ起動を許可する。それ以外は起動を拒否し、変数名と ADR 0010 を含む理由だけを出す(値は出さない)。`true` のときは §6.1 の検査がすべて通ること。
   - **DeveloperPort の CLI 版は作らない。** 決定 1 の `DeveloperPort` は 1 回の同期の提案で、「まだ結果が無い(待ち)」を表せない。runner には、待ちを表せる `CodingPatchSource` の口がすでにあり、CLI はそこで結線する(`CodingAttemptPatchSource`)。`DeveloperPort` の CLI 版を作ると、待ちの状態を失うか、待ちを UNKNOWN と誤るため、採らない。
   - **結果の流れ**: 結果の受理(`submitResult`)で、件数、形、PatchPolicy を検査し、通ったものだけを `coding_results` に保存して RESULT_RECEIVED にする。Run 側は base のスナップショットで PatchPolicy を再検査し、通ったら既存の commit と PR の経路へ進む。拒否の段階では何も保存せず、attempt は CLAIMED のまま。
   - **EMPTY_PATCH**: 変更の無い結果は受理の段階で拒否する(PatchPolicy の `EMPTY_PATCH`)。「直す必要がなかった」を正常な結果として Run に進める経路は作らない。エージェントが変更を送らないまま lease が切れれば ORPHANED になり、Run は UNKNOWN で運用者が判断する(安全側)。エージェント側で「変更なし」をどの失敗コードで報告するかは、エージェント本体の PR で決める。
   - **期限切れの扱い**: 期限を過ぎた QUEUED は EXPIRED、lease の切れた CLAIMED / RUNNING は ORPHANED とし、Run の次の進行で UNKNOWN にする(理由 `CODING_QUEUE_EXPIRED` / `CODING_ORPHANED`)。定期の reconcile は未配線である。
   - **失敗時の Run の状態**: 表は runbook の `docs/runbook-selfdev.md` §6.9 に置く(attempt の状態ごとに、Run の状態と理由コードを書く)。
   - **未了**: エージェント本体の配備、k8s の設定、既定値の変更(別 PR)。`coding_results` の保持期間と掃除(§6.5.3)。reconcile の定期実行の配線。

4. **CLI の費用を月次の予算 ledger に計上する(Issue #230、2026-10-18)。** ADR 0010 の決定 9 の「kivra は usage を集計して記録する」を、次の形で実装する。
   - **報告**: エージェントは `POST .../result` と `POST .../fail` の本文に、任意のフィールド `usage` を付ける(数値と固定名だけ。応答本文・ツールの入力は含まない)。形は `totalCostUsd`(10 進の文字列、0〜100、小数 6 桁まで)、`model`(`^[a-z0-9][a-z0-9.-]{0,63}$`)、`numTurns`(0〜1000)、`inputTokens`、`outputTokens`、`cacheCreationTokens`、`cacheReadTokens`(各 0〜10^10)の 7 項目だけ。
   - **不正な usage は 400 にしない**: 形が合わなければ usage だけを無視し、「報告なし」と同じ扱いにする。結果または fail は受理する。警告ログには attempt の ID と固定の理由(`USAGE_INVALID`)だけを出す。`usage` の無い従来の呼び出しも受理する。
   - **計上の単位**: `(attempt, lease generation)` ごとに 1 回。台帳の予算キー `coding:<attemptId>:<generation>` を、報告と見積もりの両方が共有するため、先に計上された方だけが有効になる。二重の報告は二重計上にならない。`coding_attempt_usage`(V16)は、その計上の内容(`REPORTED` または `ESTIMATED_RESERVE`、数値と model)を記録する。
   - **円換算**: API 経路と同じ `CostConverter`(`KIVRA_FX_USD_JPY`、`KIVRA_FX_AS_OF`、失効 7 日、為替の余裕 10%、税 10%)を使う。為替が失効していれば換算できず、金額 0 の UNPRICED として計上し、以後の予約は UNKNOWN_COST で止まる。`model` が価格表(`KIVRA_SELFDEV_MODEL_REGISTRY_PATH` の registry)に無ければ、換算した額を計上したうえで UNPRICED にする。
   - **報告が欠けた attempt**: 次のいずれかで終わったものは、1 attempt の予約額(`KIVRA_AGENT_ATTEMPT_RESERVE_USD`、既定 0.50 USD)を `ESTIMATED_RESERVE` として計上し、UNPRICED にする(安全側。費用が不明なら、使った前提)。(a) usage の無い fail、(b) ORPHANED、(c) EXPIRED(一度も claim されていない QUEUED を含む)、(d) usage の無い結果の受理。(d) は本文の決定ではなく、この追補での判断である。結果に費用が無い場合も、CLI は動いた可能性があるため、予約を計上する。
   - **入場制御**: attempt を claim する前に、月次の予算に 1 attempt の予約額(USD × FX × 余裕 × 税)が収まるかを `BudgetPort.admission` と同じ規則で確かめる。収まらなければ、キューに attempt があっても claim させず、Run は待ちのまま残る(NoAssignment と同じ応答)。UNPRICED が 1 件でも残っている間は、既存の予算ゲートの規則により止まる(運用者の acknowledge で解除)。理由コードはログに出し、Goal や応答本文は出さない。
   - **非同期の限界**: 入場の確認と claim は別の操作であり、同時に複数のエージェントが claim すると、予約額の数件分だけ予算を超えうる。1 台の PC と 1 つのワークスペースを前提とする。
   - **報告の書き込みが失敗したとき**: 台帳に書けなければ、結果と fail は受理せず、503 を返す。attempt は変えないため、エージェントは送り直せる。台帳の書き込みに成功した後で attempt の遷移が拒否されても、費用は実在するため残す。
   - **期限切れの一括処理の失敗**: ORPHANED / EXPIRED の遷移は済んでいるが台帳への計上が失敗した場合は、警告ログ(ID のみ)を出し、再試行はしない。定期の reconcile が配線されていないため、この欠損は運用者が確認する(runbook §6.13)。既知の制限。
   - **担保するテスト**: `CodingCostLedgerTest`(正常な報告、二重の報告、usage の無い fail と結果、ORPHANED と EXPIRED、入場制御、為替の失効、未知の model と acknowledge、台帳の失敗、ログの衛生、usage の形)、`AgentApiRoutesTest` の usage の追加分(正常、不正の無視、usage の省略、503)。
   - **変異テスト(2026-10-18)**: 入場制御を外すと `CodingCostLedgerTest` の 4 件が落ちる。二重報告の鍵を呼び出しごとに変えて二重計上にすると、2 件が落ちる。
   - **未了**: 運用画面での API 経路と CLI 経路の区別(UI PR5)、定期の reconcile の配線、`coding_attempt_usage` の保持期間。
