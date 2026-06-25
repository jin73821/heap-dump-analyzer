/* core-dump-progress.js — 코어 덤프 GDB 분석 진행 페이지 (progress.html)
 * 페이지가 노출하는 글로벌: FILENAME, SSE_URL, RESULT_URL (인라인 th:inline 로 주입).
 * body 말미에서 로드되므로 DOM 은 이미 준비됨. */
(function () {
    'use strict';

    var mainBar = document.getElementById('mainBar');
    var pctText = document.getElementById('pctText');
    var statusLabel = document.getElementById('statusLabel');
    var statusMsg = document.getElementById('statusMsg');
    var logConsole = document.getElementById('logConsole');
    var logCountEl = document.getElementById('logCount');
    var completeBanner = document.getElementById('completeBanner');
    var errorBanner = document.getElementById('errorBanner');
    var errorMsgEl = document.getElementById('errorMsg');
    var resultLink = document.getElementById('resultLink');
    var elapsedEl = document.getElementById('elapsedTime');
    var countdownText = document.getElementById('countdownText');

    // ── 경과 시간 ──────────────────────────────────────────────
    var startTime = null, elapsedTimer = null;
    var lastPercent = 0;
    function startElapsed() {
        if (startTime) return;
        startTime = Date.now();
        elapsedTimer = setInterval(function () {
            var e = Date.now() - startTime;
            var m = Math.floor(e / 60000), s = Math.floor((e % 60000) / 1000);
            elapsedEl.textContent = String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
        }, 1000);
    }

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
            item.classList.remove('step-waiting', 'step-active', 'step-done', 'step-error');
            item.classList.add('step-' + state);
        }
    }

    // ── 에러 메시지 → 단계 설명 축약 ─────────────────────────────
    function shortReason(msg) {
        if (msg.includes('파일 형식을 인식할 수 없') || msg.includes('코어 덤프 파일 형식이 아닙니다')) return '파일 형식 불일치';
        if (msg.includes('찾을 수 없') || msg.includes('경로가 올바르지 않')) return '파일 없음';
        if (msg.includes('시간 초과')) return '시간 초과';
        if (msg.includes('GDB 출력 없음')) return 'GDB 출력 없음';
        if (msg.includes('취소')) return '사용자 취소';
        if (msg.includes('vmcore')) return 'vmcore — crash 유틸 필요';
        return '오류 발생';
    }

    // ── 에러 발생 시 타임라인 정리 ────────────────────────────────
    var STEPS = ['file', 'gdb', 'parse', 'save', 'done'];
    function markTimelineError(msg) {
        var reason = shortReason(msg);
        var foundError = false;
        // active 상태 스텝 → error
        STEPS.forEach(function (s) {
            if (stepState[s] === 'active') {
                setStep(s, 'error', '실패: ' + reason);
                foundError = true;
            }
        });
        // active 없으면 첫 번째 waiting 스텝을 error로
        if (!foundError) {
            for (var i = 0; i < STEPS.length; i++) {
                if (!stepState[STEPS[i]] || stepState[STEPS[i]] === 'waiting') {
                    setStep(STEPS[i], 'error', '실패: ' + reason);
                    break;
                }
            }
        }
        // 나머지 waiting 스텝 → skipped (흐리게)
        STEPS.forEach(function (s) {
            if (!stepState[s] || stepState[s] === 'waiting') setStep(s, 'skipped', '—');
        });
    }

    // ── 진행바 ─────────────────────────────────────────────────
    function setProgress(pct, msg, state, label) {
        mainBar.style.width = pct + '%';
        pctText.textContent = pct + '%';
        if (msg) statusMsg.textContent = msg;
        if (label) statusLabel.textContent = label;
        if (state === 'done') { mainBar.classList.add('done'); mainBar.classList.remove('error'); }
        else if (state === 'error') { mainBar.classList.add('error'); mainBar.classList.remove('done'); }
        else { mainBar.classList.remove('done', 'error'); }
    }

    // ── 로그 콘솔 (버퍼링) ─────────────────────────────────────
    var logBuffer = [], logTotal = 0, logFlushId = null, autoScroll = true;
    logConsole.addEventListener('scroll', function () {
        autoScroll = (logConsole.scrollHeight - logConsole.scrollTop - logConsole.clientHeight) < 60;
    }, { passive: true });

    function appendLog(line) {
        logBuffer.push(line.length > 250 ? line.slice(0, 250) + '…' : line);
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
        logCountEl.textContent = logTotal + ' lines';
        if (autoScroll) logConsole.scrollTop = 999999;
    }

    // ── 자동 이동 ──────────────────────────────────────────────
    var autoRedirectTimer = null, autoRedirectUrl = null;
    function showComplete(url) {
        if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null; }
        if (logBuffer.length) flushLog();
        autoRedirectUrl = url || RESULT_URL;
        resultLink.href = autoRedirectUrl;
        completeBanner.classList.add('visible');
        setStep('done', 'done', '결과 페이지 준비 완료');

        var remaining = 5;
        countdownText.textContent = remaining + '초 후 자동으로 결과 페이지로 이동합니다...';
        autoRedirectTimer = setInterval(function () {
            remaining--;
            if (remaining <= 0) {
                clearInterval(autoRedirectTimer);
                autoRedirectTimer = null;
                window.location.href = autoRedirectUrl;
            } else {
                countdownText.textContent = remaining + '초 후 자동으로 결과 페이지로 이동합니다...';
            }
        }, 1000);
    }

    function cancelAutoRedirect() {
        if (autoRedirectTimer) { clearInterval(autoRedirectTimer); autoRedirectTimer = null; }
        countdownText.textContent = '자동 이동이 취소되었습니다.';
        var btn = document.getElementById('stayBtn');
        if (btn) btn.style.display = 'none';
    }

    // ── SSE ────────────────────────────────────────────────────
    function startSSE() {
        var evtSource = new EventSource(SSE_URL);

        evtSource.addEventListener('progress', function (e) {
            var d = JSON.parse(e.data);
            var status = d.status, percent = d.percent, message = d.message;

            if (status === 'RUNNING' || status === 'PARSING') startElapsed();

            if (status !== 'ERROR') lastPercent = percent;
            setProgress(percent, message,
                status === 'COMPLETED' ? 'done' : status === 'ERROR' ? 'error' : 'running',
                message);

            if (percent >= 3 && percent < 10) setStep('file', 'done', '파일 확인 완료');
            else if (percent < 3) setStep('file', 'active');

            if (percent >= 10 && percent < 85) { setStep('file', 'done'); setStep('gdb', 'active'); }
            if (percent >= 85 && percent < 92) { setStep('gdb', 'done', 'GDB 실행 완료'); setStep('parse', 'active'); }
            if (percent >= 92 && percent < 100) { setStep('parse', 'done', '파싱 완료'); setStep('save', 'active'); }

            if (d.logLine) appendLog(d.logLine);

            if (status === 'COMPLETED') {
                evtSource.close();
                setStep('file', 'done'); setStep('gdb', 'done'); setStep('parse', 'done'); setStep('save', 'done', '저장 완료');
                setProgress(100, '분석 완료!', 'done', '분석 완료');
                showComplete(d.resultUrl || RESULT_URL);
            }
            if (status === 'ERROR') {
                evtSource.close();
                if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null; }
                if (logBuffer.length) flushLog();
                var msg = d.errorMessage || '알 수 없는 오류가 발생했습니다.';
                markTimelineError(msg);
                // 진행바를 100%로 채우되 빨간색 — 에러로 "완료"됨을 명확히
                setProgress(100, '분석 실패', 'error', '분석 실패');
                errorMsgEl.textContent = msg;
                var hint = '';
                if (msg.includes('파일 형식을 인식할 수 없') || msg.includes('코어 덤프 파일 형식이 아닙니다')) hint = '업로드된 파일이 코어 덤프가 아닐 수 있습니다. Linux 프로세스 크래시 시 생성된 core 또는 core.PID 파일인지 확인하세요.';
                else if (msg.includes('찾을 수 없') || msg.includes('경로가 올바르지 않')) hint = '코어 덤프 파일이 삭제되었거나 경로가 변경되었을 수 있습니다.';
                else if (msg.includes('시간 초과')) hint = 'GDB 분석이 제한 시간을 초과했습니다. 실행 파일 없이 재분석하거나 관리자에게 문의하세요.';
                else if (msg.includes('GDB 출력 없음') || msg.includes('outputLen=0')) hint = 'GDB가 설치되지 않았거나 유효하지 않은 코어 덤프 파일입니다.';
                else if (msg.includes('vmcore')) hint = "'crash' 유틸리티를 사용해 vmcore를 분석하거나 관리자에게 문의하세요.";
                else if (msg.includes('취소')) hint = '분석이 사용자에 의해 중단되었습니다. 재분석을 시도할 수 있습니다.';
                document.getElementById('errorHint').textContent = hint;
                errorBanner.classList.add('visible');
            }
        });

        evtSource.onerror = function () {
            evtSource.close();
            if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null; }
            setTimeout(function () {
                fetch(RESULT_URL, { method: 'HEAD' })
                    .then(function (r) {
                        if (r.ok) showComplete(RESULT_URL);
                        else showSseError('SSE 연결이 끊겼고 분석 결과를 확인할 수 없습니다.',
                            '재분석을 시도하거나 잠시 후 새로고침해 주세요.');
                    })
                    .catch(function () {
                        showSseError('SSE 연결이 끊겼고 서버 응답이 없습니다.',
                            '서버 재기동 중이거나 네트워크 문제일 수 있습니다. 잠시 후 새로고침해 주세요.');
                    });
            }, 2000);
        };

        function showSseError(msg, hint) {
            if (logBuffer.length) flushLog();
            markTimelineError(msg);
            setProgress(100, '연결 오류', 'error', '연결 오류');
            errorMsgEl.textContent = msg;
            document.getElementById('errorHint').textContent = hint;
            errorBanner.classList.add('visible');
        }
    }

    // ── 재분석: result.json 삭제 후 리로드 → SSE 재시작 ──────────
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
                else {
                    alert('재분석 요청 실패: ' + (d.message || '알 수 없는 오류'));
                    if (btn) { btn.disabled = false; btn.innerHTML = '&#8635; 재분석'; }
                }
            })
            .catch(function (e) {
                alert('재분석 중 오류: ' + e.message);
                if (btn) { btn.disabled = false; btn.innerHTML = '&#8635; 재분석'; }
            });
    }

    // 전역 노출 (인라인 onclick 핸들러용)
    window.cancelAutoRedirect = cancelAutoRedirect;
    window.retryAnalysis = retryAnalysis;

    startSSE();
})();
