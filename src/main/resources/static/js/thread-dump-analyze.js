/* thread-dump-analyze.js — 스레드 덤프 결과 화면 (thread-dump/analyze.html, 2026-11-26)
 * 상태 폴링(분석 중) · 결과 조회(GET /api/thread-dump/{fn}/result) · 요약/락·정체/스레드/리포트 탭 렌더 · AI 분석 · 재분석·삭제.
 *
 * 결과 JSON 은 사전화돼 있다: tracks(스레드) → stacks(프레임 인덱스 열) → frames(문자열), 이름은 names.
 * 인덱스는 저장 크기 상한으로 줄어든 결과에서 빌 수 있으므로 조회는 전부 nameOf/stackOf/frameText 를 거친다(범위 밖이면 빈 값).
 * 폴링은 SessionTimeout.managedInterval + registerActivityGuard 경유(함정 38). 모달은 showModal/hideModal 만 거친다.
 *
 * 2.7.1 — THD_MODE === 'heap' 이면 힙 덤프의 스레드 정보로 만든 결과다(/api/thread-dump/heap/{fn}/…): 분석·재분석·삭제·폴링이 없고
 * 결과를 바로 그린다. 스레드 덤프 모드에서는 힙 덤프 수동 연결(후보 모달)·이어 붙인 구성 파일 카드가 붙는다.
 * '힙 점유' 열은 힙 모드면 tracks[].hr, 연결 모드면 연결 뷰의 memory[이름].r 을 쓴다(값이 하나도 없으면 열을 숨긴다). */
(function () {
    'use strict';

    var TAG = '[ThreadDumpAnalyze]';
    function ignored(where, e) { if (window.Common) window.Common.logIgnored(TAG + ' ' + where, e); }
    function failed(where, e)  { if (window.Common) window.Common.logError(TAG + ' ' + where, e); }
    var esc = function (s) { return window.Common ? Common.escHtml(String(s == null ? '' : s)) : String(s == null ? '' : s); };
    function byId(id) { return document.getElementById(id); }
    function fmtNum(n) { return n == null ? '–' : Number(n).toLocaleString('en-US'); }
    function fmtSec(sec) {
        if (sec == null) return '–';
        if (sec < 60) return (Math.round(sec * 10) / 10) + '초';
        if (sec < 3600) return Math.floor(sec / 60) + '분 ' + Math.round(sec % 60) + '초';
        return Math.floor(sec / 3600) + '시간 ' + Math.floor((sec % 3600) / 60) + '분';
    }
    function fmtTs(ms) {
        if (ms == null) return '';
        var d = new Date(ms);
        function p(n) { return (n < 10 ? '0' : '') + n; }
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    }

    // ── 모달 열기·닫기 연출 ───────────────────────────────────
    function showModal(ov) {
        if (!ov) return;
        ov._thdSeq = (ov._thdSeq || 0) + 1;
        ov.classList.remove('closing');
        ov.classList.add('open');
    }
    function hideModal(ov) {
        if (!isModalOpen(ov)) return;
        var seq = ov._thdSeq = (ov._thdSeq || 0) + 1;
        var fin = function () { if (ov._thdSeq === seq) ov.classList.remove('open', 'closing'); };
        if (!Common.afterMotion || Common.reduceMotion()) { fin(); return; }
        ov.classList.add('closing');
        Common.afterMotion(ov, 'animationend', 260, fin);
    }
    function isModalOpen(ov) { return !!ov && ov.classList.contains('open') && !ov.classList.contains('closing'); }

    function toast(msg, type) {
        var wrap = byId('cdToastWrap');
        if (!wrap) { alert(msg); return; }
        type = type || 'info';
        var t = document.createElement('div');
        t.className = 'cd-toast rail-' + type;
        t.setAttribute('role', 'status');
        var ic = document.createElement('span'); ic.className = 'cd-toast-icon';
        ic.textContent = type === 'success' ? '✓' : type === 'danger' ? '!' : 'ℹ';
        var m = document.createElement('span'); m.className = 'cd-toast-msg'; m.textContent = msg;
        var x = document.createElement('button'); x.className = 'cd-toast-close'; x.type = 'button'; x.setAttribute('aria-label', '닫기'); x.textContent = '×';
        var kill = function () { if (t.parentNode) t.parentNode.removeChild(t); };
        x.addEventListener('click', kill);
        t.appendChild(ic); t.appendChild(m); t.appendChild(x);
        wrap.appendChild(t);
        setTimeout(kill, 4500);
    }

    /** 서버 오류 본문 {success,code,error} 에서 사람이 읽을 문구를 꺼낸다. */
    function errMsg(e, fallback) {
        if (!e) return fallback;
        if (e.sessionExpired) return '세션이 만료되었습니다. 다시 로그인하세요.';
        if (e.dbUnavailable && Common.DB_UNAVAILABLE_MESSAGE) return Common.DB_UNAVAILABLE_MESSAGE;
        if (e.body) { try { var d = JSON.parse(e.body); if (d && (d.error || d.message)) return d.error || d.message; } catch (x) { ignored('오류 본문 파싱', x); } }
        return e.message || fallback;
    }

    var HEAP_MODE = typeof THD_MODE !== 'undefined' && THD_MODE === 'heap';
    var api = function (path) { return '/api/thread-dump/' + (HEAP_MODE ? 'heap/' : '') + encodeURIComponent(THD_FILENAME) + path; };
    function fmtBytes(b) {
        if (b == null) return '–';
        if (b < 1024) return b + ' B';
        var u = ['KB', 'MB', 'GB', 'TB'], v = b / 1024, i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return (v >= 100 ? v.toFixed(0) : v.toFixed(1)) + ' ' + u[i];
    }

    // ── 실패 코드별 안내 ──────────────────────────────────────
    var ERROR_HINTS = {
        NOT_THREAD_DUMP: 'HotSpot 계열 JVM 의 jstack · kill -3 · jcmd Thread.print 출력인지 확인하세요. 다른 종류의 파일이면 Files 화면에서 유형을 바꿀 수 있습니다.',
        UNSUPPORTED_FORMAT: '지원하지 않는 덤프 형식입니다. jstack -l <pid> 또는 jcmd <pid> Thread.print 로 다시 받아 주세요.',
        EMPTY: '파일이 비어 있습니다. 덤프를 다시 받아 올려 주세요.',
        TOO_LARGE: '압축을 푼 크기가 상한을 넘습니다. 필요한 구간만 잘라서 올리거나 threaddump.max-file-bytes 설정을 조정하세요.',
        TIMEOUT: '분석 시간 상한을 넘었습니다. 스냅샷 수를 줄여(파일을 나눠) 다시 시도하세요.',
        CANCELLED: '사용자가 취소한 분석입니다. 다시 분석할 수 있습니다.',
        FILE_MISSING: '원본 파일이 저장소에 없습니다. 파일을 다시 올려 주세요.',
        IO_ERROR: '파일을 읽는 중 오류가 났습니다. 저장소 디스크 상태를 확인한 뒤 다시 분석하세요.',
        OUT_OF_MEMORY: '서버 메모리가 부족했습니다. 다른 분석이 끝난 뒤 다시 시도하거나 파일을 나눠 올리세요.',
        INTERNAL: '분석기 내부 오류입니다. 같은 파일로 반복되면 관리자에게 서버 로그([ThreadDump] action=analyze-failed) 확인을 요청하세요.',
        INTERRUPTED: '분석 도중 서버가 재시작되었습니다. 다시 분석하면 됩니다.',
        HEAP_NOT_FOUND: '이 힙 덤프의 분석 기록이 없습니다. 힙 덤프를 먼저 분석하세요.',
        HEAP_NOT_ANALYZED: '힙 덤프 분석이 끝나지 않았습니다. 분석이 끝난 뒤 다시 여세요.',
        HEAP_THREADS_UNAVAILABLE: 'MAT 산출물에 스레드 정보(.threads·Thread Overview)가 없습니다. 힙 덤프를 재분석하면 다시 만들어집니다.'
    };
    function errorCodeOf(msg, code) {
        if (code) return code;
        var m = /^([A-Z_]+):/.exec(msg || '');
        return m ? m[1] : null;
    }
    function renderErrorHint(msg, code) {
        var el = byId('thdErrorHint');
        if (!el) return;
        var hint = ERROR_HINTS[errorCodeOf(msg, code)];
        el.textContent = hint || '';
        el.style.display = hint ? '' : 'none';
    }

    // ── 상태 폴링 (분석 중) ───────────────────────────────────
    var _pollHandle = null;
    var _analyzing = false;
    var _guarded = false;

    function startPolling() {
        if (_pollHandle) return;
        _analyzing = true;
        if (!_guarded && window.SessionTimeout && SessionTimeout.registerActivityGuard) {
            SessionTimeout.registerActivityGuard(function () { return _analyzing; });
            _guarded = true;
        }
        var fails = 0;
        var tick = function () {
            Common.fetchJSON(api('/status')).then(function (d) {
                fails = 0;
                renderProgress(d);
                if (d.status === 'SUCCESS') { stopPolling(); onAnalysisDone(); }
                else if (d.status === 'ERROR' || d.status === 'MISSING') {
                    stopPolling();
                    showError(d.error || (d.status === 'MISSING' ? '파일이 없습니다.' : '분석 실패'), d.errorCode);
                }
            }).catch(function (e) {
                if (e && e.sessionExpired) { stopPolling(); toast('세션이 만료되었습니다. 다시 로그인하세요.', 'danger'); return; }
                ignored('상태 폴링 실패', e);
                // 서버가 잠깐 응답하지 않는 것은 흔하다 — 계속 실패할 때만 알린다
                if (++fails === 10) toast('분석 상태를 확인하지 못하고 있습니다. 네트워크를 확인하세요.', 'danger');
            });
        };
        if (window.SessionTimeout && SessionTimeout.managedInterval) _pollHandle = SessionTimeout.managedInterval(tick, 1000);
        else { var id = setInterval(tick, 1000); _pollHandle = { stop: function () { clearInterval(id); } }; }
        tick();
    }
    function stopPolling() {
        _analyzing = false;
        if (_pollHandle && _pollHandle.stop) _pollHandle.stop();
        _pollHandle = null;
    }
    var PHASE_LABEL = { queued: '대기 중(앞선 분석이 끝나면 시작)', parsing: '읽는 중', saving: '저장 중', done: '완료', error: '실패' };
    function renderProgress(d) {
        var p = d.progressPct == null ? 0 : d.progressPct;
        var fill = byId('thdProgressFill'); if (fill) fill.style.width = p + '%';
        var pctEl = byId('thdProgressPct'); if (pctEl) pctEl.textContent = p + '%';
        var lines = byId('thdProgressLines'); if (lines) lines.textContent = d.lines ? fmtNum(d.lines) + '줄' : '';
        var snaps = byId('thdProgressSnaps'); if (snaps) snaps.textContent = d.snapshots ? '스냅샷 ' + d.snapshots + ' · 스레드 ' + fmtNum(d.threads) : '';
        var ph = byId('thdProgressPhase'); if (ph) ph.textContent = d.phase ? (PHASE_LABEL[d.phase] || d.phase) : '';
        var title = byId('thdProgressTitle');
        if (title && d.status === 'ANALYZING') title.textContent = d.phase === 'queued' ? '분석 순서를 기다리는 중입니다' : '스레드 덤프를 분석하는 중입니다';
        var cancel = byId('thdCancelBtn'); if (cancel && d.status === 'ANALYZING') cancel.style.display = '';
        var sp = byId('thdSpinner'); if (sp && d.status === 'ANALYZING') sp.style.display = '';
    }

    function showError(msg, code) {
        var box = byId('thdError'); if (box) box.style.display = '';
        var m = byId('thdErrorMsg'); if (m) m.textContent = msg;
        renderErrorHint(msg, code);
        var prog = byId('thdProgress'); if (prog) prog.style.display = 'none';
        var res = byId('thdResult'); if (res) res.style.display = 'none';
        setRetryDisabled(!THD_FILE_EXISTS);
    }
    function showRunning() {
        var res = byId('thdResult'); if (res) res.style.display = 'none';
        var prog = byId('thdProgress'); if (prog) prog.style.display = '';
        var err = byId('thdError'); if (err) err.style.display = 'none';
        var sp = byId('thdSpinner'); if (sp) sp.style.display = '';
        var title = byId('thdProgressTitle'); if (title) title.textContent = '분석을 시작하는 중입니다';
        var fill = byId('thdProgressFill'); if (fill) fill.style.width = '0%';
    }
    function setRetryDisabled(disabled) {
        ['thdBtnReanalyze', 'thdRetryBtn'].forEach(function (id) { var b = byId(id); if (b) b.disabled = disabled; });
    }

    /** 분석이 끝났다 — 결과를 받아 같은 화면에 그린다(새로 고침 없이). */
    function onAnalysisDone() {
        THD_STATUS = 'SUCCESS';
        var prog = byId('thdProgress'); if (prog) prog.style.display = 'none';
        setRetryDisabled(false);
        document.querySelectorAll('.status-badge').forEach(function (b) {
            b.className = 'status-badge status-success'; b.textContent = '완료';
        });
        loadAndRender();
    }

    function startAnalysis() {
        setRetryDisabled(true);
        showRunning();
        Common.fetchJSON('/api/thread-dump/analyze/' + encodeURIComponent(THD_FILENAME), { method: 'POST' })
            .then(function (d) { if (d.status === 'SUCCESS') onAnalysisDone(); else startPolling(); })
            .catch(function (e) { failed('분석 시작 실패', e); setRetryDisabled(false); showError(errMsg(e, '분석을 시작하지 못했습니다.')); });
    }
    function reanalyze() {
        setRetryDisabled(true);
        Common.fetchJSON('/api/thread-dump/reanalyze/' + encodeURIComponent(THD_FILENAME), { method: 'POST' })
            .then(function () { _aiHtml = null; _aiSevHtml = null; showRunning(); startPolling(); })
            .catch(function (e) { failed('재분석 요청 실패', e); setRetryDisabled(false); toast('재분석 실패: ' + errMsg(e, ''), 'danger'); });
    }
    function thdCancel() {
        Common.fetchJSON('/api/thread-dump/cancel/' + encodeURIComponent(THD_FILENAME), { method: 'POST' })
            .then(function (d) { toast(d.cancelled ? '취소를 요청했습니다.' : '이미 끝난 분석입니다.', 'info'); })
            .catch(function (e) { toast('취소 실패: ' + errMsg(e, ''), 'danger'); });
    }

    // ── 재분석 확인 ───────────────────────────────────────────
    function thdOpenReanalyze() {
        var has = THD_STATUS === 'SUCCESS';
        byId('thdReanalyzeTitle').textContent = has ? '스레드 덤프 재분석' : '스레드 덤프 다시 분석';
        byId('thdReanalyzeDesc').textContent = has ? '이 스레드 덤프를 처음부터 다시 분석합니다.'
            : '마지막 분석이 끝나지 않았습니다. 이 스레드 덤프를 처음부터 다시 분석합니다.';
        byId('thdReanalyzeReplace').style.display = has ? '' : 'none';
        byId('thdReanalyzeAi').style.display = has && _aiHtml ? '' : 'none';
        var btn = byId('thdReanalyzeBtn'); btn.textContent = has ? '재분석' : '다시 분석'; btn.disabled = false;
        showModal(byId('thdReanalyzeModal'));
        btn.focus();
    }
    function thdCloseReanalyze() { hideModal(byId('thdReanalyzeModal')); }
    function thdConfirmReanalyze() {
        byId('thdReanalyzeBtn').disabled = true;
        thdCloseReanalyze();
        reanalyze();
    }

    // ══ 결과 ══════════════════════════════════════════════════
    var R = null;
    var _focus = 0;

    function nameOf(t) {
        if (!R || t == null || t < 0 || t >= R.tracks.length) return '?';
        var n = R.tracks[t].n;
        return n >= 0 && n < R.names.length ? R.names[n] : '?';
    }
    /** 스레드 t 의 스냅샷 snap 에서의 스택 id(-1 = 없음). */
    function stackAt(t, snap) {
        var tr = R.tracks[t];
        if (!tr) return -1;
        var off = snap - tr.first;
        return off >= 0 && off < tr.k.length && tr.k[off] != null ? tr.k[off] : -1;
    }
    function stateAt(t, snap) {
        var tr = R.tracks[t];
        if (!tr) return '-';
        var off = snap - tr.first;
        return off >= 0 && off < tr.s.length ? tr.s.charAt(off) : '-';
    }
    /** 그 스냅샷에 스택이 없으면 마지막으로 스택이 있던 스냅샷의 것. */
    function anyStack(t, snap) {
        var sid = stackAt(t, snap);
        if (sid >= 0) return sid;
        var tr = R.tracks[t];
        if (!tr) return -1;
        for (var i = tr.k.length - 1; i >= 0; i--) if (tr.k[i] != null && tr.k[i] >= 0) return tr.k[i];
        return -1;
    }
    function framesOf(sid) {
        if (sid == null || sid < 0 || sid >= R.stacks.length) return [];
        return (R.stacks[sid].f || []).map(function (fi) { return fi >= 0 && fi < R.frames.length ? R.frames[fi] : '?'; });
    }
    function cutOf(sid) { return sid != null && sid >= 0 && sid < R.stacks.length ? (R.stacks[sid].cut || 0) : 0; }
    function topFrame(sid) { var f = framesOf(sid); return f.length ? f[0] : ''; }

    var STATE_NAME = { R: 'RUNNABLE', B: 'BLOCKED', W: 'WAITING', T: 'TIMED_WAITING', N: 'NEW', X: 'TERMINATED', V: 'JVM', U: 'UNKNOWN' };
    var STATE_COLOR = { RUNNABLE: '#16A34A', BLOCKED: '#DC2626', WAITING: '#D97706', TIMED_WAITING: '#EAB308', VM: '#64748B', NEW: '#9CA3AF', TERMINATED: '#9CA3AF', UNKNOWN: '#9CA3AF' };
    var STATE_ORDER = ['RUNNABLE', 'BLOCKED', 'WAITING', 'TIMED_WAITING', 'VM', 'NEW', 'TERMINATED', 'UNKNOWN'];
    var STATE_KO = { RUNNABLE: 'RUNNABLE', BLOCKED: 'BLOCKED', WAITING: 'WAITING', TIMED_WAITING: 'TIMED_WAITING', VM: 'JVM 내부', NEW: 'NEW', TERMINATED: 'TERMINATED', UNKNOWN: '알 수 없음' };
    function stateBadge(c) {
        if (c === '-' || !c) return '<span class="hi-muted">–</span>';
        return '<span class="thd-state st-' + esc(c) + '">' + esc(STATE_NAME[c] || c) + '</span>';
    }
    var CATEGORY_KO = { deadlock: '교착', blocked: '락 대기(BLOCKED)', 'lock-wait': '락 대기(ReentrantLock)', 'pool-wait': '커넥션 풀 대기', io: 'I/O 응답 대기', running: '실행 중', sleeping: 'sleep', waiting: '대기' };

    function threadLink(t) {
        if (t == null || t < 0 || !R.tracks[t]) return '<span class="hi-muted">알 수 없음</span>';
        return '<button type="button" class="thd-link" data-track="' + t + '" onclick="thdShowThread(+this.dataset.track)">' + esc(nameOf(t)) + '</button>';
    }
    function shortClass(cls) { if (!cls) return '?'; var i = cls.lastIndexOf('.'); return i < 0 ? cls : cls.substring(i + 1); }

    function loadAndRender() {
        Common.fetchJSON(api('/result')).then(function (r) {
            if (!r || !r.meta || !Array.isArray(r.tracks)) {
                showError('저장된 결과를 읽지 못했습니다. 다시 분석하세요.', 'RESULT_CORRUPT');
                return;
            }
            R = r;
            _focus = Math.max(0, Math.min(r.meta.focusSnapshot || 0, Math.max(0, (r.snapshots || []).length - 1)));
            byId('thdResult').style.display = '';
            var steps = [
                ['종합 배너', renderBanner], ['KPI', renderKpi], ['차트', renderCharts], ['소견', renderFindings], ['파싱 진단', renderDiagnostics],
                ['교착', renderDeadlocks], ['락 경합', renderLocks], ['정체 스레드', renderStuck], ['CPU', renderCpu],
                ['스레드 표', renderThreads], ['스택 묶음', renderGroups], ['풀', renderPools], ['원문', renderRaw],
                ['구성 파일', renderMembers], ['힙 점유', renderHeapMemory]
            ];
            // 한 영역의 렌더 오류가 화면 전체를 비우지 않게 따로 감싼다
            steps.forEach(function (s) { try { s[1](); } catch (e) { failed(s[0] + ' 렌더', e); } });
            syncAiButtons();
            loadAi();
            var hash = (window.location.hash || '').replace('#', '');
            if (TABS.indexOf(hash) > 0) thdTab(hash);
        }).catch(function (e) {
            failed('결과 조회 실패', e);
            if (HEAP_MODE) { var c = null; try { c = JSON.parse(e.body || '{}').code; } catch (x) { ignored('오류 코드 파싱', x); } showError(errMsg(e, '스레드 정보를 만들지 못했습니다.'), c); return; }
            if (e && e.status === 404) showError('분석 결과가 없습니다. 다시 분석하세요.', 'NO_RESULT');
            else showError(errMsg(e, '분석 결과를 불러오지 못했습니다. 잠시 후 새로 고치세요.'));
        });
    }

    // ── 탭 ────────────────────────────────────────────────────
    var TABS = ['summary', 'locks', 'threads', 'ai', 'report'];
    function thdTab(name) {
        if (TABS.indexOf(name) < 0) name = 'summary';
        TABS.forEach(function (t) {
            var panel = byId('thdPanel-' + t);
            if (panel) panel.hidden = t !== name;
        });
        document.querySelectorAll('.thd-tab').forEach(function (b) {
            var on = b.dataset.tab === name;
            b.classList.toggle('active', on);
            b.setAttribute('aria-selected', on ? 'true' : 'false');
        });
        if (name === 'report') initReport();
        try { if (window.history && history.replaceState) history.replaceState(null, '', name === 'summary' ? window.location.pathname : '#' + name); }
        catch (e) { ignored('탭 해시 갱신', e); }
    }

    // ── 요약 ──────────────────────────────────────────────────
    function renderBanner() {
        var el = byId('thdBanner'), k = R.kpi, m = R.meta;
        var real = (R.findings || []).filter(function (f) { return f.severity !== 'Info'; });
        var sev = k.severity || 'Info';
        var title, text;
        if (!real.length) {
            el.className = 'thd-banner ok';
            title = '주의가 필요한 징후를 찾지 못했습니다';
            text = HEAP_MODE ? '힙 덤프를 만든 순간의 스레드 ' + fmtNum(k.threadsLast) + '개를 분석했습니다. 스냅샷 비교·CPU·synchronized 락 관계는 힙 덤프에 없어 판정하지 않았습니다.'
                : (R.snapshots.length === 1 ? '스냅샷이 1개뿐이라 멈춘 스레드·CPU·증가 추세는 판정하지 못했습니다. ' : '')
                + '스레드 ' + fmtNum(k.threadsLast) + '개를 분석했습니다.';
        } else {
            el.className = 'thd-banner sev-' + sev;
            title = real[0].title + (real.length > 1 ? ' 외 ' + (real.length - 1) + '건' : '');
            text = real[0].detail || '';
        }
        var span = '';
        if (R.snapshots.length > 1) span = '스냅샷 ' + R.snapshots.length + '개' + (m.spanSec != null ? ' · ' + fmtSec(m.spanSec) : '')
            + (m.intervalSource === 'elapsed' ? ' (간격은 추정)' : '');
        if (m.files && m.files.length) span = (span ? span + ' · ' : '') + '구성 파일 ' + m.files.length + '개를 이어 붙임';
        el.innerHTML = '<span class="thd-sev sev-' + esc(sev) + '">' + esc(sev) + '</span>'
            + '<div class="thd-banner-main"><div class="thd-banner-title">' + esc(title) + '</div>'
            + '<div class="thd-banner-text">' + esc(text) + '</div>'
            + (span ? '<div class="thd-banner-text" style="color:#6B7280">' + esc(span) + '</div>' : '') + '</div>';
    }

    function kpiCard(label, value, sub, cls) {
        return '<div class="gcl-kpi ' + (cls || '') + '"><div class="gcl-kpi-label">' + esc(label) + '</div><div class="gcl-kpi-value" title="' + esc(value) + '">' + esc(value) + '</div>'
            + (sub ? '<div class="gcl-kpi-sub" title="' + esc(sub) + '">' + esc(sub) + '</div>' : '') + '</div>';
    }
    function renderKpi() {
        var k = R.kpi, m = R.meta, s = R.snapshots[_focus] || {};
        var h = '';
        h += kpiCard('스레드', fmtNum(k.threadsLast), 'Java ' + fmtNum(s.javaThreads) + ' · 최대 ' + fmtNum(k.threadsPeak));
        h += kpiCard('교착', fmtNum(k.deadlocks), k.deadlocks > 0 ? '스스로 풀리지 않습니다' : '없음', k.deadlocks > 0 ? 'bad' : '');
        h += kpiCard('최대 BLOCKED', fmtNum(k.blockedMax), '한 락의 최대 대기 ' + fmtNum(k.topLockWaiters), k.blockedMax >= 10 ? 'warn' : '');
        if (HEAP_MODE) {
            var mem = heapTop(1)[0];
            h += kpiCard('힙 점유 최대', mem ? fmtBytes(mem.r) : '–', mem ? nameOf(mem.t) : '스레드별 점유 정보 없음', '');
            h += kpiCard('덤프 시각', R.snapshots[0] && R.snapshots[0].tsText ? R.snapshots[0].tsText : '–', '힙 덤프 생성 시각');
        } else {
        h += kpiCard('정체 스레드', R.snapshots.length > 1 ? fmtNum(k.stuck) : '–', R.snapshots.length > 1 ? '스냅샷 간 스택 동일' : '스냅샷 1개 — 판정 불가', k.stuck > 0 ? 'warn' : '');
        h += kpiCard('스냅샷', fmtNum(R.snapshots.length), m.snapshotsSeen > R.snapshots.length ? '파일에는 ' + m.snapshotsSeen + '개 — 마지막 것만'
            : (m.spanSec != null ? fmtSec(m.spanSec) + (m.intervalSource === 'elapsed' ? ' (추정)' : '') : (m.timeSource === 'none' ? '시각 정보 없음' : '')));
        }
        // 미들웨어는 보여 주지 않는다 — 스레드 덤프로는 확정할 수 없어 옛 결과의 추정값도 쓰지 않는다(2.7.1)
        h += kpiCard('JVM', m.jdkMajor != null ? 'JDK ' + m.jdkMajor : '–', m.runs > 1 ? '재기동 ' + (m.runs - 1) + '회 포함' : '');
        byId('thdKpi').innerHTML = h;
        byId('thdTabLocksCnt').textContent = (R.deadlocks.length + R.locks.length + R.stuck.length) ? String(R.deadlocks.length + R.locks.length + R.stuck.length) : '';
        byId('thdTabThreadsCnt').textContent = fmtNum(k.threadsLast);
    }

    // 스냅샷이 1개면(힙 덤프 모드 포함) 추이 카드를 제목 한 줄로 줄이고, 그 아래 빈자리에 AI 분석 카드를 옮겨 둔다.
    // 카드는 DOM 노드째 옮기므로 id·핸들러·렌더된 내용이 그대로 따라간다. 스냅샷이 여럿이면 원래 자리(#thdAiHome 앞)로 돌려 놓는다.
    function placeAiCard(many) {
        var card = byId('thdAiCard'), slot = byId('thdSide'), home = byId('thdAiHome'), trend = byId('thdTrendCard');
        if (!card || !slot || !home || !trend) return;
        trend.classList.toggle('thd-trend-compact', !many);
        byId('thdCharts').classList.toggle('thd-charts-notrend', !many);
        card.classList.toggle('thd-ai-side', !many);
        if (!many && card.parentNode !== slot) slot.appendChild(card);
        else if (many && card.parentNode === slot) home.parentNode.insertBefore(card, home);
    }

    var _charts = [];
    function renderCharts() {
        placeAiCard(R.snapshots.length > 1);
        if (!window.Chart) return;
        _charts.forEach(function (c) { try { c.destroy(); } catch (e) { ignored('차트 정리', e); } });
        _charts = [];
        var snap = R.snapshots[_focus];
        if (!snap) return;
        var keys = STATE_ORDER.filter(function (s) { return snap.states && snap.states[s]; });
        byId('thdStateNote').textContent = '스냅샷 ' + (_focus + 1) + '번' + (snap.tsText ? ' · ' + snap.tsText : '');
        _charts.push(new Chart(byId('thdStateChart'), {
            type: 'bar',
            data: { labels: keys.map(function (s) { return STATE_KO[s]; }),
                datasets: [{ data: keys.map(function (s) { return snap.states[s]; }), backgroundColor: keys.map(function (s) { return STATE_COLOR[s]; }), borderRadius: 4, maxBarThickness: 34 }] },
            options: { indexAxis: 'y', responsive: true, maintainAspectRatio: false, animation: false,
                plugins: { legend: { display: false } },
                scales: { x: { beginAtZero: true, ticks: { precision: 0 } }, y: { ticks: { font: { size: 11 } } } } }
        }));
        var many = R.snapshots.length > 1;
        byId('thdTrendChart').style.display = many ? '' : 'none';
        byId('thdTrendEmpty').style.display = many ? 'none' : '';
        if (!many) return;
        var used = STATE_ORDER.filter(function (s) { return R.snapshots.some(function (x) { return x.states && x.states[s]; }); });
        _charts.push(new Chart(byId('thdTrendChart'), {
            type: 'bar',
            data: { labels: R.snapshots.map(function (x) { return '#' + (x.idx + 1) + (x.tsText ? ' ' + x.tsText.slice(-8) : ''); }),
                datasets: used.map(function (s) {
                    return { label: STATE_KO[s], data: R.snapshots.map(function (x) { return (x.states && x.states[s]) || 0; }), backgroundColor: STATE_COLOR[s], stack: 'st' };
                }) },
            options: { responsive: true, maintainAspectRatio: false, animation: false,
                plugins: { legend: { position: 'bottom', labels: { boxWidth: 10, font: { size: 11 } } } },
                scales: { x: { stacked: true, ticks: { font: { size: 10 }, maxRotation: 0, autoSkip: true } }, y: { stacked: true, beginAtZero: true, ticks: { precision: 0 } } } }
        }));
    }

    function renderFindings() {
        var box = byId('thdFindings');
        var fs = R.findings || [];
        var real = fs.filter(function (f) { return f.severity !== 'Info'; });
        byId('thdFindingsCount').textContent = real.length + '건' + (fs.length > real.length ? ' (+참고 ' + (fs.length - real.length) + ')' : '');
        if (!fs.length) { box.innerHTML = '<div class="gcl-empty">규칙 기반 이상 징후가 없습니다.</div>'; return; }
        box.innerHTML = fs.map(function (f) {
            var who = (f.tracks || []).slice(0, 8).map(threadLink).join(', ');
            var more = (f.tracks || []).length > 8 ? ' 외 ' + ((f.tracks || []).length - 8) + '개' : '';
            return '<div class="gcl-finding sev-' + esc(f.severity) + '"><div class="gcl-finding-head"><span class="gcl-finding-sev">' + esc(f.severity) + '</span>'
                + '<span class="gcl-finding-title">' + esc(f.title) + '</span><span class="gcl-finding-code">' + esc(f.code) + '</span></div>'
                + (f.detail ? '<div class="gcl-finding-detail">' + esc(f.detail) + '</div>' : '')
                + (f.advice ? '<div class="gcl-finding-advice">' + esc(f.advice) + '</div>' : '')
                + (who ? '<div class="gcl-finding-detail" style="font-size:12px;color:#6B7280">관련 스레드: ' + who + esc(more) + '</div>' : '') + '</div>';
        }).join('');
    }

    function renderDiagnostics() {
        var d = R.diagnostics || {}, m = R.meta;
        var items = [
            ['읽은 줄', m.lines, false], ['덤프 밖 줄(서버 로그)', d.noiseLines, false], ['해석하지 못한 줄', d.unparsedLines, true],
            ['끝까지 읽히지 않은 스냅샷', d.incompleteSnapshots, true], ['버린 미완성 스레드', d.incompleteThreads, true],
            ['잘라 낸 긴 줄', d.truncatedLines, true], ['치환한 깨진 문자', d.replacedChars, true], ['지운 제어문자', d.strippedControlChars, false],
            ['알 수 없는 스레드 상태', d.unknownStates, true], ['알 수 없는 락 줄', d.unknownLockLines, true], ['시각 줄 없는 스냅샷', d.missingTimestamps, false],
            ['건너뛴 분석 규칙', (d.ruleFailures || []).length, true],
            ['덤프가 없는 구성 파일', (d.membersWithoutDump || []).length, true],
            ['뺀 스레드(NEW·종료)', d.heapNotAliveThreads, false], ['스택이 없는 스레드', d.heapThreadsWithoutStack, false],
            ['상태를 모르는 스레드', d.heapThreadsWithoutState, false]
        ];
        var issues = 0;
        byId('thdDiag').innerHTML = items.filter(function (it, i) { return i === 0 || it[1] > 0; }).map(function (it) {
            if (it[2] && it[1] > 0) issues++;
            return '<div class="thd-diag-item' + (it[2] && it[1] > 0 ? ' warn' : '') + '"><div class="thd-diag-k">' + esc(it[0]) + '</div><div class="thd-diag-v">' + fmtNum(it[1]) + '</div></div>';
        }).join('');
        byId('thdDiagCount').textContent = issues ? '보완 ' + issues + '종' : '이상 없음';
        var extra = '';
        if ((d.limitHits || []).length) {
            var LIMIT_KO = { BYTES: '파일 크기 상한', GZIP_TRUNCATED: 'gzip 이 도중에 끊김', SNAPSHOTS: '스냅샷 수 상한', THREADS_PER_SNAPSHOT: '스냅샷당 스레드 상한' };
            extra += '<div class="thd-diag-note">도달한 상한: ' + d.limitHits.map(function (x) { return esc(LIMIT_KO[x] || x); }).join(', ') + '</div>';
        }
        if ((d.membersWithoutDump || []).length) extra += '<div class="thd-diag-note">분석에서 빠진 구성 파일: ' + d.membersWithoutDump.map(esc).join(', ') + '</div>';
        if (d.decoratedPrefix) extra += '<div class="thd-diag-note">줄 앞에 붙은 접두를 떼고 읽었습니다: <code>' + esc(d.decoratedPrefix) + '</code></div>';
        if (m.tracksTrimmed) extra += '<div class="thd-diag-note">저장 크기 상한으로 스레드 목록 일부를 뺐습니다. 소견과 요약 수치는 전량 기준입니다.</div>';
        if ((d.ruleFailures || []).length) {
            extra += '<div class="thd-diag-note">건너뛴 규칙: ' + d.ruleFailures.map(function (x) { return esc(x.rule) + ' (' + esc(x.error) + ')'; }).join(', ') + '</div>';
        }
        if ((d.unparsedSamples || []).length) {
            extra += '<div class="thd-diag-note">해석하지 못한 줄(앞에서부터 ' + d.unparsedSamples.length + '개)</div><pre class="gcl-pre">'
                + d.unparsedSamples.map(function (x) { return esc((x.file ? x.file + ' ' : '') + x.line + ': ' + x.text); }).join('\n') + '</pre>';
        }
        if ((d.oomLines || []).length) {
            extra += '<div class="thd-diag-note">로그의 OutOfMemoryError 줄</div><pre class="gcl-pre">' + d.oomLines.map(esc).join('\n') + '</pre>';
        }
        byId('thdDiagExtra').innerHTML = extra;
    }

    // ── 락·정체 ───────────────────────────────────────────────
    function renderDeadlocks() {
        var box = byId('thdDeadlocks'), list = R.deadlocks || [];
        byId('thdDeadlockCount').textContent = list.length + '건';
        byId('thdDeadlockCard').style.display = list.length ? '' : 'none';
        box.innerHTML = list.map(function (d, i) {
            var chain = (d.chain || []).map(function (c) {
                return '<li>' + (c.t >= 0 ? threadLink(c.t) : '"' + esc(c.name) + '"') + '<span class="arrow">→</span><span class="lock">' + esc(c.addr || '?') + ' (' + esc(shortClass(c.cls)) + ')</span> 대기'
                    + ' · 소유 ' + (c.heldBy >= 0 ? threadLink(c.heldBy) : '"' + esc(c.heldByName || '?') + '"') + '</li>';
            }).join('');
            var raw = (d.raw || []).length
                ? '<details class="thd-raw-toggle"><summary class="thd-link">JVM 이 출력한 원문 보기</summary><pre class="gcl-pre">' + d.raw.map(esc).join('\n') + '</pre></details>' : '';
            return '<div class="thd-dl"><div class="thd-dl-head">교착 ' + (i + 1) + '<span class="src">'
                + (d.source === 'jvm' ? 'JVM 이 보고' : '락 대기 그래프에서 확인') + ' · 스냅샷 ' + (d.snap + 1) + '번</span></div>'
                + '<ol class="thd-chain">' + chain + '</ol>' + raw + '</div>';
        }).join('');
    }

    function renderLocks() {
        var list = R.locks || [];
        byId('thdLockCount').textContent = list.length + '개';
        byId('thdLockNote').textContent = list.length ? '스냅샷 ' + (_focus + 1) + '번에서 대기 스레드가 있는 락입니다. 소유 스레드가 무엇을 하느라 락을 쥐고 있는지가 원인입니다.' : '';
        byId('thdLockBody').innerHTML = list.length ? list.map(function (l) {
            var ownerTop = l.owner >= 0 ? topFrame(anyStack(l.owner, _focus)) : '';
            return '<tr><td class="frame" title="' + esc(l.cls || '') + '">' + esc(l.addr) + ' (' + esc(shortClass(l.cls)) + ')</td>'
                + '<td>' + (l.kind === 'juc' ? 'ReentrantLock' : '모니터') + '</td>'
                + '<td class="num">' + fmtNum(l.waiterCount) + '</td>'
                + '<td class="name">' + threadLink(l.owner) + '</td>'
                + '<td class="frame" title="' + esc(ownerTop) + '">' + esc(ownerTop || '–') + '</td>'
                + '<td class="name thd-hide-sm">' + (l.rootBlocker >= 0 && l.rootBlocker !== l.owner ? threadLink(l.rootBlocker) : '<span class="hi-muted">–</span>') + '</td></tr>';
        }).join('') : '<tr><td colspan="6" class="gcl-empty">대기 스레드가 있는 락이 없습니다.</td></tr>';
    }

    function renderStuck() {
        var list = R.stuck || [];
        // 표에는 교착 스레드도 함께 보인다(분류 '교착') — KPI 의 정체 스레드 수는 교착을 뺀 값이다
        var inDeadlock = list.filter(function (s) { return s.category === 'deadlock'; }).length;
        var total = Math.max(list.length, (R.kpi.stuck || 0) + inDeadlock);
        byId('thdStuckCount').textContent = R.snapshots.length > 1 ? fmtNum(total) + '개' + (inDeadlock ? ' (교착 ' + inDeadlock + ' 포함)' : '') : '판정 불가';
        byId('thdStuckBody').innerHTML = list.length ? list.slice(0, 200).map(function (s) {
            var top = topFrame(s.stackId);
            return '<tr><td class="name">' + threadLink(s.t) + '</td>'
                + '<td><span class="thd-tag' + (s.category === 'deadlock' ? ' dl' : '') + '">' + esc(CATEGORY_KO[s.category] || s.category) + '</span></td>'
                + '<td class="num">' + (s.from + 1) + '~' + (s.to + 1) + '</td>'
                + '<td class="num thd-hide-sm">' + (s.durationSec != null ? esc(fmtSec(s.durationSec)) : '–') + '</td>'
                + '<td class="frame" title="' + esc(top) + '">' + esc(top || '–') + '</td>'
                + '<td class="num thd-hide-sm">' + (s.cpuDeltaMs != null ? fmtNum(Math.round(s.cpuDeltaMs)) + ' ms' : '–') + '</td></tr>';
        }).join('') : '<tr><td colspan="6" class="gcl-empty">' + (R.snapshots.length > 1 ? '같은 스택에 머문 스레드가 없습니다.' : '스냅샷이 2개 이상이어야 판정할 수 있습니다.') + '</td></tr>';
        byId('thdStuckMore').textContent = total > 200 ? '오래 머문 순으로 앞의 200개만 표시했습니다(전체 ' + fmtNum(total) + '개).' : '';
    }

    function renderCpu() {
        var list = R.cpuTop || [];
        byId('thdCpuCard').style.display = list.length ? '' : 'none';
        byId('thdCpuCount').textContent = list.length + '개';
        byId('thdCpuBody').innerHTML = list.map(function (c) {
            var top = topFrame(anyStack(c.t, c.to));
            return '<tr><td class="name">' + threadLink(c.t) + '</td><td class="num">' + fmtNum(Math.round(c.deltaMs)) + ' ms</td>'
                + '<td class="num">' + (Math.round(c.pct * 10) / 10) + '%</td><td class="num">' + (c.from + 1) + '~' + (c.to + 1) + '</td>'
                + '<td class="frame" title="' + esc(top) + '">' + esc(top || '–') + '</td></tr>';
        }).join('');
    }

    // ── 스레드 표 ─────────────────────────────────────────────
    var _grid = null;
    var _snap = 0;
    var _stateSel = {};
    var _deadlocked = {};
    var _openRow = null;
    var LIB_RE = /^(java|javax|jakarta|jdk|sun|com\.sun|org\.apache|org\.springframework|org\.eclipse|org\.jboss|io\.netty|weblogic|jeus|oracle|com\.mysql|org\.mariadb|org\.postgresql|com\.zaxxer|org\.hibernate|kotlin|scala|reactor)\./;

    function histHtml(tr, snap) {
        var h = '<span class="thd-hist">';
        for (var i = 0; i < R.snapshots.length; i++) {
            var off = i - tr.first;
            var c = off >= 0 && off < tr.s.length ? tr.s.charAt(off) : '-';
            h += '<i class="' + (c === '-' ? 'st-absent' : 'st-' + c) + (i === snap ? ' cur' : '') + '" title="스냅샷 ' + (i + 1) + ': ' + (c === '-' ? '없음' : (STATE_NAME[c] || c)) + '"></i>';
        }
        return h + '</span>';
    }

    function buildThreadRows(snap) {
        var rows = [];
        R.tracks.forEach(function (tr, t) {
            var c = stateAt(t, snap);
            if (c === '-') return;
            var sid = stackAt(t, snap);
            var frames = framesOf(sid);
            var name = nameOf(t);
            var idle = snap === _focus && tr.idle;
            var poolStem = tr.p >= 0 && R.pools[tr.p] ? R.pools[tr.p].stem : '';
            var off = snap - tr.first;
            var cpu = tr.cpu && tr.cpu[off] != null ? tr.cpu[off] : null;
            var row = document.createElement('tr');
            row.className = 'clickable';
            row.setAttribute('data-track', String(t));
            row.setAttribute('data-name', name.toLowerCase());
            row.setAttribute('data-state', c);
            row.setAttribute('data-top', frames.length ? frames[0].toLowerCase() : '');
            row.setAttribute('data-depth', String(frames.length + cutOf(sid)));
            row.setAttribute('data-cpu', cpu == null ? '' : String(cpu));
            row.setAttribute('data-pool', tr.p >= 0 ? String(tr.p) : '');
            row.setAttribute('data-idle', idle ? '1' : '0');
            row.setAttribute('data-sys', tr.sys ? '1' : '0');
            var mem = memOf(t);
            row.setAttribute('data-mem', mem == null ? '' : String(mem));
            // 검색은 이름 + 스택 전체에서 — 프레임은 길 수 있어 소문자 한 덩어리로 미리 만든다
            row.setAttribute('data-search', (name + '\n' + frames.join('\n')).toLowerCase());
            var tags = '';
            if (_deadlocked[t]) tags += ' <span class="thd-tag dl">교착</span>';
            if (idle) tags += ' <span class="thd-tag idle">유휴</span>';
            if (tr.sys) tags += ' <span class="thd-tag sys">JVM</span>';
            if (tr.d && !tr.sys) tags += ' <span class="thd-tag">daemon</span>';
            row.innerHTML = '<td class="name" title="' + esc(name) + (poolStem ? ' — 풀: ' + esc(poolStem) : '') + '">' + esc(name) + tags + '</td>'
                + '<td>' + stateBadge(c) + '</td>'
                + '<td class="thd-hide-sm">' + histHtml(tr, snap) + '</td>'
                + '<td class="frame" title="' + esc(frames[0] || '') + '">' + esc(frames[0] || (tr.nat ? '(' + tr.nat + ')' : '–')) + '</td>'
                + '<td class="num thd-hide-sm">' + (frames.length ? frames.length + cutOf(sid) : '–') + '</td>'
                + '<td class="num thd-hide-sm">' + (cpu == null ? '–' : fmtNum(Math.round(cpu))) + '</td>'
                + '<td class="num thd-mem-col">' + (mem == null ? '<span class="hi-muted">–</span>' : esc(fmtBytes(mem))) + '</td>';
            row.addEventListener('click', function () { toggleDetail(row, t); });
            rows.push(row);
        });
        return rows;
    }

    function stackHtml(t, snap) {
        var sid = stackAt(t, snap), frames = framesOf(sid), tr = R.tracks[t];
        if (!frames.length) return '<span class="none">(스택 없음' + (tr && tr.sys ? ' — JVM 내부 스레드는 Java 스택을 찍지 않습니다' : '') + ')</span>';
        var ops = snap === _focus && tr.ops ? tr.ops : [];
        var out = [];
        var opLine = function (o) {
            var wait = o.v === 'waiting to lock' || o.v === 'parking to wait for' || o.v === 'waiting on';
            return '<span class="op' + (wait ? ' wait' : '') + '">    - ' + esc(o.v === 'owns' ? 'owns synchronizer' : o.v) + ' &lt;' + esc(o.a) + '&gt;' + (o.c ? ' (a ' + esc(o.c) + ')' : '') + '</span>';
        };
        frames.forEach(function (f, i) {
            out.push('<span class="' + (LIB_RE.test(f) ? 'fr-lib' : 'fr-app') + '">  at ' + esc(f) + '</span>');
            ops.forEach(function (o) { if (o.at === i + 1 && o.v !== 'owns') out.push(opLine(o)); });
        });
        if (cutOf(sid) > 0) out.push('<span class="cut">  … ' + cutOf(sid) + '개 프레임 생략(저장 깊이 상한)</span>');
        ops.forEach(function (o) { if (o.v === 'owns') out.push(opLine(o)); });
        return out.join('\n');
    }

    function detailHtml(t, snap) {
        var tr = R.tracks[t];
        var picks = '';
        for (var i = 0; i < R.snapshots.length; i++) {
            var has = stateAt(t, i) !== '-';
            picks += '<button type="button" class="thd-snap-btn' + (i === snap ? ' active' : '') + '"' + (has ? '' : ' disabled')
                + ' data-track="' + t + '" data-snap="' + i + '" onclick="event.stopPropagation();thdDetailSnap(+this.dataset.track,+this.dataset.snap)">' + (i + 1) + '</button>';
        }
        var meta = [];
        if (tr.num != null) meta.push('#' + tr.num);
        if (tr.tid) meta.push('tid ' + tr.tid);
        if (tr.nid) meta.push('nid ' + tr.nid);
        if (tr.p >= 0 && R.pools[tr.p]) meta.push('풀 ' + R.pools[tr.p].stem);
        var hm = heapInfoOf(t);
        if (hm) {
            if (hm.r != null) meta.push('힙 점유 ' + fmtBytes(hm.r));
            if (hm.l != null) meta.push('지역 변수 최대 ' + fmtBytes(hm.l));
            if (hm.c) meta.push(hm.c);
            if (hm.s && !HEAP_MODE) meta.push('힙 덤프 시점 ' + hm.s);
        }
        var sf = R.snapshots[snap] && R.snapshots[snap].file;
        if (sf) meta.push('파일 ' + sf);
        var note = snap !== _focus && tr.ops ? '<div class="thd-stack-note">락 줄(locked · waiting to lock)은 스냅샷 ' + (_focus + 1) + '번에서만 표시합니다.</div>' : '';
        return '<div class="thd-detail"><div class="thd-detail-head"><b>' + esc(nameOf(t)) + '</b>' + stateBadge(stateAt(t, snap))
            + '<span>' + esc(meta.join(' · ')) + '</span>'
            + (R.snapshots.length > 1 ? '<span class="thd-snap-pick">스냅샷 ' + picks + '</span>' : '') + '</div>'
            + '<pre class="thd-stack">' + stackHtml(t, snap) + '</pre>' + note + '</div>';
    }

    function closeDetail() {
        if (_openRow) {
            _openRow.classList.remove('expanded');
            var d = _openRow.nextSibling;
            if (d && d.classList && d.classList.contains('thd-detail-row')) d.parentNode.removeChild(d);
        }
        _openRow = null;
    }
    function toggleDetail(row, t) {
        if (_openRow === row) { closeDetail(); return; }
        closeDetail();
        var d = document.createElement('tr');
        d.className = 'thd-detail-row';
        d.innerHTML = '<td colspan="7">' + detailHtml(t, _snap) + '</td>';
        row.parentNode.insertBefore(d, row.nextSibling);
        row.classList.add('expanded');
        _openRow = row;
    }
    function thdDetailSnap(t, snap) {
        if (!_openRow) return;
        var d = _openRow.nextSibling;
        if (d && d.classList && d.classList.contains('thd-detail-row')) d.firstChild.innerHTML = detailHtml(t, snap);
    }

    function applyThreadFilter() {
        if (!_grid) return;
        closeDetail();
        var q = (byId('thdThreadSearch').value || '').trim().toLowerCase();
        var pool = byId('thdPoolSelect').value;
        var hideIdle = byId('thdHideIdle').checked, hideSys = byId('thdHideSys').checked;
        var anyState = Object.keys(_stateSel).length > 0;
        var shown = _grid.allRows.filter(function (row) {
            // ⚠ detach 표라 tbody 를 직접 손대면 다른 페이지 행이 걸러지지 않는다 — 술어에만 넣는다
            if (hideSys && row.getAttribute('data-sys') === '1') return false;
            if (hideIdle && row.getAttribute('data-idle') === '1') return false;
            if (anyState && !_stateSel[row.getAttribute('data-state')]) return false;
            if (pool && row.getAttribute('data-pool') !== pool) return false;
            if (q && row.getAttribute('data-search').indexOf(q) === -1) return false;
            return true;
        });
        _grid.setFiltered(shown);
        byId('thdThreadCount').textContent = shown.length === _grid.allRows.length ? fmtNum(shown.length) + '개' : fmtNum(shown.length) + ' / ' + fmtNum(_grid.allRows.length) + '개';
    }

    function renderStateChips() {
        var snap = R.snapshots[_snap] || {};
        var box = byId('thdStateChips');
        var codes = ['R', 'B', 'W', 'T', 'V'];
        var key = { R: 'RUNNABLE', B: 'BLOCKED', W: 'WAITING', T: 'TIMED_WAITING', V: 'VM' };
        box.innerHTML = codes.filter(function (c) { return snap.states && snap.states[key[c]]; }).map(function (c) {
            return '<button type="button" class="thd-chip' + (_stateSel[c] ? ' active' : '') + '" data-state="' + c + '" aria-pressed="' + (_stateSel[c] ? 'true' : 'false') + '" onclick="thdToggleState(this.dataset.state)">'
                + esc(STATE_KO[key[c]]) + '<span class="cnt">' + fmtNum(snap.states[key[c]]) + '</span></button>';
        }).join('');
    }
    function thdToggleState(c) {
        if (_stateSel[c]) delete _stateSel[c]; else _stateSel[c] = 1;
        if (c === 'V' && _stateSel.V) byId('thdHideSys').checked = false;   // JVM 내부만 보려는데 숨김이 켜져 있으면 빈 표가 된다
        renderStateChips();
        applyThreadFilter();
    }

    function loadThreadSnapshot(snap) {
        _snap = snap;
        closeDetail();
        var rows = buildThreadRows(snap);
        if (!_grid) {
            _grid = TableGrid.create({
                tbodyId: 'thdThreadBody', headerSelector: '#thdThreadTable th.sortable', pageSizeKey: 'threadDumpThreadPageSize',
                applyFilter: applyThreadFilter, defaultSort: { key: 'state', dir: 'asc', type: 'str' }, sortAttrPrefix: 'data-',
                pageSizes: [25, 50, 100, 200], defaultPageSize: 50, pageSizeSelectId: 'thdThreadPageSize', noMatchId: 'thdThreadNoMatch',
                paginationBarId: 'thdThreadPg', pgInfoId: 'thdThreadPgInfo', pgListId: 'thdThreadPgList', detach: true,
                // 표가 다시 그려지면(정렬·페이지 이동) 끼워 넣은 상세 행은 사라진다 — 열림 표시도 같이 지운다
                onRenderStart: function () { if (_openRow) _openRow.classList.remove('expanded'); _openRow = null; }
            });
            window.thdThreadGrid = _grid;
        }
        _grid.init(rows);
        renderStateChips();
        applyThreadFilter();
    }

    function renderThreads() {
        _deadlocked = {};
        (R.deadlocks || []).forEach(function (d) { (d.tracks || []).forEach(function (t) { _deadlocked[t] = 1; }); });
        var sel = byId('thdSnapSelect');
        sel.innerHTML = R.snapshots.map(function (s) {
            return '<option value="' + s.idx + '"' + (s.idx === _focus ? ' selected' : '') + '>' + (s.idx + 1) + '번' + (s.tsText ? ' · ' + esc(s.tsText) : '') + ' (' + fmtNum(s.threads) + ')'
                + (s.file ? ' — ' + esc(s.file) : '') + '</option>';
        }).join('');
        sel.onchange = function () { loadThreadSnapshot(parseInt(sel.value, 10) || 0); };
        var pool = byId('thdPoolSelect');
        pool.innerHTML = '<option value="">전체</option>' + R.pools.map(function (p, i) {
            return '<option value="' + i + '">' + esc(p.stem) + '</option>';
        }).join('');
        var deb = null;
        byId('thdThreadSearch').addEventListener('input', function () { clearTimeout(deb); deb = setTimeout(applyThreadFilter, 150); });
        pool.addEventListener('change', applyThreadFilter);
        byId('thdHideIdle').addEventListener('change', applyThreadFilter);
        byId('thdHideSys').addEventListener('change', applyThreadFilter);
        byId('thdThreadTrimNote').textContent = R.meta.tracksTrimmed ? '저장 크기 상한으로 일부 스레드는 이 표에서 빠졌습니다.' : '';
        loadThreadSnapshot(_focus);
    }

    /** 다른 카드의 스레드 이름을 눌렀다 — 스레드 탭으로 가서 그 스레드의 스택을 편다. */
    function thdShowThread(t) {
        if (!R || !R.tracks[t]) return;
        thdTab('threads');
        _stateSel = {};
        byId('thdHideIdle').checked = false;
        byId('thdHideSys').checked = false;
        byId('thdPoolSelect').value = '';
        byId('thdThreadSearch').value = nameOf(t);
        var snap = stateAt(t, _snap) !== '-' ? _snap : R.tracks[t].first;
        if (snap !== _snap) { byId('thdSnapSelect').value = String(snap); loadThreadSnapshot(snap); }
        else { renderStateChips(); applyThreadFilter(); }
        var row = null;
        _grid.filteredRows.forEach(function (r) { if (r.getAttribute('data-track') === String(t)) row = r; });
        if (row && row.parentNode) { toggleDetail(row, t); row.scrollIntoView({ block: 'center', behavior: Common.reduceMotion && Common.reduceMotion() ? 'auto' : 'smooth' }); }
    }

    function renderGroups() {
        var list = (R.stackGroups || []).filter(function (g) { return g.count >= 2; });
        byId('thdGroupCount').textContent = list.length + '묶음';
        byId('thdGroups').innerHTML = list.length ? list.map(function (g, i) {
            var names = (g.sample || []).map(nameOf).join(', ') + (g.count > (g.sample || []).length ? ' …' : '');
            var frames = framesOf(g.stackId);
            var body = frames.map(function (f) { return '<span class="' + (LIB_RE.test(f) ? 'fr-lib' : 'fr-app') + '">  at ' + esc(f) + '</span>'; }).join('\n')
                + (cutOf(g.stackId) > 0 ? '\n<span class="cut">  … ' + cutOf(g.stackId) + '개 프레임 생략</span>' : '');
            return '<div class="thd-group" id="thdGroup' + i + '"><div class="thd-group-head" role="button" tabindex="0" aria-expanded="false" data-idx="' + i + '"'
                + ' onclick="thdToggleGroup(+this.dataset.idx)" onkeydown="if(event.key===\'Enter\'||event.key===\' \'){event.preventDefault();thdToggleGroup(+this.dataset.idx)}">'
                + '<span class="thd-chev" aria-hidden="true">▸</span><span>' + fmtNum(g.count) + '개</span><span class="thd-state st-' + esc((g.state || 'U').charAt(0)) + '">' + esc(g.state) + '</span>'
                + '<span class="names" title="' + esc(names) + '">' + esc(topFrame(g.stackId)) + ' — ' + esc(names) + '</span></div>'
                + '<pre class="thd-stack" hidden>' + body + '</pre></div>';
        }).join('') : '<div class="gcl-empty">같은 스택을 가진 스레드가 2개 이상인 묶음이 없습니다.</div>';
    }
    function thdToggleGroup(i) {
        var el = byId('thdGroup' + i);
        if (!el) return;
        var open = el.classList.toggle('expanded');
        var pre = el.querySelector('.thd-stack'); if (pre) pre.hidden = !open;
        var head = el.querySelector('.thd-group-head'); if (head) head.setAttribute('aria-expanded', open ? 'true' : 'false');
    }

    function renderPools() {
        var list = R.pools.map(function (p, i) { return { p: p, i: i, size: p.sizes[_focus] || 0 }; }).filter(function (x) { return x.size > 0; })
            .sort(function (a, b) { return b.size - a.size; });
        byId('thdPoolCount').textContent = list.length + '개';
        byId('thdPoolBody').innerHTML = list.length ? list.slice(0, 100).map(function (x) {
            var p = x.p, idle = p.idle[_focus] || 0, active = p.active[_focus] || 0;
            return '<tr><td class="name" title="' + esc(p.stem) + '">' + esc(p.stem) + '</td>'
                + '<td>' + (p.kind === 'worker' ? '<span class="thd-tag worker">요청 처리</span>' : '<span class="thd-tag">기타</span>') + '</td>'
                + '<td class="num">' + fmtNum(x.size) + '</td><td class="num">' + fmtNum(idle) + '</td>'
                + '<td class="num"' + (p.kind === 'worker' && idle === 0 && x.size >= 10 ? ' style="color:var(--gcl-sev-high);font-weight:700"' : '') + '>' + fmtNum(active) + '</td>'
                + '<td class="num thd-hide-sm">' + (R.snapshots.length > 1 ? p.sizes.join(' → ') : '–') + '</td></tr>';
        }).join('') : '<tr><td colspan="6" class="gcl-empty">이름에 번호가 붙은 스레드 묶음이 없습니다.</td></tr>';
    }

    function renderRaw() {
        var raw = R.rawSample || {};
        var lines = (raw.firstDumpHead && raw.firstDumpHead.length) ? raw.firstDumpHead : (raw.head || []);
        var note = raw.firstDumpLine && raw.firstDumpLine > 1 ? '(첫 덤프는 ' + raw.firstDumpLine + '번째 줄에서 시작합니다)\n' : '';
        byId('thdRaw').textContent = note + lines.join('\n');
    }

    // ── 리포트 탭 ─────────────────────────────────────────────
    var _reportMode = null;
    var _reportTimer = null;
    function reportUrl(mode) {
        return '/thread-dump/' + (HEAP_MODE ? 'heap/' : 'analyze/') + encodeURIComponent(THD_FILENAME) + (mode === 'pdf' ? '/print-pdf?mode=inline' : '/print-html');
    }
    function thdReportMode(mode, auto) {
        var frame = byId('thdReportFrame');
        if (!frame) return;
        if (_reportTimer) { clearTimeout(_reportTimer); _reportTimer = null; }
        _reportMode = mode;
        frame.src = reportUrl(mode);
        byId('thdReportHtmlBtn').classList.toggle('active', mode === 'html');
        byId('thdReportPdfBtn').classList.toggle('active', mode === 'pdf');
        var note = byId('thdReportNote');
        if (note) note.textContent = auto ? '이 브라우저에서는 PDF 미리보기를 표시할 수 없어 HTML 로 보여 줍니다. PDF 파일은 다운로드 버튼으로 받으세요.'
            : '요약 리포트입니다. 스레드 전체 목록은 \'스레드\' 탭에서 봅니다.';
        if (mode === 'pdf') {
            // PDF 인라인은 일부 브라우저에서 load 이벤트가 오지 않는다 — 4초 안에 안 오면 HTML 로 바꾼다
            var loaded = false;
            var onLoad = function () { loaded = true; frame.removeEventListener('load', onLoad); };
            frame.addEventListener('load', onLoad);
            _reportTimer = setTimeout(function () { _reportTimer = null; if (!loaded && _reportMode === 'pdf') thdReportMode('html', true); }, 4000);
        }
    }
    function initReport() {
        var frame = byId('thdReportFrame');
        if (!frame || frame.getAttribute('src')) return;      // 이미 로드됨 (함정 6 — !frame.src 로 판정하지 않는다)
        thdReportMode('html', false);
    }

    // ── AI 분석 ───────────────────────────────────────────────
    var AI_EMPTY = '<div class="gcl-ai-empty">LLM 이 소견·교착 사슬·락 경합·정체 스레드의 스택을 종합해 근본 원인과 조치를 제시합니다. 오른쪽 <b>분석</b> 버튼으로 실행하세요.</div>';
    var AI_OFF = '<div class="gcl-ai-empty">AI 분석이 꺼져 있습니다. 관리자가 Settings 에서 LLM 을 활성화하면 사용할 수 있습니다.</div>';
    var _aiHtml = null;
    var _aiRunning = false;
    var _aiGuarded = false;
    var _aiTimer = null;

    function aiBlock(title, inner) {
        return '<section class="gcl-ai-block"><div class="gcl-ai-block-head"><h4 class="gcl-ai-block-title">' + esc(title) + '</h4></div>' + inner + '</section>';
    }
    function aiMeta(k, v) { return '<span class="gcl-ai-meta-item"><span class="gcl-ai-meta-k">' + esc(k) + '</span><span class="gcl-ai-meta-v">' + esc(v) + '</span></span>'; }

    // 요약 탭(#thdAiBody)에는 심각도 띠만, 'AI 분석' 탭(#thdAiFull)에는 전체를 그린다.
    // _aiHtml = 전체(있으면 "재분석"), _aiSevHtml = 심각도 띠 — 실패 시 직전 결과를 되살리는 데 쓴다.
    var _aiSevHtml = null;
    function setAiBodies(brief, full) {
        var b = byId('thdAiBody'), f = byId('thdAiFull');
        if (b) b.innerHTML = brief;
        if (f) f.innerHTML = full;
    }
    function aiEmptyHtml() { return THD_LLM_ENABLED ? AI_EMPTY : AI_OFF; }
    function setAiTabSev(sev) {
        var el = byId('thdTabAiSev');
        if (!el) return;
        el.className = 'thd-tab-sev' + (sev ? ' sev-' + sev : '');
        el.textContent = sev || '';
    }
    function renderAi(d) {
        if (!d || d.found === false) return;
        var data = d.data || d;
        var sev = data.severity || 'Unknown';
        var sevHtml = '<div class="gcl-ai-sev sev-' + esc(sev) + '"><span class="gcl-ai-sev-badge">' + esc(sev) + '</span>'
            + (data.severityDesc ? '<span class="gcl-ai-sev-desc">' + esc(data.severityDesc) + '</span>' : '') + '</div>';
        var h = sevHtml;
        if (Array.isArray(data.warnings) && data.warnings.length) h += '<div class="gcl-ai-warn">응답 정규화: ' + esc(data.warnings.join(' · ')) + '</div>';
        h += '<div class="gcl-ai-blocks">';
        if (data.summary) h += aiBlock('요약', '<div class="gcl-ai-block-text">' + esc(data.summary) + '</div>');
        if (data.rootCause) h += aiBlock('근본 원인', '<div class="gcl-ai-block-text">' + esc(data.rootCause) + '</div>');
        var recs = data.recommendations;
        if (typeof recs === 'string') recs = recs.split(/\n+/).filter(Boolean);
        if (recs && recs.length) {
            h += aiBlock('권장 조치', '<ol class="gcl-ai-recs">' + recs.map(function (r, i) {
                return '<li><span class="gcl-ai-rec-no">' + (i + 1) + '</span><span>' + esc(String(r).replace(/^\s*\d+[.)]\s*/, '')) + '</span></li>';
            }).join('') + '</ol>');
        }
        if (data.codeAdvice) h += aiBlock('수정 지점', '<div class="gcl-ai-block-text">' + esc(data.codeAdvice) + '</div>');
        h += '</div>';
        var at = d.analysedAt || data.analysedAt, model = d.model || data.model;
        var latency = d.latencyMs != null ? d.latencyMs : data.latencyMs;
        h += '<div class="gcl-ai-meta">' + (model ? aiMeta('모델', model) : '') + (at ? aiMeta('분석 시각', fmtTs(typeof at === 'number' ? at : Date.parse(at))) : '')
            + (latency != null && !isNaN(latency) ? aiMeta('소요', (latency / 1000).toFixed(1) + '초') : '')
            + (d.saved === false ? '<span class="gcl-ai-meta-warn">저장 실패: ' + esc(d.saveError || '') + ' — 화면을 벗어나면 이 결과는 사라집니다</span>' : '')
            + '<button type="button" class="btn btn-sm btn-ghost" onclick="thdDeleteAi()">삭제</button></div>';
        _aiHtml = h;
        _aiSevHtml = sevHtml;
        setAiBodies(sevHtml, h);
        setAiTabSev(/^(Critical|High|Medium|Low)$/.test(sev) ? sev : '');
        syncAiButtons();
    }
    function loadAi() {
        if (!_aiHtml) { setAiBodies(aiEmptyHtml(), aiEmptyHtml()); setAiTabSev(''); }
        Common.fetchJSON(api('/ai-insight')).then(function (d) { if (d && d.found) renderAi(d); }).catch(function (e) { ignored('AI 해석 조회', e); });
    }
    function syncAiButtons() {
        var ok = THD_STATUS === 'SUCCESS' && THD_LLM_ENABLED;
        var top = byId('thdBtnAi');
        if (top) { top.disabled = _aiRunning || !ok; top.textContent = _aiRunning ? 'AI 분석 중…' : 'AI 분석'; top.title = THD_LLM_ENABLED ? '' : 'AI 분석이 꺼져 있습니다(Settings → LLM)'; }
        [['thdAiRunBtn', 'thdAiRunLabel'], ['thdAiRunBtn2', 'thdAiRunLabel2']].forEach(function (p) {
            var run = byId(p[0]), label = byId(p[1]);
            if (run) run.disabled = _aiRunning || !ok;
            if (label) label.textContent = _aiRunning ? '분석 중…' : (_aiHtml ? '재분석' : '분석');
        });
        var detail = byId('thdAiDetailBtn');
        if (detail) detail.style.display = _aiHtml && !_aiRunning ? '' : 'none';
    }
    function thdRunAi() {
        if (_aiRunning || THD_STATUS !== 'SUCCESS') return;
        byId('thdAiTitle').textContent = _aiHtml ? 'AI 재분석' : 'AI 분석 시작';
        byId('thdAiReplace').style.display = _aiHtml ? '' : 'none';
        byId('thdAiConfirmBtn').textContent = _aiHtml ? '재분석' : '분석 시작';
        showModal(byId('thdAiModal'));
        byId('thdAiConfirmBtn').focus();
    }
    function thdCloseAi() { hideModal(byId('thdAiModal')); }
    function thdConfirmAi() { thdCloseAi(); startAi(); }
    function stopAiTimer() { if (_aiTimer) { clearInterval(_aiTimer); _aiTimer = null; } }
    function startAi() {
        if (_aiRunning) return;
        _aiRunning = true;
        if (!_aiGuarded && window.SessionTimeout && SessionTimeout.registerActivityGuard) {
            SessionTimeout.registerActivityGuard(function () { return _aiRunning; });   // 끝나는 작업만 — 응답이 오면 false(함정 38)
            _aiGuarded = true;
        }
        syncAiButtons();
        // 'AI 분석' 탭에서 시작했으면 그 탭에 머문다 — 다른 탭이면 요약으로 와서 진행을 보인다
        var onAiTab = !byId('thdPanel-ai').hidden;
        if (!onAiTab) thdTab('summary');
        var loading = '<div class="gcl-ai-loading"><div class="gcl-ai-loading-row"><span class="gcl-ai-loading-spin" aria-hidden="true"></span>'
            + '<div class="gcl-ai-loading-text"><div class="gcl-ai-loading-title" role="status">AI 분석 중입니다</div>'
            + '<div class="gcl-ai-loading-desc">모델에 따라 15~40초 걸릴 수 있습니다.</div></div>'
            + '<span class="gcl-ai-elapsed" aria-label="경과 시간"><span class="gcl-ai-elapsed-k">경과</span><span class="gcl-ai-elapsed-v" id="thdAiElapsed">0</span>초</span></div>'
            + '<div class="gcl-ai-loading-track" aria-hidden="true"><div class="gcl-ai-loading-fill"></div></div></div>';
        // 경과 시계(#thdAiElapsed)는 보이는 쪽 하나에만 둔다 — id 가 둘이 되지 않게
        var idle = loading.replace(' id="thdAiElapsed"', '').replace(' role="status"', '');   // 알림 영역도 하나만
        if (onAiTab) setAiBodies(idle, loading); else setAiBodies(loading, idle);
        byId(onAiTab ? 'thdAiFull' : 'thdAiBody').scrollIntoView({ block: 'center' });
        var t0 = Date.now();
        stopAiTimer();
        _aiTimer = setInterval(function () {   // 화면 안 시계일 뿐 요청을 보내지 않는다 — managedInterval 대상 아님
            var el = byId('thdAiElapsed');
            if (el) el.textContent = String(Math.floor((Date.now() - t0) / 1000));
        }, 1000);
        var done = function () { _aiRunning = false; stopAiTimer(); syncAiButtons(); };
        // 실패하면 오류를 위에 얹고 직전 결과는 그대로 둔다(저장된 옛 결과는 지워지지 않았다)
        var showErr = function (msg) {
            var err = '<div class="gcl-ai-err" role="alert">' + esc(msg) + '</div>';
            setAiBodies(err + (_aiSevHtml || AI_EMPTY), err.replace(' role="alert"', '') + (_aiHtml || AI_EMPTY));
        };
        Common.fetchJSON(api('/ai-analyze'), { method: 'POST' })
            .then(function (d) {
                done();
                if (!d.success) { failed('AI 분석 실패 응답', d.errorCode || d.error); showErr(d.error || d.errorCode || 'AI 분석 실패'); return; }
                renderAi(d);
                toast('AI 분석을 마쳤습니다 (' + Math.floor((Date.now() - t0) / 1000) + '초).', 'success');
            })
            .catch(function (e) {
                done();
                failed('AI 분석 요청 실패', e);
                showErr(e && e.rateLimited ? 'LLM 호출량 제한 — 잠시 후 다시 시도하세요' : errMsg(e, 'AI 분석 실패'));
            });
    }
    function thdDeleteAi() {
        if (!confirm('AI 분석 결과를 삭제할까요?')) return;
        Common.fetchJSON(api('/ai-insight'), { method: 'DELETE' })
            .then(function () { _aiHtml = null; _aiSevHtml = null; setAiBodies(AI_EMPTY, AI_EMPTY); setAiTabSev(''); syncAiButtons(); })
            .catch(function (e) { toast('삭제 실패: ' + errMsg(e, ''), 'danger'); });
    }

    // ── 서버 이름·다운로드·삭제 ───────────────────────────────
    function thdOpenServerEdit() {
        var cur = byId('thdServerValue');
        byId('thdServerInput').value = cur && !cur.classList.contains('empty') ? cur.textContent : '';
        var err = byId('thdServerErr'); err.textContent = ''; err.classList.remove('show');
        byId('thdServerSaveBtn').disabled = false;
        showModal(byId('thdServerModal'));
        byId('thdServerInput').focus();
    }
    function thdCloseServerEdit() { hideModal(byId('thdServerModal')); }
    function thdSaveServer() {
        var btn = byId('thdServerSaveBtn'); btn.disabled = true;
        Common.fetchJSON(api('/server-name'), { method: 'POST', body: JSON.stringify({ serverName: byId('thdServerInput').value }) })
            .then(function (d) {
                var cur = byId('thdServerValue');
                if (cur) { cur.textContent = d.serverName || '미지정'; cur.classList.toggle('empty', !d.serverName); }
                thdCloseServerEdit();
                toast('서버 이름을 저장했습니다.', 'success');
                var frame = byId('thdReportFrame');
                if (frame && frame.getAttribute('src') && _reportMode) thdReportMode(_reportMode, false);   // 리포트 머리말에 들어가는 값이다
            })
            .catch(function (e) { btn.disabled = false; var err = byId('thdServerErr'); err.textContent = errMsg(e, '저장하지 못했습니다.'); err.classList.add('show'); });
    }

    function thdDownloadOriginal() {
        var url = '/api/thread-dump/download/' + encodeURIComponent(THD_FILENAME);
        if (window.Common && Common.downloadFile) Common.downloadFile(url, { fallbackName: THD_FILENAME });
        else window.location.href = url;
    }

    function thdOpenDelete() {
        var err = byId('thdDeleteErr'); if (err) { err.textContent = ''; err.classList.remove('show'); }
        byId('thdDeleteBtn').disabled = false;
        showModal(byId('thdDeleteModal'));
    }
    function thdCloseDelete() { hideModal(byId('thdDeleteModal')); }
    function thdConfirmDelete() {
        var chk = byId('thdDeleteFileChk');
        var deleteFile = chk ? chk.checked : true;
        var btn = byId('thdDeleteBtn'); btn.disabled = true;
        Common.fetchJSON(api('') + '?deleteFile=' + deleteFile, { method: 'DELETE' })
            .then(function () { window.location.href = '/thread-dump'; })
            .catch(function (e) { btn.disabled = false; var err = byId('thdDeleteErr'); if (err) { err.textContent = errMsg(e, '삭제하지 못했습니다.'); err.classList.add('show'); } });
    }

    // ── 힙 점유(2.7.1) ────────────────────────────────────────
    var _link = null;          // 연결 뷰(GET …/heap-link) — 스레드 덤프 모드에서만

    /** 스레드 t 의 힙 정보 {r,l,c,s} — 힙 모드는 트랙에, 연결 모드는 연결 뷰의 이름 표에 있다. */
    function heapInfoOf(t) {
        var tr = R && R.tracks[t];
        if (!tr) return null;
        if (HEAP_MODE) return tr.hr != null || tr.hl != null || tr.hc ? { r: tr.hr, l: tr.hl, c: tr.hc } : null;
        if (!_link || !_link.memory) return null;
        return _link.memory[nameOf(t)] || null;
    }
    function memOf(t) { var h = heapInfoOf(t); return h && h.r != null ? h.r : null; }
    function heapTop(n) {
        var out = [];
        if (!R) return out;
        R.tracks.forEach(function (tr, t) { var r = memOf(t); if (r != null) out.push({ t: t, r: r }); });
        out.sort(function (a, b) { return b.r - a.r; });
        return out.slice(0, n);
    }
    function renderHeapMemory() {
        var table = byId('thdThreadTable');
        var any = heapTop(1).length > 0;
        if (table) table.classList.toggle('thd-has-mem', any);
        var card = byId('thdMemCard');
        if (!card) return;
        var top = heapTop(10);
        card.style.display = top.length ? '' : 'none';
        if (!top.length) return;
        byId('thdMemNote').textContent = HEAP_MODE ? 'MAT 가 스레드 객체마다 계산한 Retained Heap 입니다(스레드가 끝나면 풀려날 크기).'
            : '연결한 힙 덤프(' + (_link.heapFilename || '') + ')에서 이름이 같은 스레드의 Retained Heap 입니다. 두 덤프의 시각이 다르면 같은 일을 하던 순간이 아닐 수 있습니다.';
        byId('thdMemBody').innerHTML = top.map(function (x) {
            var h = heapInfoOf(x.t) || {};
            var sid = anyStack(x.t, _focus), f = topFrame(sid);
            return '<tr><td class="name">' + threadLink(x.t) + '</td><td>' + stateBadge(stateAt(x.t, _focus)) + '</td>'
                + '<td class="num">' + esc(fmtBytes(x.r)) + '</td><td class="num thd-hide-sm">' + (h.l != null ? esc(fmtBytes(h.l)) : '–') + '</td>'
                + '<td class="frame" title="' + esc(f) + '">' + esc(f || '–') + '</td></tr>';
        }).join('');
    }

    // ── 힙 덤프 연결(2.7.1, 수동) ─────────────────────────────
    function renderLinkChip() {
        var el = byId('thdHeapLinkChip');
        if (!el || !_link) return;
        if (_link.available === false) { el.style.display = 'none'; return; }
        el.style.display = '';
        var v = byId('thdHeapLinkValue');
        if (!_link.linked) {
            v.textContent = '연결 안 됨'; v.classList.add('empty'); v.removeAttribute('href');
            byId('thdHeapLinkEdit').textContent = '연결';
            return;
        }
        v.classList.remove('empty');
        v.textContent = _link.heapFilename;
        v.setAttribute('href', '/analyze/' + encodeURIComponent(_link.heapFilename));
        var tip = [];
        if (_link.dumpTime) tip.push('생성 ' + _link.dumpTime);
        if (_link.gapSec != null) tip.push(_link.gapSec === 0 ? '스냅샷 구간 안' : '스냅샷과 ' + fmtSec(_link.gapSec) + ' 차이');
        if (_link.matchedThreads != null) tip.push('이름 일치 스레드 ' + _link.matchedThreads + '/' + _link.threadCount);
        if (_link.memoryError) tip.push('힙 스레드 정보: ' + _link.memoryError);
        if (_link.heapExists === false) tip.push('힙 덤프 분석 기록이 없습니다');
        v.title = tip.join(' · ');
        byId('thdHeapLinkEdit').textContent = '변경';
    }
    function loadHeapLink() {
        if (HEAP_MODE || !byId('thdHeapLinkChip')) return Promise.resolve();
        return Common.fetchJSON(api('/heap-link')).then(function (d) {
            _link = d;
            renderLinkChip();
            if (R) { try { loadThreadSnapshot(_snap); renderHeapMemory(); } catch (e) { failed('힙 점유 다시 그리기', e); } }
        }).catch(function (e) { ignored('힙 덤프 연결 조회', e); });
    }
    function thdOpenHeapLink() {
        var box = byId('thdHeapCandList');
        box.innerHTML = '<div class="gcl-empty">후보를 찾는 중…</div>';
        byId('thdHeapUnlinkBtn').style.display = _link && _link.linked ? '' : 'none';
        var err = byId('thdHeapLinkErr'); err.textContent = ''; err.classList.remove('show');
        showModal(byId('thdHeapLinkModal'));
        Common.fetchJSON(api('/heap-candidates')).then(function (d) {
            var list = d.candidates || [];
            if (!list.length) { box.innerHTML = '<div class="gcl-empty">분석이 끝난 힙 덤프가 없습니다.</div>'; return; }
            box.innerHTML = list.slice(0, 50).map(function (c) {
                var why = (c.reasons || []).length ? c.reasons.join(' · ') : '일치 근거 없음';
                return '<button type="button" class="thd-cand' + (c.linked ? ' current' : '') + (c.score > 0 ? ' scored' : '') + '" data-fn="' + esc(c.filename) + '" onclick="thdPickHeap(this.dataset.fn)">'
                    + '<span class="thd-cand-main"><span class="thd-cand-name">' + esc(c.filename) + '</span>'
                    + (c.linked ? '<span class="thd-tag worker">연결됨</span>' : '')
                    + (c.score > 0 ? '<span class="thd-cand-score" title="일치 근거 수">' + c.score + '</span>' : '') + '</span>'
                    + '<span class="thd-cand-sub">' + esc([c.dumpTime ? '생성 ' + c.dumpTime : '생성 시각 모름', c.serverName ? '서버 ' + c.serverName : ''].filter(Boolean).join(' · ')) + '</span>'
                    + '<span class="thd-cand-why' + (c.score > 0 ? '' : ' none') + '">' + esc(why) + (c.nameMatchError ? ' (스레드 정보 없음)' : '') + '</span></button>';
            }).join('');
        }).catch(function (e) { box.innerHTML = '<div class="gcl-empty">' + esc(errMsg(e, '후보를 불러오지 못했습니다.')) + '</div>'; });
    }
    function thdCloseHeapLink() { hideModal(byId('thdHeapLinkModal')); }
    function saveHeapLink(heapFn) {
        return Common.fetchJSON(api('/heap-link'), { method: 'POST', body: JSON.stringify({ heapFilename: heapFn || '' }) })
            .then(function (d) {
                _link = d;
                renderLinkChip();
                thdCloseHeapLink();
                if (R) { loadThreadSnapshot(_snap); renderHeapMemory(); }
                toast(heapFn ? '힙 덤프를 연결했습니다.' : '힙 덤프 연결을 끊었습니다.', 'success');
                var frame = byId('thdReportFrame');
                if (frame && frame.getAttribute('src') && _reportMode) thdReportMode(_reportMode, false);
            })
            .catch(function (e) { var err = byId('thdHeapLinkErr'); err.textContent = errMsg(e, '저장하지 못했습니다.'); err.classList.add('show'); });
    }
    function thdPickHeap(fn) { saveHeapLink(fn); }
    function thdUnlinkHeap() { saveHeapLink(''); }

    // ── 이어 붙인 구성 파일(2.7.1) ────────────────────────────
    function renderMembers() {
        var card = byId('thdMembersCard');
        if (!card) return;
        var files = R.meta.files || [];
        card.style.display = files.length ? '' : 'none';
        if (!files.length) return;
        var miss = {};
        ((R.diagnostics || {}).membersWithoutDump || []).forEach(function (x) { miss[String(x).split(' — ')[0]] = x; });
        var count = {};
        R.snapshots.forEach(function (s) { if (s.file) count[s.file] = (count[s.file] || 0) + 1; });
        byId('thdMembersCount').textContent = files.length + '개';
        byId('thdMembersBody').innerHTML = files.map(function (f, i) {
            return '<tr><td class="num">' + (i + 1) + '</td><td class="name" title="' + esc(f) + '">' + esc(f) + (f === THD_FILENAME ? ' <span class="thd-tag worker">대표</span>' : '') + '</td>'
                + '<td class="num">' + (count[f] ? fmtNum(count[f]) : '0') + '</td>'
                + '<td>' + (miss[f] ? '<span class="hi-muted">' + esc(miss[f].split(' — ')[1] || '빠짐') + '</span>' : '<span class="thd-tag">분석됨</span>') + '</td></tr>';
        }).join('');
    }
    function thdOpenUngroup() { byId('thdUngroupBtn').disabled = false; showModal(byId('thdUngroupModal')); }
    function thdCloseUngroup() { hideModal(byId('thdUngroupModal')); }
    function thdConfirmUngroup() {
        var btn = byId('thdUngroupBtn'); btn.disabled = true;
        Common.fetchJSON(api('/ungroup'), { method: 'POST' })
            .then(function () { window.location.href = '/thread-dump'; })
            .catch(function (e) { btn.disabled = false; thdCloseUngroup(); toast('묶음 해제 실패: ' + errMsg(e, ''), 'danger'); });
    }

    // ── 초기화 ────────────────────────────────────────────────
    document.addEventListener('DOMContentLoaded', function () {
        // ?start=1 — 확인 모달을 거쳐 온 '분석 시작'. 한 번만 쓰고 주소에서 지운다(새로고침·뒤로가기로 다시 시작되지 않게)
        var wantStart = false;
        try {
            wantStart = new URLSearchParams(window.location.search).get('start') === '1';
            if (wantStart) history.replaceState(null, document.title, window.location.pathname);
        } catch (e) { ignored('start 파라미터 처리', e); }

        document.addEventListener('keydown', function (ev) {
            if (ev.key === 'Escape') { thdCloseAi(); thdCloseReanalyze(); thdCloseDelete(); thdCloseServerEdit(); thdCloseHeapLink(); thdCloseUngroup(); thdCloseExport(); }
        });
        var serverInput = byId('thdServerInput');
        if (serverInput) serverInput.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') thdSaveServer(); });

        if (HEAP_MODE) { loadAndRender(); syncAiButtons(); return; }
        loadHeapLink();
        if (THD_STATUS === 'SUCCESS') loadAndRender();
        else if (THD_STATUS === 'ANALYZING') startPolling();
        else if (THD_STATUS === 'NOT_ANALYZED' && THD_FILE_EXISTS) startAnalysis();          // 미분석 파일을 열었다 = 분석 의사
        else if (THD_STATUS === 'ERROR' && wantStart && THD_FILE_EXISTS) startAnalysis();    // 확인 모달에서 '분석 시작' 했다
        else if (THD_STATUS === 'ERROR') renderErrorHint(byId('thdErrorMsg') ? byId('thdErrorMsg').textContent : '', THD_ERROR_CODE);
        syncAiButtons();
    });

    // ── 힙 모드: 스레드 덤프 파일로 내보내기 ─────────────────────
    // POST /api/thread-dump/heap/{fn}/export[?newName=true] → 스레드 덤프 저장소에 <이름>_threads.txt 로 저장·등록(이 힙 덤프와 연결됨).
    // 모달 한 개가 확인 → 진행 → 완료 / 이미 있음(409 EXPORT_EXISTS) / 실패 상태를 오간다.
    var _exporting = false;
    function tdUrl(kind, name) {
        var enc = encodeURIComponent(name);
        return kind === 'list' ? '/thread-dump?file=' + enc : '/thread-dump/analyze/' + enc;
    }
    function exportState(state, msg, name) {
        var box = byId('thdExportMsg');
        var show = function (id, on) { var el = byId(id); if (el) el.hidden = !on; };
        box.hidden = !msg;
        box.className = 'thd-export-msg' + (state === 'done' ? ' ok' : state === 'exists' ? ' warn' : state === 'error' ? ' bad' : '');
        box.innerHTML = msg || '';
        byId('thdExportInfo').hidden = state === 'done' || state === 'exists';
        show('thdExportGo', state === 'confirm' || state === 'working' || state === 'error');
        show('thdExportNew', state === 'exists');
        show('thdExportList', (state === 'done' || state === 'exists') && !!name);
        show('thdExportOpen', state === 'done' && !!name);
        if (name) { byId('thdExportList').dataset.href = tdUrl('list', name); byId('thdExportOpen').dataset.href = tdUrl('analyze', name); }
        byId('thdExportGo').disabled = state === 'working';
        byId('thdExportNew').disabled = state === 'working';
        byId('thdExportGo').textContent = state === 'working' ? '내보내는 중…' : (state === 'error' ? '다시 시도' : '내보내기');
        byId('thdExportCancel').textContent = state === 'done' || state === 'exists' ? '닫기' : '취소';
    }
    function thdOpenExport() {
        var ov = byId('thdExportModal');
        if (!ov || _exporting) return;
        exportState('confirm', '', null);
        showModal(ov);
        byId('thdExportGo').focus();
    }
    function thdCloseExport() {
        var ov = byId('thdExportModal');
        if (ov && !_exporting) hideModal(ov);
    }
    function thdRunExport(newName) {
        if (_exporting) return;
        _exporting = true;
        exportState('working', '<span class="gcl-spinner" aria-hidden="true"></span> 스레드 덤프 파일을 만드는 중입니다…', null);
        Common.fetchJSON(api('/export') + (newName ? '?newName=true' : ''), { method: 'POST' })
            .then(function (d) {
                _exporting = false;
                var head = '<b>' + esc(d.filename) + '</b> (스레드 ' + fmtNum(d.threads) + '개, ' + esc(fmtBytes(d.size)) + ')<br>';
                if (d.status === 'SUCCESS') {
                    exportState('done', '내보내고 분석까지 마쳤습니다 — ' + head + '스레드 덤프 목록에 <b>완료</b> 상태로 올라갔고 이 힙 덤프와 연결돼 있습니다.', d.filename);
                } else if (d.status === 'ERROR') {
                    exportState('done', '파일은 내보냈지만 분석에 실패했습니다 — ' + head + esc(d.analysisError || '') + ' 결과 화면에서 다시 분석할 수 있습니다.', d.filename);
                    byId('thdExportMsg').className = 'thd-export-msg warn';
                } else {
                    exportState('done', '내보냈고 분석 중입니다 — ' + head + '앞선 분석이 끝나는 대로 이어서 분석합니다. 결과 화면에서 진행을 볼 수 있습니다.', d.filename);
                }
                byId('thdExportOpen').focus();
            })
            .catch(function (e) {
                _exporting = false;
                var body = null;
                try { body = e && e.body ? JSON.parse(e.body) : null; } catch (x) { ignored('내보내기 오류 본문 파싱', x); }
                if (body && body.code === 'EXPORT_EXISTS') {
                    exportState('exists', '이미 내보낸 파일이 있습니다 — <b>' + esc(body.filename) + '</b><br>'
                        + '목록에서 그 파일을 보거나, 새 이름(<code>_threads-2.txt</code> …)으로 한 번 더 내보낼 수 있습니다. 기존 파일은 건드리지 않습니다.', body.filename);
                    return;
                }
                failed('스레드 덤프 내보내기 실패', e);
                exportState('error', esc(errMsg(e, '내보내지 못했습니다.')), null);
            });
    }

    window.thdOpenExport = thdOpenExport;
    window.thdCloseExport = thdCloseExport;
    window.thdRunExport = thdRunExport;
    window.thdTab = thdTab;
    window.thdCancel = thdCancel;
    window.thdOpenReanalyze = thdOpenReanalyze;
    window.thdCloseReanalyze = thdCloseReanalyze;
    window.thdConfirmReanalyze = thdConfirmReanalyze;
    window.thdShowThread = thdShowThread;
    window.thdDetailSnap = thdDetailSnap;
    window.thdToggleState = thdToggleState;
    window.thdToggleGroup = thdToggleGroup;
    window.thdReportMode = thdReportMode;
    window.thdRunAi = thdRunAi;
    window.thdCloseAi = thdCloseAi;
    window.thdConfirmAi = thdConfirmAi;
    window.thdDeleteAi = thdDeleteAi;
    window.thdOpenServerEdit = thdOpenServerEdit;
    window.thdCloseServerEdit = thdCloseServerEdit;
    window.thdSaveServer = thdSaveServer;
    window.thdDownloadOriginal = thdDownloadOriginal;
    window.thdOpenDelete = thdOpenDelete;
    window.thdCloseDelete = thdCloseDelete;
    window.thdConfirmDelete = thdConfirmDelete;
    window.thdOpenHeapLink = thdOpenHeapLink;
    window.thdCloseHeapLink = thdCloseHeapLink;
    window.thdPickHeap = thdPickHeap;
    window.thdUnlinkHeap = thdUnlinkHeap;
    window.thdOpenUngroup = thdOpenUngroup;
    window.thdCloseUngroup = thdCloseUngroup;
    window.thdConfirmUngroup = thdConfirmUngroup;
})();
