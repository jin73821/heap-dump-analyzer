/* name-tip.js — 말줄임된 이름의 전체 보기 + 클릭 고정 + 복사 (2026-11-03, Files 파일명)
 *
 *   var tip = NameTip.attach({ root: tbody, selector: '.fname', watch: 이름 열 헤더 });
 *   tip.refresh()   표시 행이 바뀐 뒤 — 잘린 이름에만 포인터·키보드 초점을 준다
 *   tip.close()     재렌더 전에 — 교체된 행을 가리킨 채 남지 않게
 *
 * krds-tooltip.js 와 모양은 같지만 따로 둔 이유: 그쪽은 pointer-events:none 인 설명용이라 글자를
 * 선택할 수 없다. 여기서는 고정하면 팝오버 안에서 선택·복사한다.
 *
 *   마우스 올림 / 키보드 초점 → 전체 이름 표시 (잘린 이름만)
 *   클릭 / Enter / Space     → 고정: 이름 전체 선택 + [복사] + [닫기]
 *   Esc / 바깥 클릭 / [닫기]  → 닫힘 (키보드로 고정했으면 이름으로 초점 복귀)
 *
 * ⚠ 복사는 execCommand 폴백이 필수다 — 이 앱은 사내 HTTP 로 서비스되고 navigator.clipboard 는 보안
 *   컨텍스트(HTTPS·localhost)에만 있다. localhost 검증만으로는 폴백 경로가 돌지 않는다.
 * ⚠ 팝오버는 body 직속 + fixed 다 — 표가 overflow 컨테이너 안이라 안에 두면 잘린다.
 */
(function(global) {
    var SHOW_DELAY_MS = 180;   // 표를 훑고 지나갈 때 깜빡이지 않게
    var GAP = 8;               // 이름과 팝오버 사이, 화면 가장자리 여백

    var el = null, textEl = null, hintEl = null, copyBtn = null, closeBtn = null;
    var anchor = null, pinned = false, viaKeyboard = false, showTimer = null, copyTimer = null;

    function ignored(tag, e) {
        if (global.Common && Common.logIgnored) Common.logIgnored('[NameTip] ' + tag, e);
    }

    function injectStyle() {
        if (document.getElementById('nameTipStyle')) return;
        var st = document.createElement('style');
        st.id = 'nameTipStyle';
        st.textContent =
            '.name-tip{position:fixed;z-index:1100;box-sizing:border-box;max-width:min(560px,calc(100vw - 16px));padding:8px 12px;' +
            'background:#1F2937;color:#F9FAFB;font-size:12px;line-height:1.5;border-radius:8px;' +
            'box-shadow:0 4px 12px rgba(0,0,0,.22);opacity:0;visibility:hidden;transition:opacity .12s ease;pointer-events:none}' +
            '.name-tip.show{opacity:1;visibility:visible}' +
            '.name-tip.pinned{pointer-events:auto}' +
            '.name-tip::after{content:"";position:absolute;left:var(--name-tip-x,50%);transform:translateX(-50%);border:6px solid transparent}' +
            '.name-tip.pos-top::after{top:100%;border-top-color:#1F2937}' +
            '.name-tip.pos-bottom::after{bottom:100%;border-bottom-color:#1F2937}' +
            '.name-tip-text{word-break:break-all;white-space:pre-wrap}' +
            '.name-tip.pinned .name-tip-text{user-select:text;-webkit-user-select:text;cursor:text}' +
            '.name-tip-text::selection{background:#6366F1;color:#fff}' +
            '.name-tip-hint{margin-top:3px;font-size:11px;color:#9CA3AF}' +
            '.name-tip.pinned .name-tip-hint{display:none}' +
            '.name-tip-actions{display:none;gap:6px;justify-content:flex-end;margin-top:8px}' +
            '.name-tip.pinned .name-tip-actions{display:flex}' +
            '.name-tip-btn{padding:3px 10px;font:inherit;font-size:11px;font-weight:600;line-height:18px;border-radius:5px;' +
            'border:1px solid #4B5563;background:transparent;color:#F9FAFB;cursor:pointer}' +
            '.name-tip-btn:hover{background:#374151}' +
            '.name-tip-btn:focus-visible{outline:2px solid #A5B4FC;outline-offset:1px}' +
            '.name-tip-btn.primary{background:#4F46E5;border-color:#4F46E5}' +
            '.name-tip-btn.primary:hover{background:#4338CA}' +
            '.name-tip-on{cursor:pointer}' +
            '.name-tip-on:focus-visible{outline:2px solid #6366F1;outline-offset:1px;border-radius:2px}' +
            '@media (prefers-reduced-motion: reduce){.name-tip{transition:none}}';
        document.head.appendChild(st);
    }

    function build() {
        if (el) return;
        injectStyle();
        el = document.createElement('div');
        el.id = 'nameTip';
        el.className = 'name-tip';
        el.setAttribute('role', 'tooltip');
        el.setAttribute('aria-hidden', 'true');
        textEl = document.createElement('div');
        textEl.className = 'name-tip-text';
        hintEl = document.createElement('div');
        hintEl.className = 'name-tip-hint';
        hintEl.textContent = '클릭하면 고정되어 복사할 수 있습니다';
        var actions = document.createElement('div');
        actions.className = 'name-tip-actions';
        copyBtn = document.createElement('button');
        copyBtn.type = 'button';
        copyBtn.className = 'name-tip-btn primary';
        closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'name-tip-btn';
        closeBtn.textContent = '닫기';
        actions.appendChild(copyBtn);
        actions.appendChild(closeBtn);
        el.appendChild(textEl);
        el.appendChild(hintEl);
        el.appendChild(actions);
        document.body.appendChild(el);
        // 고정 때 전체 선택해 두므로, 그대로 드래그하면 선택 범위가 아니라 글자 끌기가 시작된다 — 눌렀을 때 풀어 준다
        textEl.addEventListener('mousedown', function() {
            var sel = window.getSelection();
            if (sel) sel.removeAllRanges();
        });
        copyBtn.addEventListener('click', onCopy);
        closeBtn.addEventListener('click', function() { close(true); });
    }

    /** 실제로 말줄임됐는가 — scrollWidth 는 정수 반올림이라 1px 미만 잘림을 놓친다. */
    function isTruncated(node) {
        var box = node.getBoundingClientRect();
        if (!box.width) return false;
        if (window.getComputedStyle(node).textOverflow !== 'ellipsis') return false;
        if (window.getComputedStyle(node).whiteSpace !== 'nowrap') return false;   // 모바일 카드는 줄바꿈으로 전부 보인다
        var range = document.createRange();
        range.selectNodeContents(node);
        return range.getBoundingClientRect().width > box.width + 0.5;
    }

    function place() {
        var r = anchor.getBoundingClientRect();
        el.style.left = '0px';
        el.style.top = '0px';
        var tw = el.offsetWidth, th = el.offsetHeight;
        var left = Math.max(GAP, Math.min(r.left, window.innerWidth - tw - GAP));
        var top = r.top - th - GAP, pos = 'top';
        if (top < GAP) { top = r.bottom + GAP; pos = 'bottom'; }
        el.classList.remove('pos-top', 'pos-bottom');
        el.classList.add('pos-' + pos);
        // 긴 이름은 앞부분만 보이므로 화살표는 이름 왼쪽 근처를 가리킨다(말줄임 끝이 아니라)
        el.style.setProperty('--name-tip-x', Math.max(14, Math.min(r.left + 24, r.right) - left) + 'px');
        el.style.left = left + 'px';
        el.style.top = top + 'px';
    }

    function show(node) {
        build();
        anchor = node;
        textEl.textContent = (node.textContent || '').trim();
        el.classList.add('show');
        el.setAttribute('aria-hidden', 'false');
        node.setAttribute('aria-describedby', 'nameTip');
        place();
    }

    function setPinned(on) {
        pinned = on;
        el.classList.toggle('pinned', on);
        el.setAttribute('role', on ? 'dialog' : 'tooltip');
        if (on) el.setAttribute('aria-label', '전체 파일명');
        else el.removeAttribute('aria-label');
        resetCopyLabel();
    }

    function selectText() {
        var sel = window.getSelection();
        if (sel) sel.selectAllChildren(textEl);
    }

    function pin(node, keyboard) {
        clearTimeout(showTimer);
        if (anchor && anchor !== node) close(false);
        show(node);
        viaKeyboard = !!keyboard;
        setPinned(true);
        place();          // 버튼 줄만큼 높이가 늘었다
        selectText();     // 고정 즉시 Ctrl+C 로도 복사되게
        if (keyboard) copyBtn.focus();
    }

    function close(restoreFocus) {
        clearTimeout(showTimer);
        if (!anchor) return;
        var node = anchor, wasKeyboard = viaKeyboard, wasPinned = pinned;
        node.removeAttribute('aria-describedby');
        anchor = null;
        viaKeyboard = false;
        if (wasPinned) {
            var sel = window.getSelection();
            if (sel && sel.anchorNode && el.contains(sel.anchorNode)) sel.removeAllRanges();
        }
        setPinned(false);
        el.classList.remove('show');
        el.setAttribute('aria-hidden', 'true');
        if (restoreFocus && wasKeyboard && document.contains(node)) node.focus();
    }

    // ── 복사 ──────────────────────────────────────────
    function resetCopyLabel() {
        clearTimeout(copyTimer);
        if (copyBtn) copyBtn.textContent = '복사';
    }

    function legacyCopy(text) {
        var ta = document.createElement('textarea');
        ta.value = text;
        ta.setAttribute('readonly', '');
        ta.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0';
        document.body.appendChild(ta);
        ta.select();
        var ok = false;
        try {
            ok = document.execCommand('copy');
        } catch (e) {
            ignored('execCommand 복사', e);
        }
        document.body.removeChild(ta);
        return ok;
    }

    function onCopy() {
        var text = textEl.textContent;
        function report(ok) {
            if (!anchor) return;   // 그사이 닫혔다
            selectText();          // 폴백이 선택을 옮겼으니 되돌린다 — 실패해도 Ctrl+C 로 복사할 수 있게
            copyBtn.textContent = ok ? '복사됨' : 'Ctrl+C 로 복사';
            clearTimeout(copyTimer);
            copyTimer = setTimeout(resetCopyLabel, ok ? 1500 : 3000);
        }
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(function() { report(true); }, function(e) {
                ignored('clipboard API', e);
                report(legacyCopy(text));
            });
        } else {
            report(legacyCopy(text));
        }
    }

    // ── 배선 ──────────────────────────────────────────
    function attach(cfg) {
        var root = cfg.root, selector = cfg.selector;
        function nameOf(target) {
            var node = target && target.closest ? target.closest(selector) : null;
            return node && root.contains(node) ? node : null;
        }

        root.addEventListener('mouseover', function(e) {
            var node = nameOf(e.target);
            if (!node || pinned || node === anchor || !isTruncated(node)) return;
            clearTimeout(showTimer);
            showTimer = setTimeout(function() { if (!pinned) show(node); }, SHOW_DELAY_MS);
        });
        root.addEventListener('mouseout', function(e) {
            var node = nameOf(e.target);
            if (!node || nameOf(e.relatedTarget) === node) return;
            clearTimeout(showTimer);
            if (!pinned) close(false);
        });
        root.addEventListener('focusin', function(e) {
            var node = nameOf(e.target);
            if (node && !pinned && isTruncated(node)) show(node);
        });
        root.addEventListener('focusout', function(e) {
            if (nameOf(e.target) && !pinned) close(false);
        });
        root.addEventListener('click', function(e) {
            var node = nameOf(e.target);
            if (!node || !isTruncated(node)) return;
            if (pinned && anchor === node) close(false);
            else pin(node, false);
        });
        root.addEventListener('keydown', function(e) {
            if (e.key !== 'Enter' && e.key !== ' ') return;
            var node = nameOf(e.target);
            if (!node || !isTruncated(node)) return;
            e.preventDefault();
            pin(node, true);
        });

        // 고정 중 바깥을 누르면 닫는다 — mousedown 이라 다른 이름의 click 은 새로 고정한다
        document.addEventListener('mousedown', function(e) {
            if (pinned && !el.contains(e.target) && nameOf(e.target) !== anchor) close(false);
        }, true);
        document.addEventListener('keydown', function(e) {
            if (e.key === 'Escape' && anchor) close(true);
        });
        function follow() {
            if (!anchor) return;
            var r = anchor.getBoundingClientRect();
            if (!r.width || r.bottom < 0 || r.top > window.innerHeight) close(false);
            else place();
        }
        window.addEventListener('scroll', follow, true);
        window.addEventListener('resize', follow);

        function refresh() {
            Array.prototype.forEach.call(root.querySelectorAll(selector), function(node) {
                var on = node.offsetParent !== null && isTruncated(node);
                if (on === node.classList.contains('name-tip-on')) return;
                node.classList.toggle('name-tip-on', on);
                if (on) node.setAttribute('tabindex', '0');
                else node.removeAttribute('tabindex');
            });
        }
        injectStyle();
        // 체크 열 표시·창 크기로 열 폭이 바뀌면 잘림 여부도 바뀐다
        if (cfg.watch && global.ResizeObserver) new ResizeObserver(refresh).observe(cfg.watch);
        return { refresh: refresh, close: function() { close(false); } };
    }

    global.NameTip = { attach: attach };
})(typeof window !== 'undefined' ? window : this);
