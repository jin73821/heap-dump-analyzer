/*
 * session-timeout.js — 세션 무동작 만료 클라이언트 타이머 (2026-08-23)
 *
 * 【이 파일이 존재하는 이유】
 * 서버는 무동작 1시간(server.servlet.session.timeout)에 세션을 만료시킨다. 그런데 배너가
 * 60초마다 /api/system/status 를 치는 바람에 Spring Session 의 LAST_ACCESS_TIME 이 계속 갱신돼
 * **탭이 하나라도 열려 있으면 만료가 영원히 오지 않았다.** 사용자는 2시간을 방치해도 로그인 상태였다.
 *
 * 그래서 이 모듈이 "사용자가 실제로 얼마나 유휴했는가"를 유일하게 판정하고,
 *   ① 유휴가 길어지면 배경 폴러를 멈춰 서버 세션도 실제로 만료되게 하고
 *   ② 만료 1분 전 경고 모달을 띄우고
 *   ③ 만료 시각에 로그아웃 후 /login?expired=true 로 보낸다.
 *
 * 【세 가지 함정 — 고치기 전에 반드시 읽을 것】
 *
 * 1) SSE·XHR 업로드는 세션을 계속 살려주지 않는다.
 *    LAST_ACCESS_TIME 은 요청이 필터에 **진입할 때 1회** 찍힌다. 90분짜리 업로드나 35분짜리 MAT SSE 는
 *    시작할 때 한 번 갱신하고 끝이다. 지금까지 이게 문제가 안 됐던 건 순전히 배너 폴링 덕이었다.
 *    → 폴링을 멈추는 이상, 진행 중 작업은 registerActivityGuard() 로 선언해야 하고
 *      이 모듈이 5분 주기로 /api/session/keepalive 를 대신 쳐준다. 이 연결을 끊으면
 *      **장시간 업로드가 도중에 401 로 죽는다** — 이번 변경의 최대 회귀 위험.
 *
 * 2) 경과 시간은 절대 틱 수로 세지 말 것.
 *    Chrome 은 숨은 탭 타이머를 분당 1회로 throttle 하고, 노트북 절전 중에는 아예 발화하지 않으며,
 *    beforeunload 다이얼로그가 뜨면 렌더러가 멈춰 타이머가 밀린다(함정 36 과 같은 메커니즘).
 *    → 항상 Date.now() - lastActivityAt 로 계산하고, visibilitychange/focus/pageshow 에서 즉시 재평가한다.
 *
 * 3) 활동 이벤트에 mousemove 와 scroll 을 넣지 말 것.
 *    mousemove 는 책상 진동·커서 드리프트·마우스 지글러로 세션을 되살려 지금 고치는 버그를 재현한다.
 *    scroll 은 **프로그램적 스크롤에도 발화**한다 — progress.html 은 분석 중 로그 패널을 자동 스크롤하고
 *    memo.js:434 는 scrollTop 을 직접 만진다. scroll 을 세면 방치된 진행 페이지가 불멸이 된다.
 *    실제 사용자 스크롤은 wheel + touchmove 로 충분히 잡힌다.
 *
 * 【모달은 자체 주입】 memo.js / krds-tooltip.js 와 같은 패턴. 소비 페이지의 CSS 에 의존하지 않는다.
 * common.css 의 .mbtn-* 를 쓰면 안 된다 — analyze/compare/progress/leak-rules 4개 페이지는
 * common.css 자체를 로드하지 않아 색상조차 없다(compare.html:237 의 주석은 사실과 다르다).
 * 배너 fragment 안에 마크업을 두는 것도 금지 — ai-chat/analyze 가 배너를 cloneNode 하므로 id 가
 * 중복돼 getElementById 가 원본만 잡는다(함정 8). 그래서 document.body 에 싱글턴으로 붙인다.
 */
(function (global) {
    'use strict';

    if (global.SessionTimeout) return;   // 싱글턴 — 중복 로드 방어

    /* 삼킨 예외 기록 (2026-09-09) — Common 이 없으면 조용히 no-op.
       common.js 는 banner.html·account-memo.html 에서 이 파일보다 먼저 로드된다. */
    var TAG = '[SessionTimeout]';
    function ignored(where, e) { if (global.Common) global.Common.logIgnored(TAG + ' ' + where, e); }
    function failed(where, e)  { if (global.Common) global.Common.logError(TAG + ' ' + where, e); }

    /* ── 튜너블 ──────────────────────────────────────────── */
    var WARN_BEFORE_MS            = 60000;   // 만료 몇 ms 전에 경고 모달을 띄우는가
    var IDLE_POLL_STOP_MS         = 120000;  // 이만큼 유휴하면 배경 폴러 정지 (서버 세션도 만료되게)
    var PING_MIN_INTERVAL_MS      = 300000;  // keep-alive 최소 간격 (서버 부하 억제)
    var TICK_MS                   = 5000;    // 평상시 판정 주기
    var TICK_WARN_MS              = 1000;    // 경고 모달 카운트다운 주기
    var ACTIVITY_WRITE_THROTTLE_MS = 5000;   // localStorage 쓰기 억제
    var NAV_WATCHDOG_MS           = 1500;    // /logout 응답이 안 와도 이동시키는 상한
    var DEFAULT_TIMEOUT_SEC       = 3600;

    var LS_KEY            = 'heapSessionState';
    var BC_NAME           = 'heap-session-sync';
    var KEEPALIVE_URL     = '/api/session/keepalive';
    var LOGOUT_URL        = '/logout';
    var EXPIRED_URL       = '/login?expired=true';
    var BANNER_CACHE_KEY  = 'bannerStatusCache';   // 401 본문이 섞여 오염될 수 있어 만료 시 제거

    /* ── 내부 상태 ───────────────────────────────────────── */
    var ST = {};
    var _mode        = 'navigate';   // 'navigate' | 'memo'
    var _timeoutMs   = DEFAULT_TIMEOUT_SEC * 1000;
    var _guards      = [];           // 진행 중 작업 술어
    var _expireHooks = [];
    var _navBlocks   = [];           // true 면 이 탭은 만료돼도 이동하지 않는다
    var _managed     = [];           // managedInterval 핸들
    var _expiring    = false;
    var _navigated   = false;
    var _warnOpen    = false;
    var _tickTimer   = null;
    var _tickEvery   = TICK_MS;
    var _bc          = null;
    var _memState    = null;         // localStorage 불가 시 탭 로컬 폴백
    var _lastWrite   = 0;
    var _pingInFlight = false;

    /* ── 공유 상태 (탭 간) ────────────────────────────────
       localStorage 가 "출처"이고 BroadcastChannel 은 즉시 통지용일 뿐이다.
       localStorage 를 쓰는 이유: 새로 연 탭이 기존 유휴 시계를 물려받아야 한다.
       탭마다 초기화하면 탭을 열 때마다 만료가 리셋돼 버그가 되살아난다. */
    function now() { return Date.now(); }

    function readState() {
        try {
            var raw = global.localStorage.getItem(LS_KEY);
            if (raw) {
                var s = JSON.parse(raw);
                if (s && s.v === 1) return s;
            }
        } catch (e) {
            /* 프라이빗 모드 등 — 탭 로컬 폴백 */
            ignored('상태 복원 실패(localStorage 차단) — 탭 로컬 폴백', e);
        }
        if (!_memState) _memState = freshState();
        return _memState;
    }

    function freshState() {
        var t = now();
        return { v: 1, lastActivityAt: t, lastServerTouchAt: t, timeoutSec: _timeoutMs / 1000, expiredAt: 0 };
    }

    function writeState(s) {
        _memState = s;
        try { global.localStorage.setItem(LS_KEY, JSON.stringify(s)); }
        catch (e) { ignored('상태 저장 실패(localStorage 차단) — 탭 로컬 폴백 유지', e); }
    }

    function clearState() {
        _memState = null;
        try { global.localStorage.removeItem(LS_KEY); }
        catch (e) { ignored('상태 삭제 실패(localStorage 차단)', e); }
    }

    function post(type, extra) {
        if (!_bc) return;
        var msg = { type: type, at: now() };
        if (extra) for (var k in extra) if (extra.hasOwnProperty(k)) msg[k] = extra[k];
        try { _bc.postMessage(msg); }
        catch (e) { ignored('탭 간 통지 실패(채널 닫힘) — type=' + type, e); }
    }

    /* ── 활동 기록 ───────────────────────────────────────── */

    /** 사용자 활동(또는 명시적 연장)을 기록한다. force 면 throttle 을 무시. */
    ST.touch = function (force) {
        if (_expiring) return;
        var t = now();
        if (!force && t - _lastWrite < ACTIVITY_WRITE_THROTTLE_MS) return;
        _lastWrite = t;
        var s = readState();
        s.lastActivityAt = t;
        writeState(s);
        if (force) post('activity');
    };

    /** 서버를 실제로 건드린 시각 기록 — 배경 폴러와 keep-alive 가 서로 중복 호출하지 않게 한다. */
    ST.noteServerTouch = function () {
        var s = readState();
        s.lastServerTouchAt = now();
        writeState(s);
    };

    /* ── 진행 중 작업 가드 ────────────────────────────────
       ⚠ registerUnloadGuard(배너) 와 혼동 금지. 그쪽은 "이탈 경고를 띄울 조건"이고
       account.html 의 '미저장 메모 있음' 처럼 **무기한** 참일 수 있는 조건이 등록돼 있다.
       그걸 활동 가드로 재사용하면 메모 한 글자만 쳐두고 자리를 비워도 세션이 영원히 안 끊긴다
       — 지금 고치는 버그와 정확히 같은 결과가 된다. 여기에는 **끝나는 작업만** 등록할 것. */
    ST.registerActivityGuard = function (fn) {
        if (typeof fn === 'function') _guards.push(fn);
    };

    /* 런어웨이 백스톱 — 가드가 영원히 참이면 세션이 안 끊긴다.
       실제로 그럴 수 있는 경로가 있다: progress.html 의 SSE onerror 는 HEAD 응답이 !ok 이면
       showComplete 도 .catch 도 타지 않아 analysisRunning 이 true 로 남는다.
       가드가 이 시간을 넘겨 붙들고 있으면 무시하고 정상 만료로 돌아간다. 정당한 장시간 업로드를
       끊지 않을 만큼 넉넉하되, "영원히"는 아니게 잡은 값. */
    var GUARD_MAX_HOLD_MS = 8 * 3600 * 1000;
    var _guardHoldSince = 0;
    var _guardWarned = false;

    ST.hasActiveWork = function () {
        var any = false;
        for (var i = 0; i < _guards.length; i++) {
            try { if (_guards[i]()) { any = true; break; } }
            catch (e) { failed('활동 가드 실행 실패 — 진행 중 작업이 없는 것으로 간주한다', e); }
        }
        if (!any) { _guardHoldSince = 0; _guardWarned = false; return false; }
        if (!_guardHoldSince) _guardHoldSince = now();
        if (now() - _guardHoldSince > GUARD_MAX_HOLD_MS) {
            if (!_guardWarned) {
                _guardWarned = true;
                /* console 자체가 없는 환경 — 더 할 수 있는 일이 없다 */
                try { global.console.warn(TAG + ' 진행 중 작업 가드가 8시간을 넘겨 무시합니다.'); } catch (e) { return false; }
            }
            return false;
        }
        return true;
    };

    ST.onExpire = function (fn) {
        if (typeof fn === 'function') _expireHooks.push(fn);
    };

    /* 만료돼도 이 탭은 이동시키지 않을 조건 (CLAUDE.md 세션 만료 규약:
       "작성 중 데이터가 있는 화면은 강제 리다이렉트 대신 백업 + 재로그인 유도").
       ⚠ registerActivityGuard 와 다르다 — 이건 세션을 **연장하지 않는다**. 만료는 정상적으로 일어나고
       이 탭만 화면을 유지할 뿐이다. 미저장 메모 한 줄로 세션이 불멸이 되면 안 되기 때문이다.
       작성 중 내용이 없으면 false 를 돌려 평범하게 로그인 페이지로 이동하게 할 것. */
    ST.registerNavigationBlock = function (fn) {
        if (typeof fn === 'function') _navBlocks.push(fn);
    };

    function navigationBlocked() {
        if (_mode === 'memo') {
            // 조건 술어가 하나도 없으면 보수적으로 이동을 막는다(구 동작 호환)
            if (!_navBlocks.length) return true;
        }
        for (var i = 0; i < _navBlocks.length; i++) {
            try { if (_navBlocks[i]()) return true; }
            catch (e) { failed('이동 차단 술어 실행 실패 — 차단하지 않는다', e); }
        }
        return false;
    }

    ST.isExpiring = function () { return _expiring; };

    ST.configure = function (opts) {
        opts = opts || {};
        if (opts.mode === 'memo' || opts.mode === 'navigate') _mode = opts.mode;
        if (opts.timeoutSeconds > 0) _timeoutMs = opts.timeoutSeconds * 1000;
        if (typeof opts.onExpire === 'function') _expireHooks.push(opts.onExpire);
    };

    /* ── 유휴 계산 ───────────────────────────────────────── */
    function idleMs() {
        var s = readState();
        var d = now() - s.lastActivityAt;
        // 시계가 뒤로 갔다(NTP 보정 / 절전 후 RTC 복원). 만료시키면 안 되므로 활동으로 간주.
        if (d < 0) { s.lastActivityAt = now(); writeState(s); return 0; }
        return d;
    }

    /** 배경 폴러 게이트. 진행 중 작업이 있으면 숨은 탭이어도 계속 돈다. */
    ST.shouldPoll = function () {
        if (_expiring) return false;
        if (ST.hasActiveWork()) return true;
        if (idleMs() >= IDLE_POLL_STOP_MS) return false;
        return !global.document.hidden;
    };

    /* ── managedInterval — 유휴 시 스스로 멈추는 setInterval ──
       정지 후 다시 조건이 맞으면 **즉시 1회 실행**한 뒤 주기를 재개한다.
       (배너가 복귀 직후 낡은 값을 보여주지 않도록 — banner.html 의 캐시 age 로직과 같은 취지) */
    ST.managedInterval = function (fn, ms) {
        var ran = false;
        var timer = global.setInterval(function () {
            if (!ST.shouldPoll()) { ran = false; return; }
            if (!ran) { ran = true; }
            try { fn(); }
            catch (e) { failed('주기 실행 콜백 실패 — 폴링은 계속한다', e); }
            ST.noteServerTouch();
        }, ms);
        var handle = {
            stop: function () { if (timer) { global.clearInterval(timer); timer = null; } }
        };
        _managed.push(handle);
        return handle;
    };

    function stopAllManaged() {
        for (var i = 0; i < _managed.length; i++) {
            try { _managed[i].stop(); }
            catch (e) { ignored('관리 폴러 정지 실패', e); }
        }
    }

    /* ── keep-alive ──────────────────────────────────────
       진행 중 작업이 있거나 사용자가 최근 활동했는데 서버를 오래 안 건드렸으면 한 번 친다.
       배너가 있는 페이지는 폴링이 noteServerTouch 를 하므로 자연히 억제되고,
       배너도 폴러도 없는 /account/memo 에서는 이게 유일한 갱신 경로가 된다. */
    function maybeKeepAlive() {
        if (_expiring || _pingInFlight) return;
        var s = readState();
        var active = ST.hasActiveWork() || (now() - s.lastActivityAt) < IDLE_POLL_STOP_MS;
        if (!active) return;
        if (now() - s.lastServerTouchAt < PING_MIN_INTERVAL_MS) return;
        ping();
    }

    function ping(onResult) {
        _pingInFlight = true;
        global.fetch(KEEPALIVE_URL, { credentials: 'same-origin' })
            .then(function (r) {
                if (r.status === 401 || r.status === 403) { ST.notifyExpired('keepalive'); return null; }
                if (!r.ok) return null;
                ST.noteServerTouch();
                return r.json().catch(function () { return null; });
            })
            .then(function (d) {
                // 관리자가 타임아웃을 바꿔 새 세션이 다른 값을 갖게 된 경우 자가 교정.
                if (d && d.timeoutSeconds > 0 && d.timeoutSeconds * 1000 !== _timeoutMs) {
                    _timeoutMs = d.timeoutSeconds * 1000;
                }
                if (onResult) onResult(true);
            })
            .catch(function () { if (onResult) onResult(false); })
            .then(function () { _pingInFlight = false; });
    }

    /* ── 경고 모달 (CSS·DOM 자체 주입 싱글턴) ─────────────── */
    var _ov = null;

    function injectCss() {
        if (global.document.getElementById('stoCss')) return;
        var css =
            '.sto-ov{display:none;position:fixed;inset:0;z-index:11000;background:rgba(0,0,0,.5);'
          + 'align-items:center;justify-content:center;padding:16px}'
          + '.sto-ov.open{display:flex}'
          + '.sto-box{background:#fff;border-radius:12px;box-shadow:0 20px 60px rgba(0,0,0,.25);'
          + 'max-width:420px;width:100%;padding:24px;font-family:inherit;animation:stoIn .18s ease-out}'
          + '@keyframes stoIn{from{opacity:0;transform:translateY(12px)}to{opacity:1;transform:none}}'
          + '.sto-title{font-size:16px;font-weight:700;color:#111827;margin:0 0 10px;display:flex;'
          + 'align-items:center;gap:8px}'
          + '.sto-desc{font-size:13px;line-height:1.65;color:#4B5563;margin:0 0 6px}'
          + '.sto-count{font-size:13px;line-height:1.65;color:#4B5563;margin:0 0 18px}'
          + '.sto-count b{font-size:20px;color:#B91C1C;font-variant-numeric:tabular-nums}'
          /* 형태 규칙을 여기서 직접 준다 — .mbtn-* 는 색상 전용인데다 4개 페이지엔 정의조차 없다(함정 17) */
          + '.sto-btns{display:flex;gap:8px;justify-content:flex-end;flex-wrap:wrap}'
          + '.sto-btns button{padding:9px 16px;border:none;border-radius:6px;font-size:13px;'
          + 'font-weight:600;font-family:inherit;line-height:1.5;cursor:pointer}'
          + '.sto-btns .sto-extend{background:#2563EB;color:#fff}'
          + '.sto-btns .sto-extend:hover{background:#1D4ED8}'
          + '.sto-btns .sto-out{background:#F3F4F6;color:#374151}'
          + '.sto-btns .sto-out:hover{background:#E5E7EB}'
          + '@media(max-width:480px){.sto-btns{justify-content:stretch}.sto-btns button{flex:1 1 auto}}';
        var st = global.document.createElement('style');
        st.id = 'stoCss';
        st.textContent = css;
        global.document.head.appendChild(st);
    }

    function buildModal() {
        if (_ov) return _ov;
        injectCss();
        var ov = global.document.createElement('div');
        ov.className = 'sto-ov';
        ov.setAttribute('role', 'dialog');
        ov.setAttribute('aria-modal', 'true');
        ov.setAttribute('aria-label', '세션 만료 경고');
        ov.innerHTML =
            '<div class="sto-box">'
          + '<p class="sto-title">⏱ 세션이 곧 만료됩니다</p>'
          + '<p class="sto-desc">장시간 활동이 없어 보안을 위해 자동으로 로그아웃됩니다.</p>'
          + '<p class="sto-count">남은 시간 <b class="sto-sec">60</b>초</p>'
          + '<div class="sto-btns">'
          + '<button type="button" class="sto-out">지금 로그아웃</button>'
          + '<button type="button" class="sto-extend">세션 연장</button>'
          + '</div></div>';
        global.document.body.appendChild(ov);
        // ⚠ id 가 아니라 이 오버레이 서브트리 안에서만 찾는다 (배너 clone 과의 id 충돌 회피 — 함정 8)
        ov.querySelector('.sto-extend').addEventListener('click', function () { ST.extend(); });
        ov.querySelector('.sto-out').addEventListener('click', function () { ST._expire('user'); });
        _ov = ov;
        return ov;
    }

    function showWarn() {
        if (_warnOpen || _expiring) return;
        _warnOpen = true;
        var ov = buildModal();
        ov.classList.add('open');
        // 포커스를 연장 버튼에 둬서 Enter/Space 로 바로 연장할 수 있게 한다
        try { ov.querySelector('.sto-extend').focus(); }
        catch (e) { ignored('경고 모달 연장 버튼 포커스 실패', e); }
        retick(TICK_WARN_MS);
    }

    function hideWarn() {
        if (!_warnOpen) return;
        _warnOpen = false;
        if (_ov) _ov.classList.remove('open');
        retick(TICK_MS);
    }

    function paintCountdown(remainMs) {
        if (!_ov) return;
        var el = _ov.querySelector('.sto-sec');
        if (el) el.textContent = String(Math.max(0, Math.ceil(remainMs / 1000)));
    }

    /** '세션 연장' — 활동을 기록하고 서버 세션을 실제로 갱신한다. */
    ST.extend = function () {
        if (_expiring) return;
        ST.touch(true);
        hideWarn();
        post('extend');
        ping(function (ok) {
            // 이미 서버에서 만료됐다면 ping 이 notifyExpired 를 호출한다. ok=false 는 네트워크 오류 —
            // 다음 틱에서 다시 판정되므로 여기서 별도 처리하지 않는다.
            if (ok) ST.noteServerTouch();
        });
    };

    /* ── 만료 ────────────────────────────────────────────
       서버가 이미 401 을 준 경우(앱 재시작 / 관리자 강제 종료 / CSRF 불일치)의 진입점. */
    ST.notifyExpired = function (source) {
        ST._expire(source || 'server');
    };

    ST._expire = function (reason) {
        if (_expiring) return;
        _expiring = true;

        // 1) 다른 탭에 즉시 통지 + 공유 상태에도 기록(BC 를 놓친 탭은 다음 틱에 본다)
        try {
            var s = readState();
            s.expiredAt = now();
            writeState(s);
        } catch (e) {
            ignored('만료 시각 공유 상태 기록 실패(localStorage 차단)', e);
        }
        post('expired', { reason: reason });

        // 2) 만료 훅 — 메모 페이지는 여기서 localStorage 백업을 남긴다.
        //    이동보다 **먼저** 돌려야 작성 중이던 내용이 보존된다.
        for (var i = 0; i < _expireHooks.length; i++) {
            try { _expireHooks[i](reason); }
            catch (e) { failed('만료 훅 실행 실패 — 작성 중이던 내용이 백업되지 않았을 수 있다', e); }
        }

        hideWarn();
        if (_tickTimer) { global.clearInterval(_tickTimer); _tickTimer = null; }
        stopAllManaged();

        // 3) 작성 중 데이터가 있는 탭은 여기서 끝 — 이동시키지 않는다.
        //    (세션은 정상적으로 만료된 상태다. 화면만 유지해 백업·재로그인 안내를 보여준다)
        if (navigationBlocked()) return;

        // 4) 이탈 경고를 무력화한다.
        //    ⚠ location.replace 도 beforeunload 를 발화시킨다. 경고가 뜨면 사용자가 '취소'할 수 있고
        //    그러면 자동 이동이 통째로 무산된다(게다가 함정 36 대로 스피너가 남는다).
        //    프로퍼티 대입형(upload-queue.js:466)은 여기서 지워지지만, addEventListener 형은
        //    외부에서 제거할 수 없어 각 핸들러가 isExpiring() 을 보고 스스로 빠져야 한다.
        try { global.onbeforeunload = null; }
        catch (e) { ignored('onbeforeunload 해제 실패', e); }
        if (typeof global.disableUnloadGuards === 'function') {
            try { global.disableUnloadGuards(); }
            catch (e) { ignored('페이지 이탈 가드 해제 실패', e); }
        }
        try { global.localStorage.removeItem(BANNER_CACHE_KEY); }
        catch (e) { ignored('배너 상태 캐시 삭제 실패(localStorage 차단)', e); }

        // 5) 로그아웃 후 이동. 응답을 기다리되 watchdog 으로 반드시 이동한다.
        var token = null, header = 'X-CSRF-TOKEN';
        try {
            if (global.Common) { token = global.Common.csrfToken(); header = global.Common.csrfHeaderName(); }
        } catch (e) {
            ignored('CSRF 토큰 조회 실패 — 토큰 없이 로그아웃을 시도한다', e);
        }
        var opts = { method: 'POST', credentials: 'same-origin', keepalive: true, redirect: 'manual' };
        if (token) { opts.headers = {}; opts.headers[header] = token; }

        global.setTimeout(navigate, NAV_WATCHDOG_MS);
        try {
            global.fetch(LOGOUT_URL, opts)
                .catch(function (e) { ignored('로그아웃 요청 실패 — 이동은 그대로 진행한다', e); })
                .then(navigate);
        } catch (e) { navigate(); }
    };

    function navigate() {
        if (_navigated) return;
        _navigated = true;
        clearState();
        // replace: 뒤로가기로 죽은 페이지에 돌아가지 않게
        try { global.location.replace(EXPIRED_URL); } catch (e) { global.location.href = EXPIRED_URL; }
    }

    /* ── 판정 루프 ───────────────────────────────────────── */
    function evaluate() {
        if (_expiring) return;

        var s = readState();
        // 다른 탭이 만료를 선언했다 (BC 를 놓쳤어도 여기서 잡힌다)
        if (s.expiredAt) { ST._expire('peer'); return; }

        // 진행 중 작업이 있으면 유휴 시계를 멈춘다(=계속 활동 중인 것으로 본다).
        if (ST.hasActiveWork()) {
            ST.touch(false);
            hideWarn();
            maybeKeepAlive();
            return;
        }

        var idle = idleMs();
        var remain = _timeoutMs - idle;

        if (remain <= 0) { ST._expire('idle'); return; }
        if (remain <= WARN_BEFORE_MS) {
            showWarn();
            paintCountdown(remain);
        } else {
            hideWarn();
            maybeKeepAlive();
        }
    }

    function retick(ms) {
        if (_tickEvery === ms && _tickTimer) return;
        _tickEvery = ms;
        if (_tickTimer) global.clearInterval(_tickTimer);
        _tickTimer = global.setInterval(evaluate, ms);
    }

    /* ── 초기화 ─────────────────────────────────────────── */
    function activity() { ST.touch(false); }

    function init() {
        // 만료 간격: 서버가 배너/메모 페이지에서 window.SESSION_TIMEOUT_SECONDS 로 내려준다.
        // (HttpSession.getMaxInactiveInterval() 유래 — 관리자가 설정을 바꿔도 이 세션의 진짜 값)
        var sec = global.SESSION_TIMEOUT_SECONDS;
        if (typeof sec === 'number' && sec > 0) _timeoutMs = sec * 1000;

        var s = readState();
        if (!s.lastActivityAt) { s = freshState(); }
        s.timeoutSec = _timeoutMs / 1000;
        // 페이지를 새로 렌더했다는 건 서버를 방금 건드렸다는 뜻
        s.lastServerTouchAt = now();
        // 페이지 이동 자체도 사용자 활동이다 — 링크 클릭은 mousedown 으로 잡히지만
        // 뒤로/앞으로·주소 직접 입력·북마크는 아무 이벤트도 남기지 않아 유휴로 오판된다.
        // ⚠ 이게 안전한 건 이 앱에 **주기적 자동 새로고침이 없기** 때문이다(location.reload 는 전부
        // 추가/수정/삭제 성공 뒤 1회성). 타이머로 스스로 reload 하는 화면을 만들면 그 페이지는
        // 영원히 만료되지 않으므로, 그런 걸 추가할 땐 여기를 반드시 다시 볼 것.
        s.lastActivityAt = now();
        writeState(s);

        try { _bc = new global.BroadcastChannel(BC_NAME); }
        catch (e) { _bc = null; ignored('BroadcastChannel 미지원 — 탭 간 동기화 없이 동작한다', e); }
        if (_bc) {
            _bc.onmessage = function (ev) {
                var d = ev && ev.data;
                if (!d) return;
                if (d.type === 'expired') { ST._expire('peer'); }
                else if (d.type === 'activity' || d.type === 'extend') { hideWarn(); evaluate(); }
            };
        }
        // BroadcastChannel 미지원 폴백 — storage 이벤트는 쓴 탭에는 안 온다(memo.js 와 같은 형태)
        global.addEventListener('storage', function (ev) {
            if (ev.key !== LS_KEY) return;
            if (_expiring) return;
            evaluate();
        });

        var evts = ['mousedown', 'keydown', 'wheel', 'touchstart', 'touchmove', 'input', 'change'];
        for (var i = 0; i < evts.length; i++) {
            global.document.addEventListener(evts[i], activity, { capture: true, passive: true });
        }

        // 타이머는 절전·throttle 을 못 견딘다 — 복귀 신호에서 즉시 재평가한다(위 함정 2)
        global.document.addEventListener('visibilitychange', function () {
            if (!global.document.hidden) evaluate();
        });
        global.addEventListener('focus', evaluate);
        global.addEventListener('pageshow', function (e) {
            // bfcache 로 되살아난 죽은 페이지는 다시 내보낸다
            if (e && e.persisted && _expiring) { _navigated = false; navigate(); return; }
            evaluate();
        });

        retick(TICK_MS);
        evaluate();
    }

    /* ── 디버그 훅 ───────────────────────────────────────
       ⚠ 타임아웃을 **늘리는** 오버라이드는 의도적으로 제공하지 않는다. 그런 훅은 세션을 무한 연장하는
       우회로가 돼 지금 고치는 버그를 그대로 되살린다. 아래 둘은 만료를 앞당기기만 하므로 악용 가치가 없다. */
    ST._debug = {
        forceWarn:   function () { showWarn(); paintCountdown(WARN_BEFORE_MS); },
        forceExpire: function () { ST._expire('debug'); },
        shorten:     function (sec) {           // 만료를 앞당기는 방향으로만 허용
            var ms = sec * 1000;
            if (ms > 0 && ms < _timeoutMs) { _timeoutMs = ms; evaluate(); return true; }
            return false;
        },
        state: function () {
            var s = readState();
            return { mode: _mode, timeoutMs: _timeoutMs, idleMs: idleMs(), expiring: _expiring,
                     activeWork: ST.hasActiveWork(), guards: _guards.length, state: s };
        }
    };

    global.SessionTimeout = ST;

    if (global.document.readyState === 'loading') {
        global.document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})(window);
