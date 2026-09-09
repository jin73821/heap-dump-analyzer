/*
 * common.js — 페이지간 공통 헬퍼.
 *
 * window.Common 네임스페이스 하나에 모든 헬퍼를 노출.
 * 페이지 인라인 스크립트보다 먼저 로드되어야 한다 (banner.html 에서 로드).
 *
 * 신규 코드는 Common.* 사용 권장. 기존 페이지의 동명 함수(escHtml 등) 는
 * 호환성을 위해 그대로 유지 — 별도 PR 에서 점진적으로 마이그레이션.
 */
(function (global) {
    'use strict';

    var Common = global.Common || {};

    /*
     * 삼킨 예외를 남기는 두 헬퍼 (2026-09-09 정적분석 04.02 조치).
     *
     * 빈 catch 는 "정상 폴백"과 "조용한 실패"를 구분할 수 없게 만든다 —
     * 최소한 무엇을 흡수했는지는 남긴다. 두 함수 모두 **스스로 throw 하지 않도록**
     * typeof 가드만 쓴다(내부에 try 를 두면 그 catch 가 다시 빈 블록이 된다).
     *
     *   logIgnored — 기능적으로 정당한 폴백(스토리지 차단·구형 브라우저 등).
     *                console.debug 라 브라우저 기본 로그 레벨에서는 보이지 않는다.
     *   logError   — 흡수했지만 알려야 하는 오류. console.warn.
     */
    Common.logIgnored = function (tag, e) {
        if (global.console && global.console.debug) {
            global.console.debug(tag + ' 예외를 폴백 처리:', (e && e.message) || e);
        }
    };

    Common.logError = function (tag, e) {
        if (global.console && global.console.warn) {
            global.console.warn(tag + ' 오류:', (e && e.message) || e);
        }
    };

    /* common.js 자신은 Common 이 아직 완성되기 전에도 쓰므로 지역 별칭을 둔다. */
    function ignored(where, e) { Common.logIgnored('[Common] ' + where, e); }

    /**
     * HTML 이스케이프 — `&`, `<`, `>`, `"`, `'` 를 안전한 문자 참조로 변환.
     * null/undefined 는 빈 문자열로 처리.
     */
    Common.escHtml = function (s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    };

    /** CSRF 토큰 (meta 태그에서 추출, 없으면 null) */
    Common.csrfToken = function () {
        var m = document.querySelector('meta[name="_csrf"]');
        return m ? m.content : null;
    };

    /** CSRF 헤더 이름 (meta 태그에서 추출, 기본 X-CSRF-TOKEN) */
    Common.csrfHeaderName = function () {
        var m = document.querySelector('meta[name="_csrf_header"]');
        return m ? m.content : 'X-CSRF-TOKEN';
    };

    /**
     * fetch 옵션에 CSRF 헤더 자동 부착.
     * @example Common.fetchJSON('/api/x', { method: 'POST', body: '...' })
     */
    Common.fetchJSON = function (url, opts) {
        opts = opts || {};
        var headers = opts.headers || {};
        if (!headers['Content-Type'] && opts.body) {
            headers['Content-Type'] = 'application/json';
        }
        var token = Common.csrfToken();
        if (token) {
            headers[Common.csrfHeaderName()] = token;
        }
        opts.headers = headers;
        opts.credentials = opts.credentials || 'same-origin';
        return fetch(url, opts).then(function (r) {
            if (!r.ok) {
                return r.text().then(function (t) {
                    // 세션 만료/로그아웃: 서버가 /api/** 에 401 + code=SESSION_EXPIRED 로 응답한다.
                    // (페이지 라우트는 종전대로 /login 리다이렉트 — 여기 오는 건 AJAX 뿐)
                    var expired = r.status === 401;
                    // 호출량 제한: LLM 계열이 429 + errorCode=LLM_* 로 응답한다.
                    // 본문의 한국어 안내가 원인(초당/일일/동시)을 구분하므로 그대로 노출한다.
                    var limited = r.status === 429;
                    var msg = null;
                    if (limited) {
                        try { msg = (JSON.parse(t) || {}).error; }
                        catch (e) { ignored('429 응답 본문 파싱 — JSON 아님, 기본 문구 사용', e); }
                        if (!msg) msg = '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.';
                    }
                    var err = new Error(expired
                        ? '로그인 세션이 만료되었습니다. 다시 로그인해 주세요.'
                        : (limited ? msg : 'HTTP ' + r.status + ': ' + (t || r.statusText)));
                    err.status = r.status;
                    err.body = t;
                    if (expired) err.sessionExpired = true;
                    if (limited) {
                        err.rateLimited = true;
                        var ra = r.headers.get('Retry-After');
                        if (ra) err.retryAfterSeconds = parseInt(ra, 10);
                    }
                    throw err;
                });
            }
            var ct = r.headers.get('content-type') || '';
            return ct.indexOf('application/json') >= 0 ? r.json() : r.text();
        });
    };

    /**
     * 동적 form 에 `_csrf` hidden input 추가.
     * GET → POST 전환에서 사용 (CLAUDE.md 의 동적 폼 패턴).
     */
    Common.appendCsrfToForm = function (form) {
        var token = Common.csrfToken();
        if (!token) return;
        var input = document.createElement('input');
        input.type = 'hidden';
        input.name = '_csrf';
        input.value = token;
        form.appendChild(input);
    };

    /**
     * 살아 있는 토스트를 세로로 다시 쌓는다.
     *
     * ⚠ common.css 의 `.toast` 는 `position: fixed; top: 70px` **고정 좌표**라, 두 개가 동시에
     *   뜨면 같은 자리에 정확히 포개져 글자가 겹쳐 읽힌다(2026-08-29 제보 — Save All 의
     *   "저장 완료" 와 경고 문구). CSS 대신 여기서 top 을 계산하는 이유는 common.css 를 바꾸면
     *   14개 페이지의 `?v=` 캐시 키를 모두 갱신해야 하기 때문이다.
     *
     * ⚠ `.toast-stack` 마커로 이 계열만 센다 — {@link Common.showToast} 의 `#toast` 고정
     *   엘리먼트(servers/server-detail/admin-users)도 `.toast` 클래스를 쓰므로, 두 계열을 함께
     *   쓰는 페이지가 생기면 좌표 계산이 어긋난다.
     */
    function restackToasts() {
        var live = document.querySelectorAll('.toast-stack');
        var top = 70;
        for (var i = 0; i < live.length; i++) {
            live[i].style.top = top + 'px';
            top += live[i].offsetHeight + 8;
        }
    }

    /**
     * 토스트 (settings/llm-settings/rag-settings 계열) — div.toast.toast-{type} 생성 후 자동 제거.
     * CSS 는 common.css 의 .toast/.toast-success/.toast-error/@keyframes toastIn.
     * 동시에 여러 개가 뜨면 위에서부터 세로로 쌓인다(restackToasts).
     */
    Common.toast = function (msg, type) {
        var t = document.createElement('div');
        t.className = 'toast toast-stack toast-' + (type || 'success');
        t.textContent = msg;
        document.body.appendChild(t);
        restackToasts();
        setTimeout(function () {
            t.style.transition = 'opacity .4s';
            t.style.opacity = '0';
            setTimeout(function () { t.remove(); restackToasts(); }, 400);
        }, 2500);
    };

    /**
     * 토스트 (#toast 고정 엘리먼트 계열 — servers/server-detail/admin/users).
     * 페이지가 `<div id="toast">` 와 .toast.show CSS 를 제공해야 한다.
     */
    Common.showToast = function (msg, type) {
        var t = document.getElementById('toast');
        if (!t) return;
        t.textContent = msg;
        t.className = 'toast ' + type + ' show';
        setTimeout(function () { t.classList.remove('show'); }, 3000);
    };

    /**
     * reload/redirect 를 넘어 전달되는 1회성 토스트 — sessionStorage 에 저장해두면
     * 다음 페이지 로드 시 아래 showPendingToasts() 가 Common.toast 로 표시 후 제거.
     * bulk 삭제처럼 "성공 → location.reload()" 흐름의 성공 피드백용.
     */
    Common.flashToast = function (msg, type) {
        try {
            sessionStorage.setItem('commonFlashToast', JSON.stringify({ m: msg, t: type || 'success' }));
        } catch (e) {
            /* 프라이빗 모드 등 저장 불가 — 피드백만 유실되고 동작은 계속된다 */
            ignored('flashToast 저장 실패(sessionStorage 차단)', e);
        }
    };

    /* 페이지 로드 시 대기 중인 토스트 표시:
       ① sessionStorage 플래시(Common.flashToast) ② 서버 flash attribute 를 담은
       `.flash-data[data-msg][data-type]` 마커 엘리먼트 (form POST → redirect 흐름용). */
    function showPendingToasts() {
        try {
            var raw = sessionStorage.getItem('commonFlashToast');
            if (raw) {
                sessionStorage.removeItem('commonFlashToast');
                var d = JSON.parse(raw);
                if (d && d.m) Common.toast(d.m, d.t);
            }
        } catch (e) {
            ignored('대기 중 토스트 복원 실패(sessionStorage 차단·손상 값)', e);
        }
        var els = document.querySelectorAll('.flash-data[data-msg]');
        Array.prototype.forEach.call(els, function (el, i) {
            var msg = el.getAttribute('data-msg');
            if (!msg) return;
            // 스택 지원(2026-08-29) 후로는 겹치지 않지만, 다건 플래시는 순차로 읽는 편이
            // 이해하기 쉬워 지연을 유지한다.
            setTimeout(function () { Common.toast(msg, el.getAttribute('data-type') || 'success'); }, i * 3100);
        });
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', showPendingToasts);
    } else {
        showPendingToasts();
    }

    /** 바이트 사람-읽기 포맷 (FormatUtils.formatBytes JS 미러) */
    Common.formatBytes = function (bytes) {
        if (bytes == null || bytes < 0) return '-';
        if (bytes === 0) return '0 B';
        var units = ['B', 'KB', 'MB', 'GB', 'TB'];
        var i = 0;
        var n = bytes;
        while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
        return n.toFixed(i === 0 ? 0 : 2) + ' ' + units[i];
    };

    global.Common = Common;
})(typeof window !== 'undefined' ? window : this);
