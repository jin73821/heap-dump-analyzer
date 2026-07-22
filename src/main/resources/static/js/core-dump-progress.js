/* core-dump-progress.js — 코어 덤프 GDB 분석 진행 페이지 (progress.html)
 * 글로벌: FILENAME, SSE_URL, RESULT_URL (인라인 th:inline 주입).
 * 정직한 진행: GDB 실행 구간(percent 고정)은 indeterminate 바로 표시. */
(function () {
    'use strict';

    var mainBar = document.getElementById('mainBar');
    var progressTrack = document.getElementById('progressTrack');
    var pctText = document.getElementById('pctText');
    var statusLabel = document.getElementById('statusLabel');
    var statusMsg = document.getElementById('statusMsg');
    var logConsole = document.getElementById('logConsole');
    var logCountEl = document.getElementById('logCount');
    var logAutoscrollBtn = document.getElementById('logAutoscroll');
    var completeBanner = document.getElementById('completeBanner');
    var errorBanner = document.getElementById('errorBanner');
    var errorMsgEl = document.getElementById('errorMsg');
    var resultLink = document.getElementById('resultLink');
    var elapsedEl = document.getElementById('elapsedTime');
    var countdownText = document.getElementById('countdownText');

    // ── 경과 시간 ──────────────────────────────────────────────
    var startTime = null, elapsedTimer = null, lastPercent = 0;
    function startElapsed() {
        if (startTime) return;
        startTime = Date.now();
        elapsedTimer = setInterval(function () {
            var e = Date.now() - startTime;
            var m = Math.floor(e / 60000), s = Math.floor((e % 60000) / 1000);
            elapsedEl.textContent = String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
        }, 1000);
    }
    function stopElapsed() { if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null; } }

    // ── 타임라인 ───────────────────────────────────────────────
    var stepState = {};
    function setStep(id, state, desc) {
        if (stepState[id] === state && !desc) return;
        stepState[id] = state;
        var dot = document.getElementById('icon-' + id);
        var item = document.getElementById('step-' + id);
        var descEl = document.getElementById('desc-' + id);
        if (dot) dot.className = 'timeline-dot ' + state;
        if (desc && descEl) descEl.textContent = desc;
        if (item) {
            item.classList.remove('step-waiting', 'step-active', 'step-done', 'step-error', 'step-skipped');
            item.classList.add('step-' + state);
        }
    }

    function shortReason(msg) {
        if (msg.includes('파일 형식을 인식할 수 없') || msg.includes('코어 덤프 파일 형식이 아닙니다')) return '파일 형식 불일치';
        if (msg.includes('찾을 수 없') || msg.includes('경로가 올바르지 않')) return '파일 없음';
        if (msg.includes('시간 초과')) return '시간 초과';
        if (msg.includes('GDB 출력 없음')) return 'GDB 출력 없음';
        if (msg.includes('취소')) return '사용자 취소';
        if (msg.includes('vmcore')) return 'vmcore — crash 유틸 필요';
        return '오류 발생';
    }

    var STEPS = ['file', 'gdb', 'parse', 'save', 'done'];
    var STEP_IDX = { file: 1, gdb: 2, parse: 3, save: 4, done: 5 };
    function markTimelineError(msg) {
        var reason = shortReason(msg), found = false;
        STEPS.forEach(function (s) { if (stepState[s] === 'active') { setStep(s, 'error', '실패: ' + reason); found = true; } });
        if (!found) {
            for (var i = 0; i < STEPS.length; i++) {
                if (!stepState[STEPS[i]] || stepState[STEPS[i]] === 'waiting') { setStep(STEPS[i], 'error', '실패: ' + reason); break; }
            }
        }
        STEPS.forEach(function (s) { if (!stepState[s] || stepState[s] === 'waiting') setStep(s, 'skipped', '—'); });
    }
    function activeStepIdx() {
        for (var i = 0; i < STEPS.length; i++) if (stepState[STEPS[i]] === 'active') return STEP_IDX[STEPS[i]];
        return 2;
    }

    // ── 진행바 (indeterminate 지원) ─────────────────────────────
    function setProgress(pct, msg, state, label, indet) {
        if (indet) {
            mainBar.classList.add('indeterminate');
            mainBar.classList.remove('done', 'error');
            pctText.textContent = '···';
            if (progressTrack) progressTrack.removeAttribute('aria-valuenow');
        } else {
            mainBar.classList.remove('indeterminate');
            mainBar.style.width = pct + '%';
            pctText.textContent = (state === 'error') ? '실패' : (pct + '%');
            if (progressTrack) progressTrack.setAttribute('aria-valuenow', pct);
            if (state === 'done') { mainBar.classList.add('done'); mainBar.classList.remove('error'); }
            else if (state === 'error') { mainBar.classList.add('error'); mainBar.classList.remove('done'); }
            else { mainBar.classList.remove('done', 'error'); }
        }
        if (msg != null) statusMsg.textContent = msg;
        if (label != null) statusLabel.textContent = label;
    }

    // ── 로그 콘솔 ───────────────────────────────────────────────
    var logBuffer = [], logTotal = 0, logFlushId = null, autoScroll = true, recentLines = [];
    logConsole.addEventListener('scroll', function () {
        autoScroll = (logConsole.scrollHeight - logConsole.scrollTop - logConsole.clientHeight) < 60;
        syncAutoscrollBtn();
    }, { passive: true });
    function syncAutoscrollBtn() {
        if (!logAutoscrollBtn) return;
        logAutoscrollBtn.textContent = autoScroll ? '자동 스크롤 켜짐' : '맨 아래로';
        logAutoscrollBtn.classList.toggle('paused', !autoScroll);
    }
    if (logAutoscrollBtn) logAutoscrollBtn.addEventListener('click', function () {
        autoScroll = true; logConsole.scrollTop = logConsole.scrollHeight; syncAutoscrollBtn();
    });

    function appendLog(line) {
        var trimmed = line.length > 250 ? line.slice(0, 250) + '…' : line;
        logBuffer.push(trimmed);
        recentLines.push(trimmed);
        if (recentLines.length > 5) recentLines.shift();
        if (!logFlushId) logFlushId = setTimeout(flushLog, 120);
    }
    function flushLog() {
        logFlushId = null;
        if (!logBuffer.length) return;
        var chunk = logBuffer.join('\n') + '\n';
        logBuffer = [];
        var MAX = 600, TRIM = 400;
        logTotal += chunk.split('\n').length - 1;
        var existing = logConsole.textContent.split('\n').length - 1;
        if (existing > MAX) {
            var lines = logConsole.textContent.split('\n');
            logConsole.textContent = lines.slice(lines.length - TRIM).join('\n') + '\n';
        }
        logConsole.appendChild(document.createTextNode(chunk));
        logCountEl.textContent = existing > MAX ? ('최근 ' + TRIM + '줄 · 총 ' + logTotal) : (logTotal + ' lines');
        if (autoScroll) logConsole.scrollTop = logConsole.scrollHeight;
    }

    // ── 자동 이동 ──────────────────────────────────────────────
    var autoRedirectTimer = null, autoRedirectUrl = null, analysisDone = false;
    function goResult() { window.location.replace(autoRedirectUrl || RESULT_URL); }
    function showComplete(url) {
        stopElapsed();
        if (logBuffer.length) flushLog();
        autoRedirectUrl = url || RESULT_URL;
        analysisDone = true;
        resultLink.href = autoRedirectUrl;
        resultLink.onclick = function (e) { e.preventDefault(); goResult(); };
        completeBanner.classList.add('visible');
        setStep('done', 'done', '결과 페이지 준비 완료');
        var remaining = 5;
        countdownText.textContent = remaining + '초 후 자동으로 결과 페이지로 이동합니다...';
        autoRedirectTimer = setInterval(function () {
            remaining--;
            if (remaining <= 0) { clearInterval(autoRedirectTimer); autoRedirectTimer = null; goResult(); }
            else countdownText.textContent = remaining + '초 후 자동으로 결과 페이지로 이동합니다...';
        }, 1000);
    }
    function cancelAutoRedirect() {
        if (autoRedirectTimer) { clearInterval(autoRedirectTimer); autoRedirectTimer = null; }
        countdownText.textContent = '자동 이동이 취소되었습니다.';
        var btn = document.getElementById('stayBtn');
        if (btn) btn.style.display = 'none';
    }

    function hintFor(msg) {
        if (msg.includes('파일 형식을 인식할 수 없') || msg.includes('코어 덤프 파일 형식이 아닙니다')) return '업로드된 파일이 코어 덤프가 아닐 수 있습니다. Linux 프로세스 크래시 시 생성된 core 또는 core.PID 파일인지 확인하세요.';
        if (msg.includes('찾을 수 없') || msg.includes('경로가 올바르지 않')) return '코어 덤프 파일이 삭제되었거나 경로가 변경되었을 수 있습니다.';
        if (msg.includes('시간 초과')) return 'GDB 분석이 제한 시간을 초과했습니다. 실행 파일 없이 재분석하거나 관리자에게 문의하세요.';
        if (msg.includes('GDB 출력 없음') || msg.includes('outputLen=0')) return 'GDB가 설치되지 않았거나 유효하지 않은 코어 덤프 파일입니다.';
        if (msg.includes('vmcore')) return "'crash' 유틸리티를 사용해 vmcore를 분석하거나 관리자에게 문의하세요.";
        if (msg.includes('취소')) return '분석이 사용자에 의해 중단되었습니다. 재분석을 시도할 수 있습니다.';
        return '';
    }
    function showError(msg, hint) {
        if (logBuffer.length) flushLog();
        stopElapsed();
        markTimelineError(msg);
        setProgress(100, '분석 실패', 'error', '분석 실패', false);
        errorMsgEl.textContent = msg;
        document.getElementById('errorHint').textContent = (hint != null) ? hint : hintFor(msg);
        if (recentLines.length) {
            var body = document.getElementById('errorLogtailBody');
            if (body) body.textContent = recentLines.join('\n');
            var box = document.getElementById('errorLogtail');
            if (box) box.style.display = '';
        }
        errorBanner.classList.add('visible');
    }

    // ── SSE (백오프 재연결) ─────────────────────────────────────
    var reconnectAttempt = 0, MAX_RECONNECT = 3, evtSource = null;
    function startSSE() {
        evtSource = new EventSource(SSE_URL);

        evtSource.addEventListener('progress', function (e) {
            reconnectAttempt = 0;
            var d = JSON.parse(e.data);
            var status = d.status, percent = d.percent, message = d.message;

            if (status === 'RUNNING' || status === 'PARSING') startElapsed();
            if (status !== 'ERROR') lastPercent = percent;

            var indet = (status === 'RUNNING' && percent >= 15 && percent < 85);
            var label = message;
            if (indet) label = 'GDB 실행 중 — 단계 ' + activeStepIdx() + '/5 · 로그 ' + logTotal + '줄';
            setProgress(percent, message,
                status === 'COMPLETED' ? 'done' : status === 'ERROR' ? 'error' : 'running',
                label, indet && status !== 'COMPLETED' && status !== 'ERROR');

            if (percent >= 3 && percent < 10) setStep('file', 'done', '파일 확인 완료');
            else if (percent < 3) setStep('file', 'active');
            if (percent >= 10 && percent < 85) { setStep('file', 'done'); setStep('gdb', 'active'); }
            if (percent >= 85 && percent < 92) { setStep('gdb', 'done', 'GDB 실행 완료'); setStep('parse', 'active'); }
            if (percent >= 92 && percent < 100) { setStep('parse', 'done', '파싱 완료'); setStep('save', 'active'); }

            if (d.logLine) appendLog(d.logLine);

            if (status === 'COMPLETED') {
                evtSource.close();
                setStep('file', 'done'); setStep('gdb', 'done'); setStep('parse', 'done'); setStep('save', 'done', '저장 완료');
                setProgress(100, '분석 완료!', 'done', '분석 완료', false);
                showComplete(d.resultUrl || RESULT_URL);
            }
            if (status === 'ERROR') {
                evtSource.close();
                showError(d.errorMessage || '알 수 없는 오류가 발생했습니다.', null);
            }
        });

        evtSource.onerror = function () {
            evtSource.close();
            if (analysisDone) return;
            if (reconnectAttempt < MAX_RECONNECT) {
                reconnectAttempt++;
                statusMsg.textContent = '연결 재시도 중 ' + reconnectAttempt + '/' + MAX_RECONNECT + '...';
                setTimeout(startSSE, 800 * reconnectAttempt);
            } else {
                stopElapsed();
                setTimeout(probeCompletion, 1200);
            }
        };
    }

    // 재연결 소진 후: history 에서 SUCCESS 확인 (HEAD 200 오탐 방지)
    function probeCompletion() {
        fetch('/api/core-dump/history', { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(function (list) {
                var me = (list || []).filter(function (x) { return x.filename === FILENAME; })[0];
                if (me && me.status === 'SUCCESS') showComplete(RESULT_URL);
                else if (me && me.status === 'ERROR') showError(me.errorMessage || '분석에 실패했습니다.', null);
                else showError('SSE 연결이 끊겼고 분석 결과를 확인할 수 없습니다.', '재분석을 시도하거나 잠시 후 새로고침해 주세요.');
            })
            .catch(function () {
                showError('SSE 연결이 끊겼고 서버 응답이 없습니다.', '서버 재기동 중이거나 네트워크 문제일 수 있습니다. 잠시 후 새로고침해 주세요.');
            });
    }

    // ── 재분석 ─────────────────────────────────────────────────
    function retryAnalysis() {
        var btn = document.getElementById('retryBtn');
        if (btn) { btn.disabled = true; btn.textContent = '재분석 요청 중...'; }
        var meta = document.querySelector('meta[name="_csrf"]');
        var metaH = document.querySelector('meta[name="_csrf_header"]');
        var headers = {};
        if (meta && metaH) headers[metaH.content] = meta.content;
        fetch('/api/core-dump/reanalyze/' + encodeURIComponent(FILENAME), { method: 'POST', headers: headers })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (d.status === 'ok') window.location.reload();
                else { alert('재분석 요청 실패: ' + (d.message || '알 수 없는 오류')); if (btn) { btn.disabled = false; btn.textContent = '재분석'; } }
            })
            .catch(function (e) { alert('재분석 중 오류: ' + e.message); if (btn) { btn.disabled = false; btn.textContent = '재분석'; } });
    }

    window.cancelAutoRedirect = cancelAutoRedirect;
    window.retryAnalysis = retryAnalysis;

    window.addEventListener('pageshow', function (e) { if (e.persisted && analysisDone) goResult(); });

    syncAutoscrollBtn();
    startSSE();
})();
