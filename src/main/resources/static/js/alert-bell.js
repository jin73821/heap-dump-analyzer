/**
 * alert-bell.js — 알림 벨 + 최우선 알람 말풍선 (window.AlertBell)
 *
 * 사용: AlertBell.mount(buttonEl, { boardUrl: '/alerts', limit: 10, interval: 60000 })
 * - 말풍선에는 미확인 최우선 알람(Critical·OOM)만 최신 10건. 항목마다 '확인'(ACK) — ACK 는 모든 사용자가 공유한다.
 * - CSS·DOM 자체 주입이라 페이지는 버튼과 <script> 한 줄만 두면 된다(server-scan.js 의 스캔 대상 팝오버와 같은 언어:
 *   흰 배경·1px 테두리·2겹 삼각 꼬리, 버튼 오른쪽 끝 정렬, 아래 공간이 없으면 위로 뒤집힘, Esc·바깥 클릭 닫기).
 * - 배지 갱신은 SessionTimeout.managedInterval 경유 — 배경 타이머를 직접 만들지 말 것(유휴 세션 만료를 무력화한다).
 */
(function (global) {
    'use strict';

    var TAG = '[AlertBell]';
    function failed(where, e) { if (global.Common) global.Common.logError(TAG + ' ' + where, e); }
    function esc(s) { return global.Common ? global.Common.escHtml(s == null ? '' : String(s)) : ''; }

    var DOMAIN_LABEL = { heap: '힙 덤프', core: '코어덤프', gclog: 'GC 로그' };

    function injectStyle() {
        if (document.getElementById('alertBellStyle')) return;
        var st = document.createElement('style');
        st.id = 'alertBellStyle';
        st.textContent = ''
            + '.ab-bell{position:relative;padding-left:9px!important;padding-right:9px!important;cursor:pointer}'
            + '.ab-bell svg{width:16px;height:16px;stroke:currentColor;fill:none;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round;display:block}'
            + '.ab-bell[aria-expanded="true"]{background:#EFF6FF;border-color:#BFDBFE;color:#2563EB}'
            + '.ab-bell.has-alert{color:#B91C1C;border-color:#FECACA}'
            + '.ab-badge{position:absolute;top:-6px;right:-6px;min-width:17px;height:17px;padding:0 4px;box-sizing:border-box;border-radius:9px;'
            + 'background:#DC2626;color:#fff;font-size:10px;font-weight:700;line-height:17px;text-align:center;font-variant-numeric:tabular-nums;'
            + 'box-shadow:0 0 0 2px #fff}'
            + '.ab-badge[hidden]{display:none}'
            + '.ab-pop{position:fixed;z-index:10000;width:380px;max-width:calc(100vw - 16px);box-sizing:border-box;background:#fff;'
            + 'border:1px solid #E5E7EB;border-radius:10px;box-shadow:0 10px 30px rgba(15,23,42,.16),0 2px 6px rgba(15,23,42,.08);'
            + 'font-size:13px;color:#1F2937;text-align:left;font-weight:400}'
            + '.ab-pop::before,.ab-pop::after{content:"";position:absolute;left:var(--ab-arrow-x,24px);width:0;height:0;border:7px solid transparent}'
            + '.ab-pop::before{top:-15px;border-bottom-color:#E5E7EB}'
            + '.ab-pop::after{top:-13px;border-bottom-color:#fff}'
            + '.ab-pop.up::before{top:auto;bottom:-15px;border-bottom-color:transparent;border-top-color:#E5E7EB}'
            + '.ab-pop.up::after{top:auto;bottom:-13px;border-bottom-color:transparent;border-top-color:#fff}'
            /* 열기·닫기 애니메이션 — 꼬리(버튼 쪽)를 기준점으로 커지며 나타나고, 닫을 때는 짧게 사라진다.
               방향은 변수(--ab-dy)로만 바꾼다: 애니메이션 이름을 바꾸면 열리는 도중 위/아래가 뒤집힐 때 처음부터 다시 돈다 */
            + '.ab-pop{--ab-dy:-6px;transform-origin:calc(var(--ab-arrow-x,24px) + 7px) 0}'
            + '.ab-pop.up{--ab-dy:6px;transform-origin:calc(var(--ab-arrow-x,24px) + 7px) 100%}'
            + '@keyframes abPopIn{from{opacity:0;transform:translateY(var(--ab-dy)) scale(.95)}to{opacity:1;transform:none}}'
            + '@keyframes abPopOut{to{opacity:0;transform:translateY(var(--ab-dy)) scale(.97)}}'
            + '.ab-pop.ab-in{animation:abPopIn .2s cubic-bezier(.2,.8,.2,1) both}'
            + '.ab-pop.ab-out{animation:abPopOut .13s ease-in both;pointer-events:none}'
            /* 확인(ACK)한 항목은 옆으로 빠지며 접힌다 — 목록이 한 번에 툭 끊기지 않게. 음수 margin 이 flex gap 까지 거둔다 */
            + '.ab-item.ab-leaving{height:0!important;padding-top:0;padding-bottom:0;margin-bottom:-6px;border-width:0;opacity:0;'
            + 'transform:translateX(16px);overflow:hidden;pointer-events:none;transition:height .2s cubic-bezier(.4,0,.2,1),'
            + 'padding .2s cubic-bezier(.4,0,.2,1),margin .2s cubic-bezier(.4,0,.2,1),border-width .2s,opacity .15s ease,transform .2s ease}'
            /* 확인(ACK) 창 — 확인은 전체 사용자에게 공유되므로 누르기 전에 대상을 보여 주고 묻는다. 말풍선(z 10000) 위 */
            + '.ab-cf-ov{position:fixed;inset:0;z-index:10001;background:rgba(17,24,39,.45);display:flex;align-items:center;'
            + 'justify-content:center;padding:16px;animation:abCfFade .16s ease both}'
            + '.ab-cf-box{width:100%;max-width:400px;box-sizing:border-box;background:#fff;border-radius:12px;padding:20px 22px 18px;'
            + 'box-shadow:0 20px 50px rgba(15,23,42,.28);text-align:left;color:#1F2937;animation:abCfIn .2s cubic-bezier(.2,.8,.2,1) both}'
            + '@keyframes abCfFade{from{opacity:0}to{opacity:1}}'
            + '@keyframes abCfIn{from{opacity:0;transform:translateY(-10px) scale(.97)}to{opacity:1;transform:none}}'
            + '.ab-cf-ttl{font-size:15px;font-weight:700;color:#111827;margin-bottom:10px}'
            + '.ab-cf-target{padding:10px 12px;border:1px solid #FECACA;border-radius:8px;background:#FEF2F2;margin-bottom:12px}'
            + '.ab-cf-target-ttl{font-size:13px;font-weight:600;color:#7F1D1D;word-break:break-word}'
            + ".ab-cf-target-meta{margin-top:3px;font-size:11.5px;color:#6B7280;font-family:'JetBrains Mono','D2Coding',monospace;word-break:break-all}"
            + '.ab-cf-msg{font-size:13px;color:#4B5563;line-height:1.6;word-break:keep-all}'
            + '.ab-cf-note{color:#6B7280}'
            + '.ab-cf-btns{display:flex;justify-content:flex-end;gap:8px;margin-top:18px}'
            + '.ab-cf-btns button{padding:7px 18px;border-radius:7px;font-size:13px;font-weight:600;line-height:18px;font-family:inherit;'
            + 'cursor:pointer;border:1px solid transparent}'
            + '.ab-cf-cancel{background:#fff;border-color:#D1D5DB!important;color:#374151}'
            + '.ab-cf-cancel:hover{background:#F9FAFB}'
            + '.ab-cf-ok{background:#2563EB;color:#fff}'
            + '.ab-cf-ok:hover{background:#1D4ED8}'
            + '.ab-cf-btns button:focus-visible{outline:2px solid #93C5FD;outline-offset:2px}'
            + '.ab-pop:focus{outline:none}'
            + '@media (prefers-reduced-motion:reduce){.ab-pop.ab-in,.ab-pop.ab-out{animation:none}.ab-item.ab-leaving{transition:none}'
            + '.ab-cf-ov,.ab-cf-box{animation:none}}'
            + '.ab-hd{display:flex;align-items:center;gap:8px;padding:11px 14px 9px;border-bottom:1px solid #F3F4F6}'
            + '.ab-hd-ttl{font-size:13px;font-weight:700;color:#111827}'
            + '.ab-hd-cnt{font-size:11px;font-weight:700;color:#B91C1C;background:#FEE2E2;border-radius:9px;padding:1px 7px}'
            + '.ab-hd-sub{margin-left:auto;font-size:11px;color:#9CA3AF}'
            + '.ab-list{max-height:min(60vh,420px);overflow:auto;padding:8px 10px;display:flex;flex-direction:column;gap:6px}'
            + '.ab-item{display:flex;gap:8px;align-items:flex-start;padding:9px 10px;border:1px solid #FECACA;border-radius:8px;background:#FEF2F2}'
            + '.ab-item-main{flex:1 1 auto;min-width:0;color:inherit;text-decoration:none;display:block}'
            + 'a.ab-item-main:hover .ab-item-ttl{text-decoration:underline}'
            + '.ab-item-top{display:flex;align-items:center;gap:6px;font-size:10.5px;color:#6B7280}'
            + '.ab-chip{padding:1px 6px;border-radius:8px;font-weight:700;background:#fff;border:1px solid #E5E7EB;color:#374151;white-space:nowrap}'
            + '.ab-sev{padding:1px 6px;border-radius:8px;font-weight:700;background:#DC2626;color:#fff;white-space:nowrap}'
            + '.ab-item-ttl{margin-top:4px;font-size:12.5px;font-weight:600;color:#7F1D1D;line-height:1.4;word-break:break-word}'
            + '.ab-item-file{margin-top:2px;font-size:11px;color:#6B7280;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;'
            + "font-family:'JetBrains Mono','D2Coding',monospace}"
            + '.ab-ack{flex-shrink:0;align-self:center;padding:5px 10px;border-radius:6px;border:1px solid #D1D5DB;background:#fff;color:#374151;'
            + 'font-size:11.5px;font-weight:600;line-height:16px;font-family:inherit;cursor:pointer;white-space:nowrap}'
            + '.ab-ack:hover:not(:disabled){background:#F9FAFB;border-color:#9CA3AF}'
            + '.ab-ack:disabled{opacity:.55;cursor:progress}'
            + '.ab-empty{padding:26px 14px;text-align:center;font-size:12.5px;color:#6B7280}'
            + '.ab-empty.err{color:#B91C1C}'
            + '.ab-more{padding:2px 4px 0;text-align:center;font-size:11px;color:#9CA3AF}'
            + '.ab-ft{display:flex;align-items:center;gap:8px;padding:9px 12px;border-top:1px solid #F3F4F6}'
            + '.ab-ft-note{font-size:11px;color:#9CA3AF;flex:1 1 auto;min-width:0}'
            + '.ab-board{display:inline-flex;align-items:center;gap:5px;padding:6px 13px;border-radius:6px;border:1px solid transparent;background:#2563EB;color:#fff;'
            + 'font-size:12px;font-weight:600;line-height:18px;text-decoration:none;white-space:nowrap}'
            + '.ab-board:hover{opacity:.88}';
        document.head.appendChild(st);
    }

    /** OS '동작 줄이기' — 켜져 있으면 애니메이션 없이 바로 붙이고 뗀다(끝 이벤트가 오지 않으므로 기다리면 안 된다) */
    function motionOff() {
        return !!(global.matchMedia && global.matchMedia('(prefers-reduced-motion: reduce)').matches);
    }

    /**
     * 끝 이벤트를 한 번만 받는다 — 탭이 숨겨지는 등으로 이벤트가 오지 않아도 timeoutMs 뒤에는 반드시 진행한다.
     * prop: transitionend 는 속성마다 오므로 기다릴 속성(가장 긴 것)을 지정한다
     */
    function afterMotion(el, eventName, timeoutMs, done, prop) {
        var called = false;
        function fin(ev) {
            if (called || (ev && (ev.target !== el || (prop && ev.propertyName !== prop)))) return;
            called = true;
            el.removeEventListener(eventName, fin);
            done();
        }
        el.addEventListener(eventName, fin);
        setTimeout(fin, timeoutMs);
    }

    var BELL_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/>'
        + '<path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>';

    function mount(btn, opts) {
        if (!btn || btn._abMounted) return null;
        btn._abMounted = true;
        opts = opts || {};
        var boardUrl = opts.boardUrl || '/alerts';
        var limit = opts.limit || 10;
        var interval = opts.interval || 60000;

        injectStyle();
        if (!btn.querySelector('svg')) btn.insertAdjacentHTML('afterbegin', BELL_SVG);
        var badge = btn.querySelector('.ab-badge');
        if (!badge) {
            badge = document.createElement('span');
            badge.className = 'ab-badge';
            badge.hidden = true;
            btn.appendChild(badge);
        }
        btn.setAttribute('aria-haspopup', 'dialog');
        btn.setAttribute('aria-expanded', 'false');

        var pop = null;        // {el, onKey, onDown, onReflow}
        var cf = null;         // 확인 창 {ov, trigger, onKey} — 떠 있는 동안 말풍선은 닫히지 않는다
        var state = { count: 0, alerts: [], error: null, loaded: false };

        function setCount(n) {
            state.count = n > 0 ? n : 0;
            badge.hidden = state.count === 0;
            badge.textContent = state.count > 99 ? '99+' : String(state.count);
            btn.classList.toggle('has-alert', state.count > 0);
            btn.setAttribute('aria-label', state.count > 0 ? '알림 — 미확인 최우선 알람 ' + state.count + '건' : '알림 — 미확인 알람 없음');
            btn.title = state.count > 0 ? '미확인 최우선 알람 ' + state.count + '건' : '알림';
        }

        function fetchTop() {
            return global.Common.fetchJSON('/api/alerts/top?limit=' + encodeURIComponent(limit)).then(function (d) {
                // 인증 리다이렉트가 200 HTML 로 돌아오는 경우까지 걸러낸다 — 본문을 믿기 전에 success 확인
                if (!d || d.success !== true) throw new Error('알림을 불러오지 못했습니다.');
                state.alerts = d.alerts || [];
                state.error = null;
                state.loaded = true;
                setCount(d.unackedCount || 0);
                return d;
            });
        }

        function refresh() {
            return fetchTop().then(function () { if (pop) render(); }).catch(function (e) {
                failed('알림 조회 실패', e);
                state.error = e && e.sessionExpired ? '세션이 만료되었습니다. 다시 로그인하세요.' : '알림을 불러오지 못했습니다.';
                if (pop) render();
            });
        }

        function itemHtml(a) {
            var main = '<div class="ab-item-top"><span class="ab-chip">' + esc(DOMAIN_LABEL[a.domain] || a.domain) + '</span>'
                + '<span class="ab-sev">' + esc(a.severity || 'Critical') + '</span>'
                + '<span>' + esc(a.occurredAtLabel || '') + '</span></div>'
                + '<div class="ab-item-ttl">' + esc(a.title) + '</div>'
                + '<div class="ab-item-file" title="' + esc(a.filename) + '">' + esc(a.filename)
                + (a.serverName ? ' · ' + esc(a.serverName) : '') + '</div>';
            return '<div class="ab-item" data-key="' + esc(a.key) + '">'
                + (a.link ? '<a class="ab-item-main" href="' + esc(a.link) + '">' + main + '</a>' : '<div class="ab-item-main">' + main + '</div>')
                + '<button type="button" class="ab-ack" data-act="ack" title="확인 처리 — 모든 사용자의 알림에서 빠집니다">확인</button></div>';
        }

        function render() {
            if (!pop) return;
            var body;
            if (state.error) body = '<div class="ab-empty err">' + esc(state.error) + '</div>';
            else if (!state.loaded) body = '<div class="ab-empty">불러오는 중…</div>';
            else if (!state.alerts.length) body = '<div class="ab-empty">미확인 최우선 알람이 없습니다.</div>';
            else {
                body = '<div class="ab-list">' + state.alerts.map(itemHtml).join('')
                    + (state.count > state.alerts.length
                        ? '<div class="ab-more">외 ' + (state.count - state.alerts.length) + '건은 현황판에서 확인하세요</div>' : '')
                    + '</div>';
            }
            pop.el.innerHTML = '<div class="ab-hd"><span class="ab-hd-ttl">최우선 알람</span>'
                + (state.count > 0 ? '<span class="ab-hd-cnt">' + state.count + '</span>' : '')
                + '<span class="ab-hd-sub">Critical · OOM</span></div>'
                + body
                + '<div class="ab-ft"><span class="ab-ft-note">확인(ACK)은 전체 사용자에게 공유됩니다</span>'
                + '<a class="ab-board" href="' + esc(boardUrl) + '">현황판 보기</a></div>';
            place();
        }

        function place() {
            if (!pop) return;
            var el = pop.el;
            var r = btn.getBoundingClientRect();
            var w = el.offsetWidth, h = el.offsetHeight;
            var vw = window.innerWidth, vh = window.innerHeight;
            var left = Math.min(Math.max(8, r.right - w), vw - w - 8);
            var up = r.bottom + 10 + h > vh - 8 && r.top - 10 - h >= 8;
            var top = up ? r.top - 10 - h : r.bottom + 10;
            el.classList.toggle('up', up);
            el.style.left = Math.round(left) + 'px';
            el.style.top = Math.round(Math.max(8, top)) + 'px';
            var arrowX = Math.min(Math.max(12, r.left + r.width / 2 - left - 7), w - 26);
            el.style.setProperty('--ab-arrow-x', Math.round(arrowX) + 'px');
        }

        function close(restoreFocus) {
            if (!pop) return;
            closeConfirm(false);   // 말풍선 없이 확인 창만 남지 않게
            var p = pop;
            pop = null;
            document.removeEventListener('keydown', p.onKey, true);
            document.removeEventListener('mousedown', p.onDown, true);
            document.removeEventListener('touchstart', p.onDown, true);
            window.removeEventListener('resize', p.onReflow);
            window.removeEventListener('scroll', p.onReflow, true);
            btn.setAttribute('aria-expanded', 'false');
            if (restoreFocus) btn.focus();
            // 닫는 동안에는 클릭을 받지 않는다(.ab-out 의 pointer-events:none) — 그 사이 다시 열면 새 말풍선이 뜬다
            var el = p.el;
            function detach() { if (el.parentNode) el.parentNode.removeChild(el); }
            el.classList.remove('ab-in');
            if (motionOff()) { detach(); return; }
            el.classList.add('ab-out');
            afterMotion(el, 'animationend', 220, detach);
        }

        /** 확인한 항목을 접으며 뺀 뒤 done — 높이를 고정해야 0 으로의 트랜지션이 돈다 */
        function leave(row, done) {
            if (!row || motionOff()) { done(); return; }
            row.style.height = row.offsetHeight + 'px';
            void row.offsetHeight;
            row.classList.add('ab-leaving');
            afterMotion(row, 'transitionend', 260, done, 'height');
        }

        /** 말풍선 안의 해당 알람 행 — 확인 창이 떠 있는 사이 배지 갱신으로 목록이 다시 그려질 수 있어 매번 찾는다 */
        function rowOf(key) {
            if (!pop) return null;
            var rows = pop.el.querySelectorAll('.ab-item');
            for (var i = 0; i < rows.length; i++) if (rows[i].getAttribute('data-key') === key) return rows[i];
            return null;
        }

        function closeConfirm(restoreFocus) {
            if (!cf) return;
            var c = cf;
            cf = null;
            document.removeEventListener('keydown', c.onKey, true);
            if (c.ov.parentNode) c.ov.parentNode.removeChild(c.ov);
            if (restoreFocus && c.trigger && document.body.contains(c.trigger)) c.trigger.focus();
        }

        /** 확인(ACK) 전에 묻는다 — 대상 알람을 보여 주고, 기본 포커스는 취소(Enter 한 번으로 처리되지 않게) */
        function askAck(key, trigger) {
            var a = null;
            for (var i = 0; i < state.alerts.length; i++) if (state.alerts[i].key === key) { a = state.alerts[i]; break; }
            if (!a || cf) return;
            var ov = document.createElement('div');
            ov.className = 'ab-cf-ov';
            ov.innerHTML = '<div class="ab-cf-box" role="dialog" aria-modal="true" aria-labelledby="abCfTtl" aria-describedby="abCfMsg">'
                + '<div class="ab-cf-ttl" id="abCfTtl">알람 확인</div>'
                + '<div class="ab-cf-target"><div class="ab-cf-target-ttl">' + esc(a.title) + '</div>'
                + '<div class="ab-cf-target-meta">' + esc(DOMAIN_LABEL[a.domain] || a.domain) + ' · ' + esc(a.filename)
                + (a.serverName ? ' · ' + esc(a.serverName) : '') + '</div></div>'
                + '<div class="ab-cf-msg" id="abCfMsg">이 알람을 확인 처리합니다. 확인은 전체 사용자에게 공유되어 모든 사용자의 알림에서 빠집니다.'
                + '<br><span class="ab-cf-note">(현황판에서 확인을 취소할 수 있습니다)</span></div>'
                + '<div class="ab-cf-btns"><button type="button" class="ab-cf-cancel" data-cf="cancel">취소</button>'
                + '<button type="button" class="ab-cf-ok" data-cf="ok">확인 처리</button></div></div>';
            document.body.appendChild(ov);
            var cancelBtn = ov.querySelector('[data-cf="cancel"]'), okBtn = ov.querySelector('[data-cf="ok"]');
            cf = {
                ov: ov, trigger: trigger,
                onKey: function (ev) {
                    if (ev.key === 'Escape') { ev.preventDefault(); ev.stopPropagation(); closeConfirm(true); }
                    else if (ev.key === 'Tab') {   // 두 버튼 사이에서만 돈다
                        if (ev.shiftKey && document.activeElement === cancelBtn) { ev.preventDefault(); okBtn.focus(); }
                        else if (!ev.shiftKey && document.activeElement === okBtn) { ev.preventDefault(); cancelBtn.focus(); }
                    }
                }
            };
            ov.addEventListener('click', function (ev) {
                if (ev.target === ov || ev.target === cancelBtn) { closeConfirm(true); return; }
                if (ev.target === okBtn) {
                    closeConfirm(false);
                    if (pop) pop.el.focus();   // 행이 곧 사라지므로 말풍선에 포커스를 둔다
                    ack(key);
                }
            });
            document.addEventListener('keydown', cf.onKey, true);
            cancelBtn.focus();
        }

        function ack(key) {
            var row = rowOf(key);
            var ackBtn = row ? row.querySelector('[data-act="ack"]') : null;
            if (ackBtn) ackBtn.disabled = true;
            global.Common.fetchJSON('/api/alerts/ack', { method: 'POST', body: JSON.stringify({ key: key }) }).then(function (d) {
                if (!d || d.success !== true) throw new Error('확인 처리에 실패했습니다.');
                setCount(d.unackedCount || 0);
                leave(rowOf(key), function () {
                    state.alerts = state.alerts.filter(function (a) { return a.key !== key; });
                    // 10건 밖에 밀려 있던 알람을 끌어올린다 (refresh 는 실패를 스스로 처리한다)
                    if (state.count > state.alerts.length) refresh();
                    else render();
                });
            }).catch(function (e) {
                failed('ACK 실패', e);
                if (ackBtn) ackBtn.disabled = false;
                var msg = e && e.sessionExpired ? '세션이 만료되었습니다. 다시 로그인하세요.' : '확인 처리에 실패했습니다.';
                if (global.Common && global.Common.toast) global.Common.toast(msg, 'error');
            });
        }

        function open() {
            var el = document.createElement('div');
            el.className = 'ab-pop';
            el.setAttribute('role', 'dialog');
            el.setAttribute('aria-label', '최우선 알람');
            el.tabIndex = -1;   // 확인 처리 뒤 행이 사라져도 키보드 포커스가 말풍선 안에 머물게
            document.body.appendChild(el);
            pop = {
                el: el,
                // 확인 창이 떠 있으면 Esc·바깥 클릭은 확인 창 몫이다 — 말풍선까지 닫으면 무엇이 취소됐는지 헷갈린다
                onKey: function (ev) { if (cf) return; if (ev.key === 'Escape') { ev.stopPropagation(); close(true); } },
                onDown: function (ev) { if (cf) return; if (!el.contains(ev.target) && !btn.contains(ev.target)) close(false); },
                onReflow: function () { place(); }
            };
            el.addEventListener('click', function (ev) {
                var b = ev.target.closest ? ev.target.closest('[data-act="ack"]') : null;
                if (!b) return;
                ev.preventDefault();
                askAck(b.closest('.ab-item').getAttribute('data-key'), b);
            });
            document.addEventListener('keydown', pop.onKey, true);
            document.addEventListener('mousedown', pop.onDown, true);
            document.addEventListener('touchstart', pop.onDown, true);
            window.addEventListener('resize', pop.onReflow);
            window.addEventListener('scroll', pop.onReflow, true);
            btn.setAttribute('aria-expanded', 'true');
            render();                    // 위치(위/아래)를 먼저 정해야 꼬리 쪽에서 커진다
            if (!motionOff()) {
                el.classList.add('ab-in');
                afterMotion(el, 'animationend', 260, function () { el.classList.remove('ab-in'); });
            }
            refresh();   // 열 때마다 최신으로 — 다른 사용자의 확인이 반영된다
        }

        btn.addEventListener('click', function () { if (pop) close(false); else open(); });

        setCount(0);
        refresh();
        if (global.SessionTimeout && global.SessionTimeout.managedInterval) {
            global.SessionTimeout.managedInterval(function () { refresh(); }, interval);
        }
        return { refresh: refresh, close: close };
    }

    global.AlertBell = { mount: mount };
})(window);
