# 設計: 永続予算ストア(Budget Ledger)B1

- 対象 Issue: kigawa-net/kivra#109(Local-first / Batch LLM routing with hard 10,000 JPY monthly API cap)
- 関連: `docs/design/05-model-gateway.md` 第 7 節(予算)、#73、exkes の capacity controls
- 状態: B1 実装済み(DB 版の予算ストア。本番の推論経路には未配線)
- 対象外(後続段階): B2 価格カタログ・為替 / B3 Batch(提出・冪等・UNKNOWN_HELD)/ B4 レポート

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

- 予約中の額(reserved)は `budget_periods` に持たず、`RESERVED` の予約の合計として都度求める。状態遷移だけで整合する。
- DML のみ使う。アプリ起動後に DDL は発行しない。H2(MODE=MySQL)と MariaDB の両方で通る DDL。

## 4. 予約の状態遷移

```
            reserve                 commit(actual)          commit(actual|null)
 (none) ──────────────▶ RESERVED ─────────────────▶ COMMITTED
                          │  │
                 release  │  │  TTL 経過(操作の先頭で遅延判定)
                          ▼  ▼
                     RELEASED   LAPSED ── commit(actual): 額を補正 ──▶ COMMITTED
                                   │
                                   └── release: 計上を取り消す ─────▶ RELEASED
```

- `RESERVED`: 上限の計算に含まれる(reserved)。
- `LAPSED`: 保守的に committed に積み、unpriced として数える。以後の admission は `UNKNOWN_COST` で拒否される(現行の `BudgetController` と同じ)。
- `COMMITTED` / `RELEASED`: 終端。commit / release は冪等(再実行は無視)。同じ key は終端後も再予約できない。
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
- 設定: `kivra.budget.tenantId`(`KIVRA_BUDGET_TENANT_ID`)、`kivra.budget.monthlyLimitJpy`(`KIVRA_BUDGET_MONTHLY_LIMIT_JPY`、既定 10000)、`kivra.budget.softRatio`(`KIVRA_BUDGET_SOFT_RATIO`、既定 0.8)。読み込みは `BudgetConfig.from`。
- `ModelGateway` は現在も `BudgetPort` を受けるだけで、本番用に DB 版のみを許す制約は未実装。
- 呼び出し元は同期 JDBC を使う。コルーチンのスレッドで呼ぶ場合は別途 `Dispatchers.IO` 等に逃がす必要がある(配線時に扱う)。

## 10. 今後(B2〜B4)

- B2: 価格カタログ(有効日付つき、トークン区分ごとの単価)と為替・税の保守的換算。
- B3: Batch の提出・冪等・ポーリング結果の対応付け、`UNKNOWN_HELD`、再提出の禁止、取消と返金の照合。Batch と realtime が同じ台帳を共有する。
- B4: 支出のレポート(task / stage / model / provider 別、キャッシュ率、エスカレーション率)。
