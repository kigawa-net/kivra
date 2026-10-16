# ADR 0008: 自己開発の最小ループは PR 作成までを自動化し、テストは CI、マージは人間が行う

- ステータス: 採用(Accepted)
- 日付: 2026-10-15
- 関連: Issue #56(Self-Development Integration Runner)、#41(M8 Epic)、#59(Saga / Stage 遷移)、#70(Acceptance Harness)、#60(External Dispatch)、#62(Recovery Queue)、#49 / #50 / #53(Evidence・Eligibility・PR 照合)、#34(GitHub Adapter)、#71(Provider Compatibility Matrix)、ADR 0001(Run の正本)、ADR 0004(BudgetPort が予算の正本)、ADR 0005(結果の分類と UNKNOWN)、ADR 0007(Policy は読み取り専用)

## 背景

M8 の目標は「Kivra 自身の開発を、Task から PR まで自動で進める」ことである。ただし、設計 Issue(#56、#41、#59、#70 など)は
Saga・Dispatch Proof・Recovery Queue・Circuit Breaker・Fortis 移行まで含み、Walking Skeleton の完了までに多くの前提が要る。
この ADR は、その中で **最小の縦切り**(PR を作るところまで)を先に確定し、後続の実装を小さな PR に分けられるようにする。

既存のコードの前提は次のとおり(2026-10-15 時点の origin/main を読んだ結果)。

- `DevelopmentRunState`(`kivra-core/.../States.kt`)には `PULL_REQUEST` と `WAITING_APPROVAL` があり、`prOnlyCompletionSources` は `{PULL_REQUEST, WAITING_APPROVAL}` である。COMPLETED へは `completePrOnlyRun` だけで到達する(汎用の `transition()` では到達しない)。
- `DbRunAdmission`(`kivra-app/.../db/DbRunAdmission.kt`)は本番に未配線で、`policy` が null の間は全 Task を拒否する(fail-closed)。
- `OperationJournalRepository` と `OperationStatus`(PREPARED / IN_FLIGHT / UNKNOWN / RETRYABLE / RECONCILIATION_REQUIRED / CONFIRMED / FAILED)は存在する。
- `GitHubPort`(`kivra-github/.../GitHubPort.kt`)には `ensureRunBranch`、`ensurePullRequest` などがあり、merge は**インタフェースに無い**(テストで固定)。`commitFiles` は未実装。
- `ModelGateway`(`kivra-model-gateway`)は `RequestLedger` を要求し、`BudgetPort` の予約・確定を行う。DB の永続的な予算台帳は V10(`durable_budget_ledger`)にある。
- 本番で有効な外部 Capability は 0 件である(`docs/provider-compatibility-matrix.md`)。GitHub と Anthropic は「無効(未結線)」である。
- Acceptance Harness(`kivra-app/src/test/.../acceptance/`)には、障害系のシナリオ(Concurrent Admission、Intent crash、Response lost、Old Worker delayed POST)があるが、**Happy path(#70 のシナリオ 1)はまだ無い**。
- 最新のマイグレーションは V11 である。次は V12 になる。

## 決定

1. **最小の自己開発ループ**は次のとおりとする。
   Task →(Admission: `DbRunAdmission`)→ Run →(**Developer**: LLM が `PatchSet` を提案。`ModelGateway` 経由、予算の予約・確定つき)→ (**`PatchPolicy`** による検証: パス・許可リスト・拒否リスト・サイズ・秘密らしい内容。fail-closed)→ (**GitHub API**: `kivra/run-<runId>` ブランチを作り、`commitFiles` で 1 コミットを書き、`ensurePullRequest` で PR を作る)→ **テストは GitHub の CI が実行**(kivra は生成コードを実行しない)→ **人間がレビューしてマージ**(kivra はマージしない)→ kivra がマージを検証し、Run を `PR_ONLY` で COMPLETED にする。

2. **exkes(実行環境)は MVP では使わない**。kivra は生成されたコードを実行しないので、実行環境の隔離が要らない。テストは GitHub の CI に任せる。exkes は、ローカルでのテスト実行や複数ステップのエージェントが必要になった段階で導入する(見直しの条件を参照)。

3. **安全性は多層で構造的に保証する**。プロンプトには頼らない。
   - (a) `PatchPolicy`(`kivra-core`)は次のパスを拒否する: `.github/`、`gradle/`、Flyway のマイグレーション(`db/migration/`)、`docs/adr/`、Policy・認証・予算の中核、`CLAUDE.md`、`.env*`、鍵ファイル。拒否はファイル単位で、1 つでも違反すれば PatchSet 全体を拒否する。
   - (b) `commitFiles`(`kivra-github`)は `kivra/run-<runId>` 以外のブランチに書かない(`main` に書けない)。`force=false` とし、既存 ref を更新しない。
   - (c) kivra の GitHub トークンの権限は、対象リポジトリの `contents:write` と `pull_requests:write` のみとする。`administration` と `workflows` は付けない。**ブランチ保護で `main` への直接 push を禁止する**。ただし、2026-10-15 時点では `kigawa-net/kivra` の `main` に Ruleset もブランチ保護も無い(API で確認: Ruleset 0 件、`branches/main/protection` は 404)。書き込み権限のあるトークンがあれば、`main` に直接 push も force push もできる。保護は `kigawa-net/infra` の Terraform(`platform/github/variables.tf` の `var.repositories`)で管理する。`kivra` はそのリストに無かったため、kigawa-net/infra#295 と PR kigawa-net/infra#296(ドラフト)で追加する。
     - **前提(自己開発を有効にする前に満たす)**: kigawa-net/infra#296 がマージされ、`terraform apply` で `kigawa-net/kivra` の `main` にブランチ保護が付いていること。既定の保護は PR 必須・承認 0 件・force push 禁止・削除禁止、`enforce_admins = false`。
     - **`enforce_admins = false` のため、管理者の権限を持つトークンは保護を迂回できる。** kivra に渡す GitHub のトークンは、管理者の権限を持たない専用の細粒度の PAT(`kigawa-net/kivra` の `contents:write` と `pull_requests:write` のみ。`administration` なし、`workflows` なし)にする。
   - (d) **人間のマージが必須**。kivra は承認・マージの API を呼ばない。`GitHubPort` に merge が無いことはテストで固定済みであり、これを維持する。保護(infra#296)の既定は承認 0 件であり、この場合「人間がマージする」は構造的な強制ではなく、運用による(マージ権限を持つのは人間のみ、という前提に依存する)。承認 1 件の必須化は、ユーザーの判断とする。
   - (e) **キルスイッチ**: 環境変数 `KIVRA_SELFDEV_ENABLED`(既定 `false`)で無効化できる。有効化は能力ゲート(`docs/provider-compatibility-matrix.md` の手順)を通してから行う。
   - (f) **予算**: LLM の呼び出しは `BudgetPort` の予約が必須とする(月 1 万円。超過・価格不明は拒否する = fail-closed)。Run ごとの上限も持つ。予算の正本は ADR 0004 に従い `BudgetPort` とする。
   - (g) **1 Run につき、変更案の提出(PR 作成)は 1 回**とする。自動の再試行で PR を増やさない。`ensurePullRequest` の冪等性(run 専用ブランチ名と head による照合)に依存する。
   - (h) 失敗・結果不明は `UNKNOWN` に保留し、盲目的に再送しない(ADR 0005)。LLM の呼び出しも、送信後に結果が不明なら再送しない。

4. **対象リポジトリ**は、最初は `kigawa-net/kivra` 自身のみとする(許可リストの設定で 1 つに限定する)。他のリポジトリへの拡張は、別の決定とする。

5. **Developer の入力**は、タスクのタイトルと説明、対象リポジトリの該当ファイル(GitHub API で読む。サイズの上限つき)とする。
   **タスクの本文は非信頼の入力として扱う。** LLM へのプロンプトでは、指示とデータを区別し(データは明確な区切りで囲む)、タスクの本文が Policy・許可リストを変更する指示として解釈されないようにする。最終的な防御は `PatchPolicy` と `commitFiles` の構造的な保証であって、プロンプトではない。

6. **Run の状態遷移**: 既存の `DevelopmentRunState` に、次の状態を足す設計とする。実装は別 PR で行う。

   | 状態 | 種別 | 意味 | 既存との対応・補足 |
   |---|---|---|---|
   | CREATED | 既存 | Run 作成直後 | 自己開発では CREATED → DEVELOPING へ直接遷移させる遷移を追加する(現在は PLANNING を経由) |
   | DEVELOPING | 既存 | LLM 呼び出し中 | 既存の値を再利用 |
   | PATCH_READY | **追加** | PatchSet が PatchPolicy を通過し、コミット待ち | 検証の結果を保持する状態 |
   | PULL_REQUEST | 既存 | PR 作成済み。人間のレビュー待ち | 要求文の「PR_OPEN」に相当。名称変更は既存の `prOnlyCompletionSources` と遷移表の変更を伴うため、**既存の `PULL_REQUEST` を再利用する** |
   | COMPLETED | 既存 | マージを検証済み | `completePrOnlyRun` のみで到達 |
   | CANCELLED | 既存 | PR が閉じられた(マージされずに)、またはタイムアウト(`PULL_REQUEST -> CANCELLED`) | 既存の終端。理由コード `PR_ABANDONED` を監査(audit)の payload に残す。`ABANDONED` は追加しない(下記) |
   | FAILED | 既存 | `PatchPolicy` で拒否、予算超過など | 理由コードのみを記録する(本文や秘密は残さない) |
   | UNKNOWN | **追加** | 外部の結果が不明。照合(#62)へ | 非終端。自動の再送はしない |

   - 既存の `PULL_REQUEST` を再利用するため、`WAITING_APPROVAL` は自己開発の経路では使わない。
   - `ABANDONED` は追加しない。PR が閉じられた/タイムアウトは `CANCELLED` + 監査の理由コード `PR_ABANDONED`。理由: `ABANDONED` を終端にするには、V1 の生成列 `active_flag` の終端一覧を変え、一意キー `uk_dev_runs_active_task` を作り直す必要があり、適用中に一意性の保証が外れる窓ができるため(2026-10-15 の決定)。
   - `UNKNOWN` は active(照合まで保持)。出る経路は、運用者の監査つきの明示操作と、照合(#61)で設計する。`PATCH_READY` への遷移は `PR_ONLY` の Run だけ。
   - **既存の SIMULATED の Run(`/api/tasks`)の挙動は変えない**。自己開発は、フラグ(`KIVRA_SELFDEV_ENABLED`)が有効で、対象(`target`)の指定がある場合のみ動く。

7. **MVP でやらないこと**: exkes での実行、ローカルでのテスト実行、複数ステップのエージェント、複数リポジトリ、自動マージ、Flyway のマイグレーションの生成、ワークフロー(`.github/`)の変更、依存関係の追加(`*.gradle.kts`)、ADR・設計判断の変更。

8. **見直しの条件**:
   - 変更案の品質が低い場合(例: 直近 20 件の PR の CI の通過率が 50% 未満)は、プロンプトと文脈の収集を見直す。
   - exkes の導入は、ローカルでテストを実行したくなったときに検討する。

## 結果

- MVP の完了は「GitHub の PR が作られ、人間が GitHub でマージし、kivra がそのマージを照合して `PR_ONLY` で COMPLETED にする」ことである。
- 自己開発の経路は既定で無効。有効化は能力ゲート経由に限る。
- 安全性の根拠は、プロンプトではなく `PatchPolicy`、`commitFiles` の制約、GitHub 側の権限と、ブランチ保護(infra#296 の適用後)にある。
- ⑤ に Flyway V12 は無い。`ABANDONED` を追加しないため、`development_runs.state` の制約と V1 の `active_flag` は変えない(`PATCH_READY` / `UNKNOWN` は非終端なので active 扱い)。

## 追補(2026-10-16): GitHub 認証は App を推奨し、PAT は互換として残す

- GitHub への認証は、**GitHub App の installation token を既定**とする。PAT(`KIVRA_SELFDEV_GITHUB_TOKEN`)は後方互換のために残す。
- App の権限は `contents:write` / `pull_requests:write` / `metadata:read`(GitHub が自動で付与する読み取り)のみとする。インストール先は `kigawa-net/kivra` のみとする。token 発行の要求も、そのリポジトリ 1 つに限定する。
- App の秘密鍵は k8s Secret で渡す(`KIVRA_SELFDEV_GITHUB_APP_PRIVATE_KEY` はファイルパス優先。PEM 本文も受ける)。実値は文書・ログ・例外に書かない。
- 認証の方式は PAT か App のどちらか一方だけを設定する。両方、またはどちらも無いときは起動に失敗する(fail-closed)。
- installation token は期限の 5 分前まで再利用し、同時の要求は 1 回の発行にまとめる。401 では token を破棄して 1 回だけ再取得する。

## 追補(2026-10-16): Anthropic の認証は Workload Identity Federation を推奨し、静的キーは代替として残す

- Anthropic への認証は、**Workload Identity Federation(WIF)を推奨**する。k8s の projected ServiceAccount トークン(JWT)を `POST /v1/oauth/token` で Anthropic アクセストークンに交換し、Messages API には `Authorization: Bearer` で送る。`x-api-key` は送らない。静的キー(`KIVRA_SELFDEV_ANTHROPIC_API_KEY`)は代替として残す。
- 設定は一方のみ(fail-closed)。WIF は `KIVRA_SELFDEV_ANTHROPIC_FEDERATION_RULE_ID` / `_ORGANIZATION_ID` / `_SERVICE_ACCOUNT_ID` / `_IDENTITY_TOKEN_FILE` の 4 つが必須、`_WORKSPACE_ID` は任意。トークンファイルの存在は起動時に検査しない(Pod の起動順で遅れうるため、交換時に読む)。
- OAuth のスコープは `workspace:inference` で足りる。JWT の `jti` は既定で 1 回限りのため、同じ JWT は再送しない。交換のたびにファイルを読み直し、ローテーションを待つ。失敗時の再試行は最大 2 回・指数バックオフ。
- アクセストークンは期限の 120 秒前に推奨更新(失敗しても、キャッシュを使い続ける)、30 秒前以降は必須更新(失敗はエラー)。同時の更新は 1 回にまとめる。401 ではキャッシュを破棄し、次の要求で再交換する(その場の再送はしない)。
- 前提: projected token は `expirationSeconds: 3600`、audience は `https://api.anthropic.com`。k8s の inline JWKS は、クラスターの署名鍵のローテーションに合わせて更新が必要(更新を忘れると交換が失敗する)。
- 例外・ログ・`toString` には、JWT・アクセストークン・レスポンス本文・ファイルの内容を出さない(固定メッセージ)。

## 追補(2026-10-17): 自己開発の提案呼び出しでは thinking を無効にする

- Haiku 5.5 は adaptive thinking が既定で有効で、`max_tokens`(`DEVELOPER_MAX_OUTPUT_TOKENS`=8000)を thinking だけで使い切り、text を 1 文字も返さないことがあった(dev, 2026-10-17: 2 試行とも `finishReason=LENGTH`、`outputChars=0`)。自己開発の `DeveloperConfig.thinking` の既定は `DISABLED` とし、本文に `"thinking":{"type":"disabled"}` を送る。上書きは `DeveloperConfig` で行う。
- 費用の上限見積りは変えない。thinking のトークンも `max_tokens` の内側に収まるため、上限は既存の式のままで足りる。

## 実装の分割

| 順 | PR の範囲 | 依存 | 触るモジュール | 他の PR と競合するか |
|---|---|---|---|---|
| ① | `PatchSet` と `PatchPolicy` | なし | `kivra-core` | 別の Haiku が実装中。`kivra-core` の他の変更とは重ならない想定 |
| ② | `GitHubPort.commitFiles`(`kivra/run-<runId>` に限定、`force=false`)。Git Data API を使う(blob → tree → commit → ref の順。複数ファイルを 1 コミットで原子的に書く) | なし | `kivra-github` | 別の Haiku が実装中。`GitHubPort` の既存の API は変えない |
| ③ | 新モジュール `kivra-developer`: `DeveloperPort`、LLM を使う実装(プロンプト、構造化出力、予算の予約・確定)、Fake 実装。`kivra-core` と `kivra-model-gateway` に依存し、DB を使わないので DB なしでテストできる | ①(`PatchSet`)、②(マージ後に着手)、`kivra-model-gateway` | `kivra-developer`(新規)、`settings.gradle.kts`(この PR で追加) | `settings.gradle.kts` を触るため、① と ② のマージ後に着手する |
| ④ | `SelfDevRunner`(状態機械。Run、Journal、GitHub、Developer をつなぐ)。DB を使うので `kivra-app` に置く。Fake で Acceptance Harness の Happy path(#70 のシナリオ 1)を通す | ①②③⑤ | `kivra-app`(`agent/` か新パッケージ)、`kivra-app/src/test/.../acceptance/` | ③ の Fake を使うため、③ の後。Harness の追加は ④ のみ |
| ⑤ | Run の状態の追加(`UNKNOWN`、`PATCH_READY`、遷移表。`ABANDONED` は追加せず `CANCELLED` + `PR_ABANDONED`。Flyway V12 は無し) | ①(`kivra-core` を共有するため、① の後) | `kivra-core`(`States.kt`)、`kivra-app/src/main/kotlin/net/kigawa/kivra/db/`、それらのテスト | ① の後に着手する(`kivra-core` の変更の順序は ① → ⑤) |
| ⑥ | 配線(`Application.kt`: `KIVRA_SELFDEV_ENABLED`、設定、Anthropic の API キーと GitHub トークンの Secret の読み込み、能力ゲートの手順) | ②③④ | `kivra-app`(`Application.kt`、`config/KivraConfig.kt`) | `KivraConfig` を触るため、他の設定 PR と順序を合わせる |
| ⑦ | 起動の経路: 別のエンドポイント `POST /api/selfdev/tasks`。既存の `/api/tasks` の挙動は変えない(能力ゲートを明示できる)。認証は既存と同じ(`admin-panel:admin`)。`KIVRA_SELFDEV_ENABLED` が必須 | ④⑥ | `kivra-app`(新しいルート、`routes/` 配下) | `/api/tasks`(`TaskApiRoutes.kt`)は変えない |
| ⑧ | k8s の Secret と設定、dev での最初の実行の手順(runbook) | ⑥⑦ | `kigawa-net-k8s` 系の manifest、`docs/`(runbook) | 別リポジトリ(k8s)の変更を含む。`docs/` の runbook は `docs/adr/` 以外の新規ファイル |

依存の要点: ①と②は並行できる。③ は ①② のマージ後(`settings.gradle.kts` の競合を避けるため)。④ は ①②③⑤ が要る。⑤ は ① の後。⑥⑦⑧ は ④ の後。

### ユーザーにしか用意できないもの

| 項目 | 内容 | いつ必要か |
|---|---|---|
| Anthropic の API キー | k8s の Secret として置く(値は ADR・ログに出さない) | ⑥ の後、dev で LLM を有効にするとき |
| GitHub のトークン | 管理者の権限を持たない専用の細粒度 PAT(`kigawa-net/kivra` の `contents:write` と `pull_requests:write` のみ。`administration` なし、`workflows` なし)。k8s の Secret として置く。`enforce_admins = false` のため、管理者の権限を持つトークンは保護を迂回できる | ⑥ の後、dev で GitHub を有効にするとき |
| kigawa-net/infra#296 のマージと `terraform apply`(R2 の資格情報が要る)。必須レビューは既定の承認 0 件のままでよいか確認する(人間がマージ時に確認する運用。承認 1 件の必須化は、ユーザーの判断) | ⑧ の runbook の前 |
| 予算の確認 | 月 1 万円の上限と、Run ごとの上限の値 | ③ の実装の前 |
| 対象リポジトリの確定 | MVP の対象が `kigawa-net/kivra` のみでよいことの確認 | ⑥ の前 |

## 確認できなかった事実

- Happy path(#70 シナリオ 1)の Harness は、このリポジトリに無い(④ で作る)。
- ⑤ で `UNKNOWN` と `PATCH_READY` を足したときの、既存の遷移テストへの影響は、⑤ の PR で確認した(`active_flag` は両方を active として扱うので変更不要)。
- 月 1 万円の予算を、`BudgetController`(インメモリ)と V10 の永続的な台帳のどちらで強制するかは未決定。本番では永続的な台帳を使うべき(ADR 0004)。
- `kigawa-net/kivra` の `main` にはブランチ保護が無い(2026-10-15 に API で確認: Ruleset 0 件、`branches/main/protection` は 404)。infra#296 で追加予定。
- `commitFiles` は Git Data API を使う(決定済み)。
- 必須のレビュー・承認の有無(既定は承認 0 件)は、infra#296 の適用後に確認する。
- `PatchPolicy` の「秘密らしい内容」の判定方法(パターン、エントロピー)は未決定。

## 見直しの条件

- ① `PatchPolicy` の実装で、拒否パスの一覧が増えたとき。
- ⑤ の状態の追加で、既存の `PULL_REQUEST` の再利用が遷移表の矛盾を生むとき(その場合は名称を `PR_OPEN` に変える別の決定を行う)。
- 変更案の品質の基準(6 節 8 項)を下回ったとき。
- exkes の導入の条件(ローカルでのテスト実行)が発生したとき。
