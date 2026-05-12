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
                    var err = new Error('HTTP ' + r.status + ': ' + (t || r.statusText));
                    err.status = r.status;
                    err.body = t;
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
