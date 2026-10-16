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
  var CONTROL_CHARS = /[\u0000-\u001f\u007f-\u009f]/;
  var PR_URL = /^https:\/\/github\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\/pull\/[0-9]+$/;

  var MESSAGES = {
    session: 'セッションが切れました（ログインが必要です）。「再読み込みしてログインし直す」からログインし直してください。',
    notFound: '見つかりません（他のユーザーの Run は見えません）',
    badRequest: '入力内容を確認してください（1〜200 文字、制御文字は使えません）',
    conflict: 'この投入は受け付けられませんでした（同じ投入キーで別のタイトルが使われていました）。もう一度投入してください。',
    server: 'サーバーでエラーが起きました。時間をおいてもう一度お試しください。',
    network: '通信に失敗しました。',
    submitNetwork: '通信に失敗しました。Run が作成されたかどうかは確認できていません。同じ内容で投入し直しても、同じ Run として扱われます（二重には作られません）。',
    invalid: '応答の形式が想定と違います。ログインが切れている可能性があります。ページを再読み込みしてください。'
  };

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
    COMMIT_NOT_APPLIED_TWICE: 'コミットが二度とも反映されなかった（GitHub 側の確認が必要）',
    BRANCH_ADVANCED_BEFORE_COMMIT: 'コミットの前にブランチが進んでいた',
    DEVELOPER_OUTCOME_UNKNOWN: 'モデル呼び出しの結果が不明',
    DEVELOPER_TIMEOUT: 'モデル呼び出しの期限が過ぎた（結果が不明な可能性）',
    DEVELOPER_DUPLICATE_REQUEST: '同じ要求が処理中、または結果が不明',
    DEVELOPER_BUDGET: '予算の予約が拒否された、または単価が不明（モデルは呼ばれていない）',
    DEVELOPER_POLICY_REJECTED: 'モデルの変更案がポリシーに通らなかった'
  };

  var ACTIONS = {
    RUN_CREATED: '作成',
    RUN_TRANSITION: '状態の変更',
    RUN_PR_BOUND: 'PR と結び付け',
    RUN_RELEASED: '運用者が解放'
  };

  var state = {
    idempotencyKey: newKey(),
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
    timer: null
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
    renderRunsStatus(Date.now());
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

  /**
   * The only place that decides whether the submit button is enabled. Use it after every change to
   * submitting or sessionExpired, so a finished send never re-enables the button after the session expired.
   */
  function updateSubmitEnabled() {
    var blocked = state.submitting || state.sessionExpired;
    $('submit-btn').disabled = blocked;
    $('submit-note').hidden = !state.sessionExpired;
  }

  function updateCounter() {
    var n = Array.from($('title').value).length;
    $('title-count').textContent = n + ' / ' + MAX_TITLE;
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
    state.submitting = true;
    updateSubmitEnabled();
    showResult('送信中です…', '');

    var r;
    try {
      r = await apiFetch('/api/tasks', 'POST', { title: title, idempotencyKey: state.idempotencyKey });
    } finally {
      state.submitting = false;
      updateSubmitEnabled();
    }

    var accepted = r.ok && (r.status === 202 || r.status === 200) && r.data && typeof r.data.runId === 'string';
    if (accepted) {
      // A new key only after success. (Same key + same title is a replay that returns the same Run.)
      state.idempotencyKey = newKey();
      $('title').value = '';
      updateCounter();
      var stateText = '状態: ' + stateInfo(r.data.state).label + '（シミュレーション）';
      if (r.status === 202) {
        showResult('受け付けました。Run ID: ' + str(r.data.runId) + ' / ' + stateText, 'ok');
      } else {
        showResult('同じ投入として既に受け付け済みです（既存の Run）。Run ID: ' + str(r.data.runId) + ' / ' + stateText, 'ok');
      }
      refreshRuns();
      return;
    }

    if (r.kind === 'conflict') state.idempotencyKey = newKey();
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
    if (showDetail) {
      loadDetail(r.id, r.all);
    } else {
      window.scrollTo(0, 0);
    }
  }

  // ---- Timers (stopped while the tab is hidden) --------------------------

  function tick() {
    var now = Date.now();
    if (now - state.runsLastAttemptAt >= REFRESH_MS) refreshRuns();
    if (now - state.healthCheckedAt >= REFRESH_MS) checkHealth();
    if (now - state.statusCheckedAt >= REFRESH_MS) refreshStatus();
    renderRunsStatus(now);
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
    renderRunsStatus(Date.now());
    route();
    if (!document.hidden) startTimers();
  }

  init();
})();
