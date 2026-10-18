/*
 * Kivra minimal UI (see README "UI"). Plain JavaScript, no framework, no HTML string injection.
 * Server data enters the DOM only through textContent (see h() and str()). A link is made only from a validated
 * pull request URL (see safePrUrl()).
 */
(function () {
  'use strict';

  var REFRESH_MS = 30000;
  var TICK_MS = 1000;
  var MAX_TITLE = 200;
  var MAX_BODY = 8000;
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
    CODING_OVERLOADED: 'モデルが混雑していました'
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
    releasing: false,
    autopilotEnabled: false,
    autopilot: null,
    autopilotLoading: false,
    autopilotCheckedAt: 0,
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
    stopping: false
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
      else if (k === 'id' || k.indexOf('aria-') === 0) node.setAttribute(k, v);
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
    $('session-banner').hidden = false;
    $('refresh-btn').disabled = true;
    updateSubmitEnabled();
    updateGoalControls();
    renderRunsStatus(Date.now());
    renderGoalsStatus(Date.now());
  }

  function fail(path, kind, status) {
    if (kind === 'session' && isApi(path)) markSessionExpired();
    return { ok: false, kind: kind, status: status };
  }

  /**
   * Same-origin only. This page sets no credential header: the reverse proxy (oauth2-proxy) adds the token.
   * redirect: 'manual' keeps a redirect to the sign-in page from being followed silently. It shows up as an
   * opaqueredirect response, which is treated as an expired session. The page never builds a redirect URL itself.
   */
  async function apiFetch(path, method, body) {
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
    if (res.type === 'opaqueredirect' || res.redirected) return fail(path, 'session');
    if (!res.ok) return fail(path, kindOf(res.status), res.status);
    // A 2xx that is not JSON (e.g. an HTML sign-in page served after a followed redirect) is an expired session for /api/*.
    var contentType = res.headers.get('content-type') || '';
    if (!/^application\/json(\s*;|\s*$)/i.test(contentType)) {
      return fail(path, isApi(path) ? 'session' : 'invalid');
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
      el.textContent = '未取得';
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

  function route() {
    var r = parseRoute();
    var showDetail = r !== null;
    $('view-list').hidden = showDetail;
    $('view-detail').hidden = !showDetail;
    $('detail-notice').textContent = '';
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

  /** The reason as sent: trimmed. It must be 1 to 500 characters (code points) and have no control characters. */
  function releaseReasonText() {
    return $('release-reason').value.trim();
  }

  function reasonIsValid(text) {
    var n = Array.from(text).length;
    return n >= 1 && n <= RELEASE_MAX && !CONTROL_CHARS.test(text);
  }

  function showReleaseError(text) {
    var el = $('release-error');
    el.textContent = text;
    el.hidden = text === '';
  }

  /** The send button is enabled only for a valid reason and while no send is running. */
  function updateReleaseControls() {
    var text = releaseReasonText();
    $('release-count').textContent = Array.from(text).length + ' / ' + RELEASE_MAX;
    $('release-submit').disabled = state.releasing || !reasonIsValid(text);
    $('release-cancel').disabled = state.releasing;
  }

  function openRelease(run, all, kind) {
    var text = RELEASE_TEXT[kind];
    state.release = { runId: run.runId, all: all, kind: kind };
    $('release-title').textContent = text.title;
    $('release-explain').textContent = text.explain;
    $('release-reason').value = '';
    showReleaseError('');
    updateReleaseControls();
    $('release-dialog').showModal();
  }

  /** Reloads the detail only when it is still the page on screen (the operator may have navigated away). */
  function reloadReleasedRun(rel) {
    var cur = parseRoute();
    if (cur && cur.id === rel.runId) loadDetail(rel.runId, rel.all);
  }

  async function submitRelease() {
    var rel = state.release;
    if (!rel || state.releasing || state.sessionExpired) return;
    var reason = releaseReasonText();
    if (!reasonIsValid(reason)) {
      showReleaseError(MESSAGES.releaseBadRequest);
      updateReleaseControls();
      return;
    }
    state.releasing = true;
    showReleaseError('');
    updateReleaseControls();
    var r;
    try {
      r = rel.kind === 'UNKNOWN'
        ? await apiFetch('/api/admin/selfdev/runs/' + encodeURIComponent(rel.runId) + '/release', 'POST', { reason: reason })
        : await apiFetch('/api/admin/selfdev/runs/' + encodeURIComponent(rel.runId) + '/release-unbound-pr', 'POST', { reason: reason });
    } finally {
      state.releasing = false;
    }

    if (r.ok) {
      state.release = null;
      $('release-dialog').close();
      $('detail-notice').textContent = MESSAGES.releaseDone;
      reloadReleasedRun(rel);
      refreshRuns();
      return;
    }
    updateReleaseControls();
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
    $('plans-refresh').disabled = state.sessionExpired;
    $('autopilot-toggle').disabled = state.sessionExpired || !state.autopilot;
    $('breaker-reset').disabled = state.sessionExpired || !state.autopilot;
    $('stop-submit').disabled = state.stopping || !stopReasonValid(stopReasonText());
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
      } else {
        state.autopilotEnabled = false;
        state.autopilot = null;
      }
    } finally {
      state.autopilotLoading = false;
      renderAutopilot();
    }
  }

  function renderAutopilot() {
    var on = state.autopilotEnabled === true;
    $('goal-section').hidden = !on;
    $('plans-section').hidden = !on;
    if (!on) {
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
    el.textContent = state.goalsOkAt === 0 ? '未取得' : '最終取得: ' + fmtLocal(state.goalsOkAt) + '（' + relative(state.goalsOkAt, now) + '）';
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

  // ---- Stop dialog (the reason is required; it is sent, not kept) --------

  function stopReasonText() {
    return $('stop-reason').value.trim();
  }

  function stopReasonValid(text) {
    var n = Array.from(text).length;
    return n >= 1 && n <= GOAL_STOP_MAX && !CONTROL_CHARS.test(text);
  }

  function showStopError(text) {
    var el = $('stop-error');
    el.textContent = text;
    el.hidden = text === '';
  }

  function openStop(goal) {
    state.stop = { goalId: goal.goalId, version: goal.version };
    $('stop-title').textContent = 'Goal の中止';
    $('stop-explain').textContent = 'この Goal を中止します。まだ投入していないタスクは見送りになります。作成済みの PR は閉じず、マージもしません（PR の扱いは人が決めます）。';
    $('stop-reason').value = '';
    showStopError('');
    updateGoalControls();
    $('stop-dialog').showModal();
  }

  async function submitStop() {
    var s = state.stop;
    if (!s || state.stopping || state.sessionExpired) return;
    var reason = stopReasonText();
    if (!stopReasonValid(reason)) {
      showStopError(AP.reasonInvalid);
      updateGoalControls();
      return;
    }
    state.stopping = true;
    showStopError('');
    updateGoalControls();
    var r;
    try {
      r = await apiFetch('/api/selfdev/goals/' + encodeURIComponent(s.goalId) + '/stop', 'POST', { version: s.version, reason: reason });
    } finally {
      state.stopping = false;
    }
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
    updateGoalControls();
    var msg = r.kind === 'network' ? AP.network : (r.status === 400 ? AP.reasonInvalid : (r.status === 409 ? AP.stale : (r.status === 404 ? AP.notFound : AP.unknown)));
    showStopError(msg);
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

  // ---- Timers (stopped while the tab is hidden) --------------------------

  function tick() {
    var now = Date.now();
    if (now - state.runsLastAttemptAt >= REFRESH_MS) refreshRuns();
    if (now - state.healthCheckedAt >= REFRESH_MS) checkHealth();
    if (now - state.statusCheckedAt >= REFRESH_MS) refreshStatus();
    if (now - state.autopilotCheckedAt >= REFRESH_MS) refreshAutopilot();
    if (state.autopilotEnabled && now - state.goalsLastAttemptAt >= REFRESH_MS) refreshGoals();
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

  function init() {
    $('submit-form').addEventListener('submit', onSubmit);
    $('title').addEventListener('input', updateCounter);
    $('body').addEventListener('input', updateBodyCounter);
    $('refresh-btn').addEventListener('click', function () {
      refreshRuns();
      checkHealth();
      refreshStatus();
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
    });
    updateCounter();
    renderHealth();
    renderModeNote();
    renderKindNote();
    updateKindControls();
    $('kind-form').addEventListener('change', onKindChange);
    $('release-reason').addEventListener('input', updateReleaseControls);
    $('release-cancel').addEventListener('click', function () {
      $('release-dialog').close();
    });
    $('release-submit').addEventListener('click', submitRelease);
    $('goal-form').addEventListener('submit', onGoalSubmit);
    $('goal-title').addEventListener('input', function () { updateGoalCounters(); updateGoalControls(); });
    $('goal-body').addEventListener('input', function () { updateGoalCounters(); updateGoalControls(); });
    $('plans-refresh').addEventListener('click', function () { refreshAutopilot(); refreshGoals(); });
    $('autopilot-toggle').addEventListener('click', onAutopilotToggle);
    $('breaker-reset').addEventListener('click', onBreakerReset);
    $('stop-reason').addEventListener('input', updateGoalControls);
    $('stop-cancel').addEventListener('click', function () { $('stop-dialog').close(); });
    $('stop-submit').addEventListener('click', submitStop);
    updateGoalCounters();
    renderAutopilot();
    refreshAutopilot();
    renderRunsStatus(Date.now());
    route();
    if (!document.hidden) startTimers();
  }

  init();
})();
