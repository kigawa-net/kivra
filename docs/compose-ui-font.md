# Compose UI の日本語フォント

`/ui/compose`(Compose Multiplatform の Wasm 版)で日本語を描くためのフォントの扱い。

## 背景

Compose の Wasm 版は canvas(Skia)に描く。ブラウザのシステムフォントは使われないため、日本語のグリフを持つフォントを
アプリに同梱し、既定のフォントにしないと日本語が豆腐(□)になる。

## 採用フォント

| 項目 | 内容 |
|---|---|
| フォント | Noto Sans JP(Regular, wght=400) |
| 出典 | Google Fonts の `ofl/notosansjp/NotoSansJP[wght].ttf`(可変フォント)を fonttools の `instancer` で wght=400 に固定 |
| ライセンス | SIL Open Font License 1.1。Copyright 2014-2021 Adobe。本文は `kivra-compose-ui/src/commonMain/composeResources/files/licenses/NotoSansJP-OFL.txt` |
| 同梱ファイル | `kivra-compose-ui/src/commonMain/composeResources/font/noto_sans_jp_regular.ttf` |
| サイズ | 1,450,924 バイト(約 1.4 MiB、TTF、サブセット後) |
| 字種 | ASCII(U+0020〜U+007E)、JIS X 0208 第一水準(漢字 第一水準 + かな + 記号)、UI のソースに現れる非 ASCII 文字すべて。字形 3,966 |

参考: 可変フォント全体は約 9.6 MB、Regular の全字種インスタンスは約 5.8 MB。

ウェイトは Regular 1 つだけにしている。太字(Bold など)の見出しは、Compose が Regular を太く合成して描く。
太字の実ファイルを足すと、同じ手順で約 1.4 MB 増える。

## 配信

- `kivra-app/.../routes/UiRoutes.kt` が `composeResources/` を配信する。
  - `/ui/compose/composeResources/{path...}` と `/ui/composeResources/{path...}` の両方。
    後者は、末尾スラッシュなしの `/ui/compose` で Compose が `./composeResources/` を解決した先(`/ui/` の直下)に対応する。
  - 配信できる型は `ttf` / `otf` / `woff` / `woff2`(`font/*`)と `txt`(`text/plain`)だけ。それ以外、空・`.`・`..` の
    セグメント、バックスラッシュを含むパスは 404。
  - 応答は他の Compose 資産と同じく `Content-Security-Policy`(`connect-src 'self'` でフォントも取得できる)、
    `X-Content-Type-Options: nosniff`、`Cache-Control: no-store`。
- `copyComposeWebDist` は `composeResources/**` を既に含むので、ビルド設定の変更は不要。

## 読み込みと描画

`kivra-compose-ui/src/wasmJsMain/.../KivraTheme.kt`:

1. フォントのバイト列を `Res.readBytes` で取得し、`Font(identity, bytes)` から `FontFamily` を作る。
2. `FontFamily.Resolver.preload` で字形を使える状態にしてから描く。
3. 読み込み中は何も描かない(豆腐を一瞬出さないため)。
4. 取得や読み込みが失敗したら、例外を出さずに既定フォントで描く(日本語は豆腐になる)。
5. 15 秒を過ぎたら、読み込み中でも既定フォントで描く。

`KivraApp` のテーマ(`MaterialTheme`)は `KivraTheme` の中に置き、Typography の全スタイルにこのフォントを指定する。

## 生成手順(再生成するとき)

字種を変えたとき(UI の文言を増やした、など)は、次の手順で作り直す。作業ディレクトリは任意の一時ディレクトリでよい。

```bash
# 0. 入力: Noto Sans JP の可変フォント(Google Fonts の ofl/notosansjp/NotoSansJP[wght].ttf)と OFL.txt
pip install fonttools brotli   # または venv に入れる

# 1. 字種リスト(ASCII + JIS X 0208 第一水準 + UI ソースの非 ASCII 文字)
python3 charset.py 1 /path/to/repo chars-l1.txt

# 2. wght=400 の静的インスタンス
fonttools varLib.instancer NotoSansJP[wght].ttf wght=400 -o Regular-full.ttf -q

# 3. サブセット(TTF のまま。WOFF2 にすると小さくなるが、Skia の wasm 版で読めるかは未確認)
pyftsubset Regular-full.ttf --text-file=chars-l1.txt --layout-features='*' \
  --output-file=/path/to/repo/kivra-compose-ui/src/commonMain/composeResources/font/noto_sans_jp_regular.ttf

# 4. 検査: UI の非 ASCII 文字がすべて含まれるか
python3 coverage.py /path/to/repo/.../noto_sans_jp_regular.ttf /path/to/repo
```

`charset.py`(JIS X 0208 第一水準は EUC-JP の 0xB0A1〜0xCFD3 から、UI の文字は `kivra-compose-ui/src/**/*.kt`・`kivra-web/src/**/*.kt`・`kivra-web/src/**/*.html` から集める):

```python
import sys, os, glob

level = sys.argv[1]
root = sys.argv[2]
chars = set(chr(c) for c in range(0x20, 0x7F))
rows = range(0xA1, 0xD4) if level == "1" else range(0xA1, 0xF5)
for r in rows:
    for c in range(0xA1, 0xFF):
        try:
            ch = bytes([r, c]).decode("euc_jp")
        except UnicodeDecodeError:
            continue
        if len(ch) == 1:
            chars.add(ch)
for pat in ["kivra-compose-ui/src/**/*.kt", "kivra-web/src/**/*.kt", "kivra-web/src/**/*.html"]:
    for f in glob.glob(os.path.join(root, pat), recursive=True):
        if "/build/" in f:
            continue
        for ch in open(f, encoding="utf-8").read():
            if ord(ch) > 0x7E:
                chars.add(ch)
chars.discard("﻿")
open(sys.argv[3], "w", encoding="utf-8").write("".join(sorted(chars, key=ord)))
```

`coverage.py` は、字形表(cmap)に UI の非 ASCII 文字が無いものを列挙する。テストコードにしか現れない文字(絵文字や
bidi 制御文字の例)は、画面には描かれないので対象外として見てよい。

## 既知の制限

- JIS 第二水準の漢字や、字種に含まれない記号・絵文字は豆腐のまま。利用者が入力したデータ(タスク名など)に含まれる字も
  同様。必要なら字種を広げて作り直す(第二水準まで含めると約 2.6 MB)。
- 太字は合成。
- 配信は `no-store` のため、ページを開くたびに約 1.4 MB を取得する。キャッシュさせるなら、ファイル名にハッシュを入れる
  などの別の対応が要る(CSP 方針とは別に決める)。
