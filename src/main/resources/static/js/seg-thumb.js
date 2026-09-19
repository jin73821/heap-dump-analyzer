/**
 * seg-thumb.js — 세그먼트 토글의 '미끄러지는 선택 표시' + 전환 내용 슬라이드 (window.SegThumb)
 *
 * 사용: SegThumb.attach(토글 컨테이너, { active: '[aria-pressed="true"]' })   // History 는 { active: '.active' }
 *       SegThumb.slideIn(새로 보인 요소들, dir)   // dir: 오른쪽 탭으로 가면 1, 왼쪽이면 -1
 *
 * - 페이지의 토글 로직은 그대로 둔다. 모듈이 버튼의 class·aria-pressed 변화를 감지(MutationObserver)해 흰 선택 표시를
 *   눌린 버튼 자리로 옮긴다 — 페이지가 선택을 바꾸는 방법(클릭·저장값 복원·URL)과 무관하게 따라간다.
 * - 버튼 자체 배경은 선택 표시가 자리를 잡은 뒤(.seg-thumb-ready)에만 끈다 — 스크립트가 없어도 종전처럼 선택이 보인다.
 * - 첫 배치는 움직이지 않는다(트랜지션은 첫 배치 두 프레임 뒤부터) — 저장된 선택으로 열 때 왼쪽 끝에서 날아오지 않게.
 * - OS '동작 줄이기'(prefers-reduced-motion)면 이동·슬라이드 모두 끈다.
 * - CSS 는 자체 주입. 사용처: 대시보드 탐지 현황 · 현황판 알람 필터·최근 분석 탭 · History Detections.
 */
(function (global) {
    'use strict';

    var TAG = '[SegThumb]';
    function ignored(where, e) { if (global.Common) global.Common.logIgnored(TAG + ' ' + where, e); }

    function injectStyle() {
        if (document.getElementById('segThumbStyle')) return;
        var st = document.createElement('style');
        st.id = 'segThumbStyle';
        st.textContent = ''
            + '.seg-thumb-host{position:relative}'
            + '.seg-thumb-host>.seg-thumb{position:absolute;left:0;top:0;width:0;height:0;border-radius:6px;background:#fff;'
            + 'box-shadow:0 1px 2px rgba(0,0,0,.08),0 1px 3px rgba(0,0,0,.04);pointer-events:none;opacity:0;z-index:0}'
            + '.seg-thumb-host>:not(.seg-thumb){position:relative;z-index:1}'
            + '.seg-thumb-host.seg-thumb-ready>.seg-thumb{opacity:1}'
            /* 선택 표시가 배경 역할을 넘겨받는다 — 페이지마다 선택 스타일 선택자가 달라 표시(.seg-thumb-on)로 가리킨다 */
            + '.seg-thumb-host.seg-thumb-ready>.seg-thumb-on{background:transparent!important;box-shadow:none!important}'
            + '.seg-thumb-host.seg-thumb-anim>.seg-thumb{transition:transform .28s cubic-bezier(.4,0,.2,1),'
            + 'width .28s cubic-bezier(.4,0,.2,1),height .28s cubic-bezier(.4,0,.2,1)}'
            + '@keyframes segSlideIn{from{opacity:0;transform:translateX(var(--seg-from,0))}to{opacity:1;transform:none}}'
            + '.seg-slide-in{animation:segSlideIn .26s cubic-bezier(.4,0,.2,1) both}'
            + '@media (prefers-reduced-motion:reduce){.seg-thumb-host.seg-thumb-anim>.seg-thumb{transition:none}.seg-slide-in{animation:none}}';
        document.head.appendChild(st);
        // 슬라이드가 끝나면 표시를 뗀다 — 남겨 두면 다음 전환에서 다시 붙일 때까지 무해하지만 DOM 을 깨끗이 둔다
        document.addEventListener('animationend', function (ev) {
            if (ev.animationName === 'segSlideIn' && ev.target.classList) ev.target.classList.remove('seg-slide-in');
        });
    }

    function motionOff() {
        return !!(global.matchMedia && global.matchMedia('(prefers-reduced-motion: reduce)').matches);
    }

    function setClass(el, cls, on) {
        // 같은 값이어도 classList 조작은 속성 변경(=MutationObserver 기록)을 만든다 — 바뀔 때만 건드려 감지 루프를 막는다
        if (el.classList.contains(cls) !== on) el.classList.toggle(cls, on);
    }

    function attach(host, opts) {
        if (!host) return null;
        if (host._segThumb) return host._segThumb;
        opts = opts || {};
        var activeSel = opts.active || '[aria-pressed="true"]';
        injectStyle();

        var thumb = document.createElement('span');
        thumb.className = 'seg-thumb';
        thumb.setAttribute('aria-hidden', 'true');
        host.insertBefore(thumb, host.firstChild);
        setClass(host, 'seg-thumb-host', true);
        var placed = false;

        function place() {
            var on = null, kids = host.children;
            for (var i = 0; i < kids.length; i++) {
                if (kids[i] === thumb) continue;
                var hit = !on && kids[i].matches(activeSel);
                if (hit) on = kids[i];
                setClass(kids[i], 'seg-thumb-on', hit);
            }
            // 선택이 없거나 숨겨져 폭을 잴 수 없으면 선택 표시를 감춘다(버튼 자체 배경이 다시 보인다)
            if (!on || !on.offsetWidth) { setClass(host, 'seg-thumb-ready', false); return; }
            thumb.style.width = on.offsetWidth + 'px';
            thumb.style.height = on.offsetHeight + 'px';
            thumb.style.transform = 'translate(' + on.offsetLeft + 'px,' + on.offsetTop + 'px)';
            thumb.style.borderRadius = global.getComputedStyle(on).borderRadius;
            setClass(host, 'seg-thumb-ready', true);
            if (!placed) {
                placed = true;
                requestAnimationFrame(function () { requestAnimationFrame(function () { setClass(host, 'seg-thumb-anim', true); }); });
            }
        }

        // 선택 변경 — 페이지가 class/aria-pressed 를 바꾸면 여기서 따라간다
        new MutationObserver(place).observe(host, { attributes: true, subtree: true, attributeFilter: ['class', 'aria-pressed'] });
        // 버튼 폭 변화(선택 글꼴 굵기·웹폰트·반응형 줄바꿈·숨겨졌다 보임)
        if (global.ResizeObserver) new global.ResizeObserver(place).observe(host);
        global.addEventListener('resize', place);
        if (document.fonts && document.fonts.ready) document.fonts.ready.then(place, function (e) { ignored('웹폰트 대기 실패', e); });
        place();

        var handle = { update: place, element: host };
        host._segThumb = handle;
        return handle;
    }

    /** 새로 보인 내용을 이동 방향에서 페이드로 들인다. 연속 전환에도 처음부터 다시 돈다. */
    function slideIn(els, dir) {
        if (!els || motionOff()) return;
        injectStyle();
        for (var i = 0; i < els.length; i++) {
            var el = els[i];
            el.classList.remove('seg-slide-in');
            void el.offsetWidth;
            el.style.setProperty('--seg-from', ((dir || 0) * 10) + 'px');
            el.classList.add('seg-slide-in');
        }
    }

    global.SegThumb = { attach: attach, slideIn: slideIn };
})(window);
