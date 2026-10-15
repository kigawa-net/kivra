# 設計: 永続 requestId 台帳(Durable Request Ledger)

- 対象 Issue: kigawa-net/kivra#56(自己開発ループ)
- 関連: ADR 0008(自己開発 MVP)の ⑥、`docs/design/13-durable-budget-ledger.md`(B1 の DB 版予算ストア)
- 状態: 実装済み(`DbRequestLedger`、本番には未配線。配線は ⑥b)

## 1. 位置づけ

- `ModelGateway` は `RequestLedger` で requestId の冪等性を保つ。プロセス内の `InMemoryRequestLedger` は再起動で失われるため、本番では使えない(`devAllowInMemoryLedger` で明示的に許可された場合だけ)。
- `DbRequestLedger`(kivra-app)は同じ契約を MariaDB で実装する。レプリカや再起動をまたいで、完了済みの応答を再生できる。
- 契約(`RequestLedger` の KDoc)と共通テスト `RequestLedgerContract` は、InMemory と DB の両方に同じシナリオを流して揃えている。

## 2. スキーマ(V13 `request_ledger`)

| 列 | 型 | 意味 |
|---|---|---|
| `request_id` | VARCHAR(128) 主キー | 呼び出し側の requestId |
| `digest` | CHAR(64) | `RequestDigest`(SHA-256)。本文は保存しない |
| `state` | VARCHAR(16) | RESERVED / SENDING / RELEASED / COMPLETED / UNKNOWN |
| `response_json` | MEDIUMTEXT NULL | 完了した応答(JSON)。上限 256 KiB(UTF-8 バイト)。超えると NULL |
| `response_replayable` | BOOLEAN | 応答を再生できるか。ストリーム完了や上限超過は FALSE |
| `created_at` / `updated_at` | DATETIME(6) | 作成と最終更新 |

索引: `(state, updated_at)`。行は削除しない。Expand-only(新規テーブルのみ)。

## 3. 状態遷移

| 操作 | 遷移 | 備考 |
|---|---|---|
| `begin`(新規) | (なし) → RESERVED | 行を RELEASED で作り、同じトランザクションで RESERVED に進める |
| `begin`(RELEASED) | RELEASED → RESERVED | 未送信と証明された失敗の後の再 claim |
| `begin`(COMPLETED) | 変化なし | 保存した応答を再生(`response_replayable` のときだけ。それ以外は `Completed(null)`) |
| `begin`(RESERVED / SENDING / UNKNOWN) | 変化なし | `DUPLICATE_REQUEST` |
| `begin`(digest 不一致) | 変化なし | `REQUEST_ID_REUSED`(常に拒否) |
| `markSending` | RESERVED / SENDING → SENDING | プロバイダ呼び出しの直前 |
| `complete` | RESERVED / SENDING → COMPLETED | 応答を保存。終端からは動かない(冪等) |
| `markOutcomeUnknown` | RESERVED / SENDING → UNKNOWN | 終端。再送しない |
| `releaseUnsent` | RESERVED / SENDING → RELEASED | 未送信と証明された失敗のみ。COMPLETED / UNKNOWN からは不可 |

`begin` は `INSERT ... ON DUPLICATE KEY UPDATE` の後に `SELECT ... FOR UPDATE` を取る(`DbRunAdmission` と同じ順序)。逆順(重複 INSERT の後に FOR UPDATE)は MariaDB 11 でデッドロックした(B1 の教訓)。

### SENDING からの release について

仕様メモでは「RESERVED のみ release」とされていたが、既存の契約と ModelGateway の流れに合わせ、**SENDING からも release を許す**。`markSending` はプロバイダ呼び出しの直前に書かれるため、呼び出しが送信されなかったと証明された失敗(NOT_SENT)は SENDING に残る。その後の再 claim(送信前失敗のフォールバック)を成立させるため。COMPLETED と UNKNOWN からは release できない。

## 4. 障害時の扱い(fail-closed)

- `begin` は DB エラーで `Rejected(STORE_UNAVAILABLE)`。送信されない。
- `markSending` と `complete` は `RequestLedgerWriteException` を投げる。`ModelGateway` の既存の経路が働く。送信前の失敗は releaseUnsent へ、応答を記録できなかった場合は seal して `OUTCOME_UNKNOWN`(RECONCILIATION_REQUIRED)。
- `markOutcomeUnknown` と `releaseUnsent` は例外を投げずログに残す。書けなかった行は RESERVED / SENDING のまま残り、再 claim を拒否するため再送は起きない。
- ログと例外には応答の本文、プロンプト、requestId の全体を出さない(先頭 8 文字と例外の種類、SQLState のみ)。

## 5. 容量

- 新しい requestId は、行数が `capacity`(既定 1,000,000)に達していると `LEDGER_FULL` で拒否する。既存の行は落とさない。
- 件数は上限までしか読まない(`LIMIT`)。ロックなしで読むため、同時に来た新規 requestId は上限を最大で同時実行数の分だけ超えうる(設計上の許容)。

## 6. 直列化の方式

- 候補は (a) `kivra-model-gateway` の型に `@Serializable` を付ける、(b) `DbRequestLedger` 内の手書きコーデック。
- `BigDecimal`(`Cost.amount`)は標準の直列化器がなく、gateway 側に独自の直列化器を足すことになる。
- 採用したのは (b)。`ModelResponseCodec`(kivra-app)が全フィールドを手で書き出す。gateway の型と公開 API は変えない。
- 全フィールドを保存するのは、復元した応答が保存前と**等しい**ことを保つため(`Part` の各種、`structured` の JSON null と未設定の区別、`Cost` の小数の桁数)。

## 7. 既知の未対応

- 本番配線(`Application.kt` への組み込み)は ⑥b で行う。
- 完了した応答の保存により、`PATCH_READY` からの再開で LLM を再度呼ばずに同じ変更案を得られる(④ の限界の緩和)。
- 古い行の掃除はしない(仕様どおり、行は削除しない)。容量が問題になった時点で別途設計する。
