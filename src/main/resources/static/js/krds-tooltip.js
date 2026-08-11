/* krds-tooltip.js — [data-tip] 앵커드 팝오버 툴팁 공통 (KRDS component_08_05, 2026-08-09)
 *
 * history.html 에 인라인으로만 있던 구현을 공통 모듈로 추출 (settings.html 과 공유).
 * 로드만 하면 자동 활성 — 트리거에 data-tip="설명" 을 달고, 키보드 접근이 필요하면 tabindex="0" 을 준다.
 *
 * float-tooltip.js([data-tooltip], 커서 추종형) 와의 차이:
 *   - 트리거에 앵커링(위 중앙, 공간 부족 시 아래로 뒤집힘) + 화살표가 트리거를 가리킴
 *   - hover / focus(키보드) / 터치 탭 토글 / Esc / 바깥 클릭 닫기 / 스크롤·리사이즈 재배치
 *   - role="tooltip" + aria-hidden + aria-describedby 연결
 * 네이티브 title 은 표시 지연·모바일 미지원이라 미사용 (KRDS: title 속성 중복 사용 금지).
 */
(function() {
    // ── 스타일 (모듈 자립 — 소비 페이지가 CSS 를 따로 갖지 않아도 되게 함) ──
    if (!document.getElementById('krdsTipStyle')) {
        var st = document.createElement('style');
        st.id = 'krdsTipStyle';
        st.textContent =
            '.krds-tooltip-popover{position:fixed;z-index:1100;max-width:280px;padding:8px 12px;' +
            'background:#1F2937;color:#F9FAFB;font-size:12px;font-weight:400;line-height:1.5;' +
            'border-radius:8px;box-shadow:0 4px 12px rgba(0,0,0,.22);opacity:0;visibility:hidden;' +
            'transition:opacity .12s ease;pointer-events:none;word-break:keep-all;white-space:pre-wrap}' +
            '.krds-tooltip-popover.show{opacity:1;visibility:visible}' +
            '.krds-tooltip-popover::after{content:"";position:absolute;left:var(--tip-arrow-x,50%);' +
            'transform:translateX(-50%);border:6px solid transparent}' +
            '.krds-tooltip-popover.pos-top::after{top:100%;border-top-color:#1F2937}' +
            '.krds-tooltip-popover.pos-bottom::after{bottom:100%;border-bottom-color:#1F2937}';
        document.head.appendChild(st);
    }

    // 싱글턴 팝오버 — 페이지가 미리 심어둔 것이 있으면 재사용
    var tip = document.getElementById('krdsTip');
    if (!tip) {
        tip = document.createElement('div');
        tip.id = 'krdsTip';
        tip.className = 'krds-tooltip-popover';
        tip.setAttribute('role', 'tooltip');
        tip.setAttribute('aria-hidden', 'true');
        document.body.appendChild(tip);
    }

    var curTrigger = null;
    var shownAt = 0;   // 표시 시각 — 터치 탭 burst(mouseover→click) 판별용

    function showTip(trigger) {
        var text = trigger.getAttribute('data-tip');
        if (!text) return;
        if (curTrigger && curTrigger !== trigger) hideTip();
        if (curTrigger !== trigger) shownAt = Date.now();
        curTrigger = trigger;
        tip.textContent = text;
        tip.classList.add('show');
        tip.setAttribute('aria-hidden', 'false');
        trigger.setAttribute('aria-describedby', 'krdsTip');

        // 위치: 트리거 위 중앙 → 공간 부족 시 아래 (KRDS: 화면 이탈 금지)
        tip.style.left = '0px'; tip.style.top = '0px';   // 측정용 리셋
        var r = trigger.getBoundingClientRect();
        var tw = tip.offsetWidth, th = tip.offsetHeight, m = 8;
        var left = Math.max(8, Math.min(r.left + r.width / 2 - tw / 2, window.innerWidth - tw - 8));
        var top = r.top - th - m, pos = 'top';
        if (top < 8) { top = r.bottom + m; pos = 'bottom'; }
        tip.classList.remove('pos-top', 'pos-bottom');
        tip.classList.add('pos-' + pos);
        // 화살표는 트리거 중심을 향함 (뷰포트 클램프 후 보정)
        tip.style.setProperty('--tip-arrow-x', (r.left + r.width / 2 - left) + 'px');
        tip.style.left = left + 'px';
        tip.style.top = top + 'px';
    }

    function hideTip() {
        if (!curTrigger) return;
        tip.classList.remove('show');
        tip.setAttribute('aria-hidden', 'true');
        curTrigger.removeAttribute('aria-describedby');
        curTrigger = null;
    }

    // Mouseover / Mouseleave (위임 — 페이지네이션 재렌더에도 유효)
    document.addEventListener('mouseover', function(e) {
        var t = e.target.closest ? e.target.closest('[data-tip]') : null;
        if (t) showTip(t);
        else if (curTrigger) hideTip();
    });
    // Focus / Blur (키보드 접근)
    document.addEventListener('focusin', function(e) {
        var t = e.target.closest ? e.target.closest('[data-tip]') : null;
        if (t) showTip(t);
    });
    document.addEventListener('focusout', function(e) {
        if (curTrigger && e.target === curTrigger) hideTip();
    });
    // 클릭: 터치(hover 불가) 기기만 탭 토글 — 데스크톱은 hover 가 담당 (클릭 토글 시 hover 상태와 충돌).
    // 바깥 클릭 닫기는 공통.
    document.addEventListener('click', function(e) {
        var t = e.target.closest ? e.target.closest('[data-tip]') : null;
        if (t) {
            if (window.matchMedia && window.matchMedia('(hover: hover)').matches) return;
            // 터치는 탭 시 click 직전에 mouseover/focusin 을 에뮬레이션해 이미 표시된 상태 —
            // 같은 탭 burst(350ms 내)의 click 은 토글로 취급하지 않음 (표시 직후 닫힘 방지)
            if (curTrigger === t && Date.now() - shownAt > 350) hideTip(); else showTip(t);
        } else if (curTrigger) hideTip();
    });
    // Esc 닫기 (초점은 트리거에 유지 — KRDS)
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape' && curTrigger) hideTip();
    });
    // 스크롤/리사이즈: 위치 재계산 (닫기 대신 — Chrome scroll 이벤트는 비동기라
    // 스크롤 직후 hover 시 늦게 도착한 이벤트가 표시 직후의 툴팁을 닫아버리는 것 방지).
    // 트리거가 뷰포트를 벗어나면 그때 닫음.
    function repositionOrHide() {
        if (!curTrigger) return;
        var r = curTrigger.getBoundingClientRect();
        if (r.bottom < 0 || r.top > window.innerHeight) { hideTip(); return; }
        showTip(curTrigger);
    }
    window.addEventListener('scroll', repositionOrHide, true);
    window.addEventListener('resize', repositionOrHide);
})();
