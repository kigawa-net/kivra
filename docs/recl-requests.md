# recl への要求事項(設計 #6 §4.3 の整理)

状態: **未提示（部分的に前進）**。kigawa-net/recl は 2026-10-12 時点で設計書（`docs/design.md`、PR #17）と内部 Core / InMemory Storage（PR #19）のみ main にマージ済み。
**実 REST / MCP サーバーと Fortis Adapter は未提供**で、本書の契約は recl 側で確定していない。
実互換性ゲート: [recl #20](https://github.com/kigawa-net/recl/issues/20)（Trusted Mutation Envelope・Canonical Digest・UUIDv7 境界）、[Kivra #71](https://github.com/kigawa-net/kivra/issues/71)（Provider Compatibility Matrix & Production Capability Gate）。
Mock の成功は Production Provider 検証ではない。
本書は Kivra 側 `ReclAdapter`(`kivra-memory`)が期待している契約と、recl に確認・要求すべき事項の一覧である。
**recl が実装する/存在する前提ではない**。recl の設計書(docs/design.md、マージ済み)と本書を照合し、不一致は Adapter 側で吸収するか recl に依頼する。

優先度は設計 #6 §4.3 に従う。「Adapter の暫定挙動」は recl が対応しない場合に Kivra 側で採る回避策。

| # | 要求(要確認) | 理由 | Adapter の暫定挙動 / 現状の実装 |
|---|---|---|---|
| 1 | **冪等な save**: クライアント指定 `Idempotency-Key`(または content-hash 重複検出) | Orchestrator 再起動・Write Queue 再送での二重保存防止 | 結果不明(タイムアウト/5xx)の save は、`idempotentSaveConfirmed` の値に関係なく再送しない（永続 Journal 連携まで再送しない）。破棄せず要照合として保持し、監査は件数のみ(`WRITE_HELD_RECONCILE`)。`Idempotency-Key` ヘッダと本文 `idempotency_key` を送るが、尊重されるかは未確認 |
| 2 | **構造化メタデータ**(kind, provenance, confidence, sensitivity, retention)の保存と、kind / sensitivity での検索フィルタ | Memory 種別と機密区分の制御 | 専用フィールドがある前提の JSON を送る。無い場合は Scope 風タグ文字列へのエンコード案(設計 #6 §3.2 (a))に切り替える。検索結果は Adapter 側でも kind / sensitivity を再フィルタ |
| 3 | **Redact / 物理削除 API**、および削除の Qdrant・`context_revisions` への伝播保証 | 秘密・個人情報の混入時の消去(§7.3) | `ReclConfig.redactSupported=false` が既定で `DeleteMode.REDACT` は `UNSUPPORTED`。soft delete に黙って格下げしない |
| 4 | **Scope 階層**(prefix 検索)の仕様明示 | `tenant:/project:/exec:` の写像(§3.3) | Kivra が許可 Scope の集合を計算し、`AccessContext.allowedScopes` の範囲内でのみ問い合わせる。階層非対応なら上位 Scope を明示列挙する必要がある。Adapter は応答の Scope が許可範囲内かを検証し、外れた結果は破棄/拒否 |
| 5 | **Principal 単位のトークン**(Kivra サービス用と Agent 用を分離、Scope を token に束縛) | Agent の recl 直接アクセスを MVP では禁止(§5.6)、最小権限 | Bearer 1 本(`ReclCredentials`)。トークン方式自体が未確認 |
| 6 | 検索結果に **ヒットした Scope・score・version** を含める | 引用追跡(`M:<id>@v<n>`)・再現率計測 | `context.scopes` / `version` / `score` を期待。無ければ PROTOCOL 扱い |
| 7 | **監査**: 誰がどの Context を read/write したかを recl 側でも記録(`X-Trace-Id` で相関) | Kivra 側監査との突合 | `X-Trace-Id` ヘッダを送る |
| 8 | **一括 export** | ユーザーの閲覧(M11) | 未実装(スコープ外) |

## 追加で確認したい点(Adapter が仮定している契約)

- API ルートとパス: `POST /contexts/search`、`GET /contexts/{id}`、`GET /contexts/pinned?scope=..`、`POST /contexts`、`PATCH /contexts/{id}`(`expected_version`)、`POST /contexts/{id}/supersede`、`DELETE /contexts/{id}?mode=soft|redact`、`GET /health`(`ReclConfig.apiPrefix` 既定 `/api/v1`)。
- ID の型(Adapter は `[A-Za-z0-9._-]{1,128}` のみ受理)、version は整数で更新ごとに単調増加。
- 楽観ロック競合のステータス(Adapter は 409 / 412 を `VERSION_CONFLICT` とみなす)、スコープ外・存在しない・非公開 Context の区別(Adapter は 404 を `null` とみなし、削除の証拠にはしない)。
- 検索がキーワード(sparse)を含むか(recl#6 は dense のみ記載)。
- soft delete 済み・supersede 済み Context の取得時の status 表現(`active` / `superseded` / `deleted`)。
- 認証: API Token の発行・失効・Scope 束縛、機械向け認証の方式。
- レート制限 HTTP 429 と 5xx は、Mutation が未送信・未適用である証拠にならない。Adapter は変更系 429 を `OUTCOME_UNKNOWN` として保持し、自動再送を禁止する（Kivra #75）。読み取り系 GET は別途再試行可能。
- `health` の有無(Qdrant のみ障害時に pinned / ID 取得が継続できるか、§4.4)。

## recl 設計書との差分(2026-10-12 時点で確認した事実)

- ドメイン Status は recl が `proposed / confirmed / superseded / archived`、Kivra の `MemoryStatus` は `active / superseded / deleted`。一致しないため、応答の status 写像は未確定(現状の Adapter は未知の値を PROTOCOL 扱い)。
- URL prefix・request/response DTO・MCP identity は未確定。Adapter の `/api/v1` や DTO は仮。
- Context ID は UUIDv7。再試行で安定させる前提(recl 設計 §Create)だが、recl 側の実装・テストは未提供。
- Supersede(2 Context 間の原子性)は設計上 approved な Fortis atomic batch が無いと原子的に扱えないとされている。Kivra 側は本番提供を前提にしない。
- REDACT(物理消去)は recl 設計書に専用 API の記載が無い(ログの redact は言及あり)。`ReclConfig.redactSupported=false` のまま。
- `GET` の 404 は「以前の write が未適用だった」証拠にならない(設計 §Create の receipt 保持期限の議論を参照)。
- Mutating operation は Fake / Read-only を原則とし、`ReclConfig.mutationsEnabled` が既定 `false`。サーバー側の冪等性・権限・Receipt・契約テストが合格するまで有効化しない。
- 未対応: MariaDB 撤廃の経緯(recl 側の判断)は本 PR の範囲では確認していない。Fortis の public conditional KV / Snapshot の可否は recl #14 で未検証。
