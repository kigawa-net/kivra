/*
 * Kivra minimal UI (see README "UI"). Plain JavaScript, no framework, no HTML string injection.
 * Server data enters the DOM only through textContent (see h() and str()). A link is made only from a validated
 * pull request URL (see safePrUrl()).
 */
(function () {
  'use strict';

  var REFRESH_MS = 30000;
  // The text of a status line before its first fetch (every status line starts from this one text).
  var NOT_FETCHED_TEXT = '\u672a\u53d6\u5f97';
  var TICK_MS = 1000;
  var MAX_TITLE = 200;
  var MAX_BODY = 8000;
  var MAX_POLICY = 2000;
  var CONTROL_CHARS = /[\u0000-\u001f\u007f-\u009f]/;
  // The body may have line breaks and tabs; every other control character is refused (as in the title).
  var BODY_CONTROL_CHARS = /[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/;
  var PR_URL = /^https:\/\/github\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\/pull\/[0-9]+$/;

  var MESSAGES = {
    session: 'セッションが切れました（ログインが必要です）。「再読み込みしてログインし直す」からログインし直してください。',
    notFound: '見つかりません（他のユーザーの Run は見えません）',
    badRequest: '入力内容を確認してください（1〜200 文字、制御文字は使えません）',
    conflict: 'この投入キーは、別の内容（別のタイトル、または別の種別）で既に使われていました。投入キーを作り直しました。内容を確認して、もう一度投入してください。',
    tooMany: '自己開発の Run が上限に達しています。完了か解放を待ってから、もう一度投入してください。',
    unsupported: '送信の形式がサーバーに受け付けられませんでした。ページを再読み込みしてから、もう一度お試しください。',
    server: 'サーバーでエラーが起きました。時間をおいてもう一度お試しください。',
    network: '通信に失敗しました。',
    submitNetwork: '通信に失敗しました。Run が作成されたかどうかは確認できていません。同じ内容で投入し直しても、同じ Run として扱われます（二重には作られません）。',
    invalid: '応答の形式が想定と違います。ログインが切れている可能性があります。ページを再読み込みしてください。',
    releaseBadRequest: '理由を確認してください（1〜500 文字、空白だけは使えません）。',
    releaseConflict: 'この Run は解放できる状態ではありません（状態が既に変わっています）。一覧と詳細を確認してください。',
    releaseNetwork: '通信に失敗しました。解放されたかどうかは、詳細を開き直して確認してください。',
    releaseDone: '解放しました（状態: CANCELLED）。'
  };

  var RELEASE_MAX = 500;
  var SELF_DEV_KIND = 'SELF_DEV';
  var SIMULATED_KIND = 'SIMULATED';

  // Display text for the fixed codes. Unknown values are shown as they are (see the helpers below).
  var KINDS = {
    SIMULATED: { label: 'シミュレーション', cls: 'badge', note: 'AI や GitHub は呼ばれません' },
    SELF_DEV: { label: '自己開発（PR を作成）', cls: 'badge kind-self', note: 'GitHub に PR を作成します' }
  };

  var STATES = {
    CREATED: '作成済み。まだ何も実行されていません',
    PLANNING: '計画中',
    PREPARING: '準備中',
    DEVELOPING: '実装中',
    TESTING: 'テスト中',
    REVIEWING: 'レビュー中',
    PATCH_READY: 'パッチ準備完了（PR の作成を待っています）',
    PULL_REQUEST: 'PR を作成済み。人がレビューしてマージします（kivra はマージしません）',
    WAITING_APPROVAL: '承認待ち',
    RELEASING: 'リリース中',
    VERIFYING: '検証中',
    RETRY_WAITING: '再試行待ち',
    COMPLETED: '完了（マージを検証済み）',
    FAILED: '失敗',
    CANCELLED: '中止',
    UNKNOWN: '結果不明。自動では動きません。運用者が確認して解放するまで、同時上限を占有します'
  };

  var REASONS = {
    PR_ABANDONED: 'PR が閉じられた（マージされず）',
    OPERATOR_RELEASED: '運用者が解放した',
    CANCEL_REQUESTED: '中止が要求された',
    PR_MERGED: 'PR がマージされ、マージを検証した',
    PR_NOT_FOUND: 'PR が見つからない（GitHub 側の確認が必要）',
    PR_AMBIGUOUS: 'PR が一つに決まらない（GitHub 側の確認が必要）',
    GITHUB_UNKNOWN_OUTCOME: 'GitHub の結果が不明',
    POLICY_REJECTED: '変更がポリシーに通らなかった',
    TASK_REFERENCE_UNRESOLVED: '依頼文の参照（Issue）を解決できなかった',
    TASK_REFERENCE_REPO_NOT_ALLOWED: '参照先が対象のリポジトリではない',
    TASK_REFERENCE_PULL_REQUEST: '参照先が Issue ではなく PR',
    TASK_REFERENCE_CLOSED: '参照先の Issue はクローズ済み',
    TASK_REFERENCE_NOT_FOUND: '参照先の Issue が見つからない',
    TASK_REFERENCE_FORBIDDEN: '参照先の Issue を読めない（権限）',
    TASK_REFERENCE_UNAVAILABLE: '参照先の Issue を取得できなかった（一時的な失敗）',
    TASK_REFERENCE_RESPONSE_TOO_LARGE: '参照先の Issue が大きすぎる',
    TASK_REFERENCE_NO_ROOM_FOR_REFERENCE: '手動の本文が長く、参照の本文を入れる余地がない',
    COMMIT_NOT_APPLIED_TWICE: 'コミットが二度とも反映されなかった（GitHub 側の確認が必要）',
    BRANCH_ADVANCED_BEFORE_COMMIT: 'コミットの前にブランチが進んでいた',
    DEVELOPER_OUTCOME_UNKNOWN: 'モデル呼び出しの結果が不明',
    DEVELOPER_TIMEOUT: 'モデル呼び出しの期限が過ぎた（結果が不明な可能性）',
    DEVELOPER_DUPLICATE_REQUEST: '同じ要求が処理中、または結果が不明',
    DEVELOPER_BUDGET: '予算の予約が拒否された、または単価が不明（モデルは呼ばれていない）',
    DEVELOPER_POLICY_REJECTED: 'モデルの変更案がポリシーに通らなかった',
    CODING_EMPTY_PATCH: '変更が無く終了しました',
    CODING_BUDGET_EXCEEDED: '1 attempt の費用上限に達しました',
    CODING_OVERLOADED: 'モデルが混雑していました',
    // The CODING_ codes below are written from the runbook (docs/runbook-selfdev.md, 6.7 and the Run state table) and CodingPatchSource.
    CODING_AUTH_REQUIRED: '認証に失敗した（運用者が判断する）',
    CODING_NEEDS_HUMAN: 'ホスト側の異常で、運用者の判断が必要（自動の再試行はしない）',
    CODING_TIMEOUT: '実行が時間切れで止められた',
    CODING_OUTPUT_LIMIT: 'CLI の出力が上限を超えた（出力の合計、または 1 行の上限）',
    CODING_RATE_LIMITED: 'レート制限で止まった（自動の再試行はしない）',
    CODING_BAD_OUTPUT: 'CLI の出力を読めなかった、または変更が拒否された',
    CODING_CLI_NONZERO: 'CLI が異常終了した（終了コードが 0 以外、または結果が失敗）',
    CODING_CANCELLED: '実行が取り消された',
    CODING_FAILED: '実行が失敗したが、理由の内容が記録されていない',
    CODING_ORPHANED: '実行の期限（lease）が切れ、結果が分からない（運用者が判断する）',
    CODING_QUEUE_EXPIRED: '実行の待ち行列で 24 時間を過ぎ、期限切れになった（運用者が判断する）',
    CODING_RESULT_MISSING: '変更の結果を読めなかった（再提案はしない）',
    CODING_NO_ATTEMPT: '実行の記録（試行）が見つからなかった',
    CODING_NOT_DEVELOPING: '開発の段階ではなかったため、実行を始めなかった',
    CODING_ENQUEUE_REFUSED: '実行の投入が拒否された（Run が見つからない、開発中でない、または入力が不正）',
    OPERATOR_RELEASED_UNBOUND_PR: '運用者が解放した（PR と結び付いていない）'
  };

  var ACTIONS = {
    RUN_CREATED: '作成',
    RUN_TRANSITION: '状態の変更',
    RUN_PR_BOUND: 'PR と結び付け',
    RUN_RELEASED: '運用者が解放'
  };

  var state = {
    idempotencyKey: newKey(),
    // The kind and the title the key was first used with (null: not used yet). A change of either gets a new key.
    keyFor: null,
    kind: SIMULATED_KIND,
    scope: 'mine',
    runs: null,
    runsOkAt: 0,
    runsFailed: false,
    runsErrorKind: null,
    runsLoading: false,
    runsPending: false,
    runsLastAttemptAt: 0,
    selfDevEnabled: null,
    statusLoading: false,
    statusCheckedAt: 0,
    sessionExpired: false,
    healthOk: null,
    healthCheckedAt: 0,
    healthOkAt: 0,
    healthLoading: false,
    submitting: false,
    detailSeq: 0,
    timer: null,
    release: null,
    autopilotEnabled: false,
    autopilot: null,
    autopilotLoading: false,
    autopilotCheckedAt: 0,
    promotion: null,
    promotionLoading: false,
    goals: null,
    goalsOkAt: 0,
    goalsFailed: false,
    goalsLoading: false,
    goalsPending: false,
    goalsLastAttemptAt: 0,
    openGoalId: null,
    detail: null,
    detailError: null,
    detailLoading: false,
    goalKey: newKey(),
    goalKeyFor: null,
    goalSubmitting: false,
    stop: null,
    budget: null,
    budgetError: null,
    budgetVisible: false,
    budgetLoading: false,
    budgetCheckedAt: 0,
    budgetLastAttemptAt: 0,
    refreshAllRunning: false,
    budgetFx: null,
    budgetDialog: null,
    budgetSubmitting: false,
    orderSubmitting: false,
    exec: null
  };

  function $(id) {
    return document.getElementById(id);
  }

  /** Builds an element. Text only goes through textContent; attributes are restricted to a fixed list. */
  function h(tag, props, children) {
    var node = document.createElement(tag);
    var p = props || {};
    Object.keys(p).forEach(function (k) {
      var v = p[k];
      if (k === 'className') node.className = v;
      else if (k === 'text') node.textContent = v;
      else if (k === 'href') node.setAttribute('href', v);
      else if (k === 'type') node.setAttribute('type', v);
      // Only the fixed constants '_blank' and 'noopener noreferrer' are passed here (see prNode).
      else if (k === 'target' || k === 'rel') node[k] = v;
      // Element ids and ARIA attributes, set as attributes (values are fixed strings or ids this page built).
      else if (k === 'id' || k === 'role' || k.indexOf('aria-') === 0) node.setAttribute(k, v);
      else throw new Error('unsupported prop: ' + k);
    });
    (children || []).forEach(function (c) {
      if (c === null || c === undefined) return;
      node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    });
    return node;
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function str(v) {
    return v === null || v === undefined ? '' : String(v);
  }

  function newKey() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') return window.crypto.randomUUID();
    var b = new Uint8Array(16);
    window.crypto.getRandomValues(b);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    var hex = Array.prototype.map.call(b, function (x) { return ('0' + x.toString(16)).slice(-2); }).join('');
    return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
  }

  function parseTime(s) {
    var t = Date.parse(str(s));
    return isNaN(t) ? null : t;
  }

  function fmtLocal(ms) {
    return new Date(ms).toLocaleString('ja-JP');
  }

  function fmtUtc(ms) {
    return new Date(ms).toISOString().replace('T', ' ').replace(/\.\d{3}Z$/, ' UTC');
  }

  function fmtClock(ms) {
    return new Date(ms).toLocaleTimeString('ja-JP');
  }

  function relative(ms, now) {
    var sec = Math.round((now - ms) / 1000);
    if (sec < 60) return sec <= 0 ? 'たった今' : sec + ' 秒前';
    if (sec < 3600) return Math.floor(sec / 60) + ' 分前';
    if (sec < 86400) return Math.floor(sec / 3600) + ' 時間前';
    return Math.floor(sec / 86400) + ' 日前';
  }

  function has(map, key) {
    return Object.prototype.hasOwnProperty.call(map, key);
  }

  function clip(v, max) {
    var chars = Array.from(str(v));
    return chars.length > max ? chars.slice(0, max).join('') + '…' : chars.join('');
  }

  function stateInfo(s) {
    var c = str(s);
    if (has(STATES, c)) return { label: c, desc: STATES[c] };
    return { label: c === '' ? '（不明）' : c, desc: '不明な状態です（この画面に説明はありません）' };
  }

  function kindInfo(mode) {
    var c = str(mode);
    if (has(KINDS, c)) return KINDS[c];
    return { label: '不明な種別（' + (c === '' ? '空' : c) + '）', cls: 'badge warn', note: '' };
  }

  /**
   * The title of a run for display, as text. Both kinds show the title the authenticated principal submitted (ADR 0008
   * addendum, 2026-10-17). An unknown kind does not show it: its meaning is not known, so nothing is shown.
   */
  function titleOf(run) {
    if (run.mode === 'SIMULATED' || run.mode === 'SELF_DEV') return clip(run.title, MAX_TITLE);
    return '（非表示：種別が不明）';
  }

  /** The reason code as text: the explanation, then the code itself. Unknown codes are shown as codes only. */
  function reasonText(code) {
    if (code === null || code === undefined || str(code) === '') return '';
    var c = str(code);
    if (has(REASONS, c)) return REASONS[c] + '（' + c + '）';
    if (c.indexOf('DEVELOPER_') === 0) return '開発エージェント（モデル）の結果を使えなかった（' + c + '）';
    return '不明な理由（' + c + '）';
  }

  /** Only a github.com pull request URL of the exact shape is a link. Anything else is shown as text. */
  function safePrUrl(url) {
    return typeof url === 'string' && PR_URL.test(url) ? url : null;
  }

  /** The pull request as a link (only when the URL is valid) or as text. Never an unchecked href. */
  function prNode(run) {
    var n = run.pullRequestNumber;
    if (typeof n !== 'number' || Math.floor(n) !== n || n <= 0) return h('span', { text: '-' });
    var repo = typeof run.pullRequestRepository === 'string' ? run.pullRequestRepository + ' ' : '';
    var label = repo + '#' + n;
    var url = safePrUrl(run.pullRequestUrl);
    if (url === null) return h('span', { text: label });
    return h('a', { href: url, target: '_blank', rel: 'noopener noreferrer', text: label + ' ↗' });
  }

  /** Maps a non-2xx status to a fixed message key. The response body is never shown. */
  function kindOf(status) {
    if (status === 400) return 'badRequest';
    if (status === 401) return 'session';
    if (status === 404) return 'notFound';
    if (status === 409) return 'conflict';
    if (status === 415) return 'unsupported';
    if (status === 429) return 'tooMany';
    return 'server';
  }

  function isApi(path) {
    return path.indexOf('/api/') === 0;
  }

  /**
   * Once the session is gone, /api/* can no longer succeed until the page is reloaded (oauth2-proxy then
   * redirects to the Keycloak login). Only the automatic refresh of the run list stops; /health keeps running.
   */
  function markSessionExpired() {
    state.sessionExpired = true;
    stopExecution();
    $('session-banner').hidden = false;
    updateSubmitEnabled();
    updateGoalControls();
    renderRunsStatus(Date.now());
    renderGoalsStatus(Date.now());
    updateOrderControls();
  }

  function fail(path, kind, status, quiet) {
    if (kind === 'session' && isApi(path) && !quiet) markSessionExpired();
    return { ok: false, kind: kind, status: status };
  }

  /** The fixed machine code of a JSON error body (e.g. TOO_LARGE), or undefined. The rest of the body is never used. */
  async function fixedCode(res) {
    try {
      var ct = res.headers.get('content-type') || '';
      if (!/^application\/json(\s*;|\s*$)/i.test(ct)) return undefined;
      var data = await res.json();
      return data && typeof data.code === 'string' && /^[A-Z_]{1,32}$/.test(data.code) ? data.code : undefined;
    } catch (e) {
      return undefined;
    }
  }

  /**
   * Same-origin only. This page sets no credential header: the reverse proxy (oauth2-proxy) adds the token.
   * redirect: 'manual' keeps a redirect to the sign-in page from being followed silently. It shows up as an
   * opaqueredirect response, which is treated as an expired session. The page never builds a redirect URL itself.
   */
  async function apiFetch(path, method, body, quiet) {
    // Client-side guard only: it saves unneeded requests and is not an authorization check. The server still
    // enforces auth on every request. Only a page reload clears sessionExpired (the sign-in happens on reload).
    if (isApi(path) && state.sessionExpired) return { ok: false, kind: 'session' };
    var init = {
      method: method,
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'manual',
      headers: { Accept: 'application/json' }
    };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    var res;
    try {
      res = await fetch(path, init);
    } catch (e) {
      return { ok: false, kind: 'network' };
    }
    if (res.type === 'opaqueredirect' || res.redirected) return fail(path, 'session', undefined, quiet);
    if (!res.ok) {
      var failed = fail(path, kindOf(res.status), res.status, quiet);
      failed.code = await fixedCode(res);
      return failed;
    }
    // A 2xx that is not JSON (e.g. an HTML sign-in page served after a followed redirect) is an expired session for /api/*.
    var contentType = res.headers.get('content-type') || '';
    if (!/^application\/json(\s*;|\s*$)/i.test(contentType)) {
      return fail(path, isApi(path) ? 'session' : 'invalid', undefined, quiet);
    }
    try {
      return { ok: true, status: res.status, data: await res.json() };
    } catch (e) {
      return { ok: false, kind: 'invalid' };
    }
  }

  // ---- Submit -----------------------------------------------------------

  function showResult(text, cls) {
    var el = $('submit-result');
    el.textContent = text;
    el.className = 'result' + (cls ? ' ' + cls : '');
  }

  /** Whether a self-development task may be submitted: only when the status API says enabled (never on a guess). */
  function selfDevAvailable() {
    return state.selfDevEnabled === true;
  }

  /** The kind radios: self-development only when available; both locked while a send or an expired session blocks submit. */
  function updateKindControls() {
    var blocked = state.submitting || state.sessionExpired;
    $('kind-sim').disabled = blocked;
    $('kind-self').disabled = blocked || !selfDevAvailable();
    $('kind-self').checked = state.kind === SELF_DEV_KIND;
    $('kind-sim').checked = state.kind === SIMULATED_KIND;
    // The body field belongs to the self-development kind only: the simulated Task API takes no body.
    $('body-block').hidden = state.kind !== SELF_DEV_KIND;
    $('body').disabled = blocked;
  }

  function updateBodyCounter() {
    var n = Array.from($('body').value).length;
    $('body-count').textContent = n + ' / ' + MAX_BODY;
  }

  function renderKindNote() {
    var el = $('kind-note');
    if (state.selfDevEnabled === true) {
      el.textContent = '自己開発を選ぶと、GitHub に PR を作成する Run になります（kivra は PR をマージしません）。';
    } else if (state.selfDevEnabled === false) {
      el.textContent = '自己開発は無効のため、シミュレーションだけ投入できます。';
    } else {
      el.textContent = '自己開発の設定を取得できていないため、自己開発は選べません。';
    }
  }

  /** Called after every status read. A self-development selection is dropped when it is no longer available. */
  function applySelfDevAvailability() {
    if (state.kind === SELF_DEV_KIND && !selfDevAvailable()) {
      state.kind = SIMULATED_KIND;
      newSubmitKey();
    }
    updateKindControls();
    renderKindNote();
  }

  /** A new key for the next submission. Used after a success, a 409, or a change of kind. */
  function newSubmitKey() {
    state.idempotencyKey = newKey();
    state.keyFor = null;
  }

  function onKindChange() {
    var next = $('kind-self').checked && selfDevAvailable() ? SELF_DEV_KIND : SIMULATED_KIND;
    if (next === state.kind) {
      updateKindControls();
      return;
    }
    state.kind = next;
    newSubmitKey();
    updateKindControls();
  }

  /**
   * The only place that decides whether the submit button is enabled. Use it after every change to
   * submitting or sessionExpired, so a finished send never re-enables the button after the session expired.
   */
  function updateSubmitEnabled() {
    var blocked = state.submitting || state.sessionExpired;
    $('submit-btn').disabled = blocked;
    $('submit-note').hidden = !state.sessionExpired;
    updateKindControls();
  }

  function updateCounter() {
    var n = Array.from($('title').value).length;
    $('title-count').textContent = n + ' / ' + MAX_TITLE;
  }

  /**
   * The key stays the same while the kind and the title stay the same, so a retry after a network failure is the same
   * submission. A change of either one makes a new key (the server would refuse the old key with 409 anyway).
   */
  function keyForSubmission(kind, title, body) {
    var sig = kind + '\n' + title + '\n' + body;
    if (state.keyFor !== sig) {
      if (state.keyFor !== null) state.idempotencyKey = newKey();
      state.keyFor = sig;
    }
    return state.idempotencyKey;
  }

  async function onSubmit(ev) {
    ev.preventDefault();
    // Enter in the title field also reaches here (no POST while the session is expired).
    if (state.submitting || state.sessionExpired) {
      updateSubmitEnabled();
      return;
    }
    var title = $('title').value.trim();
    var n = Array.from(title).length;
    if (n === 0 || n > MAX_TITLE || CONTROL_CHARS.test(title)) {
      showResult(MESSAGES.badRequest, 'error');
      return;
    }
    var kind = state.kind;
    if (kind === SELF_DEV_KIND && !selfDevAvailable()) {
      showResult(MESSAGES.invalid, 'error');
      return;
    }
    // The body is sent only for the self-development kind, and only when it is not blank.
    var text = kind === SELF_DEV_KIND ? $('body').value.trim() : '';
    if (Array.from(text).length > MAX_BODY || BODY_CONTROL_CHARS.test(text)) {
      showResult(MESSAGES.badRequest, 'error');
      return;
    }
    var body = { title: title, idempotencyKey: keyForSubmission(kind, title, text) };
    if (text !== '') body.body = text;
    state.submitting = true;
    updateSubmitEnabled();
    showResult('送信中です…', '');

    var r;
    try {
      r = kind === SELF_DEV_KIND
        ? await apiFetch('/api/selfdev/tasks', 'POST', body)
        : await apiFetch('/api/tasks', 'POST', body);
    } finally {
      state.submitting = false;
      updateSubmitEnabled();
    }

    var accepted = r.ok && (r.status === 202 || r.status === 200) && r.data && typeof r.data.runId === 'string';
    if (accepted) {
      // A new key only after success. (Same key + same content is a replay that returns the same Run.)
      newSubmitKey();
      $('title').value = '';
      $('body').value = '';
      updateCounter();
      updateBodyCounter();
      var kindLabel = kind === SELF_DEV_KIND ? '自己開発' : 'シミュレーション';
      var stateText = '状態: ' + stateInfo(r.data.state).label + '（' + kindLabel + '）';
      if (r.status === 202) {
        showResult('受け付けました。Run ID: ' + str(r.data.runId) + ' / ' + stateText, 'ok');
      } else {
        showResult('同じ投入として既に受け付け済みです（既存の Run）。Run ID: ' + str(r.data.runId) + ' / ' + stateText, 'ok');
      }
      refreshRuns();
      return;
    }

    // 409: the key was used with other content. Make a new key; the operator sends again on purpose.
    if (r.kind === 'conflict') newSubmitKey();
    var msg = r.kind === 'network' ? MESSAGES.submitNetwork : (r.ok ? MESSAGES.invalid : (MESSAGES[r.kind] || MESSAGES.server));
    showResult(msg, 'error');
  }

  // ---- Run list ---------------------------------------------------------

  function runRow(run, all) {
    var id = str(run.runId);
    var created = parseTime(run.createdAt);
    var when = created === null ? str(run.createdAt) : fmtLocal(created) + '（' + relative(created, Date.now()) + '）';
    var kind = kindInfo(run.mode);
    var st = stateInfo(run.state);
    var metaKids = [
      h('span', { className: kind.cls, text: kind.label }),
      h('span', { className: 'time', text: when })
    ];
    if (all) metaKids.push(h('span', { className: 'time', text: '作成者: ' + str(run.createdBy) }));
    var reason = reasonText(run.reasonCode);
    // The detail link and the pull request link are siblings: a link cannot sit inside another link.
    return h('li', { className: 'run-item' }, [
      h('a', { className: 'run-link', href: '#/runs/' + encodeURIComponent(id) + (all ? '?scope=all' : '') }, [
        h('div', { className: 'run-head' }, [
          h('span', { className: 'run-title', text: titleOf(run) }),
          h('span', { className: 'badge', text: '状態: ' + st.label })
        ]),
        h('div', { className: 'run-meta' }, metaKids)
      ]),
      h('div', { className: 'run-sub' }, [
        reason ? h('span', { className: 'reason', text: '理由: ' + reason }) : null,
        h('span', { className: 'run-pr' }, ['PR ', prNode(run)])
      ])
    ]);
  }

  function renderRuns() {
    var ul = $('runs-list');
    clear(ul);
    if (!state.runs) return;
    if (state.runs.length === 0) {
      ul.appendChild(h('li', { className: 'empty', text: 'まだ Run はありません。上のフォームから Task を投入できます。' }));
      return;
    }
    var all = state.scope === 'all';
    state.runs.forEach(function (run) {
      if (run && typeof run === 'object' && typeof run.runId === 'string') ul.appendChild(runRow(run, all));
    });
  }

  function renderRunsStatus(now) {
    var el = $('runs-status');
    var list = $('runs-list');
    if (state.sessionExpired) {
      list.classList.add('stale');
      el.className = 'meta error';
      if (state.runsOkAt === 0) {
        el.textContent = '一覧を取得できていません（セッション切れ）';
      } else {
        var secAgo = Math.max(0, Math.round((now - state.runsOkAt) / 1000));
        el.textContent = secAgo + ' 秒前のデータ（セッション切れ）';
      }
      return;
    }
    if (state.runsFailed) {
      list.classList.add('stale');
      var reason = MESSAGES[state.runsErrorKind] || MESSAGES.server;
      if (state.runsOkAt === 0) {
        el.textContent = '一覧を取得できていません（更新に失敗: ' + reason + '）';
      } else {
        var sec = Math.max(0, Math.round((now - state.runsOkAt) / 1000));
        el.textContent = sec + ' 秒前のデータ（更新に失敗: ' + reason + '）';
      }
      el.className = 'meta error';
      return;
    }
    list.classList.remove('stale');
    el.className = 'meta';
    if (state.runsOkAt === 0) {
      el.textContent = NOT_FETCHED_TEXT;
    } else {
      el.textContent = '最終取得: ' + fmtLocal(state.runsOkAt) + '（' + relative(state.runsOkAt, now) + '）';
    }
  }

  async function refreshRuns() {
    // After an expired session the automatic refresh stops (every /api/runs call would only get 401 again).
    if (state.sessionExpired) return;
    if (state.runsLoading) {
      // A change of scope while a request is in flight: fetch again once it finishes.
      state.runsPending = true;
      return;
    }
    state.runsLoading = true;
    state.runsLastAttemptAt = Date.now();
    var scope = state.scope;
    try {
      var r = scope === 'all'
        ? await apiFetch('/api/admin/runs?limit=100', 'GET')
        : await apiFetch('/api/runs', 'GET');
      // The answer to a scope that is no longer selected is dropped.
      if (scope !== state.scope) return;
      if (r.ok && Array.isArray(r.data)) {
        state.runs = r.data;
        state.runsOkAt = Date.now();
        state.runsFailed = false;
        state.runsErrorKind = null;
        renderRuns();
      } else {
        state.runsFailed = true;
        state.runsErrorKind = r.ok ? 'invalid' : r.kind;
      }
    } finally {
      state.runsLoading = false;
      renderRunsStatus(Date.now());
      if (state.runsPending) {
        state.runsPending = false;
        refreshRuns();
      }
    }
  }

  function onScopeChange() {
    state.scope = $('scope-all').checked ? 'all' : 'mine';
    state.runs = null;
    state.runsOkAt = 0;
    state.runsFailed = false;
    renderRuns();
    refreshRuns();
  }

  // ---- Self-development status (the banner above the list) ---------------

  async function refreshStatus() {
    if (state.statusLoading) return;
    state.statusLoading = true;
    state.statusCheckedAt = Date.now();
    try {
      var r = await apiFetch('/api/selfdev/status', 'GET');
      state.selfDevEnabled = r.ok && r.data && typeof r.data.enabled === 'boolean' ? r.data.enabled : null;
    } finally {
      state.statusLoading = false;
      renderModeNote();
      applySelfDevAvailability();
    }
  }

  function renderModeNote() {
    var el = $('mode-note');
    if (state.selfDevEnabled === true) {
      el.textContent = '自己開発の Run は、GitHub に PR を作成します（実際に実行されます）。シミュレーションの Run は AI を起動しません。';
    } else if (state.selfDevEnabled === false) {
      el.textContent = '自己開発は無効です。このサーバーで作られる Run は、シミュレーションだけです。';
    } else {
      el.textContent = '自己開発の設定を取得できていません。';
    }
  }

  // ---- Health -----------------------------------------------------------

  async function checkHealth() {
    if (state.healthLoading) return;
    state.healthLoading = true;
    try {
      var r = await apiFetch('/health', 'GET');
      state.healthCheckedAt = Date.now();
      state.healthOk = !!(r.ok && r.data && r.data.status === 'ok');
      if (state.healthOk) state.healthOkAt = state.healthCheckedAt;
    } finally {
      state.healthLoading = false;
      renderHealth();
    }
  }

  function renderHealth() {
    var el = $('health-status');
    if (state.healthOk === null) {
      el.textContent = '接続: 未確認';
      el.className = 'conn';
    } else if (state.healthOk) {
      el.textContent = '接続: OK（確認 ' + fmtClock(state.healthCheckedAt) + '）';
      el.className = 'conn ok';
    } else {
      var last = state.healthOkAt ? '、最後に成功 ' + fmtClock(state.healthOkAt) : '、成功したことはありません';
      el.textContent = '接続: 失敗（確認 ' + fmtClock(state.healthCheckedAt) + last + '）';
      el.className = 'conn ng';
    }
  }

  // ---- Detail -----------------------------------------------------------

  function eventLine(ev) {
    var t = parseTime(ev.occurredAt);
    var action = has(ACTIONS, str(ev.action)) ? ACTIONS[str(ev.action)] : str(ev.action);
    var text = (t === null ? str(ev.occurredAt) : fmtLocal(t)) + '  ' + action;
    if (ev.toState) text += ' → ' + str(ev.toState);
    if (ev.reasonCode) text += '（' + reasonText(ev.reasonCode) + '）';
    text += '  [' + str(ev.result) + ']';
    return h('li', { text: text });
  }

  function renderDetail(container, run, all) {
    clear(container);
    if (!run || typeof run !== 'object') {
      container.appendChild(h('p', { className: 'error', text: MESSAGES.invalid }));
      return;
    }
    var created = parseTime(run.createdAt);
    var updated = parseTime(run.updatedAt);
    var kind = kindInfo(run.mode);
    var st = stateInfo(run.state);
    var reason = reasonText(run.reasonCode);
    var rows = [
      ['種別', kind.label + (kind.note ? '（' + kind.note + '）' : '')],
      ['タイトル', titleOf(run)],
      ['状態', st.label + ' — ' + st.desc],
      ['理由', reason === '' ? 'なし' : reason],
      ['PR', prNode(run)]
    ];
    // The reference a self-development task was resolved from (owner/repo#n). Shown as text, like the title.
    if (str(run.taskSource) !== '') rows.push(['解決元（参照）', str(run.taskSource)]);
    if (all) rows.push(['作成者', str(run.createdBy)]);
    rows = rows.concat([
      ['Run ID', str(run.runId)],
      ['Task ID', str(run.taskId)],
      ['作成日時（ローカル）', created === null ? str(run.createdAt) : fmtLocal(created)],
      ['作成日時（UTC）', created === null ? '' : fmtUtc(created)],
      ['更新日時（ローカル）', updated === null ? str(run.updatedAt) : fmtLocal(updated)],
      ['この画面の取得時刻', fmtLocal(Date.now())]
    ]);
    var dl = h('dl', {}, []);
    rows.forEach(function (row) {
      dl.appendChild(h('dt', { text: row[0] }));
      dl.appendChild(h('dd', {}, [row[1]]));
    });
    container.appendChild(dl);
    var releaseKind = releaseKindOf(run);
    if (releaseKind !== null) container.appendChild(releaseActionNode(run, all, releaseKind));
    if (run.mode === SELF_DEV_KIND && typeof run.runId === 'string' && RUN_ID_RE.test(run.runId)) {
      container.appendChild(beginExecution(run.runId, str(run.state)));
    }
    if (Array.isArray(run.events)) {
      container.appendChild(h('h3', { text: '状態の経過' }));
      if (run.events.length === 0) {
        container.appendChild(h('p', { className: 'empty', text: '記録がありません' }));
      } else {
        var ol = h('ol', { className: 'events' }, []);
        run.events.forEach(function (ev) {
          if (ev && typeof ev === 'object') ol.appendChild(eventLine(ev));
        });
        container.appendChild(ol);
      }
    }
  }

  async function loadDetail(id, all) {
    stopExecution();
    var seq = ++state.detailSeq;
    var body = $('detail-body');
    clear(body);
    if (id === '') {
      body.appendChild(h('p', { className: 'error', text: 'リンクが正しくありません' }));
      return;
    }
    // After an expired session no detail request is sent; the same guidance as the banner is shown instead.
    if (state.sessionExpired) {
      body.appendChild(h('p', { className: 'error', text: MESSAGES.session }));
      return;
    }
    body.appendChild(h('p', { className: 'empty', text: '読み込み中…' }));
    var r = all
      ? await apiFetch('/api/admin/runs/' + encodeURIComponent(id), 'GET')
      : await apiFetch('/api/runs/' + encodeURIComponent(id), 'GET');
    if (seq !== state.detailSeq) return;
    if (!r.ok) {
      clear(body);
      body.appendChild(h('p', { className: 'error', text: MESSAGES[r.kind] || MESSAGES.server }));
      return;
    }
    renderDetail(body, r.data, all);
  }

  /**
   * Null for the list view. For #/runs/<id> (and #/runs/<id>?scope=all) it returns { id, all }. The id is '' when it
   * cannot be decoded.
   */
  function parseRoute() {
    var m = /^#\/runs\/([^?]+)(\?.*)?$/.exec(window.location.hash);
    if (!m) return null;
    var all = /(^|[?&])scope=all(&|$)/.test(m[2] || '');
    try {
      return { id: decodeURIComponent(m[1]), all: all };
    } catch (e) {
      return { id: '', all: all };
    }
  }

  // ---- Pages (the side navigation) ---------------------------------------
  // One page per hash. gate is the section whose own hidden (set by its render function from the API's answer) decides
  // whether the page's nav item is shown; a page without a gate is always available.
  var PAGES = [
    { id: 'tasks', hash: '#/', title: 'タスク', gate: null },
    { id: 'goals', hash: '#/goals', title: '開発目標', gate: 'plans-section' },
    { id: 'orders', hash: '#/orders', title: 'order', gate: 'orders-section' },
    { id: 'approvals', hash: '#/approvals', title: '承認', gate: 'approval-section' },
    { id: 'budget', hash: '#/budget', title: '予算', gate: 'budget-section' },
    { id: 'memory', hash: '#/memory', title: '記憶', gate: 'memory-section' }
  ];
  // Set once a page's API answer is known, so a deep link is not sent back to #/ before the answer arrives.
  var NAV = { settled: {} };

  /** The page id for the current hash: a run detail belongs to 'tasks'; anything unknown is null (route sends it to #/). */
  function pageIdForHash() {
    var h = window.location.hash;
    if (parseRoute() !== null) return 'tasks';
    if (h === '' || h === '#') return 'tasks';
    for (var i = 0; i < PAGES.length; i++) {
      if (PAGES[i].hash === h) return PAGES[i].id;
    }
    return null;
  }

  function currentPageId() {
    return pageIdForHash() || 'tasks';
  }

  function replaceHash(h) {
    if (window.history && typeof window.history.replaceState === 'function') {
      window.history.replaceState(null, '', h);
    } else {
      window.location.hash = h;
    }
  }

  function pendingCount() {
    return ORD.enabled && ORD.approver && ORD.requests !== null ? ORD.requests.length : null;
  }

  /** The page switch, the nav items and their badge, the header's count and the title. Reads the sections' own hidden. */
  function renderNav() {
    var cur = currentPageId();
    var detail = parseRoute() !== null;
    PAGES.forEach(function (p) {
      if (p.id !== cur || p.gate === null || detail) return;
      if ($(p.gate).hidden && NAV.settled[p.id] === true) {
        replaceHash('#/');
        cur = 'tasks';
      }
    });
    PAGES.forEach(function (p) {
      var shown = p.gate === null || !$(p.gate).hidden;
      var link = $('nav-' + p.id);
      link.hidden = !shown;
      if (p.id === cur) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
      $('page-' + p.id).hidden = p.id !== cur;
    });
    var n = pendingCount();
    var badge = $('nav-approvals-badge');
    badge.hidden = n === null;
    var label = n === null ? '' : '保留中の要求 ' + n + ' 件';
    badge.textContent = n === null ? '' : String(n);
    if (n === null) badge.removeAttribute('aria-label');
    else badge.setAttribute('aria-label', label);
    var head = $('header-approvals');
    head.hidden = n === null || n < 1;
    head.textContent = n === null ? '' : '承認待ち ' + n + ' 件';
    if (n === null) head.removeAttribute('aria-label');
    else head.setAttribute('aria-label', label);
    var title = detail ? 'Run の詳細' : PAGES.filter(function (p) { return p.id === cur; })[0].title;
    document.title = 'Kivra \u2014 ' + title;
  }

  function route() {
    if (window.location.hash === '#main') return;
    stopExecution();
    if (pageIdForHash() === null) replaceHash('#/');
    var r = parseRoute();
    var showDetail = r !== null;
    $('view-list').hidden = showDetail;
    $('view-detail').hidden = !showDetail;
    $('detail-notice').textContent = '';
    renderNav();
    if (showDetail) {
      loadDetail(r.id, r.all);
    } else {
      window.scrollTo(0, 0);
    }
  }

  // ---- Release (UNKNOWN, and a PULL_REQUEST without a binding) ----------

  var RUN_ID_RE = /^[0-9A-Z]{26}$/;

  var RELEASE_TEXT = {
    UNKNOWN: {
      button: '運用者の解放…',
      title: '運用者の解放（UNKNOWN から CANCELLED へ）',
      explain: 'この Run を CANCELLED にします。PR は閉じません・マージしません（PR の扱いは人が決めます）。同じ原因が続けば、次の Run も UNKNOWN になる可能性があります。解放は監査ログに残ります。'
    },
    UNBOUND_PR: {
      button: '解放（束縛なし）…',
      title: '解放（GitHub の PR と結び付いていない Run）',
      explain: 'この Run を CANCELLED にします。この Run は GitHub の PR と結び付いていないため、PR の状態は確認しません。GitHub には何も書き込みません（PR は閉じず、マージもしません）。'
    }
  };

  /**
   * Which release a run gets, or null. UNKNOWN (POST .../release) for a self-development run in UNKNOWN; UNBOUND_PR
   * (POST .../release-unbound-pr) for a self-development PULL_REQUEST with no pull request binding in the detail response.
   * The server decides again on every request.
   */
  function releaseKindOf(run) {
    if (run.mode !== SELF_DEV_KIND || typeof run.runId !== 'string' || !RUN_ID_RE.test(run.runId)) return null;
    if (run.state === 'UNKNOWN') return 'UNKNOWN';
    if (run.state === 'PULL_REQUEST' && typeof run.pullRequestNumber !== 'number') return 'UNBOUND_PR';
    return null;
  }

  function releaseActionNode(run, all, kind) {
    var button = h('button', { type: 'button', className: 'secondary', text: RELEASE_TEXT[kind].button });
    button.addEventListener('click', function () {
      openRelease(run, all, kind);
    });
    return h('div', { className: 'detail-actions' }, [button]);
  }

  function showReleaseError(text) {
    var el = $('release-error');
    el.textContent = text;
    el.hidden = text === '';
  }

  function updateReleaseCount() {
    $('release-count').textContent = Array.from($('release-reason').value.trim()).length + ' / ' + RELEASE_MAX;
  }

  function openRelease(run, all, kind) {
    var text = RELEASE_TEXT[kind];
    state.release = { runId: run.runId, all: all, kind: kind };
    $('release-title').textContent = text.title;
    $('release-explain').textContent = text.explain;
    showReleaseError('');
    openReasonDialog({
      dialog: 'release-dialog', input: 'release-reason', submit: 'release-submit', cancel: 'release-cancel',
      max: RELEASE_MAX, send: submitRelease
    });
    updateReleaseCount();
  }

  /** Reloads the detail only when it is still the page on screen (the operator may have navigated away). */
  function reloadReleasedRun(rel) {
    var cur = parseRoute();
    if (cur && cur.id === rel.runId) loadDetail(rel.runId, rel.all);
  }

  /** Sends the reason (already validated by openReasonDialog). The dialog stays open on failure. */
  async function submitRelease(reason) {
    var rel = state.release;
    if (!rel || state.sessionExpired) return;
    showReleaseError('');
    var r = rel.kind === 'UNKNOWN'
      ? await apiFetch('/api/admin/selfdev/runs/' + encodeURIComponent(rel.runId) + '/release', 'POST', { reason: reason })
      : await apiFetch('/api/admin/selfdev/runs/' + encodeURIComponent(rel.runId) + '/release-unbound-pr', 'POST', { reason: reason });

    if (r.ok) {
      state.release = null;
      $('release-dialog').close();
      $('detail-notice').textContent = MESSAGES.releaseDone;
      reloadReleasedRun(rel);
      refreshRuns();
      return;
    }
    var msg;
    if (r.kind === 'network') msg = MESSAGES.releaseNetwork;
    else if (r.status === 400) msg = MESSAGES.releaseBadRequest;
    else if (r.status === 409) msg = MESSAGES.releaseConflict;
    else if (r.status === 404) msg = MESSAGES.notFound;
    else msg = MESSAGES[r.kind] || MESSAGES.server;
    showReleaseError(msg);
    // A 409 means the run has changed: show its current state under the dialog.
    if (r.status === 409) reloadReleasedRun(rel);
  }


  // ---- Budget (operator): the month's spend, the CLI / API split, the FX warning and the UNPRICED list (ADR 0003, #230) ----
  // Shown only when GET /api/admin/budget/status answers 200 to this user. The requests are quiet: a 401 or 403 here means
  // "not for this user" and does not end the session. Only text enters the DOM (textContent, or h() with text).

  var ACTUAL_RE = /^[0-9]{1,12}(\.[0-9]{1,6})?$/;
  var YEN_RE = /^-?[0-9]+(\.[0-9]+)?$/;
  var CODING_KEY_RE = /^coding:([^:]+):([0-9]+)$/;
  var CODE_RE = /^[A-Z_]{1,32}$/;
  var FX_WARN_DAYS = 2;
  var BUDGET_TEXT_MAX = 500;
  var BUDGET_ACTUAL_MAX = 24;
  var BUDGET_ACTUAL_HINT = '半角の数字と小数点で入力します（小数は 6 桁まで）。実額は計上額の 100 倍まで。';

  var BUDGET_REASON = {
    COST_NOT_ESTABLISHED: '費用が確定しない（予約額のまま計上。実額の確認が必要）',
    TTL_ELAPSED: '期限切れ（保守的に予約額で計上。請求が無かったかもしれない）'
  };

  var BUDGET_MSG = {
    loadFailed: '予算の状態を取得できませんでした。「今すぐ更新」で再度お試しください。',
    invalid: '予算の応答の形式が想定と違います。「今すぐ更新」で再度お試しください。',
    network: '通信に失敗しました。「今すぐ更新」で再度お試しください。',
    actualBad: '実額は円の数字（小数は 6 桁まで、12 桁まで）で入力してください。空欄は「実額不明」です。',
    textBad: '根拠と理由を入力してください（1〜500 文字、制御文字は使えません）。',
    tooLarge: '実額が計上額の 100 倍を超えています。実額を確かめてください（上限は計上額の 100 倍まで）。',
    notComparable: '実額の単位が予約と合いません。円の数字で入力してください。',
    badRequest: '入力を確認してください（実額は円の数字、根拠と理由は 1〜500 文字、制御文字は使えません）。',
    notFound: 'この予約は見つかりません。「今すぐ更新」で一覧を確認してください。',
    conflict: 'この予約は未確定ではありません（既に解除されたか、状態が変わりました）。一覧を更新してください。',
    unavailable: '予算の台帳に書けませんでした。予約は変わっていません。時間をおいて、もう一度お試しください。',
    acknowledgedDone: '解除しました（監査ログに記録されました）。',
    alreadyDone: '既に解除済みでした（変更はありません）。'
  };

  /** A yen amount from the server (a decimal string) as text, or a fixed placeholder when it is not a number. */
  function moneyText(v) {
    var t = str(v);
    if (!YEN_RE.test(t)) return '（不明）';
    return Number(t).toLocaleString('ja-JP', { maximumFractionDigits: 6 }) + ' 円';
  }

  function budgetNum(v) {
    var t = str(v);
    return YEN_RE.test(t) ? Number(t) : null;
  }

  /** The reservation key as a short, readable label. A CLI key (coding:<attemptId>:<generation>) shows its attempt. */
  function keyLabel(key) {
    var m = CODING_KEY_RE.exec(key);
    if (m) return 'CLI の attempt ' + m[1].slice(0, 8) + '…（世代 ' + m[2] + '）';
    return '予約 ' + key.slice(0, 8) + '…';
  }

  function pathLabel(path) {
    return path === 'cli' ? 'CLI 経路' : 'API 経路';
  }

  /** The body is shown only when every field this screen reads has the expected shape. */
  function isBudgetBody(d) {
    if (!d || typeof d !== 'object') return false;
    if (typeof d.period !== 'string' || typeof d.unpricedCount !== 'number' || !Array.isArray(d.unpriced)) return false;
    var money = [d.limitJpy, d.committedJpy, d.reservedJpy, d.remainingJpy];
    if (!money.every(function (v) { return YEN_RE.test(str(v)); })) return false;
    if (!d.paths || !d.paths.api || !d.paths.cli) return false;
    return YEN_RE.test(str(d.paths.api.committedJpy)) && YEN_RE.test(str(d.paths.cli.committedJpy));
  }

  /** The screen's refresh. Only the operator sees the section: 401, 403 or 404 keep it hidden. */
  async function refreshBudget() {
    if (state.budgetLoading || state.sessionExpired) return;
    state.budgetLoading = true;
    state.budgetLastAttemptAt = Date.now();
    try {
      var results = await Promise.all([
        apiFetch('/api/admin/budget/status', 'GET', undefined, true),
        apiFetch('/api/admin/budget/fx', 'GET', undefined, true)
      ]);
      var r = results[0];
      if (!r.ok && (r.status === 401 || r.status === 403 || r.status === 404)) {
        state.budgetVisible = false;
        state.budget = null;
        state.budgetError = null;
      } else {
        state.budgetVisible = true;
        if (!r.ok) {
          state.budget = null;
          state.budgetError = r.kind === 'network' ? 'network' : 'loadFailed';
        } else if (!isBudgetBody(r.data)) {
          state.budget = null;
          state.budgetError = 'invalid';
        } else {
          state.budget = r.data;
          state.budgetError = null;
          state.budgetCheckedAt = Date.now();
        }
      }
      var f = results[1];
      state.budgetFx = f.ok && f.data && typeof f.data === 'object' && 'fx' in f.data
        ? { ok: true, fx: f.data.fx }
        : { ok: false };
    } finally {
      state.budgetLoading = false;
      NAV.settled.budget = true;
      renderBudget();
      renderNav();
    }
  }

  function renderBudget() {
    $('budget-section').hidden = !state.budgetVisible;
    if (!state.budgetVisible) return;
    var d = state.budget;
    $('budget-body').hidden = d === null;
    $('budget-status').textContent = state.budgetLoading
      ? '読み込み中…'
      : d === null
        ? (BUDGET_MSG[state.budgetError] || BUDGET_MSG.loadFailed)
        : '最終取得: ' + fmtLocal(state.budgetCheckedAt);
    if (d === null) return;
    renderBudgetBar(d);
    renderBudgetFx();
    $('budget-month').textContent = '対象の月: ' + d.period + '（JST の暦月。上限は月ごとに数えます）';
    $('budget-path-api').textContent = moneyText(d.paths.api.committedJpy);
    $('budget-path-cli').textContent = moneyText(d.paths.cli.committedJpy);
    renderUnpriced(d);
  }

  /** The bar and its text. An overspent month says so in words, not only by the bar. */
  function renderBudgetBar(d) {
    var limit = budgetNum(d.limitJpy);
    var committed = budgetNum(d.committedJpy);
    var reserved = budgetNum(d.reservedJpy);
    var remaining = budgetNum(d.remainingJpy);
    var used = committed + reserved;
    var pct = limit > 0 ? (used / limit) * 100 : 0;
    var bar = $('budget-bar');
    bar.max = 100;
    bar.value = Math.round(Math.min(100, Math.max(0, pct)) * 100) / 100;
    var text = '確定 ' + moneyText(d.committedJpy) + ' + 予約中 ' + moneyText(d.reservedJpy) + ' / 上限 ' + moneyText(d.limitJpy) +
      '（使用 ' + Math.floor(pct) + '%）。';
    if (remaining < 0) {
      text += '上限を超えています（超過 ' + moneyText(String(-remaining)) + '）。';
    } else {
      text += '残り ' + moneyText(d.remainingJpy) + '。';
    }
    var el = $('budget-bar-text');
    el.textContent = text;
    el.className = remaining < 0 ? 'meta error' : 'meta';
  }

  /** The FX warning. Two days or less is a warning (amber, with words). An expired rate is an error (red, with words). */
  function renderBudgetFx() {
    var el = $('budget-fx');
    var f = state.budgetFx;
    el.hidden = false;
    if (!f || !f.ok) {
      el.className = 'meta';
      el.textContent = '為替の状態を取得できませんでした。';
      return;
    }
    if (!f.fx) {
      el.className = 'meta';
      el.textContent = '為替レートは設定されていません（USD の単価は使っていません）。';
      return;
    }
    var fx = f.fx;
    var asOf = str(fx.asOf);
    var days = typeof fx.daysLeft === 'number' ? fx.daysLeft : null;
    if (fx.expired === true) {
      el.className = 'budget-alert error';
      el.textContent = '為替レートが失効しています（基準日 ' + asOf + '）。失効中は CLI の claim が止まり、報告は金額 0 の未確定になります。レートを更新してください（runbook §4.8）。';
    } else if (days !== null && days <= FX_WARN_DAYS) {
      el.className = 'budget-alert warn';
      el.textContent = '為替レートの有効期限まで残り ' + days + ' 日です（基準日 ' + asOf + '）。期限が切れる前に更新してください。';
    } else {
      el.className = 'meta';
      el.textContent = '為替レート: 基準日 ' + asOf + '、残り ' + (days === null ? '（不明）' : days + ' 日');
    }
  }

  function renderUnpriced(d) {
    var list = $('budget-unpriced-list');
    clear(list);
    var rows = d.unpriced.filter(function (row) {
      return row && typeof row.reservationKey === 'string' && typeof row.scopeId === 'string' && typeof row.period === 'string';
    });
    $('budget-unpriced-count').textContent = d.unpricedCount === 0
      ? '未確定の予約はありません。'
      : '未確定の予約: ' + d.unpricedCount + ' 件。1 件でも残っている間は、新しい予約（CLI の claim を含む）が UNKNOWN_COST で止まります。';
    rows.forEach(function (row) {
      var button = h('button', {
        type: 'button',
        className: 'secondary',
        text: '解除…',
        'aria-label': keyLabel(row.reservationKey) + 'を解除'
      });
      button.addEventListener('click', function () { openBudgetAck(row); });
      list.appendChild(h('li', { className: 'run-item budget-unpriced-item' }, [
        h('div', { className: 'run-title', text: keyLabel(row.reservationKey) + '（' + pathLabel(row.path) + '）' }),
        h('div', { className: 'meta', text: '金額 ' + moneyText(row.amountJpy) + '、期間 ' + str(row.period) + '、状態 ' + str(row.state) }),
        h('div', { className: 'meta', text: '理由: ' + (BUDGET_REASON[row.reason] || str(row.reason)) }),
        h('div', { className: 'detail-actions' }, [button])
      ]));
    });
    var note = $('budget-unpriced-note');
    var more = d.unpricedCount > rows.length;
    note.hidden = !more;
    note.textContent = more
      ? '一覧は ' + rows.length + ' 件までです。残りは runbook §6.13 の SQL で確認してください。'
      : '';
  }

  // ---- The acknowledgement dialog: two steps (edit, then confirm) ----

  function budgetFormState() {
    var actual = $('budget-actual').value.trim();
    var evidence = $('budget-evidence').value.trim();
    var reason = $('budget-reason').value.trim();
    var actualOk = actual === '' || (ACTUAL_RE.test(actual) && actual.length <= BUDGET_ACTUAL_MAX);
    var textOk = function (t) {
      var n = Array.from(t).length;
      return n >= 1 && n <= BUDGET_TEXT_MAX && !CONTROL_CHARS.test(t);
    };
    return {
      actual: actual,
      actualOk: actualOk,
      evidence: evidence,
      reason: reason,
      textOk: textOk(evidence) && textOk(reason),
      ok: actualOk && textOk(evidence) && textOk(reason)
    };
  }

  function showBudgetError(text) {
    var el = $('budget-dialog-error');
    el.textContent = text;
    el.hidden = text === '';
  }

  function setBudgetStage(stage) {
    var confirm = stage === 'confirm';
    $('budget-dialog-edit').hidden = confirm;
    $('budget-dialog-confirm').hidden = !confirm;
    $('budget-back').hidden = !confirm;
    $('budget-submit').hidden = !confirm;
    $('budget-next').hidden = confirm;
    if (state.budgetDialog) state.budgetDialog.stage = stage;
  }

  function updateBudgetCounts() {
    $('budget-evidence-count').textContent = Array.from($('budget-evidence').value).length + ' / ' + BUDGET_TEXT_MAX;
    $('budget-reason-count').textContent = Array.from($('budget-reason').value).length + ' / ' + BUDGET_TEXT_MAX;
  }

  /** Send, back and submit follow the form: a second request, or a change during a send, is never possible. */
  function updateBudgetControls() {
    updateBudgetCounts();
    var f = budgetFormState();
    var hint = $('budget-actual-hint');
    hint.textContent = f.actualOk ? BUDGET_ACTUAL_HINT : BUDGET_MSG.actualBad;
    hint.className = f.actualOk ? 'meta' : 'meta error';
    var busy = state.budgetSubmitting === true;
    $('budget-next').disabled = busy || !f.ok;
    $('budget-back').disabled = busy;
    $('budget-cancel').disabled = busy;
    $('budget-submit').disabled = busy || !f.ok;
  }

  function openBudgetAck(row) {
    state.budgetDialog = { row: row, stage: 'edit' };
    $('budget-dialog-title').textContent = '未確定の予約を解除';
    $('budget-dialog-explain').textContent = keyLabel(row.reservationKey) + '（' + pathLabel(row.path) + '、期間 ' + str(row.period) +
      '、計上額 ' + moneyText(row.amountJpy) + '）を解除します。実額が分かれば入力してください。実額が分からなければ空欄のままにします（計上額が残ります）。';
    $('budget-actual').value = '';
    $('budget-evidence').value = '';
    $('budget-reason').value = '';
    showBudgetError('');
    setBudgetStage('edit');
    updateBudgetControls();
    $('budget-dialog').showModal();
  }

  function onBudgetNext() {
    var f = budgetFormState();
    if (!f.ok) {
      showBudgetError(f.actualOk ? BUDGET_MSG.textBad : BUDGET_MSG.actualBad);
      updateBudgetControls();
      return;
    }
    showBudgetError('');
    var text = f.actual === ''
      ? '実額は不明のままにします。計上額 ' + moneyText(state.budgetDialog.row.amountJpy) + ' が残ります。'
      : '実額として ' + f.actual + ' 円を記録します（0 は「請求されていないことを確認済み」の意味です）。';
    $('budget-confirm-text').textContent = text;
    setBudgetStage('confirm');
    updateBudgetControls();
  }

  function onBudgetBack() {
    showBudgetError('');
    setBudgetStage('edit');
    updateBudgetControls();
  }

  function budgetErrorText(r) {
    if (r.kind === 'network') return BUDGET_MSG.network;
    if (r.kind === 'session') return MESSAGES.session;
    if (r.status === 400) {
      if (r.code === 'TOO_LARGE') return BUDGET_MSG.tooLarge;
      if (r.code === 'NOT_COMPARABLE') return BUDGET_MSG.notComparable;
      return BUDGET_MSG.badRequest;
    }
    if (r.status === 404) return BUDGET_MSG.notFound;
    if (r.status === 409) return BUDGET_MSG.conflict;
    if (r.status === 503) return BUDGET_MSG.unavailable;
    return MESSAGES.server;
  }

  async function onBudgetSubmit() {
    var d = state.budgetDialog;
    if (!d || state.budgetSubmitting || state.sessionExpired) return;
    var f = budgetFormState();
    if (!f.ok) {
      showBudgetError(f.actualOk ? BUDGET_MSG.textBad : BUDGET_MSG.actualBad);
      setBudgetStage('edit');
      updateBudgetControls();
      return;
    }
    var row = d.row;
    var body = { scopeId: row.scopeId, period: row.period, reservationKey: row.reservationKey, evidence: f.evidence, reason: f.reason };
    // An absent actualAmountJpy means "the actual cost is not known" on the server, so an empty field is left out.
    if (f.actual !== '') body.actualAmountJpy = f.actual;
    state.budgetSubmitting = true;
    showBudgetError('');
    updateBudgetControls();
    var r;
    try {
      r = await apiFetch('/api/admin/budget/acknowledge', 'POST', body);
    } finally {
      state.budgetSubmitting = false;
    }
    var status = r.ok && r.data ? r.data.status : null;
    if (status === 'ACKNOWLEDGED' || status === 'ALREADY_ACKNOWLEDGED') {
      state.budgetDialog = null;
      $('budget-dialog').close();
      var msg = $('budget-msg');
      msg.textContent = status === 'ALREADY_ACKNOWLEDGED' ? BUDGET_MSG.alreadyDone : BUDGET_MSG.acknowledgedDone;
      msg.className = 'result ok';
      refreshBudget();
      return;
    }
    showBudgetError(r.ok ? MESSAGES.invalid : budgetErrorText(r));
    setBudgetStage('edit');
    updateBudgetControls();
    if (!r.ok && (r.status === 404 || r.status === 409)) refreshBudget();
  }

  function initBudget() {
    ['budget-actual', 'budget-evidence', 'budget-reason'].forEach(function (id) {
      $(id).addEventListener('input', updateBudgetControls);
    });
    $('budget-next').addEventListener('click', onBudgetNext);
    $('budget-back').addEventListener('click', onBudgetBack);
    $('budget-submit').addEventListener('click', onBudgetSubmit);
    $('budget-cancel').addEventListener('click', function () {
      if (state.budgetSubmitting) return;
      state.budgetDialog = null;
      $('budget-dialog').close();
    });
  }

  // ---- Autopilot (ADR 0011): the Goal form, the Goal list and detail, approvals, and the switches ----------
  // Shown only when GET /api/selfdev/autopilot answers 200 with enabled. A 503 (disabled) or any other answer keeps every
  // autopilot control hidden. The operator's text (goal title and body, task text, reasons) enters the DOM through textContent only.

  var GOAL_STATES = {
    GOAL_SUBMITTED: { label: '計画待ち', desc: 'Goal を受け付けました。Planner が計画を作るのを待っています' },
    PLANNING: { label: '計画中', desc: 'Planner が計画を作っています' },
    PLAN_PROPOSED: { label: '計画の承認待ち', desc: '計画ができました。Plan を確認し、承認すると、承認したタスクだけが投入されます' },
    TASKS_QUEUED: { label: '承認済み', desc: '計画は承認されています。承認したタスクの投入を待っています' },
    RUNNING: { label: '投入あり', desc: '投入済みのタスクがあります（Run が作られています）' },
    BLOCKED: { label: '停止中', desc: '止まっています。理由を確認してください' },
    PAUSED: { label: '一時停止中', desc: '運用者が一時停止しました' },
    DONE: { label: '完了', desc: '全タスクが完了しました' },
    STOPPED: { label: '中止', desc: '中止で終わりました（運用者の中止、またはタスクが完了せずに終わった）' }
  };

  var TASK_STATES = {
    PENDING_APPROVAL: '承認待ち（運用者の承認が必要です）',
    QUEUED: '承認済み（投入を待っています）',
    SUBMITTING: '投入中',
    SUBMITTED: 'Run を作成済み',
    COMPLETED: '完了（PR をマージ済み）',
    FAILED: '失敗（自動では再投入しません）',
    CANCELLED: '解放済み（運用者が再投入するか見送ります）',
    SKIPPED: '見送り（却下、または Goal の中止）'
  };

  var GOAL_REASONS = {
    PLAN_INVALID: '計画の検証に失敗しました（再計画できます）',
    PLAN_BUDGET_HOLD: '予算の上限で計画を作れませんでした（再計画できます）',
    PLAN_CALL_FAILED: 'モデル呼び出しに失敗しました（再計画できます）',
    CLAIM_EXPIRED: '計画の作成が時間切れになりました',
    REPLAN_REQUESTED: '再計画を要求しました',
    PENDING_PR_LIMIT: 'マージ待ちの PR が上限に達しています（自動で再開します）',
    DAILY_RUN_CAP: '1 日の Run 数の上限に達しています（翌日に再開します）',
    BUDGET_HOLD: '月次の予算の割当てに達しています（次の期間に再開します）',
    CIRCUIT_OPEN: '連続して失敗したため止まっています（運用者の再開が必要）',
    AUTO_SCOPE_EXCEEDED: '変更範囲が計画を超えました（運用者の確認が必要）',
    OPERATOR_PAUSED: '運用者が一時停止しました',
    OPERATOR_RESUMED: '運用者が再開しました',
    OPERATOR_STOPPED: '運用者が中止しました',
    OPERATOR_APPROVED: '運用者が承認しました',
    OPERATOR_SKIPPED: '運用者が見送りました',
    GOAL_STOPPED: 'Goal の中止により見送りました',
    ALL_TASKS_COMPLETED: 'すべてのタスクが完了しました',
    TASKS_INCOMPLETE: 'タスクが終わりましたが、すべては完了しませんでした（PR とタスクを確認してください）'
  };

  var PLAN_BLOCK_CODES = ['PLAN_INVALID', 'PLAN_BUDGET_HOLD', 'PLAN_CALL_FAILED'];
  var GOAL_STOP_MAX = 500;

  var AP = {
    goalSaved: 'Goal を受け付けました。Planner が計画を作るまで少し待ってください。',
    goalReplayed: 'この Goal は既に受け付け済みです（二重には作られません）。',
    goalKeyConflict: 'この投入キーは別の内容で既に使われていました。投入キーを作り直しました。内容を確認して、もう一度送ってください。',
    goalBad: '入力内容を確認してください（目的は 1〜200 文字、本文は 0〜8000 文字。制御文字は使えません）。',
    stale: '状態が変わっていました。一覧と詳細を読み直してから、もう一度操作してください。',
    refused: 'この操作は、今の状態では行えません。一覧と詳細を確認してください。',
    notFound: '見つかりません（既に消えたか、ID が違います）。',
    planApproved: 'Plan を承認しました。投入の対象は、承認されたタスクだけです。',
    taskApproved: 'タスクを承認しました（投入を待っています）。',
    taskRejected: 'タスクを見送りました。',
    replanned: '再計画を要求しました。Planner が新しい計画を作ります。',
    stopped: 'Goal を中止しました。未投入のタスクは見送りになります（作成済みの PR はそのままです）。',
    pausedDone: '自律運転を一時停止しました。新しい投入は止まります（投入済みの Run は止めません）。',
    resumedDone: '自律運転を再開しました。',
    breakerDone: 'サーキットブレーカーを再開しました（連続失敗の数を 0 に戻しました）。',
    approvalNeedsPlan: '先に Plan を承認してください。',
    reasonInvalid: '理由を確認してください（1〜500 文字、空白だけは使えません）。',
    network: '通信に失敗しました。もう一度お試しください。',
    unknown: '結果を確認できませんでした。一覧を読み直してください。'
  };

  function goalStateInfo(s) {
    var c = str(s);
    if (has(GOAL_STATES, c)) return GOAL_STATES[c];
    return { label: c === '' ? '（不明）' : c, desc: '不明な状態です' };
  }

  function taskStateText(s) {
    var c = str(s);
    return has(TASK_STATES, c) ? TASK_STATES[c] : (c === '' ? '（不明）' : c);
  }

  function goalReasonText(code) {
    if (code === null || code === undefined || str(code) === '') return '';
    var c = str(code);
    return has(GOAL_REASONS, c) ? GOAL_REASONS[c] + '（' + c + '）' : '理由: ' + c;
  }

  function goalTitleOf(goal) {
    return clip(goal.title, MAX_TITLE);
  }

  function updateGoalControls() {
    var blocked = state.goalSubmitting || state.sessionExpired;
    $('goal-title').disabled = blocked;
    $('goal-body').disabled = blocked;
    $('goal-submit').disabled = blocked;
    $('autopilot-toggle').disabled = state.sessionExpired || !state.autopilot;
    $('breaker-reset').disabled = state.sessionExpired || !state.autopilot;
  }

  function updateGoalCounters() {
    var t = Array.from($('goal-title').value).length;
    $('goal-title-count').textContent = t + ' / ' + MAX_TITLE;
    var b = Array.from($('goal-body').value).length;
    $('goal-count').textContent = b + ' / ' + MAX_BODY;
  }

  /** The key stays the same while the title and the body stay the same (a retry is the same Goal). */
  function keyForGoal(title, body) {
    var sig = title + '\n' + body;
    if (state.goalKeyFor !== sig) {
      if (state.goalKeyFor !== null) state.goalKey = newKey();
      state.goalKeyFor = sig;
    }
    return state.goalKey;
  }

  function showGoalResult(text, cls) {
    var el = $('goal-result');
    el.textContent = text;
    el.className = 'result' + (cls ? ' ' + cls : '');
  }

  async function onGoalSubmit(ev) {
    ev.preventDefault();
    if (state.goalSubmitting || state.sessionExpired || !state.autopilotEnabled) {
      updateGoalControls();
      return;
    }
    var title = $('goal-title').value.trim();
    var nTitle = Array.from(title).length;
    if (nTitle === 0 || nTitle > MAX_TITLE || CONTROL_CHARS.test(title)) {
      showGoalResult(AP.goalBad, 'error');
      return;
    }
    var text = $('goal-body').value.trim();
    if (Array.from(text).length > MAX_BODY || BODY_CONTROL_CHARS.test(text)) {
      showGoalResult(AP.goalBad, 'error');
      return;
    }
    var body = { title: title, idempotencyKey: keyForGoal(title, text) };
    if (text !== '') body.body = text;
    state.goalSubmitting = true;
    updateGoalControls();
    showGoalResult('送信中です…', '');
    var r;
    try {
      r = await apiFetch('/api/selfdev/goals', 'POST', body);
    } finally {
      state.goalSubmitting = false;
      updateGoalControls();
    }
    var accepted = r.ok && (r.status === 202 || r.status === 200) && r.data && typeof r.data.goalId === 'string';
    if (accepted) {
      state.goalKey = newKey();
      state.goalKeyFor = null;
      $('goal-title').value = '';
      $('goal-body').value = '';
      updateGoalCounters();
      showGoalResult(r.status === 202 ? AP.goalSaved : AP.goalReplayed, 'ok');
      refreshGoals();
      return;
    }
    if (r.kind === 'conflict') {
      state.goalKey = newKey();
      state.goalKeyFor = null;
      showGoalResult(AP.goalKeyConflict, 'error');
    } else if (r.status === 503) {
      showGoalResult(MESSAGES.server, 'error');
      refreshAutopilot();
    } else if (r.kind === 'network') {
      showGoalResult(AP.network, 'error');
    } else if (r.kind === 'badRequest') {
      showGoalResult(AP.goalBad, 'error');
    } else {
      showGoalResult(MESSAGES[r.kind] || MESSAGES.server, 'error');
    }
  }

  // The stage 1 -> 2 readout (ADR 0011 section 6.3). The server counts; this page only names the fixed ids and shows the numbers.
  // The labels are fixed here, so no text from the server is shown. An id that is not known is shown as an unknown condition.
  var PROMOTION_LABELS = {
    APPROVED_COMPLETED: { label: '承認したタスクのうち完了したもの', atLeast: true },
    POLICY_REJECTED: { label: 'ポリシーで止まった承認済みタスク', atLeast: false },
    OUT_OF_SCOPE: { label: '範囲外の変更として止まったタスク', atLeast: false },
    BUDGET_CONFIRMED: { label: '予算の計上の実機確認（#230）', atLeast: false }
  };
  var PROMOTION_UNMEASURED = '未計測（DB の集計では分かりません。runbook の手順で確認してください）';

  /** A readout is shown only when every entry has the expected shape; anything else is a failure with the fixed message. */
  function promotionShapeOk(list) {
    return Array.isArray(list) && list.every(function (c) {
      if (!c || typeof c.id !== 'string' || typeof c.measured !== 'boolean') return false;
      if (!c.measured) return c.current === null && c.required === null && c.met === null;
      return Number.isInteger(c.current) && Number.isInteger(c.required) && typeof c.met === 'boolean';
    });
  }

  async function refreshPromotion() {
    if (!state.autopilotEnabled || state.sessionExpired || state.promotionLoading) return;
    state.promotionLoading = true;
    try {
      var r = await apiFetch('/api/selfdev/autopilot/promotion', 'GET');
      if (r.ok && r.data && promotionShapeOk(r.data.conditions)) {
        state.promotion = r.data.conditions;
      } else {
        state.promotion = false;
      }
    } finally {
      state.promotionLoading = false;
      renderPromotion();
    }
  }

  function promotionLine(c) {
    var known = has(PROMOTION_LABELS, c.id) ? PROMOTION_LABELS[c.id] : null;
    if (!known) return '不明な条件';
    if (!c.measured) return known.label + '：' + PROMOTION_UNMEASURED;
    var need = known.atLeast ? c.required + ' 件以上' : 'ゼロ';
    var verdict = c.met ? '達成' : '未達';
    return known.label + '：' + c.current + ' 件（必要: ' + need + '、' + verdict + '）';
  }

  function renderPromotion() {
    var list = $('promotion-list');
    var status = $('promotion-status');
    list.textContent = '';
    if (!state.autopilotEnabled) {
      status.textContent = NOT_FETCHED_TEXT;
      return;
    }
    if (state.promotion === false) {
      status.textContent = '昇格条件を取得できませんでした。しばらくしてから、もう一度お試しください。';
      return;
    }
    if (!Array.isArray(state.promotion)) {
      status.textContent = NOT_FETCHED_TEXT;
      return;
    }
    var unmet = state.promotion.filter(function (c) { return c.measured && c.met === false; }).length;
    status.textContent = unmet > 0
      ? '測定できた条件のうち未達が ' + unmet + ' 件あります。'
      : '測定できた条件はすべて達成しています。予算の確認は手動です。';
    state.promotion.forEach(function (c) {
      var li = document.createElement('li');
      li.textContent = promotionLine(c);
      list.appendChild(li);
    });
    // A note, not a control: the promotion is a settings change made by the operator, and this page changes nothing.
    var note = document.createElement('li');
    note.textContent = '昇格は設定の変更で、運用者が判断します。この表示は何も変えません。';
    list.appendChild(note);
  }

  // Status of the autopilot. Only a 200 that says enabled shows anything.
  async function refreshAutopilot() {
    if (state.autopilotLoading) return;
    state.autopilotLoading = true;
    state.autopilotCheckedAt = Date.now();
    try {
      var r = await apiFetch('/api/selfdev/autopilot', 'GET');
      if (r.ok && r.data && r.data.enabled === true) {
        var firstTime = !state.autopilotEnabled;
        state.autopilotEnabled = true;
        state.autopilot = r.data;
        // The list is read as soon as the autopilot is known to be on (the refresh timer does not run until the next tick).
        if (firstTime) refreshGoals();
        refreshPromotion();
        if (firstTime) refreshOrders();
        if (firstTime) refreshMemory();
      } else {
        state.autopilotEnabled = false;
        state.autopilot = null;
        state.promotion = null;
      }
    } finally {
      state.autopilotLoading = false;
      NAV.settled.goals = true;
      if (!state.autopilotEnabled) {
        NAV.settled.orders = true;
        NAV.settled.approvals = true;
        NAV.settled.memory = true;
      }
      renderAutopilot();
      renderNav();
    }
  }

  function renderAutopilot() {
    var on = state.autopilotEnabled === true;
    $('goal-section').hidden = !on;
    $('plans-section').hidden = !on;
    if (!on) {
      MEMS.enabled = false;
      MEMS.data = null;
      renderMemory();
      state.goals = null;
      state.openGoalId = null;
      state.detail = null;
      renderGoals();
      updateGoalControls();
      return;
    }
    var a = state.autopilot;
    $('goal-repo').textContent = str(a.targetRepo) === '' ? '（未設定）' : str(a.targetRepo);
    var status = a.paused
      ? '全体: 一時停止中（新しい投入は止まっています。投入済みの Run は止めません）'
      : '全体: 稼働中（今日の Run ' + str(a.runsToday) + ' / ' + str(a.maxRunsPerDay) + ' 件）';
    $('autopilot-status').textContent = status;
    // The automatic merge's own pause (ADR 0014 U-10). Shown only; the operation is served by the API, not by a button here.
    $('auto-merge-status').textContent = a.autoMergePaused === true
      ? '自動マージ: 一時停止中（自動マージの判定を行っていません）'
      : '自動マージ: 一時停止していません';
    $('autopilot-toggle').textContent = a.paused ? '自律運転を再開' : '自律運転を一時停止';
    var breaker = a.breakerOpen === true;
    $('breaker-block').hidden = !breaker;
    $('breaker-status').hidden = !breaker;
    $('breaker-status').textContent = breaker
      ? '連続で ' + str(a.consecutiveFailures) + ' 件失敗したため、自律運転を止めています。原因を確認してから再開してください。'
      : '';
    updateGoalControls();
  }

  async function refreshGoals() {
    if (!state.autopilotEnabled || state.sessionExpired) return;
    if (state.goalsLoading) {
      state.goalsPending = true;
      return;
    }
    state.goalsLoading = true;
    state.goalsLastAttemptAt = Date.now();
    try {
      var r = await apiFetch('/api/selfdev/goals', 'GET');
      if (r.ok && Array.isArray(r.data)) {
        state.goals = r.data;
        state.goalsOkAt = Date.now();
        state.goalsFailed = false;
        renderGoals();
      } else {
        state.goalsFailed = true;
      }
    } finally {
      state.goalsLoading = false;
      renderGoalsStatus(Date.now());
      if (state.goalsPending) {
        state.goalsPending = false;
        refreshGoals();
      }
    }
  }

  function renderGoalsStatus(now) {
    var el = $('plans-status');
    if (state.sessionExpired) {
      el.className = 'meta error';
      el.textContent = 'セッション切れのため、一覧を更新できません';
      return;
    }
    if (state.goalsFailed) {
      el.className = 'meta error';
      el.textContent = '一覧を更新できませんでした' + (state.goalsOkAt ? '（前回の取得: ' + fmtLocal(state.goalsOkAt) + '）' : '');
      return;
    }
    el.className = 'meta';
    el.textContent = state.goalsOkAt === 0 ? NOT_FETCHED_TEXT : '最終取得: ' + fmtLocal(state.goalsOkAt) + '（' + relative(state.goalsOkAt, now) + '）';
  }

  function renderGoals() {
    var ul = $('plans-list');
    clear(ul);
    if (!state.goals) return;
    if (state.goals.length === 0) {
      ul.appendChild(h('li', { className: 'empty', text: 'まだ Goal はありません。上のフォームから Goal を送れます。' }));
      return;
    }
    state.goals.forEach(function (goal) {
      if (goal && typeof goal === 'object' && typeof goal.goalId === 'string') ul.appendChild(goalRow(goal));
    });
  }

  function goalRow(goal) {
    var id = str(goal.goalId);
    var open = state.openGoalId === id;
    var st = goalStateInfo(goal.state);
    var updated = parseTime(goal.updatedAt);
    var when = updated === null ? str(goal.updatedAt) : fmtLocal(updated);
    var toggle = h('button', {
      type: 'button',
      className: 'secondary',
      text: open ? '閉じる' : '詳細',
      'aria-expanded': open ? 'true' : 'false',
      'aria-controls': 'goal-detail-' + id
    });
    toggle.addEventListener('click', function () { toggleGoal(id); });
    var kids = [
      h('div', { className: 'run-head' }, [
        h('span', { className: 'run-title', text: goalTitleOf(goal) }),
        h('span', { className: 'badge', text: '状態: ' + st.label })
      ]),
      h('div', { className: 'run-meta' }, [
        h('span', { className: 'time', text: '更新: ' + when }),
        h('span', { className: 'time', text: '計画の回数: ' + str(goal.revision) })
      ]),
      h('div', { className: 'run-sub' }, [
        goal.stateReason ? h('span', { className: 'reason', text: '理由: ' + goalReasonText(goal.stateReason) }) : null
      ]),
      toggle
    ];
    if (open) kids.push(goalDetailBox(id));
    return h('li', { className: 'run-item' }, kids);
  }

  function goalDetailBox(id) {
    var box = h('div', { className: 'goal-detail', id: 'goal-detail-' + id });
    var d = state.detail;
    if (state.detailLoading || !d || d.goalId !== id) {
      if (state.detailError) {
        box.appendChild(h('p', { className: 'error', text: MESSAGES[state.detailError] || MESSAGES.server }));
      } else {
        box.appendChild(h('p', { className: 'empty', text: '読み込み中…' }));
      }
      return box;
    }
    renderGoalDetail(box, d.data);
    return box;
  }

  function toggleGoal(id) {
    if (state.openGoalId === id) {
      state.openGoalId = null;
      state.detail = null;
      state.detailError = null;
      renderGoals();
      return;
    }
    state.openGoalId = id;
    state.detail = null;
    state.detailError = null;
    renderGoals();
    loadGoalDetail(id);
  }

  async function loadGoalDetail(id) {
    var seq = ++state.detailSeq;
    state.detailLoading = true;
    var r = await apiFetch('/api/selfdev/goals/' + encodeURIComponent(id), 'GET');
    if (seq !== state.detailSeq) return;
    state.detailLoading = false;
    if (r.ok && r.data && typeof r.data === 'object' && r.data.goal && typeof r.data.goal === 'object') {
      state.detail = { goalId: id, data: r.data };
      state.detailError = null;
    } else {
      state.detail = null;
      state.detailError = r.ok ? 'invalid' : r.kind;
    }
    renderGoals();
  }

  /** Builds the goal detail: the body, the Plan(s) with their tasks, and the actions the current state allows. */
  function renderGoalDetail(box, data) {
    clear(box);
    var goal = data.goal;
    var st = goalStateInfo(goal.state);
    box.appendChild(h('h3', { text: '本文' }));
    box.appendChild(h('pre', { className: 'task-body', text: typeof data.body === 'string' && data.body !== '' ? data.body : '（本文はありません）' }));
    box.appendChild(h('p', { className: 'meta', text: '状態: ' + st.label + ' — ' + st.desc + (goal.stateReason ? '（' + goalReasonText(goal.stateReason) + '）' : '') }));

    var actions = [];
    if (goal.state === 'PLAN_PROPOSED') {
      actions.push(actionButton('この Plan を承認', function () { approvePlan(goal); }));
    }
    if (goal.state === 'BLOCKED' && PLAN_BLOCK_CODES.indexOf(str(goal.stateReason)) !== -1) {
      actions.push(actionButton('再計画する', function () { replanGoal(goal); }));
    }
    if (goal.state !== 'DONE' && goal.state !== 'STOPPED') {
      actions.push(actionButton('中止…', function () { openStop(goal); }, 'secondary'));
    }
    if (actions.length) box.appendChild(h('div', { className: 'detail-actions' }, actions));

    var plans = Array.isArray(data.plans) ? data.plans : [];
    if (plans.length === 0) {
      box.appendChild(h('p', { className: 'empty', text: 'まだ計画はありません（Planner が作るのを待っています）' }));
      return;
    }
    plans.forEach(function (plan) {
      if (!plan || typeof plan !== 'object') return;
      box.appendChild(h('h3', { text: '計画 ' + str(plan.revision) + '（' + str(plan.state) + '）' }));
      if (plan.summary) box.appendChild(h('p', { className: 'meta', text: str(plan.summary) }));
      var ol = h('ol', { className: 'tasks' }, []);
      (Array.isArray(plan.tasks) ? plan.tasks : []).forEach(function (task) {
        if (task && typeof task === 'object') ol.appendChild(taskItem(goal, task));
      });
      box.appendChild(ol);
    });
  }

  function actionButton(text, onClick, cls) {
    var b = h('button', { type: 'button', className: cls || '', text: text });
    b.addEventListener('click', onClick);
    return b;
  }

  function taskItem(goal, task) {
    var kids = [
      h('div', { className: 'run-head' }, [
        h('span', { className: 'run-title', text: str(task.ordinal) + '. ' + clip(task.title, MAX_TITLE) }),
        h('span', { className: 'badge', text: taskStateText(task.state) })
      ])
    ];
    var sub = [
      h('span', { text: '規模: ' + str(task.sizeEstimate) }),
      h('span', { text: '対象: ' + (Array.isArray(task.targetPaths) ? task.targetPaths.join(', ') : '') })
    ];
    if (task.requiresApproval === true) sub.push(h('span', { className: 'reason', text: '変更範囲に承認が必要なパス（承認なしでは投入されません）' }));
    if (task.stateReason) sub.push(h('span', { className: 'reason', text: '理由: ' + goalReasonText(task.stateReason) }));
    kids.push(h('div', { className: 'run-sub' }, sub));
    if (typeof task.body === 'string' && task.body !== '') kids.push(h('pre', { className: 'task-body', text: task.body }));
    if (task.state === 'PENDING_APPROVAL') {
      var planApproved = goal.state !== 'PLAN_PROPOSED';
      var approve = actionButton('このタスクを承認', function () { approveTask(goal, task); });
      approve.disabled = !planApproved || state.sessionExpired;
      var reject = actionButton('見送る', function () { rejectTask(goal, task); }, 'secondary');
      reject.disabled = state.sessionExpired;
      kids.push(h('div', { className: 'detail-actions' }, [approve, reject]));
      if (!planApproved) kids.push(h('p', { className: 'meta', text: AP.approvalNeedsPlan }));
    }
    return h('li', { className: 'task' }, kids);
  }

  /**
   * The shared tail of the approval and state actions. The caller sends the request (so each apiFetch path is a literal) and passes
   * the promise in. The body carries the version the operator read (compare and set), and the goal is reloaded afterwards.
   */
  async function goalWrite(pending, goalId, okText) {
    var r = await pending;
    var msg;
    if (r.ok) {
      msg = okText;
    } else if (r.kind === 'network') {
      msg = AP.network;
    } else if (r.status === 409) {
      msg = AP.stale;
    } else if (r.status === 404) {
      msg = AP.notFound;
    } else if (r.status === 400) {
      msg = MESSAGES.badRequest;
    } else {
      msg = AP.unknown;
    }
    $('plans-msg').textContent = msg;
    $('plans-msg').className = 'result' + (r.ok ? ' ok' : ' error');
    refreshGoals();
    if (state.openGoalId === goalId) {
      state.detail = null;
      loadGoalDetail(goalId);
    }
    return r;
  }

  function approvePlan(goal) {
    if (state.sessionExpired) return;
    goalWrite(apiFetch('/api/selfdev/goals/' + encodeURIComponent(goal.goalId) + '/approve-plan', 'POST', { version: goal.version }), goal.goalId, AP.planApproved);
  }

  function replanGoal(goal) {
    if (state.sessionExpired) return;
    goalWrite(apiFetch('/api/selfdev/goals/' + encodeURIComponent(goal.goalId) + '/replan', 'POST', { version: goal.version }), goal.goalId, AP.replanned);
  }

  function approveTask(goal, task) {
    if (state.sessionExpired) return;
    goalWrite(apiFetch('/api/selfdev/tasks/' + encodeURIComponent(str(task.taskId)) + '/approve', 'POST', { version: task.version }), goal.goalId, AP.taskApproved);
  }

  function rejectTask(goal, task) {
    if (state.sessionExpired) return;
    goalWrite(apiFetch('/api/selfdev/tasks/' + encodeURIComponent(str(task.taskId)) + '/reject', 'POST', { version: task.version }), goal.goalId, AP.taskRejected);
  }

  /**
   * Shared reason dialog. Submit calls options.send(trimmed reason) and keeps the dialog open while it runs (Submit and
   * Cancel are disabled); the caller closes the dialog when the send succeeds. Cancel and Esc close it.
   * The returned promise resolves with the dialog's return value when it closes, or null on cancel and Esc.
   * options.dialog, options.input, options.submit and options.cancel are element ids; options.max is the length limit (code points).
   * Submit stays disabled until the reason is valid. The listeners are removed when the dialog closes.
   */
  function openReasonDialog(options) {
    var dialog = $(options.dialog);
    var input = $(options.input);
    var submit = $(options.submit);
    var cancel = $(options.cancel);
    var busy = false;
    var reasonValid = function (text) {
      var n = Array.from(text).length;
      return n >= 1 && n <= options.max && !CONTROL_CHARS.test(text);
    };
    var update = function () {
      submit.disabled = busy || !reasonValid(input.value.trim());
      cancel.disabled = busy;
    };
    var onSubmit = async function () {
      var text = input.value.trim();
      if (busy || !reasonValid(text)) return;
      busy = true;
      update();
      try {
        await options.send(text);
      } finally {
        busy = false;
        update();
      }
    };
    var onCancel = function () {
      dialog.close('');
    };
    return new Promise(function (resolve) {
      var onClose = function () {
        input.removeEventListener('input', update);
        submit.removeEventListener('click', onSubmit);
        cancel.removeEventListener('click', onCancel);
        dialog.removeEventListener('close', onClose);
        resolve(dialog.returnValue === '' ? null : dialog.returnValue);
      };
      input.addEventListener('input', update);
      submit.addEventListener('click', onSubmit);
      cancel.addEventListener('click', onCancel);
      dialog.addEventListener('close', onClose);
      input.value = '';
      dialog.returnValue = '';
      update();
      dialog.showModal();
    });
  }

  // ---- Stop dialog (the reason is required; it is sent, not kept) --------

  function showStopError(text) {
    var el = $('stop-error');
    el.textContent = text;
    el.hidden = text === '';
  }

  function openStop(goal) {
    state.stop = { goalId: goal.goalId, version: goal.version };
    $('stop-title').textContent = 'Goal の中止';
    $('stop-explain').textContent = 'この Goal を中止します。まだ投入していないタスクは見送りになります。作成済みの PR は閉じず、マージもしません（PR の扱いは人が決めます）。';
    showStopError('');
    openReasonDialog({
      dialog: 'stop-dialog', input: 'stop-reason', submit: 'stop-submit', cancel: 'stop-cancel',
      max: GOAL_STOP_MAX, send: submitStop
    });
  }

  /** Sends the reason (already validated by openReasonDialog). The dialog stays open on failure. */
  async function submitStop(reason) {
    var s = state.stop;
    if (!s || state.sessionExpired) return;
    showStopError('');
    var r = await apiFetch('/api/selfdev/goals/' + encodeURIComponent(s.goalId) + '/stop', 'POST', { version: s.version, reason: reason });
    if (r.ok) {
      state.stop = null;
      $('stop-dialog').close();
      $('plans-msg').textContent = AP.stopped;
      $('plans-msg').className = 'result ok';
      refreshGoals();
      if (state.openGoalId === s.goalId) {
        state.detail = null;
        loadGoalDetail(s.goalId);
      }
      return;
    }
    var msg = r.kind === 'network' ? AP.network : (r.status === 400 ? AP.reasonInvalid : (r.status === 409 ? AP.stale : (r.status === 404 ? AP.notFound : AP.unknown)));
    showStopError(msg);
  }

  // ---- Self-development memory (ADR 0016 P2, operator): the list, the filters, one deletion and the conditional deletion ----
  // Shown only when GET /api/selfdev/memory answers 200. A 503 (the memory or the autopilot is off), 403, 404 or any other answer
  // hides the card. Only fixed labels and checked values enter the page, all through textContent. The server withholds the text of
  // an UNTRUSTED note (ADR 0016 U-1), so none is shown here either.
  var MEM = {
    LIMIT: 20,
    REASON_MAX: 500,
    KIND: {
      OPERATOR_NOTE: '運用者の覚え書き',
      GOAL_TITLE: 'Goal の題名（運用者）',
      REJECTION_REASON: '却下の理由（運用者）',
      PLAN_RATIONALE: '計画の理由（モデル、未検証）',
      FAILURE_EXPLANATION: '失敗の説明（系統、未検証）',
      CYCLE_REVIEW: 'order の周の振り返り（系統、未検証）',
      PLAN_FACT: '計画の事実',
      TASK_FACT: 'タスクの事実',
      RUN_FACT: 'Run の事実',
      ATTEMPT_FACT: '試行の事実',
      COST_FACT: '費用の事実',
      GOAL_FACT: 'Goal の事実'
    },
    TIER: {
      TRUSTED_OPERATOR: '運用者の文章（信頼）',
      UNTRUSTED: '未検証（モデルや系統が書いた）'
    },
    MSG: {
      network: '通信に失敗しました。時間をおいて再度お試しください。',
      session: 'セッションが切れました。画面を再読み込みしてください。',
      notFound: '記録が見つかりませんでした（削除済みの可能性があります）。一覧を読み直しました。',
      reasonInvalid: '理由は 1 から 500 文字で、制御文字を含めないでください。',
      limit: '該当が多すぎます（1 回の上限は 500 件です）。条件を絞ってください。',
      needFilter: '条件を 1 つ以上選んでから確認してください（種類か信頼）。',
      withheld: '本文は表示しません（未検証の文章）',
      deleted: '記録を削除しました。',
      unknown: '処理できませんでした。時間をおいて再度お試しください。'
    }
  };

  var MEMS = {
    enabled: false,
    loading: false,
    busy: false,
    okAt: 0,
    lastAttemptAt: 0,
    data: null,
    offset: 0,
    filter: { kind: '', tier: '' },
    dry: null,
    action: null
  };

  function memCount(v) {
    return typeof v === 'number' && isFinite(v) && Math.floor(v) === v && v >= 0;
  }

  function memValidList(d) {
    return d !== null && typeof d === 'object' && Array.isArray(d.items) && memCount(d.total) &&
      memCount(d.tombstones) && memCount(d.deletionAudit);
  }

  function memValidItem(it) {
    return it !== null && typeof it === 'object' && typeof it.recordId === 'string' && /^[0-9A-Z]{26}$/.test(it.recordId);
  }

  function memLabel(table, key) {
    return typeof key === 'string' && has(table, key) ? table[key] : '不明';
  }

  function memCodeText(code) {
    return typeof code === 'string' && /^[A-Z_]{1,64}$/.test(code) ? code : '不明';
  }

  function memFilterQuery() {
    var parts = ['limit=' + MEM.LIMIT, 'offset=' + MEMS.offset];
    if (MEMS.filter.kind) parts.push('kind=' + encodeURIComponent(MEMS.filter.kind));
    if (MEMS.filter.tier) parts.push('tier=' + encodeURIComponent(MEMS.filter.tier));
    return parts.join('&');
  }

  /** The applied filter as a request body for the conditional deletion, or null when no condition is chosen. */
  function memFilterBody() {
    var f = {};
    if (MEMS.filter.kind) f.kind = MEMS.filter.kind;
    if (MEMS.filter.tier) f.tier = MEMS.filter.tier;
    return f.kind || f.tier ? f : null;
  }

  function memFilterKey() {
    return JSON.stringify([MEMS.filter.kind, MEMS.filter.tier]);
  }

  function memFailText(r) {
    if (r.kind === 'network') return MEM.MSG.network;
    if (r.kind === 'session') return MEM.MSG.session;
    if (r.status === 400) return MEM.MSG.reasonInvalid;
    if (r.status === 404) return MEM.MSG.notFound;
    if (r.status === 409) return MEM.MSG.limit;
    return MEM.MSG.unknown;
  }

  function memFillSelects() {
    var kind = $('memory-kind');
    var tier = $('memory-tier');
    clear(kind);
    clear(tier);
    var all = h('option', { text: 'すべての種類' });
    all.value = '';
    kind.appendChild(all);
    Object.keys(MEM.KIND).forEach(function (k) {
      var o = h('option', { text: MEM.KIND[k] });
      o.value = k;
      kind.appendChild(o);
    });
    var anyTier = h('option', { text: 'すべての信頼' });
    anyTier.value = '';
    tier.appendChild(anyTier);
    Object.keys(MEM.TIER).forEach(function (k) {
      var o = h('option', { text: MEM.TIER[k] });
      o.value = k;
      tier.appendChild(o);
    });
  }

  async function refreshMemory() {
    if (MEMS.loading || state.sessionExpired) return;
    if (!state.autopilotEnabled) {
      MEMS.enabled = false;
      MEMS.data = null;
      renderMemory();
      return;
    }
    MEMS.loading = true;
    MEMS.lastAttemptAt = Date.now();
    try {
      var r = await apiFetch('/api/selfdev/memory?' + memFilterQuery(), 'GET');
      if (r.ok && memValidList(r.data)) {
        MEMS.enabled = true;
        MEMS.okAt = Date.now();
        MEMS.data = {
          items: r.data.items.filter(memValidItem),
          total: r.data.total,
          tombstones: r.data.tombstones,
          deletionAudit: r.data.deletionAudit
        };
      } else {
        MEMS.enabled = false;
        MEMS.data = null;
      }
    } finally {
      MEMS.loading = false;
      NAV.settled.memory = true;
      renderMemory();
      renderNav();
    }
  }

  function renderMemory() {
    var on = MEMS.enabled === true && state.autopilotEnabled === true && MEMS.data !== null;
    $('memory-section').hidden = !on;
    var blocked = MEMS.busy || MEMS.loading || state.sessionExpired;
    $('memory-apply').disabled = blocked;
    $('memory-dryrun').disabled = blocked;
    var dryOk = MEMS.dry !== null && MEMS.dry.key === memFilterKey() && MEMS.dry.matched >= 1;
    $('memory-cond-delete').disabled = blocked || !dryOk;
    if (!on) {
      clear($('memory-list'));
      return;
    }
    var d = MEMS.data;
    $('memory-status').textContent = state.sessionExpired
      ? 'セッション切れのため、一覧を更新できません'
      : '最終取得: ' + fmtLocal(MEMS.okAt) + '（該当 ' + d.total + ' 件）';
    $('memory-counts').textContent = '墓標（削除された文章）: ' + d.tombstones + ' 件、削除の監査: ' + d.deletionAudit + ' 件';
    var last = MEMS.offset + d.items.length;
    $('memory-page').textContent = d.items.length === 0 ? '' : (MEMS.offset + 1) + ' から ' + last + ' 件目を表示しています';
    $('memory-prev').disabled = blocked || MEMS.offset === 0;
    $('memory-next').disabled = blocked || last >= d.total;
    var ul = $('memory-list');
    clear(ul);
    if (d.items.length === 0) {
      ul.appendChild(h('li', { className: 'empty', text: '該当する記録はありません。' }));
      return;
    }
    d.items.forEach(function (it) { ul.appendChild(memRow(it, blocked)); });
  }

  function memRow(it, blocked) {
    var kids = [
      h('div', { className: 'run-head' }, [
        h('span', { className: 'run-title', text: memLabel(MEM.KIND, it.kind) }),
        h('span', { className: 'badge', text: memLabel(MEM.TIER, it.tier) })
      ]),
      h('div', { className: 'run-meta' }, [
        h('span', { className: 'time', text: '作成: ' + ordTime(it.createdAt) }),
        memCount(it.countValue) ? h('span', { className: 'time', text: '件数: ' + it.countValue }) : null,
        it.code ? h('span', { className: 'reason', text: '理由コード: ' + memCodeText(it.code) }) : null
      ])
    ];
    if (typeof it.body === 'string') {
      kids.push(h('p', { className: 'memory-text', text: it.body }));
    } else if (it.bodyWithheld === true) {
      kids.push(h('p', { className: 'meta', text: MEM.MSG.withheld }));
    }
    var del = h('button', { type: 'button', className: 'secondary', text: 'この記録を削除' });
    del.disabled = blocked;
    del.addEventListener('click', function () { openMemoryDelete(it.recordId); });
    kids.push(del);
    return h('li', { className: 'run-item' }, kids);
  }

  function showMemoryError(text) {
    var el = $('memory-error');
    el.textContent = text;
    el.hidden = text === '';
  }

  function updateMemoryCount() {
    $('memory-count').textContent = Array.from($('memory-reason').value).length + ' / ' + MEM.REASON_MAX;
  }

  function openMemoryDelete(recordId) {
    if (MEMS.busy || state.sessionExpired) return;
    MEMS.action = { type: 'one', recordId: recordId };
    $('memory-title').textContent = '記録の削除';
    $('memory-explain').textContent = 'この記録を削除します。文章は消え、墓標（ID と種類と時刻だけ）と監査が残ります。取り消せません。';
    showMemoryError('');
    updateMemoryCount();
    openReasonDialog({
      dialog: 'memory-dialog', input: 'memory-reason', submit: 'memory-submit', cancel: 'memory-cancel',
      max: MEM.REASON_MAX, send: submitMemoryDelete
    });
  }

  function openMemoryConditionDelete() {
    if (MEMS.busy || state.sessionExpired || !MEMS.dry || MEMS.dry.key !== memFilterKey()) return;
    MEMS.action = { type: 'cond', filter: memFilterBody() };
    $('memory-title').textContent = '条件での削除';
    $('memory-explain').textContent = '表示中の条件に該当する ' + MEMS.dry.matched + ' 件を削除します。文章は消え、墓標と監査が残ります。取り消せません。';
    showMemoryError('');
    updateMemoryCount();
    openReasonDialog({
      dialog: 'memory-dialog', input: 'memory-reason', submit: 'memory-submit', cancel: 'memory-cancel',
      max: MEM.REASON_MAX, send: submitMemoryDelete
    });
  }

  /** Sends the deletion (the reason was checked by openReasonDialog). The dialog stays open on failure. */
  async function submitMemoryDelete(reason) {
    var a = MEMS.action;
    if (!a || MEMS.busy || state.sessionExpired) return;
    showMemoryError('');
    MEMS.busy = true;
    renderMemory();
    var r;
    try {
      if (a.type === 'one') {
        r = await apiFetch('/api/selfdev/memory/' + encodeURIComponent(a.recordId) + '/delete', 'POST', { reason: reason });
      } else {
        r = await apiFetch('/api/selfdev/memory/delete-by-condition', 'POST', Object.assign({}, a.filter, { dryRun: false, reason: reason }));
      }
    } finally {
      MEMS.busy = false;
    }
    if (r.ok) {
      MEMS.action = null;
      MEMS.dry = null;
      $('memory-dialog').close();
      $('memory-msg').textContent = MEM.MSG.deleted;
      $('memory-msg').className = 'result ok';
      refreshMemory();
      return;
    }
    showMemoryError(memFailText(r));
    if (r.status === 404) refreshMemory();
    renderMemory();
  }

  async function onMemoryDryRun() {
    if (MEMS.busy || state.sessionExpired) return;
    var body = memFilterBody();
    if (body === null) {
      $('memory-dryrun-result').textContent = MEM.MSG.needFilter;
      return;
    }
    MEMS.busy = true;
    renderMemory();
    var r;
    try {
      r = await apiFetch('/api/selfdev/memory/delete-by-condition', 'POST', Object.assign({}, body, { dryRun: true }));
    } finally {
      MEMS.busy = false;
    }
    if (r.ok && r.data && memCount(r.data.matched)) {
      MEMS.dry = { key: memFilterKey(), matched: r.data.matched };
      $('memory-dryrun-result').textContent = '該当: ' + r.data.matched + ' 件（1 回の上限は 500 件）';
    } else {
      MEMS.dry = null;
      $('memory-dryrun-result').textContent = r.status === 409 ? MEM.MSG.limit : memFailText(r);
    }
    renderMemory();
  }

  function onMemoryApply() {
    if (MEMS.loading || MEMS.busy) return;
    var kind = $('memory-kind').value;
    var tier = $('memory-tier').value;
    MEMS.filter = {
      kind: has(MEM.KIND, kind) ? kind : '',
      tier: has(MEM.TIER, tier) ? tier : ''
    };
    MEMS.offset = 0;
    MEMS.dry = null;
    $('memory-dryrun-result').textContent = '';
    refreshMemory();
  }

  function onMemoryPage(delta) {
    if (MEMS.loading || MEMS.busy || !MEMS.data) return;
    var next = MEMS.offset + delta * MEM.LIMIT;
    if (next < 0) return;
    MEMS.offset = next;
    refreshMemory();
  }

  // ---- Global pause and the breaker ----------------------------------------

  async function onAutopilotToggle() {
    if (!state.autopilot || state.sessionExpired) return;
    var resume = state.autopilot.paused === true;
    var r = await apiFetch('/api/selfdev/autopilot/' + (resume ? 'resume' : 'pause'), 'POST', {});
    $('plans-msg').textContent = r.ok ? (resume ? AP.resumedDone : AP.pausedDone) : AP.unknown;
    $('plans-msg').className = 'result' + (r.ok ? ' ok' : ' error');
    refreshAutopilot();
  }

  async function onBreakerReset() {
    if (!state.autopilot || state.sessionExpired) return;
    var r = await apiFetch('/api/selfdev/autopilot/reset-breaker', 'POST', {});
    $('plans-msg').textContent = r.ok ? AP.breakerDone : AP.unknown;
    $('plans-msg').className = 'result' + (r.ok ? ' ok' : ' error');
    refreshAutopilot();
  }

  // ---- Execution (ADR 0012 PR 4, "実行の経過") -------------------------------
  // Shown only for a self-development Run whose execution snapshot answers 200 with attempts. Everything else (404,
  // 403, no attempts, a simulation) hides the section. The REST snapshot is the base; SSE is an optional nudge.
  // A dropped stream or a stopped watch is never shown as "the Run is fine" or "the problem is gone".

  var EXEC = {
    POLL_MS: 5000,
    POLL_HIDDEN_MS: 30000,
    POLL_STREAM_MS: 15000,
    BACKOFF_MAX_MS: 60000,
    STREAM_MAX_ERRORS: 3,
    NUDGE_MS: 500
  };
  var EXEC_TERMINAL = { COMPLETED: true, FAILED: true, CANCELLED: true };
  var EXEC_ACTIVE_ATTEMPT = { QUEUED: true, CLAIMED: true, RUNNING: true };
  var EXEC_ATTEMPT_STATES = {
    QUEUED: '待機中',
    CLAIMED: '割り当て済み',
    RUNNING: '稼働中（CLI）',
    RESULT_RECEIVED: '結果を受信',
    FAILED: '失敗',
    CANCELLED: '取消し',
    EXPIRED: '期限切れ',
    ORPHANED: '孤立（対応する実行が見つからない）'
  };
  // The fixed tool names (MonitorEventSchema.TOOL_NAMES) and their labels. Any other name is counted as OTHER.
  var EXEC_TOOLS = [
    ['Read', '読み取り（Read）'],
    ['Edit', '編集（Edit）'],
    ['Write', '書き込み（Write）'],
    ['Bash', 'シェル（Bash）'],
    ['Grep', '内容の検索（Grep）'],
    ['Glob', 'ファイルの検索（Glob）'],
    ['Task', 'サブタスク（Task）'],
    ['TodoWrite', 'TODO の更新（TodoWrite）'],
    ['OTHER', 'その他']
  ];
  var EXEC_PHASES = {
    STARTED: '開始',
    EDITING: '編集中',
    TESTING: 'テスト中',
    ROUND_COMPLETED: 'ラウンド完了',
    FINALIZING: '仕上げ中'
  };
  var EXEC_STOP = {
    NONE: '停止要求はありません',
    REQUESTED: '停止要求あり（エージェントの確認待ち）',
    STOPPING: '停止中（エージェントが停止要求を受けたと報告）',
    EXIT_CONFIRMED: '停止確認（エージェントがプロセスの終了を報告）',
    CANCELLED: '取消し済み（Run が CANCELLED）'
  };
  var EXEC_MSG = {
    loadFailed: '監視の情報を取得できませんでした（通信または応答の失敗）。Run の状態ではありません。しばらくすると自動で取り直します。',
    stale: '表示は古い可能性があります',
    noReceipt: '受信の記録がありません。表示は古い可能性があります',
    stopped: 'Run の状態が終端のため、取得を止めました（表示は取得時点のものです）。'
  };

  function parseInstant(s) {
    var m = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?Z$/.exec(str(s));
    if (!m) return null;
    // Only milliseconds are used: browsers differ on longer fractions.
    var t = Date.parse(m[1] + '.' + (m[2] ? (m[2] + '000').slice(0, 3) : '000') + 'Z');
    return isNaN(t) ? null : t;
  }

  /** A count from the server: a non-negative whole number, or null (shown as 不明). */
  function count(v) {
    return typeof v === 'number' && isFinite(v) && v >= 0 && Math.floor(v) === v ? String(v) : '不明';
  }

  /** A money value (a decimal string from the server): always labelled with USD by the caller. */
  function usd(v) {
    return typeof v === 'string' && /^[0-9]{1,12}(\.[0-9]{1,6})?$/.test(v) ? 'USD ' + v : '不明';
  }

  function lookup(map, key) {
    return has(map, str(key)) ? map[str(key)] : str(key) === '' ? '（不明）' : '不明な値（' + str(key) + '）';
  }

  function beginExecution(runId, runState) {
    stopExecution();
    var body = h('div', { className: 'exec-body', 'aria-live': 'polite' }, []);
    var section = h('section', { className: 'exec-section', 'aria-labelledby': 'h-exec' }, [
      h('h3', { id: 'h-exec', text: '実行の経過' }),
      h('p', { className: 'meta', text: '自己開発（CLI 経路）の実行を、読み取り専用で表示します。停止はこの画面からは行えません。' }),
      body
    ]);
    section.hidden = true;
    var ex = {
      runId: runId, runState: runState, section: section, body: body,
      active: true, shown: false, timer: null, inFlight: false, pendingNudge: false,
      failures: 0, lastError: null, snap: null, snapAt: 0, skewMs: 0,
      stream: null, streamOpen: false, streamErrors: 0, streamResyncs: 0, streamDisabled: false
    };
    state.exec = ex;
    execFetch(ex);
    return section;
  }

  function stopExecution() {
    var ex = state.exec;
    if (!ex) return;
    ex.active = false;
    if (ex.timer !== null) clearTimeout(ex.timer);
    ex.timer = null;
    streamClose(ex);
    state.exec = null;
  }

  function execSchedule(ex, ms) {
    if (ex.timer !== null) clearTimeout(ex.timer);
    ex.timer = setTimeout(function () {
      ex.timer = null;
      execFetch(ex);
    }, ms);
  }

  /** The next poll interval: longer while the tab is hidden or the stream is open, and backed off after failures. */
  function execDelay(ex) {
    var base = document.hidden ? EXEC.POLL_HIDDEN_MS : (ex.streamOpen ? EXEC.POLL_STREAM_MS : EXEC.POLL_MS);
    if (ex.failures === 0) return base;
    return Math.min(EXEC.BACKOFF_MAX_MS, base * Math.pow(2, Math.min(ex.failures, 4)));
  }

  function execReschedule(ex) {
    if (!ex.active || ex.inFlight) return;
    execSchedule(ex, execDelay(ex));
  }

  /** A stream message or resync: read the snapshot soon, once (several messages in a row make one read). */
  function execNudge(ex) {
    if (!ex.active) return;
    if (ex.inFlight) {
      ex.pendingNudge = true;
      return;
    }
    execSchedule(ex, EXEC.NUDGE_MS);
  }

  function execVisibilityChanged() {
    var ex = state.exec;
    if (!ex || !ex.active || ex.inFlight) return;
    if (document.hidden) execReschedule(ex);
    else execFetch(ex);
  }

  function isOlderRevision(a, b) {
    return a.attemptVersion < b.attemptVersion || (a.attemptVersion === b.attemptVersion && a.eventCount < b.eventCount);
  }

  function validSnapshot(d) {
    return d && typeof d === 'object' && typeof d.runState === 'string' && Array.isArray(d.attempts) &&
      d.revision && typeof d.revision === 'object' && typeof d.freshness === 'string' &&
      d.tools && typeof d.tools === 'object' && d.stop && typeof d.stop === 'object' &&
      d.cost && typeof d.cost === 'object';
  }

  async function execFetch(ex) {
    if (!ex.active) return;
    if (ex.inFlight) {
      ex.pendingNudge = true;
      return;
    }
    if (ex.timer !== null) clearTimeout(ex.timer);
    ex.timer = null;
    ex.inFlight = true;
    var r = await apiFetch('/api/admin/runs/' + encodeURIComponent(ex.runId) + '/execution', 'GET', undefined, true);
    ex.inFlight = false;
    if (!ex.active) return;
    if (!r.ok) {
      execFailed(ex, r);
    } else if (!validSnapshot(r.data)) {
      execFailed(ex, { kind: 'invalid' });
    } else {
      execAccepted(ex, r.data);
    }
    if (ex.active && ex.pendingNudge) {
      ex.pendingNudge = false;
      execNudge(ex);
    }
  }

  function execFailed(ex, r) {
    // 401, 403 and 404: this Run has no execution view for this user (an API-path run, or not visible). Hide, stop.
    if (r.status === 401 || r.status === 403 || r.status === 404 || r.kind === 'session') {
      if (state.sessionExpired) {
        stopExecution();
        return;
      }
      ex.shown = false;
      ex.active = false;
      renderExecution(ex);
      return;
    }
    ex.failures += 1;
    ex.lastError = r.kind === 'network' ? 'network' : 'failed';
    ex.shown = true;
    renderExecution(ex);
    execReschedule(ex);
  }

  function execAccepted(ex, d) {
    ex.failures = 0;
    ex.lastError = null;
    if (d.attempts.length === 0) {
      // No attempt: not a CLI run (yet). Hidden; the page is read again on the next load.
      ex.shown = false;
      ex.active = false;
      renderExecution(ex);
      return;
    }
    // A snapshot older than the one shown never replaces it (a late answer must not move the state back).
    var accept = !ex.snap || !isOlderRevision(d.revision, ex.snap.revision);
    var now = Date.now();
    var serverNow = parseInstant(d.snapshotAt);
    if (accept) {
      ex.snap = d;
      ex.snapAt = now;
      ex.skewMs = serverNow === null ? 0 : serverNow - now;
      ex.runState = d.runState;
    }
    ex.shown = true;
    renderExecution(ex);
    if (EXEC_TERMINAL[ex.runState] === true) {
      streamClose(ex);
      ex.active = false;
      renderExecution(ex);
      return;
    }
    if (!ex.streamOpen && !ex.streamDisabled && ex.stream === null) streamStart(ex);
    if (ex.pendingNudge) return;
    execReschedule(ex);
  }

  /** SSE: optional. A failure, or a stream that keeps dropping, falls back to the REST poll (which runs anyway). */
  function streamStart(ex) {
    if (state.sessionExpired || ex.streamDisabled || ex.stream !== null || typeof window.EventSource !== 'function') return;
    var es;
    try {
      es = new window.EventSource('/api/admin/runs/' + encodeURIComponent(ex.runId) + '/execution/stream');
    } catch (e) {
      ex.streamDisabled = true;
      return;
    }
    ex.stream = es;
    es.onopen = function () {
      if (ex.stream !== es) return;
      ex.streamOpen = true;
      renderExecution(ex);
    };
    es.addEventListener('monitor', function () {
      if (ex.stream !== es) return;
      ex.streamErrors = 0;
      execNudge(ex);
    });
    es.addEventListener('resync', function () {
      if (ex.stream !== es) return;
      // The server says the cursor is gone: read the snapshot again and open a new stream. A second resync in this view: stop using SSE.
      streamClose(ex);
      ex.streamResyncs += 1;
      if (ex.streamResyncs > 1) ex.streamDisabled = true;
      renderExecution(ex);
      execNudge(ex);
    });
    es.onerror = function () {
      if (ex.stream !== es) return;
      ex.streamOpen = false;
      ex.streamErrors += 1;
      if (es.readyState === 2 || ex.streamErrors >= EXEC.STREAM_MAX_ERRORS) {
        streamClose(ex);
        ex.streamDisabled = true;
        execReschedule(ex);
      }
      renderExecution(ex);
    };
  }

  function streamClose(ex) {
    if (ex.stream !== null) {
      ex.stream.onopen = null;
      ex.stream.onerror = null;
      ex.stream.close();
    }
    ex.stream = null;
    ex.streamOpen = false;
  }

  function staleMinutes(ex) {
    var last = parseInstant(ex.snap && ex.snap.lastReceivedAt);
    if (last === null) return null;
    var elapsed = Date.now() + ex.skewMs - last;
    return Math.max(0, Math.floor(elapsed / 60000));
  }

  function statusLine(ex) {
    if (!ex.snap) return null;
    var fresh = ex.snap.freshness;
    // A finished run stops receiving on purpose: no stale warning for it (the freshness value itself is unchanged).
    if (EXEC_TERMINAL[ex.runState] === true) {
      var ended = parseInstant(ex.snap.lastReceivedAt);
      return h('p', { className: 'meta', text: '終了済み（Run の状態が終端のため、受信は止まっています）。最終の受信: ' + (ended === null ? '不明' : fmtLocal(ended)) });
    }
    if (fresh === 'STALE') {
      var n = staleMinutes(ex);
      var text = n === null ? EXEC_MSG.noReceipt : '最終の受信から ' + (n < 1 ? '1 分未満' : n + ' 分経過。' + EXEC_MSG.stale);
      return h('p', { className: 'exec-stale', role: 'status', text: text });
    }
    var last = parseInstant(ex.snap.lastReceivedAt);
    return h('p', { className: 'meta', text: '鮮度: ' + fresh + '。最終の受信: ' + (last === null ? '不明' : fmtLocal(last)) });
  }

  function renderExecution(ex) {
    ex.section.hidden = !ex.shown;
    if (ex.section.hidden) {
      clear(ex.body);
      return;
    }
    var body = ex.body;
    clear(body);
    var nodes = [];
    if (ex.lastError) {
      var at = ex.snapAt ? '（最後に取得できた時点: ' + fmtClock(ex.snapAt) + '）' : '';
      nodes.push(h('p', { className: 'exec-warn', role: 'status', text: EXEC_MSG.loadFailed + at }));
    }
    if (!ex.snap) {
      if (!ex.lastError) nodes.push(h('p', { className: 'empty', text: '読み込み中…' }));
      body.appendChild(h('div', { className: 'exec-block' }, nodes));
      return;
    }
    var snap = ex.snap;
    var stale = statusLine(ex);
    if (stale) nodes.push(stale);
    nodes.push(h('p', { className: 'meta', text: streamText(ex) }));
    body.appendChild(h('div', { className: 'exec-block' }, nodes));

    renderExecWarnings(body, snap);
    renderExecAttempts(body, snap);
    renderExecCounts(body, snap);
    renderExecCost(body, snap);
    renderExecStop(body, snap);
  }

  function renderExecWarnings(body, snap) {
    var warn = [];
    if (snap.warnings > 0) warn.push('警告: ' + count(snap.warnings) + ' 件');
    if (snap.dropped > 0) warn.push('送信の取りこぼし（上限などで落とした件数）: ' + count(snap.dropped) + ' 件');
    if (snap.truncated === true) warn.push('記録の上限に達したため、以降は一部だけ記録されています');
    var retries = snap.apiRetries && typeof snap.apiRetries === 'object' ? Object.keys(snap.apiRetries) : [];
    retries.forEach(function (k) { warn.push('API の再試行（' + k + '）: ' + count(snap.apiRetries[k]) + ' 回'); });
    if (warn.length > 0) {
      body.appendChild(h('div', { className: 'exec-block' }, [
        h('h4', { text: '注意' }),
        h('ul', { className: 'exec-warnlist' }, warn.map(function (t) { return h('li', { text: t }); }))
      ]));
    }
  }

  function renderExecAttempts(body, snap) {
    var attemptItems = snap.attempts.map(function (a) {
      if (!a || typeof a !== 'object') return null;
      var t = '試行 #' + count(a.attemptNo) + '：' + lookup(EXEC_ATTEMPT_STATES, a.state) +
        '（世代 ' + count(a.leaseGeneration) + '）';
      var last = parseInstant(a.lastReceivedAt);
      if (last !== null) t += '、最終の受信 ' + fmtLocal(last);
      return h('li', { text: t });
    }).filter(function (n) { return n !== null; });
    var active = snap.attempts.some(function (a) { return a && EXEC_ACTIVE_ATTEMPT[str(a.state)] === true; });
    var progress = [
      h('h4', { text: '試行' }),
      h('ul', { className: 'exec-list' }, attemptItems)
    ];
    if (active) {
      progress.push(h('p', { className: 'meta', text: '進み具合: 不確定（総量の上限は分からないため、割合は出しません）' }));
      progress.push(h('progress', { 'aria-label': '進み具合（不確定）' }, []));
    }
    body.appendChild(h('div', { className: 'exec-block' }, progress));
  }

  function renderExecCounts(body, snap) {
    var phase = snap.lastPhase === null || snap.lastPhase === undefined ? 'なし' : lookup(EXEC_PHASES, snap.lastPhase);
    var toolDl = h('dl', { className: 'exec-dl' }, []);
    EXEC_TOOLS.forEach(function (pair) {
      toolDl.appendChild(h('dt', { text: pair[1] }));
      toolDl.appendChild(h('dd', { text: count(toolCountFor(snap.tools, pair[0])) + ' 回' }));
    });
    body.appendChild(h('div', { className: 'exec-block' }, [
      h('h4', { text: '進み（メッセージ数・ツール呼び出し）' }),
      h('dl', { className: 'exec-dl' }, [
        h('dt', { text: 'メッセージ数' }), h('dd', { text: count(snap.turns) + ' 件' }),
        h('dt', { text: 'ツール呼び出し（合計）' }), h('dd', { text: count(snap.tools.total) + ' 回' }),
        h('dt', { text: 'ツール結果の失敗' }), h('dd', { text: count(snap.tools.errors) + ' 件' }),
        h('dt', { text: '権限の拒否' }), h('dd', { text: count(snap.permissionDenied) + ' 件' }),
        h('dt', { text: '最後の段階（エージェントの報告）' }), h('dd', { text: phase }),
        h('dt', { text: 'テストの段階' }), h('dd', { text: snap.testingReported === true ? '報告あり' : '報告なし' })
      ]),
      h('p', { className: 'meta', text: 'メッセージ数は assistant のメッセージの数です。API のターン数とは異なることがあります。' }),
      h('h4', { text: 'ツールの種類別の件数' }),
      toolDl
    ]));
  }

  function renderExecCost(body, snap) {
    var cost = snap.cost;
    // A failed attempt has no CLI report (confirmed 0) and its estimate is the reserve: the estimate is then not a real cost.
    var failedNoReport = snap.attempts.some(function (a) { return a && str(a.state) === 'FAILED'; }) &&
      /^0(\.0+)?$/.test(str(cost.confirmedUsd)) && cost.estimatedBasis === 'RESERVE_ESTIMATE';
    var estimateText = usd(cost.estimatedUsd) + '（推定）' + (failedNoReport ? '。予約額。実費ではない' : '');
    body.appendChild(h('div', { className: 'exec-block' }, [
      h('h4', { text: '費用' }),
      h('dl', { className: 'exec-dl' }, [
        h('dt', { text: '確定（CLI の報告。終了後に確定します）' }), h('dd', { text: usd(cost.confirmedUsd) }),
        h('dt', { text: '推定（予約からの見積り。確定ではありません）' }), h('dd', { className: 'exec-estimate', text: estimateText })
      ])
    ]));
  }

  function renderExecStop(body, snap) {
    var stop = snap.stop;
    body.appendChild(h('div', { className: 'exec-block' }, [
      h('h4', { text: '停止' }),
      h('dl', { className: 'exec-dl' }, [
        h('dt', { text: '段階' }), h('dd', { text: lookup(EXEC_STOP, stop.stage) }),
        h('dt', { text: '停止要求' }), h('dd', { text: stop.runCancelRequested === true ? 'あり' : 'なし' }),
        h('dt', { text: 'エージェントの確認（エージェントの報告）' }), h('dd', { text: stop.agentAcknowledged === true ? '停止要求を受けたと報告' : '報告なし' }),
        h('dt', { text: 'プロセスの終了（エージェントの報告）' }), h('dd', { text: stop.processExitConfirmed === true ? '終了を報告' : '報告なし' })
      ])
    ]));
  }

  /** Tool counts by the fixed names. Unknown names are not shown: they are added to OTHER. */
  function toolCountFor(tools, name) {
    var by = tools.byName && typeof tools.byName === 'object' ? tools.byName : {};
    if (name !== 'OTHER') return has(by, name) ? by[name] : 0;
    var other = has(by, 'OTHER') ? by.OTHER : 0;
    Object.keys(by).forEach(function (k) {
      if (!EXEC_TOOLS.some(function (p) { return p[0] === k; })) other += typeof by[k] === 'number' ? by[k] : 0;
    });
    return other;
  }

  function streamText(ex) {
    if (ex.streamOpen) return '取得方法: 常時接続（SSE）。定期の取得も併用しています。';
    if (ex.streamDisabled) return '取得方法: 定期取得（' + Math.round(EXEC.POLL_MS / 1000) + ' 秒ごと）。常時接続は使えなかったため、定期取得に切り替えました。';
    return '取得方法: 定期取得（' + Math.round(EXEC.POLL_MS / 1000) + ' 秒ごと）';
  }

  // ---- Timers (stopped while the tab is hidden) --------------------------

  function budgetBusy() {
    return state.budgetSubmitting === true || state.budgetDialog !== null || $('budget-dialog').open === true;
  }

  function memoryShown() {
    return MEMS.enabled === true && state.autopilotEnabled === true && !$('memory-section').hidden;
  }

  function memoryBusy() {
    return MEMS.busy === true || $('memory-dialog').open === true;
  }

  /** The header's refresh: reads again the data of the page shown now (plus the runs and the health), through the same functions the timer uses. */
  async function refreshAll() {
    if (state.refreshAllRunning) return;
    state.refreshAllRunning = true;
    var btn = $('refresh-all-btn');
    btn.disabled = true;
    var page = currentPageId();
    var jobs = [refreshRuns, checkHealth, refreshStatus];
    if (page === 'goals') {
      jobs.push(refreshAutopilot);
      if (state.autopilotEnabled) jobs.push(refreshGoals);
    }
    if ((page === 'orders' || page === 'approvals') && ORD.enabled) jobs.push(refreshOrders);
    if (page === 'budget' && state.budgetVisible) jobs.push(refreshBudget);
    if (page === 'memory' && memoryShown()) jobs.push(refreshMemory);
    try {
      // One failure never stops the others (each function shows its own error).
      await Promise.all(jobs.map(function (fn) {
        return Promise.resolve().then(fn).catch(function () {});
      }));
    } finally {
      state.refreshAllRunning = false;
      btn.disabled = false;
      $('refresh-all-at').textContent = '最終更新: ' + fmtClock(Date.now());
    }
  }

  function tick() {
    var now = Date.now();
    if (now - state.runsLastAttemptAt >= REFRESH_MS) refreshRuns();
    if (now - state.healthCheckedAt >= REFRESH_MS) checkHealth();
    if (now - state.statusCheckedAt >= REFRESH_MS) refreshStatus();
    if (now - state.autopilotCheckedAt >= REFRESH_MS) refreshAutopilot();
    if (state.autopilotEnabled && now - state.goalsLastAttemptAt >= REFRESH_MS) refreshGoals();
    if (now - ORD.listLastAttemptAt >= REFRESH_MS) refreshOrders();
    // Budget and memory refresh only while their section is shown, and never while a dialog or a submission is open.
    if (state.budgetVisible && !budgetBusy() && now - state.budgetLastAttemptAt >= REFRESH_MS) refreshBudget();
    if (memoryShown() && !memoryBusy() && now - MEMS.lastAttemptAt >= REFRESH_MS) refreshMemory();
    renderRunsStatus(now);
    renderGoalsStatus(now);
  }

  function startTimers() {
    if (state.timer) return;
    state.timer = setInterval(tick, TICK_MS);
    tick();
  }

  function stopTimers() {
    if (state.timer) clearInterval(state.timer);
    state.timer = null;
  }

  // ---- Self-development orders (ADR 0015 stage 1, PR 2b): the order list and detail, the operator's pause and resume, and the
  // stop approval of the root approver. Shown only when the API answers 200 (503, 404 and 403 keep a section hidden).
  // The title, the policy, a requester's ID and any free text are never written into the page. The digest stays in memory
  // and is sent back by the approval only; it is never shown. Every code is shown through the fixed tables below.

  var ORD_ID = /^[0-9A-Z]{26}$/;
  var ORD_DIGEST = /^[0-9a-f]{64}$/;
  var ORD_CODE = /^[A-Z_]{1,40}$/;

  var ORDER_STATES = {
    ACTIVE: { label: '稼働中', desc: '周を始められる状態です（周は運用者が開始します）' },
    PAUSED: { label: '一時停止中', desc: '周を始めません。理由を確認してください' },
    STOP_REQUESTED: { label: '停止の承認待ち', desc: '停止が要求されています。承認されるまで、新しい周は始まりません' },
    STOPPED: { label: '停止済み', desc: '終端の状態です。元に戻せません' }
  };

  var ORDER_REASONS = {
    OPERATOR_PAUSED: '運用者が一時停止しました',
    OPERATOR_RESUMED: '運用者が再開しました',
    STARTUP_HOLD: '機能の有効化後の起動時に保留されました（運用者が確認して再開します）',
    BUDGET_HOLD: '月次の予算の割当てが尽きたため保留されました（運用者が再開します）',
    CYCLE_FAILURES: '周が続けて失敗したため保留されました（運用者が再開します）',
    UNKNOWN_RUN: '周の Run の結果が不明なため保留されました（運用者が確認して再開します）',
    FX_EXPIRED: '為替レートが古く、費用を換算できないため保留されました（運用者が再開します）',
    LIMIT_REACHED: '周の上限に達しました（再開できません。新しい order が必要です）',
    EXPIRED: '期限が過ぎました（再開できません。新しい order が必要です）',
    STOP_REQUESTED: '停止が要求されました',
    STOP_NOT_APPROVED: '停止は承認されませんでした（却下、取り消し、または期限切れ）。再開できます',
    OPERATOR_STOPPED: '承認により停止しました'
  };

  var STOP_REASONS = {
    OPERATOR_REQUEST: '運用者の要求',
    RUNAWAY_SUSPECTED: '暴走の疑い',
    BUDGET_CONCERN: '予算への懸念',
    SCOPE_CONCERN: '範囲への懸念',
    CHILD_CYCLE_FAILURES: '親が自動で停止しました（子の周が続けて失敗したため）',
    CHILD_BUDGET_EXHAUSTED: '親が自動で停止しました（子の予算を使い切ったため）'
  };

  var REQUESTER_KINDS = { AI: 'AI の主体', HUMAN: '人（運用者）' };
  // The codes a person may request a stop with: StopRequestReason.REQUESTABLE on the server (the automatic child codes are not offered).
  var STOP_REQUEST_REASONS = ['OPERATOR_REQUEST', 'RUNAWAY_SUSPECTED', 'BUDGET_CONCERN', 'SCOPE_CONCERN'];
  // A paused order with one of these reasons is not resumed by an operator (ADR 0015, U-11).
  var NOT_RESUMABLE_REASONS = ['LIMIT_REACHED', 'EXPIRED'];

  var ORDER_MSG = {
    confirm: 'この order を停止します。元に戻せません。',
    approved: '停止を承認しました。order は停止済みになりました（元に戻せません）。',
    rejected: '停止の要求を却下しました。order は一時停止中になります（自動では再開しません）。',
    paused: 'order を一時停止しました。',
    resumed: 'order を再開しました。',
    stopConfirm: 'この order の停止を要求します。承認者が承認するまで、order は停止しません。',
    requested: '停止を要求しました。承認されるまで、order は停止しません。',
    cancelled: '停止の要求を取り消しました。order は一時停止中になります（自動では再開しません）。',
    stale: '状態が変わっていました。一覧と詳細を読み直してから、もう一度操作してください。',
    forbidden: 'この操作は、あなたの権限では行えません。',
    notFound: '見つかりません（既に消えたか、状態が変わりました）。',
    badRequest: '入力を確認できませんでした。ページを再読み込みしてください。',
    disabled: '自己開発の order は、今は無効です。',
    network: '通信に失敗しました。操作されたかどうかは、一覧を読み直して確認してください。',
    unknown: '結果を確認できませんでした。一覧を読み直してください。'
  };

  var ORD = {
    enabled: false,
    list: null,
    listOkAt: 0,
    listLoading: false,
    listPending: false,
    listLastAttemptAt: 0,
    approver: false,
    requests: null,
    openId: null,
    detail: null,
    detailFail: null,
    detailLoading: false,
    detailSeq: 0,
    busy: false,
    pendingApprove: null,
    pendingStop: null
  };

  /** A fixed code as text: the explanation and the code. A code outside the table is shown as unknown (with the code, if it has the code shape). */
  function ordCodeText(table, code) {
    if (code === null || code === undefined || str(code) === '') return '';
    var c = str(code);
    if (has(table, c)) return table[c] + '（' + c + '）';
    return ORD_CODE.test(c) ? '不明（' + c + '）' : '不明';
  }

  function ordStateInfo(s) {
    var c = str(s);
    if (has(ORDER_STATES, c)) return ORDER_STATES[c];
    return { label: ORD_CODE.test(c) ? '不明（' + c + '）' : '不明', desc: '不明な状態です（この画面に説明はありません）' };
  }

  function ordNum(v) {
    return typeof v === 'number' && isFinite(v) ? String(v) : '不明';
  }

  /** A yen amount of an order (a JSON number) as text, or 不明. */
  function ordYen(v) {
    return typeof v === 'number' && isFinite(v) ? Number(v).toLocaleString('ja-JP') + ' 円' : '不明';
  }

  function ordTime(s) {
    var t = parseTime(s);
    return t === null ? '不明' : fmtLocal(t);
  }

  function ordRequesterText(kind) {
    var c = str(kind);
    return has(REQUESTER_KINDS, c) ? REQUESTER_KINDS[c] : '不明';
  }

  function ordValidSummary(o) {
    return !!o && typeof o === 'object' && typeof o.orderId === 'string' && ORD_ID.test(o.orderId);
  }

  function ordValidRequest(q) {
    return !!q && typeof q === 'object' && q.state === 'PENDING' &&
      typeof q.requestId === 'string' && ORD_ID.test(q.requestId) &&
      typeof q.orderId === 'string' && ORD_ID.test(q.orderId);
  }

  function ordDigestOk(d) {
    return typeof d === 'string' && ORD_DIGEST.test(d);
  }

  function ordSetMsg(id, text, cls) {
    var el = $(id);
    el.textContent = text;
    el.className = 'result' + (cls ? ' ' + cls : '');
  }

  /** The fixed text for a failed order request. The response body is never shown. */
  function ordFailText(r) {
    if (r.kind === 'session') return MESSAGES.session;
    if (r.kind === 'network') return ORDER_MSG.network;
    if (r.status === 409) return ORDER_MSG.stale;
    if (r.status === 403) return ORDER_MSG.forbidden;
    if (r.status === 404) return ORDER_MSG.notFound;
    if (r.status === 400) return ORDER_MSG.badRequest;
    if (r.status === 503) return ORDER_MSG.disabled;
    return ORDER_MSG.unknown;
  }

  function ordPendingText(p) {
    return 'あり（理由: ' + ordCodeText(STOP_REASONS, p.reasonCode) + '、要求の時刻: ' + ordTime(p.createdAt) + '、期限: ' + ordTime(p.expiresAt) + '）';
  }

  function updateOrderControls() {
    var blocked = ORD.busy || state.sessionExpired;
    $('order-approve-submit').disabled = blocked || ORD.pendingApprove === null;
    updateStopControls();
    renderOrderList();
    renderApprovals();
    updateOrderCreate();
  }

  // ---- Order create (ADR 0015): the title and the policy go to POST /api/selfdev/orders. Neither is ever shown back:
  // the result, the log and the errors use only the fixed texts below. The server checks the length and the characters too.

  var ORDER_CREATE_MSG = {
    saved: 'order を作成しました。',
    bad: '題名は 1〜200 文字、方針は 1〜2000 文字で入力してください（制御文字は使えません）',
    rootExists: 'ルートの order は既にあります',
    invalid: '題名/方針が不正です（長さ、制御文字）',
    maxCycles: '最大の周の数は 1 以上の整数で、サーバーの上限以下にしてください',
    forbidden: 'この操作は許可されていません',
    disabled: 'order は無効です',
    failed: '作成に失敗しました',
    sending: '送信中です…'
  };

  function orderCreateFormState() {
    var title = $('order-title').value.trim();
    var policy = $('order-policy').value.trim();
    var nTitle = Array.from(title).length;
    var nPolicy = Array.from(policy).length;
    var rawCycles = $('order-max-cycles').value.trim();
    var maxCycles = rawCycles === '' ? 1 : (/^[0-9]{1,2}$/.test(rawCycles) ? parseInt(rawCycles, 10) : 0);
    var ok = nTitle >= 1 && nTitle <= MAX_TITLE && !CONTROL_CHARS.test(title) &&
      nPolicy >= 1 && nPolicy <= MAX_POLICY && !BODY_CONTROL_CHARS.test(policy);
    return { title: title, policy: policy, maxCycles: maxCycles, ok: ok, cyclesOk: maxCycles >= 1 && maxCycles <= 10 };
  }

  function updateOrderCreate() {
    $('order-title-count').textContent = Array.from($('order-title').value).length + ' / ' + MAX_TITLE;
    $('order-policy-count').textContent = Array.from($('order-policy').value).length + ' / ' + MAX_POLICY;
    var blocked = state.orderSubmitting || state.sessionExpired;
    $('order-title').disabled = blocked;
    $('order-policy').disabled = blocked;
    $('order-max-cycles').disabled = blocked;
    $('order-create-submit').disabled = blocked;
  }

  function showOrderCreateResult(text, cls) {
    var el = $('order-create-result');
    el.textContent = text;
    el.className = 'result' + (cls ? ' ' + cls : '');
  }

  /** The fixed text for a failed create. The response body is never shown. */
  function orderCreateFailText(r) {
    if (r.kind === 'session') return MESSAGES.session;
    if (r.status === 409 && r.code === 'ROOT_EXISTS') return ORDER_CREATE_MSG.rootExists;
    if (r.status === 400 && r.code === 'MAX_CYCLES_INVALID') return ORDER_CREATE_MSG.maxCycles;
    if (r.status === 400) return ORDER_CREATE_MSG.invalid;
    if (r.status === 403) return ORDER_CREATE_MSG.forbidden;
    if (r.status === 503) return ORDER_CREATE_MSG.disabled;
    return ORDER_CREATE_MSG.failed;
  }

  async function onOrderCreateSubmit(ev) {
    ev.preventDefault();
    if (state.orderSubmitting || state.sessionExpired || !ORD.enabled) {
      updateOrderCreate();
      return;
    }
    var f = orderCreateFormState();
    if (!f.ok) {
      showOrderCreateResult(ORDER_CREATE_MSG.bad, 'error');
      return;
    }
    if (!f.cyclesOk) {
      showOrderCreateResult(ORDER_CREATE_MSG.maxCycles, 'error');
      return;
    }
    state.orderSubmitting = true;
    updateOrderCreate();
    showOrderCreateResult(ORDER_CREATE_MSG.sending, '');
    var r;
    try {
      r = await apiFetch('/api/selfdev/orders', 'POST', { title: f.title, policy: f.policy, maxCycles: f.maxCycles });
    } finally {
      state.orderSubmitting = false;
      updateOrderCreate();
    }
    if (r.ok) {
      $('order-title').value = '';
      $('order-policy').value = '';
      $('order-max-cycles').value = '1';
      updateOrderCreate();
      showOrderCreateResult(ORDER_CREATE_MSG.saved, 'ok');
      refreshOrders();
      return;
    }
    showOrderCreateResult(orderCreateFailText(r), 'error');
    if (r.status === 409) refreshOrders();
  }

  function renderOrderSection() {
    $('orders-section').hidden = !ORD.enabled;
    $('approval-section').hidden = !(ORD.enabled && ORD.approver);
    if (state.sessionExpired) {
      $('orders-status').textContent = 'セッション切れのため、一覧を更新できません';
    } else {
      $('orders-status').textContent = ORD.listOkAt === 0 ? NOT_FETCHED_TEXT : '最終取得: ' + fmtLocal(ORD.listOkAt);
    }
    $('approval-status').textContent = ORD.requests === null ? NOT_FETCHED_TEXT : '保留中の要求: ' + ORD.requests.length + ' 件';
    updateOrderControls();
    renderNav();
  }

  async function refreshOrders() {
    if (state.sessionExpired) return;
    if (ORD.listLoading) {
      ORD.listPending = true;
      return;
    }
    ORD.listLoading = true;
    ORD.listLastAttemptAt = Date.now();
    // Only an answer to a real order request settles the nav; the autopilot's own answer settles it while the autopilot is off.
    var asked = state.autopilotEnabled === true;
    try {
      // The order API answers 503 while the autopilot is off, so nothing is read until the autopilot is known to be on.
      var r = state.autopilotEnabled ? await apiFetch('/api/selfdev/orders', 'GET') : { ok: false };
      if (r.ok && Array.isArray(r.data)) {
        ORD.enabled = true;
        ORD.list = r.data.filter(ordValidSummary);
        ORD.listOkAt = Date.now();
        var q = await apiFetch('/api/selfdev/stop-requests?state=PENDING', 'GET');
        if (q.ok && Array.isArray(q.data)) {
          ORD.approver = true;
          ORD.requests = q.data.filter(ordValidRequest);
        } else {
          ORD.approver = false;
          ORD.requests = null;
        }
      } else {
        ORD.enabled = false;
        ORD.list = null;
        ORD.approver = false;
        ORD.requests = null;
      }
    } finally {
      ORD.listLoading = false;
      if (asked) {
        NAV.settled.orders = true;
        NAV.settled.approvals = true;
      }
      renderOrderSection();
      if (ORD.listPending) {
        ORD.listPending = false;
        refreshOrders();
      }
    }
  }

  function renderOrderList() {
    var ul = $('orders-list');
    clear(ul);
    if (!ORD.enabled || !ORD.list) return;
    if (ORD.list.length === 0) {
      ul.appendChild(h('li', { className: 'empty', text: 'まだ order はありません。' }));
      return;
    }
    ordTree(ORD.list).forEach(function (n) { ul.appendChild(ordRow(n.order, n.level)); });
  }

  /**
   * The list as a tree, in the list's order: each child comes right after its parent, one level deeper. A row whose parent is not
   * in the list (an orphan) is top-level. Only the IDs and the parent IDs are read here, and a row is shown once.
   */
  function ordTree(list) {
    var ids = {};
    list.forEach(function (o) { ids[o.orderId] = true; });
    var kids = {};
    var roots = [];
    list.forEach(function (o) {
      var p = typeof o.parentOrderId === 'string' && ids[o.parentOrderId] && o.parentOrderId !== o.orderId ? o.parentOrderId : null;
      if (p === null) roots.push(o);
      else (kids[p] = kids[p] || []).push(o);
    });
    var out = [];
    var seen = {};
    function walk(o, level) {
      if (seen[o.orderId]) return;
      seen[o.orderId] = true;
      out.push({ order: o, level: level });
      (kids[o.orderId] || []).forEach(function (k) { walk(k, level + 1); });
    }
    roots.forEach(function (o) { walk(o, 0); });
    // A row that no root reached (only possible with a cycle in the data) is still shown, as top-level.
    list.forEach(function (o) { walk(o, 0); });
    return out;
  }

  /** The indent classes exist for levels 0 to 4 (ui/app.css); a deeper row uses the last one. */
  var ORD_MAX_LEVEL = 4;

  function ordRow(o, level) {
    var id = o.orderId;
    var open = ORD.openId === id;
    var st = ordStateInfo(o.state);
    var toggle = h('button', {
      type: 'button',
      className: 'secondary',
      text: open ? '閉じる' : '詳細',
      'aria-expanded': open ? 'true' : 'false',
      'aria-controls': 'order-detail-' + id
    });
    toggle.addEventListener('click', function () { toggleOrder(id); });
    var kids = [
      h('div', { className: 'run-head' }, [
        h('span', { className: 'run-title', text: 'order ' + id }),
        h('span', { className: 'badge', text: '状態: ' + st.label })
      ]),
      h('div', { className: 'run-meta' }, [
        h('span', { className: 'time', text: '深さ: ' + ordNum(o.depth) }),
        h('span', { className: 'time', text: '子の数: ' + ordNum(o.childCount) }),
        h('span', { className: 'time', text: '使用: ' + ordYen(o.spentJpy) + ' / 予算: ' + ordYen(o.orderBudgetJpy) }),
        h('span', { className: 'time', text: '周: ' + ordNum(o.cyclesStarted) + ' / ' + ordNum(o.maxCycles) }),
        h('span', { className: 'time', text: '連続して失敗した周: ' + ordNum(o.consecutiveFailedCycles) }),
        h('span', { className: 'time', text: '期限: ' + ordTime(o.expiresAt) })
      ]),
      h('div', { className: 'run-sub' }, [
        o.stateReason ? h('span', { className: 'reason', text: '理由: ' + ordCodeText(ORDER_REASONS, o.stateReason) }) : null
      ]),
      toggle
    ];
    if (open) kids.push(ordDetailBox(id));
    var lv = Math.min(Math.max(level | 0, 0), ORD_MAX_LEVEL);
    return h('li', { className: 'run-item order-level-' + lv }, kids);
  }

  function ordDetailBox(id) {
    var box = h('div', { className: 'goal-detail', id: 'order-detail-' + id });
    var d = ORD.detail;
    if (ORD.detailLoading || !d || d.orderId !== id) {
      if (ORD.detailFail) box.appendChild(h('p', { className: 'error', text: ORD.detailFail }));
      else box.appendChild(h('p', { className: 'empty', text: '読み込み中…' }));
      return box;
    }
    ordRenderDetail(box, d);
    return box;
  }

  /** The detail: the state and the cycles, the pending stop request (without its digest), and the pause or resume the state allows. */
  function ordRenderDetail(box, d) {
    var o = d.order;
    var st = ordStateInfo(o.state);
    box.appendChild(h('p', {
      className: 'meta',
      text: '状態: ' + st.label + ' — ' + st.desc + (o.stateReason ? '（' + ordCodeText(ORDER_REASONS, o.stateReason) + '）' : '')
    }));
    box.appendChild(h('p', {
      className: 'meta',
      text: '周: 開始 ' + ordNum(o.cyclesStarted) + ' / 上限 ' + ordNum(o.maxCycles) + '（連続して失敗した周: ' + ordNum(o.consecutiveFailedCycles) + '）'
    }));
    box.appendChild(h('p', { className: 'meta', text: '停止の要求: ' + (d.pending ? ordPendingText(d.pending) : 'なし') }));
    box.appendChild(h('p', { className: 'meta', text: '題名と方針は、この画面には表示しません。' }));

    var actions = [];
    var versionOk = typeof o.version === 'number' && isFinite(o.version);
    if (versionOk && o.state === 'ACTIVE') {
      actions.push(actionButton('一時停止', function () { ordPause(d); }, 'secondary'));
    }
    if (versionOk && o.state === 'PAUSED' && NOT_RESUMABLE_REASONS.indexOf(str(o.stateReason)) === -1) {
      actions.push(actionButton('再開', function () { ordResume(d); }));
    }
    // A stop is requested from ACTIVE or PAUSED, and only when no request waits. The server decides the rest.
    if (versionOk && (o.state === 'ACTIVE' || o.state === 'PAUSED') && !d.pending) {
      actions.push(actionButton('停止を要求', function () { ordOpenStop(d); }, 'secondary'));
    }
    // The pending request's requester is not in the response, so any waiting request offers the cancel. The server answers 403 when it is not allowed.
    if (ordCancellable(d)) {
      actions.push(actionButton('要求を取り消す', function () { ordCancelStop(d); }, 'secondary'));
    }
    actions.forEach(function (b) { b.disabled = ORD.busy || state.sessionExpired; });
    if (actions.length) box.appendChild(h('div', { className: 'detail-actions' }, actions));
  }

  function toggleOrder(id) {
    if (ORD.openId === id) {
      ORD.openId = null;
      ORD.detail = null;
      ORD.detailFail = null;
      renderOrderList();
      return;
    }
    ORD.openId = id;
    ORD.detail = null;
    ORD.detailFail = null;
    renderOrderList();
    loadOrderDetail(id);
  }

  async function loadOrderDetail(id) {
    var seq = ++ORD.detailSeq;
    ORD.detailLoading = true;
    var r = await apiFetch('/api/selfdev/orders/' + encodeURIComponent(id), 'GET');
    if (seq !== ORD.detailSeq) return;
    ORD.detailLoading = false;
    var d = r.ok && r.data && typeof r.data === 'object' ? r.data : null;
    if (d && ordValidSummary(d.order)) {
      // Only the state and the pending request are read. The title, the policy and any other field are not used.
      ORD.detail = {
        orderId: id,
        order: d.order,
        pending: d.pendingStopRequest && typeof d.pendingStopRequest === 'object' ? d.pendingStopRequest : null
      };
      ORD.detailFail = null;
    } else {
      ORD.detail = null;
      ORD.detailFail = r.ok ? ORDER_MSG.unknown : ordFailText(r);
    }
    renderOrderList();
  }

  /** After a write: the list and the stop requests are read again, and the open detail too when it is the same order. */
  function ordAfterWrite(orderId) {
    refreshOrders();
    if (ORD.openId === orderId) loadOrderDetail(orderId);
  }

  function ordPause(d) {
    ordWrite('pause', d, ORDER_MSG.paused);
  }

  function ordResume(d) {
    ordWrite('resume', d, ORDER_MSG.resumed);
  }

  /** Pause or resume, sent with the version the operator read. The paths are literal (see the fetch-target rule in the UI test). */
  async function ordWrite(action, d, okText) {
    if (ORD.busy || state.sessionExpired) return;
    ORD.busy = true;
    updateOrderControls();
    var body = { version: d.order.version };
    var r;
    try {
      r = action === 'pause'
        ? await apiFetch('/api/selfdev/orders/' + encodeURIComponent(d.orderId) + '/pause', 'POST', body)
        : await apiFetch('/api/selfdev/orders/' + encodeURIComponent(d.orderId) + '/resume', 'POST', body);
    } finally {
      ORD.busy = false;
    }
    ordSetMsg('orders-msg', r.ok ? okText : ordFailText(r), r.ok ? 'ok' : 'error');
    ordAfterWrite(d.orderId);
  }

  function renderApprovals() {
    var ul = $('approval-list');
    clear(ul);
    if (!ORD.enabled || !ORD.approver || !ORD.requests) return;
    if (ORD.requests.length === 0) {
      ul.appendChild(h('li', { className: 'empty', text: '保留中の停止の要求はありません。' }));
      return;
    }
    ORD.requests.forEach(function (req) { ul.appendChild(ordApprovalRow(req)); });
  }

  /** One pending stop request. Its digest is used by the approval only, and is not written into the page. */
  function ordApprovalRow(req) {
    var approve = actionButton('承認', function () { ordOpenApprove(req); });
    approve.disabled = ORD.busy || state.sessionExpired || !ordDigestOk(req.digest);
    var reject = actionButton('却下', function () { ordReject(req); }, 'secondary');
    reject.disabled = ORD.busy || state.sessionExpired;
    return h('li', { className: 'run-item' }, [
      h('div', { className: 'run-head' }, [
        h('span', { className: 'run-title', text: '対象の order: ' + req.orderId }),
        h('span', { className: 'badge', text: '保留中' })
      ]),
      h('div', { className: 'run-sub' }, [
        h('span', { className: 'reason', text: '理由: ' + ordCodeText(STOP_REASONS, req.reasonCode) })
      ]),
      h('div', { className: 'run-meta' }, [
        h('span', { className: 'time', text: '要求した主体: ' + ordRequesterText(req.requesterKind) }),
        h('span', { className: 'time', text: '要求の時刻: ' + ordTime(req.createdAt) }),
        h('span', { className: 'time', text: '期限: ' + ordTime(req.expiresAt) })
      ]),
      h('div', { className: 'detail-actions' }, [approve, reject])
    ]);
  }

  function ordOpenApprove(req) {
    if (ORD.busy || state.sessionExpired || !ordDigestOk(req.digest)) return;
    ORD.pendingApprove = req;
    $('order-approve-text').textContent = ORDER_MSG.confirm;
    $('order-approve-target').textContent = '対象の order: ' + req.orderId + '（理由: ' + ordCodeText(STOP_REASONS, req.reasonCode) + '）';
    updateOrderControls();
    $('order-approve-dialog').showModal();
  }

  /** The approval sends the digest back as it was read, and nothing else. A failure closes the dialog and shows a fixed text. */
  async function ordSubmitApprove() {
    var req = ORD.pendingApprove;
    if (!req || ORD.busy || state.sessionExpired || !ordDigestOk(req.digest)) return;
    ORD.busy = true;
    updateOrderControls();
    var r;
    try {
      r = await apiFetch('/api/selfdev/stop-requests/' + encodeURIComponent(req.requestId) + '/approve', 'POST', { digest: req.digest });
    } finally {
      ORD.busy = false;
    }
    ORD.pendingApprove = null;
    $('order-approve-dialog').close();
    ordSetMsg('approval-msg', r.ok ? ORDER_MSG.approved : ordFailText(r), r.ok ? 'ok' : 'error');
    updateOrderControls();
    ordAfterWrite(req.orderId);
  }

  async function ordReject(req) {
    if (ORD.busy || state.sessionExpired) return;
    ORD.busy = true;
    updateOrderControls();
    var r;
    try {
      // The reject takes no reason. The body is an empty JSON object, because the route requires a JSON content type.
      r = await apiFetch('/api/selfdev/stop-requests/' + encodeURIComponent(req.requestId) + '/reject', 'POST', {});
    } finally {
      ORD.busy = false;
    }
    ordSetMsg('approval-msg', r.ok ? ORDER_MSG.rejected : ordFailText(r), r.ok ? 'ok' : 'error');
    ordAfterWrite(req.orderId);
  }

  function ordCancellable(d) {
    return !!d.pending && ordValidRequest(d.pending);
  }

  function ordOption(value, text) {
    var o = document.createElement('option');
    o.value = value;
    o.textContent = text;
    return o;
  }

  /** The stop request dialog: the reason code is chosen from the fixed list; the request body is the version read and the code. */
  function ordOpenStop(d) {
    if (ORD.busy || state.sessionExpired || typeof d.order.version !== 'number') return;
    ORD.pendingStop = { orderId: d.orderId, version: d.order.version };
    $('order-stop-text').textContent = ORDER_MSG.stopConfirm;
    $('order-stop-target').textContent = '対象の order: ' + d.orderId;
    $('order-stop-reason').value = '';
    updateStopControls();
    $('order-stop-dialog').showModal();
  }

  function updateStopControls() {
    var sel = $('order-stop-reason');
    var blocked = ORD.busy || state.sessionExpired;
    var chosen = STOP_REQUEST_REASONS.indexOf(sel.value) !== -1;
    sel.disabled = blocked;
    $('order-stop-cancel').disabled = ORD.busy;
    $('order-stop-submit').disabled = blocked || ORD.pendingStop === null || !chosen;
  }

  async function ordSubmitStop() {
    var req = ORD.pendingStop;
    var reason = $('order-stop-reason').value;
    if (!req || ORD.busy || state.sessionExpired || STOP_REQUEST_REASONS.indexOf(reason) === -1) return;
    ORD.busy = true;
    updateOrderControls();
    var r;
    try {
      r = await apiFetch('/api/selfdev/orders/' + encodeURIComponent(req.orderId) + '/stop-requests', 'POST', { version: req.version, reasonCode: reason });
    } finally {
      ORD.busy = false;
    }
    ORD.pendingStop = null;
    $('order-stop-dialog').close();
    ordSetMsg('orders-msg', r.ok ? ORDER_MSG.requested : ordFailText(r), r.ok ? 'ok' : 'error');
    ordAfterWrite(req.orderId);
  }

  /** Cancels the waiting request. The body is an empty JSON object, as for reject. */
  async function ordCancelStop(d) {
    if (ORD.busy || state.sessionExpired || !ordCancellable(d)) return;
    ORD.busy = true;
    updateOrderControls();
    var r;
    try {
      r = await apiFetch('/api/selfdev/stop-requests/' + encodeURIComponent(d.pending.requestId) + '/cancel', 'POST', {});
    } finally {
      ORD.busy = false;
    }
    ordSetMsg('orders-msg', r.ok ? ORDER_MSG.cancelled : ordFailText(r), r.ok ? 'ok' : 'error');
    ordAfterWrite(d.orderId);
  }

  function initOrders() {
    var reasonSel = $('order-stop-reason');
    reasonSel.appendChild(ordOption('', '理由を選んでください'));
    STOP_REQUEST_REASONS.forEach(function (c) { reasonSel.appendChild(ordOption(c, STOP_REASONS[c])); });
    reasonSel.addEventListener('change', updateStopControls);
    $('order-stop-cancel').addEventListener('click', function () {
      ORD.pendingStop = null;
      $('order-stop-dialog').close();
      updateOrderControls();
    });
    $('order-stop-submit').addEventListener('click', ordSubmitStop);
    $('order-stop-dialog').addEventListener('close', function () {
      ORD.pendingStop = null;
      updateOrderControls();
    });
    $('order-title').addEventListener('input', updateOrderCreate);
    $('order-policy').addEventListener('input', updateOrderCreate);
    $('order-create-form').addEventListener('submit', onOrderCreateSubmit);
    $('order-approve-cancel').addEventListener('click', function () {
      ORD.pendingApprove = null;
      $('order-approve-dialog').close();
      updateOrderControls();
    });
    $('order-approve-submit').addEventListener('click', ordSubmitApprove);
    $('order-approve-dialog').addEventListener('close', function () {
      ORD.pendingApprove = null;
      updateOrderControls();
    });
    renderOrderSection();
  }

  function init() {
    $('submit-form').addEventListener('submit', onSubmit);
    $('title').addEventListener('input', updateCounter);
    $('body').addEventListener('input', updateBodyCounter);
    $('refresh-all-btn').addEventListener('click', refreshAll);
    // The skip link would change the hash (and so the page); focus the body instead.
    $('skip-link').addEventListener('click', function (ev) {
      if (ev && ev.preventDefault) ev.preventDefault();
      $('main').focus();
    });
    $('scope-form').addEventListener('change', onScopeChange);
    // Re-entering the sign-in flow is done by the browser reload; the sign-in URL is never built here.
    $('reload-btn').addEventListener('click', function () {
      window.location.reload();
    });
    window.addEventListener('hashchange', route);
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) stopTimers();
      else startTimers();
      execVisibilityChanged();
    });
    updateCounter();
    renderHealth();
    renderModeNote();
    renderKindNote();
    updateKindControls();
    $('kind-form').addEventListener('change', onKindChange);
    $('release-reason').addEventListener('input', updateReleaseCount);
    $('goal-form').addEventListener('submit', onGoalSubmit);
    $('goal-title').addEventListener('input', function () { updateGoalCounters(); updateGoalControls(); });
    $('goal-body').addEventListener('input', function () { updateGoalCounters(); updateGoalControls(); });
    $('memory-apply').addEventListener('click', onMemoryApply);
    $('memory-prev').addEventListener('click', function () { onMemoryPage(-1); });
    $('memory-next').addEventListener('click', function () { onMemoryPage(1); });
    $('memory-dryrun').addEventListener('click', onMemoryDryRun);
    $('memory-cond-delete').addEventListener('click', openMemoryConditionDelete);
    $('memory-reason').addEventListener('input', updateMemoryCount);
    memFillSelects();
    $('autopilot-toggle').addEventListener('click', onAutopilotToggle);
    $('breaker-reset').addEventListener('click', onBreakerReset);
    ['runs-status', 'promotion-status', 'plans-status', 'approval-status', 'orders-status', 'budget-status', 'memory-status']
      .forEach(function (id) { $(id).textContent = NOT_FETCHED_TEXT; });
    initBudget();
    initOrders();
    refreshBudget();
    updateGoalCounters();
    renderAutopilot();
    refreshAutopilot();
    renderRunsStatus(Date.now());
    route();
    if (!document.hidden) startTimers();
  }

  init();
})();
