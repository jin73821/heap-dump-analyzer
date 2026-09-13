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

    /** 저장 시점 개별 삭제 → {success, deleted:1, remaining}. 현재 메모에는 영향 없음. */
    Memo.historyDelete = function (id) {
        return Common.fetchJSON('/api/account/memo/history/' + encodeURIComponent(id),
            { method: 'DELETE' }).then(assertSaved);
    };

    /** 본인 이력 전체 삭제 → {success, deleted, remaining:0} */
    Memo.historyClear = function () {
        return Common.fetchJSON('/api/account/memo/history', { method: 'DELETE' }).then(assertSaved);
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

    // ── 줄 단위 비교 (과거 시점 → 현재 메모) ─────────────────────
    // 이력 창의 '변경점 표시'. 공통 앞/뒤 줄을 먼저 떼어내고(메모 수정은 대개 일부분이라 이것만으로
    // 대부분 끝난다) 가운데만 Myers O(ND) 로 비교한다. 10MB 메모도 받아야 하므로 편집 거리·시간에
    // 상한을 두고, 넘으면 가운데를 '통째 삭제 + 통째 추가'로 표시한다(정확하지만 최소는 아닌 비교).

    /** 비교 편집 거리 상한 — trace 메모리가 D² 에 비례한다(2000 → 약 16MB). */
    var DIFF_MAX_EDITS = 2000;
    /** 비교 시간 상한 (ms) — 넘으면 간략 비교로 후퇴해 모달이 멈추지 않게 한다. */
    var DIFF_MAX_MS = 1500;

    function splitLines(text) {
        if (text == null || text === '') return [];
        return String(text).replace(/\r\n?/g, '\n').split('\n');
    }

    /** A→B 최소 편집 스크립트. 상한 초과 시 null. */
    function myersLines(A, B) {
        var N = A.length, M = B.length;
        if (N + M === 0) return [];
        var limit = Math.min(N + M, DIFF_MAX_EDITS);
        var off = limit + 1;
        var v = new Int32Array(2 * limit + 3);
        var trace = [];
        var t0 = Date.now();
        for (var d = 0; d <= limit; d++) {
            trace.push({ base: -d - 1, arr: v.slice(off - d - 1, off + d + 2) });
            if ((d & 63) === 63 && Date.now() - t0 > DIFF_MAX_MS) return null;
            for (var k = -d; k <= d; k += 2) {
                var x = (k === -d || (k !== d && v[off + k - 1] < v[off + k + 1])) ? v[off + k + 1] : v[off + k - 1] + 1;
                var y = x - k;
                while (x < N && y < M && A[x] === B[y]) { x++; y++; }
                v[off + k] = x;
                if (x >= N && y >= M) return backtrackMyers(trace, d, A, B);
            }
        }
        return null;
    }

    function backtrackMyers(trace, D, A, B) {
        var out = [];
        var x = A.length, y = B.length;
        for (var d = D; d >= 0; d--) {
            var snap = trace[d];
            var k = x - y;
            var pk = (k === -d || (k !== d && snap.arr[k - 1 - snap.base] < snap.arr[k + 1 - snap.base])) ? k + 1 : k - 1;
            var px = snap.arr[pk - snap.base];
            var py = px - pk;
            while (x > px && y > py) { out.push({ t: 'eq', s: A[x - 1] }); x--; y--; }
            if (d > 0) {
                if (x === px) out.push({ t: 'add', s: B[y - 1] });
                else out.push({ t: 'del', s: A[x - 1] });
            }
            x = px; y = py;
        }
        return out.reverse();
    }

    /**
     * 줄 단위 비교. before = 과거 시점, after = 현재 메모.
     * @returns {ops:[{t:'eq'|'del'|'add', s}], added, removed, approximate}
     *   del = 과거 시점에만 있는 줄(이후 지워짐), add = 현재에만 있는 줄(이후 추가됨)
     */
    Memo.diffLines = function (before, after) {
        var a = splitLines(before), b = splitLines(after);
        var n = a.length, m = b.length, pre = 0, suf = 0, i;
        while (pre < n && pre < m && a[pre] === b[pre]) pre++;
        while (suf < n - pre && suf < m - pre && a[n - 1 - suf] === b[m - 1 - suf]) suf++;
        var A = a.slice(pre, n - suf), B = b.slice(pre, m - suf);

        var mid = myersLines(A, B), approximate = false;
        if (mid === null) {
            approximate = true;
            mid = [];
            for (i = 0; i < A.length; i++) mid.push({ t: 'del', s: A[i] });
            for (i = 0; i < B.length; i++) mid.push({ t: 'add', s: B[i] });
        }
        var ops = [], added = 0, removed = 0;
        for (i = 0; i < pre; i++) ops.push({ t: 'eq', s: a[i] });
        for (i = 0; i < mid.length; i++) {
            ops.push(mid[i]);
            if (mid[i].t === 'add') added++;
            else if (mid[i].t === 'del') removed++;
        }
        for (i = n - suf; i < n; i++) ops.push({ t: 'eq', s: a[i] });
        return { ops: ops, added: added, removed: removed, approximate: approximate };
    };

    // ── 변경 이력 뷰어 (모달) ───────────────────────────────────
    // 서버 memo_history 목록 + 미리보기 + 복원 + 삭제(개별·전체) + 현재 메모 대비 변경점 표시.
    // 백업 뷰어와 같은 싱글턴 주입 패턴이라 소비 페이지는 Memo.openHistoryViewer(cfg) 한 줄이면 된다.

    var REASON_LABEL = { save: '저장 전', restore: '복원 전', clear: '초기화 전' };

    /** 변경점 표시 렌더 상한 (줄) — 넘는 부분은 생략 안내 */
    var DIFF_RENDER_MAX_LINES = 20000;
    /** 변경점 표시 on/off 기억 (브라우저별 편의 설정) */
    var DIFF_PREF_KEY = 'memoHistoryDiff';

    var TRASH_SVG = '<svg viewBox="0 0 16 16" width="13" height="13" aria-hidden="true" fill="none" stroke="currentColor" '
        + 'stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M2.5 4h11M6 4V2.5h4V4M4 4l.7 9.5h6.6L12 4"/></svg>';

    var hv = null;

    function readDiffPref() {
        try { return localStorage.getItem(DIFF_PREF_KEY) !== '0'; }   // 기본 켜짐
        catch (e) { return true; }
    }
    function writeDiffPref(on) {
        try { localStorage.setItem(DIFF_PREF_KEY, on ? '1' : '0'); }
        catch (e) { ignored('변경점 표시 설정 저장 실패(localStorage 차단)', e); }
    }

    function injectHistoryCss() {
        if (document.getElementById('memo-hv-css')) return;
        var css = ''
            + '.memo-hv-ov{position:fixed;inset:0;background:rgba(17,24,39,.55);z-index:1200;'
            + 'display:flex;align-items:center;justify-content:center;padding:20px}'
            + '.memo-hv-box{background:#fff;border-radius:12px;width:min(1040px,100%);max-height:min(88vh,800px);'
            + 'display:flex;flex-direction:column;box-shadow:0 18px 48px rgba(0,0,0,.25);overflow:hidden}'
            + '.memo-hv-hd{display:flex;align-items:center;gap:10px;padding:14px 18px;border-bottom:1px solid #E5E7EB}'
            + '.memo-hv-hd h3{margin:0;font-size:15px;font-weight:700;color:#111827;flex:1 1 auto}'
            + '.memo-hv-x{border:none;background:none;font-size:20px;line-height:1;color:#9CA3AF;cursor:pointer;padding:2px 6px}'
            + '.memo-hv-x:hover{color:#374151}'
            + '.memo-hv-note{padding:9px 18px 0;font-size:11.5px;color:#6B7280;line-height:1.6}'
            + '.memo-hv-main{flex:1 1 auto;display:flex;gap:12px;padding:12px 18px;min-height:0}'
            + '.memo-hv-list{flex:0 0 290px;overflow:auto;border:1px solid #E5E7EB;border-radius:8px;background:#FCFCFD}'
            + '.memo-hv-item{padding:9px 11px;border-bottom:1px solid #F3F4F6;cursor:pointer;outline:none}'
            + '.memo-hv-item:hover{background:#F9FAFB}'
            + '.memo-hv-item:focus-visible{box-shadow:inset 0 0 0 2px #93C5FD}'
            + '.memo-hv-item.on{background:#EFF6FF;box-shadow:inset 3px 0 0 #2563EB}'
            + '.memo-hv-item.pending-del{background:#FEF2F2;box-shadow:inset 3px 0 0 #DC2626}'
            + '.memo-hv-item .t{display:flex;align-items:center;gap:6px;font-size:11.5px;color:#374151;font-weight:600}'
            + '.memo-hv-item .g{margin-left:auto;font-weight:400;color:#9CA3AF}'
            + '.memo-hv-item .p{margin-top:3px;font-size:11px;color:#9CA3AF;overflow:hidden;'
            + 'text-overflow:ellipsis;white-space:nowrap}'
            + '.memo-hv-del{display:inline-flex;align-items:center;justify-content:center;width:22px;height:22px;'
            + 'margin:-3px -4px -3px 0;border:1px solid transparent;border-radius:5px;background:none;color:#9CA3AF;cursor:pointer;padding:0}'
            + '.memo-hv-del:hover,.memo-hv-del:focus-visible{color:#DC2626;background:#FEE2E2;border-color:#FECACA;outline:none}'
            + '.memo-hv-badge{display:inline-block;padding:1px 6px;border-radius:8px;font-size:10px;font-weight:700}'
            + '.memo-hv-badge.save{background:#E0E7FF;color:#3730A3}'
            + '.memo-hv-badge.restore{background:#FEF3C7;color:#92400E}'
            + '.memo-hv-badge.clear{background:#FEE2E2;color:#991B1B}'
            + '.memo-hv-view{flex:1 1 auto;display:flex;flex-direction:column;gap:8px;min-width:0;min-height:0}'
            + '.memo-hv-tools{display:flex;align-items:center;gap:8px 12px;flex-wrap:wrap;font-size:11.5px;color:#4B5563}'
            + '.memo-hv-sw{display:inline-flex;align-items:center;gap:7px;cursor:pointer;font-weight:600;color:#374151;user-select:none}'
            + '.memo-hv-sw input{position:absolute;opacity:0;width:1px;height:1px}'
            + '.memo-hv-sw .trk{position:relative;width:30px;height:17px;border-radius:9px;background:#D1D5DB;transition:background .15s;flex-shrink:0}'
            + '.memo-hv-sw .trk::after{content:"";position:absolute;top:2px;left:2px;width:13px;height:13px;border-radius:50%;'
            + 'background:#fff;box-shadow:0 1px 2px rgba(0,0,0,.25);transition:transform .15s}'
            + '.memo-hv-sw input:checked + .trk{background:#2563EB}'
            + '.memo-hv-sw input:checked + .trk::after{transform:translateX(13px)}'
            + '.memo-hv-sw input:focus-visible + .trk{box-shadow:0 0 0 3px rgba(37,99,235,.3)}'
            + '.memo-hv-sw input:disabled + .trk{opacity:.5}'
            + '.memo-hv-sw.disabled{cursor:not-allowed;color:#9CA3AF}'
            + '.memo-hv-tools kbd{font-family:inherit;font-size:10.5px;font-weight:700;color:#4B5563;background:#F3F4F6;'
            + 'border:1px solid #D1D5DB;border-bottom-width:2px;border-radius:4px;padding:0 5px;line-height:16px}'
            + '.memo-hv-stat{font-variant-numeric:tabular-nums}'
            + '.memo-hv-stat .a{color:#15803D;font-weight:700}'
            + '.memo-hv-stat .r{color:#B91C1C;font-weight:700}'
            + '.memo-hv-legend{margin-left:auto;display:inline-flex;align-items:center;gap:10px;color:#6B7280}'
            + '.memo-hv-legend i{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:4px;vertical-align:-1px}'
            + '.memo-hv-legend .lg-del{background:#FEE2E2;box-shadow:inset 3px 0 0 #DC2626}'
            + '.memo-hv-legend .lg-add{background:#DCFCE7;box-shadow:inset 3px 0 0 #16A34A}'
            + '.memo-hv-body{position:relative;flex:1 1 auto;overflow:auto;padding:12px 14px;border:1px solid #E5E7EB;border-radius:8px;'
            + 'background:#FAFAFA;font-family:\'D2Coding\',\'JetBrains Mono\',monospace;font-size:12.5px;'
            + 'line-height:1.65;color:#111827;white-space:pre-wrap;word-break:break-word;margin:0;min-height:160px}'
            + '.memo-hv-body.empty{color:#9CA3AF;font-style:italic}'
            + '.memo-hv-body.diff{padding:8px 0}'
            /* 줄 표시: 색 + 부호(+/−) — 색만으로는 색각 이상·흑백 인쇄에서 구분되지 않는다. 부호는 ::before 라 복사에 섞이지 않는다 */
            + '.memo-hv-ln{display:block;min-height:1.65em;padding:0 14px 0 30px;position:relative}'
            + '.memo-hv-ln::before{position:absolute;left:10px;top:0;width:12px;text-align:center;font-weight:700;color:#9CA3AF}'
            + '.memo-hv-ln.add{background:#DCFCE7;box-shadow:inset 3px 0 0 #16A34A}'
            + '.memo-hv-ln.add::before{content:"+";color:#15803D}'
            + '.memo-hv-ln.del{background:#FEE2E2;box-shadow:inset 3px 0 0 #DC2626;color:#7F1D1D}'
            + '.memo-hv-ln.del::before{content:"−";color:#B91C1C}'
            + '.memo-hv-ln.more{color:#9CA3AF;font-style:italic;padding-top:6px}'
            + '.memo-hv-confirm{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:12px 18px;'
            + 'border-top:1px solid #FECACA;background:#FEF2F2}'
            + '.memo-hv-confirm[hidden],.memo-hv-btns[hidden]{display:none}'
            + '.memo-hv-confirm .msg{flex:1 1 260px;font-size:12px;line-height:1.55;color:#7F1D1D}'
            + '.memo-hv-confirm .msg b{font-weight:700}'
            + '.memo-hv-confirm .msg.err{color:#B91C1C;font-weight:600}'
            + '.memo-hv-btns{display:flex;gap:8px;justify-content:flex-end;flex-wrap:wrap;padding:12px 18px;'
            + 'border-top:1px solid #F3F4F6}'
            + '.memo-hv-btns button,.memo-hv-confirm button{padding:7px 14px;border-radius:6px;font-size:12px;font-weight:600;'
            + 'line-height:18px;font-family:inherit;cursor:pointer;border:1px solid transparent}'
            + '.memo-hv-btns .hv-clear{margin-right:auto;background:#fff;border-color:#FCA5A5;color:#B91C1C}'
            + '.memo-hv-btns .hv-clear:disabled{border-color:#E5E7EB;color:#D1D5DB;cursor:not-allowed}'
            + '.memo-hv-btns .hv-close,.memo-hv-confirm .cf-cancel{background:#fff;border-color:#D1D5DB;color:#374151}'
            + '.memo-hv-btns .hv-restore{background:#2563EB;color:#fff}'
            + '.memo-hv-btns .hv-restore:disabled{background:#93C5FD;cursor:not-allowed}'
            + '.memo-hv-confirm .cf-ok{background:#DC2626;color:#fff}'
            + '.memo-hv-confirm .cf-ok:disabled{background:#FCA5A5;cursor:progress}'
            + '.memo-hv-btns button:hover:not(:disabled),.memo-hv-confirm button:hover:not(:disabled){opacity:.85}'
            + '@media(max-width:760px){.memo-hv-main{flex-direction:column}'
            + '.memo-hv-list{flex:0 0 34%;min-height:120px}.memo-hv-btns{justify-content:stretch}'
            + '.memo-hv-btns button{flex:1 1 auto}.memo-hv-btns .hv-clear{margin-right:0}'
            + '.memo-hv-legend{margin-left:0}}';
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
            +     '<div class="memo-hv-list" role="listbox" aria-label="저장 시점"></div>'
            +     '<div class="memo-hv-view">'
            +       '<div class="memo-hv-tools">'
            +         '<label class="memo-hv-sw" title="현재 편집 중인 메모와 비교해 달라진 줄에 색을 칠합니다 (단축키 D)">'
            +           '<input type="checkbox" class="hv-diff" role="switch"><span class="trk"></span>변경점 표시</label>'
            +         '<kbd aria-label="단축키 D">D</kbd>'
            +         '<span class="memo-hv-stat" aria-live="polite"></span>'
            +         '<span class="memo-hv-legend" hidden>'
            +           '<span><i class="lg-del"></i>이 시점에만 있는 줄</span>'
            +           '<span><i class="lg-add"></i>현재 메모에만 있는 줄</span></span>'
            +       '</div>'
            +       '<pre class="memo-hv-body"></pre>'
            +     '</div>'
            +   '</div>'
            +   '<div class="memo-hv-confirm" role="alertdialog" aria-live="assertive" hidden>'
            +     '<span class="msg"></span>'
            +     '<button type="button" class="cf-cancel">취소</button>'
            +     '<button type="button" class="cf-ok">삭제</button>'
            +   '</div>'
            +   '<div class="memo-hv-btns">'
            +     '<button type="button" class="hv-clear" disabled>전체 삭제</button>'
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
            clearBtn: ov.querySelector('.hv-clear'),
            btns: ov.querySelector('.memo-hv-btns'),
            confirm: ov.querySelector('.memo-hv-confirm'),
            confirmMsg: ov.querySelector('.memo-hv-confirm .msg'),
            confirmOk: ov.querySelector('.memo-hv-confirm .cf-ok'),
            confirmCancel: ov.querySelector('.memo-hv-confirm .cf-cancel'),
            diffInput: ov.querySelector('.hv-diff'),
            diffSwitch: ov.querySelector('.memo-hv-sw'),
            stat: ov.querySelector('.memo-hv-stat'),
            legend: ov.querySelector('.memo-hv-legend'),
            selectedId: null,
            selectedText: null,   // 선택 시점 본문(상세 조회 결과) — 토글 시 재조회 없이 다시 그린다
            items: [],
            policy: {},
            confirmAction: null,
            cfg: {}
        };

        function close() {
            hideConfirm();
            ov.hidden = true;
            document.removeEventListener('keydown', onKey);
        }
        function isTypingTarget(el) {
            if (!el) return false;
            var tag = el.tagName;
            return tag === 'INPUT' && el.type !== 'checkbox' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
        }
        function onKey(ev) {
            if (ev.key === 'Escape') {
                // 삭제 확인 중이면 확인만 취소한다 — 한 번에 창까지 닫히면 무엇이 취소됐는지 헷갈린다
                if (!api.confirm.hidden) { if (!api.confirmOk.disabled) hideConfirm(); }
                else close();
                return;
            }
            // 변경점 표시 토글 단축키 D — 한글 입력 상태에서도 동작하도록 물리 키(code)도 본다
            if ((ev.code === 'KeyD' || ev.key === 'd' || ev.key === 'D')
                    && !ev.ctrlKey && !ev.metaKey && !ev.altKey && !ev.repeat && !ev.isComposing
                    && !isTypingTarget(ev.target) && api.confirm.hidden && !api.diffInput.disabled) {
                ev.preventDefault();
                setDiff(!api.diffInput.checked, true);
            }
        }
        api.close = close;
        api.bindEsc = function () { document.addEventListener('keydown', onKey); };

        ov.querySelector('.memo-hv-x').addEventListener('click', close);
        ov.querySelector('.hv-close').addEventListener('click', close);
        ov.addEventListener('click', function (ev) { if (ev.target === ov) close(); });

        // ── 변경점 표시 ──
        function setDiff(on, persist) {
            api.diffInput.checked = !!on;
            if (persist) writeDiffPref(!!on);
            renderSelected();
        }
        api.setDiff = setDiff;
        api.diffInput.addEventListener('change', function () { setDiff(api.diffInput.checked, true); });

        function getCurrent() {
            var fn = api.cfg.getCurrent;
            if (typeof fn !== 'function') return null;
            try {
                var v = fn();
                return typeof v === 'string' ? v : null;
            } catch (e) {
                if (window.Common) window.Common.logError(TAG + ' 현재 메모 조회 실패 — 변경점 표시 불가', e);
                return null;
            }
        }

        function renderPlain(text) {
            api.body.classList.remove('diff');
            var truncated = text.length > PREVIEW_MAX_CHARS;
            api.body.textContent = truncated
                ? text.substring(0, PREVIEW_MAX_CHARS) + '\n\n… (이하 생략 — 복원하면 전체가 들어갑니다)'
                : (text === '' ? '(내용 없음)' : text);
            api.body.classList.toggle('empty', text === '');
        }

        function renderDiff(text, current) {
            var res = Memo.diffLines(text, current);
            var frag = document.createDocumentFragment();
            var first = null;
            var n = Math.min(res.ops.length, DIFF_RENDER_MAX_LINES);
            for (var i = 0; i < n; i++) {
                var op = res.ops[i];
                var el = document.createElement('span');
                el.className = 'memo-hv-ln' + (op.t === 'eq' ? '' : ' ' + op.t);
                el.textContent = op.s;   // 사용자 입력 — textContent 로만
                if (op.t !== 'eq' && !first) first = el;
                frag.appendChild(el);
            }
            if (res.ops.length > n) {
                var more = document.createElement('span');
                more.className = 'memo-hv-ln more';
                more.textContent = '… 이하 ' + (res.ops.length - n).toLocaleString() + '줄 생략';
                frag.appendChild(more);
            }
            api.body.textContent = '';
            api.body.classList.remove('empty');
            api.body.classList.add('diff');
            if (!res.ops.length) {
                api.body.classList.remove('diff');
                api.body.classList.add('empty');
                api.body.textContent = '(두 메모 모두 내용 없음)';
            } else {
                api.body.appendChild(frag);
            }

            if (res.added === 0 && res.removed === 0) {
                api.stat.textContent = '현재 메모와 동일합니다';
            } else {
                api.stat.innerHTML = '현재 메모 대비 <span class="a">+' + res.added.toLocaleString() + '</span> '
                    + '<span class="r">−' + res.removed.toLocaleString() + '</span> 줄'
                    + (res.approximate ? ' · 변경이 많아 간략 비교' : '');
            }
            // 첫 변경 줄로 이동 — 긴 메모에서 색칠된 곳을 찾아 스크롤하지 않아도 되게
            if (first) api.body.scrollTop = Math.max(0, first.offsetTop - 48);
        }

        /** 선택된 시점을 현재 토글 상태로 다시 그린다. */
        function renderSelected() {
            var current = getCurrent();
            var canDiff = current !== null;
            api.diffInput.disabled = !canDiff;
            api.diffSwitch.classList.toggle('disabled', !canDiff);
            api.diffSwitch.title = canDiff
                ? '현재 편집 중인 메모와 비교해 달라진 줄에 색을 칠합니다 (단축키 D)'
                : '비교할 현재 메모가 없어 변경점을 표시할 수 없습니다';
            var on = canDiff && api.diffInput.checked;
            api.legend.hidden = !(on && api.selectedText !== null);
            api.stat.textContent = '';
            if (api.selectedText === null) return;
            api.body.scrollTop = 0;
            if (on) renderDiff(api.selectedText, current);
            else renderPlain(api.selectedText);
        }
        api.renderSelected = renderSelected;

        function showPlaceholder(msg) {
            api.selectedText = null;
            api.body.classList.remove('diff');
            api.body.classList.add('empty');
            api.body.textContent = msg;
            api.legend.hidden = true;
            api.stat.textContent = '';
        }
        api.showPlaceholder = showPlaceholder;

        // ── 삭제 확인 바 (모달 안) ──
        function showConfirm(html, okLabel, action) {
            api.confirmAction = action;
            api.confirmMsg.className = 'msg';
            api.confirmMsg.innerHTML = html;
            api.confirmOk.textContent = okLabel;
            api.confirmOk.disabled = false;
            api.confirmCancel.disabled = false;
            api.confirm.hidden = false;
            api.btns.hidden = true;
            api.confirmCancel.focus();   // 기본은 취소 — Enter 한 번으로 지워지지 않게
        }
        function hideConfirm() {
            api.confirm.hidden = true;
            api.btns.hidden = false;
            api.confirmAction = null;
            var pending = api.list.querySelector('.memo-hv-item.pending-del');
            if (pending) pending.classList.remove('pending-del');
        }
        api.hideConfirm = hideConfirm;
        api.showConfirm = showConfirm;

        api.confirmCancel.addEventListener('click', hideConfirm);
        api.confirmOk.addEventListener('click', function () {
            if (!api.confirmAction) return;
            api.confirmOk.disabled = true;
            api.confirmCancel.disabled = true;
            api.confirmOk.textContent = '삭제 중…';
            api.confirmAction().then(hideConfirm).catch(function (e) {
                api.confirmMsg.className = 'msg err';
                api.confirmMsg.textContent = '삭제하지 못했습니다: ' + Memo.errorMessage(e);
                api.confirmOk.textContent = '다시 시도';
                api.confirmOk.disabled = false;
                api.confirmCancel.disabled = false;
                if (api.cfg.onError) api.cfg.onError(e);
            });
        });

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

        api.clearBtn.addEventListener('click', function () {
            if (!api.items.length) return;
            showConfirm('보관된 변경 이력 <b>' + api.items.length.toLocaleString() + '건</b>을 모두 삭제합니다. '
                + '삭제한 이력은 되돌릴 수 없습니다. <span style="color:#6B7280">(현재 메모는 그대로 유지됩니다)</span>',
                '전체 삭제', function () {
                    return Memo.historyClear().then(function (d) {
                        api.items = [];
                        api.list.textContent = '';
                        api.selectedId = null;
                        api.restoreBtn.disabled = true;
                        refreshAfterDelete();
                        if (api.cfg.onDeleted) api.cfg.onDeleted({ all: true, deleted: d.deleted || 0, remaining: 0 });
                    });
                });
        });

        return api;
    }

    function historyNote(policy, count) {
        if (policy.enabled === false) return '변경 이력 기록이 비활성화되어 있습니다 (관리자 설정).';
        return '보관 ' + count.toLocaleString() + '건 · 저장 직전 내용을 최근 ' + (policy.retentionDays || 7) + '일 · 최대 '
            + (policy.maxPerUser || 100) + '건까지 보관합니다. 항목을 고르면 내용을 확인할 수 있습니다.';
    }

    function refreshAfterDelete() {
        hv.note.textContent = historyNote(hv.policy, hv.items.length);
        hv.clearBtn.disabled = hv.items.length === 0;
        if (!hv.items.length) hv.showPlaceholder('보관된 이력이 없습니다.');
        else if (hv.selectedId == null) hv.showPlaceholder('왼쪽 목록에서 시점을 선택하세요.');
    }

    function describeItem(item) {
        return Common.escHtml(Memo.formatBackupTs(item.createdAt) || '시각 미상') + ' · '
            + Common.escHtml(REASON_LABEL[item.reason] || '저장 전') + ' · ' + Common.escHtml(Memo.fmtBytes(item.byteSize || 0));
    }

    /** 목록 1건 렌더 — 사용자 입력(preview)은 textContent 로만 넣는다. */
    function renderHistoryRow(item) {
        var row = document.createElement('div');
        row.className = 'memo-hv-item';
        row.setAttribute('data-id', item.id);
        row.setAttribute('role', 'option');
        row.tabIndex = 0;

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
        var del = document.createElement('button');
        del.type = 'button';
        del.className = 'memo-hv-del';
        del.title = '이 시점 삭제';
        del.setAttribute('aria-label', (Memo.formatBackupTs(item.createdAt) || '') + ' 시점 삭제');
        del.innerHTML = TRASH_SVG;
        t.appendChild(badge);
        t.appendChild(when);
        t.appendChild(size);
        t.appendChild(del);

        var p = document.createElement('div');
        p.className = 'p';
        p.textContent = item.preview || '(내용 없음)';

        row.appendChild(t);
        row.appendChild(p);
        row._del = del;
        return row;
    }

    function selectHistoryItem(row, item) {
        var rows = hv.list.querySelectorAll('.memo-hv-item');
        for (var i = 0; i < rows.length; i++) {
            rows[i].classList.remove('on');
            rows[i].setAttribute('aria-selected', 'false');
        }
        row.classList.add('on');
        row.setAttribute('aria-selected', 'true');
        hv.selectedId = item.id;
        hv.restoreBtn.disabled = true;
        hv.showPlaceholder('불러오는 중…');
        Memo.historyGet(item.id).then(function (detail) {
            if (hv.selectedId !== item.id) return;   // 빠른 연속 클릭·삭제 — 마지막 선택만 반영
            hv.selectedText = detail.memo || '';
            hv.renderSelected();
            hv.restoreBtn.disabled = false;
        }).catch(function (e) {
            if (hv.selectedId !== item.id) return;
            hv.showPlaceholder('내용을 불러오지 못했습니다: ' + Memo.errorMessage(e));
            if (hv.cfg.onError) hv.cfg.onError(e);
        });
    }

    function askDeleteItem(row, item) {
        var prev = hv.list.querySelector('.memo-hv-item.pending-del');
        if (prev) prev.classList.remove('pending-del');
        row.classList.add('pending-del');
        hv.showConfirm('<b>' + describeItem(item) + '</b> 시점을 삭제합니다. 삭제한 시점은 되돌릴 수 없습니다. '
            + '<span style="color:#6B7280">(현재 메모는 그대로 유지됩니다)</span>', '삭제', function () {
                return Memo.historyDelete(item.id).then(function (d) {
                    if (row.parentNode) row.parentNode.removeChild(row);
                    hv.items = hv.items.filter(function (x) { return x.id !== item.id; });
                    if (hv.selectedId === item.id) {
                        hv.selectedId = null;
                        hv.restoreBtn.disabled = true;
                    }
                    refreshAfterDelete();
                    if (hv.cfg.onDeleted) hv.cfg.onDeleted({ all: false, deleted: 1, remaining: d.remaining });
                });
            });
    }

    /**
     * 변경 이력 모달.
     * cfg = {
     *   getCurrent?: fn() → string   // 비교 기준 '현재 메모'(편집 중인 값). 없으면 변경점 표시 비활성
     *   onRestored?: fn(data), onDeleted?: fn({all, deleted, remaining}), onError?: fn(err)
     * }
     */
    Memo.openHistoryViewer = function (cfg) {
        if (!hv) hv = buildHistoryViewer();
        hv.cfg = cfg || {};
        hv.hideConfirm();
        hv.selectedId = null;
        hv.items = [];
        hv.restoreBtn.disabled = true;
        hv.clearBtn.disabled = true;
        hv.list.textContent = '';
        hv.diffInput.checked = readDiffPref();
        hv.showPlaceholder('불러오는 중…');
        hv.renderSelected();   // 토글 가능 여부만 반영(선택 없음)
        hv.note.textContent = '';
        hv.ov.hidden = false;
        hv.bindEsc();

        Memo.historyList().then(function (d) {
            var items = d.items || [];
            hv.policy = { enabled: d.enabled, retentionDays: d.retentionDays, maxPerUser: d.maxPerUser };
            hv.items = items.slice();
            hv.note.textContent = historyNote(hv.policy, items.length);
            hv.clearBtn.disabled = items.length === 0;

            if (!items.length) {
                hv.showPlaceholder('보관된 이력이 없습니다.');
                return;
            }
            hv.showPlaceholder('왼쪽 목록에서 시점을 선택하세요.');

            items.forEach(function (item) {
                var row = renderHistoryRow(item);
                row.addEventListener('click', function () { selectHistoryItem(row, item); });
                row.addEventListener('keydown', function (ev) {
                    if (ev.target !== row) return;   // 안의 삭제 버튼 키 입력은 버튼 몫
                    if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); selectHistoryItem(row, item); }
                    else if (ev.key === 'Delete') { ev.preventDefault(); askDeleteItem(row, item); }
                });
                row._del.addEventListener('click', function (ev) {
                    ev.stopPropagation();   // 행 선택(상세 조회)으로 번지지 않게
                    askDeleteItem(row, item);
                });
                hv.list.appendChild(row);
            });
        }).catch(function (e) {
            hv.showPlaceholder('이력을 불러오지 못했습니다: ' + Memo.errorMessage(e));
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
