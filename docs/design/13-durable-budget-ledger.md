# 設計: 永続予算ストア(Budget Ledger)B1

- 対象 Issue: kigawa-net/kivra#109(Local-first / Batch LLM routing with hard 10,000 JPY monthly API cap)
- 関連: `docs/design/05-model-gateway.md` 第 7 節(予算)、#73、exkes の capacity controls
- 状態: B1 実装済み(DB 版の予算ストア。本番の推論経路には未配線)。B2 実装済み(価格の版・tier・円換算。§11)
- 対象外(後続段階): B3 Batch(提出・冪等・UNKNOWN_HELD)/ B4 レポート

## 1. 位置づけ

- `kivra-model-gateway` の `BudgetPort` が予算操作の契約(reserve / commit / release / usage / admission / softExceeded)。
- `BudgetController`(インメモリ)は dev/test 専用。`DbBudgetStore`(kivra-app)は MariaDB に永続化し、複数レプリカで同じ上限を共有する。
- モジュールの依存方向: `kivra-app` → `kivra-model-gateway`。gateway は DB に依存しない。

## 2. 単位・期間・スコープ

- 金額は JPY の整数単位の BigDecimal で受け、DB には **マイクロ円(1 円 = 1,000,000)の BIGINT** で保存する。
  - 費用の変換は**切り上げ**(保守的)。上限(hard / soft)の変換は**切り捨て**(保守的)。
  - BIGINT の上限は約 9.2 × 10^12 円で、月 10,000 円の上限に対して十分。超える費用は `UNKNOWN_COST` で拒否する。
- tenant スコープ: JST(Asia/Tokyo)の暦月 `YYYY-MM` ごとに期間が分かれる。月をまたぐと新しい期間の行が自動で作られ、0 円から始まる。
- run スコープ(`run:<id>`): 期間は `ALL`(run の生存期間)。任意の上限。tenant 上限を超えて使えない(階層)。
- 上限は構築時(設定)で固定。引き上げる API は作らない。

## 3. テーブル(V10)

| テーブル | 役割 |
| --- | --- |
| `budget_periods` | 主キー `(scope_id, period)`。`committed_micro_yen`、`unpriced_calls`、`hard_micro_yen`(作成時の記録) |
| `budget_reservations` | 主キー `reservation_key`(requestId/attempt)。`state`、`amount_micro_yen`、`expires_at` |
| `budget_reservation_scopes` | 予約の経路の各スコープと、課金先の期間 |
| `budget_acknowledgements`(V12) | 運用者による解除の監査行。主キー `reservation_key`(1 予約につき 1 行)。`operator_id`、`reason`、`evidence`、`charged_before_micro_yen`、`actual_micro_yen`、`actual_known`、`acknowledged_at`。追記専用(アプリは INSERT のみ) |

- 予約中の額(reserved)は `budget_periods` に持たず、`RESERVED` の予約の合計として都度求める。状態遷移だけで整合する。
- `budget_reservations.amount_micro_yen` は、**その予約が現在 committed に計上している額(充当額 charged)** を表す。通常は見積額と同じ。遅れた実コストの commit で、実コストに更新される(計上の補正と同じ値)。解除直前の額は `budget_acknowledgements.charged_before_micro_yen` に残る。元の見積額は、遅れた実コストで更新された後は予約の行には残らない。
- DML のみ使う。アプリ起動後に DDL は発行しない。H2(MODE=MySQL)と MariaDB の両方で通る DDL。

## 4. 予約の状態遷移

```
 (none) ── reserve ──▶ RESERVED ── commit(actual) ──────────────▶ COMMITTED
                         │  │
                         │  └── commit(actual=null) ──┐
                 release │                            ▼
                         │     TTL 経過 ──▶ LAPSED ──▶ UNPRICED ── acknowledgeUnpriced ──▶ ACKNOWLEDGED
                         ▼                    │  ▲          (監査つき。1 件ずつ)
                     RELEASED                 │  └ commit(actual): 額だけ補正し、unpriced は残す
                                              └ release: 計上を取り消す ──▶ RELEASED
```

- `RESERVED`: 上限の計算に含まれる(reserved)。
- `LAPSED`: 保守的に committed に積み、unpriced として数える。以後の admission は `UNKNOWN_COST` で拒否される(現行の `BudgetController` と同じ)。
- `UNPRICED`(V10 の時点では `COMMITTED` として扱っていたものを、解除の対象として区別する): 実コストが確定しなかった予約。見積額を committed に積み、unpriced を数える。遅れた実コストの commit は、額を補正するが **unpriced は下げない**(`LAPSED` から遷移した場合も同じ)。運用者が `acknowledgeUnpriced` で解除するまで `UNPRICED` のまま。
- `ACKNOWLEDGED`: 運用者の監査つきの解除で終端。計上を実コスト(または充当額)に確定し、各スコープの unpriced を 1 つ下げる。`budget_acknowledgements` に 1 行を追加する(同じトランザクション)。
- `COMMITTED` / `RELEASED` / `UNPRICED` / `ACKNOWLEDGED`: 終端(`UNPRICED` は解除まで unpriced の計上を持つ)。commit / release は冪等(再実行は無視)。同じ key は終端後も再予約できない。
- key は `reservation_key` の主キーで守る。レプリカ間でも二重予約しない。

## 5. 原子性とロック順

- 全操作は 1 トランザクションで、最初に **tenant スコープの全期間行を `SELECT ... FOR UPDATE`(期間順)** で取る。これが全レプリカ共通の入口になる。
- その後に run の期間行を `(scope_id, period)` の昇順で取る。取得順は tenant → run の昇順で固定し、デッドロックの循環を作らない。
- 期間行の新規作成は、ロックを取る前に**別トランザクション**で行う(挿入が本体のロックを待たないため)。同時作成の重複キーは無視する。
- reserve は、重複 key・unpriced・`committed + reserved + 今回 > hard` の検査と予約行の挿入を同じトランザクションで行う。途中の例外は全体をロールバックし、予約を残さない。
- 予約・確定・解放・使用量の参照・admission・softExceeded の DB エラーは **拒否**(`BUDGET_STORE_UNAVAILABLE`、fail closed)。

## 6. lapsed(期限切れ)の扱い

- 既定 TTL は 5 分。期限切れの判定は、予約・commit・release・usage・admission・softExceeded の各操作の先頭で行う(バックグラウンドのスレッドは作らない)。
- 遅れて届いた commit は、実額が分かれば額を補正する。release は計上を取り消す。どちらも `budget_reservations.state` の遷移で行う。

## 7. 月またぎ

- 予約は作成時の期間(JST 暦月)に紐づく。月が変わった後の commit / release は、**予約が作られた期間**の行を更新する。
- 新しい月の使用量は 0 から始まり、前月の使用は持ち越さない。前月の未確定の予約は、前月の行を上限の計算から外す(新月の reserved には含まれない)。
- run スコープは期間 `ALL` なので、月が変わっても run の上限は累計で効く。

## 8. テスト

- 共通の契約(`BudgetPortContract`)を `BudgetController` と `DbBudgetStore` の両方に流す。並行予約(20 スレッド、2 つのレプリカ)、上限ちょうど、fail closed、重複 key、冪等、TTL と補正、階層、ポリシー欠落、soft 80% を検査する。
- DB 版の固有テスト: 月またぎ(JST 23:59:59 → 0:00)、再起動後の残量、1 マイクロ円未満の切り上げ、例外時のロールバック、期間行の作成。
- 実機: `KIVRA_TEST_DB=mariadb` で MariaDB 11 に対して実行する(README の手順)。

## 9. 配線していない点

- 本番の起動(`Application.kt`)には組み込まない。有料 API の呼び出し経路がまだ無いため。
- `acknowledgeUnpriced` と `POST /api/admin/budget/acknowledge`(PR #168)も、本番には未配線(`Application.kt` で `DbBudgetStore` を構築せず、ルートも登録しない)。配線は ⑥ の PR で行う。
- 設定: `kivra.budget.tenantId`(`KIVRA_BUDGET_TENANT_ID`)、`kivra.budget.monthlyLimitJpy`(`KIVRA_BUDGET_MONTHLY_LIMIT_JPY`、既定 10000)、`kivra.budget.softRatio`(`KIVRA_BUDGET_SOFT_RATIO`、既定 0.8)。読み込みは `BudgetConfig.from`。
- `ModelGateway` は現在も `BudgetPort` を受けるだけで、本番用に DB 版のみを許す制約は未実装。
- 呼び出し元は同期 JDBC を使う。コルーチンのスレッドで呼ぶ場合は別途 `Dispatchers.IO` 等に逃がす必要がある(配線時に扱う)。

## 10. 今後(B2〜B4)

- B2: 実装済み(§11)。
- 解除: `acknowledgeUnpriced` は済(PR #168)。運用者が予約キー単位で、根拠つきで解除する(ADR 0003)。台帳へのイベントの追記は、B3 以降で扱う。
- B3: Batch の提出・冪等・ポーリング結果の対応付け、`UNKNOWN_HELD`、再提出の禁止、取消と返金の照合。Batch と realtime が同じ台帳を共有する。
- B4: 支出のレポート(task / stage / model / provider 別、キャッシュ率、エスカレーション率)。

## 11. B2: 価格カタログ・為替・税の保守的換算

実装: `kivra-model-gateway`(`Registry.kt` の `Price` / `PriceTier` / `ModelEntry.priceAt`、`CostConverter.kt`、`Provider.kt` の `Estimator` / `CostCalculator`)。

### 11.1 価格は版(version)のデータ

- レジストリの `price` は、単一の書式(後方互換: `effectiveFrom` 無し = 常に有効)、または版のリストを受け付ける。
- 版ごとに `effectiveFrom`(ISO 日付)を持てる。同じモデルに同じ日付の版を 2 つ置くと、読み込みで拒否する。
- 版の選択: リクエスト時刻を **JST の日付**に直し、その日以前で `effectiveFrom` が最も新しい版を使う。未来の版は、その日付まで使わない。`effectiveFrom` 無しの版は、他の日付付きの版より古いものとして扱う。
- 有効な版が無い(価格の無い UNKNOWN を含む)は UNKNOWN_COST で拒否する(fail closed)。JST の月末に合わせて版を切り替えるため、予算の暦月(§2)と同じ境界になる。

### 11.2 トークン階層(tier)

- `tiers`: `inputTokensUpTo`(その値以下の入力で適用)ごとに、入力・出力・キャッシュ読み取りの単価を持つ。境界は昇順で厳密増加に限る。
- トップレベルの単価は、最後の境界より大きい入力に適用する。
- 入力の大きさ = `inputTokens + cachedInputTokens`(実際の使用量)。
- **予約時(見積もり)は階層が不明**なので、全階層の中で成分ごとに最も高い単価を使う(上限側)。キャッシュ読み取りは入力側の単価と比べて高い方を使う。
- **確定時(実コスト)**は、実際の使用量の入力の大きさで階層を選ぶ。

### 11.3 円換算

```
円 = ceil_micro( 外貨価格 × 為替レート × (1 + 為替余裕) × (1 + 税余裕) )   USD の価格
円 = ceil_micro( 円建ての価格 × (1 + 税余裕) )                             JPY の価格(為替なし)
```

- `ceil_micro` は、マイクロ円(1 円 = 1,000,000)への切り上げ。予算ストア(§2)の単位に合わせる。
- 換算は `CostConverter` が担う。`CostConverter` を注入した場合の予算は JPY(換算後の額を `BudgetAmount` の JPY として計上する)。注入しない場合は従来どおり価格の通貨のまま(USD 予算の既存テストはこの経路)。
- `BudgetAmount.priceRevision` に、レジストリ版・為替レート・為替余裕・税余裕・レートの基準日時(`fxAsOf`)・価格版の日付を残す(機密ではない)。
- 確定したコスト(`ModelResponse.cost`)は価格の通貨(実コスト)のまま返す。予算に計上するのは換算後の円。

### 11.4 fail closed(換算しない = UNKNOWN_COST で拒否)

- 通貨が USD / JPY 以外。
- レートが未設定、または 1 円未満・1000 円超(範囲外)。
- `fxAsOf` が未設定、未来、または現在から `maxAgeDays` より古い(境界の日数ちょうどは有効)。
- 有効な価格の版が無い。

ローカル推論(`TrustZone.LOCAL`、かつ全単価が**明示的に 0**)は、無料として 0 円で計上する。レートは不要で、為替レートが無くても通る。価格が無い(UNKNOWN)のローカルモデルは無料ではなく、拒否する。外部プロバイダで単価が 0 の場合は、通常どおりレートが必要。

### 11.5 設定値

| 環境変数 | 既定 | 意味 |
| --- | --- | --- |
| `KIVRA_FX_USD_JPY` | 未設定(換算しない) | 1 USD あたりの円。1 以上 1000 以下のみ使う |
| `KIVRA_FX_AS_OF` | 未設定(換算しない) | レートの基準日時(ISO-8601 の瞬間、または日付は UTC 0 時) |
| `KIVRA_FX_MARGIN_RATIO` | 0.10 | 為替の余裕(0 以上 1 以下) |
| `KIVRA_TAX_MARGIN_RATIO` | 0.10 | 税の余裕(0 以上 1 以下) |
| `KIVRA_FX_MAX_AGE_DAYS` | 7 | レートの最大の古さ(日、1 以上) |

- `FxConfig.fromEnv` で読む。空の値は未設定として扱う。書式が不正な値(数値でない、範囲外の余裕、0 以下の日数)は起動時に `FxConfigException` で拒否する。エラーメッセージには設定名のみを出し、値は出さない。
- 範囲外のレート(例 5000)は読み込みでは通し、換算時に null(fail closed)にする。
- 設定の変更は再起動または設定の再読み込みで効く。設定オブジェクトは不変。

### 11.6 未配線

- 本番の推論経路には未配線。`ModelGateway` / `RoutingEngine` は `costConverter` を任意で受け取る(未注入 = 従来どおり)。`kivra-app` 側の設定注入は B3 以降(有料 API の経路ができた時点)。
- 残る懸念: 確定時に換算が失敗すると(レートが古くなった場合など)、その呼び出しは `unrecorded` 扱い(応答は返さず、requestId を封印する)になる。

### 11.7 テスト

`PriceCatalogTest`(版の選択と JST 境界、未来の版、版なし、tier の境界と最高単価、YAML の読み込みと拒否)、`CostConverterTest`(換算式、端数、JPY、fail closed の各条件、為替の変動、月末の切り替え、ローカル無料と UNKNOWN の区別、設定の読み込み)、`ModelGatewayPricingTest`(確定コストが円で計上される、換算不能で provider を呼ばない)。
