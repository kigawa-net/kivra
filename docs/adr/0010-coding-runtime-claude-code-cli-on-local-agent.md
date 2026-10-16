# ADR 0010: 自己開発のコード編集を、ユーザーのローカル PC の常駐エージェントと使い捨てコンテナの Claude Code CLI で行う

- ステータス: 提案(Proposed)。ユーザーの承認待ち。承認まで実装は始めない
- 日付: 2026-10-17
- 関連: Issue #187(Use existing logged-in Claude Code CLI as coding runtime)、#157、#56、#181、#174、#71、#112。ADR 0008(一部改定)、ADR 0009、ADR 0001(Run の正本)

## 背景

ADR 0008 の自己開発は、LLM の 1 回の提案で `PatchSet`(ファイル全文の `Write` / `Delete`)を返させ、その PatchSet を `PatchPolicy` で検査して PR にする。テストは CI に任せ、kivra は生成コードを実行しない。

この方式には限界がある。

- 提案は全文を LLM の出力で返すため、大きなファイルを編集すると出力上限を超える。dev の 2026-10-17 に次の事例が起きた。thinking を無効にした状態で、README.md(46,398 バイト)の 1 箇所の修正を依頼したところ、PatchSet が全文を返す形式のため出力が 8000 トークンの上限に達して途切れた(Run 01M52RC1G2CQYCGYV85W5D4WHV。2 試行とも outputChars=11753 / 11752、startsWithObject=true、endsWithObject=false、finishReason=LENGTH。MALFORMED_JSON となり FAILED)。本 ADR の動機はこの事例である。
- 同じ日の別の事例は、thinking が出力上限を使い切ったもの(outputChars=0、finishReason=LENGTH)である。これは ADR 0008 追補(2026-10-17)の thinking 無効化で対処済み(#184)。#188 で LENGTH の再送を止めた。
- 提案は 1 回の呼び出しで終わる。編集してテストを流し、失敗を直す、という複数ターンの作業ができない。
- 変更の途中経過を見ることができず、失敗時に作業を再開する手段がない。

一方、利用者の手元には、既にログイン済みの Claude Code CLI がある。Issue #187 は、これを自己開発のコーディング実行基盤に使うことを求めている。

#187 の最新のコメント(kigawa01)は、認証について「ユーザーが専用の実行環境で `claude login` を対話的に行い、kivra は資格情報を扱わない」方針を示している。本 ADR は、この方針を Anthropic の API キーを使う案 K に置き換える(§決定 6。#187 のコメントとの差は §結果 に記す)。

## 決定

1. **コーディング実行は、`DeveloperPort`(1 回の提案)とは別の `CodingRuntimePort` で抽象化する。** 長時間の作業(開始、観測、取消、再照合)を扱う。既存の `ModelGateway`(計画・予算)、`GitHubPort`(PR 管理)とは役割を分ける。
2. **実行はユーザーのローカル PC の常駐エージェント `kivra-agent` が行う。** kivra は attempt を DB に作るキュー型とし、kivra-agent が outbound で pull(claim)する。kivra は PC に到達しない。kivra-agent と kivra の認証は、PC ごとの Keycloak client(client credentials)とロール `kivra-agent` で行う。kivra-agent は JVM で動き、専用 OS ユーザー(`kivra-agent`)の `systemd --user` で常駐させる。
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
6. **認証は案 K(Anthropic の API キー)とし、ホスト側のプロキシが認証ヘッダーを付与する(設計案 A3)。** API キーはホストのプロキシだけが持ち、コンテナ内に置かない。コンテナの `claude` は `ANTHROPIC_BASE_URL` でプロキシへ向け、認証なしで起動する。サブスクリプション(Claude.ai ログイン、Pro/Max)による方式(案 L)は採用しない。支出は専用ワークスペースの Console 側の支出上限を最終防壁とする。kivra は API キーを DB・Secret(コンテナ向け)・ログに保存せず、プロキシの設定だけで扱う。kivra-agent は、起動時と各 Run の前に、コンテナに `ANTHROPIC_API_KEY` などの認証用の環境変数が無いことを検査し、あれば Run を始めない。`--bare` は使わない。
7. **CLI の実行は fail-closed に扱う。** タスクの本文(タイトル・説明)は非信頼の入力としてデータで渡す。Policy や許可リストを変える指示として扱わない。最終的な防御は `PatchPolicy` と `commitFiles` の構造的な保証であり、プロンプトではない。
8. **単一の attempt 実行。** claim は CAS(`lease_generation` を進める)で行い、結果と heartbeat は `lease_generation` が一致する場合だけ受け付ける(fencing)。PC が停止している間、attempt は QUEUED で待つ。PC は基本的に常時 ON とし、`queued_expires_at` は作成から 24 時間を初期値とする。期限を過ぎた attempt は EXPIRED とし、Run を hold する(古い指示で実行しない)。
9. **予算と上限。** 支出の最終防壁は Console 側の支出上限。kivra は stream-json の usage を集計して attempt の結果と一緒に記録する(PC 側の報告は改ざんされうるため、正本にはしない)。1 Run の時間、ラウンド数、出力量、差分の件数とサイズには上限を置く(数値は未決)。
10. **`api` 方式(`LlmDeveloper`)は残す。** 設定 `KIVRA_SELFDEV_RUNTIME` で切り替え、既定は `api` とする(承認後の実装で決める)。

## 理由

- 出力上限の問題は、CLI が作業ディレクトリを直接編集することで解消する。全文を LLM の出力で返す必要がなくなる。
- 複数ターンの作業(編集、テスト、修正)は、CLI を使うことで初めて可能になる。
- 差分を既存の `PatchPolicy` に通すため、CLI や agent が侵害されても、GitHub への書き込みは従来の検査を通る。
- pull 型のため、PC が NAT の内側にあっても動く。kivra から PC への経路を作らない。
- 使い捨てコンテナは、CLI の Bash が及ぶ範囲をホストから切り離す。ホストの鍵とファイルが見えない。
- 案 K は、API キーを公式が自動化向けに案内している方式であり、規約の解釈の曖昧さを避けられる。コンテナに認証を置かないため、コンテナ内の CLI が API キーを読めない。
- k8s の NetworkPolicy は、現行の CNI(Flannel)では効かない。ローカルでは、内部ネットワークとプロキシで外向き通信を強制できる。

## 代替案

- **案 L(サブスクリプションでの `claude login`)**: 採らない。Pro/Max の認証を自動実行に使うことの扱いが公式の記述から明確でなく、規約の曖昧さを残すため。
- **k8s のワーカー Pod(StatefulSet と PVC)**: 採らない。認証を PVC に保持するため、長期の認証情報をクラスタに置くことになる。また、NetworkPolicy が効かず egress を強制しにくい。PC が落ちている間は Run が QUEUED で待つ、という代償を受け入れる。
- **専用ホスト(常時稼働のサーバー、または専用 VM)**: 可用性と隔離の面で有利になりうる。運用の負荷が高いため、本 ADR では採らない。PC での運用が成り立たない場合の次の選択肢として残す。
- **API 方式の部分置換**: 提案を全文の PatchSet から差分だけに変える案。出力上限は避けられるが、複数ターンの作業とテストの実行ができない。採らない。
- **ホストで CLI を直接実行(コンテナなし)**: ホストの秘密が見える。不採用。承認済みのタスクだけに限る一時的な例外としてのみ検討する。
- **CLI に commit と push をさせる**: 資格情報が PC に入り、`PatchPolicy` を迂回する。不採用。
- **認証を Secret や DB に保存する**: 禁止。
- **docker.sock を渡す**: 不変条件 1 に違反する。不採用。

## 結果

- kivra 側: `coding_attempts`(想定 V14。expand のみ)、`CodingRuntimePort`、`/api/agent/*` の認可(JWT のロール・audience・client id を検証し、ヘッダーは信頼しない)、result の受付から `PatchPolicy` を経て既存の `commitAndOpenPullRequest` へつなぐ経路。
- Keycloak: ロール `kivra-agent`、PC ごとの client(client credentials)。ユーザーが管理作業を行う。
- ingress: kivra-dev は oauth2-proxy の背後にあり、`--allowed-role=admin-panel:admin` のため、agent の Bearer は通らない。決定: 現在の公開 HTTPS(kivra-dev.kigawa.net)に、`/api/agent/` の別経路を足す。VPN は使わない。kivra が JWT のロール・audience・azp を検証する。
- 新しいコンポーネント: PC 上の `kivra-agent`(JVM。配布、`systemd --user` のユニット、設定、停止の手段)、ホスト側の Anthropic プロキシ(認証ヘッダーを付与して `api.anthropic.com` へ転送)、実行イメージ(claude CLI、git、JDK、非 root)、許可リストのプロキシ。
- **ADR 0008 の改定(受け入れ済み)**: ADR 0008 は「kivra は生成コードを実行しない」「テストは CI のみ」としている。本 ADR により、テストは PC の隔離コンテナで行い、最終確認は CI で行う。ただし kivra 自身(kivra-app)が生成コードを実行することはない。この例外は kivra-agent の隔離コンテナ内の実行に限る。
- **#187 のコメントとの差**: #187 のコメントは「同じ OS ユーザーで `claude login` を行う」方式を示している。本 ADR は案 K(API キー、ホスト側プロキシ)に切り替えるため、そのコメントの認証の節は本 ADR で置き換える。
- 残存リスク:
  - 許可したドメインへの送信は防げない。ドメイン単位の許可では、許可先に秘密を入れて送ることを止められない。
  - ルートレス docker でも、コンテナ突破後の影響は専用 OS ユーザーの権限に及ぶ。ホスト上の専用ユーザーのファイルを読めないことは、不変条件 3 と専用ユーザーの設計で担保する。
  - 支出は Console の上限で止まるが、上限に達するまでの支出は発生しうる。
  - 利用規約の解釈(下記)。
- 運用: PC の稼働(常時 ON 前提)、スリープの扱い、API キーの更新、支出上限、client の失効、停止手順(STOP ファイル、systemd の停止、kivra 側の STOPPED)。

### 規約について(事実の範囲)

- 公式ドキュメントは、プログラムから組み込む開発者には API キー認証を案内している(研究の要約に基づく。原文の再確認は未実施)。
- OAuth(Claude.ai ログイン、Pro/Max)は「通常の利用」向けという記述があり、開発者が自分のプロダクトからそれを経由させることを許可しないという記述がある。
- 消費者向け規約 第 3 条は、API キー経由の場合を除き、automated / non-human な手段でのアクセスを扱っている。
- 案 K は、API キーを公式が自動化向けに案内している方式を使うため、規約の解釈の曖昧さを避ける選択である。本 ADR は規約上の適法性を断定しない。

## 未決の点

- **A3 の実機での成立**: コンテナ内の `claude` が、認証なしで `ANTHROPIC_BASE_URL` のプロキシを経由して起動し、応答を得られるか。PR 0 で検証する。成り立たない場合は、本 ADR の認証の決定を改める。
- **プロキシの許可ドメインの確定**: Gradle と Maven の取得先の正確な一覧。PR 0 で確認する。
- **Run の上限値**: 1 Run の時間(例: 30 分)、ラウンド数、出力量、差分の件数とサイズ。
- 実行イメージの詳細: claude CLI のインストール方法とバージョンの固定、JDK と Gradle のバージョン、配布先(Harbor か GitHub Release)。PR 10 で決める。
- `PatchPolicy` の上限の食い違い: `PatchPolicyConfig.maxFiles = 20` と、`DeveloperConstraints.maxFiles` の既定 10 を、PR 9(kivra 側の result 受付)で統一する。確定する実装課題として記載する。
- 既存の問題: `/api/agent/*` を含む Task API がヘッダーを信頼する問題(NetworkPolicy が効かないため、クラスタ内の Pod が偽ヘッダーで操作できる)は別 Issue で扱い、本 ADR の対象外とする。
- 未確認の事実: `--max-turns` の有無、`claude auth status` の出力、利用上限の挙動、oauth2-proxy の JWT Bearer 用のフラグ。

## 見直しの条件

- PR 0 で A3 が成り立たないと分かったとき。認証の決定を改める。
- 規約に関する新しい公式の記述が見つかったとき。
- 支出上限を超えて、Run の上限の見直しが必要になったとき。

## 実装状況

- 未着手。本 ADR の承認後、設計案 `design-coding-runtime.md` の §9 の順で、小さな PR に分けて進める。
