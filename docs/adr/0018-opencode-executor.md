# ADR 0018: コーディングの実行者に OpenCode CLI を使えるようにする(既定は Claude Code CLI。無料枠のモデルを前提に、設定で切り替える)

- ステータス: 提案(Proposed)。ユーザーの確認待ち。承認まで実装は始めない。本 ADR は設計のみで、コード、設定、k8s、エージェントの配備、proxy の許可は変えない
- 日付: 2026-10-19
- 関連: Issue #289(本 ADR の対象。要件は同 Issue の本文)。ADR 0010(コーディング実行基盤。本 ADR は決定 3 の `claude -p` の部分を、設定で選べるようにする)、ADR 0003(予算、`unpriced`、`acknowledgeUnpriced`)、ADR 0012(実行イベントと監視。OpenCode の出力を監視に写す案を §2 に書く)、ADR 0008(自己開発の元の設計)。取り下げられた案(ADR 0017 のサブスク認証、Issue #288 の MCP 実行者)には依存しない

## 背景

ADR 0010 では、コーディングの実行者として Claude Code CLI(`claude -p`)を、kivra-agent の使い捨てコンテナで動かしている。認証は WIF で、ホスト側の proxy が短命のトークンを付与する(決定 6)。費用は Anthropic の API の従量で、予算台帳に計上する(追補 4)。

この方式には、次の問題がある。

- **費用が事前に分からない。** 1 attempt の費用は、モデルと使用量で決まる。予約額(`KIVRA_AGENT_ATTEMPT_RESERVE_USD`、既定 0.50 USD)は実費の分布を集める前の推定である(ADR 0010 残件 3)。報告が欠けた attempt は予約額で計上し UNPRICED にする(追補 4)。UNPRICED が残ると、既存の予算ゲートが止まる。
- **ユーザーが Claude Code を使わないと決めた**(Issue #289、2026-10-19)。実行者を Claude Code 以外にする。

Issue #289 の決定は次のとおり。kivra-agent の既存のサンドボックス(rootless docker、認証情報なし、proxy 経由)で、`claude -p` の代わりに **OpenCode CLI** を動かす。OpenCode の `opencode` プロバイダの無料枠のモデル(`-free` の接尾辞など)を使えば、費用はゼロになり、未確定の予約(UNPRICED)の問題と、サブスクの規約の問題の両方がなくなる。

本 ADR は、この決定を設計に落とす。OpenCode の挙動は、公式ドキュメント(opencode.ai/docs の cli、permissions、zen、config、providers)だけを根拠にする。ローカルの `opencode` コマンドは実行していない。確認できなかった点は「未確認」と書く。

### 事実(コードと既存の ADR から確認した範囲)

- コマンドの組み立て: `kivra-agent/.../docker/CliCommand.kt` の `argv` は、`claude -p <PROMPT> --output-format stream-json --verbose --permission-mode dontAsk --no-session-persistence --max-turns <n> --model <m> --max-budget-usd <b> --allowedTools Read Edit Write Glob Grep Bash` の固定の形である。Bash を含む。タスクの本文は引数に入れず、`.kivra/task.json` から CLI が読む(PROMPT はそれを指示する固定文)。
- `DockerArgsGuard`(規則 9)は、このコマンドと完全に一致することを検査する。`--model` は `allowedModels`(既定は `claude-haiku-5-5` のみ)に限る。
- コンテナの起動は `agent-runtime/network/run-agent.sh` が行う。`--read-only`、`--cap-drop=ALL`、`no-new-privileges`、作業ディレクトリだけを rw でマウントし、`ANTHROPIC_BASE_URL=http://<prefix>-proxy:8080/anthropic` と、ダミーの `ANTHROPIC_API_KEY`(実キーではない値)を設定する。
- proxy(`agent-runtime/proxy/`)は 2 系統である。(a) `/anthropic/...` は `anthropic.go` の `anthropicPaths`(`/v1/messages` と `/v1/messages/count_tokens` の POST だけ)に限って転送し、認証(WIF)を付与する。(b) CONNECT は `allowlist.txt` の完全一致のホスト(`services.gradle.org`、`plugins.gradle.org`、`repo.maven.apache.org`)の 443 だけを通す。CONNECT はホストの単位で、パスを絞れない。
- 出力の解析: `StreamJsonParser` は `system`(`init`、`api_retry` の `error` 分類)と `result`(`subtype`、`is_error`、`total_cost_usd`、`permission_denials`)を読む。`MonitorEventExtractor` は、`assistant` の `tool_use`、`user` の `tool_result`(エラー)、`system`、`result` を監視イベントに写す(ADR 0012)。`CliOutcomeClassifier` は `error_max_budget_usd` を BUDGET_EXCEEDED にし、API のエラー分類を見る。
- 差分: `RealAttemptExecutor` は、作業ディレクトリをホストで展開し、ベースと比べて PatchSet を作る(ADR 0010 決定 4)。ホストは作業ディレクトリに対して git を実行しない。最終関門は kivra 側の `PatchPolicy` と `ChangeRules`(ADR 0010 追補 1)。
- 予算: `CodingCostWiring.kt` は、価格表を `KIVRA_SELFDEV_MODEL_REGISTRY_PATH` の registry から作る。registry が無ければ、すべてのモデルが unpriced になる。`CodingCostLedger` は、報告の usage を `coding:<attemptId>:<世代>` の鍵で一回だけ計上し、報告が欠けた attempt は予約額を UNPRICED で計上する(ADR 0010 追補 4)。為替が失効すると、claim は UNKNOWN_COST で拒否される。
- 送信先の規則: `kivra-model-gateway/.../Routing.kt` は、`DataClass.INTERNAL` のデータを `TrustZone.EXTERNAL` のプロバイダに送るとき、`dataHandling.status == CONFIRMED` を要求する(`DATA_HANDLING_UNCONFIRMED`)。ただし、この規則は `ModelGateway` のルーティングに適用される。コーディングの CLI 経路は ModelGateway を通らず、proxy を経由するため、この規則は現状の CLI 経路には掛かっていない(この点は未確認。コードの呼び出し関係を追って確認した結果であり、テストで確かめてはいない)。
- マイグレーション: main の最新は `V21__selfdev_memory.sql`。
- 実行者の設定: `KIVRA_SELFDEV_RUNTIME`(`api` または `cli`。既定は `api`)は `SelfDevSettings` で読む。`cli` は `KIVRA_AGENT_API_ENABLED=true` のときだけ許す(ADR 0010 追補 3)。実行者を選ぶ変数は、まだ無い。

## 決定

1. **実行者を設定で選べるようにする。** 新しい設定 `KIVRA_CODING_EXECUTOR=CLAUDE|OPENCODE` を、kivra(`kivra-app`)と kivra-agent の両方に置く。**既定は `CLAUDE`**。`CLAUDE` は現在の `claude -p` の経路(WIF)をそのまま使う。切り戻しは、この変数を `CLAUDE` に戻すことだけで済む。
   - 両方の側の設定を一致させる。不一致(kivra が `OPENCODE`、エージェントが `CLAUDE` など)は、claim の時点で拒否する。
   - `OPENCODE` の起動の条件は、§6 の予算の検査と、§4 の許可リストの検査を、すべて通すこと。
2. **`claude -p` の経路は削除せず残す。** 実行者の追加は、既存の経路を変えない。`CLAUDE` の経路のテストは、そのまま残す。
3. **OpenCode の実行は、`CliCommand` の並行の経路(`OpenCodeCommand`)として作る。** `claude` の argv を変えず、同じ規則(固定の形、タスク本文を引数に入れない、ガードの完全一致検査)を OpenCode の argv に対して作る。具体的な argv は PoC で確定する(§8)。
4. **既定の実行者は変えない。** この ADR の承認後も、PoC と E2E が通るまで、dev でも既定は `CLAUDE` とする。

### §2 `claude -p` に依存している箇所の洗い出しと、OpenCode での対応

| 箇所 | `claude -p` での役割 | OpenCode での対応 | 対応がない・未確認の点 |
|---|---|---|---|
| コマンドの組み立て(`CliCommand.argv`) | 固定の argv。`-p`、`--output-format stream-json`、`--permission-mode dontAsk`、`--no-session-persistence`、`--max-turns`、`--model`、`--max-budget-usd`、`--allowedTools` | `opencode run [message..]`。`--model/-m provider/model`、`--format json`、`--dir`、`--agent`(公式 CLI ページで確認)。許可は設定ファイルの `permission` で与える | `--format json` の**イベントの形は未確認**(ページは「raw JSON events」と書くだけ)。終了コード、stdin の扱い、実行時の上限時間は**未確認** |
| 指示の渡し方(PROMPT + `.kivra/task.json`) | CLI がファイルを読む | `opencode run` の message は固定文にし、`.kivra/task.json` を読ませる。同じ方式 | 同じ方式で成り立つかは PoC で確認 |
| 許可ツールの制限 | `--allowedTools` + `dontAsk`(許可外は拒否) | `permission` の値 `allow` / `deny` / `ask` を、キーごとに与える。既定は多くが `allow` のため、**明示の `deny` が必須** | `ask` が出たときの headless の挙動は**未確認**。`deny` だけにすれば出ない想定で、PoC で確かめる |
| 最大ターン数(`--max-turns`) | ターン数の上限 | **対応する設定は確認できなかった**(`max steps` / `max turns` の項目が無い。subagent の深さ `subagent_depth`(既定 1)は別物) | 上限なし。kivra-agent の**時間の上限**(docker の kill)で止める必要がある(既存の時間上限の値は本 ADR では未確認) |
| 費用の上限(`--max-budget-usd`) | 1 attempt の費用の上限 | **対応なし**。費用の上限の設定は確認できなかった | 無料枠のモデルに限れば費用は 0 のため、費用の上限は不要になる。有料モデルを使わないことは §6 の起動の検査で強制する |
| 出力のパース(`StreamJsonParser`、`CliOutcomeClassifier`) | `result` の `subtype` と `total_cost_usd`、`api_retry` の分類 | `--format json` の形が未確認のため、**パースの対応は作れない**。結果の分類は、終了コード(未確認)と、作業ディレクトリの差分の有無だけで行うことになる | 下の「縮退」を参照 |
| 監視イベント(`MonitorEventExtractor`、ADR 0012) | `tool_use`、`tool_result`(エラー)、`init`、`api_retry`、`result` を、ツールの呼び出しとして表示する | JSON イベントの形が分かるまで、**対応は作れない**。PoC でイベントの形を採取し、`tool_use` 相当があれば写す | 縮退時は、attempt の状態遷移(QUEUED / CLAIMED / RUNNING / 終端)だけを表示する(ADR 0012 の attempt の状態による表示) |
| 費用の報告(`usage`、追補 4) | `total_cost_usd` と `model` を kivra に送る | 無料枠のモデルでは費用は 0 だが、**報告値は改ざんされうる**(ADR 0010 決定 9)。正本は registry の価格表(§6) | — |
| モデルの固定(`DEFAULT_MODEL = claude-haiku-5-5`、`allowedModels`) | 1 モデルに固定 | `opencode` のモデル ID を、設定(`KIVRA_OPENCODE_MODEL`)で 1 つに固定する(§7) | 無料枠のモデルは入れ替わる。現在の一覧は公式の Free models の節に依る(固定しない) |
| 認証(WIF、`ANTHROPIC_BASE_URL`) | proxy が JWT を短命のトークンに交換して付与 | §4 のとおり、OpenCode 用の proxy 経路を新設する。認証は無料枠の仕組みに依る | **無料枠で認証が要るかは未確認**(§4) |
| 作業ディレクトリの設定ファイル | (本 ADR の範囲では未確認) | OpenCode は作業ディレクトリの `opencode.json` と `.opencode/` を読む(設定の優先順位の一つ)。**リポジトリ内の設定が、許可の設定を変えうる** | §3 で対策する(インラインの設定を最も優先させる) |

**縮退(イベントも結果の分類もできない場合)の扱い。** PoC で `--format json` の形が分からなければ、次のように作る。
- 結果は、PatchSet の検査(`PatchPolicy`)で決まる。変更が無ければ、ADR 0010 追補 6 の `EMPTY_PATCH` で報告する。終了コードが取れない場合でも、差分が PatchPolicy を通らなければ拒否される。安全側に倒れる(誤って通すことにはならない)。
- 失敗の理由は、固定の一般コード(`CLI_FAILED`)にする。OpenCode 固有の理由を推測で作らない。
- 監視は attempt の状態だけにする。イベントが無くても、Run の進行と安全の検査には影響しない。

### §3 サンドボックスの安全(最重要)

**前提。** OpenCode の `--auto` は、確認なしでツールを実行するモードである(公式: 「explicitly denied ではない権限の要求を自動で承認する」)。したがって、**サンドボックス(コンテナ、読み取り専用の root、認証情報なし、許可リストの proxy)が唯一の防壁**である。権限の設定は、防壁の一部にすぎない。

**OpenCode が持つツールと、既定の値(公式 permissions ページ)。**
- キー: `read`、`edit`(`edit`、`write`、`patch` を含む)、`glob`、`grep`、`bash`、`task`(subagent)、`skill`、`lsp`、`question`、`webfetch`、`websearch`、`external_directory`、`doom_loop`。`"*"` で全体の既定を置ける。
- 既定値: 多くは `allow`。`doom_loop` と `external_directory` は `ask`。`read` は `*.env` と `*.env.*` を拒否する(`*.env.example` は許可)。
- `bash` は、解析したコマンドのパターンで書ける。ルールは最後に一致したものが勝つ。

**本 ADR の許可の形(案。PoC で確認する)。**
```
"permission": {
  "*": "deny",
  "read": "allow",
  "edit": "allow",
  "glob": "allow",
  "grep": "allow",
  "bash": "deny",
  "task": "deny",
  "skill": "deny",
  "lsp": "deny",
  "question": "deny",
  "webfetch": "deny",
  "websearch": "deny",
  "external_directory": "deny",
  "doom_loop": "deny"
}
```
- **`ask` を使わない。** `--auto` は `ask` を含む未拒否の要求を承認する。そのため、許可しないものは `deny` で閉じる。`ask` は、headless での挙動が未確認のため使わない。
- **`--auto` を使わない**(推奨)。`allow` と `deny` だけなら、承認の要求は出ない想定である。PoC で、要求が出ないことを確認する。出た場合は、実行を失敗させる(承認しない)。
- **`bash` は既定で `deny`。** Claude 経路は Bash を持つが、OpenCode 経路では持たせない(Claude 経路より狭い)。テストの実行(Gradle)が要る課題は、フェーズ 2 で、`./gradlew <固定の形>` のような絞ったパターンを検討する。その場合も、Gradle が使う外部通信は §4 の許可リストに限られる。
- **設定はインラインで与える。** `OPENCODE_CONFIG_CONTENT`(公式の優先順位で 6 番目)で与えると、リポジトリ内の `opencode.json`(4 番目)と `.opencode/`(5 番目)より優先される。作業ディレクトリ内の設定が、許可を緩めることを防ぐ。ただし、管理された設定(7 番目)は、インラインより優先される。コンテナ内に管理設定を置かないことを、起動の検査で確かめる。
- **グローバルの設定を置かない。** `~/.config/opencode/opencode.json` は、コンテナの `HOME`(tmpfs)に作らない。`~/.local/share/opencode/auth.json`(`/connect` の資格情報の保存先。公式)も、コンテナに置かない。
- **リモートの設定を無効にする**(`.well-known/opencode` からの取得。優先順位の 1 番目)。無効化の設定名は**未確認**。PoC で、そのような取得の通信が出ないことを、proxy の拒否ログで確かめる。
- **自動更新を切る。** `autoupdate: false`(公式の設定項目)。イメージのバージョンを固定する。
- **`share`(セッションの共有)を切る。** 公式の設定ページでは `share` の項目を確認できなかった(未確認)。共有の通信が無いことを、proxy の拒否ログで確かめる。

**`--auto` の意味と危険性(公式の記述)。** 「明示の `deny` は、`--auto` でも効く」。`--auto` が効くのは、未拒否の要求だけである。したがって、`"*": "deny"` を基準にすれば、`--auto` を使っても許可外のツールは動かない。しかし、この ADR は `--auto` を使わない(上の方針)。

**kivra 側の関門(実行者によらず同じ)。**
- 結果は `submitResult` で受理の検査を受け、`PatchPolicy` を通ったものだけが保存される(ADR 0010 追補 3)。許可パス、拒否パス、サイズ、件数、秘密らしい内容、symlink、submodule、実行ビット、バイナリを検査する。**実行者の種類で、この検査を変えない**。
- Run 側は、base のスナップショットで `PatchPolicy` を再検査する(追補 3)。OpenCode の出力だからといって、この検査を省かない。
- `ChangeRules` と `PathRules` は、実行者の種類と無関係に適用する。
- **プロンプトインジェクション**: タスクの本文と Issue の本文は、データとして渡す(ADR 0010 決定 7、追補 5)。OpenCode が指示に従っても、出てくる差分は PatchPolicy を通るものに限られる。これが最後の砦であり、プロンプトに頼らない。

### §4 ネットワークと認証

**OpenCode の通信先(公式 zen ページ).** 無料枠のモデルの多くは `https://opencode.ai/inference/openai/v1/chat/completions`(npm パッケージ `@ai-sdk/openai-compatible`)を使う。Jev 1.13 系は `https://opencode.ai/inference/systemone/v1/systemone` を使う。モデル一覧は `https://opencode.ai/inference/v1/models`。

**proxy の許可の変更(案)。** CONNECT で `opencode.ai` を許可すると、ホスト全体の任意のパスに届く。これは `anthropic.go` の「パスを絞る」方針と合わない。よって、次の形にする。
- CONNECT の許可リスト(`allowlist.txt`)には `opencode.ai` を**足さない**。
- proxy に `/opencode/...` の経路を新設する(`/anthropic` と同じ形)。転送するのは `POST /inference/openai/v1/chat/completions` の 1 つだけとする。`/inference/v1/models` と `systemone` 系は、必要が PoC で確認されたときだけ足す。それ以外は 403 にする。
- 上流は `https://opencode.ai` とし、コンテナの `OPENCODE_CONFIG_CONTENT` の `options.baseURL` を `http://<prefix>-proxy:8080/opencode/inference/openai/v1` に向ける(`baseURL` の設定名は公式のプロバイダの項目による。確認済み)。

**認証(無料枠は、コンテナに実キーを置かずに成り立つか).**
- 公式の zen ページは、無料枠の認証の要否を**記載していない**(未確認)。公式の `/connect` は、サインインと API キーの貼り付けを案内する。
- **成立の条件**: (a) 無料枠が認証なしで呼べる、または (b) 無料枠の API キーがあり、それを**proxy が付与**する(コンテナには置かない。ADR 0010 の不変条件 4 と同じ)。
- (a) なら、proxy は何も付与しない。(b) なら、proxy に静的キーを置くことになり、ADR 0010 の代替案 K と同じ問題を抱える(長期の秘密が PC に増える)。**(b) の採否は運用者が決める**(§U-3)。
- **成立しない場合**: 無料枠が認証を要求し、キーをコンテナに置くしかないなら、本 ADR の OPENCODE 経路は採らない。CLAUDE 経路を続ける。
- `auth.json` は、コンテナに無いことを、起動の検査(§6)で確認する。コンテナ内に、`OPENCODE_` で始まる環境変数や、`*_API_KEY` の変数が無いことを確かめる。

**許可の範囲の最小化。**
- 追加する経路は、`POST /opencode/inference/openai/v1/chat/completions` の 1 つだけ。
- proxy の拒否ログに、OpenCode が試みたほかの通信(モデル一覧、更新、共有、リモート設定)が無いことを、PoC で確かめる。

### §5 データの扱い(重要)

**公式の記述(zen ページ、Free models の節と、データ利用の節)。**
- 一般の方針: 「Our providers follow a zero-retention policy and do not use your data for model training, with the following exceptions」(例外が付く)。
- 無料枠のモデルごとの記述:

| モデル(公式の ID) | データの扱い(公式の記述を要約) | 本 ADR の扱い |
|---|---|---|
| `big-pickle`、`exo-free`、`mimo-v2.6-flash-free`、`mimo-v2.5-free` | 無料期間中、収集したデータが**モデルの改善に使われうる**(During its free period, collected data may be used to improve the model) | **使わない**(入力を学習に使われうる) |
| `ll-alpha-free` | 同上。さらに、データを 30 日保持する | **使わない** |
| `beam-free` | 入力は学習に使わない。安全のため 30 日保持する。一部の国と地域では利用できない | 公開データに限り PoC で使いうる。本番は運用者の承認が要る(U-1) |
| `nemotron-3-ultra-free`、`nemotron-3.5-lightning-free`、`nemotron-3.5-super-vl-free`(NVIDIA のエンドポイント) | 試用のみ。個人情報や機密を送らないこと。利用は安全と NVIDIA の製品改善のためにログされる | **使わない**(機密の入力を送らない前提が、kivra の用途と合わない) |
| `glm-5.3-flash-free`、`deepseek-v4.1-flash-free`、`minimax-m3.1-flash-free`、`longcat-2.5-preview-free` | 提供元はゼロ保持の方針で、学習に使わないと記載(保持期間の記載は無い) | **PoC の候補**。ただし提供元の記述の原文を、運用者が確認して登録する(U-1) |
| `jev-1.13-free` | 公式ページは詳細を提供元の文書に委ねる | **未確認。使わない** |

- 無料枠のモデルの利用は、公式に「for a limited time」と書かれており、終了日は示されていない。

**kivra の入力の分類。**
- kivra のリポジトリは公開である(ただし、将来の非公開リポジトリは別)。
- タスクの本文(title、body、Issue の本文)は、リポジトリの公開の有無と無関係に、利用者が書いた内容を含みうる。**`INTERNAL` として扱う**。
- 既存の規則(`Routing.kt`)は、`INTERNAL` を `EXTERNAL` のプロバイダに送るには `CONFIRMED` を要求する。本 ADR では、この規則の精神を OpenCode 経路にも当てはめる。すなわち、`dataHandling` が `CONFIRMED` のモデルだけを、OPENCODE 経路で使う(§6 の起動の検査)。
- `CONFIRMED` の登録は、運用者がプロバイダの記述を確認して行う。`confirmedBy`、`confirmedAt`、`sourceUrl` を記録する(既存の registry の形)。

**前提(運用者の確認)。** 無料枠を使う前に、運用者が、使うモデルのプロバイダのデータの扱いを確認して承認する。確認の結果を registry の `dataHandling` に `CONFIRMED` として記録する。確認できないモデルは、使わない。この ADR は、データの扱いを**断定しない**。確認は運用者の作業である(U-1)。

### §6 予算

**無料枠のモデルの費用は 0。** ただし、「0」は、価格表(registry)で 0 と確定しているときに限る。

- **起動の検査(kivra-app)**: `KIVRA_CODING_EXECUTOR=OPENCODE` のとき、次をすべて満たさなければ起動を拒否する(変数名と理由だけを出し、値は出さない。ADR 0010 追補 3 と同じ形)。
  1. `KIVRA_OPENCODE_MODEL` が、registry に**あり**、価格が 0 と確定していること。
  2. その registry の `dataHandling.status` が `CONFIRMED` であること(§5)。
  3. 為替の設定は要らない(費用が 0 のため)。ただし、起動時の検査で、他の attempt(CLAUDE 経路)のための為替が失効していても、それは CLAUDE 経路の claim を止めるだけである。
  - 有料のモデル、registry に無いモデル、価格が不明なモデルは、**拒否する**(未知の費用を許さない)。
- **agent 側の検査(kivra-agent)**: `KIVRA_OPENCODE_MODEL` を 1 つに固定する。`allowedModels` 相当の一覧は、このモデルだけとする。
- **claim の検査**: OPENCODE 経路の attempt は、予約額を取らない(予約 0)。これにより、**未確定の予約(UNPRICED)が、この経路では生じない**。`BudgetPort.admission` と `CodingCostLedger` の入場制御は、CLAUDE 経路と同じ関数を通すが、予約額が 0 のとき、入場は通る。
- **報告(usage)**: OPENCODE 経路の結果や fail に、`usage` を付けてもよい。その場合、`totalCostUsd` は 0 とし、kivra は**報告の model が起動時のモデルと一致すること**を検査する。一致しなければ、usage を無視して、予約額で計上し UNPRICED にする(安全側。追補 4 の規則を流用する)。
- **例外の扱い**: 報告が欠けた OPENCODE の attempt(usage の無い fail、ORPHANED、EXPIRED)は、予約額を計上する。ただし OPENCODE 経路では予約額を 0 にするため、計上は 0 になり、UNPRICED は立たない。**この扱いは、無料枠の費用がゼロであることを前提とする。** 前提が崩れた(モデルの価格が変わった)ときは、CLAUDE 経路と同じ予約額に戻す(設定の変更が要る)。
- **`executor` の記録(列の追加)**: `coding_attempts` に `executor`(`CLAUDE` / `OPENCODE`)を足す。expand-only のマイグレーションで、既存の行は `CLAUDE` とする(既存の行の意味を変えないため)。マイグレーションは V22(main の最新は V21)。監査と集計で、経路を区別するために必要である(U-9)。
- **Planner の予算は変えない。** 計画(`ModelGateway`)の予算は、従来どおり API 経路の予算で管理する。

### §7 品質とモデルの選択

- **モデルの固定。** `KIVRA_OPENCODE_MODEL` で 1 つに固定する。無料枠の一覧は入れ替わるため、固定の一覧をコードに書かない。切り替えは、設定の変更と、§6 の起動の検査の通過で行う。
- **品質の前提。** 無料枠のモデルは、重要なロジックに向かないとされる(Issue #289 の要件)。本 ADR は、この品質の低さを**前提**とする。
  - 適用範囲: 1 回の変更は小さく(`allowedPathPatterns` で、docs、README、設定ファイルの一部などに絞る。範囲は PoC の後に決める)。
  - 人のレビュー(PR のマージ)が最後の砦である。自動マージ(ADR 0014 の提案)の対象に、OPENCODE 経路の PR を入れない。
- **失敗の扱い。** 失敗の理由は、既存の固定コード(`CLI_FAILED`、`OVERLOADED`、`BUDGET_EXCEEDED`、`EMPTY_PATCH`)に写す。OpenCode の固有の理由を新設しない(イベントの形が未確認のため)。
- **ブレーカーとの関係。** 連続の失敗が続いたら、OPENCODE 経路の claim を止める(運用者が再開する)。既存のブレーカーの仕組みを使うか、新しく作るかは未確認で、実装の前に確認する(U-5 の一部)。
- **モデルの廃止(消えた)ときの分類。** 廃止されたモデルの呼び出しは、HTTP のエラーとして返ると想定される(公式の記述は未確認)。エラーの分類(モデルが無い、とどう分けるか)は、PoC で採取した実際のエラーに基づいて決める。推測で分類しない。廃止は `CLI_FAILED` に写し、運用者が `KIVRA_OPENCODE_MODEL` を差し替える。
- **無料の期限。** 公式の「for a limited time」は終了日を示さない。終了は、OPENCODE 経路の失敗が続くことで気づく。運用者は、PoC の成功を定期的に確認する(手順を運用メモに残す)。

### §8 PoC と段階的な導入

**フェーズ 0: PoC(実装の前。運用者の PC の dev で行う。コードは変えない)。**

目的: 小さなタスク(docs の 1 行の修正)を、OpenCode で完走できるか。**確認すること**: (1) 非対話で完走し、終了コードが取れるか、(2) `--format json` の出力の形、(3) 許可の設定(§3)で、承認の要求が出ないか、(4) 無料枠に認証が要るか、(5) 通信先(`opencode.ai` の経路)、(6) 差分が生成され、PatchPolicy に通る形か、(7) 出力の形と、エラーの形。

手順(秘密を含めない。値は書かず、変数名だけ書く):
1. 作業ディレクトリを、使い捨てで作る(リポジトリの `git archive` の展開物。`/tmp` などの新しい空のディレクトリ)。その中に、docs の 1 行の修正を依頼する `.kivra/task.json` を置く(題名と本文は固定の文)。
2. OpenCode を入れた検証用のイメージを作る(本番のイメージは変えない)。インストールの方法と版の固定は**未確認**。ハッシュの検査は、既存の `agent-runtime/image/Dockerfile` の Claude と同じ方式を目指す。
3. 検証用の proxy の許可に、**PoC の間だけ**、`opencode.ai` を CONNECT で一時的に足す(ホスト単位で広いが、PoC の間だけ、ログで通信先を採るため。コミットしない)。拒否ログと許可ログで、通信先を記録する。
4. `agent-runtime/network/run-agent.sh <作業ディレクトリ> <OpenCode の argv>` の形で実行する(同じ隔離フラグ)。argv は `opencode run --format json -m <provider/model> --dir /work <固定の message>` を基本とし、許可は §3 の設定をインラインで与える。
5. 終了コード、標準出力(JSON の行)、標準エラーを、**秘密を含まない形で**保存する(設定の内容、トークン、環境変数の値は出さない。ADR 0011 のログの衛生に従う)。
6. 差分を作業ディレクトリの外で比べ、PatchSet の形にする(`DiffCollector` 相当)。
7. 終了後、一時的な許可を外す。`docker ps` で、残ったコンテナが無いことを確認する。

**フェーズ 1: kivra-agent の実行者の切り替え(dev のみ。既定は CLAUDE)。** `OpenCodeCommand`、設定の検査、proxy の `/opencode` 経路、テスト(フェイクの CLI による)。CLAUDE 経路のテストは変えない。**進んでよい条件**: PoC の (1)〜(6) が確認でき、proxy の拒否ログに想定外の通信が無いこと。

**フェーズ 2: kivra-app の結線(dev のみ)。** `KIVRA_CODING_EXECUTOR`、起動の検査(§6)、`executor` 列(V22)、予約 0 の扱い。**進んでよい条件**: フェーズ 1 のテストが通り、registry に `CONFIRMED` のモデルが登録されていること。

**フェーズ 3: dev の E2E。** docs の 1 行の修正を、kivra から OPENCODE 経路で流し、PR が作られることを確認する。PR は人がレビューしてからマージする。**進んでよい条件**: 3 件連続で、PatchPolicy を通る PR が、レビューの修正なしで作れること(件数は運用者が決める)。

**フェーズ 4: 既定の判断。** 既定を変えるかは、ユーザーが決める(U-4)。この ADR は既定を変えない。

**切り戻し。** `KIVRA_CODING_EXECUTOR=CLAUDE` に戻すだけで、CLAUDE 経路に戻る。OPENCODE 経路の attempt は、そのまま残る(監査のため)。V22 の列は、切り戻しでも残す(expand-only のため、削除しない)。

### §9 脅威の検討

| 脅威 | 経路 | 対策 |
|---|---|---|
| プロンプトインジェクション(Issue の本文や、リポジトリの内容による指示) | タスクの本文、リポジトリの文章 | データとして渡す(ADR 0010 決定 7)。最終の関門は `PatchPolicy`、`ChangeRules`、`PathRules`、Run 側の再検査(実行者によらない)。許可パスを絞る |
| サンドボックスの脱出 | コンテナの脆弱性、OpenCode の不具合 | ADR 0010 の不変条件 1〜7 をそのまま使う。読み取り専用の root、cap-drop、認証情報なし、docker.sock なし |
| 許可の設定の迂回(リポジトリ内の `opencode.json` や `.opencode/` で許可を緩める) | 作業ディレクトリの設定ファイル | インラインの設定を最優先で与える(公式の優先順位 6 > 4、5)。管理設定を置かない。起動の検査で、グローバルの設定が無いことを確認。PatchPolicy は、`opencode.json` や `.opencode/` への変更を拒否する(拒否パスに入れる。PoC で範囲を確認) |
| ネットワークの許可の拡大による持ち出し | `opencode.ai` の許可、CONNECT の広がり | CONNECT に入れない。`/opencode` の経路で、1 つのパスだけを通す。拒否ログの監視。ただし、**許可した先に秘密を送ることは防げない**(ADR 0010 の残存リスクと同じ) |
| 無料枠のプロバイダへの情報の漏えい | 入力(タスクの本文、リポジトリの内容)がプロバイダに届く | §5。学習に使われうるモデルは使わない。`CONFIRMED` のモデルだけ。公開リポジトリの内容でも、タスクの本文は `INTERNAL` 扱い |
| モデルの入れ替わりによる挙動の変化 | 無料枠の入れ替わり、提供元の更新 | モデルを固定(§7)。PoC の再確認。PR の人のレビュー |
| `--auto` による意図しない操作 | 承認の自動化 | `--auto` を使わない。`ask` を使わず、`allow` と `deny` だけにする(§3) |
| 実行時間の暴走(最大ターンの設定が無い) | 無限の作業 | kivra-agent の時間の上限(docker の kill)で止める。`doom_loop` は `deny` |
| subagent による権限の迂回 | `task` の利用 | `task` を `deny` にする。subagent の深さ(`subagent_depth`、既定 1)は、`task` の拒否で使われないことになる |
| 自動更新や共有による外部への送信 | `autoupdate`、`share`、リモート設定 | `autoupdate: false`、リモート設定を無効化(設定名は未確認)、`share` の無効化(未確認)。PoC で拒否ログに出ないことを確認 |
| 資格情報の混入 | 環境変数、`auth.json` | コンテナに置かない。起動の検査で、`OPENCODE_` と `*_API_KEY` を含む環境変数を拒否する |

### §10 未決事項

- **U-1: データの扱い(学習への利用)の確認。** 運用者が、使うモデルのプロバイダの記述を確認し、registry に `CONFIRMED` として記録する。**推奨**: 「学習に使わない」と明記され、保持期間が示されたモデルだけを候補にする。現時点の候補は `glm-5.3-flash-free`、`deepseek-v4.1-flash-free`、`minimax-m3.1-flash-free`、`longcat-2.5-preview-free`(いずれも公式の記述は「ゼロ保持」)。**原文の確認は未実施**。
- **U-2: 使うモデルの選定。** **推奨**: 上の 4 つのうち PoC で完走したものを 1 つ選ぶ。品質の比較は、PoC の範囲では行わない(サンプルが少ないため)。
- **U-3: 認証と API キーの扱い。** 無料枠が認証なしで呼べるか。**推奨**: 認証なしで呼べる場合だけ OPENCODE 経路を進める。キーが要る場合は、proxy に置く案を、ADR 0010 の代替案 K と同じ理由で採らず、OPENCODE 経路を断念する。
- **U-4: 既定の executor と、CLAUDE 経路を残す期間。** **推奨**: 既定は `CLAUDE` のまま。フェーズ 3 の条件を満たしてから、運用者が既定を変えるか決める。CLAUDE 経路の削除は、本 ADR の範囲外とし、既定の変更の後も残す。
- **U-5: ブレーカーの扱い。** 既存のブレーカーを、OPENCODE 経路に使うか、経路ごとに分けるか。**推奨**: 経路ごとに分ける(一方の失敗が他方を止めないため)。既存の仕組みを確認してから決める(未確認)。
- **U-6: proxy の許可の範囲。** **推奨**: `/opencode/inference/openai/v1/chat/completions` の 1 つのパスだけ。CONNECT の `opencode.ai` は追加しない(PoC の一時的な許可を除く)。
- **U-7: bash の許可。** **推奨**: フェーズ 1 〜 3 では `deny`。Gradle の検証が要るなら、フェーズ 3 の後に、固定の形のパターンを検討する。
- **U-8: `--auto` を使うか。** **推奨**: 使わない。`allow` と `deny` だけで、承認の要求が出ないことを PoC で確認する。
- **U-9: `executor` 列の追加。** **推奨**: 追加する(V22、expand-only。既存の行は `CLAUDE`)。経路の区別は、監査と集計に必要。
- **U-10: OpenCode のバージョンの固定と配布。** **推奨**: 版を固定し、Claude の Dockerfile と同じく、取得したバイナリ(または npm パッケージ)のハッシュを検査する。配布元と検査の方法は PoC で確認する(未確認)。
- **U-11: 時間の上限。** **推奨**: kivra-agent 側で、OPENCODE 経路の 1 attempt の時間の上限を、既存の値から決める(具体値は PoC の所要時間を見て決める)。

## 理由

- **費用が、構造的にゼロになる。** 無料枠のモデルでは、予約と UNPRICED の問題が、この経路では生じない(§6)。予算の不確実さを、設定の検査で止める。
- **既存の安全の構造を変えない。** コンテナの隔離、proxy の許可、PatchPolicy の最終関門は、実行者によらず同じ。実行者を変えても、防壁は変わらない。
- **切り戻しが設定だけで済む。** CLAUDE 経路を残すため、問題が起きても、設定の変更で戻せる。
- **OpenCode のツールは、Claude 経路より狭くできる。** `bash` を既定で拒否できる(Claude 経路は Bash を持つ)。権限の表現が、キーごとの `deny` で書ける。

## 代替案

- **Codex CLI。** 採らない。Issue #289 が OpenCode を指定している。Codex は OpenAI の認証が要る。無料枠の前提が成り立たない(公式の確認は本 ADR の範囲外)。
- **kivra 自身のエージェントループ(API 経路を、ツール付きで回す)。** 採らない。Issue #289 の決定に反する。実行環境の隔離(コンテナ内のシェル)を、kivra 自身が再実装することになり、ADR 0010 の隔離の不変条件を新しく作り直す必要がある。
- **有料モデルを OpenCode 経由で使う。** 採らない。費用が事前に分からない。予約と UNPRICED の問題が残る。
- **OpenCode を CONNECT のホスト全体で許可する。** 採らない。パスを絞れず、`anthropic.go` の方針と合わない(§4)。
- **OpenCode をホストで直接実行する。** 採らない。ADR 0010 の不変条件に反する(ホストの秘密が見える)。
- **OpenCode の共有機能(`share`)を使う。** 採らない。セッションの外部への共有は、目的に反する。
- **CLAUDE 経路だけを続ける。** 採らない。Issue #289 の決定に反する。ただし、CLAUDE 経路は既定のまま残す。

## 確認できなかったこと(未確認)

- `opencode run --format json` のイベントの形、終了コード、エラーの出し方、stdin の扱い、非対話の実行時の時間の上限(公式の CLI ページに記載なし)。
- `ask` が headless で出たときの挙動(公式に記載なし)。
- 無料枠のモデルが認証を要するか(公式の zen ページに記載なし)。
- 無料枠のレート制限(公式に記載なし)。
- 無料枠の終了日(公式は「for a limited time」と書くのみ)。
- リモート設定(`.well-known/opencode`)を無効にする設定名、`share` を無効にする設定名(公式の設定ページに記載なし)。
- `autoupdate` の値(`false`)は公式の設定ページに記載があるが、コンテナ内での実際の挙動は未確認。
- OpenCode のインストール方法と版の配布先(公式の CLI ページで確認していない。PoC で確認する)。
- 廃止されたモデルのエラーの形(公式に記載なし)。
- 現行の kivra-agent の、docker の時間の上限の具体値(本 ADR では読んでいない)。
- 無料枠のプロバイダのデータの扱いの原文(§5 は公式ページの要約。原文の確認は運用者の作業)。
- 本 ADR の各 CLAUDE 経路の挙動は、コードの読解に基づく。テストでの再現は行っていない。
- ローカルの `opencode` コマンドは、実行していない(設定とネットワークに触れる可能性があるため)。
</content>
