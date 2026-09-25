/**
 * live-refresh.js — 대시보드·현황판 부분 갱신 (2026-09-26)
 *
 * 서버가 같은 Thymeleaf 템플릿의 th:fragment 조각 여러 개를 한 응답(Spring FragmentsRendering)으로 돌려주면
 * id 가 같은 영역만 바꿔 끼운다. 스크롤·필터·열린 모달·업로드 큐가 유지되고 깜빡임이 없다.
 *
 *   var live = LiveRefresh.start({
 *       url: '/api/alerts/fragments',          // ⚠ /api/ 접두 필수 — 만료 시 401 JSON (페이지 경로면 302→로그인 HTML 이 200 으로 와 조용히 실패)
 *       ids: ['alertKpi', 'alertList'],         // 교체할 영역 id (= th:fragment 이름)
 *       label: '#liveUpdated',                  // '갱신 HH:MM:SS' 를 쓸 요소(선택)
 *       canRun: function () { return true; },   // 페이지 고유 차단 조건(선택) — false 면 이번 주기는 건너뛴다
 *       onSwapped: function (ids) {}            // 실제로 바뀐 영역이 있을 때 호출(선택)
 *   });
 *   live.refreshNow();  live.stop();
 *
 * 규약
 *  - 주기는 Settings > General 의 '화면 자동 갱신 주기'(window.UI_REFRESH_SECONDS, 폴백 60초).
 *  - 타이머는 SessionTimeout.managedInterval 만 쓴다(함정 38) — 유휴 2분·숨은 탭이면 스스로 멈춘다.
 *    ⚠ 이 틱은 세션 유휴 시계(lastActivityAt)를 건드리지 않는다. SessionTimeout 의 활동 기록 함수를 절대 부르지 말 것.
 *  - 요소를 갈아 끼우지 않고 속성 + innerHTML 만 바꾼다(morph) — 페이지 JS 가 캡처한 요소 참조가 살아남는다.
 *  - 응답이 직전과 같으면 DOM 을 건드리지 않는다(차트·연출 보존).
 *  - 조각이 한쪽에만 있으면(레이아웃이 바뀜) ⚠ 자동 reload 하지 않는다 — 폴링을 멈추고 '새로고침' 버튼을 보인다.
 *    session-timeout.js 의 init() 이 페이지 로드를 사용자 활동으로 기록하므로, 스스로 reload 하는 화면은 영원히 만료되지 않는다.
 *  - 차단: 요청 진행 중 · 열린 모달(.modal-ov.open) · AnalyzeConfirm 창 · 업로드 중 · 페이지 canRun() false.
 *  - 실패(5xx·네트워크)는 이전 화면을 그대로 두고 라벨만 '갱신 실패' 로 바꾼다. 401/403 은 세션 만료로 넘긴다.
 */
(function (global) {
    'use strict';

    var TAG = '[LiveRefresh]';
    function failed(msg, e) { if (global.Common && global.Common.logError) global.Common.logError(TAG + ' ' + msg, e); }
    function ignored(msg, e) { if (global.Common && global.Common.logIgnored) global.Common.logIgnored(TAG + ' ' + msg, e); }

    var styleDone = false;
    function injectStyle() {
        if (styleDone || !global.document) return;
        styleDone = true;
        var st = global.document.createElement('style');
        st.textContent = '.live-updated{font-size:11px;color:#9CA3AF;white-space:nowrap}'
            + '.live-updated.err{color:#B45309}'
            + '.live-updated .lr-reload{background:#FFFBEB;border:1px solid #FCD34D;border-radius:6px;padding:2px 8px;font:inherit;font-size:11px;color:#92400E;cursor:pointer}';
        global.document.head.appendChild(st);
    }

    /** 요소 identity 를 보존하며 속성·자식만 새것으로 (id 는 유지) */
    function morph(oldEl, newEl) {
        var i, keep = {};
        for (i = 0; i < newEl.attributes.length; i++) {
            var a = newEl.attributes[i];
            keep[a.name] = 1;
            if (oldEl.getAttribute(a.name) !== a.value) oldEl.setAttribute(a.name, a.value);
        }
        for (i = oldEl.attributes.length - 1; i >= 0; i--) {
            var n = oldEl.attributes[i].name;
            if (!keep[n] && n !== 'id') oldEl.removeAttribute(n);
        }
        oldEl.innerHTML = newEl.innerHTML;
    }

    function hhmmss() {
        var d = new Date();
        function p(n) { return (n < 10 ? '0' : '') + n; }
        return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    }

    function start(cfg) {
        cfg = cfg || {};
        injectStyle();
        var sec = global.UI_REFRESH_SECONDS > 0 ? global.UI_REFRESH_SECONDS : 60;
        var interval = cfg.interval > 0 ? cfg.interval : sec * 1000;
        var ids = cfg.ids || [];
        var label = cfg.label ? global.document.querySelector(cfg.label) : null;
        var inFlight = false, halted = false, last = {}, handle = null, dbDown = false;

        function stamp(text, err) {
            if (!label) return;
            label.textContent = text + ' ' + hhmmss();
            label.classList.toggle('err', !!err);
            label.title = err ? '마지막 갱신 시도가 실패했습니다. 화면은 마지막 성공 시점 기준입니다.' : '마지막 갱신 시각';
        }

        /** 레이아웃이 바뀌어 조각으로는 못 따라간다 — 자동 reload 대신 사용자에게 맡긴다 */
        function halt() {
            halted = true;
            if (handle) handle.stop();
            if (!label) return;
            label.textContent = '';
            label.classList.remove('err');
            var b = global.document.createElement('button');
            b.type = 'button';
            b.className = 'lr-reload';
            b.textContent = '화면 구성이 바뀌었습니다 · 새로고침';
            b.addEventListener('click', function () { global.location.reload(); });
            label.appendChild(b);
        }

        function blocked() {
            if (inFlight || halted) return true;
            var doc = global.document;
            if (doc.querySelector('.modal-ov.open')) return true;
            if (global.AnalyzeConfirm && global.AnalyzeConfirm.isOpen && global.AnalyzeConfirm.isOpen()) return true;
            if (global.UploadQueue && global.UploadQueue.isUploading && global.UploadQueue.isUploading()) return true;
            if (typeof cfg.canRun === 'function') {
                try { if (!cfg.canRun()) return true; }
                catch (e) { failed('canRun 판정 실패 — 이번 주기는 건너뛴다', e); return true; }
            }
            return false;
        }

        function apply(html) {
            var doc = new global.DOMParser().parseFromString(html, 'text/html');
            var swapped = [], structural = false;
            for (var i = 0; i < ids.length; i++) {
                var id = ids[i];
                var next = doc.getElementById(id), cur = global.document.getElementById(id);
                if (!next && !cur) continue;                  // 양쪽 다 없음(조건부 영역) — 정상
                if (!next || !cur) { structural = true; continue; }
                var h = next.outerHTML;
                if (last[id] === h) continue;                 // 변화 없음 — DOM 을 건드리지 않는다
                morph(cur, next);
                last[id] = h;
                swapped.push(id);
            }
            if (structural) { halt(); return; }
            if (swapped.length) {
                if (typeof cfg.onSwapped === 'function') {
                    try { cfg.onSwapped(swapped); }
                    catch (e) { failed('onSwapped 처리 실패 — 화면 일부가 이전 상태로 남을 수 있다', e); }
                }
                try { global.document.dispatchEvent(new CustomEvent('liverefresh:swapped', { detail: { ids: swapped } })); }
                catch (e) { ignored('liverefresh:swapped 이벤트 발행 실패', e); }
            }
            stamp('갱신', false);
        }

        function tick() {
            if (blocked()) return Promise.resolve(false);
            inFlight = true;
            return global.fetch(cfg.url, { credentials: 'same-origin', headers: { 'X-Requested-With': 'LiveRefresh' } })
                .then(function (r) {
                    if (r.status === 401 || r.status === 403) {
                        if (global.SessionTimeout && global.SessionTimeout.notifyExpired) global.SessionTimeout.notifyExpired('live-refresh');
                        throw new Error('세션 만료(HTTP ' + r.status + ')');
                    }
                    if (!r.ok) {
                        return r.text().then(function (t) {
                            // DB 연결 불가(503 DB_UNAVAILABLE) — 이전 화면을 유지하고 라벨·배너 스트립으로 알린다
                            if (global.Common && global.Common.isDbUnavailable && global.Common.isDbUnavailable(r.status, t)) {
                                var e = new Error(global.Common.DB_UNAVAILABLE_MESSAGE);
                                e.dbUnavailable = true;
                                global.Common.noteDbUnavailable();
                                throw e;
                            }
                            throw new Error('HTTP ' + r.status);
                        });
                    }
                    return r.text();
                })
                .then(function (html) { apply(html); if (dbDown) { dbDown = false; if (global.Common && global.Common.noteDbAvailable) global.Common.noteDbAvailable(); } return true; })
                .catch(function (e) {
                    failed('부분 갱신 실패 — 이전 화면을 유지한다', e);
                    if (e && e.dbUnavailable) { dbDown = true; stamp('갱신 실패 · DB 연결 불가', true); }
                    else stamp('갱신 실패', true);
                    return false;
                })
                .then(function (ok) { inFlight = false; return ok; });
        }

        if (global.SessionTimeout && global.SessionTimeout.managedInterval) {
            handle = global.SessionTimeout.managedInterval(tick, interval);
        } else {
            failed('SessionTimeout 미로드 — 자동 갱신을 켜지 않는다(배너가 세션 타이머를 싣는다)', null);
        }
        return {
            refreshNow: tick,
            stop: function () { if (handle) handle.stop(); },
            intervalMs: interval
        };
    }

    global.LiveRefresh = { start: start, morph: morph };
})(window);
