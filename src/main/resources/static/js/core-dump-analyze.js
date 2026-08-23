/* core-dump-analyze.js — 코어 덤프 분석 결과 페이지 (analyze.html)
 * 글로벌: CORE_FILENAME, (선택) REGISTERS, CORE_HAS_RESULT
 * window.Common (banner.html 로드) 의 fetchJSON/escHtml 사용. */
(function () {
    'use strict';

    var FN = typeof CORE_FILENAME !== 'undefined' ? CORE_FILENAME : '';

    // ── 토스트 ───────────────────────────────────────────────────
    function toast(msg, type) {
        var wrap = document.getElementById('cdToastWrap');
        if (!wrap) { alert(msg); return; }
        type = type || 'info';
        var t = document.createElement('div');
        t.className = 'cd-toast rail-' + type;
        t.setAttribute('role', 'status');
        var ic = document.createElement('span');
        ic.className = 'cd-toast-icon';
        ic.textContent = type === 'success' ? '✓' : type === 'danger' ? '!' : 'ℹ';
        var m = document.createElement('span'); m.className = 'cd-toast-msg'; m.textContent = msg;
        var x = document.createElement('button'); x.className = 'cd-toast-close'; x.type = 'button'; x.textContent = '×';
        var kill = function () { if (t.parentNode) t.parentNode.removeChild(t); };
        x.addEventListener('click', kill);
        t.appendChild(ic); t.appendChild(m); t.appendChild(x);
        wrap.appendChild(t); setTimeout(kill, 4000);
    }

    // ── 재분석 ───────────────────────────────────────────────────
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
                if (d.status === 'ok') window.location.href = '/core-dump/progress/' + encodeURIComponent(filename);
                else { toast('재분석 요청 실패: ' + (d.message || '알 수 없는 오류'), 'danger'); btn.disabled = false; btn.innerHTML = '&#8635; 재분석'; }
            })
            .catch(function (e) { toast('재분석 중 오류: ' + e.message, 'danger'); btn.disabled = false; btn.innerHTML = '&#8635; 재분석'; });
    }

    // ── 분석 리비전 전환 ─────────────────────────────────────────
    // 빈 값 = 현재 결과, 그 외 = 보존된 리비전(?rev=).
    function initRevisionSelect() {
        var sel = document.getElementById('cdRevSelect');
        if (!sel) return;
        var filename = sel.dataset.filename;
        if (!filename) return;
        sel.addEventListener('change', function () {
            var base = '/core-dump/analyze/' + encodeURIComponent(filename);
            window.location.href = sel.value
                ? base + '?rev=' + encodeURIComponent(sel.value)
                : base;
        });
    }

    // ── 탭 전환 (data-tab + aria + hash) ─────────────────────────
    // 버튼 매칭은 data-tab 속성 기반 — onclick 문자열 검사(구현 취약)에서 전환.
    // 전역 switchTab(name) 시그니처는 유지(요약 스트립 "스택 트레이스 보기" 링크가 호출).
    var TAB_NAMES = ['summary', 'bt', 'threads', 'registers', 'libs', 'raw', 'report'];
    function switchTab(name) {
        if (TAB_NAMES.indexOf(name) < 0) name = 'summary';
        document.querySelectorAll('.cd-tab-panel').forEach(function (el) {
            el.classList.toggle('active', el.id === 'tab-' + name);
        });
        document.querySelectorAll('.cd-tab-btn').forEach(function (b) {
            var on = b.dataset.tab === name;
            b.classList.toggle('active', on);
            b.setAttribute('aria-selected', on ? 'true' : 'false');
        });
        if (name === 'report') initReportTab();   // 최초 1회 lazy — iframe src 미설정 시에만 로드
        if (window.history && history.replaceState && location.hash !== '#' + name) {
            history.replaceState(null, '', '#' + name);
        }
    }

    // ── 리포트 탭: PDF 미리보기 (힙덤프 analyze.js loadPdfReportPanel 이식) ──
    // 첫 진입 시 미리보기 모드('pdf' | 'html')를 결정해 iframe src 설정:
    //   모바일(≤900px) / pdfViewerEnabled=false → 'html' (/print-html)
    //   그 외 → 'pdf' (/print-pdf?mode=inline) + 4초 내 load 미발생 시 'html' 자동 전환.
    // 리비전 조회 중이면 CORE_CURRENT_REV 를 ?rev= 로 전달해 보존본 리포트를 만든다.
    var _pdfMode = null;      // 'pdf' | 'html'
    var _pdfLoadTimer = null;
    var _pdfZoom = 100;       // HTML 미리보기 줌 % (50~200)

    function corePdfUrl(mode) {
        var url = '/core-dump/analyze/' + encodeURIComponent(FN)
                + (mode === 'pdf' ? '/print-pdf?mode=inline' : '/print-html');
        if (typeof CORE_CURRENT_REV !== 'undefined' && CORE_CURRENT_REV) {
            url += (url.indexOf('?') >= 0 ? '&' : '?') + 'rev=' + encodeURIComponent(CORE_CURRENT_REV);
        }
        return url;
    }
    // HTML 모드 전용 확대/축소 — same-origin iframe 내부 .report 시트에 CSS zoom 적용
    function applyCorePdfZoom() {
        var label = document.getElementById('cdPdfZoomLabel');
        if (label) label.textContent = _pdfZoom + '%';
        var iframe = document.getElementById('cdPdfIframe');
        try {
            var rep = iframe && iframe.contentDocument && iframe.contentDocument.querySelector('.report');
            if (rep) rep.style.zoom = (_pdfZoom / 100);
        } catch (e) { /* iframe 미로드 등 — 다음 load 시 재적용 */ }
    }
    function adjustCorePdfZoom(delta) {
        _pdfZoom = Math.min(200, Math.max(50, _pdfZoom + delta));
        applyCorePdfZoom();
    }
    function resetCorePdfZoom() { _pdfZoom = 100; applyCorePdfZoom(); }

    function setCorePdfMode(mode, isAutoFallback) {
        var iframe = document.getElementById('cdPdfIframe');
        var toggle = document.getElementById('cdPdfToggleBtn');
        var notice = document.getElementById('cdPdfNotice');
        var fb     = document.getElementById('cdPdfFallback');
        var zoom   = document.getElementById('cdPdfZoomCtrl');
        if (!iframe || !FN) return;

        if (_pdfLoadTimer) { clearTimeout(_pdfLoadTimer); _pdfLoadTimer = null; }
        _pdfMode = mode;
        if (fb) fb.style.display = 'none';

        iframe.src = corePdfUrl(mode);

        if (toggle) {
            toggle.style.display = 'inline-flex';
            toggle.querySelector('span').textContent = (mode === 'pdf' ? 'HTML로 보기' : 'PDF로 보기');
        }
        if (zoom) zoom.style.display = (mode === 'html' ? 'flex' : 'none');   // PDF 모드는 뷰어 자체 줌 사용
        if (notice) notice.style.display = (isAutoFallback ? 'flex' : 'none');

        if (mode === 'pdf') {
            // PDF 인라인은 일부 브라우저에서 load 이벤트가 발생 안 함 → 4초 내 미발생 시 HTML 자동 전환.
            var loaded = false;
            var onLoad = function () { loaded = true; iframe.removeEventListener('load', onLoad); };
            iframe.addEventListener('load', onLoad);
            _pdfLoadTimer = setTimeout(function () {
                _pdfLoadTimer = null;
                if (!loaded && _pdfMode === 'pdf') setCorePdfMode('html', true);
            }, 4000);
        } else {
            var htmlLoaded = false;
            var onHtmlLoad = function () {
                htmlLoaded = true;
                iframe.removeEventListener('load', onHtmlLoad);
                applyCorePdfZoom();   // 재로드 시 현재 줌 배율 재적용
            };
            iframe.addEventListener('load', onHtmlLoad);
            setTimeout(function () { if (!htmlLoaded && fb) fb.style.display = 'flex'; }, 4000);
        }
    }
    function toggleCorePdfPreview() {
        setCorePdfMode(_pdfMode === 'pdf' ? 'html' : 'pdf', false);
    }
    function initReportTab() {
        var iframe = document.getElementById('cdPdfIframe');
        if (!iframe || !FN) return;
        if (iframe.getAttribute('src')) return;   // 이미 로드됨 (함정 6 — !iframe.src 금지)
        if (window.matchMedia('(max-width: 900px)').matches) {
            setCorePdfMode('html', false);        // 모바일은 PDF 인라인 미지원 회피 (설정 문제 아님 → 배너 없음)
            return;
        }
        // 사전 감지: 브라우저가 PDF 인라인 뷰어를 제공하지 않음 (설정/정책/미내장)
        var viewerOff = (navigator.pdfViewerEnabled === false) ||
                        (navigator.pdfViewerEnabled === undefined &&
                         navigator.mimeTypes && !navigator.mimeTypes['application/pdf']);
        setCorePdfMode(viewerOff ? 'html' : 'pdf', viewerOff);
    }

    // ── 스레드 아코디언 ──────────────────────────────────────────
    function toggleThread(id) {
        var body = document.getElementById('thread-body-' + id);
        var chevron = document.getElementById('chevron-' + id);
        var header = document.querySelector('.thread-header[data-tid="' + id + '"]');
        if (!body) return;
        var isOpen = body.classList.contains('open');
        body.classList.toggle('open', !isOpen);
        if (chevron) chevron.classList.toggle('open', !isOpen);
        if (header) header.setAttribute('aria-expanded', (!isOpen).toString());
    }

    // ── 프레임 카드 locals 토글 ──────────────────────────────────
    function toggleFrame(el) {
        if (!el.querySelector('.frame-locals')) return;
        el.classList.toggle('expanded');
    }

    function toggleGarbageFrames(btn) {
        var list = document.getElementById('btFrameList') || document.querySelector('.frame-list');
        if (!list) return;
        var hidden = list.classList.toggle('hide-garbage');
        btn.textContent = hidden ? '노이즈 프레임 표시' : '노이즈 프레임 숨김';
    }

    // ── 프레임 밀도 토글 ─────────────────────────────────────────
    function applyFrameDensity(mode) {
        document.querySelectorAll('.frame-list').forEach(function (l) {
            l.classList.toggle('density-compact', mode === 'compact');
        });
        document.querySelectorAll('#frameDensityToggle button').forEach(function (b) {
            b.classList.toggle('active', b.dataset.density === mode);
        });
    }
    function setFrameDensity(mode, btn) {
        applyFrameDensity(mode);
        try { localStorage.setItem('cdFrameDensity', mode); } catch (e) {}
    }

    // ── 소스 코드 뷰어 ───────────────────────────────────────────
    function escSrc(s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
    function renderSourceView(data) {
        var html = '<div class="src-filepath">' + escSrc(data.filePath) + '</div><div class="src-lines">';
        data.lines.forEach(function (l) {
            var cls = l.isTarget ? ' src-line-target' : '';
            html += '<div class="src-line' + cls + '"><span class="src-linenum">' + l.lineNum + '</span>'
                + '<span class="src-code">' + escSrc(l.content) + '</span></div>';
        });
        return html + '</div>';
    }
    function autoLoadHeroSource(location, container) {
        fetch('/api/core-dump/' + encodeURIComponent(FN) + '/source?location=' + encodeURIComponent(location) + '&context=6')
            .then(function (r) { return r.json(); })
            .then(function (d) { if (!d.error) container.innerHTML = '<div class="code-surface">' + renderSourceView(d) + '</div>'; })
            .catch(function () {});
    }
    function loadFrameSource(btn) {
        var location = btn.dataset.location;
        var container = document.getElementById('src-' + btn.dataset.frameidx);
        if (!container) return;
        if (container.classList.contains('visible')) { container.classList.remove('visible'); btn.innerHTML = '&#128196; 소스'; return; }
        if (container.dataset.loaded) { container.classList.add('visible'); btn.innerHTML = '&#10005; 닫기'; return; }
        container.innerHTML = '<div class="src-loading">소스 파일 로딩 중...</div>';
        container.classList.add('visible'); btn.innerHTML = '&#10005; 닫기';
        fetch('/api/core-dump/' + encodeURIComponent(FN) + '/source?location=' + encodeURIComponent(location) + '&context=8')
            .then(function (r) { return r.json(); })
            .then(function (d) {
                container.dataset.loaded = '1';
                container.innerHTML = d.error ? '<div class="src-error">&#9888; ' + escSrc(d.error) + '</div>' : renderSourceView(d);
            })
            .catch(function () { container.innerHTML = '<div class="src-error">소스 파일을 불러올 수 없습니다</div>'; });
    }

    // ── Raw 출력 복사 (성공 시 버튼 라벨 전환) ──────────────────
    function execCommandCopy(pre) {
        try {
            var sel = window.getSelection(), range = document.createRange();
            range.selectNodeContents(pre); sel.removeAllRanges(); sel.addRange(range);
            var ok = document.execCommand('copy'); sel.removeAllRanges(); return ok;
        } catch (e) { return false; }
    }
    function copyRaw() {
        var pre = document.getElementById('rawOutput');
        if (!pre) return;
        var btn = document.getElementById('copyRawBtn');
        var done = function () {
            if (btn) { btn.innerHTML = '✓ 복사됨'; setTimeout(function () { btn.innerHTML = '&#128203; 복사'; }, 2000); }
        };
        var fail = function () {
            if (execCommandCopy(pre)) done();
            else toast('복사에 실패했습니다. 텍스트를 직접 선택해 복사해 주세요.', 'danger');
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
            try { navigator.clipboard.writeText(pre.textContent).then(done, fail); } catch (e) { fail(); }
        } else fail();
    }

    // ── 레지스터 테이블 (PC/SP 상단 고정) ───────────────────────
    var PC_KEYS = ['rip', 'pc', 'eip'], SP_KEYS = ['rsp', 'sp', 'esp'];
    function buildRegisters() {
        if (typeof REGISTERS === 'undefined') return;
        var keys = Object.keys(REGISTERS);
        var pcs = [], sps = [], rest = [];
        keys.forEach(function (k) {
            var lk = k.toLowerCase();
            if (PC_KEYS.indexOf(lk) >= 0) pcs.push(k);
            else if (SP_KEYS.indexOf(lk) >= 0) sps.push(k);
            else rest.push(k);
        });
        var ordered = pcs.concat(sps).concat(rest);
        var half = Math.ceil(ordered.length / 2);
        var left = document.getElementById('regTbodyLeft');
        var right = document.getElementById('regTbodyRight');
        if (!left || !right) return;
        var esc = window.Common ? Common.escHtml : function (s) { return s; };
        ordered.forEach(function (k, i) {
            var lk = k.toLowerCase();
            var tr = document.createElement('tr');
            if (PC_KEYS.indexOf(lk) >= 0) tr.className = 'reg-pc';
            else if (SP_KEYS.indexOf(lk) >= 0) tr.className = 'reg-sp';
            tr.innerHTML = '<td>' + esc(k) + '</td><td>' + esc(REGISTERS[k]) + '</td>';
            (i < half ? left : right).appendChild(tr);
        });
    }

    // ════════════════ AI 크래시 분석 패널 ════════════════
    function _sevSvg(fill, strokeAttr) {
        return '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 16 16" style="vertical-align:middle;display:inline-block">'
            + '<circle cx="8" cy="8" r="6.5" fill="' + fill + '"' + (strokeAttr || '') + '/></svg>';
    }
    var _SEV_CONFIG = {
        Critical: { cls: 'sev-critical', color: '#DC2626', icon: _sevSvg('#DC2626') },
        High:     { cls: 'sev-high',     color: '#CA8A04', icon: _sevSvg('#EAB308') },
        Medium:   { cls: 'sev-medium',   color: '#16A34A', icon: _sevSvg('#22C55E') },
        Low:      { cls: 'sev-low',      color: '#2563EB', icon: _sevSvg('#3B82F6') },
        Unknown:  { cls: 'sev-unknown',  color: '#6B7280', icon: _sevSvg('#F3F4F6', ' stroke="#9CA3AF" stroke-width="1"') }
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
        String(text || '-').split('\n').forEach(function (p, i) {
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
        lines.forEach(function (line) { var li = document.createElement('li'); li.textContent = line; ol.appendChild(li); });
        el.appendChild(ol);
    }

    function renderCoreAiResult(result) {
        var data = result.data || result;
        var severity = data.severity || 'Unknown';
        var cfg = _SEV_CONFIG[severity] || _SEV_CONFIG.Unknown;

        var banner = document.getElementById('cdaSevBanner');
        if (banner) banner.className = 'cda-sev-banner ' + cfg.cls;
        var icon = document.getElementById('cdaSevIcon');
        if (icon) icon.innerHTML = cfg.icon;
        var label = document.getElementById('cdaSevLabel');
        if (label) label.textContent = severity;

        _setLineBreaks(document.getElementById('cdaSummary'), data.summary);
        _setLineBreaks(document.getElementById('cdaRootCause'), data.rootCause);
        _setNumberedList(document.getElementById('cdaRecommendations'), data.recommendations);

        var riskCard = document.getElementById('cdaRiskCard');
        if (riskCard) {
            if (data.severityDesc && data.severityDesc.trim()) { riskCard.style.display = ''; _setLineBreaks(document.getElementById('cdaRiskDesc'), data.severityDesc); }
            else riskCard.style.display = 'none';
        }
        var atEl = document.getElementById('cdaAt');
        if (atEl) { var ts = result.analysedAt || data.analysedAt || Date.now(); atEl.textContent = new Date(ts).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) + ' 분석'; }
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
        var start = Date.now(), el = document.getElementById('cdaElapsed');
        _stopElapsed();
        _elapsedTimer = setInterval(function () { if (el) el.textContent = ((Date.now() - start) / 1000).toFixed(1) + 's 경과'; }, 100);
    }
    function _stopElapsed() { if (_elapsedTimer) { clearInterval(_elapsedTimer); _elapsedTimer = null; } }

    function loadCoreAiInsight() {
        if (!FN || typeof CORE_HAS_RESULT === 'undefined' || !CORE_HAS_RESULT || !window.Common) return;
        Common.fetchJSON('/api/core-dump/' + encodeURIComponent(FN) + '/ai-insight')
            .then(function (d) { if (d && d.found) renderCoreAiResult(d); })
            .catch(function () {});
    }
    function startCoreAiAnalysis() {
        if (!FN) { toast('파일명을 확인할 수 없습니다.', 'danger'); return; }
        var msg = document.getElementById('cdaLoadingMsg');
        if (msg) msg.textContent = '크래시 데이터 수집 중…';
        _show('cdaStateLoading');
        _startElapsed();
        setTimeout(function () { if (msg) msg.textContent = 'LLM 분석 요청 중…'; }, 400);
        Common.fetchJSON('/api/core-dump/' + encodeURIComponent(FN) + '/ai-analyze', { method: 'POST' })
            .then(function (d) {
                _stopElapsed();
                if (d && d.success) renderCoreAiResult(d);
                else {
                    var code = (d && d.errorCode) || 'ERROR';
                    var emsg = (d && d.error) || 'AI 분석에 실패했습니다.';
                    if (code === 'LLM_DISABLED') emsg = 'AI 분석이 비활성화되어 있습니다. 설정 > LLM 에서 활성화 후 다시 시도하세요.';
                    _showError(code, emsg);
                }
            })
            .catch(function (e) { _stopElapsed(); _showError('NETWORK', '서버 통신 오류: ' + (e && e.message ? e.message : e)); });
    }
    function deleteCoreAiInsight() {
        if (!FN) return;
        if (!confirm('저장된 AI 분석 결과를 삭제하시겠습니까?')) return;
        Common.fetchJSON('/api/core-dump/' + encodeURIComponent(FN) + '/ai-insight', { method: 'DELETE' })
            .then(function (d) { if (d && d.success) { _setBadge('none'); _show('cdaStateEmpty'); } else toast('삭제에 실패했습니다.', 'danger'); })
            .catch(function (e) { toast('삭제 중 오류: ' + (e && e.message ? e.message : e), 'danger'); });
    }

    // ════════════════ 라이브러리 번들 (sysroot) 모달 ════════════════
    // 별도 서버 분석 심볼 정확도 복원 — 원격 수집(collect-libs) / tar.gz 업로드 / 삭제.
    // 에러 응답 body(code/message) 검사가 필요해 Common.fetchJSON 미사용 (함정 14).
    function csrfHeaders(extra) {
        var h = extra || {};
        var meta = document.querySelector('meta[name="_csrf"]');
        var metaH = document.querySelector('meta[name="_csrf_header"]');
        if (meta && metaH) h[metaH.content] = meta.content;
        return h;
    }
    function fmtBytes(b) {
        return (window.Common && Common.formatBytes) ? Common.formatBytes(b) : b + ' B';
    }
    function _libsErr(msg) {
        var el = document.getElementById('libsModalErr');
        if (!el) return;
        el.textContent = msg || '';
        el.classList.toggle('visible', !!msg);
    }
    function _libsResult(msg) {
        var el = document.getElementById('libsModalResult');
        if (!el) return;
        el.textContent = msg || '';
        el.style.display = msg ? '' : 'none';
    }
    function _renderLibsStatus(d) {
        var st = document.getElementById('libsModalStatus');
        var collectBtn = document.getElementById('libsCollectBtn');
        var delBtn = document.getElementById('libsDeleteBtn');
        if (st) {
            if (d.present) {
                st.innerHTML = '<span class="libs-status-on">&#10003; 번들 있음</span> — '
                    + '<span class="mono">' + d.fileCount + '개 파일 · ' + fmtBytes(d.totalBytes) + '</span>'
                    + '<br>재분석하면 이 번들이 sysroot 로 적용됩니다.';
            } else {
                st.innerHTML = '<span class="libs-status-off">번들 없음</span> — 현재 분석은 분석 서버 로컬 라이브러리로 심볼을 해석합니다.';
            }
            if (d.collectable && d.originServerName) {
                st.innerHTML += '<br>출처 서버: <span class="mono">'
                    + (window.Common ? Common.escHtml(d.originServerName) : d.originServerName) + '</span> (전송 이력 기반)';
            } else {
                st.innerHTML += '<br>출처 서버를 전송 이력에서 찾을 수 없어 원격 수집은 불가합니다 — tar.gz 수동 업로드를 사용하세요.';
            }
        }
        if (collectBtn) collectBtn.style.display = d.collectable ? '' : 'none';
        if (delBtn) delBtn.style.display = d.present ? '' : 'none';
    }
    function refreshLibsStatus() {
        return fetch('/api/core-dump/' + encodeURIComponent(FN) + '/libs', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (d) { if (d.status === 'ok') _renderLibsStatus(d); return d; });
    }
    function openLibsModal() {
        _libsErr(''); _libsResult('');
        var re = document.getElementById('libsReanalyzeBtn');
        if (re) re.style.display = 'none';
        var m = document.getElementById('libsModal');
        if (m) m.classList.add('open');
        refreshLibsStatus().catch(function () {
            var st = document.getElementById('libsModalStatus');
            if (st) st.textContent = '상태를 불러오지 못했습니다.';
        });
    }
    function closeLibsModal() {
        var m = document.getElementById('libsModal');
        if (m) m.classList.remove('open');
    }
    function _libsSuccess(summary) {
        _libsResult(summary + ' 재분석하면 번들이 적용됩니다.');
        var re = document.getElementById('libsReanalyzeBtn');
        if (re) re.style.display = '';
        refreshLibsStatus().catch(function () {});
    }
    function collectLibs(btn) {
        _libsErr(''); _libsResult('');
        btn.disabled = true;
        var orig = btn.innerHTML;
        btn.textContent = '수집 중… (수 분 소요될 수 있음)';
        fetch('/api/core-dump/' + encodeURIComponent(FN) + '/collect-libs',
              { method: 'POST', headers: csrfHeaders(), credentials: 'same-origin' })
            .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, d: d }; }); })
            .then(function (res) {
                btn.disabled = false; btn.innerHTML = orig;
                var d = res.d || {};
                if (res.ok && d.status === 'ok') {
                    var msg = d.serverName + ' 에서 ' + d.collected + '/' + d.requested
                        + '개 수집 (' + fmtBytes(d.totalBytes) + ').';
                    if (d.missing && d.missing.length) {
                        msg += ' 누락 ' + d.missing.length + '건: ' + d.missing.slice(0, 3).join(', ')
                            + (d.missing.length > 3 ? ' 외' : '');
                    }
                    _libsSuccess(msg);
                } else {
                    _libsErr((d.message || '라이브러리 수집에 실패했습니다.') + (d.code ? ' [' + d.code + ']' : ''));
                }
            })
            .catch(function (e) {
                btn.disabled = false; btn.innerHTML = orig;
                _libsErr('수집 중 오류: ' + (e && e.message ? e.message : e));
            });
    }
    // 형식 제약 없음 — 아카이브(tar/tar.gz/tgz/tar.bz2/tar.xz/zip)와 개별 .so 파일 모두,
    // 여러 개 동시 선택 가능. 서버가 매직 바이트로 판정한다.
    function uploadLibsBundle(input) {
        var files = input.files ? Array.prototype.slice.call(input.files) : [];
        input.value = '';
        if (!files.length) return;
        _libsErr(''); _libsResult('');
        var label = input.closest('label');
        var origLabel = label ? label.innerHTML : null;
        if (label) label.textContent = '업로드 중… (' + files.length + '개)';
        var fd = new FormData();
        files.forEach(function (f) { fd.append('bundleFile', f); });
        // multipart — Content-Type 은 브라우저가 boundary 포함으로 설정 (수동 지정 금지)
        fetch('/api/core-dump/' + encodeURIComponent(FN) + '/libs',
              { method: 'POST', headers: csrfHeaders(), credentials: 'same-origin', body: fd })
            .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, d: d }; }); })
            .then(function (res) {
                if (label && origLabel) label.innerHTML = origLabel;
                var d = res.d || {};
                if (res.ok && d.status === 'ok') {
                    var kinds = [];
                    if (d.archives > 0) kinds.push('아카이브 ' + d.archives + '건');
                    if (d.singles > 0) kinds.push('개별 파일 ' + d.singles + '건');
                    var msg = '업로드 완료' + (kinds.length ? ' (' + kinds.join(' · ') + ')' : '')
                        + ' — 번들에 ' + d.extracted + '개 파일 반영 (' + fmtBytes(d.totalBytes) + ').';
                    if (d.skippedLinks > 0) msg += ' 링크 엔트리 ' + d.skippedLinks + '건은 건너뜀.';
                    _libsSuccess(msg);
                } else {
                    _libsErr(d.message || '번들 업로드에 실패했습니다.');
                }
            })
            .catch(function (e) {
                if (label && origLabel) label.innerHTML = origLabel;
                _libsErr('업로드 중 오류: ' + (e && e.message ? e.message : e));
            });
    }
    function deleteLibsBundle(btn) {
        if (!confirm('라이브러리 번들을 삭제하시겠습니까?\n삭제 후 재분석하면 분석 서버 로컬 라이브러리로 되돌아갑니다.')) return;
        _libsErr(''); _libsResult('');
        btn.disabled = true;
        fetch('/api/core-dump/' + encodeURIComponent(FN) + '/libs',
              { method: 'DELETE', headers: csrfHeaders(), credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                btn.disabled = false;
                if (d.status === 'ok') { _libsResult('번들을 삭제했습니다.'); refreshLibsStatus(); }
                else _libsErr(d.message || '삭제에 실패했습니다.');
            })
            .catch(function (e) { btn.disabled = false; _libsErr('삭제 중 오류: ' + (e && e.message ? e.message : e)); });
    }

    // ── 초기화 ───────────────────────────────────────────────────
    document.addEventListener('DOMContentLoaded', function () {
        initRevisionSelect();

        // 프레임 밀도 복원
        var savedDensity = 'default';
        try { savedDensity = localStorage.getItem('cdFrameDensity') || 'default'; } catch (e) {}
        if (savedDensity === 'compact') applyFrameDensity('compact');

        // Frame #0 locals 자동 확장
        var f = document.querySelector('.frame-card.frame-crash');
        if (f && f.querySelector('.frame-locals')) f.classList.add('expanded');

        // 히어로 소스 자동 로드
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

        // 탭 버튼 클릭 (data-tab) + 뒤로가기/앞으로가기 해시 복원
        // (switchTab 의 replaceState 는 hashchange 를 발화하지 않으므로 루프 없음)
        document.querySelectorAll('.cd-tab-btn').forEach(function (b) {
            b.addEventListener('click', function () { switchTab(this.dataset.tab); });
        });
        window.addEventListener('hashchange', function () {
            var h = (location.hash || '').replace('#', '');
            if (TAB_NAMES.indexOf(h) >= 0) switchTab(h);
        });

        // 해시 딥링크 탭 복원 (없으면 마크업 기본 = 요약)
        var hash = (location.hash || '').replace('#', '');
        if (TAB_NAMES.indexOf(hash) >= 0) switchTab(hash);
    });

    window.reanalyzeThis = reanalyzeThis;
    window.switchTab = switchTab;
    window.adjustCorePdfZoom = adjustCorePdfZoom;
    window.resetCorePdfZoom = resetCorePdfZoom;
    window.toggleCorePdfPreview = toggleCorePdfPreview;
    window.toggleThread = toggleThread;
    window.toggleFrame = toggleFrame;
    window.toggleGarbageFrames = toggleGarbageFrames;
    window.setFrameDensity = setFrameDensity;
    window.loadFrameSource = loadFrameSource;
    window.copyRaw = copyRaw;
    window.startCoreAiAnalysis = startCoreAiAnalysis;
    window.deleteCoreAiInsight = deleteCoreAiInsight;
    window.openLibsModal = openLibsModal;
    window.closeLibsModal = closeLibsModal;
    window.collectLibs = collectLibs;
    window.uploadLibsBundle = uploadLibsBundle;
    window.deleteLibsBundle = deleteLibsBundle;
})();
