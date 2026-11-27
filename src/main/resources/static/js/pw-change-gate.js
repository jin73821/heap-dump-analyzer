/*
 * pw-change-gate.js — 접속 중 '비밀번호 초기화' 변경 모달 (2026-11-27).
 *
 * 관리자가 계정 관리에서 '비밀번호 초기화'를 누르면 users.password_change_required 가 켜진다.
 *   - 로그아웃 상태였다면: 다음 로그인 때 /login/password 모달(새 + 확인)이 받는다(PasswordChangeController).
 *   - 이미 접속 중이라면: 배너의 상태 폴링(/api/system/status 의 passwordChangeRequired)이 이 모달을 띄운다.
 *     접속 세션이라 현재 비밀번호를 함께 받는다(현재 + 새 + 확인) — 저장은 /api/account/password(본인 변경, 표시 해제).
 *
 * 모달은 닫을 수 없다(바깥 클릭·Esc 무시). 빠져나가는 길은 '로그아웃' 뿐이다.
 * ⚠ 배너는 상태 응답을 localStorage 에 캐시해 다음 페이지에서 먼저 그린다 — 이 모달은 **새 응답에서만** 켠다
 *   (fragments/banner.html 의 fetchBannerStatus). 캐시로 켜면 바꾼 뒤에도 옛 값으로 다시 뜬다.
 * 화면마다 common.css 가 실리지 않을 수 있어(analyze 등) 스타일은 banner.html 의 .gb-pw-* 에 둔다.
 */
(function (global) {
    'use strict';
    var doc = global.document;
    var _open = false, _done = false, _busy = false;

    function byId(id) { return doc.getElementById(id); }
    function logIgnored(tag, e) { if (global.Common && global.Common.logIgnored) global.Common.logIgnored(tag, e); }

    /** fetchJSON 오류에서 사람이 읽을 문구를 꺼낸다 — 서버 /api/** 오류는 {success,code,error}(함정 14). */
    function errText(e) {
        if (e && e.sessionExpired) return e.message;
        if (e && e.body) {
            try {
                var b = JSON.parse(e.body);
                if (b && (b.error || b.message)) return b.error || b.message;
            } catch (x) { logIgnored('[PwChangeGate] 오류 본문 — JSON 아님', x); }
        }
        return (e && e.dbUnavailable) ? e.message : '비밀번호를 변경하지 못했습니다.';
    }

    function build() {
        var ov = doc.createElement('div');
        ov.className = 'gb-pw-ov';
        ov.id = 'gbPwChange';
        ov.setAttribute('role', 'dialog');
        ov.setAttribute('aria-modal', 'true');
        ov.setAttribute('aria-labelledby', 'gbPwTtl');
        ov.innerHTML =
            '<div class="gb-pw-box">'
            + '<div class="gb-pw-ttl" id="gbPwTtl">비밀번호 변경 필요</div>'
            + '<div class="gb-pw-why">관리자가 이 계정의 비밀번호를 초기화했습니다.<br>새 비밀번호를 정해야 계속 이용할 수 있습니다.</div>'
            + '<div class="gb-pw-err" id="gbPwErr" role="alert" hidden></div>'
            + '<form id="gbPwForm" autocomplete="off">'
            + '<label class="gb-pw-lbl" for="gbPwCur">현재 비밀번호</label>'
            + '<input class="gb-pw-in" type="password" id="gbPwCur" autocomplete="current-password" required>'
            + '<label class="gb-pw-lbl" for="gbPwNew">새 비밀번호</label>'
            + '<input class="gb-pw-in" type="password" id="gbPwNew" autocomplete="new-password" required>'
            + '<label class="gb-pw-lbl" for="gbPwCf">새 비밀번호 확인</label>'
            + '<input class="gb-pw-in" type="password" id="gbPwCf" autocomplete="new-password" required>'
            + '<div class="gb-pw-mis" id="gbPwMis" hidden>새 비밀번호가 일치하지 않습니다.</div>'
            + '<div class="gb-pw-hint">최소 8자 이상 · 영문/숫자/특수문자 각각 1자 이상 포함 · 현재 비밀번호와 달라야 합니다.</div>'
            + '<div class="gb-pw-btns">'
            + '<button type="button" class="gb-pw-out" id="gbPwLogout">로그아웃</button>'
            + '<button type="submit" class="gb-pw-save" id="gbPwSave" disabled>비밀번호 변경</button>'
            + '</div></form></div>';
        doc.body.appendChild(ov);

        var cur = byId('gbPwCur'), np = byId('gbPwNew'), cf = byId('gbPwCf'), save = byId('gbPwSave'), mis = byId('gbPwMis');
        function validate() {
            var match = np.value === cf.value;
            mis.hidden = !(cf.value.length > 0 && !match);
            save.disabled = _busy || !(cur.value.length > 0 && np.value.length >= 8 && cf.value.length > 0 && match);
        }
        [cur, np, cf].forEach(function (el) { el.addEventListener('input', validate); });
        // 닫을 수 없는 모달 — Esc 를 삼키고, Tab 은 모달 안에서만 돈다
        ov.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); return; }
            if (e.key !== 'Tab') return;
            var f = Array.prototype.filter.call(ov.querySelectorAll('input,button'), function (x) { return !x.disabled; });
            if (!f.length) return;
            var first = f[0], last = f[f.length - 1];
            if (e.shiftKey && doc.activeElement === first) { e.preventDefault(); last.focus(); }
            else if (!e.shiftKey && doc.activeElement === last) { e.preventDefault(); first.focus(); }
        });
        byId('gbPwForm').addEventListener('submit', function (e) {
            e.preventDefault();
            if (save.disabled) return;
            submit(cur.value, np.value, cf.value, validate);
        });
        byId('gbPwLogout').addEventListener('click', logout);
        return ov;
    }

    function showErr(msg) {
        var el = byId('gbPwErr');
        if (!el) return;
        el.textContent = msg || '';
        el.hidden = !msg;
    }

    function submit(cur, np, cf, revalidate) {
        if (!global.Common || !global.Common.fetchJSON) return;
        _busy = true;
        var save = byId('gbPwSave');
        save.disabled = true; save.textContent = '변경 중…';
        showErr('');
        global.Common.fetchJSON('/api/account/password', {
            method: 'POST',
            body: JSON.stringify({ currentPassword: cur, newPassword: np, confirmPassword: cf })
        }).then(function () {
            _done = true;
            hide();
            if (global.Common.toast) global.Common.toast('비밀번호를 변경했습니다.', 'success');
        }).catch(function (e) {
            showErr(errText(e));
        }).then(function () {
            _busy = false;
            if (save) save.textContent = '비밀번호 변경';
            revalidate();
        });
    }

    function logout() {
        var f = doc.createElement('form');
        f.method = 'post';
        f.action = (global.Common && global.Common.appPath) ? global.Common.appPath('/logout') : '/logout';
        if (global.Common && global.Common.appendCsrfToForm) global.Common.appendCsrfToForm(f);
        doc.body.appendChild(f);
        f.submit();
    }

    function show() {
        if (_open) return;
        var ov = byId('gbPwChange') || build();
        ov.classList.add('open');
        _open = true;
        var cur = byId('gbPwCur');
        if (cur) cur.focus();
    }

    function hide() {
        var ov = byId('gbPwChange');
        if (ov) ov.parentNode.removeChild(ov);
        _open = false;
    }

    /** 배너 상태 폴링이 새 응답마다 부른다. 바꾼 뒤(_done)에는 늦게 온 옛 응답으로 다시 띄우지 않는다. */
    function update(required) {
        if (!required) {
            _done = false;                             // 서버가 해제를 확인 — 이후 다시 초기화되면 또 띄운다
            if (_open && !_busy) hide();               // 관리자가 '비밀번호 수정' 으로 직접 정하면 표시가 풀린다
            return;
        }
        if (!_done) show();
    }

    global.PwChangeGate = { update: update };
})(typeof window !== 'undefined' ? window : this);
