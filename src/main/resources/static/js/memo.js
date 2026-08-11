/**
 * memo.js — 개인 메모장 공용 모듈 (window.Memo)
 *
 * 소비 페이지: /account (본문 카드) · /account/memo (새창 전용 자립형 페이지)
 * 두 창은 서로의 JS 를 호출하지 않는다. 각자 자기 컨텍스트에서 /api/account/memo* 를
 * 직접 호출하고, 화면 동기화만 BroadcastChannel(폴백: localStorage storage 이벤트)로 주고받는다.
 * → 부모 창이 다른 페이지로 이동하거나 닫혀도 새창의 저장이 계속 동작한다.
 *
 * banner.html 전역 로드가 아니라 소비 페이지가 <script> 로 직접 로드한다.
 */
(function () {
    'use strict';

    var Memo = {};
    window.Memo = Memo;

    /** 저장 한도 — UserService.MEMO_MAX_BYTES 미러 */
    Memo.MAX = 10 * 1024 * 1024;

    /** 폰트 select value → CSS font-family (UserService.ALLOWED_MEMO_FONTS 와 키 일치) */
    Memo.FONTS = {
        'd2coding': "'D2Coding', monospace",
        'jb+nanum': "'JetBrains Mono', 'Nanum Gothic Coding', Consolas, monospace",
        'nanum':    "'Nanum Gothic Coding', monospace",
        'system':   "ui-monospace, monospace"
    };

    Memo.fontFamily = function (val) {
        return Memo.FONTS[val] || Memo.FONTS['d2coding'];
    };

    /** 서버 검증(UTF-8 byte)과 같은 기준으로 클라이언트에서 미리 계산 */
    Memo.utf8ByteLen = function (str) {
        return new Blob([str == null ? '' : str]).size;
    };

    Memo.fmtBytes = function (n) {
        if (n < 1024) return n.toLocaleString() + ' B';
        if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
        return (n / (1024 * 1024)).toFixed(2) + ' MB';
    };

    /** 서버 LocalDateTime(ISO8601) → 'yyyy-MM-dd HH:mm' (분 단위까지만 표시) */
    Memo.formatTs = function (iso) {
        if (!iso) return '';
        var s = String(iso);
        if (s.length >= 16) return s.substring(0, 10) + ' ' + s.substring(11, 16);
        return s;
    };

    /** 에러 객체 → 사용자 표시 메시지 (Common.fetchJSON 은 non-2xx 를 throw + e.body 에 raw) */
    Memo.errorMessage = function (e) {
        if (!e) return '';
        if (e.body) {
            try {
                var j = JSON.parse(e.body);
                if (j && (j.error || j.message)) return j.error || j.message;
            } catch (_) { /* raw text */ }
            return e.body;
        }
        return e.message || '';
    };

    // ── 세션 만료 감지 ─────────────────────────────────────────

    /**
     * 세션 만료/로그아웃으로 인한 실패인가.
     * 서버(/api/**)는 401 + code=SESSION_EXPIRED 로 응답한다. 추가로 `sessionExpired` 플래그
     * (아래 assertSaved 가 붙이는 형태 불일치 방어)도 함께 본다.
     */
    Memo.isSessionExpired = function (e) {
        if (!e) return false;
        if (e.sessionExpired) return true;
        if (e.status === 401) return true;
        if (e.body && String(e.body).indexOf('SESSION_EXPIRED') >= 0) return true;
        return false;
    };

    function sessionExpiredError() {
        var err = new Error('로그인 세션이 만료되었습니다. 다시 로그인해 주세요.');
        err.sessionExpired = true;
        return err;
    }

    /**
     * 정상 응답은 항상 {success:true, ...} 객체다.
     * 그렇지 않으면(예: 인증 리다이렉트를 fetch 가 추종해 로그인 페이지 HTML 을 200 으로 받은 경우)
     * 저장된 것으로 오인하지 않고 세션 만료로 처리한다 — 서버 401 응답에 대한 2중 방어.
     */
    function assertSaved(d) {
        if (!d || typeof d !== 'object' || d.success !== true) throw sessionExpiredError();
        return d;
    }

    /**
     * 재로그인 후 현재 세션의 CSRF 토큰을 다시 받아 <meta name="_csrf"> 를 갱신한다.
     * 새 세션은 새 토큰을 쓰므로 이 갱신 없이는 재로그인해도 POST 가 계속 거부된다.
     * @returns Promise<boolean> — true 면 인증 상태 복구됨
     */
    Memo.refreshCsrf = function () {
        return fetch('/api/csrf', { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (d) {
                if (!d || d.success !== true || !d.token) return false;
                var meta = document.querySelector('meta[name="_csrf"]');
                var metaHeader = document.querySelector('meta[name="_csrf_header"]');
                if (meta) meta.setAttribute('content', d.token);
                if (metaHeader && d.headerName) metaHeader.setAttribute('content', d.headerName);
                return true;
            })
            .catch(function () { return false; });
    };

    // ── 서버 호출 ──────────────────────────────────────────────

    /** 메모 저장. 한도 초과는 서버 왕복 전에 차단. */
    Memo.save = function (text) {
        var v = text == null ? '' : text;
        if (Memo.utf8ByteLen(v) > Memo.MAX) {
            return Promise.reject(new Error('메모는 최대 10MB까지 저장할 수 있습니다.'));
        }
        return Common.fetchJSON('/api/account/memo', {
            method: 'POST',
            body: JSON.stringify({ memo: v })
        }).then(assertSaved);
    };

    /**
     * 창을 닫거나 페이지를 떠나는 순간의 마지막 저장.
     * navigator.sendBeacon 은 커스텀 헤더를 못 붙여 CSRF 토큰 전달이 불가하고
     * (/api/account/** 는 SecurityConfig 에서 CSRF 보호 유지 대상),
     * 일반 fetch 는 문서 폐기와 함께 취소될 수 있어 keepalive 를 쓴다.
     */
    Memo.saveKeepalive = function (text) {
        var v = text == null ? '' : text;
        if (Memo.utf8ByteLen(v) > Memo.MAX) return;
        try {
            var headers = { 'Content-Type': 'application/json' };
            var token = Common.csrfToken();
            if (token) headers[Common.csrfHeaderName()] = token;
            fetch('/api/account/memo', {
                method: 'POST',
                headers: headers,
                credentials: 'same-origin',
                keepalive: true,
                body: JSON.stringify({ memo: v })
            }).catch(function () { /* 이탈 중이라 UI 표시 불가 — 조용히 무시 */ });
        } catch (e) { /* 동일 */ }
    };

    Memo.setFont = function (val) {
        return Common.fetchJSON('/api/account/memo-font', {
            method: 'POST', body: JSON.stringify({ font: val })
        });
    };

    Memo.setAutosave = function (on) {
        return Common.fetchJSON('/api/account/memo-autosave', {
            method: 'POST', body: JSON.stringify({ autosave: !!on })
        });
    };

    // ── 창 간 동기화 ───────────────────────────────────────────

    var CHANNEL = 'heap-memo-sync';
    var LS_KEY  = 'memoSyncMsg';
    // 자기 자신이 보낸 메시지를 되받지 않기 위한 창 식별자 (localStorage 폴백 경로 방어)
    var SENDER  = 'w' + Math.random().toString(36).slice(2) + Date.now();
    var bc = null;
    var listeners = [];

    try {
        if (typeof BroadcastChannel === 'function') bc = new BroadcastChannel(CHANNEL);
    } catch (e) { bc = null; }

    function dispatch(msg) {
        if (!msg || msg.sender === SENDER) return;
        for (var i = 0; i < listeners.length; i++) {
            try { listeners[i](msg); } catch (e) { /* 한 리스너 실패가 나머지를 막지 않도록 */ }
        }
    }

    if (bc) {
        bc.onmessage = function (ev) { dispatch(ev.data); };
    }
    // BroadcastChannel 미지원 브라우저 폴백 — storage 이벤트는 '다른 창'에서만 발생
    window.addEventListener('storage', function (ev) {
        if (ev.key !== LS_KEY || !ev.newValue) return;
        try { dispatch(JSON.parse(ev.newValue)); } catch (e) { /* 손상 값 무시 */ }
    });

    /**
     * 다른 창으로 상태 전파.
     * type: 'text'(입력) | 'saved'(저장 완료) | 'font' | 'autosave' | 'cleared'(초기화)
     */
    Memo.post = function (type, payload) {
        var msg = { sender: SENDER, type: type, payload: payload };
        if (bc) {
            try { bc.postMessage(msg); return; } catch (e) { /* 폴백으로 */ }
        }
        try {
            localStorage.setItem(LS_KEY, JSON.stringify(msg));
            localStorage.removeItem(LS_KEY);   // 다음 동일 값 전송도 이벤트가 발생하도록 정리
        } catch (e) { /* 스토리지 불가 환경 — 동기화 없이 각자 동작 */ }
    };

    Memo.onMessage = function (fn) { listeners.push(fn); };

    // ── 초안 인계 (본문 → 새창) ─────────────────────────────────
    // 새창은 서버 저장본을 렌더한다. 본문에 미저장 입력이 남아 있으면 그 값이 최신이므로
    // localStorage 로 넘겨 새창이 이어받는다. 계정 전환 대비로 key 에 username 을 포함.

    function draftKey(username) { return 'memoDraft:' + (username || '_'); }

    Memo.stashDraft = function (username, text) {
        try { localStorage.setItem(draftKey(username), text == null ? '' : text); }
        catch (e) { /* 저장 실패 시 새창은 서버 저장본으로 시작 */ }
    };

    /** 초안을 읽고 즉시 제거 (1회성 인계). 없으면 null. */
    Memo.takeDraft = function (username) {
        try {
            var k = draftKey(username);
            var v = localStorage.getItem(k);
            localStorage.removeItem(k);
            return v;
        } catch (e) { return null; }
    };

    // ── 저장 실패 백업 (세션 만료 등) ───────────────────────────
    // 초안(draft)은 창 사이 인계용 1회성, 백업(backup)은 "서버에 못 넣은 내용"의 안전망이다.
    // 창을 그대로 닫아버려도 다음 방문에서 복구할 수 있게 별도 키로 보관한다.

    function backupKey(username) { return 'memoBackup:' + (username || '_'); }

    Memo.backup = function (username, text) {
        try {
            localStorage.setItem(backupKey(username),
                JSON.stringify({ text: text == null ? '' : text, at: new Date().toISOString() }));
        } catch (e) { /* 용량 초과 등 — 창에는 내용이 남아 있으므로 치명적이지 않음 */ }
    };

    /** 백업을 읽되 제거하지는 않는다 (복구 여부는 사용자가 선택). {text, at} 또는 null */
    Memo.readBackup = function (username) {
        try {
            var v = localStorage.getItem(backupKey(username));
            return v ? JSON.parse(v) : null;
        } catch (e) { return null; }
    };

    Memo.clearBackup = function (username) {
        try { localStorage.removeItem(backupKey(username)); } catch (e) { /* 무시 */ }
    };

    // ── 자동 저장 ──────────────────────────────────────────────

    /** 자동 저장 debounce 지연 (ms) — 마지막 입력 후 이만큼 조용해지면 1회 저장 */
    Memo.AUTOSAVE_DELAY_MS = 5000;

    /**
     * '자동 저장' 인포 아이콘 툴팁 문구. AUTOSAVE_DELAY_MS 에서 만들어 두 페이지(account·account-memo)가
     * 같은 문구를 쓰고, 지연 값을 바꿔도 안내가 따라오게 한다(문구 하드코딩 시 조용히 어긋남).
     */
    Memo.autosaveTipText = function () {
        var ms = Memo.AUTOSAVE_DELAY_MS;
        var sec = (ms % 1000 === 0) ? String(ms / 1000) : (ms / 1000).toFixed(1);
        return '입력을 멈추면 ' + sec + '초 뒤에 자동으로 저장됩니다.\n\n'
             + '마지막 입력에서 ' + sec + '초 동안 입력이 없을 때 1회 저장하므로, '
             + '계속 타이핑하는 중에는 저장되지 않습니다.\n\n'
             + '끄면 자동 저장이 멈추고 [저장] 버튼을 눌러야 저장됩니다.';
    };

    /**
     * debounce 자동 저장기.
     * cfg = { getValue(), isEnabled(), delay?, onStart?, onSaved(data)?, onError(e)?, onSessionExpired(e)? }
     * - 저장 중 새 입력이 들어오면 완료 후 한 번 더 저장 (마지막 값 보장)
     * - 실패해도 타이머만 해제 — 다음 입력이나 flush() 에서 재시도
     * - 세션 만료로 실패하면 **스스로 정지**한다. 재로그인 전까지는 재시도해도 계속 실패하므로
     *   무의미한 반복 요청·반복 경고를 막고, 복구는 resume() 호출자(재시도 UI)에 맡긴다.
     */
    Memo.createAutosaver = function (cfg) {
        var timer = null, inflight = false, again = false, suspended = false;

        function run() {
            timer = null;
            if (suspended || !cfg.isEnabled()) return;
            if (inflight) { again = true; return; }
            inflight = true;
            if (cfg.onStart) cfg.onStart();
            Memo.save(cfg.getValue()).then(function (d) {
                if (cfg.onSaved) cfg.onSaved(d);
            }).catch(function (e) {
                if (Memo.isSessionExpired(e)) {
                    suspended = true;
                    again = false;
                    if (cfg.onSessionExpired) cfg.onSessionExpired(e);
                } else if (cfg.onError) {
                    cfg.onError(e);
                }
            }).finally(function () {
                inflight = false;
                if (again) { again = false; run(); }
            });
        }

        return {
            /** 입력 시 호출 — 마지막 입력 후 delay 만큼 조용해지면 저장 */
            schedule: function () {
                if (suspended || !cfg.isEnabled()) return;
                if (timer) clearTimeout(timer);
                timer = setTimeout(run, cfg.delay || Memo.AUTOSAVE_DELAY_MS);
            },
            /** 대기 중인 저장을 즉시 실행 (토글 ON 전환·수동 저장 직전 등) */
            flush: function () {
                if (timer) { clearTimeout(timer); timer = null; run(); }
            },
            cancel: function () {
                if (timer) { clearTimeout(timer); timer = null; }
            },
            /** 대기 중인 자동 저장이 있는가 (이탈 시 keepalive 저장 판단용) */
            pending: function () { return timer !== null || inflight; },
            suspend: function () { suspended = true; if (timer) { clearTimeout(timer); timer = null; } },
            resume:  function () { suspended = false; },
            isSuspended: function () { return suspended; }
        };
    };
})();
