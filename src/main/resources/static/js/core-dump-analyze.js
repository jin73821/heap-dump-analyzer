/* core-dump-analyze.js — 코어 덤프 분석 결과 페이지 (analyze.html)
 * 페이지가 노출하는 글로벌: CORE_FILENAME, (선택) REGISTERS, CORE_HAS_RESULT
 * window.Common (banner.html 로드) 의 fetchJSON/escHtml 사용. */
(function () {
    'use strict';

    var FN = typeof CORE_FILENAME !== 'undefined' ? CORE_FILENAME : '';

    // ── 재분석 ─────────────────────────────────────────────────
    function reanalyzeThis(btn) {
        var filename = btn.dataset.filename;
        if (!filename) return;
        btn.disabled = true; btn.textContent = '요청 중...';
        var meta = document.querySelector('meta[name="_csrf"]');
        var metaH = document.querySelector('meta[name="_csrf_header"]');
        var headers = { 'Content-Type': 'application/json' };
        if (meta && metaH) headers[metaH.content] = meta.content;
        fetch('/api/core-dump/reanalyze/' + encodeURIComponent(filename), { method: 'POST', headers: headers })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (d.status === 'ok') {
                    window.location.href = '/core-dump/progress/' + encodeURIComponent(filename);
                } else {
                    alert('재분석 요청 실패: ' + (d.message || '알 수 없는 오류'));
                    btn.disabled = false; btn.innerHTML = '&#8635; 재분석';
                }
            })
            .catch(function (e) {
                alert('재분석 중 오류: ' + e.message);
                btn.disabled = false; btn.innerHTML = '&#8635; 재분석';
            });
    }

    // ── 탭 전환 ─────────────────────────────────────────────────
    function switchTab(name, e) {
        document.querySelectorAll('.cd-tab-panel').forEach(function (el) { el.classList.remove('active'); });
        document.querySelectorAll('.cd-tab-btn').forEach(function (el) { el.classList.remove('active'); });
        var panel = document.getElementById('tab-' + name);
        if (panel) panel.classList.add('active');
        if (e && e.target) e.target.classList.add('active');
    }

    // ── 스레드 아코디언 ──────────────────────────────────────────
    function toggleThread(id) {
        var body = document.getElementById('thread-body-' + id);
        var chevron = document.getElementById('chevron-' + id);
        if (!body) return;
        var isOpen = body.classList.contains('open');
        body.classList.toggle('open', !isOpen);
        if (chevron) chevron.classList.toggle('open', !isOpen);
    }

    // ── 프레임 카드 locals 토글 ──────────────────────────────────
    function toggleFrame(el) {
        if (!el.querySelector('.frame-locals')) return;
        el.classList.toggle('expanded');
    }

    // ── 소스 코드 뷰어 ───────────────────────────────────────────
    function escSrc(s) {
        return String(s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function renderSourceView(data) {
        var html = '<div class="src-filepath">' + escSrc(data.filePath) + '</div><div class="src-lines">';
        data.lines.forEach(function (l) {
            var cls = l.isTarget ? ' src-line-target' : '';
            html += '<div class="src-line' + cls + '">'
                + '<span class="src-linenum">' + l.lineNum + '</span>'
                + '<span class="src-code">' + escSrc(l.content) + '</span></div>';
        });
        return html + '</div>';
    }

    function autoLoadHeroSource(location, container) {
        fetch('/api/core-dump/' + encodeURIComponent(FN) + '/source?location=' + encodeURIComponent(location) + '&context=6')
            .then(function (r) { return r.json(); })
            .then(function (d) { if (!d.error) container.innerHTML = renderSourceView(d); })
            .catch(function () { /* 소스 없음 — 조용히 무시 */ });
    }

    function loadFrameSource(btn) {
        var location = btn.dataset.location;
        var container = document.getElementById('src-' + btn.dataset.frameidx);
        if (!container) return;

        if (container.classList.contains('visible')) {
            container.classList.remove('visible');
            btn.innerHTML = '&#128196; 소스';
            return;
        }
        if (container.dataset.loaded) {
            container.classList.add('visible');
            btn.innerHTML = '&#10005; 닫기';
            return;
        }
        container.innerHTML = '<div class="src-loading">소스 파일 로딩 중...</div>';
        container.classList.add('visible');
        btn.innerHTML = '&#10005; 닫기';

        fetch('/api/core-dump/' + encodeURIComponent(FN) + '/source?location=' + encodeURIComponent(location) + '&context=8')
            .then(function (r) { return r.json(); })
            .then(function (d) {
                container.dataset.loaded = '1';
                container.innerHTML = d.error
                    ? '<div class="src-error">&#9888; ' + escSrc(d.error) + '</div>'
                    : renderSourceView(d);
            })
            .catch(function () {
                container.innerHTML = '<div class="src-error">소스 파일을 불러올 수 없습니다</div>';
            });
    }

    // ── Raw 출력 복사 ────────────────────────────────────────────
    function copyRaw() {
        var pre = document.getElementById('rawOutput');
        if (!pre) return;
        navigator.clipboard.writeText(pre.textContent)
            .then(function () { alert('복사되었습니다.'); })
            .catch(function () {
                var sel = window.getSelection();
                var range = document.createRange();
                range.selectNodeContents(pre);
                sel.removeAllRanges(); sel.addRange(range);
                document.execCommand('copy');
                sel.removeAllRanges();
                alert('복사되었습니다.');
            });
    }

    // ── 레지스터 테이블 빌드 ─────────────────────────────────────
    function buildRegisters() {
        if (typeof REGISTERS === 'undefined') return;
        var keys = Object.keys(REGISTERS);
        var half = Math.ceil(keys.length / 2);
        var left = document.getElementById('regTbodyLeft');
        var right = document.getElementById('regTbodyRight');
        if (!left || !right) return;
        keys.forEach(function (k, i) {
            var tr = document.createElement('tr');
            var esc = window.Common ? Common.escHtml : function (s) { return s; };
            tr.innerHTML = '<td>' + esc(k) + '</td><td>' + esc(REGISTERS[k]) + '</td>';
            (i < half ? left : right).appendChild(tr);
        });
    }

    // ════════════════ AI 크래시 분석 패널 ════════════════
    // 위험도 색상은 analyze.js _SEV_CONFIG 와 1:1 일치 (CLAUDE.md 함정 #22).
    function _sevSvg(fill, strokeAttr) {
        return '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 16 16" style="vertical-align:middle;display:inline-block">'
            + '<circle cx="8" cy="8" r="6.5" fill="' + fill + '"' + (strokeAttr || '') + '/></svg>';
    }
    var _SEV_CONFIG = {
        Critical: { bg: 'linear-gradient(135deg,#FEF2F2,#FFE4E4)', border: '#FECACA', color: '#DC2626', icon: _sevSvg('#DC2626'), textColor: '#7F1D1D' },
        High:     { bg: 'linear-gradient(135deg,#FEFCE8,#FEF9C3)', border: '#FDE047', color: '#CA8A04', icon: _sevSvg('#EAB308'), textColor: '#713F12' },
        Medium:   { bg: 'linear-gradient(135deg,#F0FDF4,#DCFCE7)', border: '#86EFAC', color: '#16A34A', icon: _sevSvg('#22C55E'), textColor: '#14532D' },
        Low:      { bg: 'linear-gradient(135deg,#EFF6FF,#DBEAFE)', border: '#93C5FD', color: '#2563EB', icon: _sevSvg('#3B82F6'), textColor: '#1E3A5F' },
        Unknown:  { bg: 'linear-gradient(135deg,#F9FAFB,#F3F4F6)', border: '#E5E7EB', color: '#6B7280', icon: _sevSvg('#F3F4F6', ' stroke="#9CA3AF" stroke-width="1"'), textColor: '#374151' }
    };

    var _elapsedTimer = null;

    function _show(id) {
        ['cdaStateEmpty', 'cdaStateLoading', 'cdaStateResult', 'cdaStateError'].forEach(function (s) {
            var el = document.getElementById(s);
            if (el) el.style.display = (s === id) ? '' : 'none';
        });
    }

    function _setBadge(state) {
        var b = document.getElementById('cdaBadge');
        if (!b) return;
        if (state === 'done') { b.textContent = 'AI 분석 완료'; b.classList.add('is-done'); }
        else { b.textContent = '미분석'; b.classList.remove('is-done'); }
        var act = document.getElementById('cdaActions');
        if (act) act.style.display = (state === 'done') ? 'flex' : 'none';
    }

    function _setLineBreaks(el, text) {
        if (!el) return;
        el.textContent = '';
        var parts = String(text || '-').split('\n');
        parts.forEach(function (p, i) {
            if (i > 0) el.appendChild(document.createElement('br'));
            el.appendChild(document.createTextNode(p));
        });
    }

    function _setNumberedList(el, text) {
        if (!el) return;
        el.innerHTML = '';
        var lines = String(text || '').split('\n').map(function (s) { return s.replace(/^\s*\d+[.)]\s*/, '').trim(); }).filter(Boolean);
        if (!lines.length) { _setLineBreaks(el, text); return; }
        var ol = document.createElement('ol');
        lines.forEach(function (line) {
            var li = document.createElement('li');
            li.textContent = line;
            ol.appendChild(li);
        });
        el.appendChild(ol);
    }

    function renderCoreAiResult(result, isSaved) {
        var data = result.data || result;
        var severity = data.severity || 'Unknown';
        var cfg = _SEV_CONFIG[severity] || _SEV_CONFIG.Unknown;

        var banner = document.getElementById('cdaSevBanner');
        if (banner) { banner.style.background = cfg.bg; banner.style.border = '1px solid ' + cfg.border; banner.style.color = cfg.textColor; }
        var icon = document.getElementById('cdaSevIcon');
        if (icon) icon.innerHTML = cfg.icon;
        var label = document.getElementById('cdaSevLabel');
        if (label) { label.textContent = severity; label.style.color = cfg.color; }

        _setLineBreaks(document.getElementById('cdaSummary'), data.summary);
        _setLineBreaks(document.getElementById('cdaRootCause'), data.rootCause);
        _setNumberedList(document.getElementById('cdaRecommendations'), data.recommendations);

        var riskCard = document.getElementById('cdaRiskCard');
        if (riskCard) {
            if (data.severityDesc && data.severityDesc.trim()) {
                riskCard.style.display = '';
                _setLineBreaks(document.getElementById('cdaRiskDesc'), data.severityDesc);
            } else { riskCard.style.display = 'none'; }
        }

        var atEl = document.getElementById('cdaAt');
        if (atEl) {
            var ts = result.analysedAt || data.analysedAt || Date.now();
            atEl.textContent = new Date(ts).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) + ' 분석';
        }
        var modelEl = document.getElementById('cdaModel');
        if (modelEl) { var m = result.model || data.model; modelEl.textContent = m ? 'Model: ' + m : ''; }
        var latEl = document.getElementById('cdaLatency');
        if (latEl) { var ms = result.latencyMs || data.latencyMs; latEl.textContent = ms ? '응답: ' + (ms / 1000).toFixed(1) + 's' : ''; }

        _setBadge('done');
        _show('cdaStateResult');
    }

    function _showError(code, msg) {
        var c = document.getElementById('cdaErrCode');
        if (c) { c.textContent = code || ''; c.style.display = code ? '' : 'none'; }
        var m = document.getElementById('cdaErrMsg');
        if (m) m.textContent = msg || '알 수 없는 오류가 발생했습니다.';
        _show('cdaStateError');
    }

    function _startElapsed() {
        var start = Date.now();
        var el = document.getElementById('cdaElapsed');
        _stopElapsed();
        _elapsedTimer = setInterval(function () {
            if (el) el.textContent = ((Date.now() - start) / 1000).toFixed(1) + 's 경과';
        }, 100);
    }
    function _stopElapsed() { if (_elapsedTimer) { clearInterval(_elapsedTimer); _elapsedTimer = null; } }

    function loadCoreAiInsight() {
        if (!FN || typeof CORE_HAS_RESULT === 'undefined' || !CORE_HAS_RESULT) return;
        if (!window.Common) return;
        Common.fetchJSON('/api/core-dump/' + encodeURIComponent(FN) + '/ai-insight')
            .then(function (d) { if (d && d.found) renderCoreAiResult(d, true); })
            .catch(function () { /* 인사이트 없음 — 미분석 상태 유지 */ });
    }

    function startCoreAiAnalysis() {
        if (!FN) { alert('파일명을 확인할 수 없습니다.'); return; }
        var fill = document.getElementById('cdaProgressFill');
        var msg = document.getElementById('cdaLoadingMsg');
        if (fill) fill.style.width = '15%';
        if (msg) msg.textContent = '크래시 데이터 수집 중…';
        _show('cdaStateLoading');
        _startElapsed();
        setTimeout(function () {
            if (fill) fill.style.width = '55%';
            if (msg) msg.textContent = 'LLM 분석 요청 중…';
        }, 400);

        Common.fetchJSON('/api/core-dump/' + encodeURIComponent(FN) + '/ai-analyze', { method: 'POST' })
            .then(function (d) {
                _stopElapsed();
                if (fill) fill.style.width = '100%';
                if (d && d.success) {
                    renderCoreAiResult(d, d.saved !== false);
                } else {
                    var code = (d && d.errorCode) || 'ERROR';
                    var emsg = (d && d.error) || 'AI 분석에 실패했습니다.';
                    if (code === 'LLM_DISABLED') {
                        emsg = 'AI 분석이 비활성화되어 있습니다. 설정 > LLM 에서 활성화 후 다시 시도하세요.';
                    }
                    _showError(code, emsg);
                }
            })
            .catch(function (e) {
                _stopElapsed();
                _showError('NETWORK', '서버 통신 오류: ' + (e && e.message ? e.message : e));
            });
    }

    function deleteCoreAiInsight() {
        if (!FN) return;
        if (!confirm('저장된 AI 분석 결과를 삭제하시겠습니까?')) return;
        Common.fetchJSON('/api/core-dump/' + encodeURIComponent(FN) + '/ai-insight', { method: 'DELETE' })
            .then(function (d) {
                if (d && d.success) { _setBadge('none'); _show('cdaStateEmpty'); }
                else alert('삭제에 실패했습니다.');
            })
            .catch(function (e) { alert('삭제 중 오류: ' + (e && e.message ? e.message : e)); });
    }

    // ── 초기화 ─────────────────────────────────────────────────
    document.addEventListener('DOMContentLoaded', function () {
        // Frame #0 locals 자동 확장
        var f = document.querySelector('.frame-card.frame-crash');
        if (f && f.querySelector('.frame-locals')) f.classList.add('expanded');

        // 히어로 카드 소스 자동 로드
        var heroSrc = document.getElementById('heroSource');
        var firstSrcBtn = document.querySelector('#tab-bt .frame-card.frame-crash .frame-src-btn');
        if (heroSrc && firstSrcBtn) autoLoadHeroSource(firstSrcBtn.dataset.location, heroSrc);

        // 열린 thread chevron 동기화
        document.querySelectorAll('.thread-body.open').forEach(function (el) {
            var chevron = document.getElementById('chevron-' + el.id.replace('thread-body-', ''));
            if (chevron) chevron.classList.add('open');
        });

        buildRegisters();
        loadCoreAiInsight();
    });

    // 전역 노출 (인라인 onclick 핸들러용)
    window.reanalyzeThis = reanalyzeThis;
    window.switchTab = switchTab;
    window.toggleThread = toggleThread;
    window.toggleFrame = toggleFrame;
    window.loadFrameSource = loadFrameSource;
    window.copyRaw = copyRaw;
    window.startCoreAiAnalysis = startCoreAiAnalysis;
    window.deleteCoreAiInsight = deleteCoreAiInsight;
})();
