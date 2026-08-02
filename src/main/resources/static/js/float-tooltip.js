/* float-tooltip.js — [data-tooltip] 플로팅 툴팁 공통 (2026-08-02)
 * servers.html / server-detail.html 에 복붙돼 있던 IIFE 통합 (servers 판 기준 —
 * 모바일 터치 토글/스크롤 dismiss 포함 상위 버전). 로드만 하면 자동 활성. */
// ── 플로팅 툴팁 ([data-tooltip] 속성 공통) ──────────────────────
(function() {
    var tip = document.createElement('div');
    tip.style.cssText = 'display:none;position:fixed;z-index:9999;background:#1F2937;color:#fff;' +
        'font-size:12px;line-height:1.5;padding:7px 11px;border-radius:6px;max-width:360px;' +
        'word-break:break-word;white-space:pre-wrap;box-shadow:0 3px 10px rgba(0,0,0,.25);pointer-events:none;' +
        'box-sizing:border-box';
    document.body.appendChild(tip);

    var _activeTipEl = null;

    function hideTip() {
        tip.style.display = 'none';
        _activeTipEl = null;
    }
    function showTipFor(el) {
        _activeTipEl = el;
        tip.textContent = el.getAttribute('data-tooltip');
        tip.style.maxWidth = Math.min(360, window.innerWidth - 16) + 'px';
        tip.style.display = 'block';
    }

    function pos(e) {
        var x = e.clientX + 14, y = e.clientY - 40;
        if (x + tip.offsetWidth  > window.innerWidth  - 8) x = e.clientX - tip.offsetWidth  - 14;
        if (y < 8)                                          y = e.clientY + 18;
        if (y + tip.offsetHeight > window.innerHeight - 8) y = e.clientY - tip.offsetHeight - 14;
        tip.style.left = x + 'px';
        tip.style.top  = y + 'px';
    }

    // ── 마우스 (데스크톱) ──
    document.addEventListener('mouseover', function(e) {
        var el = e.target.closest ? e.target.closest('[data-tooltip]') : null;
        if (!el || !el.getAttribute('data-tooltip')) { hideTip(); return; }
        showTipFor(el);
        pos(e);
    });
    document.addEventListener('mousemove', function(e) {
        if (tip.style.display === 'none') return;
        pos(e);
    });
    document.addEventListener('mouseout', function(e) {
        var el = e.target.closest ? e.target.closest('[data-tooltip]') : null;
        if (el && el.contains(e.relatedTarget)) return;
        hideTip();
    });

    // ── 터치 (모바일) ──
    // pointer-events:none 인 tip 위를 터치하면 아래 요소(배지)로 이벤트가 전파됨.
    // _activeTipEl 을 추적해 같은 요소 재터치 시 dismiss, 외부 터치 시 즉시 dismiss.
    document.addEventListener('touchstart', function(e) {
        var el = e.target.closest ? e.target.closest('[data-tooltip]') : null;
        if (!el || !el.getAttribute('data-tooltip')) { hideTip(); return; }
        // 현재 표시 중인 같은 요소 터치 → 토글 닫기
        if (el === _activeTipEl && tip.style.display === 'block') { hideTip(); return; }
        showTipFor(el);
        requestAnimationFrame(function() {
            var rect = el.getBoundingClientRect();
            var winW = window.innerWidth;
            var winH = window.innerHeight;
            /* 모바일: 220px 로 좁혀 3줄+ 줄바꿈 유도 → 화면 이탈 방지 */
            tip.style.right = 'auto';
            tip.style.maxWidth = (winW <= 640 ? Math.min(220, winW - 16) : Math.min(360, winW - 16)) + 'px';
            var tw = tip.offsetWidth, tipH = tip.offsetHeight;
            var x = rect.left;
            if (x + tw > winW - 8) x = winW - tw - 8;
            if (x < 8) x = 8;
            var y = rect.bottom + 8;
            if (y + tipH > winH - 8) y = rect.top - tipH - 8;
            if (y < 8) y = 8;
            tip.style.left = x + 'px';
            tip.style.top  = y + 'px';
        });
    }, { passive: true });

    // 스크롤 시 팝오버 즉시 닫기
    document.addEventListener('touchmove', function() {
        if (tip.style.display === 'block') hideTip();
    }, { passive: true });
})();
