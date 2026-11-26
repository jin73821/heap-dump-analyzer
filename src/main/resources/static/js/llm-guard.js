/* ──────────────────────────────────────────────────────────────
 * window.LlmGuard — 민감정보 탐지(LLM Guard) 사용자 화면 모듈 (2026-11-26)
 *
 *   LlmGuard.isGuardCode(code)        SENSITIVE_INPUT_CONFIRM | SENSITIVE_INPUT_BLOCKED 인가
 *   LlmGuard.confirmInput(guard)      → Promise<boolean>  true = '그래도 전송'(CONFIRM 일 때만)
 *   LlmGuard.isOpen()                 확인 창이 떠 있는가(떠 있는 동안 전송을 막는다)
 *   LlmGuard.attachBadge(el, og)      채팅 말풍선 아래 "개인정보로 보이는 n건을 가렸습니다" 배지
 *   LlmGuard.noteOutput(respOrGuard)  JSON AI 결과의 outputGuard 안내 토스트(자체 스타일)
 *
 * CSS 는 /css/llm-guard.css — analyze·compare 는 common.css 를 싣지 않으므로 이 모듈은 홀로 완결된다.
 * 서버가 이미 가린 값만 다루며, 원문은 이 모듈로 들어오지 않는다.
 * ────────────────────────────────────────────────────────────── */
(function () {
    'use strict';

    var CODES = { SENSITIVE_INPUT_CONFIRM: 1, SENSITIVE_INPUT_BLOCKED: 1 };
    var SHIELD = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" '
        + 'stroke-linejoin="round" aria-hidden="true"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>';
    var WARN = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" '
        + 'stroke-linejoin="round" aria-hidden="true"><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/>'
        + '<line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>';
    var BLOCK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" '
        + 'stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"/><line x1="4.9" y1="4.9" x2="19.1" y2="19.1"/></svg>';

    function esc(s) {
        return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }
    function logIgnored(tag, e) {
        if (window.Common && typeof Common.logIgnored === 'function') Common.logIgnored('[LlmGuard] ' + tag, e);
        else if (window.console && console.debug) console.debug('[LlmGuard] ' + tag, e);
    }

    var openState = null;

    function isGuardCode(code) { return !!(code && CODES[code]); }
    function isOpen() { return !!openState; }

    /**
     * 확인/차단 창. CONFIRM 은 '취소'(기본 포커스)·'그래도 전송', BLOCK 은 '확인' 하나.
     * Esc·바깥 클릭은 취소. 닫히면 열기 전 포커스로 돌려준다.
     */
    function confirmInput(guard) {
        guard = guard || {};
        if (openState) return Promise.resolve(false);
        var block = guard.verdict === 'BLOCK';
        return new Promise(function (resolve) {
            var prevFocus = document.activeElement;
            var ov = document.createElement('div');
            ov.className = 'lgu-ov';
            var titleId = 'lguTitle' + Date.now();
            var items = (guard.findings || []).map(function (f) {
                var badge = f.action === 'BLOCK'
                    ? '<span class="lgu-badge lgu-b-block">차단</span>'
                    : '<span class="lgu-badge lgu-b-confirm">확인 필요</span>';
                var cnt = f.timeout ? '(검사 시간 초과)' : (f.count + '건');
                return '<li><span class="lgu-rule">' + esc(f.rule) + '</span><span class="lgu-count">' + esc(cnt) + '</span>'
                    + badge + (f.sample ? '<span class="lgu-sample">' + esc(f.sample) + '</span>' : '') + '</li>';
            }).join('');
            ov.innerHTML = '<div class="lgu-box ' + (block ? 'lgu-block' : 'lgu-confirm') + '" role="alertdialog" aria-modal="true" '
                + 'aria-labelledby="' + titleId + '">'
                + '<div class="lgu-head"><span class="lgu-icon">' + (block ? BLOCK : WARN) + '</span>'
                + '<h2 class="lgu-title" id="' + titleId + '">' + (block ? '전송하지 않았습니다' : '민감정보로 보이는 내용이 있습니다') + '</h2></div>'
                + '<p class="lgu-msg">' + (block
                    ? '개인신용정보·중요정보가 포함되어 전송하지 않았습니다. 해당 내용을 지우고 다시 보내 주세요.'
                    : '입력에 개인신용정보·중요정보로 보이는 내용이 있습니다. 그대로 AI 에 보낼지 확인해 주세요. 보낸 내용은 기록됩니다.') + '</p>'
                + (items ? '<ul class="lgu-list">' + items + '</ul>' : '')
                + (guard.imageNote ? '<p class="lgu-note">' + esc(guard.imageNote) + '</p>' : '')
                + '<div class="lgu-btns">'
                + (block
                    ? '<button type="button" class="lgu-btn lgu-btn-ok" data-act="cancel">확인</button>'
                    : '<button type="button" class="lgu-btn" data-act="cancel">취소</button>'
                      + '<button type="button" class="lgu-btn lgu-btn-go" data-act="go">그래도 전송</button>')
                + '</div></div>';

            function close(result) {
                if (!openState) return;
                document.removeEventListener('keydown', onKey, true);
                openState = null;
                if (ov.parentNode) ov.parentNode.removeChild(ov);
                if (prevFocus && typeof prevFocus.focus === 'function' && document.contains(prevFocus)) {
                    try { prevFocus.focus(); } catch (e) { logIgnored('포커스 복원 실패', e); }
                }
                resolve(result);
            }
            function onKey(ev) {
                if (ev.key === 'Escape') { ev.preventDefault(); ev.stopPropagation(); close(false); return; }
                if (ev.key === 'Tab') {   // 창 안에서만 돈다
                    var btns = ov.querySelectorAll('button');
                    if (!btns.length) return;
                    var first = btns[0], last = btns[btns.length - 1];
                    if (ev.shiftKey && document.activeElement === first) { ev.preventDefault(); last.focus(); }
                    else if (!ev.shiftKey && document.activeElement === last) { ev.preventDefault(); first.focus(); }
                    else if (!ov.contains(document.activeElement)) { ev.preventDefault(); first.focus(); }
                }
            }
            ov.addEventListener('click', function (ev) {
                var b = ev.target.closest('button[data-act]');
                if (b) { close(b.getAttribute('data-act') === 'go'); return; }
                if (ev.target === ov) close(false);
            });
            document.addEventListener('keydown', onKey, true);
            openState = { close: close };
            document.body.appendChild(ov);
            var initial = ov.querySelector('button[data-act="cancel"]');
            if (initial) initial.focus();
        });
    }

    function guardOf(x) {
        if (!x) return null;
        if (x.outputGuard) return x.outputGuard;
        if (typeof x.count === 'number' && x.items) return x;
        return null;
    }

    function badgeText(og) {
        return og.message || ('개인정보로 보이는 ' + og.count + '건을 가렸습니다');
    }

    /** 채팅 말풍선 아래 배지. el 은 말풍선을 감싼 wrapper(세로 flex). */
    function attachBadge(el, og) {
        og = guardOf(og);
        if (!el || !og || !og.count) return null;
        var b = document.createElement('div');
        b.className = 'lgu-out-badge';
        b.setAttribute('role', 'note');
        b.innerHTML = SHIELD + '<span></span>';
        b.lastChild.textContent = badgeText(og);
        // 시각 줄(.ai-chat-msg-time / .chat-msg-time) 바로 위에 단다 — 화면마다 클래스명이 달라 접미사로 찾는다
        var time = null;
        for (var i = 0; i < el.children.length; i++) {
            if (/(^|\s)[\w-]*msg-time(\s|$)/.test(el.children[i].className)) { time = el.children[i]; break; }
        }
        if (time) el.insertBefore(b, time); else el.appendChild(b);
        return b;
    }

    var toastTimer = null;
    /** JSON AI 결과 안내 — 자체 스타일 토스트(common.css 없이도 보인다). */
    function noteOutput(resp) {
        var og = guardOf(resp);
        if (!og || !og.count) return false;
        var old = document.querySelector('.lgu-toast');
        if (old && old.parentNode) old.parentNode.removeChild(old);
        if (toastTimer) clearTimeout(toastTimer);
        var t = document.createElement('div');
        t.className = 'lgu-toast';
        t.setAttribute('role', 'status');
        t.innerHTML = SHIELD + '<span></span>';
        t.lastChild.textContent = badgeText(og) + ' — AI 응답에서 개인정보로 보이는 값을 서버가 가렸습니다.';
        document.body.appendChild(t);
        toastTimer = setTimeout(function () {
            t.classList.add('lgu-hide');
            setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 400);
        }, 5000);
        return true;
    }

    window.LlmGuard = {
        isGuardCode: isGuardCode,
        confirmInput: confirmInput,
        isOpen: isOpen,
        attachBadge: attachBadge,
        noteOutput: noteOutput
    };
})();
