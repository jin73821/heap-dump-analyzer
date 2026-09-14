/* gc-log-analyze.js — GC 로그 결과 페이지 (gc-log/analyze.html, 2026-09-14)
 * 상태 폴링(분석 중) · KPI/차트/소견/이벤트 표/원문 렌더 · 힙 덤프 매칭 칩 · AI 해석.
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

    function renderKpi(r) {
        var k = r.kpi, m = r.meta, ps = r.pauseStats, t = r.trend;
        var h = '';
        h += kpiCard('수집기 · JDK', m.collector + (m.jdkVersion ? ' · ' + m.jdkVersion : ''), (m.format === 'UNIFIED' ? '통합 로깅' : 'JDK 8 이하 PrintGCDetails') + (m.truncated ? ' · 일부 절단' : ''));
        var timeSub = m.timeSource === 'absolute' ? fmtTs(m.logStartEpochMs) + ' ~ ' + fmtTs(m.logEndEpochMs) : m.timeSource === 'mtime' ? '절대 시각 없음 — mtime 기준 추정' : '절대 시각 없음(uptime 만)';
        h += kpiCard('로그 기간', fmtDur(m.durationSec), timeSub);
        var tpCls = k.throughputPct == null ? '' : k.throughputPct < 90 ? 'bad' : k.throughputPct < 95 ? 'warn' : '';
        h += kpiCard('처리량', pct(k.throughputPct, 2), 'GC 일시정지 합 ' + fmtMs(ps.totalMs) + ' / ' + ps.count + '회', tpCls);
        h += kpiCard('일시정지 p99 · 최대', fmtMs(ps.p99Ms) + ' · ' + fmtMs(ps.maxMs), 'p50 ' + fmtMs(ps.p50Ms) + ' · p95 ' + fmtMs(ps.p95Ms) + (ps.approximate ? ' (근사)' : ''), ps.maxMs > 5000 ? 'bad' : ps.maxMs > 1000 ? 'warn' : '');
        h += kpiCard('Full GC', String(k.fullCount), k.fullGcPerHour != null ? '시간당 ' + k.fullGcPerHour.toFixed(2) + '회 · 연속 최대 ' + k.maxConsecutiveFull : '', k.fullGcPerHour > 6 ? 'bad' : k.fullGcPerHour > 1 ? 'warn' : '');
        h += kpiCard('이벤트', String(k.eventCount), 'Young ' + k.youngCount + ' · Mixed ' + k.mixedCount + ' · Concurrent ' + k.concurrentCount);
        h += kpiCard('최대 힙 용량', fmtBytes(k.maxHeapTotalBytes), '마지막 GC 후 ' + fmtBytes(k.lastHeapAfterBytes) + (k.maxHeapTotalBytes && k.lastHeapAfterBytes ? ' (' + Math.round(100 * k.lastHeapAfterBytes / k.maxHeapTotalBytes) + '%)' : ''));
        h += kpiCard('할당률', k.allocationRateMbPerSec == null ? '–' : k.allocationRateMbPerSec.toFixed(1) + ' MB/s', k.promotionRateMbPerSec != null ? '승격 ' + k.promotionRateMbPerSec.toFixed(3) + ' MB/s' : '');
        var basisLabel = t.basis === 'full' ? 'Full GC' : t.basis === 'remark' ? 'Remark' : t.basis === 'mixed' ? 'Mixed' : '';
        h += kpiCard(basisLabel ? basisLabel + ' 직후 힙 추세' : '힙 추세', t.slopeMbPerHour == null ? '–' : (t.slopeMbPerHour >= 0 ? '+' : '') + t.slopeMbPerHour.toFixed(1) + ' MB/h',
            t.r2 != null ? 'R² ' + t.r2.toFixed(2) + ' · ' + (t.pointsUsed != null ? t.pointsUsed : t.afterPoints.length) + '점 · ' + fmtBytes(t.firstAfterBytes) + ' → ' + fmtBytes(t.lastAfterBytes)
                + (t.excludedWarmup ? ' · 기동 직후 ' + t.excludedWarmup + '점 제외' : '') : (t.note || '점이 부족함'),
            (t.slopeMbPerHour > 0 && t.r2 > 0.5) ? 'warn' : '');
        if (k.metaspaceLastBytes != null) h += kpiCard(m.permGen ? 'PermGen' : 'Metaspace', fmtBytes(k.metaspaceLastBytes), '시작 ' + fmtBytes(k.metaspaceFirstBytes) + (k.metaspaceMaxTotalBytes ? ' · 예약 ' + fmtBytes(k.metaspaceMaxTotalBytes) : ''));
        if (k.maxOverheadPct10m != null) h += kpiCard('10분 창 최대 GC 비중', pct(k.maxOverheadPct10m, 1), 'uptime ' + fmtDur(k.maxOverheadWindowStartSec) + ' 부근', k.maxOverheadPct10m >= 50 ? 'bad' : k.maxOverheadPct10m >= 25 ? 'warn' : '');
        h += INSTANCE_CARD;
        document.getElementById('gclKpi').innerHTML = h;
        renderInstance(_instance);
    }

    // ── 인스턴스 카드 ────────────────────────────────────────
    // 값 = 수동 입력 > 연결된 힙 덤프의 Instance(analyze 화면 Instance 칩과 같은 값). 서버 뷰({name,source,manual,dump,dumpFilename})를
    // 그대로 그린다 — 초기값은 GC_INSTANCE, 이후 /instance 저장 응답과 /match 계열 응답(연결 변경)이 갱신한다.
    // ⚠ 편집 중에는 값 줄을 숨기고 입력 줄을 형제로 끼운다 — 그 사이 매칭 응답이 와도 renderInstance 가 입력을 덮지 않는다.
    var INSTANCE_CARD = '<div class="gcl-kpi gcl-kpi-inst" id="gclInstCard">'
        + '<div class="gcl-kpi-label">인스턴스</div>'
        + '<button type="button" class="gcl-kpi-edit" id="gclInstEdit" onclick="startInstanceEdit()" title="인스턴스명 편집" aria-label="인스턴스명 편집">&#9998;</button>'
        + '<div class="gcl-kpi-value" id="gclInstValue"></div>'
        + '<div class="gcl-kpi-sub" id="gclInstSub"></div></div>';
    var _instance = (typeof GC_INSTANCE !== 'undefined' && GC_INSTANCE) ? GC_INSTANCE : null;

    function renderInstance(v) {
        if (v) _instance = v;
        v = _instance || {};
        var val = document.getElementById('gclInstValue');
        var sub = document.getElementById('gclInstSub');
        if (!val || !sub) return;
        var name = v.name || '';
        val.textContent = name || '미지정';
        val.title = name;
        val.classList.toggle('empty', !name);
        var s;
        if (v.source === 'manual') {
            s = '수동 입력' + (v.dump && v.dump !== v.manual ? ' · 덤프 값 ' + v.dump : '');
        } else if (v.source === 'dump') {
            s = '연결된 힙 덤프 · ' + v.dumpFilename;
        } else if (v.dumpFilename) {
            s = '연결된 힙 덤프에 Instance 없음 — 직접 입력';
        } else {
            s = '힙 덤프를 연결하면 자동으로 가져옵니다';
        }
        sub.textContent = s;
        sub.title = s;
    }

    function startInstanceEdit() {
        var card = document.getElementById('gclInstCard');
        var val = document.getElementById('gclInstValue');
        var btn = document.getElementById('gclInstEdit');
        if (!card || !val || card.querySelector('.gcl-inst-form')) return;
        var form = document.createElement('div');
        form.className = 'gcl-inst-form';
        var input = document.createElement('input');
        input.type = 'text';
        input.className = 'gcl-inst-input';
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
            tr.className = 'ev-' + e.type + (abnormal ? ' ev-flag' : '');
            tr.setAttribute('data-seq', e.seq); tr.setAttribute('data-line', e.line);
            tr.setAttribute('data-ts', e.tsEpochMs == null ? '' : String(e.tsEpochMs)); tr.setAttribute('data-uptime', e.uptimeSec == null ? '' : e.uptimeSec);
            tr.setAttribute('data-type', e.type); tr.setAttribute('data-pause', e.pauseMs == null ? '' : e.pauseMs);
            tr.setAttribute('data-search', (e.type + ' ' + (e.cause || '') + ' ' + (e.flags || []).join(' ')).toLowerCase());
            tr.setAttribute('data-flag', (e.type === 'FULL' || abnormal) ? '1' : '0');
            var heap = e.heapBefore != null ? fmtBytes(e.heapBefore) + ' → ' + fmtBytes(e.heapAfter) + (e.heapTotal != null ? ' / ' + fmtBytes(e.heapTotal) : '') : '–';
            var cpu = e.userSec != null ? e.userSec.toFixed(2) + ' / ' + e.sysSec.toFixed(2) + ' / ' + e.realSec.toFixed(2) : '–';
            tr.innerHTML = '<td class="num">' + e.seq + '</td><td class="num">' + e.line + '</td><td>' + esc(fmtTs(e.tsEpochMs)) + '</td>'
                + '<td class="num">' + (e.uptimeSec == null ? '–' : xTick(e.uptimeSec)) + '</td>'
                + '<td><span class="gcl-type t-' + esc(e.type) + '">' + esc(e.type === 'CONCURRENT_CYCLE' ? 'CONC' : e.type) + '</span></td>'
                + '<td title="' + esc(e.cause || '') + '">' + esc(e.cause || '') + '</td>'
                + '<td class="num">' + (e.pauseMs == null ? '–' : (e.concurrent ? '(' + fmtMs(e.pauseMs) + ')' : fmtMs(e.pauseMs))) + '</td>'
                + '<td>' + esc(heap) + '</td><td class="num">' + esc(cpu) + '</td>'
                + '<td>' + (e.flags || []).map(function (f) { return '<span class="gcl-flag">' + esc(f) + '</span>'; }).join('') + '</td>';
            return tr;
        });
        var search = document.getElementById('gclEvSearch');
        var onlyFlag = document.getElementById('gclEvOnlyFlag');
        function applyFilter() {
            var q = (search.value || '').trim().toLowerCase();
            var only = onlyFlag.checked;
            _evGrid.setFiltered(_evGrid.allRows.filter(function (tr) {
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
        applyFilter();
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

    // ── AI ───────────────────────────────────────────────────
    function renderAi(d) {
        var body = document.getElementById('gclAiBody');
        var meta = document.getElementById('gclAiMetaTitle');
        if (!d || d.found === false) { return; }
        var data = d.data || d;
        var sev = data.severity || 'Unknown';
        var h = '<div class="gcl-ai-sev sev-' + esc(sev) + '">' + esc(sev) + (data.severityDesc ? ' — ' + esc(data.severityDesc) : '') + '</div>';
        if (data.summary) h += '<div class="gcl-ai-block"><h4>요약</h4><div>' + esc(data.summary) + '</div></div>';
        if (data.rootCause) h += '<div class="gcl-ai-block"><h4>근본 원인</h4><div>' + esc(data.rootCause) + '</div></div>';
        var recs = data.recommendations;
        if (typeof recs === 'string') recs = recs.split(/\n+/).filter(Boolean);
        if (recs && recs.length) h += '<div class="gcl-ai-block"><h4>권고</h4><ol>' + recs.map(function (r) { return '<li>' + esc(String(r).replace(/^\s*\d+[.)]\s*/, '')) + '</li>'; }).join('') + '</ol></div>';
        if (data.gcTuningAdvice) h += '<div class="gcl-ai-block"><h4>GC 튜닝 제안</h4><div>' + esc(data.gcTuningAdvice) + '</div></div>';
        var at = d.analysedAt || data.analysedAt;
        h += '<div class="gcl-ai-meta">' + (d.model || data.model ? '<span>모델 ' + esc(d.model || data.model) + '</span>' : '') + (at ? '<span>' + esc(fmtTs(typeof at === 'number' ? at : Date.parse(at))) + '</span>' : '')
            + (d.saved === false ? '<span style="color:#991B1B">저장 실패: ' + esc(d.saveError || '') + '</span>' : '') + '<button type="button" class="btn btn-sm btn-ghost" onclick="deleteAi()">삭제</button></div>';
        body.innerHTML = h;
        if (meta) meta.textContent = '';
    }
    function loadAi() {
        Common.fetchJSON(api('/ai-insight')).then(function (d) { if (d && d.found) renderAi(d); }).catch(function (e) { ignored('AI 인사이트 조회', e); });
    }
    var _aiRunning = false;
    function runAi() {
        if (_aiRunning) return;
        _aiRunning = true;
        if (window.SessionTimeout && SessionTimeout.registerActivityGuard) SessionTimeout.registerActivityGuard(function () { return _aiRunning; });
        var btn = document.getElementById('btnAi'); btn.disabled = true; btn.textContent = 'AI 해석 중…';
        var body = document.getElementById('gclAiBody');
        body.innerHTML = '<div class="gcl-ai-empty"><span class="gcl-spinner" style="display:inline-block;vertical-align:middle;margin-right:8px"></span>LLM 이 GC 통계를 해석하는 중입니다…</div>';
        Common.fetchJSON(api('/ai-analyze'), { method: 'POST' })
            .then(function (d) {
                _aiRunning = false; btn.disabled = false; btn.textContent = 'AI 해석';
                if (!d.success) { body.innerHTML = '<div class="gcl-ai-err">' + esc(d.error || d.errorCode || 'AI 해석 실패') + '</div>'; return; }
                renderAi(d);
            })
            .catch(function (e) {
                _aiRunning = false; btn.disabled = false; btn.textContent = 'AI 해석';
                var msg = e && e.rateLimited ? 'LLM 호출량 제한 — ' + (e.retryAfterSeconds ? e.retryAfterSeconds + '초 후 다시 시도하세요' : '잠시 후 다시 시도하세요') : errMsg(e, 'AI 해석 실패');
                body.innerHTML = '<div class="gcl-ai-err">' + esc(msg) + '</div>';
            });
    }
    function deleteAi() {
        if (!confirm('AI 해석 결과를 삭제할까요?')) return;
        Common.fetchJSON(api('/ai-insight'), { method: 'DELETE' })
            .then(function () { document.getElementById('gclAiBody').innerHTML = '<div class="gcl-ai-empty">삭제했습니다. 상단 <b>AI 해석</b> 버튼으로 다시 실행할 수 있습니다.</div>'; })
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

        var search = document.getElementById('matchPickSearch');
        if (search) search.addEventListener('input', function () { renderPickList(search.value); });

        if (GC_STATUS === 'SUCCESS') {
            R = loadResult();
            if (!R) { showError('저장된 결과를 읽지 못했습니다. 재분석하세요.'); document.getElementById('gclResult').style.display = 'none'; document.getElementById('gclProgress').style.display = ''; return; }
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
    window.cancelAnalysis = cancelAnalysis;
    window.showRaw = showRaw;
    window.openMatchedDump = openMatchedDump;
    window.unlinkMatch = unlinkMatch;
    window.rematch = rematch;
    window.openMatchPickModal = openMatchPickModal;
    window.closeMatchPickModal = closeMatchPickModal;
    window.confirmMatchPick = confirmMatchPick;
    window.runAi = runAi;
    window.deleteAi = deleteAi;
    window.deleteThis = deleteThis;
    window.startInstanceEdit = startInstanceEdit;
    window.closeGcDeleteModal = closeGcDeleteModal;
    window.confirmGcDelete = confirmGcDelete;
})();
