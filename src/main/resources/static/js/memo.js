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

    /* 삼킨 예외 기록 (2026-09-09) — common.js 가 없으면 no-op.
       account.html·account-memo.html 모두 common.js 를 먼저 싣는다. */
    var TAG = '[Memo]';
    function ignored(where, e) { if (window.Common) window.Common.logIgnored(TAG + ' ' + where, e); }

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

    function pad2(n) { return (n < 10 ? '0' : '') + n; }

    /**
     * 지금 시각을 <b>로컬 기준</b> 'yyyy-MM-ddTHH:mm:ss' 로 — 서버 LocalDateTime 과 같은 규약.
     *
     * ⚠ `new Date().toISOString()` 을 쓰면 안 된다. 그건 **UTC** 라서 `formatTs`(문자열 앞부분만
     * 자르는 함수)로 표시하면 KST 기준 9시간 과거로 보인다 — 서버가 주는 memoUpdatedAt(로컬)과
     * 나란히 놓이는 화면이라 사용자가 어느 시점 메모인지 오판하게 된다.
     */
    Memo.localTs = function () {
        var d = new Date();
        return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
             + 'T' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
    };

    /**
     * 브라우저 보관 시각 표시용. 2026-08-12 이전에 저장된 값은 UTC ISO(`...Z`)라
     * 그대로 자르면 9시간 어긋나므로 로컬로 환산해 보여준다(레거시 호환).
     */
    Memo.formatBackupTs = function (at) {
        if (!at) return '';
        var s = String(at);
        if (/(Z|[+-]\d{2}:?\d{2})$/.test(s)) {
            var d = new Date(s);
            if (!isNaN(d.getTime())) {
                return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
                     + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
            }
        }
        return Memo.formatTs(s);
    };

    /** 에러 객체 → 사용자 표시 메시지 (Common.fetchJSON 은 non-2xx 를 throw + e.body 에 raw) */
    Memo.errorMessage = function (e) {
        if (!e) return '';
        if (e.body) {
            try {
                var j = JSON.parse(e.body);
                if (j && (j.error || j.message)) return j.error || j.message;
            } catch (_) {
                /* JSON 이 아니면(프록시 오류 페이지 등) 원문을 그대로 보여준다 */
                ignored('오류 응답 본문이 JSON 이 아님 — 원문 노출', _);
            }
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
            }).catch(function (e) {
                /* 이탈 중이라 UI 표시가 불가능 — 기록만 남긴다 */
                ignored('이탈 시 메모 저장 요청 실패', e);
            });
        } catch (e) {
            ignored('이탈 시 메모 저장 호출 실패', e);
        }
    };

    // ── 변경 이력 API ──────────────────────────────────────────
    // 서버 memo_history — 브라우저 localStorage 백업(memoBackup:*)과는 별개다.
    // 백업은 "서버에 못 넣은 내용", 이력은 "서버에 저장됐다가 덮인 내용".

    Memo.historyList = function () {
        return Common.fetchJSON('/api/account/memo/history').then(assertSaved);
    };

    Memo.historyGet = function (id) {
        return Common.fetchJSON('/api/account/memo/history/' + encodeURIComponent(id)).then(assertSaved);
    };

    Memo.historyRestore = function (id) {
        return Common.fetchJSON('/api/account/memo/history/' + encodeURIComponent(id) + '/restore',
            { method: 'POST' }).then(assertSaved);
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
            try { listeners[i](msg); }
            catch (e) {
                /* 한 리스너 실패가 나머지를 막지 않도록 격리 */
                if (window.Common) window.Common.logError(TAG + ' 창간 메시지 리스너 실패 — type=' + msg.type, e);
            }
        }
    }

    if (bc) {
        bc.onmessage = function (ev) { dispatch(ev.data); };
    }
    // BroadcastChannel 미지원 브라우저 폴백 — storage 이벤트는 '다른 창'에서만 발생
    window.addEventListener('storage', function (ev) {
        if (ev.key !== LS_KEY || !ev.newValue) return;
        try { dispatch(JSON.parse(ev.newValue)); }
        catch (e) { ignored('storage 폴백 메시지 파싱 실패(손상 값)', e); }
    });

    /**
     * 다른 창으로 상태 전파.
     * type: 'text'(입력) | 'saved'(저장 완료) | 'font' | 'autosave' | 'cleared'(초기화)
     */
    Memo.post = function (type, payload) {
        var msg = { sender: SENDER, type: type, payload: payload };
        if (bc) {
            try { bc.postMessage(msg); return; }
            catch (e) { ignored('BroadcastChannel 전송 실패 — storage 폴백 사용', e); }
        }
        try {
            localStorage.setItem(LS_KEY, JSON.stringify(msg));
            localStorage.removeItem(LS_KEY);   // 다음 동일 값 전송도 이벤트가 발생하도록 정리
        } catch (e) {
            /* 스토리지 불가 환경 — 창간 동기화 없이 각자 동작한다 */
            ignored('storage 폴백 전송 실패(localStorage 차단) — type=' + type, e);
        }
    };

    Memo.onMessage = function (fn) { listeners.push(fn); };

    // ── 초안 인계 (본문 → 새창) ─────────────────────────────────
    // 새창은 서버 저장본을 렌더한다. 본문에 미저장 입력이 남아 있으면 그 값이 최신이므로
    // localStorage 로 넘겨 새창이 이어받는다. 계정 전환 대비로 key 에 username 을 포함.

    function draftKey(username) { return 'memoDraft:' + (username || '_'); }

    Memo.stashDraft = function (username, text) {
        try { localStorage.setItem(draftKey(username), text == null ? '' : text); }
        catch (e) { ignored('초안 인계 저장 실패 — 새 창은 서버 저장본으로 시작한다', e); }
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
                JSON.stringify({ text: text == null ? '' : text, at: Memo.localTs() }));
        } catch (e) {
            /* 용량 초과 등 — 창에는 내용이 남아 있어 치명적이지는 않다 */
            if (window.Common) window.Common.logError(TAG + ' 미저장 메모 백업 실패 — 창을 닫으면 내용이 사라진다', e);
        }
    };

    /** 백업을 읽되 제거하지는 않는다 (복구 여부는 사용자가 선택). {text, at} 또는 null */
    Memo.readBackup = function (username) {
        try {
            var v = localStorage.getItem(backupKey(username));
            return v ? JSON.parse(v) : null;
        } catch (e) { return null; }
    };

    Memo.clearBackup = function (username) {
        try { localStorage.removeItem(backupKey(username)); }
        catch (e) { ignored('백업 삭제 실패(localStorage 차단)', e); }
    };

    // ── 복구 되돌리기 (undo) ────────────────────────────────────
    // 백업 복구는 편집 중인 내용을 통째로 덮어쓴다. 서버에는 메모 이력 테이블이 없어
    // (users.memo 단일 컬럼) 한 번 덮이면 되돌릴 방법이 없으므로, 덮기 직전 스냅샷을
    // 브라우저에 남겨 "복구 이전으로" 되돌릴 수 있게 한다.

    function undoKey(username) { return 'memoUndo:' + (username || '_'); }

    Memo.saveUndo = function (username, text) {
        try {
            localStorage.setItem(undoKey(username),
                JSON.stringify({ text: text == null ? '' : text, at: Memo.localTs() }));
            return true;
        } catch (e) { return false; }   // 용량 초과 등 — 복구 자체는 진행하되 되돌리기만 불가
    };

    /** {text, at} 또는 null. 제거하지 않는다(사용자가 되돌리기를 누를 때까지 유지). */
    Memo.readUndo = function (username) {
        try {
            var v = localStorage.getItem(undoKey(username));
            return v ? JSON.parse(v) : null;
        } catch (e) { return null; }
    };

    Memo.clearUndo = function (username) {
        try { localStorage.removeItem(undoKey(username)); }
        catch (e) { ignored('되돌리기 데이터 삭제 실패(localStorage 차단)', e); }
    };

    // ── 백업 내용 뷰어 (모달) ───────────────────────────────────
    // "저장되지 못한 메모가 남아 있습니다" 안내만으로는 무엇이 복구되는지 알 수 없고,
    // 복구는 편집 중인 내용을 덮어쓰므로 사용자가 눈으로 확인한 뒤 결정할 수 있어야 한다.
    // CSS·DOM 을 스스로 주입하는 싱글턴이라 소비 페이지는 호출 한 줄이면 된다
    // (krds-tooltip.js 와 동일 패턴 — 두 페이지에 마크업을 복붙하면 drift 가 생긴다).

    /** 미리보기 렌더 상한 — 10MB 를 <pre> 에 통째로 넣으면 브라우저가 멈춘다. */
    var PREVIEW_MAX_CHARS = 200000;

    var bv = null;   // 싱글턴 DOM 참조

    function injectViewerCss() {
        if (document.getElementById('memo-bv-css')) return;
        var css = ''
            + '.memo-bv-ov{position:fixed;inset:0;background:rgba(17,24,39,.55);z-index:1200;'
            + 'display:flex;align-items:center;justify-content:center;padding:20px}'
            + '.memo-bv-box{background:#fff;border-radius:12px;width:min(860px,100%);max-height:min(86vh,760px);'
            + 'display:flex;flex-direction:column;box-shadow:0 18px 48px rgba(0,0,0,.25);overflow:hidden}'
            + '.memo-bv-hd{display:flex;align-items:center;gap:10px;padding:14px 18px;border-bottom:1px solid #E5E7EB}'
            + '.memo-bv-hd h3{margin:0;font-size:15px;font-weight:700;color:#111827;flex:1 1 auto}'
            + '.memo-bv-x{border:none;background:none;font-size:20px;line-height:1;color:#9CA3AF;cursor:pointer;padding:2px 6px}'
            + '.memo-bv-x:hover{color:#374151}'
            + '.memo-bv-meta{padding:10px 18px 0;font-size:11.5px;color:#6B7280}'
            + '.memo-bv-tabs{display:flex;gap:6px;padding:10px 18px 0}'
            + '.memo-bv-tabs button{border:1px solid #E5E7EB;background:#F9FAFB;color:#6B7280;'
            + 'padding:6px 12px;border-radius:8px 8px 0 0;font-size:12px;font-weight:600;cursor:pointer}'
            + '.memo-bv-tabs button.on{background:#fff;color:#1D4ED8;border-color:#BFDBFE;border-bottom-color:#fff}'
            + '.memo-bv-tabs .sz{font-weight:400;color:#9CA3AF;margin-left:4px}'
            + '.memo-bv-body{flex:1 1 auto;overflow:auto;margin:0 18px;padding:12px 14px;border:1px solid #E5E7EB;'
            + 'border-radius:0 8px 8px 8px;background:#FAFAFA;font-family:\'D2Coding\',\'JetBrains Mono\',monospace;'
            + 'font-size:12.5px;line-height:1.65;color:#111827;white-space:pre-wrap;word-break:break-word;min-height:120px}'
            + '.memo-bv-body.empty{color:#9CA3AF;font-style:italic}'
            + '.memo-bv-note{padding:10px 18px 0;font-size:11.5px;line-height:1.6;color:#B45309}'
            + '.memo-bv-note.same{color:#6B7280}'
            + '.memo-bv-btns{display:flex;gap:8px;justify-content:flex-end;flex-wrap:wrap;padding:14px 18px}'
            + '.memo-bv-btns button{padding:7px 14px;border-radius:6px;font-size:12px;font-weight:600;cursor:pointer;border:1px solid transparent}'
            + '.memo-bv-btns .bv-discard{background:#fff;border-color:#FCA5A5;color:#B91C1C}'
            + '.memo-bv-btns .bv-close{background:#fff;border-color:#D1D5DB;color:#374151}'
            + '.memo-bv-btns .bv-restore{background:#2563EB;color:#fff}'
            + '.memo-bv-btns button:hover{opacity:.85}'
            + '@media(max-width:640px){.memo-bv-box{max-height:92vh}.memo-bv-btns{justify-content:stretch}'
            + '.memo-bv-btns button{flex:1 1 auto}}';
        var st = document.createElement('style');
        st.id = 'memo-bv-css';
        st.textContent = css;
        document.head.appendChild(st);
    }

    function buildViewer() {
        injectViewerCss();
        var ov = document.createElement('div');
        ov.className = 'memo-bv-ov';
        ov.setAttribute('role', 'dialog');
        ov.setAttribute('aria-modal', 'true');
        ov.setAttribute('aria-label', '저장되지 못한 메모 내용');
        ov.hidden = true;
        ov.innerHTML = ''
            + '<div class="memo-bv-box">'
            +   '<div class="memo-bv-hd"><h3>저장되지 못한 메모</h3>'
            +     '<button type="button" class="memo-bv-x" aria-label="닫기">&times;</button></div>'
            +   '<div class="memo-bv-meta"></div>'
            +   '<div class="memo-bv-tabs">'
            +     '<button type="button" data-tab="backup" class="on">저장 안 된 내용<span class="sz"></span></button>'
            +     '<button type="button" data-tab="current">현재 편집 중<span class="sz"></span></button>'
            +   '</div>'
            +   '<pre class="memo-bv-body"></pre>'
            +   '<div class="memo-bv-note"></div>'
            +   '<div class="memo-bv-btns">'
            +     '<button type="button" class="bv-discard">버리기</button>'
            +     '<button type="button" class="bv-close">닫기</button>'
            +     '<button type="button" class="bv-restore">이 내용으로 복구</button>'
            +   '</div>'
            + '</div>';
        document.body.appendChild(ov);

        var api = {
            ov: ov,
            meta: ov.querySelector('.memo-bv-meta'),
            body: ov.querySelector('.memo-bv-body'),
            note: ov.querySelector('.memo-bv-note'),
            tabs: ov.querySelectorAll('.memo-bv-tabs button'),
            texts: { backup: '', current: '' },
            cfg: {}
        };

        function close() {
            ov.hidden = true;
            document.removeEventListener('keydown', onKey);
        }
        function onKey(ev) { if (ev.key === 'Escape') close(); }
        api.close = close;
        api.bindEsc = function () { document.addEventListener('keydown', onKey); };

        function showTab(name) {
            for (var i = 0; i < api.tabs.length; i++) {
                api.tabs[i].classList.toggle('on', api.tabs[i].getAttribute('data-tab') === name);
            }
            var t = api.texts[name] || '';
            var truncated = t.length > PREVIEW_MAX_CHARS;
            api.body.textContent = truncated
                ? t.substring(0, PREVIEW_MAX_CHARS) + '\n\n… (이하 생략 — 복구하면 전체가 들어갑니다)'
                : (t === '' ? '(내용 없음)' : t);
            api.body.classList.toggle('empty', t === '');
            api.body.scrollTop = 0;
        }
        api.showTab = showTab;

        for (var i = 0; i < api.tabs.length; i++) {
            api.tabs[i].addEventListener('click', function () {
                showTab(this.getAttribute('data-tab'));
            });
        }
        ov.querySelector('.memo-bv-x').addEventListener('click', close);
        ov.querySelector('.bv-close').addEventListener('click', close);
        ov.querySelector('.bv-discard').addEventListener('click', function () {
            close();
            if (api.cfg.onDiscard) api.cfg.onDiscard();
        });
        ov.querySelector('.bv-restore').addEventListener('click', function () {
            close();
            if (api.cfg.onRestore) api.cfg.onRestore();
        });
        ov.addEventListener('click', function (ev) { if (ev.target === ov) close(); });

        return api;
    }

    /**
     * 백업 내용 미리보기 모달.
     * cfg = { backup: {text, at}, current: string, onRestore?: fn, onDiscard?: fn }
     * 내용은 textContent 로만 넣는다(사용자 입력이 그대로 들어오므로 innerHTML 금지).
     */
    Memo.openBackupViewer = function (cfg) {
        if (!cfg || !cfg.backup) return;
        if (!bv) bv = buildViewer();
        bv.cfg = cfg;

        var bText = typeof cfg.backup.text === 'string' ? cfg.backup.text : '';
        var cText = typeof cfg.current === 'string' ? cfg.current : '';
        bv.texts.backup = bText;
        bv.texts.current = cText;

        bv.meta.textContent = '브라우저 임시 보관: ' + (Memo.formatBackupTs(cfg.backup.at) || '시각 미상')
            + ' · ' + Memo.fmtBytes(Memo.utf8ByteLen(bText));
        bv.tabs[0].querySelector('.sz').textContent = ' (' + Memo.fmtBytes(Memo.utf8ByteLen(bText)) + ')';
        bv.tabs[1].querySelector('.sz').textContent = ' (' + Memo.fmtBytes(Memo.utf8ByteLen(cText)) + ')';

        var same = bText === cText;
        bv.note.classList.toggle('same', same);
        bv.note.textContent = same
            ? '현재 편집 중인 내용과 동일합니다 — 복구해도 달라지는 것이 없습니다.'
            : '⚠ 복구하면 현재 편집 중인 내용이 이 내용으로 바뀝니다. 바꾼 뒤에도 [복구 이전으로 되돌리기] 로 취소할 수 있습니다.';

        bv.showTab('backup');
        bv.ov.hidden = false;
        bv.bindEsc();
        bv.ov.querySelector('.bv-restore').focus();
    };

    // ── 변경 이력 뷰어 (모달) ───────────────────────────────────
    // 서버 memo_history 목록 + 미리보기 + 복원. 백업 뷰어와 같은 싱글턴 주입 패턴이라
    // 소비 페이지는 Memo.openHistoryViewer(cfg) 한 줄이면 된다.

    var REASON_LABEL = { save: '저장 전', restore: '복원 전', clear: '초기화 전' };

    var hv = null;

    function injectHistoryCss() {
        if (document.getElementById('memo-hv-css')) return;
        var css = ''
            + '.memo-hv-ov{position:fixed;inset:0;background:rgba(17,24,39,.55);z-index:1200;'
            + 'display:flex;align-items:center;justify-content:center;padding:20px}'
            + '.memo-hv-box{background:#fff;border-radius:12px;width:min(960px,100%);max-height:min(88vh,780px);'
            + 'display:flex;flex-direction:column;box-shadow:0 18px 48px rgba(0,0,0,.25);overflow:hidden}'
            + '.memo-hv-hd{display:flex;align-items:center;gap:10px;padding:14px 18px;border-bottom:1px solid #E5E7EB}'
            + '.memo-hv-hd h3{margin:0;font-size:15px;font-weight:700;color:#111827;flex:1 1 auto}'
            + '.memo-hv-x{border:none;background:none;font-size:20px;line-height:1;color:#9CA3AF;cursor:pointer;padding:2px 6px}'
            + '.memo-hv-x:hover{color:#374151}'
            + '.memo-hv-note{padding:9px 18px 0;font-size:11.5px;color:#6B7280;line-height:1.6}'
            + '.memo-hv-main{flex:1 1 auto;display:flex;gap:12px;padding:12px 18px;min-height:0}'
            + '.memo-hv-list{flex:0 0 290px;overflow:auto;border:1px solid #E5E7EB;border-radius:8px;background:#FCFCFD}'
            + '.memo-hv-item{padding:9px 11px;border-bottom:1px solid #F3F4F6;cursor:pointer}'
            + '.memo-hv-item:hover{background:#F9FAFB}'
            + '.memo-hv-item.on{background:#EFF6FF;box-shadow:inset 3px 0 0 #2563EB}'
            + '.memo-hv-item .t{display:flex;align-items:center;gap:6px;font-size:11.5px;color:#374151;font-weight:600}'
            + '.memo-hv-item .g{margin-left:auto;font-weight:400;color:#9CA3AF}'
            + '.memo-hv-item .p{margin-top:3px;font-size:11px;color:#9CA3AF;overflow:hidden;'
            + 'text-overflow:ellipsis;white-space:nowrap}'
            + '.memo-hv-badge{display:inline-block;padding:1px 6px;border-radius:8px;font-size:10px;font-weight:700}'
            + '.memo-hv-badge.save{background:#E0E7FF;color:#3730A3}'
            + '.memo-hv-badge.restore{background:#FEF3C7;color:#92400E}'
            + '.memo-hv-badge.clear{background:#FEE2E2;color:#991B1B}'
            + '.memo-hv-body{flex:1 1 auto;overflow:auto;padding:12px 14px;border:1px solid #E5E7EB;border-radius:8px;'
            + 'background:#FAFAFA;font-family:\'D2Coding\',\'JetBrains Mono\',monospace;font-size:12.5px;'
            + 'line-height:1.65;color:#111827;white-space:pre-wrap;word-break:break-word;margin:0}'
            + '.memo-hv-body.empty{color:#9CA3AF;font-style:italic}'
            + '.memo-hv-btns{display:flex;gap:8px;justify-content:flex-end;flex-wrap:wrap;padding:12px 18px;'
            + 'border-top:1px solid #F3F4F6}'
            + '.memo-hv-btns button{padding:7px 14px;border-radius:6px;font-size:12px;font-weight:600;'
            + 'cursor:pointer;border:1px solid transparent}'
            + '.memo-hv-btns .hv-close{background:#fff;border-color:#D1D5DB;color:#374151}'
            + '.memo-hv-btns .hv-restore{background:#2563EB;color:#fff}'
            + '.memo-hv-btns .hv-restore:disabled{background:#93C5FD;cursor:not-allowed}'
            + '.memo-hv-btns button:hover:not(:disabled){opacity:.85}'
            + '@media(max-width:760px){.memo-hv-main{flex-direction:column}'
            + '.memo-hv-list{flex:0 0 34%;min-height:120px}.memo-hv-btns{justify-content:stretch}'
            + '.memo-hv-btns button{flex:1 1 auto}}';
        var st = document.createElement('style');
        st.id = 'memo-hv-css';
        st.textContent = css;
        document.head.appendChild(st);
    }

    function buildHistoryViewer() {
        injectHistoryCss();
        var ov = document.createElement('div');
        ov.className = 'memo-hv-ov';
        ov.setAttribute('role', 'dialog');
        ov.setAttribute('aria-modal', 'true');
        ov.setAttribute('aria-label', '메모 변경 이력');
        ov.hidden = true;
        ov.innerHTML = ''
            + '<div class="memo-hv-box">'
            +   '<div class="memo-hv-hd"><h3>메모 변경 이력</h3>'
            +     '<button type="button" class="memo-hv-x" aria-label="닫기">&times;</button></div>'
            +   '<div class="memo-hv-note"></div>'
            +   '<div class="memo-hv-main">'
            +     '<div class="memo-hv-list"></div>'
            +     '<pre class="memo-hv-body"></pre>'
            +   '</div>'
            +   '<div class="memo-hv-btns">'
            +     '<button type="button" class="hv-close">닫기</button>'
            +     '<button type="button" class="hv-restore" disabled>이 시점으로 복원</button>'
            +   '</div>'
            + '</div>';
        document.body.appendChild(ov);

        var api = {
            ov: ov,
            note: ov.querySelector('.memo-hv-note'),
            list: ov.querySelector('.memo-hv-list'),
            body: ov.querySelector('.memo-hv-body'),
            restoreBtn: ov.querySelector('.hv-restore'),
            selectedId: null,
            cfg: {}
        };

        function close() {
            ov.hidden = true;
            document.removeEventListener('keydown', onKey);
        }
        function onKey(ev) { if (ev.key === 'Escape') close(); }
        api.close = close;
        api.bindEsc = function () { document.addEventListener('keydown', onKey); };

        ov.querySelector('.memo-hv-x').addEventListener('click', close);
        ov.querySelector('.hv-close').addEventListener('click', close);
        ov.addEventListener('click', function (ev) { if (ev.target === ov) close(); });

        api.restoreBtn.addEventListener('click', function () {
            if (api.selectedId == null) return;
            var id = api.selectedId;
            api.restoreBtn.disabled = true;
            Memo.historyRestore(id).then(function (d) {
                close();
                if (api.cfg.onRestored) api.cfg.onRestored(d);
            }).catch(function (e) {
                api.restoreBtn.disabled = false;
                if (api.cfg.onError) api.cfg.onError(e);
            });
        });

        return api;
    }

    /** 목록 1건 렌더 — 사용자 입력(preview)은 textContent 로만 넣는다. */
    function renderHistoryRow(item) {
        var row = document.createElement('div');
        row.className = 'memo-hv-item';
        row.setAttribute('data-id', item.id);

        var t = document.createElement('div');
        t.className = 't';
        var badge = document.createElement('span');
        badge.className = 'memo-hv-badge ' + (REASON_LABEL[item.reason] ? item.reason : 'save');
        badge.textContent = REASON_LABEL[item.reason] || '저장 전';
        var when = document.createElement('span');
        when.textContent = Memo.formatBackupTs(item.createdAt) || '';
        var size = document.createElement('span');
        size.className = 'g';
        size.textContent = Memo.fmtBytes(item.byteSize || 0);
        t.appendChild(badge);
        t.appendChild(when);
        t.appendChild(size);

        var p = document.createElement('div');
        p.className = 'p';
        p.textContent = item.preview || '(내용 없음)';

        row.appendChild(t);
        row.appendChild(p);
        return row;
    }

    /**
     * 변경 이력 모달.
     * cfg = { onRestored?: fn(data), onError?: fn(err) }
     */
    Memo.openHistoryViewer = function (cfg) {
        if (!hv) hv = buildHistoryViewer();
        hv.cfg = cfg || {};
        hv.selectedId = null;
        hv.restoreBtn.disabled = true;
        hv.list.textContent = '';
        hv.body.textContent = '불러오는 중…';
        hv.body.classList.add('empty');
        hv.note.textContent = '';
        hv.ov.hidden = false;
        hv.bindEsc();

        Memo.historyList().then(function (d) {
            var items = d.items || [];
            hv.note.textContent = d.enabled === false
                ? '변경 이력 기록이 비활성화되어 있습니다 (관리자 설정).'
                : ('저장 직전 내용을 최근 ' + (d.retentionDays || 7) + '일 · 최대 '
                   + (d.maxPerUser || 100) + '건까지 보관합니다. 항목을 고르면 내용을 확인할 수 있습니다.');

            if (!items.length) {
                hv.body.textContent = '보관된 이력이 없습니다.';
                hv.body.classList.add('empty');
                return;
            }
            hv.body.textContent = '왼쪽 목록에서 시점을 선택하세요.';
            hv.body.classList.add('empty');

            items.forEach(function (item) {
                var row = renderHistoryRow(item);
                row.addEventListener('click', function () {
                    var rows = hv.list.querySelectorAll('.memo-hv-item');
                    for (var i = 0; i < rows.length; i++) rows[i].classList.remove('on');
                    row.classList.add('on');
                    hv.selectedId = item.id;
                    hv.restoreBtn.disabled = true;
                    hv.body.textContent = '불러오는 중…';
                    hv.body.classList.add('empty');
                    Memo.historyGet(item.id).then(function (detail) {
                        if (hv.selectedId !== item.id) return;   // 빠른 연속 클릭 — 마지막 선택만 반영
                        var text = detail.memo || '';
                        hv.body.textContent = text === '' ? '(내용 없음)' : text;
                        hv.body.classList.toggle('empty', text === '');
                        hv.body.scrollTop = 0;
                        hv.restoreBtn.disabled = false;
                    }).catch(function (e) {
                        hv.body.textContent = '내용을 불러오지 못했습니다: ' + Memo.errorMessage(e);
                        hv.body.classList.add('empty');
                        if (hv.cfg.onError) hv.cfg.onError(e);
                    });
                });
                hv.list.appendChild(row);
            });
        }).catch(function (e) {
            hv.body.textContent = '이력을 불러오지 못했습니다: ' + Memo.errorMessage(e);
            hv.body.classList.add('empty');
            if (hv.cfg.onError) hv.cfg.onError(e);
        });
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
