/* gc-log-analyze.js — GC 로그 결과 페이지 (gc-log/analyze.html, 2026-09-14)
 * 상태 폴링(분석 중) · KPI/차트/소견/이벤트 표/원문 렌더 · 힙 덤프 매칭 칩 · AI 분석(확인 모달 · 경과 시간).
 * 폴링은 SessionTimeout.managedInterval + registerActivityGuard 경유(함정 38). Chart.js 는 date adapter 가 없어
 * x 축을 uptime 초(linear)로 두고 눈금 콜백이 h:mm 으로 바꾼다. */
(function () {
    'use strict';

    var TAG = '[GcLogAnalyze]';
    function ignored(where, e) { if (window.Common) window.Common.logIgnored(TAG + ' ' + where, e); }
    function failed(where, e)  { if (window.Common) window.Common.logError(TAG + ' ' + where, e); }

    var esc = function (s) { return window.Common ? Common.escHtml(String(s == null ? '' : s)) : String(s == null ? '' : s); };
    var fmtBytes = function (b) { return (b == null) ? '–' : Common.formatBytes(b); };
    function fmtMs(v) { return v == null ? '–' : (v >= 1000 ? (v / 1000).toFixed(2) + ' s' : v.toFixed(1) + ' ms'); }
    function fmtDur(sec) {
        if (sec == null) return '–';
        var s = Math.round(sec);
        if (s >= 3600) return Math.floor(s / 3600) + 'h ' + ('0' + Math.floor((s % 3600) / 60)).slice(-2) + 'm';
        if (s >= 60) return Math.floor(s / 60) + 'm ' + ('0' + (s % 60)).slice(-2) + 's';
        return sec.toFixed(1) + 's';
    }
    function fmtTs(ms) {
        if (ms == null) return '';
        var d = new Date(ms);
        function p(n) { return (n < 10 ? '0' : '') + n; }
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    }
    function fmtSlope(v) { return (v >= 0 ? '+' : '') + (Math.abs(v) < 1 ? v.toFixed(2) : v.toFixed(1)); }
    function pct(v, digits) { return v == null ? '–' : v.toFixed(digits == null ? 1 : digits) + '%'; }

    function toast(msg, type) {
        var wrap = document.getElementById('cdToastWrap');
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

    /** 서버 오류 본문 {success,code,error} 에서 사람이 읽을 문구를 꺼낸다(함정 14). */
    function errMsg(e, fallback) {
        if (!e) return fallback;
        if (e.sessionExpired) return '세션이 만료되었습니다. 다시 로그인하세요.';
        if (e.body) { try { var d = JSON.parse(e.body); if (d && (d.error || d.message)) return d.error || d.message; } catch (x) { ignored('오류 본문 파싱', x); } }
        return e.message || fallback;
    }

    var api = function (path) { return '/api/gc-log/' + encodeURIComponent(GC_FILENAME) + path; };

    // ── 상태 폴링 (분석 중) ───────────────────────────────────
    var _pollHandle = null;
    var _analyzing = false;

    function startPolling() {
        if (_pollHandle) return;
        _analyzing = true;
        if (window.SessionTimeout && SessionTimeout.registerActivityGuard) SessionTimeout.registerActivityGuard(function () { return _analyzing; });
        var tick = function () {
            Common.fetchJSON(api('/status')).then(function (d) {
                renderProgress(d);
                if (d.status === 'SUCCESS') { stopPolling(); window.location.reload(); }
                else if (d.status === 'ERROR' || d.status === 'MISSING') { stopPolling(); showError(d.error || (d.status === 'MISSING' ? '파일이 없습니다.' : '분석 실패')); }
            }).catch(function (e) {
                if (e && e.sessionExpired) { stopPolling(); toast('세션이 만료되었습니다. 다시 로그인하세요.', 'danger'); return; }
                ignored('상태 폴링 실패', e);
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
    function renderProgress(d) {
        var fill = document.getElementById('gclProgressFill');
        var pctEl = document.getElementById('gclProgressPct');
        var p = d.progressPct == null ? 0 : d.progressPct;
        if (fill) fill.style.width = p + '%';
        if (pctEl) pctEl.textContent = p + '%';
        var b = document.getElementById('gclProgressBytes'); if (b && d.totalBytes) b.textContent = fmtBytes(d.bytesRead || 0) + ' / ' + fmtBytes(d.totalBytes);
        var l = document.getElementById('gclProgressLines'); if (l && d.lines != null) l.textContent = Number(d.lines).toLocaleString() + ' 줄';
        var ev = document.getElementById('gclProgressEvents'); if (ev && d.events != null) ev.textContent = Number(d.events).toLocaleString() + ' 이벤트';
        var ph = document.getElementById('gclProgressPhase'); if (ph && d.phase) ph.textContent = d.phase === 'parsing' ? '파싱 중' : d.phase === 'saving' ? '저장 중' : d.phase === 'queued' ? '대기 중' : '';
        var sp = document.getElementById('gclSpinner'); if (sp) sp.style.display = '';
        var cancel = document.getElementById('gclCancelBtn'); if (cancel) cancel.style.display = '';
        var title = document.getElementById('gclProgressTitle'); if (title) title.textContent = 'GC 로그를 분석하는 중입니다';
    }
    function showError(msg) {
        var box = document.getElementById('gclError'); if (box) box.style.display = '';
        var m = document.getElementById('gclErrorMsg'); if (m) m.textContent = msg;
        var sp = document.getElementById('gclSpinner'); if (sp) sp.style.display = 'none';
        var cancel = document.getElementById('gclCancelBtn'); if (cancel) cancel.style.display = 'none';
        var title = document.getElementById('gclProgressTitle'); if (title) title.textContent = '분석이 끝나지 않았습니다';
        setRetryDisabled(!GC_FILE_EXISTS);
        var prog = document.getElementById('gclProgress'); if (prog) prog.style.display = 'none';   // 실패면 진행 막대는 의미가 없다
    }

    /** 진행 화면으로 전환 — 결과·옛 오류를 감추고 진행 블록을 보인다. */
    function showRunning() {
        var res = document.getElementById('gclResult'); if (res) res.style.display = 'none';
        var prog = document.getElementById('gclProgress'); if (prog) prog.style.display = '';
        var err = document.getElementById('gclError'); if (err) err.style.display = 'none';
        var sp = document.getElementById('gclSpinner'); if (sp) sp.style.display = '';
        var title = document.getElementById('gclProgressTitle'); if (title) title.textContent = '분석을 시작하는 중입니다';
    }

    function setRetryDisabled(disabled) {
        ['btnReanalyze', 'gclRetryBtn'].forEach(function (id) { var b = document.getElementById(id); if (b) b.disabled = disabled; });
    }

    /** 미분석·실패 파일의 분석 시작(POST /analyze). 이미 SUCCESS 면 서버가 그대로 알려 준다. */
    function startAnalysis() {
        setRetryDisabled(true);
        showRunning();
        Common.fetchJSON('/api/gc-log/analyze/' + encodeURIComponent(GC_FILENAME), { method: 'POST' })
            .then(function (d) { if (d.status === 'SUCCESS') window.location.reload(); else startPolling(); })
            .catch(function (e) { failed('분석 시작 실패', e); setRetryDisabled(false); showError(errMsg(e, '분석을 시작하지 못했습니다.')); });
    }

    // ── 재분석 확인 모달 (2026-09-16) ───────────────────────────
    // 재분석은 저장된 결과를 덮으므로 버튼(헤더 '재분석' · 오류 상자 '다시 분석' · 옛 결과 배너 '재분석')은 모달만 연다.
    // 요청은 confirmReanalyze → reanalyze 에서만 나간다.
    var _reanalyzeReturnFocus = null;
    function openReanalyzeConfirm() {
        var modal = document.getElementById('reanalyzeModal');
        if (!modal) { reanalyze(); return; }   // 템플릿이 옛 캐시일 때 — 동작은 막지 않는다
        var hasResult = GC_STATUS === 'SUCCESS';
        document.getElementById('reanalyzeTitle').textContent = hasResult ? 'GC 로그 재분석' : 'GC 로그 다시 분석';
        document.getElementById('reanalyzeDesc').textContent = hasResult
            ? '이 GC 로그를 처음부터 다시 분석합니다.'
            : '마지막 분석이 실패했습니다. 이 GC 로그를 처음부터 다시 분석합니다.';
        document.getElementById('reanalyzeReplace').style.display = hasResult ? '' : 'none';
        document.getElementById('reanalyzeAi').style.display = hasResult && _aiHtml ? '' : 'none';
        var btn = document.getElementById('reanalyzeConfirmBtn');
        btn.textContent = hasResult ? '재분석' : '다시 분석';
        btn.disabled = false;
        _reanalyzeReturnFocus = document.activeElement;
        modal.classList.add('open');
        btn.focus();
    }
    function closeReanalyzeConfirm() {
        var modal = document.getElementById('reanalyzeModal');
        if (!modal || !modal.classList.contains('open')) return;
        modal.classList.remove('open');
        if (_reanalyzeReturnFocus && _reanalyzeReturnFocus.focus) { try { _reanalyzeReturnFocus.focus(); } catch (e) { ignored('재분석 모달 포커스 복귀', e); } }
        _reanalyzeReturnFocus = null;
    }
    function confirmReanalyze() {
        var btn = document.getElementById('reanalyzeConfirmBtn');
        if (btn) btn.disabled = true;   // 두 번 눌러 요청이 두 번 나가지 않게
        closeReanalyzeConfirm();
        reanalyze();
    }

    function reanalyze() {
        setRetryDisabled(true);
        Common.fetchJSON('/api/gc-log/reanalyze/' + encodeURIComponent(GC_FILENAME), { method: 'POST' })
            .then(function () {
                showRunning();
                startPolling();
            })
            .catch(function (e) { setRetryDisabled(false); toast('재분석 실패: ' + errMsg(e, ''), 'danger'); });
    }

    function cancelAnalysis() {
        Common.fetchJSON('/api/gc-log/cancel/' + encodeURIComponent(GC_FILENAME), { method: 'POST' })
            .then(function () { toast('취소를 요청했습니다.', 'info'); })
            .catch(function (e) { toast('취소 실패: ' + errMsg(e, ''), 'danger'); });
    }

    // ── 결과 렌더 ────────────────────────────────────────────
    var R = null;

    function loadResult() {
        var el = document.getElementById('gcResult');
        if (!el) return null;
        try {
            var r = JSON.parse(el.textContent || 'null');
            return r && r.meta ? r : null;
        } catch (e) { failed('결과 JSON 파싱', e); return null; }
    }

    function kpiCard(label, value, sub, cls) {
        return '<div class="gcl-kpi ' + (cls || '') + '"><div class="gcl-kpi-label">' + esc(label) + '</div><div class="gcl-kpi-value" title="' + esc(value) + '">' + esc(value) + '</div>'
            + (sub ? '<div class="gcl-kpi-sub" title="' + esc(sub) + '">' + esc(sub) + '</div>' : '') + '</div>';
    }

    // ── 메모리 압박 Full GC 배너 (2026-09-16) ─────────────────
    // fullGcSummary(엔진의 Full GC 분류 블록)로 그린다. 상태 5종: 압박 있음(심각도 색) / 힙 압박 아님(Metaspace·GCLocker 만) /
    // 전부 명시적(.ok) / 옛 결과(블록 없음 → .legacy + 재분석 버튼) / Full 없음(숨김).
    var KIND_LABEL = { HEAP_PRESSURE: '압박', EXPLICIT: '명시적', METASPACE: 'Metaspace', GC_LOCKER: 'GCLocker', OTHER: '기타' };
    var PRESSURE_FINDINGS = { FULL_GC_LOW_RECLAIM: 1, FULL_GC_FREQUENT: 1, FULL_GC_INTERVAL_SHRINKING: 1 };
    var SEV_RANK = { Critical: 5, High: 4, Medium: 3, Low: 2, Info: 1 };
    function pressureSeverity(r) {
        var best = 'Low';
        (r.findings || []).forEach(function (f) { if (PRESSURE_FINDINGS[f.code] && (SEV_RANK[f.severity] || 0) > (SEV_RANK[best] || 0)) best = f.severity; });
        return best;
    }
    function pressureChip(label, n, muted) {
        return '<span class="gcl-pressure-chip' + (muted ? ' muted' : '') + '">' + esc(label) + ' <span class="cnt">' + esc(n) + '</span></span>';
    }
    function renderPressureBanner(r) {
        var box = document.getElementById('gclPressureBanner');
        if (!box) return;
        var s = r.fullGcSummary, k = r.kpi;
        box.className = 'gcl-pressure';
        if (!s) {
            // 분류 이전 옛 결과 — Full GC 가 있었다면 재분석해야 압박 여부를 알 수 있다
            if (!(k.fullCount > 0)) { box.hidden = true; return; }
            box.className = 'gcl-pressure legacy';
            box.innerHTML = '<div class="gcl-pressure-head"><span class="gcl-pressure-title">Full GC ' + esc(k.fullCount) + '회 — 이전 분석 결과라 Full GC 분류가 없습니다.</span></div>'
                + '<div class="gcl-pressure-line">재분석하면 압박 Full GC 분류를 볼 수 있습니다(메모리 압박 · Metaspace · GCLocker · 명시적 호출, 회수율·직후 점유율).</div>'
                + (GC_FILE_EXISTS ? '<div class="gcl-pressure-actions"><button type="button" class="btn btn-sm btn-primary" onclick="openReanalyzeConfirm()">재분석</button></div>' : '');
            box.hidden = false;
            return;
        }
        if (!(s.total > 0)) { box.hidden = true; return; }
        var bk = s.byKind || {};
        var meta = bk.METASPACE || 0, locker = bk.GC_LOCKER || 0, other = bk.OTHER || 0, explicit = bk.EXPLICIT || 0;
        var h;
        if (s.heapPressureCount > 0) {
            var sev = pressureSeverity(r);
            box.className = 'gcl-pressure sev-' + sev;
            h = '<div class="gcl-pressure-head"><span class="gcl-pressure-sev">' + esc(sev) + '</span>'
                + '<span class="gcl-pressure-title">메모리 압박 Full GC ' + esc(s.heapPressureCount) + '회</span>'
                + (s.heapPressurePerHour != null ? '<span class="gcl-pressure-rate">시간당 ' + s.heapPressurePerHour.toFixed(2) + '회</span>' : '') + '</div>';
            var chips = '';
            Object.keys(s.pressureByCause || {}).forEach(function (c) { chips += pressureChip(c, s.pressureByCause[c], false); });
            if (meta) chips += pressureChip('Metaspace', meta, true);
            if (locker) chips += pressureChip('GCLocker', locker, true);
            if (other) chips += pressureChip('기타', other, true);
            if (explicit) chips += pressureChip('명시적', explicit, true);
            if (chips) h += '<div class="gcl-pressure-chips">' + chips + '</div>';
            if (s.reclaimSamples > 0) {
                h += '<div class="gcl-pressure-line">회수율 중앙값 <b>' + pct(s.reclaimPctMedian, 0) + '</b> · 최저 <b>' + pct(s.reclaimPctMin, 0) + '</b> · 20% 미만 <b>' + esc(s.lowReclaimCount) + '회</b>'
                    + ' · 직후 점유 85% 이상 <b>' + esc(s.highOccupancyCount) + '회</b> · 연속 최대 <b>' + esc(s.maxConsecutivePressure) + '회</b></div>';
            } else if (s.note) h += '<div class="gcl-pressure-line">' + esc(s.note) + '</div>';
            if (s.lastAfterBytes != null) {
                h += '<div class="gcl-pressure-line">마지막 압박 Full GC 직후 힙 <b>' + esc(fmtBytes(s.lastAfterBytes)) + '</b>' + (s.lastTotalBytes != null ? ' / ' + esc(fmtBytes(s.lastTotalBytes)) : '')
                    + (s.lastAfterRatio != null ? ' (<b>' + pct(s.lastAfterRatio * 100, 0) + '</b>)' : '') + (s.lastAfterCapRatio != null ? ' · 최대 힙 대비 ' + pct(s.lastAfterCapRatio * 100, 0) : '')
                    + (s.lastAtSec != null ? ' · uptime ' + esc(fmtDur(s.lastAtSec)) : '') + (s.lastLine != null ? ' · 줄 ' + esc(s.lastLine) : '') + '</div>';
            }
            if (s.intervalShrinking === true) {
                h += '<div class="gcl-pressure-line">간격 앞 절반 중앙값 <b>' + esc(fmtDur(s.intervalMedianFirstHalfSec)) + '</b> → 뒤 절반 <b>' + esc(fmtDur(s.intervalMedianSecondHalfSec)) + '</b> — 짧아지는 중</div>';
            }
            h += '<div class="gcl-pressure-actions"><button type="button" class="btn btn-sm btn-secondary" onclick="showPressureEvents()">해당 이벤트 보기</button>'
                + '<a id="gclPressureDump" class="btn btn-sm btn-ghost" href="#" hidden>연결된 힙 덤프</a></div>';
        } else if (meta + locker + other > 0) {
            box.className = 'gcl-pressure sev-Low';
            h = '<div class="gcl-pressure-head"><span class="gcl-pressure-sev">Low</span><span class="gcl-pressure-title">Full GC ' + esc(s.total) + '회 — 힙 압박 아님</span></div>'
                + '<div class="gcl-pressure-chips">' + (meta ? pressureChip('Metaspace 임계치', meta, true) : '') + (locker ? pressureChip('GCLocker', locker, true) : '')
                + (other ? pressureChip('기타', other, true) : '') + (explicit ? pressureChip('명시적', explicit, true) : '') + '</div>'
                + '<div class="gcl-pressure-line">힙이 차서 일어난 Full GC 는 없습니다' + (meta ? ' — Metaspace 임계치는 클래스 로딩(Metaspace 부족) 신호이니 METADATA_THRESHOLD 소견을 보세요' : '') + '.</div>';
        } else {
            box.className = 'gcl-pressure ok';
            h = '<div class="gcl-pressure-head"><span class="gcl-pressure-sev">OK</span><span class="gcl-pressure-title">Full GC ' + esc(s.total) + '회는 전부 명시적 호출(System.gc() 등) — 메모리 압박 신호 아님</span>'
                + (k.systemGcIntervalSec != null ? '<span class="gcl-pressure-rate">약 ' + esc(fmtDur(k.systemGcIntervalSec)) + ' 간격</span>' : '') + '</div>';
        }
        box.innerHTML = h;
        box.hidden = false;
        syncPressureDumpLink();
    }
    function syncPressureDumpLink() {
        var a = document.getElementById('gclPressureDump');
        if (!a) return;
        var matched = _matchView && _matchView.matched;
        if (matched) { a.href = '/analyze/' + encodeURIComponent(matched.dumpFilename); a.hidden = false; }
        else a.hidden = true;
    }
    /** 배너 '해당 이벤트 보기' — 유형 FULL + 검색 '압박' 을 기존 applyFilter 3 술어로 건다(detach 표: tbody 직접 조작 금지). 검색창을 비우면 원래대로. */
    function showPressureEvents() {
        if (!_evGrid) return;
        _evTypeSel = { FULL: true };
        if (_evTypeSync) _evTypeSync();
        var search = document.getElementById('gclEvSearch'); if (search) search.value = '압박';
        var only = document.getElementById('gclEvOnlyFlag'); if (only) only.checked = false;
        if (_evApplyFilter) _evApplyFilter();
        var tbl = document.getElementById('gclEvTable'); if (tbl && tbl.scrollIntoView) tbl.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    function renderKpi(r) {
        var k = r.kpi, m = r.meta, ps = r.pauseStats, t = r.trend;
        var h = '';
        h += kpiCard('수집기 · JDK', m.collector + (m.jdkVersion ? ' · ' + m.jdkVersion : ''), (m.format === 'UNIFIED' ? '통합 로깅' : 'JDK 8 이하 PrintGCDetails') + (m.truncated ? ' · 일부 절단' : ''));
        var timeSub = m.timeSource === 'absolute' ? fmtTs(m.logStartEpochMs) + ' ~ ' + fmtTs(m.logEndEpochMs) : m.timeSource === 'mtime' ? '절대 시각 없음 — mtime 기준 추정' : '절대 시각 없음(uptime 만)';
        h += kpiCard('로그 기간', fmtDur(m.durationSec), timeSub);
        var tpCls = k.throughputPct == null ? '' : k.throughputPct < 90 ? 'bad' : k.throughputPct < 95 ? 'warn' : '';
        h += kpiCard('처리량', pct(k.throughputPct, 2), 'GC 일시정지 합 ' + fmtMs(ps.totalMs) + ' / ' + ps.count + '회', tpCls);
        h += kpiCard('일시정지 p99 · 최대', fmtMs(ps.p99Ms) + ' · ' + fmtMs(ps.maxMs), 'p50 ' + fmtMs(ps.p50Ms) + ' · p95 ' + fmtMs(ps.p95Ms) + (ps.approximate ? ' (근사)' : ''), ps.maxMs > 5000 ? 'bad' : ps.maxMs > 1000 ? 'warn' : '');
        // 경고색은 명시적 호출(System.gc() 등)을 뺀 압박 Full 빈도 기준 — 필드가 없는 옛 결과는 전체 빈도(2026-09-15)
        var fullRate = k.pressureFullGcPerHour != null ? k.pressureFullGcPerHour : k.fullGcPerHour;
        var fs = r.fullGcSummary;
        if (fs) {
            // 분류 블록이 있으면 '압박 Full GC' / '명시적 Full GC' 두 카드(2026-09-16) — 경고색은 압박 쪽에만
            var ph = fs.heapPressurePerHour;
            var lowReclaim = (r.findings || []).some(function (f) { return f.code === 'FULL_GC_LOW_RECLAIM'; });
            var pCls = lowReclaim || ph > 6 ? 'bad' : ph > 1 ? 'warn' : '';
            var pSub = (ph != null ? '시간당 ' + ph.toFixed(2) + '회 · ' : '') + '연속 최대 ' + fs.maxConsecutivePressure + (fs.reclaimSamples > 0 ? ' · 회수율 중앙값 ' + pct(fs.reclaimPctMedian, 0) : '')
                + ((fs.byKind && (fs.byKind.METASPACE || 0)) ? ' · Metaspace ' + fs.byKind.METASPACE : '');
            h += kpiCard('압박 Full GC', String(fs.heapPressureCount), pSub, pCls);
            var eSub = 'System.gc() ' + k.systemGcCount + '회' + (k.systemGcIntervalSec != null ? ' · 약 ' + fmtDur(k.systemGcIntervalSec) + ' 간격' : '') + ' · 전체 Full ' + k.fullCount + '회';
            h += kpiCard('명시적 Full GC', String(k.explicitFullCount), eSub, '');
        } else {
            h += kpiCard('Full GC', String(k.fullCount), k.fullGcPerHour != null ? '시간당 ' + k.fullGcPerHour.toFixed(2) + '회' + (k.explicitFullCount ? ' · 명시적 ' + k.explicitFullCount + '회' : '') + ' · 연속 최대 ' + k.maxConsecutiveFull : '', fullRate > 6 ? 'bad' : fullRate > 1 ? 'warn' : '');
        }
        h += kpiCard('이벤트', String(k.eventCount), 'Young ' + k.youngCount + ' · Mixed ' + k.mixedCount + ' · Concurrent ' + k.concurrentCount);
        h += kpiCard('최대 힙 용량', fmtBytes(k.maxHeapTotalBytes), '마지막 GC 후 ' + fmtBytes(k.lastHeapAfterBytes) + (k.maxHeapTotalBytes && k.lastHeapAfterBytes ? ' (' + Math.round(100 * k.lastHeapAfterBytes / k.maxHeapTotalBytes) + '%)' : ''));
        h += kpiCard('할당률', k.allocationRateMbPerSec == null ? '–' : k.allocationRateMbPerSec.toFixed(1) + ' MB/s', k.promotionRateMbPerSec != null ? '승격 ' + k.promotionRateMbPerSec.toFixed(3) + ' MB/s' : '');
        var basisLabel = t.basis === 'full' ? 'Full GC' : t.basis === 'remark' ? 'Remark' : t.basis === 'mixed' ? 'Mixed' : '';
        // significant(실질 증가량·도달 예상 게이트)가 false 면 note 가 이유를 말한다 — 없는 옛 결과는 종전 규칙(2026-09-15)
        var trendWarn = t.significant != null ? t.significant === true : (t.slopeMbPerHour > 0 && t.r2 > 0.5);
        h += kpiCard(basisLabel ? basisLabel + ' 직후 힙 추세' : '힙 추세', t.slopeMbPerHour == null ? '–' : fmtSlope(t.slopeMbPerHour) + ' MB/h',
            t.r2 != null
                ? (t.significant === false && t.note ? t.note
                    : 'R² ' + t.r2.toFixed(2) + ' · ' + (t.pointsUsed != null ? t.pointsUsed : t.afterPoints.length) + '점 · ' + fmtBytes(t.firstAfterBytes) + ' → ' + fmtBytes(t.lastAfterBytes)
                        + (t.excludedWarmup ? ' · 기동 직후 ' + t.excludedWarmup + '점 제외' : ''))
                : (t.note || '점이 부족함'),
            trendWarn ? 'warn' : '');
        if (k.metaspaceLastBytes != null) h += kpiCard(m.permGen ? 'PermGen' : 'Metaspace', fmtBytes(k.metaspaceLastBytes), '시작 ' + fmtBytes(k.metaspaceFirstBytes) + (k.metaspaceMaxTotalBytes ? ' · 예약 ' + fmtBytes(k.metaspaceMaxTotalBytes) : ''));
        if (k.maxOverheadPct10m != null) h += kpiCard('10분 창 최대 GC 비중', pct(k.maxOverheadPct10m, 1), 'uptime ' + fmtDur(k.maxOverheadWindowStartSec) + ' 부근', k.maxOverheadPct10m >= 50 ? 'bad' : k.maxOverheadPct10m >= 25 ? 'warn' : '');
        document.getElementById('gclKpi').innerHTML = h;
    }

    // ── 인스턴스 알약 (헤더 메타, 서버 알약 오른쪽) ─────────────
    // 2026-09-16: 종전 KPI 마지막 카드에서 헤더 메타 알약으로 옮겼다 — 서버명과 함께 "어느 JVM 의 로그인가" 를 말하는 식별 정보라서다.
    // 값 = 수동 입력 > 연결된 힙 덤프의 Instance(analyze 화면 Instance 칩과 같은 값). 서버 뷰({name,source,manual,dump,dumpFilename})를
    // 그대로 그린다 — 초기값은 서버 렌더(GC_INSTANCE 와 같은 값), 이후 /instance 저장 응답과 /match 계열 응답(연결 변경)이 갱신한다.
    // 알약은 결과 유무와 무관하게 있으므로 분석 전·실패 화면에서도 입력할 수 있다.
    // ⚠ 편집 중에는 값·배지·연필을 숨기고 입력 줄을 형제로 끼운다 — 그 사이 매칭 응답이 와도 renderInstance 가 입력을 덮지 않는다.
    var _instance = (typeof GC_INSTANCE !== 'undefined' && GC_INSTANCE) ? GC_INSTANCE : null;

    function instanceDescription(v) {
        if (v.source === 'manual') return '수동 입력' + (v.dump && v.dump !== v.manual ? ' · 덤프 값 ' + v.dump : '');
        if (v.source === 'dump') return '연결된 힙 덤프 · ' + v.dumpFilename;
        if (v.dumpFilename) return '연결된 힙 덤프에 Instance 없음 — 직접 입력';
        return '힙 덤프를 연결하면 자동으로 가져옵니다';
    }

    function renderInstance(v) {
        if (v) _instance = v;
        v = _instance || {};
        var item = document.getElementById('gclInstItem');
        var val = document.getElementById('gclInstValue');
        var src = document.getElementById('gclInstSrc');
        if (!item || !val) return;
        var name = v.name || '';
        val.textContent = name || '미지정';
        val.classList.toggle('empty', !name);
        item.title = '인스턴스 — ' + instanceDescription(v);
        if (src) {
            var editing = !!item.querySelector('.gcl-meta-form');
            src.className = 'gcl-meta-src ' + (v.source || 'none');
            src.textContent = v.source === 'manual' ? '수동' : '덤프';
            src.style.display = editing || v.source !== 'manual' && v.source !== 'dump' ? 'none' : '';
        }
    }

    function startInstanceEdit() {
        var item = document.getElementById('gclInstItem');
        var val = document.getElementById('gclInstValue');
        var src = document.getElementById('gclInstSrc');
        var btn = document.getElementById('gclInstEdit');
        if (!item || !val || item.querySelector('.gcl-meta-form')) return;
        var form = document.createElement('span');
        form.className = 'gcl-meta-form';
        var input = document.createElement('input');
        input.type = 'text';
        input.className = 'gcl-meta-input';
        input.maxLength = 100;
        // 수동값이 없으면 덤프 값을 채워 둔다 — 그대로 저장하면 연결을 바꿔도 유지되는 수동값이 된다
        input.value = _instance ? (_instance.manual || _instance.dump || '') : '';
        input.placeholder = _instance && _instance.dump ? '비우면 덤프 값(' + _instance.dump + ')' : '인스턴스명 입력';
        input.setAttribute('aria-label', '인스턴스명');
        var save = document.createElement('button');
        save.type = 'button'; save.className = 'gcl-inst-save'; save.textContent = '저장';
        var cancel = document.createElement('button');
        cancel.type = 'button'; cancel.className = 'gcl-inst-cancel'; cancel.textContent = '취소';
        form.appendChild(input); form.appendChild(save); form.appendChild(cancel);

        function close() {
            if (form.parentNode) form.parentNode.removeChild(form);
            val.style.display = '';
            if (btn) { btn.style.display = ''; btn.focus(); }
            renderInstance();   // 출처 배지 표시 복원
        }
        cancel.onclick = close;
        save.onclick = function () {
            if (save.disabled) return;
            save.disabled = true; cancel.disabled = true;
            Common.fetchJSON(api('/instance'), { method: 'POST', body: JSON.stringify({ instance: input.value }) })
                .then(function (d) {
                    if (!d || d.success !== true) throw new Error((d && d.error) || '저장 응답이 올바르지 않습니다.');
                    renderInstance(d.instance);
                    close();
                    toast(d.instance && d.instance.source === 'manual' ? '인스턴스명을 저장했습니다.' : '수동 입력을 지웠습니다' + (d.instance && d.instance.name ? ' — 덤프 값으로 표시합니다.' : '.'), 'success');
                })
                .catch(function (e) {
                    save.disabled = false; cancel.disabled = false;
                    toast('인스턴스명 저장 실패: ' + errMsg(e, ''), 'danger');
                });
        };
        input.addEventListener('keydown', function (ev) {
            if (ev.isComposing) return;   // 한글 조합 중 Enter 는 확정용
            if (ev.key === 'Enter') { ev.preventDefault(); save.onclick(); }
            else if (ev.key === 'Escape') { ev.preventDefault(); close(); }
        });
        val.style.display = 'none';
        if (src) src.style.display = 'none';
        if (btn) btn.style.display = 'none';
        val.insertAdjacentElement('afterend', form);
        input.focus();
        input.select();
    }

    // ── 출처 서버명 ──────────────────────────────────────────
    // 헤더 메타의 '서버' 알약. 업로드 때 비워 둔 로그도 여기서 채운다(2026-09-15 — 종전엔 알약 자체가 없어 입력할 곳이 없었다).
    // 서버명은 자동 매칭의 '같은 서버' 신호라 저장 응답(matchView)으로 매칭 칩·인스턴스 알약도 함께 다시 그린다.
    function renderServerName(name) {
        var val = document.getElementById('gclServerValue');
        if (!val) return;
        val.textContent = name || '미지정';
        val.title = name || '';
        val.classList.toggle('empty', !name);
    }

    function startServerEdit() {
        var item = document.getElementById('gclServerItem');
        var val = document.getElementById('gclServerValue');
        var btn = document.getElementById('gclServerEdit');
        if (!item || !val || item.querySelector('.gcl-meta-form')) return;
        var form = document.createElement('span');
        form.className = 'gcl-meta-form';
        var input = document.createElement('input');
        input.type = 'text';
        input.className = 'gcl-meta-input';
        input.maxLength = 100;
        input.value = val.classList.contains('empty') ? '' : val.textContent.trim();
        input.placeholder = '서버명 (비우면 미지정)';
        input.setAttribute('aria-label', '출처 서버명');
        var save = document.createElement('button');
        save.type = 'button'; save.className = 'gcl-inst-save'; save.textContent = '저장';
        var cancel = document.createElement('button');
        cancel.type = 'button'; cancel.className = 'gcl-inst-cancel'; cancel.textContent = '취소';
        form.appendChild(input); form.appendChild(save); form.appendChild(cancel);

        function close() {
            if (form.parentNode) form.parentNode.removeChild(form);
            val.style.display = '';
            if (btn) { btn.style.display = ''; btn.focus(); }
        }
        cancel.onclick = close;
        save.onclick = function () {
            if (save.disabled) return;
            save.disabled = true; cancel.disabled = true;
            var before = _matchView && _matchView.matched ? _matchView.matched.dumpFilename : null;
            Common.fetchJSON(api('/hostname'), { method: 'POST', body: JSON.stringify({ hostname: input.value }) })
                .then(function (d) {
                    if (!d || d.success !== true) throw new Error((d && d.error) || '저장 응답이 올바르지 않습니다.');
                    renderServerName(d.hostname);
                    close();
                    renderMatchChip(d);
                    var after = d.matched ? d.matched.dumpFilename : null;
                    var msg = d.hostname ? '서버명을 저장했습니다.' : '서버명을 지웠습니다.';
                    if (after && after !== before) msg += ' 자동 매칭: ' + after;
                    else if (!after && before) msg += ' 힙 덤프 자동 연결이 풀렸습니다.';
                    toast(msg, 'success');
                })
                .catch(function (e) {
                    save.disabled = false; cancel.disabled = false;
                    toast('서버명 저장 실패: ' + errMsg(e, ''), 'danger');
                });
        };
        input.addEventListener('keydown', function (ev) {
            if (ev.isComposing) return;   // 한글 조합 중 Enter 는 확정용
            if (ev.key === 'Enter') { ev.preventDefault(); save.onclick(); }
            else if (ev.key === 'Escape') { ev.preventDefault(); close(); }
        });
        val.style.display = 'none';
        if (btn) btn.style.display = 'none';
        val.insertAdjacentElement('afterend', form);
        input.focus();
        input.select();
    }

    // ── 차트 ─────────────────────────────────────────────────
    var _charts = [];
    function xTick(v) {
        var s = Math.round(v);
        if (s >= 3600) return Math.floor(s / 3600) + 'h' + ('0' + Math.floor((s % 3600) / 60)).slice(-2);
        if (s >= 60) return Math.floor(s / 60) + 'm' + ('0' + (s % 60)).slice(-2);
        return s + 's';
    }
    function xTitle(r) {
        return r.meta.firstUptimeSec != null ? 'JVM 기동 후 경과' : '이벤트 순서';
    }
    function renderCharts(r) {
        if (typeof Chart === 'undefined') return;
        var s = r.series;
        var n = s.uptimeSec.length;
        var TYPE_COLOR = { YOUNG: '#60A5FA', MIXED: '#818CF8', FULL: '#DC2626', REMARK: '#F59E0B', CLEANUP: '#FBBF24', CMS_INITIAL_MARK: '#F59E0B', CMS_FINAL_REMARK: '#F59E0B', OTHER: '#9CA3AF' };
        var after = [], total = [], before = [], pauses = [], pauseColors = [];
        for (var i = 0; i < n; i++) {
            after.push({ x: s.uptimeSec[i], y: s.heapAfter[i] });
            before.push({ x: s.uptimeSec[i], y: s.heapBefore[i] });
            if (s.heapTotal[i] != null) total.push({ x: s.uptimeSec[i], y: s.heapTotal[i] });
            pauses.push({ x: s.uptimeSec[i], y: s.pauseMs[i] });
            pauseColors.push(TYPE_COLOR[s.type[i]] || '#9CA3AF');
        }
        var mb = function (v) { return (v / 1048576).toFixed(0) + ' MB'; };
        var common = {
            responsive: true, maintainAspectRatio: false, animation: false, interaction: { mode: 'nearest', intersect: false },
            scales: { x: { type: 'linear', title: { display: true, text: xTitle(r), font: { size: 11 } }, ticks: { callback: xTick, maxTicksLimit: 10, font: { size: 10 } }, grid: { color: '#F3F4F6' } } },
            plugins: { legend: { labels: { boxWidth: 12, font: { size: 11 } } } }
        };
        var heapCtx = document.getElementById('gclHeapChart');
        if (heapCtx) {
            var datasets = [
                { label: 'GC 직후 사용량', data: after, borderColor: '#2563EB', backgroundColor: 'rgba(37,99,235,.10)', fill: true, borderWidth: 1.5, pointRadius: n > 300 ? 0 : 2, tension: 0.1 },
                { label: 'GC 직전 사용량', data: before, borderColor: '#93C5FD', borderWidth: 1, pointRadius: 0, borderDash: [3, 3], tension: 0.1 }
            ];
            if (total.length) datasets.push({ label: '총 용량', data: total, borderColor: '#6B7280', borderWidth: 1, pointRadius: 0, stepped: true });
            if (r.trend && r.trend.afterPoints && r.trend.afterPoints.length >= 2) {
                datasets.push({ label: (r.trend.basis === 'full' ? 'Full GC' : r.trend.basis === 'remark' ? 'Remark' : 'Mixed') + ' 직후', data: r.trend.afterPoints.map(function (p) { return { x: p[0], y: p[1] }; }),
                    borderColor: '#DC2626', backgroundColor: '#DC2626', showLine: true, borderWidth: 1.5, pointRadius: 3, tension: 0 });
            }
            // 압박 Full GC 만 ▲ — Series 는 병합 시 유형이 FULL 로 뭉개져 압박/명시적을 못 가르므로 분류 블록의 점을 쓴다(2026-09-16)
            if (r.fullGcSummary && r.fullGcSummary.points && r.fullGcSummary.points.length) {
                datasets.push({ label: '압박 Full GC 직후', data: r.fullGcSummary.points.map(function (p) { return { x: p[0], y: p[1] }; }),
                    borderColor: '#DC2626', backgroundColor: '#DC2626', showLine: false, pointStyle: 'triangle', pointRadius: 5, pointHoverRadius: 7 });
            }
            _charts.push(new Chart(heapCtx, { type: 'line', data: { datasets: datasets }, options: Object.assign({}, common, {
                scales: Object.assign({}, common.scales, { y: { beginAtZero: true, ticks: { callback: mb, font: { size: 10 } }, grid: { color: '#F3F4F6' } } }),
                plugins: Object.assign({}, common.plugins, { tooltip: { callbacks: { title: function (it) { return it.length ? 'uptime ' + xTick(it[0].parsed.x) : ''; }, label: function (it) { return it.dataset.label + ': ' + fmtBytes(it.parsed.y); } } } })
            }) }));
        }
        var pauseCtx = document.getElementById('gclPauseChart');
        if (pauseCtx) {
            _charts.push(new Chart(pauseCtx, { type: 'scatter', data: { datasets: [{ label: '일시정지(ms)', data: pauses, pointBackgroundColor: pauseColors, pointBorderColor: pauseColors, pointRadius: n > 500 ? 2 : 3 }] },
                options: Object.assign({}, common, {
                    scales: Object.assign({}, common.scales, { y: { beginAtZero: true, title: { display: true, text: 'ms', font: { size: 11 } }, ticks: { font: { size: 10 } }, grid: { color: '#F3F4F6' } } }),
                    plugins: { legend: { display: false }, tooltip: { callbacks: { title: function (it) { return it.length ? 'uptime ' + xTick(it[0].parsed.x) : ''; }, label: function (it) { return (s.type[it.dataIndex] || '') + ' ' + fmtMs(it.parsed.y); } } } }
                }) }));
            var note = document.getElementById('gclPauseChartNote');
            if (note) note.innerHTML = '<span style="color:#60A5FA">●</span> Young <span style="color:#818CF8">●</span> Mixed <span style="color:#DC2626">●</span> Full <span style="color:#F59E0B">●</span> Remark/Cleanup' + (s.mergeFactor > 1 ? ' · ' + s.mergeFactor + '건씩 병합(최대값)' : '');
        }
        var hn = document.getElementById('gclHeapChartNote');
        if (hn && s.mergeFactor > 1) hn.textContent = '표시 점은 ' + s.mergeFactor + '건씩 병합(직전=최대, 직후=마지막)';
        if (hn && r.fullGcSummary && r.fullGcSummary.points && r.fullGcSummary.points.length) hn.textContent += ' · ▲ 압박 Full GC';
    }

    // ── 소견 ─────────────────────────────────────────────────
    function renderFindings(r) {
        var box = document.getElementById('gclFindings');
        var fs = r.findings || [];
        var real = fs.filter(function (f) { return f.severity !== 'Info'; });
        document.getElementById('gclFindingsCount').textContent = real.length + '건' + (fs.length > real.length ? ' (+참고 ' + (fs.length - real.length) + ')' : '');
        if (!fs.length) { box.innerHTML = '<div class="gcl-empty">규칙 기반 이상 징후가 없습니다.</div>'; return; }
        box.innerHTML = fs.map(function (f) {
            return '<div class="gcl-finding sev-' + esc(f.severity) + '"><div class="gcl-finding-head"><span class="gcl-finding-sev">' + esc(f.severity) + '</span>'
                + '<span class="gcl-finding-title">' + esc(f.title) + '</span><span class="gcl-finding-code">' + esc(f.code) + '</span></div>'
                + (f.detail ? '<div class="gcl-finding-detail">' + esc(f.detail) + '</div>' : '')
                + (f.advice ? '<div class="gcl-finding-advice">' + esc(f.advice) + '</div>' : '') + '</div>';
        }).join('');
    }

    // ── 이벤트 표 (table-grid detach) ─────────────────────────
    var _evGrid = null;
    var _evRows = [];
    var ABNORMAL = { TO_SPACE_EXHAUSTED: 1, HUMONGOUS_ALLOC: 1, CONCURRENT_MODE_FAILURE: 1, PROMOTION_FAILED: 1, METADATA_THRESHOLD: 1, SYSTEM_GC: 1, GC_LOCKER: 1 };
    function renderEvents(r) {
        var events = r.events || [];
        document.getElementById('gclEventsCount').textContent = events.length + '건' + (events.length < r.kpi.eventCount ? ' / 전체 ' + r.kpi.eventCount + '건(상한)' : '');
        var tbody = document.getElementById('gclEvBody');
        var frag = document.createDocumentFragment();
        _evRows = events.map(function (e) {
            var tr = document.createElement('tr');
            var abnormal = (e.flags || []).some(function (f) { return ABNORMAL[f]; });
            var kind = e.type === 'FULL' ? e.fullKind : null;   // Full 행 분류(2026-09-16) — 옛 결과는 없다
            tr.className = 'ev-' + e.type + (abnormal ? ' ev-flag' : '') + (kind === 'EXPLICIT' ? ' ev-explicit' : '');
            tr.setAttribute('data-seq', e.seq); tr.setAttribute('data-line', e.line);
            tr.setAttribute('data-ts', e.tsEpochMs == null ? '' : String(e.tsEpochMs)); tr.setAttribute('data-uptime', e.uptimeSec == null ? '' : e.uptimeSec);
            tr.setAttribute('data-type', e.type); tr.setAttribute('data-pause', e.pauseMs == null ? '' : e.pauseMs);
            tr.setAttribute('data-kind', kind || '');
            tr.setAttribute('data-search', (e.type + ' ' + (e.cause || '') + ' ' + (e.flags || []).join(' ') + ' ' + (kind ? KIND_LABEL[kind] || kind : '')).toLowerCase());
            tr.setAttribute('data-flag', (e.type === 'FULL' || abnormal) ? '1' : '0');
            var heap = e.heapBefore != null ? fmtBytes(e.heapBefore) + ' → ' + fmtBytes(e.heapAfter) + (e.heapTotal != null ? ' / ' + fmtBytes(e.heapTotal) : '') : '–';
            var cpu = e.userSec != null ? e.userSec.toFixed(2) + ' / ' + e.sysSec.toFixed(2) + ' / ' + e.realSec.toFixed(2) : '–';
            tr.innerHTML = '<td class="num">' + e.seq + '</td><td class="num">' + e.line + '</td><td>' + esc(fmtTs(e.tsEpochMs)) + '</td>'
                + '<td class="num">' + (e.uptimeSec == null ? '–' : xTick(e.uptimeSec)) + '</td>'
                + '<td><span class="gcl-type t-' + esc(e.type) + '">' + esc(typeLabel(e.type)) + '</span>' + (kind ? '<span class="gcl-kind k-' + esc(kind) + '">' + esc(KIND_LABEL[kind] || kind) + '</span>' : '') + '</td>'
                + '<td title="' + esc(e.cause || '') + '">' + esc(e.cause || '') + '</td>'
                + '<td class="num">' + (e.pauseMs == null ? '–' : (e.concurrent ? '(' + fmtMs(e.pauseMs) + ')' : fmtMs(e.pauseMs))) + '</td>'
                + '<td>' + esc(heap) + callHeapNote(e) + '</td><td class="num">' + esc(cpu) + '</td>'
                + '<td>' + (e.flags || []).map(function (f) { return '<span class="gcl-flag">' + esc(f) + '</span>'; }).join('') + '</td>';
            return tr;
        });
        var search = document.getElementById('gclEvSearch');
        var onlyFlag = document.getElementById('gclEvOnlyFlag');
        function applyFilter() {
            var q = (search.value || '').trim().toLowerCase();
            var only = onlyFlag.checked;
            var anyType = Object.keys(_evTypeSel).length > 0;
            _evGrid.setFiltered(_evGrid.allRows.filter(function (tr) {
                if (anyType && !_evTypeSel[tr.getAttribute('data-type')]) return false;
                if (only && tr.getAttribute('data-flag') !== '1') return false;
                if (q && tr.getAttribute('data-search').indexOf(q) === -1) return false;
                return true;
            }));
        }
        _evGrid = TableGrid.create({
            tbodyId: 'gclEvBody', headerSelector: '#gclEvTable th.sortable', pageSizeKey: 'gcLogEvPageSize',
            applyFilter: applyFilter, defaultSort: { key: 'seq', dir: 'asc', type: 'num' }, sortAttrPrefix: 'data-',
            pageSizes: [25, 50, 100, 200], defaultPageSize: 50, pageSizeSelectId: 'gclPageSize', noMatchId: 'gclEvNoMatch',
            paginationBarId: 'gclEvPg', pgInfoId: 'gclEvPgInfo', pgListId: 'gclEvPgList', detach: true
        });
        window._evGrid = _evGrid;
        _evGrid.init(_evRows);
        var deb = null;
        search.addEventListener('input', function () { clearTimeout(deb); deb = setTimeout(applyFilter, 150); });
        onlyFlag.addEventListener('change', applyFilter);
        renderEventTypeFilter(events, applyFilter);
        _evApplyFilter = applyFilter;   // 배너 '해당 이벤트 보기' 가 같은 술어를 쓴다
        applyFilter();
    }
    var _evApplyFilter = null, _evTypeSync = null;

    // 명시적 호출(System.gc() 등)의 Full 단계 행 — 같은 호출의 Young 수집까지 합친 전체 회수량을 한 줄 더 보인다(2026-09-15).
    // Full 단계만 보면 '26.0 MB → 25.8 MB' 처럼 해제가 미미해 보이지만 그건 Young 에서 살아남은 객체를 Old 로 옮겨 압축하는 단계라서다.
    function callHeapNote(e) {
        if (e.callHeapBefore == null || e.heapAfter == null) return '';
        var tip = '같은 System.gc() 호출이 Young 수집 → Full GC 두 단계로 기록됐습니다. Full 단계의 변화가 작은 것은 Young 에서 살아남은 객체를 Old 로 옮겨 압축하기 때문이며, 호출 전체로는 이만큼 회수했습니다.';
        return '<div class="gcl-ev-call" title="' + esc(tip) + '">호출 전체 ' + esc(fmtBytes(e.callHeapBefore)) + ' → ' + esc(fmtBytes(e.heapAfter)) + ' (Young 수집 포함)</div>';
    }

    // ── 이벤트 유형 필터 ─────────────────────────────────────
    // 로그에 실제로 나온 유형만 버튼으로(건수 = 적재된 이벤트 기준). 여러 개를 켤 수 있고 아무것도 안 켜면 전체.
    // 선택 상태의 단일 출처는 _evTypeSel 이고 버튼은 aria-pressed 로만 그린다 — 클래스와 속성이 따로 놀지 않게.
    var TYPE_ORDER = ['YOUNG', 'MIXED', 'FULL', 'REMARK', 'CLEANUP', 'CMS_INITIAL_MARK', 'CMS_FINAL_REMARK', 'CONCURRENT_CYCLE', 'OTHER'];
    var _evTypeSel = {};
    function typeLabel(t) { return t === 'CONCURRENT_CYCLE' ? 'CONC' : t; }

    function renderEventTypeFilter(events, onChange) {
        var box = document.getElementById('gclEvTypes');
        if (!box) return;
        var counts = {};
        events.forEach(function (e) { counts[e.type] = (counts[e.type] || 0) + 1; });
        var types = Object.keys(counts).sort(function (a, b) {
            var ia = TYPE_ORDER.indexOf(a), ib = TYPE_ORDER.indexOf(b);
            return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib) || (a < b ? -1 : a > b ? 1 : 0);
        });
        box.innerHTML = '';
        _evTypeSel = {};
        _evTypeSync = null;
        if (types.length < 2) { box.hidden = true; return; }   // 유형이 하나뿐이면 거를 것이 없다
        var all = document.createElement('button');
        all.type = 'button';
        all.className = 'gcl-ev-type-btn gcl-ev-type-all';
        all.setAttribute('data-type', '');
        all.innerHTML = '전체 <span class="cnt">' + events.length + '</span>';
        box.appendChild(all);
        types.forEach(function (t) {
            var b = document.createElement('button');
            b.type = 'button';
            b.className = 'gcl-ev-type-btn';
            b.setAttribute('data-type', t);
            b.title = t + ' ' + counts[t] + '건 — 눌러서 켜고 끄기(여러 개 선택 가능)';
            b.innerHTML = '<span class="gcl-type t-' + esc(t) + '">' + esc(typeLabel(t)) + '</span><span class="cnt">' + counts[t] + '</span>';
            box.appendChild(b);
        });
        function sync() {
            var any = Object.keys(_evTypeSel).length > 0;
            box.querySelectorAll('.gcl-ev-type-btn').forEach(function (b) {
                var t = b.getAttribute('data-type');
                b.setAttribute('aria-pressed', String(t ? !!_evTypeSel[t] : !any));
            });
        }
        box.onclick = function (ev) {
            var b = ev.target.closest('.gcl-ev-type-btn');
            if (!b) return;
            var t = b.getAttribute('data-type');
            if (!t) _evTypeSel = {};
            else if (_evTypeSel[t]) delete _evTypeSel[t];
            else _evTypeSel[t] = true;
            if (Object.keys(_evTypeSel).length === types.length) _evTypeSel = {};   // 전부 켠 것 = 전체
            sync();
            onChange();
        };
        sync();
        _evTypeSync = sync;
        box.hidden = false;
    }

    // ── 원문 ─────────────────────────────────────────────────
    function showRaw(which, btn) {
        if (!R) return;
        document.querySelectorAll('.gcl-raw-tab').forEach(function (b) { b.classList.toggle('active', b === btn); });
        var pre = document.getElementById('gclRaw');
        var lines = (R.rawSample && R.rawSample[which]) || [];
        if (!lines.length) { pre.textContent = '(없음)'; return; }
        var hl = -1;
        if (which === 'aroundMaxPause' && R.pauseStats.maxAtLine != null && R.rawSample.aroundMaxPauseFirstLine != null) hl = R.pauseStats.maxAtLine - R.rawSample.aroundMaxPauseFirstLine;
        var startNo = which === 'head' ? 1 : which === 'aroundMaxPause' ? (R.rawSample.aroundMaxPauseFirstLine || 1) : Math.max(1, R.meta.lines - lines.length + 1);
        pre.innerHTML = lines.map(function (l, i) {
            var no = String(startNo + i);
            var row = '<span style="color:#64748B">' + ('      ' + no).slice(-6) + '</span>  ' + esc(l);
            return i === hl ? '<span class="hl">' + row + '</span>' : row;
        }).join('\n');
    }

    // ── 매칭 칩 ──────────────────────────────────────────────
    function renderMatchChip(v) {
        var name = document.getElementById('gclMatchName');
        var src = document.getElementById('gclMatchSrc');
        var cand = document.getElementById('gclMatchCand');
        var open = document.getElementById('gclMatchOpen');
        var unlink = document.getElementById('gclMatchUnlink');
        var matched = v && v.matched;
        if (matched) {
            name.textContent = matched.dumpFilename; name.classList.remove('empty');
            name.title = '이유: ' + (matched.reason || '') + (matched.matchedAt ? ' · ' + matched.matchedAt.replace('T', ' ').substring(0, 19) : '');
            if (!src) { src = document.createElement('span'); src.id = 'gclMatchSrc'; name.insertAdjacentElement('afterend', src); }
            src.className = 'gcl-chip-src ' + matched.source; src.textContent = matched.source === 'auto' ? '자동' : '수동'; src.style.display = '';
            if (cand) cand.style.display = 'none';
            if (open) open.style.display = ''; if (unlink) unlink.style.display = '';
        } else {
            name.textContent = v && v.source === 'manual' ? '미연결(수동 해제)' : '미연결'; name.classList.add('empty'); name.title = '';
            if (src) src.style.display = 'none';
            var n = v && v.candidates ? v.candidates.length : 0;
            if (n > 0) {
                if (!cand) { cand = document.createElement('span'); cand.id = 'gclMatchCand'; cand.className = 'gcl-chip-src cand'; name.insertAdjacentElement('afterend', cand); }
                cand.textContent = '후보 ' + n; cand.style.display = '';
            } else if (cand) cand.style.display = 'none';
            if (open) open.style.display = 'none'; if (unlink) unlink.style.display = 'none';
        }
        _matchView = v;
        if (v && v.instance) renderInstance(v.instance);
        syncPressureDumpLink();
    }
    var _matchView = null;
    function refreshMatch() {
        Common.fetchJSON(api('/match')).then(renderMatchChip).catch(function (e) { ignored('매칭 조회', e); });
    }
    function openMatchedDump() {
        if (_matchView && _matchView.matched) window.location.href = '/analyze/' + encodeURIComponent(_matchView.matched.dumpFilename);
    }
    function unlinkMatch() {
        if (!confirm('힙 덤프 연결을 해제할까요? 해제 후에는 자동 매칭이 다시 연결하지 않습니다(⟳ 로 재평가 가능).')) return;
        Common.fetchJSON(api('/match'), { method: 'POST', body: JSON.stringify({ dumpFilename: null }) })
            .then(function (v) { renderMatchChip(v); toast('연결을 해제했습니다.', 'success'); })
            .catch(function (e) { toast('해제 실패: ' + errMsg(e, ''), 'danger'); });
    }
    function rematch() {
        Common.fetchJSON(api('/rematch'), { method: 'POST' })
            .then(function (v) { renderMatchChip(v); toast(v.matched ? '자동 매칭: ' + v.matched.dumpFilename : '자동 확정된 덤프가 없습니다' + (v.candidates && v.candidates.length ? ' (후보 ' + v.candidates.length + ')' : ''), v.matched ? 'success' : 'info'); })
            .catch(function (e) { toast('재평가 실패: ' + errMsg(e, ''), 'danger'); });
    }

    var _pick = null, _pickOptions = [];
    function openMatchPickModal() {
        _pick = null;
        var modal = document.getElementById('matchPickModal');
        var list = document.getElementById('matchPickList');
        var err = document.getElementById('matchPickErr'); if (err) { err.textContent = ''; err.classList.remove('show'); }
        document.getElementById('matchPickConfirm').disabled = true;
        list.innerHTML = '<div class="gcl-pick-empty">불러오는 중…</div>';
        modal.classList.add('open');
        Common.fetchJSON(api('/match-options')).then(function (d) { _pickOptions = d.options || []; renderPickList(''); })
            .catch(function (e) { list.innerHTML = '<div class="gcl-pick-empty">조회 실패: ' + esc(errMsg(e, '')) + '</div>'; });
    }
    function renderPickList(q) {
        var list = document.getElementById('matchPickList');
        q = (q || '').toLowerCase();
        var cur = _matchView && _matchView.matched ? _matchView.matched.dumpFilename : null;
        var rows = _pickOptions.filter(function (o) { return !q || o.filename.toLowerCase().indexOf(q) >= 0 || (o.serverName || '').toLowerCase().indexOf(q) >= 0; });
        if (!rows.length) { list.innerHTML = '<div class="gcl-pick-empty">힙 덤프 분석 기록이 없습니다.</div>'; return; }
        list.innerHTML = rows.map(function (o) {
            var tags = '';
            if (o.filename === cur) tags += '<span class="gcl-pick-tag cur">현재 연결</span>';
            if (o.score >= 2) tags += '<span class="gcl-pick-tag hit">점수 ' + o.score + '</span>'; else if (o.score > 0) tags += '<span class="gcl-pick-tag">점수 ' + o.score + '</span>';
            if (o.sameServer) tags += '<span class="gcl-pick-tag hit">같은 서버</span>';
            if (o.fileDeleted) tags += '<span class="gcl-pick-tag warn">파일 삭제됨</span>';
            (o.reasons || []).forEach(function (r) { tags += '<span class="gcl-pick-tag">' + esc(r) + '</span>'; });
            return '<label class="gcl-pick-row" data-fn="' + esc(o.filename) + '"><input type="radio" name="matchPick" value="' + esc(o.filename) + '">'
                + '<div style="min-width:0;flex:1"><div class="gcl-pick-main">' + esc(o.filename) + '</div>'
                + '<div class="gcl-pick-meta"><span>서버 <b>' + esc(o.serverName || '-') + '</b></span><span>덤프 생성 <b>' + esc(o.dumpCreationTime || '-') + '</b></span><span>' + esc(o.status || '') + '</span></div>'
                + (tags ? '<div class="gcl-pick-meta">' + tags + '</div>' : '') + '</div></label>';
        }).join('');
        list.querySelectorAll('.gcl-pick-row').forEach(function (row) {
            row.addEventListener('change', function () {
                _pick = row.dataset.fn;
                list.querySelectorAll('.gcl-pick-row').forEach(function (r) { r.classList.toggle('selected', r === row); });
                document.getElementById('matchPickConfirm').disabled = false;
            });
        });
    }
    function closeMatchPickModal() { document.getElementById('matchPickModal').classList.remove('open'); }
    function confirmMatchPick() {
        if (!_pick) return;
        var btn = document.getElementById('matchPickConfirm'); btn.disabled = true;
        Common.fetchJSON(api('/match'), { method: 'POST', body: JSON.stringify({ dumpFilename: _pick }) })
            .then(function (v) { renderMatchChip(v); closeMatchPickModal(); toast('연결했습니다: ' + _pick, 'success'); })
            .catch(function (e) { btn.disabled = false; var err = document.getElementById('matchPickErr'); if (err) { err.textContent = errMsg(e, '연결 실패'); err.classList.add('show'); } });
    }

    // ── AI 분석 ──────────────────────────────────────────────
    // 흐름: [AI 분석](상단)·[분석/재분석](카드) → 확인 모달 → POST /ai-analyze(동기, 15~40초) → 카드 렌더.
    // 분석 중에는 카드에 초 단위 경과 시간을 보인다. 실패하면 오류를 위에 얹고 직전 결과는 그대로 둔다(DB 의 옛 결과는 지워지지 않았다).
    var AI_ICONS = {
        summary: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="4" y1="6" x2="20" y2="6"></line><line x1="4" y1="12" x2="20" y2="12"></line><line x1="4" y1="18" x2="14" y2="18"></line></svg>',
        cause: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>',
        recs: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="20 6 9 17 4 12"></polyline></svg>',
        tuning: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="4" y1="21" x2="4" y2="14"></line><line x1="4" y1="10" x2="4" y2="3"></line><line x1="12" y1="21" x2="12" y2="12"></line><line x1="12" y1="8" x2="12" y2="3"></line><line x1="20" y1="21" x2="20" y2="16"></line><line x1="20" y1="12" x2="20" y2="3"></line><line x1="1" y1="14" x2="7" y2="14"></line><line x1="9" y1="8" x2="15" y2="8"></line><line x1="17" y1="16" x2="23" y2="16"></line></svg>'
    };
    var AI_EMPTY_HTML = '<div class="gcl-ai-empty">LLM 이 GC 통계와 소견을 종합 분석합니다. 오른쪽 <b>분석</b> 버튼으로 실행하세요. 연결된 힙 덤프가 있으면 그 정보도 함께 넣습니다.</div>';

    /** 이스케이프한 뒤 JVM 옵션(-XX:… / -Xmx4g / -Xlog:…)만 코드 칩으로 감싼다. 값 문자에 '&' 를 넣지 않아 엔티티(&quot; 등)를 가르지 않는다. */
    var JVM_OPT_RE = /(^|[\s(\[,;/“‘:])(-(?:XX:[+\-]?[A-Za-z][A-Za-z0-9_]*(?:=[A-Za-z0-9_.:\/%+*=\-]+)?|Xm[snx]\d+[kKmMgGtT]?|Xss\d+[kKmMgG]?|Xlog(?:gc)?:[A-Za-z0-9_.:\/%+*=,\-]+))/g;
    function aiText(s) {
        return esc(s).replace(JVM_OPT_RE, function (all, pre, opt) {
            var tail = '';
            while (/[.,:]$/.test(opt)) { tail = opt.slice(-1) + tail; opt = opt.slice(0, -1); }   // 문장 끝 마침표·쉼표는 칩 밖으로
            return pre + '<code class="gcl-ai-code">' + opt + '</code>' + tail;
        });
    }
    function aiBlock(kind, title, inner) {
        return '<section class="gcl-ai-block k-' + kind + '"><div class="gcl-ai-block-head"><span class="gcl-ai-block-icon">' + AI_ICONS[kind] + '</span>'
            + '<h4 class="gcl-ai-block-title">' + esc(title) + '</h4></div>' + inner + '</section>';
    }
    function aiMetaItem(k, v) {
        return '<span class="gcl-ai-meta-item"><span class="gcl-ai-meta-k">' + esc(k) + '</span><span class="gcl-ai-meta-v">' + esc(v) + '</span></span>';
    }

    var _aiHtml = null;        // 마지막으로 그린 결과 — 재분석이 실패하면 오류 아래에 다시 보인다
    function renderAi(d) {
        var body = document.getElementById('gclAiBody');
        var meta = document.getElementById('gclAiMetaTitle');
        if (!d || d.found === false) { return; }
        var data = d.data || d;
        var sev = data.severity || 'Unknown';
        var h = '<div class="gcl-ai-sev sev-' + esc(sev) + '"><span class="gcl-ai-sev-badge">' + esc(sev) + '</span>'
            + (data.severityDesc ? '<span class="gcl-ai-sev-desc">' + aiText(data.severityDesc) + '</span>' : '') + '</div>';
        // 서버가 응답 모양을 고쳤으면(severity 미인식·권고 5건 초과·본문 절단) 무엇을 고쳤는지 보인다(2026-09-16)
        if (Array.isArray(data.warnings) && data.warnings.length) h += '<div class="gcl-ai-warn">응답 정규화: ' + esc(data.warnings.join(' · ')) + '</div>';
        h += '<div class="gcl-ai-blocks">';
        if (data.summary) h += aiBlock('summary', '요약', '<div class="gcl-ai-block-text">' + aiText(data.summary) + '</div>');
        if (data.rootCause) h += aiBlock('cause', '근본 원인', '<div class="gcl-ai-block-text">' + aiText(data.rootCause) + '</div>');
        var recs = data.recommendations;
        if (typeof recs === 'string') recs = recs.split(/\n+/).filter(Boolean);
        var pair = [];
        if (recs && recs.length) {
            pair.push(aiBlock('recs', '권고', '<ol class="gcl-ai-recs">' + recs.map(function (r, i) {
                return '<li><span class="gcl-ai-rec-no">' + (i + 1) + '</span><span>' + aiText(String(r).replace(/^\s*\d+[.)]\s*/, '')) + '</span></li>';
            }).join('') + '</ol>'));
        }
        if (data.gcTuningAdvice) pair.push(aiBlock('tuning', 'GC 튜닝 제안', '<div class="gcl-ai-block-text">' + aiText(data.gcTuningAdvice) + '</div>'));
        // 권고·튜닝이 둘 다 있을 때만 나란히 — 하나뿐이면 전폭
        h += pair.length === 2 ? '<div class="gcl-ai-pair">' + pair.join('') + '</div>' : pair.join('');
        h += '</div>';
        var at = d.analysedAt || data.analysedAt;
        var model = d.model || data.model;
        var latency = d.latencyMs != null ? d.latencyMs : data.latencyMs;
        h += '<div class="gcl-ai-meta">' + (model ? aiMetaItem('모델', model) : '') + (at ? aiMetaItem('분석 시각', fmtTs(typeof at === 'number' ? at : Date.parse(at))) : '')
            + (latency != null && !isNaN(latency) ? aiMetaItem('소요', (latency / 1000).toFixed(1) + '초') : '')
            + (d.saved === false ? '<span class="gcl-ai-meta-warn">저장 실패: ' + esc(d.saveError || '') + '</span>' : '')
            + '<button type="button" class="btn btn-sm btn-ghost" onclick="deleteAi()">삭제</button></div>';
        body.innerHTML = h;
        _aiHtml = h;
        if (meta) meta.textContent = '';
        syncAiButtons();
    }
    function loadAi() {
        Common.fetchJSON(api('/ai-insight')).then(function (d) { if (d && d.found) renderAi(d); }).catch(function (e) { ignored('AI 인사이트 조회', e); });
    }

    var _aiRunning = false;
    var _aiGuarded = false;
    var _aiTimer = null;
    function syncAiButtons() {
        var top = document.getElementById('btnAi');
        if (top) { top.disabled = _aiRunning || GC_STATUS !== 'SUCCESS'; top.textContent = _aiRunning ? 'AI 분석 중…' : 'AI 분석'; }
        var run = document.getElementById('gclAiRunBtn');
        var label = document.getElementById('gclAiRunLabel');
        if (run) run.disabled = _aiRunning;
        if (label) label.textContent = _aiRunning ? '분석 중…' : (_aiHtml ? '재분석' : '분석');
    }

    var _aiReturnFocus = null;
    function runAi() {
        if (_aiRunning) return;
        var reanalyze = !!_aiHtml;
        document.getElementById('aiConfirmTitle').textContent = reanalyze ? 'AI 재분석' : 'AI 분석 시작';
        document.getElementById('aiConfirmReplace').style.display = reanalyze ? '' : 'none';
        var dump = document.getElementById('aiConfirmDump');
        var matched = _matchView && _matchView.matched ? _matchView.matched.dumpFilename : null;
        dump.textContent = matched ? '연결된 힙 덤프(' + matched + ')의 분석 정보도 함께 보냅니다.' : '';
        dump.style.display = matched ? '' : 'none';
        document.getElementById('aiConfirmBtn').textContent = reanalyze ? '재분석' : '분석 시작';
        _aiReturnFocus = document.activeElement;
        document.getElementById('aiConfirmModal').classList.add('open');
        document.getElementById('aiConfirmBtn').focus();
    }
    function closeAiConfirm() {
        var modal = document.getElementById('aiConfirmModal');
        if (!modal.classList.contains('open')) return;
        modal.classList.remove('open');
        if (_aiReturnFocus && _aiReturnFocus.focus && !_aiReturnFocus.disabled) _aiReturnFocus.focus();
        _aiReturnFocus = null;
    }
    function confirmAi() {
        closeAiConfirm();
        startAi();
    }

    function startAi() {
        if (_aiRunning) return;
        _aiRunning = true;
        if (!_aiGuarded && window.SessionTimeout && SessionTimeout.registerActivityGuard) {
            SessionTimeout.registerActivityGuard(function () { return _aiRunning; });   // 끝나는 작업만 — 응답이 오면 false(함정 38)
            _aiGuarded = true;
        }
        syncAiButtons();
        var body = document.getElementById('gclAiBody');
        body.innerHTML = '<div class="gcl-ai-loading">'
            + '<div class="gcl-ai-loading-row"><span class="gcl-ai-loading-spin" aria-hidden="true"></span>'
            + '<div class="gcl-ai-loading-text"><div class="gcl-ai-loading-title" role="status">AI 분석 중입니다</div>'
            + '<div class="gcl-ai-loading-desc">LLM 이 GC 통계와 소견을 종합하고 있습니다. 모델에 따라 15~40초 걸릴 수 있습니다.</div></div>'
            + '<span class="gcl-ai-elapsed" aria-label="경과 시간"><span class="gcl-ai-elapsed-k">경과</span><span class="gcl-ai-elapsed-v" id="gclAiElapsed">0</span>초</span></div>'
            + '<div class="gcl-ai-loading-track" aria-hidden="true"><div class="gcl-ai-loading-fill"></div></div></div>';
        var t0 = Date.now();
        stopAiTimer();
        _aiTimer = setInterval(function () {   // 화면 안 시계일 뿐 요청을 보내지 않는다 — managedInterval 대상 아님
            var el = document.getElementById('gclAiElapsed');
            if (el) el.textContent = String(Math.floor((Date.now() - t0) / 1000));
        }, 1000);
        var done = function () { _aiRunning = false; stopAiTimer(); syncAiButtons(); };
        var showErr = function (msg) {
            body.innerHTML = '<div class="gcl-ai-err" role="alert">' + esc(msg) + '</div>' + (_aiHtml || AI_EMPTY_HTML);
        };
        Common.fetchJSON(api('/ai-analyze'), { method: 'POST' })
            .then(function (d) {
                done();
                if (!d.success) { showErr(d.error || d.errorCode || 'AI 분석 실패'); return; }
                renderAi(d);
                toast('AI 분석을 마쳤습니다 (' + Math.floor((Date.now() - t0) / 1000) + '초).', 'success');
            })
            .catch(function (e) {
                done();
                showErr(e && e.rateLimited ? 'LLM 호출량 제한 — ' + (e.retryAfterSeconds ? e.retryAfterSeconds + '초 후 다시 시도하세요' : '잠시 후 다시 시도하세요') : errMsg(e, 'AI 분석 실패'));
            });
    }
    function stopAiTimer() { if (_aiTimer) { clearInterval(_aiTimer); _aiTimer = null; } }

    function deleteAi() {
        if (!confirm('AI 분석 결과를 삭제할까요?')) return;
        Common.fetchJSON(api('/ai-insight'), { method: 'DELETE' })
            .then(function () {
                _aiHtml = null;
                document.getElementById('gclAiBody').innerHTML = '<div class="gcl-ai-empty">삭제했습니다. 오른쪽 <b>분석</b> 버튼으로 다시 실행할 수 있습니다.</div>';
                syncAiButtons();
            })
            .catch(function (e) { toast('삭제 실패: ' + errMsg(e, ''), 'danger'); });
    }

    // ── 삭제 ─────────────────────────────────────────────────
    function deleteThis() {
        var err = document.getElementById('deleteGcModalErr'); if (err) { err.textContent = ''; err.classList.remove('show'); }
        document.getElementById('deleteGcModal').classList.add('open');
    }
    function closeGcDeleteModal() { document.getElementById('deleteGcModal').classList.remove('open'); }
    function confirmGcDelete() {
        var deleteFile = document.getElementById('deleteGcFileChk').checked;
        var btn = document.getElementById('deleteGcConfirmBtn'); btn.disabled = true;
        Common.fetchJSON(api('') + '?deleteFile=' + deleteFile, { method: 'DELETE' })
            .then(function () { window.location.href = '/gc-log'; })
            .catch(function (e) { btn.disabled = false; var err = document.getElementById('deleteGcModalErr'); if (err) { err.textContent = errMsg(e, '삭제 실패'); err.classList.add('show'); } });
    }

    // ── 초기화 ────────────────────────────────────────────────
    document.addEventListener('DOMContentLoaded', function () {
        // ?start=1 — 확인 모달을 거쳐 온 '분석 시작'. 한 번만 쓰고 주소에서 지운다(새로고침·뒤로가기로 다시 시작되지 않게)
        var wantStart = false;
        try {
            wantStart = new URLSearchParams(window.location.search).get('start') === '1';
            if (wantStart) history.replaceState(null, document.title, window.location.pathname);
        } catch (e) { ignored('start 파라미터 처리', e); }

        try { renderInstance(_instance); } catch (e) { failed('인스턴스 알약 렌더', e); }   // 결과 유무와 무관 — 설명(title)을 채운다

        var search = document.getElementById('matchPickSearch');
        if (search) search.addEventListener('input', function () { renderPickList(search.value); });
        document.addEventListener('keydown', function (ev) { if (ev.key === 'Escape') { closeAiConfirm(); closeReanalyzeConfirm(); } });

        if (GC_STATUS === 'SUCCESS') {
            R = loadResult();
            if (!R) { showError('저장된 결과를 읽지 못했습니다. 재분석하세요.'); document.getElementById('gclResult').style.display = 'none'; document.getElementById('gclProgress').style.display = ''; return; }
            try { renderPressureBanner(R); } catch (e) { failed('압박 배너 렌더', e); }
            try { renderKpi(R); } catch (e) { failed('KPI 렌더', e); }
            try { renderCharts(R); } catch (e) { failed('차트 렌더', e); }
            try { renderFindings(R); } catch (e) { failed('소견 렌더', e); }
            try { renderEvents(R); } catch (e) { failed('이벤트 표 렌더', e); }
            try { showRaw('head', document.querySelector('.gcl-raw-tab[data-raw="head"]')); } catch (e) { failed('원문 렌더', e); }
            loadAi();
            refreshMatch();
        } else if (GC_STATUS === 'ANALYZING') {
            startPolling();
        } else if (GC_STATUS === 'NOT_ANALYZED' && GC_FILE_EXISTS) {
            // 미분석 파일을 열었다 = 분석 의사(확인 모달·패널 링크) — 바로 시작
            startAnalysis();
        } else if (GC_STATUS === 'ERROR' && wantStart && GC_FILE_EXISTS) {
            // 실패 기록이 있는 파일을 확인 모달에서 '분석 시작' 했다 — 옛 오류를 보여 주지 말고 다시 돌린다
            startAnalysis();
            refreshMatch();
        } else if (GC_STATUS === 'ERROR') {
            refreshMatch();
        }
    });

    window.reanalyze = reanalyze;
    window.openReanalyzeConfirm = openReanalyzeConfirm;
    window.closeReanalyzeConfirm = closeReanalyzeConfirm;
    window.confirmReanalyze = confirmReanalyze;
    window.cancelAnalysis = cancelAnalysis;
    window.showRaw = showRaw;
    window.openMatchedDump = openMatchedDump;
    window.unlinkMatch = unlinkMatch;
    window.rematch = rematch;
    window.openMatchPickModal = openMatchPickModal;
    window.closeMatchPickModal = closeMatchPickModal;
    window.confirmMatchPick = confirmMatchPick;
    window.runAi = runAi;
    window.closeAiConfirm = closeAiConfirm;
    window.confirmAi = confirmAi;
    window.deleteAi = deleteAi;
    window.deleteThis = deleteThis;
    window.startInstanceEdit = startInstanceEdit;
    window.startServerEdit = startServerEdit;
    window.closeGcDeleteModal = closeGcDeleteModal;
    window.confirmGcDelete = confirmGcDelete;
    window.showPressureEvents = showPressureEvents;
})();
