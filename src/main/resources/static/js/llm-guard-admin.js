/* ──────────────────────────────────────────────────────────────
 * /settings/llm 탭 전환 + 민감정보 탐지(LLM Guard) 관리 — 관리자 전용 (2026-11-26)
 *
 * 이 파일은 관리자에게만 링크된다(th:if isAdmin). 일반 사용자 HTML 에는 탭 마크업도 이 스크립트도 없다.
 * 정규식 예시처럼 Thymeleaf 가 인라인 표현식으로 오인할 수 있는 안내문은 템플릿이 아니라 여기에 둔다.
 * API 는 모두 /api/admin/llm-guard/** (SecurityConfig ADMIN + CSRF).
 * ────────────────────────────────────────────────────────────── */
(function () {
    'use strict';

    var API = '/api/admin/llm-guard';
    var esc = function (s) { return Common.escHtml(s == null ? '' : String(s)); };
    var toast = function (m, t) { Common.toast(m, t); };
    function ignored(tag, e) { Common.logIgnored('[LlmGuardAdmin] ' + tag, e); }
    function failed(tag, e) { Common.logError('[LlmGuardAdmin] ' + tag, e); }

    /** fetchJSON 오류에서 서버 한국어 문구를 꺼낸다(400/403/500 은 원문 JSON 이 메시지에 들어 있다 — 함정 14). */
    function errMsg(e) {
        if (!e) return '요청에 실패했습니다.';
        if (e.sessionExpired || e.rateLimited || e.dbUnavailable) return e.message;
        if (e.body) {
            try {
                var d = JSON.parse(e.body);
                if (d && d.error) return d.error;
            } catch (pe) { ignored('오류 본문 파싱 — JSON 아님', pe); }
        }
        if (e.status) return '요청에 실패했습니다 (HTTP ' + e.status + ')';
        return e.message || '요청에 실패했습니다.';
    }

    var ACTION_LABEL = { CONFIRM_REQUESTED: '확인 요청', CONFIRMED: '확인 후 전송', BLOCKED: '차단', MASKED: '출력 가림' };
    var ACTION_CLASS = { CONFIRM_REQUESTED: 'lg-b-confirm', CONFIRMED: 'lg-b-ok', BLOCKED: 'lg-b-block', MASKED: 'lg-b-mask' };
    var TYPE_LABEL = { BUILTIN: '내장 검증', REGEX: '정규식', KEYWORD: '키워드' };
    /**
     * 유형 열 — 내장 여부도 여기서 드러낸다(이름 옆 '내장' 배지는 기본 11행 전부에 붙어 변별력이 없어 뺐다).
     * 내장 키워드 규칙은 matchType 이 KEYWORD 라 따로 '내장 키워드' 로 적어야 사용자 키워드 규칙과 구분된다.
     */
    function typeLabel(r) {
        if (r.builtin && r.matchType === 'KEYWORD') return '내장 키워드';
        return TYPE_LABEL[r.matchType] || r.matchType;
    }
    var FEATURE_LABEL = { chat: 'AI 채팅', 'heap-chat': '힙 분석 채팅', 'heap-ai': '힙 AI 분석', 'compare-ai': '비교 AI',
        'gc-ai': 'GC AI', 'thread-ai': '스레드 AI', 'heap-thread-ai': '힙 스레드 AI', 'core-ai': '코어 AI', system: '시스템', other: '기타' };

    // ── 탭 ────────────────────────────────────────────────────
    var loaded = {};
    var TAB_IDS = ['config', 'guard', 'guard-log'];

    function activate(id, focus) {
        if (TAB_IDS.indexOf(id) < 0) id = 'config';
        TAB_IDS.forEach(function (t) {
            var tab = document.getElementById('tab-' + t);
            var panel = document.getElementById('panel-' + t);
            var on = t === id;
            if (tab) {
                tab.classList.toggle('active', on);
                tab.setAttribute('aria-selected', on ? 'true' : 'false');
                tab.tabIndex = on ? 0 : -1;
                if (on && focus) tab.focus();
            }
            if (panel) panel.classList.toggle('active', on);
        });
        var hash = id === 'config' ? '' : '#' + id;
        if ((location.hash || '') !== hash) {
            history.replaceState(null, '', location.pathname + location.search + hash);
        }
        if (loaded[id]) return;
        loaded[id] = true;
        if (id === 'guard') { loadSettings(); loadRules(); }
        if (id === 'guard-log') { loadStats(); loadRuleOptions(); loadLogs(0); }
    }

    function bindTabs() {
        var tabs = document.querySelectorAll('.tabs .tab[data-tab]');
        tabs.forEach(function (tab, i) {
            tab.addEventListener('click', function () { activate(tab.getAttribute('data-tab')); });
            tab.addEventListener('keydown', function (ev) {
                var k = ev.key;
                if (k !== 'ArrowRight' && k !== 'ArrowLeft' && k !== 'Home' && k !== 'End') return;
                ev.preventDefault();
                var n = tabs.length;
                var j = k === 'Home' ? 0 : k === 'End' ? n - 1 : (i + (k === 'ArrowRight' ? 1 : n - 1)) % n;
                activate(tabs[j].getAttribute('data-tab'), true);
            });
        });
        window.addEventListener('hashchange', function () { activate((location.hash || '').replace('#', '')); });
        activate((location.hash || '').replace('#', '') || 'config');
    }

    // ── 탐지 설정 ─────────────────────────────────────────────
    function loadSettings() {
        Common.fetchJSON(API + '/settings').then(function (d) {
            document.getElementById('lgInputCheck').checked = !!d.inputCheckEnabled;
            document.getElementById('lgOutputMask').checked = !!d.outputMaskEnabled;
            document.getElementById('lgOutputInstr').checked = !!d.outputInstructionEnabled;
            document.getElementById('lgSettingsState').textContent = '이력 보관 ' + d.retentionDays + '일';
        }).catch(function (e) { failed('설정 조회', e); toast('탐지 설정을 불러오지 못했습니다: ' + errMsg(e), 'error'); });
    }

    function saveSettings(btn) {
        btn.disabled = true;
        Common.fetchJSON(API + '/settings', {
            method: 'POST',
            body: JSON.stringify({
                inputCheckEnabled: document.getElementById('lgInputCheck').checked,
                outputMaskEnabled: document.getElementById('lgOutputMask').checked,
                outputInstructionEnabled: document.getElementById('lgOutputInstr').checked
            })
        }).then(function (d) {
            if (d.success !== true) throw new Error(d.error || '저장 실패');
            toast(d.persisted ? '탐지 설정을 저장했습니다.' : (d.warning || '적용됐지만 저장에 실패했습니다.'),
                d.persisted ? 'success' : 'error');
        }).catch(function (e) {
            failed('설정 저장', e);
            toast('저장 실패: ' + errMsg(e), 'error');
            loadSettings();
        }).then(function () { btn.disabled = false; });
    }

    // ── 규칙 표 ───────────────────────────────────────────────
    var rules = [];

    function loadRules() {
        return Common.fetchJSON(API + '/rules').then(function (d) {
            rules = (d && d.items) || [];
            renderRules();
            fillRuleOptions();
        }).catch(function (e) {
            failed('규칙 조회', e);
            document.getElementById('lgRuleBody').innerHTML =
                '<tr><td colspan="7" class="lg-empty">규칙을 불러오지 못했습니다: ' + esc(errMsg(e)) + '</td></tr>';
        });
    }

    function inputBadge(r) {
        if (!r.checkInput) return '<span class="lg-badge lg-b-off">점검 안 함</span>';
        return r.inputAction === 'BLOCK'
            ? '<span class="lg-badge lg-b-block">차단</span>'
            : '<span class="lg-badge lg-b-confirm">확인 후 전송</span>';
    }

    function renderRules() {
        var body = document.getElementById('lgRuleBody');
        if (!rules.length) {
            body.innerHTML = '<tr><td colspan="7" class="lg-empty">등록된 규칙이 없습니다.</td></tr>';
            return;
        }
        body.innerHTML = rules.map(function (r) {
            return '<tr class="' + (r.enabled ? '' : 'lg-off') + '" data-id="' + esc(r.id) + '">'
                + '<td class="lg-c-on"><label class="lg-switch" title="사용"><input type="checkbox" class="tog lg-rule-on"'
                + (r.enabled ? ' checked' : '') + ' aria-label="' + esc(r.name) + ' 사용"><span class="tog-track"></span></label></td>'
                + '<td><span class="lg-rule-name">' + esc(r.name) + '</span>'
                + (r.description ? '<div class="lg-rule-desc">' + esc(r.description) + '</div>' : '') + '</td>'
                + '<td>' + esc(r.category) + '</td>'
                + '<td>' + esc(typeLabel(r)) + '</td>'
                + '<td>' + inputBadge(r) + '</td>'
                + '<td class="lg-c-out">' + (r.checkOutput ? '<span class="lg-badge lg-b-mask">가림</span>' : '<span class="lg-badge lg-b-off">안 함</span>') + '</td>'
                + '<td class="lg-c-act"><button type="button" class="lg-btn-sm lg-rule-edit">수정</button>'
                // 내장 규칙도 버튼은 보인다(열 정렬·발견성) — 서버가 BUILTIN_READONLY 로 거부하므로 누르면 안내만 한다
                + (r.builtin
                    ? '<button type="button" class="lg-btn-sm lg-danger lg-rule-del" aria-disabled="true"'
                        + ' title="내장 규칙은 삭제할 수 없습니다. 사용을 끄세요.">삭제</button>'
                    : '<button type="button" class="lg-btn-sm lg-danger lg-rule-del">삭제</button>') + '</td>'
                + '</tr>';
        }).join('');
    }

    function ruleById(id) {
        for (var i = 0; i < rules.length; i++) if (String(rules[i].id) === String(id)) return rules[i];
        return null;
    }

    function onRuleTableClick(ev) {
        var tr = ev.target.closest('tr[data-id]');
        if (!tr) return;
        var r = ruleById(tr.getAttribute('data-id'));
        if (!r) return;
        if (ev.target.closest('.lg-rule-edit')) openModal(r, ev.target.closest('button'));
        else if (ev.target.closest('.lg-rule-del')) deleteRule(r);
    }

    function onRuleTableChange(ev) {
        if (!ev.target.classList.contains('lg-rule-on')) return;
        var tr = ev.target.closest('tr[data-id]');
        var r = ruleById(tr.getAttribute('data-id'));
        var on = ev.target.checked;
        ev.target.disabled = true;
        Common.fetchJSON(API + '/rules/' + encodeURIComponent(r.id), {
            method: 'PUT', body: JSON.stringify({ enabled: on })
        }).then(function (d) {
            if (d.success !== true) throw new Error(d.error || '변경 실패');
            r.enabled = on;
            tr.classList.toggle('lg-off', !on);
            toast((on ? '사용: ' : '사용 안 함: ') + r.name, 'success');
        }).catch(function (e) {
            failed('규칙 사용 변경', e);
            ev.target.checked = !on;
            toast('변경 실패: ' + errMsg(e), 'error');
        }).then(function () { ev.target.disabled = false; });
    }

    function deleteRule(r) {
        if (r.builtin) { toast('내장 규칙은 삭제할 수 없습니다. 쓰지 않으려면 사용을 끄세요: ' + r.name, 'error'); return; }
        if (!window.confirm('규칙 "' + r.name + '" 을(를) 삭제할까요?\n이미 남은 탐지 이력은 지워지지 않습니다.')) return;
        Common.fetchJSON(API + '/rules/' + encodeURIComponent(r.id), { method: 'DELETE' }).then(function (d) {
            if (d.success !== true) throw new Error(d.error || '삭제 실패');
            toast('삭제했습니다: ' + r.name, 'success');
            loadRules();
        }).catch(function (e) { failed('규칙 삭제', e); toast('삭제 실패: ' + errMsg(e), 'error'); });
    }

    // ── 규칙 모달 ─────────────────────────────────────────────
    var modalTrigger = null;
    var editing = null;
    var HINT_REGEX = '자바 정규식입니다. 이름 붙은 그룹 <code>(?&lt;v&gt;…)</code> 이 있으면 그 부분만 가립니다. '
        + '예) <code>사번[:=]\\s*(?&lt;v&gt;[0-9]{6})</code> → <code>사번: 1*****</code><br>'
        + '500자 이하. 실행이 200ms 를 넘으면 입력은 확인 후 전송으로 처리하고 출력 가림에서는 건너뜁니다. '
        + '중첩 수량자 <code>(a+)+</code> 처럼 느린 패턴은 피하세요.';
    var HINT_KEYWORD = '한 줄에 단어 하나. 대소문자를 구분하지 않습니다. 영문·숫자 단어는 다른 단어 안에서는 잡지 않습니다. '
        + '실명처럼 패턴으로 잡을 수 없는 값을 등록하세요. 출력 가림을 켜면 첫 글자만 남기고 가립니다(<code>홍**</code>).';
    var HINT_BUILTIN = '내장 규칙은 Java 검증기(Luhn·생년월일 등)로 판정합니다. 판정 방식은 바꿀 수 없습니다.';

    function $(id) { return document.getElementById(id); }

    function syncTypeUi() {
        var type = editing && editing.builtin ? editing.matchType : $('lgRuleType').value;
        var hint = $('lgRuleHint');
        var label = $('lgRulePatternLabel');
        if (type === 'BUILTIN') { label.textContent = '판정 방식'; hint.innerHTML = HINT_BUILTIN; }
        else if (type === 'REGEX') { label.textContent = '정규식'; hint.innerHTML = HINT_REGEX; }
        else { label.textContent = '단어 목록 (한 줄에 하나)'; hint.innerHTML = HINT_KEYWORD; }
        $('lgRulePattern').readOnly = type === 'BUILTIN';
        $('lgDraftText').closest('.lg-draft').hidden = type === 'BUILTIN' || (editing && editing.builtin);
    }

    function showErr(msg) {
        var el = $('lgRuleErr');
        el.textContent = msg || '';
        el.hidden = !msg;
    }

    function openModal(r, trigger) {
        editing = r || null;
        modalTrigger = trigger || document.activeElement;
        var builtin = !!(r && r.builtin);
        // 내장 규칙은 분류·유형·설명이 잠긴다 — 제목으로 이유를 알린다
        $('lgRuleTitle').textContent = !r ? '규칙 추가' : (builtin ? '내장 규칙 수정' : '규칙 수정');
        $('lgRuleId').value = r ? r.id : '';
        $('lgRuleName').value = r ? r.name : '';
        $('lgRuleCat').value = r ? r.category : '개인식별';
        $('lgRuleType').value = r && r.matchType !== 'BUILTIN' ? r.matchType : 'KEYWORD';
        $('lgRulePattern').value = r ? (r.pattern || '') : '';
        $('lgRuleEnabled').checked = r ? !!r.enabled : true;
        $('lgRuleIn').checked = r ? !!r.checkInput : true;
        $('lgRuleOut').checked = r ? !!r.checkOutput : true;
        var act = r ? r.inputAction : 'CONFIRM';
        document.querySelectorAll('input[name="lgRuleAction"]').forEach(function (x) { x.checked = x.value === act; });
        $('lgRuleDesc').value = r && r.description ? r.description : '';
        $('lgRuleCat').disabled = builtin;
        $('lgRuleType').disabled = !!r;   // 유형은 새로 만들 때만 고른다
        $('lgRuleDesc').disabled = builtin;
        $('lgDraftText').value = '';
        $('lgDraftResult').hidden = true;
        showErr('');
        syncTypeUi();
        $('lgRuleModal').classList.add('open');
        setTimeout(function () { $('lgRuleName').focus(); }, 0);
    }

    function closeModal() {
        $('lgRuleModal').classList.remove('open');
        editing = null;
        if (modalTrigger && typeof modalTrigger.focus === 'function' && document.contains(modalTrigger)) {
            modalTrigger.focus();
        }
        modalTrigger = null;
    }

    function formPayload() {
        var act = document.querySelector('input[name="lgRuleAction"]:checked');
        var p = {
            name: $('lgRuleName').value.trim(),
            enabled: $('lgRuleEnabled').checked,
            checkInput: $('lgRuleIn').checked,
            checkOutput: $('lgRuleOut').checked,
            inputAction: act ? act.value : 'CONFIRM'
        };
        var builtin = editing && editing.builtin;
        if (!builtin) {
            p.category = $('lgRuleCat').value;
            p.matchType = editing ? editing.matchType : $('lgRuleType').value;
            p.description = $('lgRuleDesc').value;
        }
        if (!builtin || editing.matchType === 'KEYWORD') p.pattern = $('lgRulePattern').value;
        return p;
    }

    function saveRule() {
        var p = formPayload();
        if (!p.name) { showErr('이름을 입력하세요.'); $('lgRuleName').focus(); return; }
        if (p.pattern !== undefined && !p.pattern.trim()) { showErr('패턴(단어 목록 또는 정규식)을 입력하세요.'); $('lgRulePattern').focus(); return; }
        var btn = $('lgRuleSave');
        btn.disabled = true;
        showErr('');
        var url = editing ? API + '/rules/' + encodeURIComponent(editing.id) : API + '/rules';
        Common.fetchJSON(url, { method: editing ? 'PUT' : 'POST', body: JSON.stringify(p) }).then(function (d) {
            if (d.success !== true) throw new Error(d.error || '저장 실패');
            toast('규칙을 저장했습니다: ' + p.name, 'success');
            closeModal();
            loadRules();
        }).catch(function (e) {
            failed('규칙 저장', e);
            showErr(errMsg(e));
        }).then(function () { btn.disabled = false; });
    }

    function runDraft() {
        var text = $('lgDraftText').value;
        if (!text.trim()) { $('lgDraftText').focus(); return; }
        var p = formPayload();
        var rule = { matchType: p.matchType || 'KEYWORD', pattern: p.pattern || '', inputAction: p.inputAction,
            checkInput: true, checkOutput: true, category: p.category || '기타', name: p.name || '초안' };
        var btn = $('lgDraftRun');
        btn.disabled = true;
        Common.fetchJSON(API + '/test', { method: 'POST', body: JSON.stringify({ text: text, rule: rule }) })
            .then(function (d) { renderTestResult($('lgDraftResult'), d); })
            .catch(function (e) {
                failed('초안 시험', e);
                var el = $('lgDraftResult');
                el.hidden = false;
                el.innerHTML = '<span class="lg-badge lg-b-block">오류</span> ' + esc(errMsg(e));
            }).then(function () { btn.disabled = false; });
    }

    // ── 시험 결과 ─────────────────────────────────────────────
    function verdictBadge(v) {
        if (v === 'BLOCK') return '<span class="lg-badge lg-b-block">차단</span>';
        if (v === 'CONFIRM') return '<span class="lg-badge lg-b-confirm">확인 후 전송</span>';
        return '<span class="lg-badge lg-b-ok">통과</span>';
    }

    function renderTestResult(el, d) {
        var inItems = (d.inputFindings || []).map(function (f) {
            return '<li>' + esc(f.rule) + ' ' + f.count + '건 '
                + (f.action === 'BLOCK' ? '<span class="lg-badge lg-b-block">차단</span>' : '<span class="lg-badge lg-b-confirm">확인</span>')
                + (f.timeout ? ' <span class="lg-badge lg-b-off">시간 초과</span>' : '') + '</li>';
        }).join('');
        var outItems = (d.outputFindings || []).map(function (f) {
            return esc(f.rule) + ' ' + f.count + '건';
        }).join(' · ');
        el.innerHTML = '<div><span class="lg-test-k">입력 판정</span>' + verdictBadge(d.inputVerdict) + '</div>'
            + (inItems ? '<ul>' + inItems + '</ul>' : '')
            + '<div style="margin-top:8px"><span class="lg-test-k">출력 가림</span>'
            + (outItems || '<span class="lg-muted">가린 값 없음</span>') + '</div>'
            + '<pre>' + esc(d.maskedText) + '</pre>'
            + (d.truncated ? '<div class="lg-muted">앞 20,000자만 시험했습니다.</div>' : '');
        el.hidden = false;
    }

    function runTest(btn) {
        var text = $('lgTestText').value;
        if (!text.trim()) { $('lgTestText').focus(); return; }
        btn.disabled = true;
        Common.fetchJSON(API + '/test', { method: 'POST', body: JSON.stringify({ text: text }) })
            .then(function (d) { renderTestResult($('lgTestResult'), d); })
            .catch(function (e) { failed('규칙 시험', e); toast('시험 실패: ' + errMsg(e), 'error'); })
            .then(function () { btn.disabled = false; });
    }

    // ── 탐지 이력 ─────────────────────────────────────────────
    var logPage = 0;
    var LOG_SIZE = 20;

    function loadStats() {
        Common.fetchJSON(API + '/stats?days=30').then(function (d) {
            var ev = d.events || {};
            $('lgStatConfirm').textContent = (ev.CONFIRM_REQUESTED || 0).toLocaleString();
            $('lgStatConfirmed').textContent = (ev.CONFIRMED || 0).toLocaleString();
            $('lgStatBlocked').textContent = (ev.BLOCKED || 0).toLocaleString();
            $('lgStatMasked').textContent = (ev.MASKED || 0).toLocaleString();
        }).catch(function (e) { failed('통계 조회', e); });
    }

    function fillRuleOptions() {
        var sel = $('lgRule');
        if (!sel) return;
        var cur = sel.value;
        sel.innerHTML = '<option value="">전체</option>' + rules.map(function (r) {
            return '<option value="' + esc(r.id) + '">' + esc(r.name) + '</option>';
        }).join('');
        sel.value = cur;
    }

    function loadRuleOptions() {
        if (rules.length) { fillRuleOptions(); return; }
        loadRules();
    }

    function filterParams() {
        var q = [];
        var add = function (k, v) { if (v) q.push(k + '=' + encodeURIComponent(v)); };
        add('from', $('lgFrom').value);
        add('to', $('lgTo').value);
        add('user', $('lgUser').value.trim());
        add('direction', $('lgDir').value);
        add('action', $('lgAction').value);
        add('ruleId', $('lgRule').value);
        return q.join('&');
    }

    function loadLogs(page) {
        logPage = Math.max(0, page || 0);
        var qs = filterParams();
        var body = $('lgLogBody');
        body.innerHTML = '<tr><td colspan="8" class="lg-empty">불러오는 중…</td></tr>';
        Common.fetchJSON(API + '/logs?page=' + logPage + '&size=' + LOG_SIZE + (qs ? '&' + qs : '')).then(function (d) {
            var items = d.items || [];
            if (!items.length) {
                body.innerHTML = '<tr><td colspan="8" class="lg-empty">조건에 맞는 탐지 이력이 없습니다.</td></tr>';
            } else {
                body.innerHTML = items.map(function (l) {
                    return '<tr>'
                        + '<td class="lg-time">' + esc(l.detectedAt) + '</td>'
                        + '<td>' + esc(l.username || '-') + (l.clientIp ? '<div class="lg-rule-desc">' + esc(l.clientIp) + '</div>' : '') + '</td>'
                        + '<td>' + (l.direction === 'INPUT' ? '<span class="lg-badge lg-b-in">입력</span>' : '<span class="lg-badge lg-b-out">출력</span>') + '</td>'
                        + '<td><span class="lg-badge ' + (ACTION_CLASS[l.action] || 'lg-b-off') + '">' + esc(ACTION_LABEL[l.action] || l.action) + '</span></td>'
                        + '<td>' + esc(FEATURE_LABEL[l.feature] || l.feature || '-') + '</td>'
                        + '<td>' + esc(l.ruleName || '-') + '</td>'
                        + '<td class="lg-num">' + esc(l.matchCount) + '</td>'
                        + '<td class="lg-sample">' + esc(l.sample || '') + '</td>'
                        + '</tr>';
                }).join('');
            }
            var total = d.totalElements || 0;
            $('lgLogSummary').textContent = '총 ' + total.toLocaleString() + '행 · 규칙마다 1행, 같은 탐지는 같은 시각 · 보관 ' + d.retentionDays + '일';
            renderPager(d.page || 0, d.totalPages || 0);
        }).catch(function (e) {
            failed('이력 조회', e);
            body.innerHTML = '<tr><td colspan="8" class="lg-empty">이력을 불러오지 못했습니다: ' + esc(errMsg(e)) + '</td></tr>';
            $('lgLogPager').innerHTML = '';
        });
    }

    /** admin/users.html 의 renderServerPager 와 같은 모양(이전 · 1 … 현재±2 … 마지막 · 다음). */
    function renderPager(current, total) {
        var pager = $('lgLogPager');
        if (total <= 1) { pager.innerHTML = ''; return; }
        var html = '<button type="button" data-p="' + (current - 1) + '"' + (current === 0 ? ' disabled' : '') + '>‹ 이전</button>';
        var start = Math.max(0, current - 2);
        var end = Math.min(total - 1, current + 2);
        if (start > 0) {
            html += '<button type="button" data-p="0">1</button>';
            if (start > 1) html += '<span class="ellipsis">…</span>';
        }
        for (var i = start; i <= end; i++) {
            html += '<button type="button" data-p="' + i + '" class="' + (i === current ? 'active' : '') + '"'
                + (i === current ? ' aria-current="page"' : '') + '>' + (i + 1) + '</button>';
        }
        if (end < total - 1) {
            if (end < total - 2) html += '<span class="ellipsis">…</span>';
            html += '<button type="button" data-p="' + (total - 1) + '">' + total + '</button>';
        }
        html += '<button type="button" data-p="' + (current + 1) + '"' + (current >= total - 1 ? ' disabled' : '') + '>다음 ›</button>';
        pager.innerHTML = html;
    }

    /** CSV — 서버가 만드는 작은 내보내기라 blob 방식(함정 19). */
    function exportCsv(btn) {
        var qs = filterParams();
        btn.disabled = true;
        fetch(API + '/logs/export' + (qs ? '?' + qs : ''), { credentials: 'same-origin' }).then(function (r) {
            if (r.status === 401) throw new Error('로그인 세션이 만료되었습니다. 다시 로그인해 주세요.');
            if (!r.ok) return r.text().then(function (t) { throw new Error(t || ('HTTP ' + r.status)); });
            var name = Common.filenameFromDisposition(r.headers.get('Content-Disposition')) || 'llm-guard-log.csv';
            var truncated = r.headers.get('X-Truncated') === 'true';
            return r.blob().then(function (b) {
                var url = URL.createObjectURL(b);
                var a = document.createElement('a');
                a.href = url;
                a.download = name;
                document.body.appendChild(a);
                a.click();
                a.remove();
                setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
                toast(truncated ? 'CSV 를 받았습니다(최대 50,000행까지만 담았습니다).' : 'CSV 를 받았습니다.', 'success');
            });
        }).catch(function (e) {
            failed('CSV 내보내기', e);
            toast('CSV 내보내기 실패: ' + e.message, 'error');
        }).then(function () { btn.disabled = false; });
    }

    // ── 연결 ─────────────────────────────────────────────────
    function bind() {
        bindTabs();
        $('lgSaveSettings').addEventListener('click', function () { saveSettings(this); });
        $('lgAddRule').addEventListener('click', function () { openModal(null, this); });
        $('lgRuleBody').addEventListener('click', onRuleTableClick);
        $('lgRuleBody').addEventListener('change', onRuleTableChange);
        $('lgRunTest').addEventListener('click', function () { runTest(this); });
        $('lgRuleType').addEventListener('change', syncTypeUi);
        $('lgRuleCancel').addEventListener('click', closeModal);
        $('lgRuleSave').addEventListener('click', saveRule);
        $('lgDraftRun').addEventListener('click', runDraft);
        $('lgDraftText').addEventListener('keydown', function (ev) {
            if (ev.key === 'Enter') { ev.preventDefault(); runDraft(); }
        });
        $('lgRuleModal').addEventListener('mousedown', function (ev) { if (ev.target === this) closeModal(); });
        document.addEventListener('keydown', function (ev) {
            if (ev.key === 'Escape' && $('lgRuleModal').classList.contains('open')) closeModal();
        });
        $('lgLogFilter').addEventListener('submit', function (ev) { ev.preventDefault(); loadLogs(0); });
        $('lgReset').addEventListener('click', function () {
            $('lgLogFilter').reset();
            loadLogs(0);
        });
        $('lgExport').addEventListener('click', function () { exportCsv(this); });
        $('lgLogPager').addEventListener('click', function (ev) {
            var b = ev.target.closest('button[data-p]');
            if (b && !b.disabled) loadLogs(parseInt(b.getAttribute('data-p'), 10));
        });
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bind);
    else bind();
})();
