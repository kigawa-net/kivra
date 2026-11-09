// ローカル検証専用の OIDC 代わり。本番では使わない。
//
// 役割: kivra-app を KIVRA_AUTH_MODE=oidc-jwt で起動するための、JWKS の配信と RS256 のアクセストークン発行。
// 外部の IdP は使わず、鍵はこのスクリプトが作る。鍵ファイルは OIDC_DIR に置く(リポジトリには入れない)。
//
//   OIDC_DIR=<dir> node oidc-local.mjs gen                  鍵 (private.pem) と JWKS (jwks.json) を作る
//   OIDC_DIR=<dir> node oidc-local.mjs serve                http://127.0.0.1:18090/certs に JWKS を配る
//   OIDC_DIR=<dir> node oidc-local.mjs token <sub> [roles]  アクセストークンを標準出力へ
//
// 既定の iss / aud / client は kivra-app の起動スクリプト(run-local-server.sh)と合わせてある。
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';

const DIR = process.env.OIDC_DIR;
const ISSUER = process.env.OIDC_ISSUER ?? 'http://127.0.0.1:18090/realms/local';
const AUDIENCE = process.env.OIDC_AUDIENCE ?? 'kivra-local';
const CLIENT = process.env.OIDC_CLIENT ?? 'admin-panel';
const KID = 'local-kid';
const PORT = Number(process.env.OIDC_PORT ?? 18090);

const b64u = (buf) => Buffer.from(buf).toString('base64url');

function requireDir() {
  if (!DIR) throw new Error('OIDC_DIR is required');
  fs.mkdirSync(DIR, { recursive: true });
}

function gen() {
  requireDir();
  const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const pub = publicKey.export({ format: 'jwk' });
  fs.writeFileSync(path.join(DIR, 'private.pem'), privateKey.export({ format: 'pem', type: 'pkcs8' }), { mode: 0o600 });
  fs.writeFileSync(
    path.join(DIR, 'jwks.json'),
    JSON.stringify({ keys: [{ kid: KID, kty: 'RSA', alg: 'RS256', use: 'sig', n: pub.n, e: pub.e }] }),
  );
  console.log(`generated keys in ${DIR}`);
}

function token(sub, roles = ['admin']) {
  requireDir();
  const priv = crypto.createPrivateKey(fs.readFileSync(path.join(DIR, 'private.pem')));
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: 'RS256', typ: 'JWT', kid: KID };
  const payload = {
    iss: ISSUER,
    aud: AUDIENCE,
    sub,
    iat: now,
    nbf: now - 10,
    exp: now + 12 * 3600,
    email: `${sub}@example.test`,
    email_verified: true,
    preferred_username: sub,
    resource_access: { [CLIENT]: { roles } },
  };
  const data = `${b64u(JSON.stringify(header))}.${b64u(JSON.stringify(payload))}`;
  const sig = crypto.sign('RSA-SHA256', Buffer.from(data), priv);
  return `${data}.${b64u(sig)}`;
}

function serve() {
  requireDir();
  const jwks = fs.readFileSync(path.join(DIR, 'jwks.json'));
  http
    .createServer((req, res) => {
      if (req.url === '/certs' || req.url.startsWith('/certs?')) {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(jwks);
      } else {
        res.writeHead(404);
        res.end();
      }
    })
    .listen(PORT, '127.0.0.1', () => console.log(`JWKS on http://127.0.0.1:${PORT}/certs`));
}

const cmd = process.argv[2];
if (cmd === 'gen') gen();
else if (cmd === 'serve') serve();
else if (cmd === 'token') {
  if (!process.argv[3]) throw new Error('usage: token <sub> [roles]');
  console.log(token(process.argv[3], process.argv[4] ? process.argv[4].split(',') : undefined));
} else {
  throw new Error('usage: oidc-local.mjs gen|serve|token <sub> [roles]');
}
