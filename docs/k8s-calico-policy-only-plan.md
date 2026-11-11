# 計画: prod に Calico をポリシー専用で追加する(ADR 0010 追補 7 の前提条件)

状態: 計画(未実施)。クラスターは変更していない。実施は運用者が行う(共有基盤の変更)。
確認日: 2026-11-11(`prod` コンテキスト、読み取りのみ)。

## 1. 現状(事実)

- CNI は Flannel(`kube-flannel` namespace、DaemonSet 7 ノード分)。NetworkPolicy のオブジェクトはあるが、**強制されていない**。
- ノード: 7 台(control-plane 3、worker 4)。Kubernetes v1.29〜v1.30、カーネル 6.8。
- 既存の NetworkPolicy は 17 件。**Calico を入れると、これらが初めて強制される。**

| namespace | 件数 | 備考 |
|---|---|---|
| `argocd` | 7 | ArgoCD の各コンポーネント |
| `kigawa-net-kivra-dev` | 1 | `kivra-netpol`: `app=kivra` への ingress を、`app=kivra-oauth2-proxy` の 8080 だけに限定 |
| `kigawa-net-nextcloud`、`nextcloud` | 4 | DB と redis(ingress と egress) |
| `kigawa-system-rook-ceph` | 2 | CSI の ctrlplugin。**ingress は全拒否、egress は 1 規則** |
| `onemc-build` | 1 | `mc-server-build-netpol` |
| `platform-exkes-main-sandbox`、`-stg-sandbox` | 3 | **`default-deny`(ingress と egress の全拒否)**。stg には許可の 1 件 |

## 2. 強制されたときに壊れうるもの(導入前に確認する)

1. **`kivra-netpol`**: kivra の Pod への ingress は oauth2-proxy だけになる。エージェント API(`/api/agent/v1`)や MCP(`/mcp`)が、oauth2-proxy を経由せず ingress から直接 kivra に届く構成なら、**強制後に届かなくなる**。Ingress の経路を確認し、必要なら ingress controller からの許可を足す(kigawa-net-k8s 側)。
2. **rook-ceph の CSI ctrlplugin**: ingress が全拒否。実際には CSI のサイドカー同士が localhost で通信し、外からの ingress は不要という想定だが、**PVC の作成・拡張・スナップショットに影響しないか**を、導入前に検証用の PVC で確認する。
3. **exkes の sandbox**: `default-deny` が効く。許可の規則が足りているかを、exkes の担当で確認する。
4. **nextcloud / ArgoCD**: 規則が実際の通信(DB、redis、Git、Dex)を過不足なく許可しているかを確認する。
5. ArgoCD、cert-manager、ingress controller、kube-dns など、policy の無い namespace は影響を受けない(policy が無い Pod は、全許可のまま)。

## 2.1 確認結果(2026-11-11、読み取りのみ)

| 対象 | 結果 | 対応 |
|---|---|---|
| `kivra-netpol` | **要対応。** `/api/agent/`、`/mcp`、`/.well-known/oauth-protected-resource` は、haproxy ingress(`system` namespace、Pod ネットワーク)から `kivra-svc:8080` に直接届く。oauth2-proxy を経由しないため、強制後に遮断される | kigawa-net-k8s に `kivra-netpol-haproxy`(haproxy の 8080 を許可)を足す(kigawa-net-k8s#575)。NetworkPolicy は許可の和なので、`kivra-netpol` は変更しない |
| `argocd`(7 件) | 問題なし。上流の標準ポリシー(ingress のみ、egress の制限なし) | なし |
| `nextcloud`、`kigawa-net-nextcloud`(4 件) | 問題なし。ingress はポート指定だけで、egress は全許可 | なし |
| `kigawa-system-rook-ceph`(CSI ctrlplugin 2 件) | 低リスク。ingress は全拒否、egress は全許可。Pod はホストネットワークではない。影響が出るのは外部からの接続(メトリクスの収集など)だけと見込む | 導入後に検証用の PVC で作成・拡張を確認する |
| `onemc-build`(`mc-server-build-netpol`) | 要確認。ingress は `kigawa-net` namespace からの 25565 だけ。Service は ClusterIP で外部の公開経路はない | 接続元が `kigawa-net` 内だけであることを担当が確認する |
| `platform-exkes-*-sandbox`(3 件) | 要確認。`default-deny` が効くようになる。`main-sandbox` には許可の規則がなく、`stg-sandbox` には gateway からの 8080 の許可が 1 件ある | exkes の担当が、必要な通信が許可されているか確認する |

kigawa-net-k8s#575 のマージは、導入より前に行う。現状(強制されない)では、動作は変わらない。

## 3. 手順(運用者)

1. 影響の確認(読み取り): §2 の各項目を確認する。特に `kivra-netpol` と rook-ceph。
2. Calico をポリシー専用で追加する。Flannel は残し、経路は変えない。方式(Canal マニフェスト、または Tigera operator の `cni.type: Flannel` 相当)は、導入するバージョンの公式手順で決める。バージョンはこの計画では決めない。
3. 先に **ノード 1 台だけ**(または検証用の namespace)で動かし、既存の Pod の通信に影響がないことを確かめる。
4. 検証用 namespace で、次を確認する(合格するまで ADR 0010 追補 7 の実装を有効化しない)。
   - default-deny の egress が実際に落ちる(外部の https にも、他 namespace の Service にも届かない)。
   - 許可した宛先(プロキシの Service と DNS)だけに届く。
   - Pod 同士(同じ namespace)の ingress が、policy どおりに遮断・許可される。
5. 全ノードに展開し、§2 の項目を再確認する(PVC、kivra の MCP とエージェント API、ArgoCD の同期、nextcloud)。

## 4. ロールバック

- Calico を外せば、NetworkPolicy は再び強制されなくなる(Flannel の経路は元のまま)。
- 導入中に通信の遮断が見つかったときは、該当の NetworkPolicy だけを一時的に外す(ArgoCD 管理のものは、kigawa-net-k8s 側で外す)か、Calico のポリシー評価を止める。

## 5. 実施しないこと

- Flannel の全置換(経路の変更)。通信の停止を招きうるため、この計画の範囲外。
- 既存の NetworkPolicy の書き換え。必要な修正は、各担当の PR で行う。
