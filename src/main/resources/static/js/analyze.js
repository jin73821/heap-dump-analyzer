// ── 사이드바 nav-item 위임 핸들러 ──────────────────────
// 인라인 onclick 대신 data-panel/data-action 속성 + 위임 리스너 사용.
// 원본 + 배너 클론 양쪽 모두 단일 핸들러로 처리, 부모 페이지 이탈 가능성 차단.
document.addEventListener('click', function(e) {
    var btn = e.target.closest('.nav-item[data-panel], .nav-item[data-action]');
    if (!btn) return;
    e.preventDefault();
    e.stopPropagation();
    var panel = btn.getAttribute('data-panel');
    var action = btn.getAttribute('data-action');
    var extra = btn.getAttribute('data-extra');
    try {
        if (panel) showPanel(panel, btn);
        if (extra && typeof window[extra] === 'function') window[extra]();
        if (action && typeof window[action] === 'function') window[action]();
    } catch (err) {
        console.error('[nav-item handler]', err);
    }
    return false;
}, false);

// ── 배너 Analysis 탭 등록 ──────────────────────────────
(function() {
    var sidebar = document.getElementById('mobileSidebar');
    if (sidebar && typeof registerBannerAnalysisTab === 'function') {
        // 사이드바 내용 전체를 배너 Analysis 탭에 복제
        var wrapper = document.createElement('div');
        wrapper.style.cssText = 'padding:8px 12px;display:flex;flex-direction:column;gap:4px;overflow-y:auto;flex:1';
        // 각 sidebar-section을 복제 (위임 핸들러가 클론 버튼도 자동 처리)
        var sections = sidebar.querySelectorAll('.sidebar-section');
        sections.forEach(function(sec) {
            var clone = sec.cloneNode(true);
            wrapper.appendChild(clone);
        });
        registerBannerAnalysisTab(wrapper);
    }
})();

// ── PDF Report panel lazy-load ────────────────────────
// 첫 진입 시 미리보기 모드('pdf' | 'html')를 결정해 iframe src 설정:
//   모바일(≤900px)                    → 'html' (/print-html — PDF 인라인 미지원 회피)
//   navigator.pdfViewerEnabled=false  → 'html' + 안내 배너 (Chrome "PDF 다운로드" 설정 등.
//                                        PDF 시도 자체를 생략해 의도치 않은 자동 다운로드 방지)
//   구형 브라우저 + mimeTypes에 pdf 無 → 'html' + 안내 배너
//   그 외                             → 'pdf' (/print-pdf?mode=inline) + 4초 사후 감지:
//                                        load 미발생 시 'html' 자동 전환 + 안내 배너
// 토글 버튼으로 PDF↔HTML 수동 전환 가능. 다운로드 버튼 download 속성도 첫 진입 시 설정.
var _pdfPreviewMode = null;   // 'pdf' | 'html'
var _pdfLoadTimer = null;
var _pdfHtmlZoom = 100;       // HTML 미리보기 줌 % (50~200)

// HTML 모드 전용 확대/축소 — same-origin iframe 내부 .report 시트에 CSS zoom 적용
// (transform: scale 과 달리 문서 흐름이 함께 리플로우되어 중앙 정렬·높이가 유지됨)
function applyPdfHtmlZoom() {
    var label = document.getElementById('pdfZoomLabel');
    if (label) label.textContent = _pdfHtmlZoom + '%';
    var iframe = document.getElementById('pdfReportIframe');
    try {
        var rep = iframe && iframe.contentDocument &&
                  iframe.contentDocument.querySelector('.report');
        if (rep) rep.style.zoom = (_pdfHtmlZoom / 100);
    } catch (e) { /* iframe 미로드 등 — 다음 load 시 재적용 */ }
}
function adjustPdfHtmlZoom(delta) {
    _pdfHtmlZoom = Math.min(200, Math.max(50, _pdfHtmlZoom + delta));
    applyPdfHtmlZoom();
}
function resetPdfHtmlZoom() {
    _pdfHtmlZoom = 100;
    applyPdfHtmlZoom();
}

function setPdfPreviewMode(mode, isAutoFallback) {
    var iframe = document.getElementById('pdfReportIframe');
    var toggle = document.getElementById('pdfPreviewToggleBtn');
    var notice = document.getElementById('pdfReportNotice');
    var fb     = document.getElementById('pdfReportFallback');
    var zoom   = document.getElementById('pdfZoomCtrl');
    if (!iframe || typeof FILENAME === 'undefined' || !FILENAME) return;

    if (_pdfLoadTimer) { clearTimeout(_pdfLoadTimer); _pdfLoadTimer = null; }
    _pdfPreviewMode = mode;
    if (fb) fb.style.display = 'none';

    iframe.src = '/analyze/' + encodeURIComponent(FILENAME) +
                 (mode === 'pdf' ? '/print-pdf?mode=inline' : '/print-html');

    if (toggle) {
        toggle.style.display = 'inline-flex';
        toggle.querySelector('span').textContent = (mode === 'pdf' ? 'HTML로 보기' : 'PDF로 보기');
    }
    if (zoom) zoom.style.display = (mode === 'html' ? 'flex' : 'none');   // PDF 모드는 뷰어 자체 줌 사용
    if (notice) notice.style.display = (isAutoFallback ? 'flex' : 'none');

    if (mode === 'pdf') {
        // PDF 인라인은 일부 브라우저에서 load 이벤트가 발생 안 함 → 4초 내 미발생 시 HTML 자동 전환.
        var loaded = false;
        var onLoad = function() { loaded = true; iframe.removeEventListener('load', onLoad); };
        iframe.addEventListener('load', onLoad);
        _pdfLoadTimer = setTimeout(function() {
            _pdfLoadTimer = null;
            if (!loaded && _pdfPreviewMode === 'pdf') setPdfPreviewMode('html', true);
        }, 4000);
    } else {
        // HTML 모드조차 load 미발생하는 극단 케이스 → 기존 텍스트 오버레이 최후 폴백.
        var htmlLoaded = false;
        var onHtmlLoad = function() {
            htmlLoaded = true;
            iframe.removeEventListener('load', onHtmlLoad);
            applyPdfHtmlZoom();   // 재로드 시 현재 줌 배율 재적용
        };
        iframe.addEventListener('load', onHtmlLoad);
        setTimeout(function() { if (!htmlLoaded && fb) fb.style.display = 'flex'; }, 4000);
    }
}

function togglePdfPreview() {
    setPdfPreviewMode(_pdfPreviewMode === 'pdf' ? 'html' : 'pdf', false);
}

function loadPdfReportPanel() {
    var iframe = document.getElementById('pdfReportIframe');
    var btn    = document.getElementById('pdfReportDownloadBtn');
    if (!iframe || typeof FILENAME === 'undefined' || !FILENAME) return;

    if (btn && !btn.getAttribute('download')) {
        var base = FILENAME.replace(/\.(hprof|bin|dump)(\.gz)?$/i, '');
        btn.setAttribute('download', base + '-report.pdf');
    }
    if (iframe.getAttribute('src')) return;   // 이미 로드됨

    var isMobile = window.matchMedia('(max-width: 900px)').matches;
    if (isMobile) {
        setPdfPreviewMode('html', false);       // 현행 유지 (설정 문제 아님 → 배너 없음)
        return;
    }
    // 사전 감지: 브라우저가 PDF 인라인 뷰어를 제공하지 않음 (설정/정책/미내장)
    var viewerOff = (navigator.pdfViewerEnabled === false) ||
                    (navigator.pdfViewerEnabled === undefined &&
                     navigator.mimeTypes && !navigator.mimeTypes['application/pdf']);
    setPdfPreviewMode(viewerOff ? 'html' : 'pdf', viewerOff);
}

// ── Panel Navigation ──────────────────────────────────
/* ── Mobile sidebar toggle ─────────────────────── */
function toggleMobileSidebar() {
    var sb = document.getElementById('mobileSidebar');
    var ov = document.getElementById('sidebarOverlay');
    var opening = !sb.classList.contains('mobile-open');
    sb.classList.toggle('mobile-open');
    ov.classList.toggle('open');
}
function closeMobileSidebar() {
    var sb = document.getElementById('mobileSidebar');
    var ov = document.getElementById('sidebarOverlay');
    if (sb) sb.classList.remove('mobile-open');
    if (ov) ov.classList.remove('open');
    // 배너도 닫기
    if (typeof closeMobileBanner === 'function') closeMobileBanner();
}

var _threadStacksLoaded = false;

function showPanel(name, btn) {
    document.querySelectorAll('.panel').forEach(function(p) { p.classList.remove('active'); });
    document.querySelectorAll('.nav-item').forEach(function(n) { n.classList.remove('active'); });
    var panel = document.getElementById('panel-' + name);
    if (panel) panel.classList.add('active');
    if (btn) btn.classList.add('active');
    closeMobileSidebar();

    if (name === 'thread-stacks' && !_threadStacksLoaded) {
        _threadStacksLoaded = true;
        loadThreadStacks();
    }

    // PDF Report 패널: 첫 진입 시 src 설정 + 다운로드 파일명 갱신
    if (name === 'pdf-report') {
        loadPdfReportPanel();
    }

    // Dominator Tree 패널: 진입 시 인라인 바 차트 렌더 (transition 애니메이션 트리거)
    if (name === 'dominator-tree') {
        renderDomBars();
    }

    // iframe lazy-load (Overview, Top Components, Suspects)
    var iframeMap = {
        'mat-overview': 'matOverviewIframe',
        'mat-top': 'matTopIframe',
        'mat-suspects': 'matSuspectsIframe'
    };
    if (iframeMap[name]) {
        var iframe = document.getElementById(iframeMap[name]);
        if (iframe && !iframe.getAttribute('src')) {
            // load 핸들러는 한 번만 등록 — 내부 네비게이션 시마다 자동 재실행됨.
            // 동적 height 조정은 의도적으로 제거: iframe이 콘텐츠 전체 높이로 자라
            // 페이지가 거대 단일 스크롤이 되며 시각적으로 "전체 화면 전환"처럼 보이는 문제 방지.
            // 고정 75vh + iframe 내부 스크롤 사용.
            iframe.addEventListener('load', function() {
                try {
                    var doc = iframe.contentDocument || iframe.contentWindow.document;
                    activateClassLinksInIframe(doc);
                } catch(e) {}
            });
            iframe.src = iframe.getAttribute('data-src');
        }
    }
}

// ── iframe 내 클래스명 링크 활성화 ──────────────────────
// load 리스너는 호출자(showPanel)가 한 번만 등록하므로, 여기서는 추가 등록하지 않는다.
function activateClassLinksInIframe(doc) {
    // FQCN 패턴: 패키지.클래스명 (최소 2단계 이상)
    var FQCN_RE = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*\.[A-Z][\w$]*$/;
    var links = doc.querySelectorAll('a[href="javascript:void(0)"]');
    for (var i = 0; i < links.length; i++) {
        var link = links[i];
        if (link._classLinkActivated) continue;
        var text = (link.textContent || '').trim();
        if (!FQCN_RE.test(text)) continue;
        link._classLinkActivated = true;
        link.style.opacity = '1';
        link.style.cursor = 'pointer';
        link.style.color = '#2563eb';
        link.style.textDecoration = 'underline';
        link.title = text + ' 상세 보기';
        (function(className) {
            link.addEventListener('click', function(e) {
                e.preventDefault();
                e.stopPropagation();
                window.parent.showComponentDetail(className);
            });
        })(text);
    }
}

// ── Suspect Toggle ────────────────────────────────────
function toggleSuspect(header) {
    var item = header.parentElement;
    item.classList.toggle('open');
}

function openStacktraceModal(url, title) {
    var modal = document.getElementById('stacktraceModal');
    var titleEl = document.getElementById('stacktraceModalTitle');
    var bodyEl = document.getElementById('stacktraceModalBody');
    if (!modal || !bodyEl) return;
    titleEl.textContent = title || 'Thread Stack';
    bodyEl.innerHTML = '<div style="text-align:center;color:#9CA3AF;padding:40px 0">로딩 중...</div>';
    modal.classList.add('open');
    fetch(url)
        .then(function(r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.text();
        })
        .then(function(html) {
            bodyEl.innerHTML = html;
        })
        .catch(function(e) {
            bodyEl.innerHTML = '<div style="text-align:center;color:#EF4444;padding:40px 0">로드 실패: ' + Common.escHtml(e.message) + '</div>';
        });
}

function closeStacktraceModal() {
    var modal = document.getElementById('stacktraceModal');
    if (modal) modal.classList.remove('open');
}

// ── Table Filter ──────────────────────────────────────
function filterTable() {
    var q = document.getElementById('objSearch').value.toLowerCase();
    var rows = document.querySelectorAll('#topObjectsTable tbody tr');
    rows.forEach(function(row) {
        var name = row.cells[1].textContent.toLowerCase();
        row.style.display = name.indexOf(q) >= 0 ? '' : 'none';
    });
}

// ── Histogram Filter ──────────────────────────────────
function filterHistogram() {
    var q = document.getElementById('histSearch').value.toLowerCase();
    document.querySelectorAll('#histogramTable tbody tr').forEach(function(r) {
        r.style.display = r.textContent.toLowerCase().indexOf(q) >= 0 ? '' : 'none';
    });
}

// ── Dominator Tree inline bar chart ───────────────────────────────────
// 각 컬럼(shallow/retained)의 최대값을 100%로 잡아 막대 너비를 산정.
// idempotent — 패널 진입 시마다 호출 가능.
function renderDomBars() {
    var fills = document.querySelectorAll('#domTreeTable .dom-bar-fill');
    if (!fills.length) return;
    var maxShallow = 0, maxRetained = 0;
    fills.forEach(function(el) {
        var v = parseFloat(el.getAttribute('data-val')) || 0;
        if (el.classList.contains('dom-bar-retained')) {
            if (v > maxRetained) maxRetained = v;
        } else {
            if (v > maxShallow) maxShallow = v;
        }
    });
    fills.forEach(function(el) {
        var v = parseFloat(el.getAttribute('data-val')) || 0;
        var max = el.classList.contains('dom-bar-retained') ? maxRetained : maxShallow;
        var pct = max > 0 ? (v / max) * 100 : 0;
        // 0이 아닌 값은 최소 2% 보장 (시각적 가시성)
        if (v > 0 && pct < 2) pct = 2;
        el.style.width = pct + '%';
    });
}

// ── Dominator Tree Filter + Disclosure (lazy on-demand) ───────────────
function filterDomTree() {
    var q = document.getElementById('domTreeSearch').value.toLowerCase();
    _closeDomDetail();
    document.querySelectorAll('#domTreeTable tbody tr.dom-row').forEach(function(r) {
        r.style.display = r.textContent.toLowerCase().indexOf(q) >= 0 ? '' : 'none';
    });
}

var _domDetailRow = null;
var _domOpenAddr  = null;
var _domCache     = {};   // addr → { incoming, outgoing }

function _closeDomDetail() {
    if (_domDetailRow && _domDetailRow.parentNode) {
        var owner = _domDetailRow.previousElementSibling;
        _domDetailRow.parentNode.removeChild(_domDetailRow);
        if (owner) owner.classList.remove('dom-open');
    }
    _domDetailRow = null;
    _domOpenAddr  = null;
}

function _renderDomRefsTable(refs, emptyMsg) {
    if (!refs || refs.length === 0) {
        return '<div class="dom-refs-empty">' + (emptyMsg || '참조 데이터 없음') + '</div>';
    }
    var rows = '';
    for (var i = 0; i < refs.length; i++) {
        var r = refs[i];
        var cls  = _escapeHtml(r.className || '-');
        var addr = _escapeHtml(r.objectAddress || '');
        var sh   = _escapeHtml(r.shallowHeapHuman || '');
        var rh   = _escapeHtml(r.retainedHeapHuman || '');
        rows += '<tr>'
              + '<td>' + cls + (addr ? ' <code>@ ' + addr + '</code>' : '') + '</td>'
              + '<td class="dom-refs-num">' + sh + '</td>'
              + '<td class="dom-refs-num">' + rh + '</td>'
              + '</tr>';
    }
    return '<table class="dom-refs-tbl">'
         + '<thead><tr><th>Class @ Address</th><th style="width:90px;text-align:right">Shallow</th><th style="width:100px;text-align:right">Retained</th></tr></thead>'
         + '<tbody>' + rows + '</tbody>'
         + '</table>';
}

function _escapeHtml(s) {
    if (s == null) return '';
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function _buildDomDetailHtml(data, loading, err) {
    if (loading) {
        return '<div class="dom-refs-loading">'
             + '<span class="dom-spinner"></span> '
             + '인바운드/아웃바운드 참조 추출 중... (MAT 쿼리 ~5-10초)'
             + '</div>';
    }
    if (err) {
        return '<div class="dom-refs-error">' + _escapeHtml(err) + '</div>';
    }
    var inHtml  = _renderDomRefsTable(data ? data.incoming : null, '인바운드 참조 없음 (GC root 경로)');
    var outHtml = _renderDomRefsTable(data ? data.outgoing : null, '아웃바운드 참조 없음 (retained set)');
    return ''
        + '<div class="dom-refs-wrap">'
        +   '<div class="dom-refs-section">'
        +     '<h4>&#8592; Incoming (Path to GC Roots — 이 객체를 살아있게 하는 경로)</h4>'
        +     inHtml
        +   '</div>'
        +   '<div class="dom-refs-section">'
        +     '<h4>&#8594; Outgoing (Retained Set — 이 객체가 살아있게 하는 객체)</h4>'
        +     outHtml
        +   '</div>'
        + '</div>';
}

function domRowClick(row) {
    if (!row || !row.dataset) return;
    var idx = parseInt(row.dataset.idx, 10);
    if (idx < 50) {
        toggleDomDetail(row);
    } else {
        showComponentDetail(row.dataset['class']);
    }
}

function toggleDomDetail(row) {
    if (!row || !row.dataset) return;
    var addr = row.dataset.address;
    if (!addr) return;
    if (_domOpenAddr === addr) { _closeDomDetail(); return; }
    _closeDomDetail();

    var detail = document.createElement('tr');
    detail.className = 'dom-detail-row';
    var td = document.createElement('td');
    td.colSpan = 6;
    detail.appendChild(td);
    row.parentNode.insertBefore(detail, row.nextSibling);
    row.classList.add('dom-open');
    _domDetailRow = detail;
    _domOpenAddr  = addr;

    if (_domCache[addr]) {
        td.innerHTML = _buildDomDetailHtml(_domCache[addr], false, null);
        return;
    }

    td.innerHTML = _buildDomDetailHtml(null, true, null);
    var url = '/api/dominator-refs/' + encodeURIComponent(FILENAME)
            + '?address=' + encodeURIComponent(addr);
    fetch(url, { credentials: 'same-origin' })
        .then(function(r) {
            return r.json().then(function(j) { return { ok: r.ok, body: j }; });
        })
        .then(function(res) {
            if (_domOpenAddr !== addr) return;  // 이미 다른 행으로 이동
            if (res.ok) {
                _domCache[addr] = res.body;
                td.innerHTML = _buildDomDetailHtml(res.body, false, null);
            } else {
                td.innerHTML = _buildDomDetailHtml(null, false, (res.body && res.body.error) || '요청 실패');
            }
        })
        .catch(function(e) {
            if (_domOpenAddr !== addr) return;
            td.innerHTML = _buildDomDetailHtml(null, false, '네트워크 오류: ' + e.message);
        });
}

// ── OOM 배너 → 행 스크롤 + flash ─────────────────────
function scrollToOomThread(idx) {
    var row = document.querySelector('tr.thread-row[data-idx="' + idx + '"]');
    if (!row) return;
    row.scrollIntoView({ block: 'center', behavior: 'smooth' });
    row.classList.add('oom-flash');
    setTimeout(function() { row.classList.remove('oom-flash'); }, 1200);
}

// Overview 배너 → Thread Overview 패널 전환 + 해당 행 스크롤
function goToOomThread(idx) {
    var navBtn = document.querySelector('.nav-item[data-panel="threads"]');
    showPanel('threads', navBtn);
    // 패널 활성화 후 layout 완료를 보장 (즉시 scrollIntoView 호출 시 비활성 패널 좌표 위험)
    requestAnimationFrame(function() {
        scrollToOomThread(idx);
    });
}

// Overview Leak 요약 카드 "전체 보기" → Leak Suspects 패널 전환
function goToSuspects() {
    var navBtn = document.querySelector('.nav-item[data-panel="suspects"]');
    showPanel('suspects', navBtn);
}

// Overview AI 인사이트 요약 카드 → AI 인사이트 패널 전환
function goToAiInsight() {
    var navBtn = document.querySelector('.nav-item[data-panel="ai-insight"]');
    showPanel('ai-insight', navBtn);
}

// recommendations 문자열에서 첫 항목만 추출 (번호 "1. .. 2. .." / 줄바꿈 / 단일 문장 모두 처리).
// 번호 정규식은 setNumberedList 와 동일 패턴 유지.
function firstRecommendation(text) {
    if (!text || !String(text).trim()) return '';
    var normalized = String(text).replace(/(\d+)\s*[.)]\s*/g, '\n$1. ').trim();
    var lines = normalized.split('\n').map(function(l) { return l.trim(); }).filter(function(l) { return l.length; });
    for (var i = 0; i < lines.length; i++) {
        var m = lines[i].match(/^\d+\.\s*(.*)/);
        if (m) return m[1].trim();
    }
    return lines[0] || String(text).trim();
}

// Overview 의 AI 인사이트 요약 카드 갱신 (showAiResult/auto-load 에서 호출).
// data 가 없거나 summary 가 비면 미생성 상태로 되돌린다.
function renderOverviewInsight(data) {
    var empty = document.getElementById('ovAiInsightEmpty');
    var body  = document.getElementById('ovAiInsightBody');
    var sumEl = document.getElementById('ovAiSummary');
    var sevEl = document.getElementById('ovAiSeverity');
    var rcRow  = document.getElementById('ovAiRootCauseRow');
    var rcVal  = document.getElementById('ovAiRootCause');
    var rcoRow = document.getElementById('ovAiRecoRow');
    var rcoVal = document.getElementById('ovAiReco');
    if (!empty || !body) return;

    var summary = (data && data.summary != null) ? String(data.summary) : '';
    if (summary.trim()) {
        if (sumEl) sumEl.textContent = summary;
        // 보강 행: 근본 원인 + 첫 번째 권장 조치 (없으면 행 숨김)
        var rc = (data && data.rootCause != null) ? String(data.rootCause).trim() : '';
        if (rcRow && rcVal) {
            if (rc) { rcVal.textContent = rc; rcRow.style.display = ''; }
            else rcRow.style.display = 'none';
        }
        var reco = firstRecommendation(data && data.recommendations);
        if (rcoRow && rcoVal) {
            if (reco) { rcoVal.textContent = reco; rcoRow.style.display = ''; }
            else rcoRow.style.display = 'none';
        }
        if (sevEl) {
            var sev = data.severity;
            if (sev) {
                var cfg = (typeof _SEV_CONFIG !== 'undefined') ? (_SEV_CONFIG[sev] || _SEV_CONFIG.Unknown) : null;
                sevEl.textContent = sev;
                sevEl.style.display = '';
                if (cfg) { sevEl.style.color = cfg.color; sevEl.style.background = cfg.bg; sevEl.style.borderColor = cfg.border; }
            } else {
                sevEl.style.display = 'none';
            }
        }
        empty.style.display = 'none';
        body.style.display = '';
    } else {
        empty.style.display = '';
        body.style.display = 'none';
        if (sevEl) sevEl.style.display = 'none';
        if (rcRow)  rcRow.style.display  = 'none';
        if (rcoRow) rcoRow.style.display = 'none';
    }
}

// ── Thread Filter ─────────────────────────────────────
function filterThreads() {
    var q = document.getElementById('threadSearch').value.toLowerCase();
    // 열린 상세 행 닫기
    _closeThreadDetail();
    document.querySelectorAll('#threadTable tbody tr.thread-row').forEach(function(r) {
        r.style.display = r.textContent.toLowerCase().indexOf(q) >= 0 ? '' : 'none';
    });
}

var _threadDetailRow = null;  // 공유 상세 행 (1개만 존재)
var _threadOpenIdx = -1;      // 현재 열린 스레드 인덱스

function _closeThreadDetail() {
    if (_threadDetailRow && _threadDetailRow.parentNode) {
        _threadDetailRow.parentNode.removeChild(_threadDetailRow);
    }
    if (_threadOpenIdx >= 0) {
        var prevRow = document.querySelector('#threadTable tr.thread-row[data-idx="' + _threadOpenIdx + '"]');
        if (prevRow) {
            prevRow.style.background = '';
            var ch = prevRow.querySelector('.thread-chevron');
            if (ch) ch.style.transform = '';
        }
    }
    _threadOpenIdx = -1;
}

function toggleThreadDetail(row) {
    var idx = parseInt(row.dataset.idx, 10);

    // 같은 행 클릭 → 닫기
    if (_threadOpenIdx === idx) {
        _closeThreadDetail();
        return;
    }

    // 기존 열린 것 닫기
    _closeThreadDetail();

    // 상세 행 생성 (한 번만)
    if (!_threadDetailRow) {
        _threadDetailRow = document.createElement('tr');
        _threadDetailRow.className = 'thread-detail';
        var td = document.createElement('td');
        td.colSpan = 6;
        td.style.cssText = 'padding:0;border-bottom:2px solid var(--primary)';
        _threadDetailRow.appendChild(td);
    }

    var stack = (typeof THREAD_STACKS !== 'undefined' && THREAD_STACKS[idx]) ? THREAD_STACKS[idx] : '';
    var td = _threadDetailRow.firstChild;
    if (stack) {
        td.innerHTML = '<div style="background:#0f172a;padding:14px 18px;font-family:var(--mono);font-size:12px;line-height:1.7;color:#a7f3d0;max-height:300px;overflow:auto;white-space:pre-wrap;word-break:break-all;cursor:text;user-select:text"></div>';
        td.firstChild.textContent = stack;
    } else {
        td.innerHTML = '<div style="padding:16px;text-align:center;color:var(--text-secondary);font-size:13px;cursor:default">No stack trace available for this thread.</div>';
    }

    // 상세 행 클릭 시 토글 방지
    _threadDetailRow.onclick = function(e) { e.stopPropagation(); };

    // 행 바로 아래 삽입
    row.parentNode.insertBefore(_threadDetailRow, row.nextSibling);
    _threadOpenIdx = idx;
    row.style.background = 'var(--primary-light)';
    var chevron = row.querySelector('.thread-chevron');
    if (chevron) chevron.style.transform = 'rotate(90deg)';
}

// ── Table Sort ────────────────────────────────────────
var sortState = {};
function sortTable(colIdx) {
    var table = document.getElementById('topObjectsTable');
    if (!table) return;
    var rows = Array.from(table.tBodies[0].rows);
    var asc = sortState[colIdx] = !sortState[colIdx];
    var headers = table.querySelectorAll('thead th');
    headers.forEach(function(h) { h.classList.remove('sorted'); });
    headers[colIdx].classList.add('sorted');

    rows.sort(function(a, b) {
        var av, bv;
        if (colIdx === 0) { av = parseInt(a.cells[0].textContent); bv = parseInt(b.cells[0].textContent); }
        else if (colIdx === 1) { av = a.cells[1].textContent.toLowerCase(); bv = b.cells[1].textContent.toLowerCase(); return asc ? av.localeCompare(bv) : bv.localeCompare(av); }
        else if (colIdx === 2) { av = parseInt(a.cells[2].textContent.replace(/[^0-9]/g,'')); bv = parseInt(b.cells[2].textContent.replace(/[^0-9]/g,'')); }
        else if (colIdx === 3) { av = parseFloat(a.dataset.size||0); bv = parseFloat(b.dataset.size||0); }
        else { av = parseFloat(a.dataset.pct||0); bv = parseFloat(b.dataset.pct||0); }
        return asc ? av - bv : bv - av;
    });
    rows.forEach(function(r) { table.tBodies[0].appendChild(r); });
}

// ── Re-Analyze Modal ──────────────────────────────────
// ── Component Detail Modal ─────────────────────────
var _cdRawLoaded = false;
var _cdCurrentClass = '';
var _cdCurrentIdx = null;

function showComponentDetail(className, idx) {
    var modal = document.getElementById('componentDetailModal');
    var title = document.getElementById('componentDetailTitle');
    var tabs = document.getElementById('cdTabs');
    var parsedView = document.getElementById('cdParsedView');
    var rawView = document.getElementById('cdRawView');
    var loading = document.getElementById('cdLoading');

    _cdRawLoaded = false;
    _cdCurrentClass = className;
    _cdCurrentIdx = idx;

    title.textContent = className;
    parsedView.style.display = 'none';
    parsedView.innerHTML = '';
    rawView.style.display = 'none';
    rawView.innerHTML = '';
    loading.style.display = 'block';
    tabs.style.display = 'none';
    modal.classList.add('open');

    // parsed JSON 먼저 시도
    var url = '/report/' + encodeURIComponent(FILENAME) + '/component-detail-parsed?className=' + encodeURIComponent(className);
    if (idx !== undefined && idx !== null) url += '&index=' + idx;
    Common.fetchJSON(url)
        .then(function(data) {
            if (data.parsedSuccessfully && (data.metadata || (data.sections && data.sections.length > 0))) {
                loading.style.display = 'none';
                parsedView.innerHTML = renderParsedDetail(data);
                parsedView.style.display = 'block';
                tabs.style.display = 'flex';
                switchCdTab('parsed', tabs.children[0]);
            } else {
                // 파싱 실패 → raw HTML 폴백
                loadRawComponentDetail(className, idx);
            }
        })
        .catch(function() {
            // parsed 엔드포인트 실패 → raw HTML 폴백
            loadRawComponentDetail(className, idx);
        });
}

function loadRawComponentDetail(className, idx) {
    var loading = document.getElementById('cdLoading');
    var rawView = document.getElementById('cdRawView');
    var parsedView = document.getElementById('cdParsedView');
    var tabs = document.getElementById('cdTabs');

    var url = '/report/' + encodeURIComponent(FILENAME) + '/component-detail?className=' + encodeURIComponent(className);
    if (idx !== undefined && idx !== null) url += '&index=' + idx;
    fetch(url)
        .then(function(r) {
            if (!r.ok) throw new Error('Not found');
            return r.text();
        })
        .then(function(html) {
            loading.style.display = 'none';
            rawView.innerHTML = '<div class="mat-frame" style="max-height:none">' + html + '</div>';
            rawView.style.display = 'block';
            parsedView.style.display = 'none';
            _cdRawLoaded = true;
            // 탭 없이 raw만 표시 (파싱 실패 케이스)
            if (!parsedView.innerHTML) {
                tabs.style.display = 'none';
            }
        })
        .catch(function(err) {
            loading.style.display = 'none';
            // Histogram에서 해당 클래스 정보를 찾아 표시
            var histInfo = findClassInHistogram(className);
            if (histInfo) {
                parsedView.innerHTML = renderHistogramFallback(className, histInfo);
                parsedView.style.display = 'block';
                rawView.style.display = 'none';
                tabs.style.display = 'none';
            } else {
                rawView.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-secondary)">' +
                    '<div style="font-size:48px;margin-bottom:16px">&#128269;</div>' +
                    '<div style="font-size:16px;font-weight:700;margin-bottom:8px">상세 정보를 사용할 수 없습니다</div>' +
                    '<div style="font-size:14px">이 컴포넌트의 상세 하위 페이지를 찾을 수 없습니다.<br>힙 덤프를 다시 분석하여 상세 페이지를 생성해 보세요.</div>' +
                    '<div style="font-size:12px;margin-top:12px;color:#9ca3af;font-family:var(--mono)">오류: ' + err + '</div></div>';
                rawView.style.display = 'block';
                tabs.style.display = 'none';
            }
        });
}

// Histogram 테이블에서 클래스명으로 정보 검색
function findClassInHistogram(className) {
    var rows = document.querySelectorAll('#histogramTable tbody tr');
    for (var i = 0; i < rows.length; i++) {
        var cells = rows[i].querySelectorAll('td');
        if (cells.length >= 5) {
            var cn = (cells[1].textContent || '').trim();
            if (cn === className) {
                return {
                    rank: (cells[0].textContent || '').trim(),
                    objects: (cells[2].textContent || '').trim(),
                    shallowHeap: (cells[3].textContent || '').trim(),
                    retainedHeap: (cells[4].textContent || '').trim(),
                    retainedBytes: parseInt(rows[i].dataset.retained || '0', 10),
                    shallowBytes: parseInt(rows[i].dataset.shallow || '0', 10),
                    objectCount: parseInt(rows[i].dataset.objects || '0', 10)
                };
            }
        }
    }
    return null;
}

// 바이트 수를 사람이 읽기 쉬운 형태로 변환
function formatBytesHuman(str) {
    if (!str) return '-';
    // HTML 엔티티 디코딩 + ">= " 접두사 제거
    var prefix = '';
    var s = str.replace(/&gt;/g, '>').replace(/&lt;/g, '<').replace(/&amp;/g, '&');
    s = s.replace(/^[>=≥\s]+/, function(m) { prefix = '≥ '; return ''; });
    // 콤마 제거 후 숫자 파싱
    var num = parseInt(s.replace(/,/g, ''), 10);
    if (isNaN(num)) return str;
    var display;
    if (num >= 1073741824) display = (num / 1073741824).toFixed(2) + ' GB';
    else if (num >= 1048576) display = (num / 1048576).toFixed(2) + ' MB';
    else if (num >= 1024) display = (num / 1024).toFixed(2) + ' KB';
    else display = num + ' B';
    return prefix + display;
}

// Leak Suspects에서 클래스명이 언급된 항목 검색
function findRelatedLeakSuspects(className) {
    var suspects = [];
    var shortName = className.indexOf('.') >= 0 ? className.substring(className.lastIndexOf('.') + 1) : className;
    document.querySelectorAll('#panel-suspects .suspect-item').forEach(function(item) {
        var desc = item.querySelector('.suspect-desc');
        var titleEl = item.querySelector('.suspect-title-text');
        var text = (desc ? desc.textContent : '') + ' ' + (titleEl ? titleEl.textContent : '');
        if (text.indexOf(className) >= 0 || text.indexOf(shortName) >= 0) {
            suspects.push({
                title: titleEl ? titleEl.textContent.trim() : 'Leak Suspect',
                element: item
            });
        }
    });
    return suspects;
}

// Top Consumers 테이블에서 클래스명 검색
function findClassInTopConsumers(className) {
    var rows = document.querySelectorAll('#topObjectsTable tbody tr');
    for (var i = 0; i < rows.length; i++) {
        var cn = rows[i].dataset['class'];
        if (cn === className) {
            return {
                idx: rows[i].dataset.idx,
                row: rows[i],
                pct: parseFloat(rows[i].dataset.pct || '0'),
                size: parseInt(rows[i].dataset.size || '0', 10)
            };
        }
    }
    return null;
}

// 자주 등장하는 클래스에 대한 부연설명 사전
var CLASS_DESCRIPTIONS = {
    'byte[]': {
        desc: 'Java 원시 바이트 배열. I/O 버퍼, 네트워크 데이터, 파일 콘텐츠, 직렬화 데이터 등에 광범위하게 사용됩니다.',
        detail: 'Java 9+에서는 String 내부 저장소가 char[]에서 byte[]로 변경되어(Compact Strings), 문자열이 많은 애플리케이션에서 byte[]가 상위에 위치하는 것은 정상적입니다.',
        icon: '&#128196;'
    },
    'char[]': {
        desc: 'Java 원시 문자 배열. Java 8 이하에서 String의 내부 저장소로 사용됩니다.',
        detail: 'char[]가 상위에 있다면 문자열 데이터가 힙의 큰 부분을 차지하고 있음을 의미합니다. 문자열 중복(String deduplication)이나 인터닝(interning) 적용을 고려해 볼 수 있습니다.',
        icon: '&#128172;'
    },
    'int[]': {
        desc: 'Java 원시 정수 배열. HashMap/HashSet 등 해시 기반 컬렉션의 내부 버킷 배열로 주로 사용됩니다.',
        detail: '대량의 int[]는 해시맵 크기가 크거나 비트맵/인덱스 데이터를 많이 사용하고 있음을 나타낼 수 있습니다.',
        icon: '&#128290;'
    },
    'java.lang.String': {
        desc: 'Java 문자열 객체. 내부적으로 byte[] (Java 9+) 또는 char[] (Java 8)에 실제 데이터를 저장합니다.',
        detail: '문자열 객체가 상위에 있으면 문자열 중복이 많을 수 있습니다. -XX:+UseStringDeduplication (G1 GC) 옵션을 고려해 보세요.',
        icon: '&#128221;'
    },
    'java.lang.Object[]': {
        desc: 'Object 배열. ArrayList, HashMap 등 대부분의 Java 컬렉션 내부 저장소로 사용됩니다.',
        detail: '컬렉션 프레임워크가 내부적으로 Object[]를 사용하므로, 이 클래스의 메모리가 크다면 컬렉션의 초기 용량(initial capacity) 최적화를 검토해 보세요.',
        icon: '&#128230;'
    },
    'java.util.HashMap$Node': {
        desc: 'HashMap의 개별 엔트리(키-값 쌍) 노드입니다.',
        detail: '노드 수가 많다면 대형 HashMap이 존재하거나, 작은 HashMap이 대량으로 생성되고 있을 수 있습니다. 불필요한 맵 생성을 줄이는 것을 검토하세요.',
        icon: '&#128279;'
    },
    'java.util.LinkedHashMap$Entry': {
        desc: 'LinkedHashMap의 엔트리 노드. 삽입 순서 또는 접근 순서를 유지하는 연결 리스트 구조입니다.',
        detail: 'LRU 캐시 구현이나 순서 보장이 필요한 경우에 사용됩니다. 일반 HashMap보다 엔트리당 메모리를 더 소비합니다.',
        icon: '&#128279;'
    },
    'java.util.concurrent.ConcurrentHashMap$Node': {
        desc: 'ConcurrentHashMap의 엔트리 노드. 스레드 안전한 해시맵의 내부 구조입니다.',
        detail: '동시성 캐시나 공유 데이터 구조에서 많이 사용됩니다. 노드 수가 과도하면 캐시 정리(eviction) 정책을 확인하세요.',
        icon: '&#128279;'
    },
    'java.lang.ref.Finalizer': {
        desc: 'finalize() 메서드를 가진 객체를 추적하는 참조 객체입니다.',
        detail: 'Finalizer가 많으면 finalize()를 오버라이드한 객체들이 GC 지연을 유발하고 있을 수 있습니다. try-with-resources 패턴으로 전환을 고려하세요.',
        icon: '&#9888;'
    },
    'java.lang.Class': {
        desc: 'JVM에 로드된 클래스의 메타데이터 객체입니다.',
        detail: 'Class 객체 수가 과도하면 클래스 로더 누수(ClassLoader leak)나 동적 프록시/리플렉션의 과다 사용을 의심해 볼 수 있습니다.',
        icon: '&#127979;'
    },
    'long[]': {
        desc: 'Java 원시 long 배열. 타임스탬프, ID 값, 통계 카운터 등에 사용됩니다.',
        detail: 'BitSet 내부 저장소로도 사용됩니다. 대량의 long[]은 대형 BitSet이나 시계열 데이터 저장을 나타낼 수 있습니다.',
        icon: '&#128290;'
    },
    'short[]': {
        desc: 'Java 원시 short 배열. 오디오 데이터, 이미지 처리 등 메모리 절약이 필요한 수치 데이터에 사용됩니다.',
        icon: '&#128290;'
    },
    'boolean[]': {
        desc: 'Java 원시 boolean 배열. 플래그 집합이나 상태 추적에 사용됩니다.',
        detail: 'JVM에서 boolean 하나가 1바이트를 차지하므로, 대량의 플래그에는 BitSet 사용이 더 효율적입니다.',
        icon: '&#128290;'
    },
    'double[]': {
        desc: 'Java 원시 double 배열. 수치 계산, 통계, 좌표 데이터 등에 사용됩니다.',
        icon: '&#128290;'
    },
    'float[]': {
        desc: 'Java 원시 float 배열. 그래픽, 과학 계산 등에서 단정밀도 부동소수점 데이터에 사용됩니다.',
        icon: '&#128290;'
    },
    'java.util.ArrayList': {
        desc: '가변 길이 배열 기반 리스트. 내부적으로 Object[]를 사용합니다.',
        detail: '빈 ArrayList도 내부 배열을 할당할 수 있습니다. 크기를 예측할 수 있다면 초기 용량을 지정하여 불필요한 배열 복사를 줄이세요.',
        icon: '&#128203;'
    },
    'java.util.HashMap': {
        desc: '해시 기반 키-값 쌍 저장 컬렉션. 내부적으로 Node[]와 TreeNode를 사용합니다.',
        detail: '기본 초기 용량(16)과 로드 팩터(0.75)가 적용됩니다. 예상 크기를 알고 있다면 초기 용량을 지정하여 리해싱(rehashing)을 줄이세요.',
        icon: '&#128203;'
    },
    'java.lang.ref.WeakReference': {
        desc: '약한 참조(Weak Reference) 객체. GC가 참조 대상을 회수할 수 있게 합니다.',
        detail: 'WeakHashMap이나 캐시에서 주로 사용됩니다. 많은 수의 WeakReference는 캐시 구현을 확인해 볼 필요가 있음을 나타냅니다.',
        icon: '&#128279;'
    },
    'java.lang.ref.SoftReference': {
        desc: '소프트 참조(Soft Reference) 객체. 메모리 부족 시에만 GC가 회수합니다.',
        detail: '캐시에 자주 사용됩니다. OOM 상황에서 SoftReference가 많으면 캐시 크기 제한을 검토하세요.',
        icon: '&#128279;'
    },
    'java.util.TreeMap$Entry': {
        desc: 'TreeMap의 레드-블랙 트리 노드입니다. 정렬된 키-값 쌍을 저장합니다.',
        detail: 'HashMap$Node보다 엔트리당 메모리 사용이 크지만, 키 정렬이 필요한 경우에 사용됩니다.',
        icon: '&#128279;'
    }
};

// Histogram 기반 폴백 렌더링
function renderHistogramFallback(className, info) {
    // 힙 점유율 계산
    var retainedBytes = info.retainedBytes || 0;
    var heapPct = (typeof TOTAL_BYTES !== 'undefined' && TOTAL_BYTES > 0) ? (retainedBytes / TOTAL_BYTES * 100) : 0;

    // 클래스명 + 순위 뱃지
    var h = '<div style="margin-bottom:14px;padding:12px 14px;background:var(--bg);border-radius:8px;display:flex;align-items:flex-start;gap:10px">';
    h += '<div style="flex:1">';
    h += '<div style="font-size:11px;color:var(--text-secondary);text-transform:uppercase;letter-spacing:.5px;margin-bottom:4px">클래스명</div>';
    h += '<div style="font-size:14px;font-weight:600;color:var(--text);font-family:var(--mono);word-break:break-all">' + escHtml(className) + '</div>';
    h += '</div>';
    if (info.rank) {
        h += '<div style="flex-shrink:0;background:var(--primary);color:#fff;border-radius:12px;padding:4px 12px;font-size:12px;font-weight:700;white-space:nowrap">';
        h += 'Histogram #' + escHtml(info.rank);
        h += '</div>';
    }
    h += '</div>';

    // 클래스 부연설명
    var classDesc = CLASS_DESCRIPTIONS[className];
    if (classDesc) {
        h += '<div style="margin-bottom:14px;padding:12px 14px;background:#f0f9ff;border:1px solid #bae6fd;border-radius:8px">';
        h += '<div style="font-size:13px;color:#0c4a6e;line-height:1.6">';
        h += '<span style="margin-right:6px">' + (classDesc.icon || '&#128204;') + '</span>';
        h += '<strong>' + escHtml(className) + '</strong> &mdash; ' + classDesc.desc;
        if (classDesc.detail) {
            h += '<div style="margin-top:8px;padding-top:8px;border-top:1px solid #bae6fd;font-size:12px;color:#0369a1">';
            h += '&#128161; ' + classDesc.detail;
            h += '</div>';
        }
        h += '</div></div>';
    }

    // 메타 카드
    h += '<div class="cd-meta">';
    h += cdMetaCard('객체 수', info.objects);
    h += cdMetaCard('얕은 힙', formatBytesHuman(info.shallowHeap));
    h += cdMetaCard('보유 힙', formatBytesHuman(info.retainedHeap));
    if (heapPct > 0) {
        h += cdMetaCard('힙 점유율', heapPct.toFixed(2) + '%');
    }
    h += '</div>';

    // 힙 점유율 바
    if (heapPct > 0) {
        h += '<div style="margin-top:14px;padding:12px 14px;background:var(--bg);border-radius:8px">';
        h += '<div style="font-size:11px;color:var(--text-secondary);text-transform:uppercase;letter-spacing:.5px;margin-bottom:8px">전체 힙 대비 Retained Heap</div>';
        h += '<div style="background:#e5e7eb;border-radius:6px;height:22px;overflow:hidden;position:relative">';
        var barWidth = Math.min(heapPct, 100);
        var barColor = heapPct >= 30 ? '#ef4444' : heapPct >= 10 ? '#f59e0b' : '#3b82f6';
        h += '<div style="height:100%;width:' + barWidth + '%;background:' + barColor + ';border-radius:6px;transition:width .3s ease"></div>';
        h += '<div style="position:absolute;top:0;left:8px;line-height:22px;font-size:12px;font-weight:700;color:' + (heapPct >= 15 ? '#fff' : '#374151') + '">' + heapPct.toFixed(2) + '%</div>';
        h += '</div>';
        h += '</div>';
    }

    // 연관 Leak Suspect 검색
    var suspects = findRelatedLeakSuspects(className);
    if (suspects.length > 0) {
        h += '<div style="margin-top:14px;padding:14px;background:#fef2f2;border:1px solid #fecaca;border-radius:8px">';
        h += '<div style="font-size:12px;font-weight:700;color:#991b1b;margin-bottom:8px">&#9888; 관련 Leak Suspect</div>';
        suspects.forEach(function(s) {
            h += '<div style="font-size:13px;color:#991b1b;padding:4px 0;cursor:pointer;text-decoration:underline" ';
            h += 'onclick="showPanel(\'suspects\');setTimeout(function(){document.querySelectorAll(\'#panel-suspects .suspect-item\').forEach(function(el){';
            h += 'if(el.querySelector(\'.suspect-title-text\')&&el.querySelector(\'.suspect-title-text\').textContent.trim()===\'' + escHtml(s.title).replace(/'/g, "\\'") + '\'){';
            h += 'el.classList.add(\'open\');el.scrollIntoView({behavior:\'smooth\',block:\'center\'});}});},200);';
            h += 'document.getElementById(\'componentDetailModal\').classList.remove(\'open\');">';
            h += '&#8594; ' + escHtml(s.title);
            h += '</div>';
        });
        h += '</div>';
    }

    // Top Consumers에 존재하면 링크 표시
    var tcMatch = findClassInTopConsumers(className);
    if (tcMatch) {
        h += '<div style="margin-top:14px;padding:14px;background:#eff6ff;border:1px solid #bfdbfe;border-radius:8px">';
        h += '<div style="font-size:12px;font-weight:700;color:#1e40af;margin-bottom:6px">&#128204; Top Consumers 상세 분석 가능</div>';
        h += '<div style="font-size:13px;color:#1e40af;cursor:pointer;text-decoration:underline" ';
        h += 'onclick="document.getElementById(\'componentDetailModal\').classList.remove(\'open\');';
        h += 'setTimeout(function(){showComponentDetail(\'' + escHtml(className).replace(/'/g, "\\'") + '\',' + tcMatch.idx + ');},300);">';
        h += '&#8594; Top Consumers에서 상세 분석 보기';
        h += '</div>';
        h += '</div>';
    }

    // 안내 메시지
    h += '<div style="margin-top:14px;padding:16px;background:#fffbeb;border:1px solid #fde68a;border-radius:8px;font-size:13px;color:#92400e">';
    h += '<strong>&#9432; 참고:</strong> 위 정보는 Class Histogram에서 가져온 기본 데이터입니다.';
    if (!tcMatch) {
        h += ' 이 클래스는 Top Components 리포트에 포함되어 있지 않아 상세 분석 데이터가 제한적입니다.';
    }
    h += '</div>';
    return h;
}

function switchCdTab(tab, btn) {
    var parsedView = document.getElementById('cdParsedView');
    var rawView = document.getElementById('cdRawView');
    var tabs = document.getElementById('cdTabs');

    // 탭 버튼 활성화
    for (var i = 0; i < tabs.children.length; i++) tabs.children[i].classList.remove('active');
    if (btn) btn.classList.add('active');

    if (tab === 'parsed') {
        parsedView.style.display = 'block';
        rawView.style.display = 'none';
    } else {
        parsedView.style.display = 'none';
        rawView.style.display = 'block';
        // Raw Data 최초 클릭 시 lazy 로드
        if (!_cdRawLoaded) {
            rawView.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-secondary)">원본 데이터 로딩 중...</div>';
            _cdRawLoaded = true;
            var url = '/report/' + encodeURIComponent(FILENAME) + '/component-detail?className=' + encodeURIComponent(_cdCurrentClass);
            if (_cdCurrentIdx !== undefined && _cdCurrentIdx !== null) url += '&index=' + _cdCurrentIdx;
            fetch(url)
                .then(function(r) { return r.ok ? r.text() : Promise.reject('Not found'); })
                .then(function(html) {
                    rawView.innerHTML = '<div class="mat-frame" style="max-height:none">' + html + '</div>';
                })
                .catch(function() {
                    rawView.innerHTML = '<div style="text-align:center;padding:20px;color:var(--text-secondary)">원본 데이터를 사용할 수 없습니다</div>';
                });
        }
    }
}

// ── MAT 영문 → 한국어 번역 사전 ──────────────────────────
var MAT_TITLE_MAP = {
    // 섹션 제목
    'Miscellaneous': '기타 분석',
    'Map Collision Ratios': '맵 충돌 비율',
    'Duplicate Strings': '중복 문자열',
    'Duplicate Classes': '중복 클래스',
    'Empty Collections': '빈 컬렉션',
    'Collection Fill Ratios': '컬렉션 채움 비율',
    'Zero-Length Arrays': '길이 0 배열',
    'Primitive Arrays with a Constant Value': '상수 값 원시 배열',
    'Array Fill Ratios': '배열 채움 비율',
    'Possible Memory Waste': '메모리 낭비 가능성',
    'Memory Waste': '메모리 낭비',
    'Possible Memory Leak': '메모리 누수 가능성',
    'Memory Leak': '메모리 누수',
    'Soft Reference Statistics': '소프트 참조 통계',
    'Weak Reference Statistics': '약한 참조 통계',
    'Phantom Reference Statistics': '팬텀 참조 통계',
    'Finalizer Statistics': 'Finalizer 통계',
    'Finalizer Summary': 'Finalizer 요약',
    'Class Histogram': '클래스 히스토그램',
    'Top Components': '상위 컴포넌트',
    'Table Of Contents': '목차',
    'Component Report': '컴포넌트 리포트',
    'Overview': '개요',
    'Details': '상세 정보',
    'Top Consumers': '상위 소비자',
    'Retained Set': '보유 집합',
    'Suspects': '의심 항목',
    'Leak Suspects': '누수 의심 항목',
    'System Overview': '시스템 개요',
    'Thread Overview': '스레드 개요',
    'Thread Details': '스레드 상세',
    'Description': '설명',
    'Shortest Paths To the Accumulation Point': '축적 지점까지의 최단 경로',
    'Accumulated Objects in Dominator Tree': '도미네이터 트리의 축적 객체',
    'Accumulated Objects by Class in Dominator Tree': '도미네이터 트리의 클래스별 축적 객체',
    'All Accumulated Objects by Class': '클래스별 전체 축적 객체'
};

var MAT_HEADER_MAP = {
    // 테이블 헤더
    'Class Name': '클래스명',
    'Objects': '객체 수',
    'Shallow Heap': '얕은 힙',
    'Retained Heap': '보유 힙',
    'Heap Size': '힙 크기',
    'Percentage': '비율',
    'Num Objects': '객체 수',
    'Used Heap Size': '사용 힙 크기',
    'Class': '클래스',
    'Name': '이름',
    'Value': '값',
    'Count': '개수',
    'Size': '크기',
    'Waste': '낭비',
    'Instances': '인스턴스',
    'Total': '합계',
    'Empty': '비어있음',
    'Non-Empty': '비어있지 않음',
    'Number of Objects': '객체 수',
    'Shallow Size': '얕은 크기',
    'Retained Size': '보유 크기',
    'Ref.Count': '참조 수',
    'Average': '평균',
    'Minimum': '최소',
    'Maximum': '최대',
    'Median': '중간값',
    'Standard Deviation': '표준편차',
    'Address': '주소',
    'Type': '유형',
    'Collection': '컬렉션',
    'Fill Ratio': '채움 비율',
    'Length': '길이',
    'Key': '키',
    'Collision Ratio': '충돌 비율',
    'Number of Collisions': '충돌 수',
    'Total Size': '총 크기',
    'Wasted Size': '낭비 크기'
};

// 자주 등장하는 MAT 영문 문구 → 한국어 패턴 매칭 (줄 단위)
var MAT_LINE_PATTERNS = [
    [/^Detected the following maps with collision ratios above (\d+)%:/i,
        function(m) { return '다음 맵에서 ' + m[1] + '% 이상의 충돌 비율이 감지되었습니다:'; }],
    [/^(\d[\d,]*)\s+instances?\s+of\s+([\w.$\[\]<>]+)\s+retain\s+>=?\s*([\d,]+)\s+bytes/i,
        function(m) { return m[2] + '의 인스턴스 ' + m[1] + '개가 ' + m[3] + ' 바이트 이상을 보유하고 있습니다'; }],
    [/^One\s+instance\s+of\s+([\w.$\[\]<>]+)\s+retain[s]?\s+>=?\s*([\d,]+)\s+bytes/i,
        function(m) { return m[1] + '의 인스턴스 1개가 ' + m[2] + ' 바이트를 보유하고 있습니다'; }],
    [/^(\d[\d,]*)\s+instances?\s+of\s+([\w.$\[\]<>]+)\s+retain\s+([\d,]+)\s+bytes/i,
        function(m) { return m[2] + '의 인스턴스 ' + m[1] + '개가 ' + m[3] + ' 바이트를 보유하고 있습니다'; }],
    [/^Table Of Contents$/i, function() { return '목차'; }],
    [/^Created by Eclipse Memory Analyzer$/i, function() { return 'Eclipse Memory Analyzer에 의해 생성됨'; }],
    [/^Created by (.+)$/i, function(m) { return m[1] + '에 의해 생성됨'; }],
    [/^The class(?:es)? (.+?) (is|are) loaded by (\d+) class loaders?/i,
        function(m) { return m[1] + ' 클래스가 ' + m[3] + '개의 클래스 로더에 의해 로드되었습니다'; }],
    [/^(\d[\d,]*)\s+instances\s+of\s+(.*?),\s+loaded by\s+(.*?),?\s+occupy\s+([\d,]+)\s+\(([\d.]+)%\)\s+bytes/i,
        function(m) { return m[3] + '에 의해 로드된 ' + m[2] + '의 인스턴스 ' + m[1] + '개가 ' + m[4] + ' 바이트(' + m[5] + '%)를 점유하고 있습니다'; }],
    [/^One\s+instance\s+of\s+(.*?),?\s+loaded by\s+(.*?),?\s+occup(?:y|ies)\s+([\d,]+)\s+\(([\d.]+)%\)\s+bytes/i,
        function(m) { return m[2] + '에 의해 로드된 ' + m[1] + '의 인스턴스 1개가 ' + m[3] + ' 바이트(' + m[4] + '%)를 점유하고 있습니다'; }],
    [/^These\s+(\d+)\s+instances?\s+are?\s+referenced\s+from\s+one\s+instance\s+of\s+([\w.$]+)/i,
        function(m) { return '이 인스턴스 ' + m[1] + '개는 ' + m[2] + '의 인스턴스 1개에서 참조됩니다'; }],
    [/^Biggest instances?:/i, function() { return '가장 큰 인스턴스:'; }],
    [/^The memory is accumulated in one instance of\s+(.*?)\s+loaded by\s+(.*?)(?:\s*@\s*0x[0-9a-fA-F]+)?$/i,
        function(m) { return '메모리가 ' + m[2] + '에 의해 로드된 ' + m[1] + '의 인스턴스 1개에 축적되어 있습니다'; }],
    [/^The memory is accumulated in\s+(.*?)\s+loaded by\s+(.*?)$/i,
        function(m) { return '메모리가 ' + m[2] + '에 의해 로드된 ' + m[1] + '에 축적되어 있습니다'; }],
    [/^Keywords$/i, function() { return '키워드'; }],
    [/^See stacktrace$/i, function() { return '스택트레이스 보기'; }],
    [/^Problem Suspect (\d+)$/i, function(m) { return '의심 항목 ' + m[1]; }],
    [/^Suspect (\d+)$/i, function(m) { return '의심 항목 ' + m[1]; }],
    [/^(\d[\d,]*)\s*×\s*(.*?)\s*\(([\d,]+)\s*bytes\)$/i,
        function(m) { return m[1] + ' × ' + m[2] + ' (' + m[3] + ' 바이트)'; }]
];

// 긴 문장 전체를 매칭하는 패턴 (줄 분리 전에 적용)
var MAT_SENTENCE_PATTERNS = [
    // Duplicate Strings — "Found N occurrences of X with at least M instances..."
    [/Found (\d[\d,]*) occurrences? of ([\w.$\[\]]+) with at least (\d[\d,]*) instances? having identical content\.\s*Total size is ([\d,]+) bytes\.\s*Top elements include:\s*/i,
        function(m) { return '동일한 내용을 가진 인스턴스가 ' + m[3] + '개 이상인 ' + m[2] + '가 ' + m[1] + '건 발견되었습니다. 총 크기는 ' + m[4] + ' 바이트입니다. 주요 항목: '; }],
    // "N × value (bytes)" 패턴 — 리스트 항목 및 인라인
    [/^(\d[\d,]*)\s*×\s*(.*?)\s*\(([\d,]+)\s*bytes\)$/i,
        function(m) { return m[1] + ' × ' + m[2] + ' (' + m[3] + ' 바이트)'; }],
    // Soft Reference Statistics
    [/A total of (\d[\d,]*) ([\w.$]+) objects have been found, which softly reference (\d[\d,]*) objects?\./i,
        function(m) { return '총 ' + m[1] + '개의 ' + m[2] + ' 객체가 발견되었으며, ' + m[3] + '개의 객체를 소프트 참조하고 있습니다.'; }],
    [/A total of (\d[\d,]*) ([\w.$]+) objects have been found, which softly reference no objects?\./i,
        function(m) { return '총 ' + m[1] + '개의 ' + m[2] + ' 객체가 발견되었으며, 소프트 참조하는 객체는 없습니다.'; }],
    // Weak Reference Statistics
    [/A total of (\d[\d,]*) ([\w.$]+) objects have been found, which weakly reference (\d[\d,]*) objects?\./i,
        function(m) { return '총 ' + m[1] + '개의 ' + m[2] + ' 객체가 발견되었으며, ' + m[3] + '개의 객체를 약한 참조하고 있습니다.'; }],
    [/A total of (\d[\d,]*) ([\w.$]+) objects have been found, which weakly reference no objects?\./i,
        function(m) { return '총 ' + m[1] + '개의 ' + m[2] + ' 객체가 발견되었으며, 약한 참조하는 객체는 없습니다.'; }],
    // Phantom Reference Statistics
    [/A total of (\d[\d,]*) ([\w.$]+) objects have been found, which phantom reference (\d[\d,]*) objects?\./i,
        function(m) { return '총 ' + m[1] + '개의 ' + m[2] + ' 객체가 발견되었으며, ' + m[3] + '개의 객체를 팬텀 참조하고 있습니다.'; }],
    // "No objects totalling X B are retained (kept alive) only via soft/weak references."
    [/No objects totalling ([\d,]+ \w+) are retained \(kept alive\) only via (soft|weak|phantom) references\./ig,
        function(m) { var t = m[2]==='soft'?'소프트':m[2]==='weak'?'약한':'팬텀'; return t + ' 참조만으로 유지(alive)되는 객체는 없습니다 (총 ' + m[1] + ').'; }],
    // "N objects totalling X unit are retained (kept alive) only via soft/weak references." — 실제 MAT 출력 포맷
    [/(\d[\d,]*) objects totalling ([\d,.]+ \w+) are retained \(kept alive\) only via (soft|weak|phantom) references\./ig,
        function(m) { var t = m[3]==='soft'?'소프트':m[3]==='weak'?'약한':'팬텀'; return t + ' 참조만으로 유지(alive)되는 객체가 ' + m[1] + '개 있습니다 (총 ' + m[2] + ').'; }],
    [/([\d,]+ \w+) of objects are retained \(kept alive\) only via (soft|weak|phantom) references\./ig,
        function(m) { var t = m[2]==='soft'?'소프트':m[2]==='weak'?'약한':'팬텀'; return t + ' 참조만으로 유지(alive)되는 객체가 ' + m[1] + ' 있습니다.'; }],
    // "No objects totalling X B are softly/weakly referenced and also strongly retained..."
    [/No objects totalling ([\d,]+ \w+) are (softly|weakly) referenced and also strongly retained \(kept alive\) via (soft|weak|phantom) references\./ig,
        function(m) { var t = m[3]==='soft'?'소프트':m[3]==='weak'?'약한':'팬텀'; var r = m[2]==='softly'?'소프트':'약한'; return r + ' 참조되면서 ' + t + ' 참조를 통해 강하게 유지(alive)되는 객체는 없습니다 (총 ' + m[1] + ').'; }],
    // "N objects totalling X unit are softly/weakly referenced and also strongly retained..." — 실제 MAT 출력 포맷
    [/(\d[\d,]*) objects totalling ([\d,.]+ \w+) are (softly|weakly) referenced and also strongly retained \(kept alive\) via (soft|weak|phantom) references\./ig,
        function(m) { var t = m[4]==='soft'?'소프트':m[4]==='weak'?'약한':'팬텀'; var r = m[3]==='softly'?'소프트':'약한'; return r + ' 참조되면서 ' + t + ' 참조를 통해 강하게 유지(alive)되는 객체가 ' + m[1] + '개 있습니다 (총 ' + m[2] + ').'; }],
    // "One object totalling X unit are softly/weakly referenced and also strongly retained..." — 단수형 (1 개) 변형
    [/One object totalling ([\d,.]+ \w+) (?:is|are) (softly|weakly) referenced and also strongly retained \(kept alive\) via (soft|weak|phantom) references\./ig,
        function(m) { var t = m[3]==='soft'?'소프트':m[3]==='weak'?'약한':'팬텀'; var r = m[2]==='softly'?'소프트':'약한'; return r + ' 참조되면서 ' + t + ' 참조를 통해 강하게 유지(alive)되는 객체가 1개 있습니다 (총 ' + m[1] + ').'; }],
    // "One object totalling X unit is retained (kept alive) only via soft/weak/phantom references." — 단수형 (only via) 변형
    [/One object totalling ([\d,.]+ \w+) (?:is|are) retained \(kept alive\) only via (soft|weak|phantom) references\./ig,
        function(m) { var t = m[2]==='soft'?'소프트':m[2]==='weak'?'약한':'팬텀'; return t + ' 참조만으로 유지(alive)되는 객체가 1개 있습니다 (총 ' + m[1] + ').'; }],
    [/([\d,]+ \w+) of objects are (softly|weakly) referenced and also strongly retained \(kept alive\) via (soft|weak|phantom) references\./ig,
        function(m) { var t = m[3]==='soft'?'소프트':m[3]==='weak'?'약한':'팬텀'; var r = m[2]==='softly'?'소프트':'약한'; return r + ' 참조되면서 ' + t + ' 참조를 통해 강하게 유지(alive)되는 객체가 ' + m[1] + ' 있습니다.'; }],
    // "Possible Memory Leak" — 라벨 (위 retained 문장 앞에 자주 등장)
    [/Possible Memory Leak/ig,
        function() { return '잠재적 메모리 누수'; }],
    // Reference type component-keep statements (Soft/Weak/Phantom References)
    [/Component does not keep (Soft|Weak|Phantom) References alive\./ig,
        function(m) { var t = m[1].toLowerCase()==='soft'?'소프트':m[1].toLowerCase()==='weak'?'약한':'팬텀'; return '이 컴포넌트는 ' + t + ' 참조를 유지(alive)하지 않습니다.'; }],
    // Finalizer
    [/Component does not keep objects? with Finalizer methods? alive\./i,
        function() { return '이 컴포넌트는 Finalizer 메서드를 가진 객체를 유지(alive)하지 않습니다.'; }],
    [/A total of (\d[\d,]*) objects with finalizers? have been found\./i,
        function(m) { return 'Finalizer를 가진 객체가 총 ' + m[1] + '개 발견되었습니다.'; }],
    [/A total of (\d[\d,]*) objects? implement the finalize method\./i,
        function(m) { return '총 ' + m[1] + '개의 객체가 finalize 메서드를 구현하고 있습니다.'; }],
    [/Heap dump contains no java\.lang\.ref\.Finalizer objects?\.\s*IBM VMs implement Finalizer differently and are currently not supported by this report\./i,
        function() { return '힙 덤프에 java.lang.ref.Finalizer 객체가 없습니다. IBM VM 은 Finalizer 를 다르게 구현하며, 본 리포트에서는 현재 지원되지 않습니다.'; }],
    [/Heap dump contains no java\.lang\.ref\.Finalizer objects?\./i,
        function() { return '힙 덤프에 java.lang.ref.Finalizer 객체가 없습니다.'; }],
    [/IBM VMs implement Finalizer differently and are currently not supported by this report\./i,
        function() { return 'IBM VM 은 Finalizer 를 다르게 구현하며, 본 리포트에서는 현재 지원되지 않습니다.'; }],
    // Map collision ratios
    [/No maps found with collision ratios greater than (\d+)%\./i,
        function(m) { return '충돌 비율이 ' + m[1] + '%를 초과하는 맵이 발견되지 않았습니다.'; }],
    [/Detected the following maps with collision ratios greater than (\d+)%:/i,
        function(m) { return '충돌 비율이 ' + m[1] + '%를 초과하는 다음 맵이 발견되었습니다:'; }],
    // Collection fill ratios
    [/Detected the following collections with fill ratios below (\d+)%:/i,
        function(m) { return '채움 비율이 ' + m[1] + '% 미만인 다음 컬렉션이 발견되었습니다:'; }],
    [/Detected the following arrays with fill ratios below (\d+)%:/i,
        function(m) { return '채움 비율이 ' + m[1] + '% 미만인 다음 배열이 발견되었습니다:'; }],
    // "211 instances of java.util.HashMap retain >= 4,045,896 bytes."
    [/(\d[\d,]*) instances? of ([\w.$]+) retain >= ([\d,]+) bytes\./ig,
        function(m) { return m[2] + ' 인스턴스 ' + m[1] + '개가 ' + m[3] + ' 바이트 이상을 유지하고 있습니다.'; }],
    // Empty collections / duplicate strings
    [/No excessive duplicate strings found\./i,
        function() { return '과도한 중복 문자열이 발견되지 않았습니다.'; }],
    [/No suspicious empty collections found\./i,
        function() { return '의심스러운 빈 컬렉션이 발견되지 않았습니다.'; }],
    [/No excessive usage of empty collections found\./i,
        function() { return '빈 컬렉션의 과도한 사용이 발견되지 않았습니다.'; }],
    [/No suspicious collection fill ratios found\./i,
        function() { return '의심스러운 컬렉션 채움 비율이 발견되지 않았습니다.'; }],
    [/No serious amount of collections with low fill ratios found\./i,
        function() { return '낮은 채움 비율을 가진 컬렉션이 심각한 수준으로 발견되지 않았습니다.'; }],
    [/No suspicious zero-length arrays found\./i,
        function() { return '의심스러운 길이 0 배열이 발견되지 않았습니다.'; }],
    [/No excessive usage of zero-length arrays found\./i,
        function() { return '길이 0 배열의 과도한 사용이 발견되지 않았습니다.'; }],
    [/No suspicious array fill ratios found\./i,
        function() { return '의심스러운 배열 채움 비율이 발견되지 않았습니다.'; }],
    [/No serious amount of arrays with low fill ratios found\./i,
        function() { return '낮은 채움 비율을 가진 배열이 심각한 수준으로 발견되지 않았습니다.'; }],
    [/No suspicious primitive arrays with constant values found\./i,
        function() { return '의심스러운 상수 값 원시 배열이 발견되지 않았습니다.'; }],
    [/No excessive usage of primitive arrays with a constant value found\./i,
        function() { return '상수 값 원시 배열의 과도한 사용이 발견되지 않았습니다.'; }],
    // MAT 리포트 footer (Table Of Contents / Created by Eclipse Memory Analyzer) — 본문 inline 등장 시 제거.
    // 표시 가치가 없고 단락 끝에 붙어 시각적 노이즈만 유발.
    // (단독 라인 형태는 MAT_LINE_PATTERNS 의 ^Table Of Contents$ / ^Created by Eclipse... 규칙이 별도 처리)
    [/Table Of Contents\s+Created by Eclipse Memory Analyzer/ig,
        function() { return ''; }],
    [/Created by Eclipse Memory Analyzer/ig,
        function() { return ''; }],
    [/Table Of Contents/ig,
        function() { return ''; }]
];

function trTitle(title) {
    if (!title) return '';
    if (MAT_TITLE_MAP[title]) return MAT_TITLE_MAP[title];
    // 부분 매칭: "Duplicate Strings" 같은 키가 포함된 경우
    for (var k in MAT_TITLE_MAP) {
        if (title === k) return MAT_TITLE_MAP[k];
    }
    return title;
}

function trHeader(header) {
    if (!header) return '';
    if (MAT_HEADER_MAP[header]) return MAT_HEADER_MAP[header];
    return header;
}

function decodeHtmlEntities(s) {
    return s.replace(/&gt;/g, '>').replace(/&lt;/g, '<').replace(/&amp;/g, '&')
            .replace(/&#61;/g, '=').replace(/&#62;/g, '>').replace(/&#60;/g, '<')
            .replace(/&#34;/g, '"').replace(/&quot;/g, '"').replace(/&#39;/g, "'");
}

function trText(text) {
    if (!text) return '';
    // HTML 엔티티 디코딩
    text = decodeHtmlEntities(text);

    // 1) 문장 단위 패턴 매칭 (전체 텍스트에 대해 반복 적용)
    for (var i = 0; i < MAT_SENTENCE_PATTERNS.length; i++) {
        var pat = MAT_SENTENCE_PATTERNS[i][0];
        var fn = MAT_SENTENCE_PATTERNS[i][1];
        // global 플래그가 있으면 replaceAll, 아니면 단일 매칭
        if (pat.global) {
            text = text.replace(pat, function() {
                var args = Array.prototype.slice.call(arguments);
                return fn(args);
            });
        } else {
            var m = text.match(pat);
            if (m) {
                text = text.replace(pat, fn(m));
            }
        }
    }

    // 2) 줄 단위 패턴 매칭
    var lines = text.split('\n');
    var result = [];
    for (var li = 0; li < lines.length; li++) {
        var line = lines[li];
        var prefix = '';
        var content = line;
        if (line.indexOf('- ') === 0) {
            prefix = '- ';
            content = line.substring(2);
        }
        var translated = false;
        for (var j = 0; j < MAT_LINE_PATTERNS.length; j++) {
            var lm = content.match(MAT_LINE_PATTERNS[j][0]);
            if (lm) {
                result.push(prefix + MAT_LINE_PATTERNS[j][1](lm));
                translated = true;
                break;
            }
        }
        if (!translated) result.push(line);
    }
    // blanked 패턴(e.g. MAT footer) 제거 후 남은 다중 공백/끝 공백 정돈
    return result.join('\n').replace(/[ \t]{2,}/g, ' ').replace(/[ \t]+(\n|$)/g, '$1');
}

// 섹션 트리에서 경고/오류 수 집계
function countSectionSeverities(sections) {
    var result = { warnings: 0, errors: 0, total: 0 };
    if (!sections) return result;
    for (var i = 0; i < sections.length; i++) {
        result.total++;
        if (sections[i].severity === 'warning') result.warnings++;
        if (sections[i].severity === 'error') result.errors++;
        if (sections[i].children && sections[i].children.length > 0) {
            var sub = countSectionSeverities(sections[i].children);
            result.warnings += sub.warnings;
            result.errors += sub.errors;
            result.total += sub.total;
        }
    }
    return result;
}

// 분석 요약 텍스트 생성
function generateAnalysisSummary(data, className, heapPct, severities, histInfo, suspects) {
    var points = [];

    // 힙 점유율 기반 평가
    if (heapPct >= 30) {
        points.push('&#9888; 이 컴포넌트는 전체 힙의 <strong>' + heapPct.toFixed(1) + '%</strong>를 차지하고 있어 <strong>주요 메모리 소비자</strong>입니다. 메모리 최적화가 필요할 수 있습니다.');
    } else if (heapPct >= 10) {
        points.push('&#128313; 전체 힙의 <strong>' + heapPct.toFixed(1) + '%</strong>를 차지하며, 상당한 메모리를 사용하고 있습니다.');
    } else if (heapPct > 0) {
        points.push('&#9989; 전체 힙의 <strong>' + heapPct.toFixed(1) + '%</strong>를 차지하며, 정상 범위 내입니다.');
    }

    // 경고/오류
    if (severities.errors > 0) {
        points.push('&#128308; MAT 분석에서 <strong>' + severities.errors + '건의 오류</strong>가 감지되었습니다. 아래 섹션을 확인하세요.');
    }
    if (severities.warnings > 0) {
        points.push('&#128992; MAT 분석에서 <strong>' + severities.warnings + '건의 경고</strong>가 발견되었습니다.');
    }

    // Leak Suspect 연관
    if (suspects.length > 0) {
        points.push('&#128270; 이 클래스가 <strong>' + suspects.length + '개의 Leak Suspect</strong>에서 언급되고 있습니다 — 메모리 누수 가능성을 검토하세요.');
    }

    // Histogram 연동
    if (histInfo) {
        points.push('&#128202; Histogram 기준 <strong>#' + histInfo.rank + '위</strong>, 객체 수 <strong>' + histInfo.objects + '</strong>, Shallow Heap <strong>' + histInfo.shallowHeap + '</strong>');
    }

    // 객체 수 대비 크기 비율 (metadata 기반)
    if (data.metadata && data.metadata.objectCount > 0 && data.metadata.sizeBytes > 0) {
        var avgObjSize = data.metadata.sizeBytes / data.metadata.objectCount;
        if (avgObjSize > 1048576) {
            points.push('&#128200; 객체당 평균 크기가 <strong>' + (avgObjSize / 1048576).toFixed(1) + ' MB</strong>로, 대형 객체가 포함되어 있습니다.');
        } else if (data.metadata.objectCount > 100000) {
            points.push('&#128200; 객체 수가 <strong>' + formatNum(data.metadata.objectCount) + '</strong>개로 매우 많습니다. 객체 풀링이나 캐시 정리를 검토하세요.');
        }
    }

    return points;
}

function renderParsedDetail(data) {
    var h = '';
    var className = _cdCurrentClass || (data.className || '');

    // 클래스 부연설명
    var classDesc = CLASS_DESCRIPTIONS[className];
    if (classDesc) {
        h += '<div style="margin-bottom:14px;padding:12px 14px;background:#f0f9ff;border:1px solid #bae6fd;border-radius:8px">';
        h += '<div style="font-size:13px;color:#0c4a6e;line-height:1.6">';
        h += '<span style="margin-right:6px">' + (classDesc.icon || '&#128204;') + '</span>';
        h += '<strong>' + escHtml(className) + '</strong> &mdash; ' + classDesc.desc;
        if (classDesc.detail) {
            h += '<div style="margin-top:8px;padding-top:8px;border-top:1px solid #bae6fd;font-size:12px;color:#0369a1">';
            h += '&#128161; ' + classDesc.detail;
            h += '</div>';
        }
        h += '</div></div>';
    }

    // 메타데이터 카드
    // Top Consumers 테이블의 MAT 기준 퍼센트를 우선 사용 (정확한 dominator 분석 값)
    var tcInfo = findClassInTopConsumers(className);
    var heapPct = (tcInfo && tcInfo.pct > 0) ? tcInfo.pct : 0;
    var retainedSize = (tcInfo && tcInfo.size > 0) ? tcInfo.size : 0;

    if (data.metadata) {
        var m = data.metadata;
        // Top Consumers에 없는 경우에만 metadata.sizeBytes로 폴백
        if (heapPct === 0 && typeof TOTAL_BYTES !== 'undefined' && TOTAL_BYTES > 0 && m.sizeBytes > 0) {
            heapPct = m.sizeBytes / TOTAL_BYTES * 100;
        }
        h += '<div class="cd-meta">';
        h += cdMetaCard('크기', m.sizeDisplay || '-');
        if (retainedSize > 0) {
            h += cdMetaCard('Retained Heap', formatBytesHuman(String(retainedSize)));
        }
        h += cdMetaCard('클래스 수', formatNum(m.classCount));
        h += cdMetaCard('객체 수', formatNum(m.objectCount));
        h += cdMetaCard('클래스 로더', m.classLoader || '-');
        if (heapPct > 0) {
            h += cdMetaCard('힙 점유율', heapPct.toFixed(2) + '%');
        }
        h += '</div>';

        // 힙 점유율 바
        if (heapPct > 0) {
            h += '<div style="margin:10px 0 14px;padding:0 2px">';
            var barWidth = Math.min(heapPct, 100);
            var barColor = heapPct >= 30 ? '#ef4444' : heapPct >= 10 ? '#f59e0b' : '#3b82f6';
            h += '<div style="background:#e5e7eb;border-radius:6px;height:20px;overflow:hidden;position:relative">';
            h += '<div style="height:100%;width:' + barWidth + '%;background:' + barColor + ';border-radius:6px;transition:width .3s ease"></div>';
            h += '<div style="position:absolute;top:0;left:8px;line-height:20px;font-size:11px;font-weight:700;color:' + (heapPct >= 15 ? '#fff' : '#374151') + '">전체 힙의 ' + heapPct.toFixed(2) + '%</div>';
            h += '</div></div>';
        }
    }

    // 요약 카드 생성
    var severities = countSectionSeverities(data.sections);
    var histInfo = findClassInHistogram(className);
    var suspects = findRelatedLeakSuspects(className);
    var summaryPoints = generateAnalysisSummary(data, className, heapPct, severities, histInfo, suspects);

    if (summaryPoints.length > 0) {
        var summaryBg = severities.errors > 0 ? '#fef2f2' : (severities.warnings > 0 || heapPct >= 30) ? '#fffbeb' : '#f0fdf4';
        var summaryBorder = severities.errors > 0 ? '#fecaca' : (severities.warnings > 0 || heapPct >= 30) ? '#fde68a' : '#bbf7d0';
        var summaryColor = severities.errors > 0 ? '#991b1b' : (severities.warnings > 0 || heapPct >= 30) ? '#92400e' : '#166534';
        h += '<div style="margin-bottom:14px;padding:14px;background:' + summaryBg + ';border:1px solid ' + summaryBorder + ';border-radius:8px">';
        h += '<div style="font-size:12px;font-weight:700;color:' + summaryColor + ';margin-bottom:8px;text-transform:uppercase;letter-spacing:.5px">&#128221; 분석 요약</div>';
        h += '<ul style="margin:0;padding-left:20px;list-style:none">';
        for (var sp = 0; sp < summaryPoints.length; sp++) {
            h += '<li style="font-size:13px;color:' + summaryColor + ';line-height:1.8;padding:2px 0">' + summaryPoints[sp] + '</li>';
        }
        h += '</ul>';

        // Leak Suspect 링크
        if (suspects.length > 0) {
            h += '<div style="margin-top:10px;padding-top:10px;border-top:1px solid ' + summaryBorder + '">';
            suspects.forEach(function(s) {
                h += '<div style="font-size:12px;color:' + summaryColor + ';padding:3px 0;cursor:pointer;text-decoration:underline" ';
                h += 'onclick="showPanel(\'suspects\');setTimeout(function(){document.querySelectorAll(\'#panel-suspects .suspect-item\').forEach(function(el){';
                h += 'if(el.querySelector(\'.suspect-title-text\')&&el.querySelector(\'.suspect-title-text\').textContent.trim()===\'' + escHtml(s.title).replace(/'/g, "\\'") + '\'){';
                h += 'el.classList.add(\'open\');el.scrollIntoView({behavior:\'smooth\',block:\'center\'});}});},200);';
                h += 'document.getElementById(\'componentDetailModal\').classList.remove(\'open\');">';
                h += '&#128270; Leak Suspect로 이동: ' + escHtml(s.title);
                h += '</div>';
            });
            h += '</div>';
        }
        h += '</div>';
    }

    // 섹션 (전역 인덱스 카운터로 고유 ID 보장)
    _cdSectionIdx = 0;
    if (data.sections && data.sections.length > 0) {
        for (var i = 0; i < data.sections.length; i++) {
            h += renderCdSection(data.sections[i], true, 0); // 최상위는 기본 열림
        }
    }

    if (!h) h = '<div style="text-align:center;padding:30px;color:var(--text-secondary)">파싱된 데이터가 없습니다</div>';
    return h;
}

// 메타 카드 부연설명 사전
var META_HELP = {
    '크기': '이 클래스 로더가 로드한 모든 클래스와 객체의 총 크기입니다. 다른 컴포넌트와 공유되는 객체도 포함되어 Retained Heap보다 클 수 있습니다.',
    'Retained Heap': '이 컴포넌트가 GC root에서 독점적으로 보유(retain)하는 힙 크기입니다. 이 컴포넌트가 해제되면 실제로 회수되는 메모리량입니다.',
    '클래스 수': '이 컴포넌트에 포함된 Java 클래스의 수입니다.',
    '객체 수': '이 컴포넌트에 속한 객체 인스턴스의 총 수입니다. 수가 매우 많다면 객체 생성 최적화를 검토할 수 있습니다.',
    '클래스 로더': '이 컴포넌트의 클래스를 로드한 ClassLoader입니다. <system class loader>는 JVM 부트스트랩/시스템 클래스 로더를 의미합니다.',
    '힙 점유율': '전체 힙 메모리 대비 이 컴포넌트의 Retained Heap 비율입니다. MAT의 dominator 분석 기준으로 계산됩니다.',
    '얕은 힙': 'Shallow Heap — 객체 헤더와 필드가 차지하는 메모리입니다. 참조하는 다른 객체의 크기는 포함하지 않습니다.',
    '보유 힙': 'Retained Heap — 이 객체가 GC되면 함께 회수되는 전체 메모리입니다. 객체 자체 + 이 객체만이 유일하게 참조하는 다른 객체들의 크기를 포함합니다.'
};

function escAttr(s) {
    if (!s) return '';
    return s.replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function toggleMetaHelp(btn) {
    var wasOpen = btn._cdHelpOpen;
    // 다른 모든 팝업 닫기
    document.querySelectorAll('.cd-help-popup').forEach(function(p) { p.remove(); });
    document.querySelectorAll('.cd-help-btn').forEach(function(b) { b._cdHelpOpen = false; });
    // 같은 버튼이면 토글 (닫기만)
    if (wasOpen) return;
    // 새 팝업 생성 (body에 추가하여 overflow 영향 없음)
    var popup = document.createElement('div');
    popup.className = 'cd-help-popup';
    popup.textContent = btn.getAttribute('data-help');
    document.body.appendChild(popup);
    btn._cdHelpOpen = true;
    // 버튼 위치 기준으로 팝업 배치
    var rect = btn.getBoundingClientRect();
    var popupW = 240;
    var popupH = popup.offsetHeight;
    var spaceBelow = window.innerHeight - rect.bottom;
    var showBelow = spaceBelow > popupH + 16;
    // 모달 박스의 실제 경계를 기준으로 사용 (모달 내부일 경우)
    var modalBox = btn.closest('.modal-box');
    var rightBound = window.innerWidth;
    var leftBound = 0;
    if (modalBox) {
        var modalRect = modalBox.getBoundingClientRect();
        rightBound = modalRect.right;
        leftBound = modalRect.left;
    }
    // 수평 위치: 버튼 중앙 기준, 모달/화면 밖으로 안 나가도록 보정
    var btnCenter = rect.left + rect.width / 2;
    var left = btnCenter - popupW / 2;
    if (left < leftBound + 8) left = leftBound + 8;
    if (left + popupW > rightBound - 8) left = rightBound - popupW - 8;
    if (showBelow) {
        popup.style.top = (rect.bottom + 8) + 'px';
        popup.classList.add('arrow-top');
    } else {
        popup.style.top = (rect.top - popupH - 8) + 'px';
        popup.classList.add('arrow-bottom');
    }
    popup.style.left = left + 'px';
    // 화살표 DOM 요소 추가 (버튼 중심에 맞춤)
    var arrow = document.createElement('div');
    arrow.className = 'cd-help-arrow';
    var arrowPos = btnCenter - left - 6;
    arrowPos = Math.max(10, Math.min(arrowPos, popupW - 22));
    arrow.style.left = arrowPos + 'px';
    popup.appendChild(arrow);
    // 외부 클릭 또는 스크롤 시 닫기
    var cleanup = function() {
        popup.remove();
        btn._cdHelpOpen = false;
        document.removeEventListener('click', closeHandler, true);
        var scrollTarget = document.getElementById('componentDetailBody');
        if (scrollTarget) scrollTarget.removeEventListener('scroll', cleanup);
    };
    var closeHandler = function(e) {
        if (!popup.contains(e.target) && e.target !== btn) cleanup();
    };
    setTimeout(function() {
        document.addEventListener('click', closeHandler, true);
        var scrollTarget = document.getElementById('componentDetailBody');
        if (scrollTarget) scrollTarget.addEventListener('scroll', cleanup, { once: true });
    }, 0);
}

function cdMetaCard(label, value) {
    var help = META_HELP[label];
    var helpBtn = help ? ' <button class="cd-help-btn" onclick="event.stopPropagation();toggleMetaHelp(this)" data-help="' + escAttr(help) + '">?</button>' : '';
    return '<div class="cd-meta-card"><div class="cd-meta-label">' + escHtml(label) + helpBtn + '</div><div class="cd-meta-value">' + escHtml(value) + '</div></div>';
}

var _cdSectionIdx = 0;

function renderCdSection(sec, defaultOpen, depth) {
    var idx = _cdSectionIdx++;
    var hasChildren = sec.children && sec.children.length > 0;
    var indent = depth > 0 ? ' style="margin-left:' + (depth * 8) + 'px"' : '';

    var h = '<div class="cd-section"' + indent + '>';
    // 헤더
    h += '<div class="cd-section-hdr" onclick="toggleCdSection(' + idx + ')">';
    h += '<span class="cd-arrow' + (defaultOpen ? ' open' : '') + '" id="cdArrow' + idx + '">&#9654;</span> ';
    if (sec.severity === 'warning') h += '<span class="cd-badge cd-badge-warning">경고</span> ';
    if (sec.severity === 'error') h += '<span class="cd-badge cd-badge-error">오류</span> ';
    h += escHtml(trTitle(sec.title) || '섹션');
    if (hasChildren) h += ' <span style="color:var(--text-secondary);font-size:11px;font-weight:400">(' + sec.children.length + ')</span>';
    h += '</div>';

    // 본문
    h += '<div class="cd-section-body' + (defaultOpen ? ' open' : '') + '" id="cdBody' + idx + '">';

    // 자체 콘텐츠
    if (sec.description) {
        h += '<div class="cd-text" style="margin-bottom:10px">' + escHtml(trText(sec.description)) + '</div>';
    }

    if (sec.type === 'TABLE' && sec.tables) {
        for (var t = 0; t < sec.tables.length; t++) {
            h += renderCdTable(sec.tables[t]);
        }
    } else if (sec.type === 'TEXT' && sec.textContent) {
        var text = trText(sec.textContent);
        var lines = text.split('\n');
        var hasList = false;
        var textPart = '';
        var listItems = [];
        for (var li = 0; li < lines.length; li++) {
            if (lines[li].indexOf('- ') === 0) {
                hasList = true;
                listItems.push(lines[li].substring(2));
            } else {
                textPart += (textPart ? ' ' : '') + lines[li];
            }
        }
        if (textPart) h += '<div class="cd-text">' + escHtml(textPart) + '</div>';
        if (hasList) {
            h += '<ul class="cd-list">';
            for (var li2 = 0; li2 < listItems.length; li2++) {
                h += '<li>' + escHtml(listItems[li2]) + '</li>';
            }
            h += '</ul>';
        }
    }

    // 하위 섹션 재귀 렌더링
    if (hasChildren) {
        h += '<div style="margin-top:8px">';
        for (var ci = 0; ci < sec.children.length; ci++) {
            h += renderCdSection(sec.children[ci], false, depth + 1);
        }
        h += '</div>';
    }

    h += '</div></div>';
    return h;
}

function renderCdTable(tbl) {
    if (!tbl || !tbl.headers || tbl.headers.length === 0) return '';
    var h = '<table class="cd-table"><thead><tr>';
    for (var i = 0; i < tbl.headers.length; i++) {
        var align = (tbl.rightAligned && tbl.rightAligned[i]) ? ' class="num"' : '';
        h += '<th' + align + '>' + escHtml(trHeader(tbl.headers[i])) + '</th>';
    }
    h += '</tr></thead><tbody>';
    if (tbl.rows) {
        var maxRows = Math.min(tbl.rows.length, 100);
        for (var r = 0; r < maxRows; r++) {
            h += '<tr>';
            for (var c = 0; c < tbl.rows[r].length && c < tbl.headers.length; c++) {
                var align2 = (tbl.rightAligned && tbl.rightAligned[c]) ? ' class="num"' : '';
                h += '<td' + align2 + '>' + escHtml(tbl.rows[r][c]) + '</td>';
            }
            h += '</tr>';
        }
        if (tbl.rows.length > 100) {
            h += '<tr><td colspan="' + tbl.headers.length + '" style="text-align:center;color:var(--text-secondary);font-size:12px">... ' + (tbl.rows.length - 100) + '개 행 추가 (전체 보기는 원본 데이터 탭 사용)</td></tr>';
        }
    }
    h += '</tbody></table>';
    return h;
}

function toggleCdSection(idx) {
    var body = document.getElementById('cdBody' + idx);
    var arrow = document.getElementById('cdArrow' + idx);
    if (body.classList.contains('open')) {
        body.classList.remove('open');
        arrow.classList.remove('open');
    } else {
        body.classList.add('open');
        arrow.classList.add('open');
    }
}

function formatNum(n) {
    if (n === null || n === undefined) return '-';
    return n.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

// escHtml → Common.escHtml (common.js). 기존 textContent 버전은 `"` `'` 미처리 → Common 은 5 문자 escape 로 강화.
var escHtml = Common.escHtml;

function closeComponentDetail() {
    document.getElementById('componentDetailModal').classList.remove('open');
    document.querySelectorAll('.cd-help-popup').forEach(function(p) { p.remove(); });
}

function showReanalyzeModal() {
    document.getElementById('reanalyzeModal').classList.add('open');
}
function closeReanalyzeModal() {
    document.getElementById('reanalyzeModal').classList.remove('open');
}
function doReanalyze() {
    closeReanalyzeModal();
    var f = document.createElement('form');
    f.method = 'POST'; f.action = '/analyze/rerun/' + encodeURIComponent(FILENAME); f.style.display = 'none';
    Common.appendCsrfToForm(f);
    document.body.appendChild(f); f.submit();
}

// ── Download File Modal ───────────────────────────────
function showDownloadModal() {
    document.getElementById('dlModal').classList.add('open');
}
function closeDlModal() {
    document.getElementById('dlModal').classList.remove('open');
}
function doDlFile() {
    closeDlModal();
    window.location.href = '/download/' + encodeURIComponent(FILENAME);
}

// ── CSV Export ────────────────────────────────────────
function exportCSV() {
    if (!OBJ_NAMES || OBJ_NAMES.length === 0) { alert('No data to export'); return; }
    var csvFilename = FILENAME.replace(/\.[^.]+$/, '') + '_top_objects.csv';
    document.getElementById('csvModalFilename').textContent = csvFilename;
    document.getElementById('csvModalRows').textContent = OBJ_NAMES.length;
    document.getElementById('csvModal').classList.add('open');
}
function closeCsvModal() {
    document.getElementById('csvModal').classList.remove('open');
}
function doExportCSV() {
    closeCsvModal();
    var csv = 'Rank,Class Name,Objects,Retained Heap (bytes),% of Heap\n';
    for (var i = 0; i < OBJ_NAMES.length; i++) {
        csv += (i+1) + ',"' + OBJ_NAMES[i].replace(/"/g,'""') + '",' + OBJ_COUNTS[i] + ',' + OBJ_SIZES[i] + ',' + OBJ_PCTS[i].toFixed(2) + '\n';
    }
    var blob = new Blob([csv], {type:'text/csv'});
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url; a.download = FILENAME.replace(/\.[^.]+$/, '') + '_top_objects.csv';
    a.click(); URL.revokeObjectURL(url);
}

// ── Charts ────────────────────────────────────────────
(function initCharts() {
    if (typeof TOTAL_BYTES === 'undefined' || (USED_BYTES === 0 && FREE_BYTES === 0 && OBJ_NAMES.length === 0)) return;

    // ── Stacked Bar (Top Objects breakdown) ──
    buildStackedBar();

    // ── Pie Chart (MAT Style: Top Objects + Others) ──
    var pieCtx = document.getElementById('pieChart');
    if (pieCtx && OBJ_NAMES.length > 0) {
        var pieLabels = OBJ_NAMES.slice(0, 8).map(function(n) {
            var parts = n.split('.');
            var s = parts[parts.length-1] || n;
            return s.length > 28 ? s.slice(0,28) + '...' : s;
        });
        var pieSizes = OBJ_SIZES.slice(0, 8);
        var othersSize = TOTAL_BYTES - pieSizes.reduce(function(a,b){return a+b;}, 0);
        if (othersSize > 0) { pieLabels.push('Others'); pieSizes.push(othersSize); }

        var pieColors = ['#2563eb','#3b82f6','#60a5fa','#93c5fd','#0891b2','#06b6d4','#0d9488','#10b981','#d1d5db'];
        var pieClassCount = Math.min(OBJ_NAMES.length, 8);

        new Chart(pieCtx, {
            type: 'pie',
            data: { labels: pieLabels, datasets: [{ data: pieSizes, backgroundColor: pieColors, borderColor: '#fff', borderWidth: 2, hoverOffset: 8 }] },
            options: {
                responsive: true, maintainAspectRatio: true,
                onClick: function(evt, elements) {
                    if (!elements || elements.length === 0) return;
                    var idx = elements[0].index;
                    if (idx < pieClassCount) {
                        showComponentDetail(OBJ_NAMES[idx], idx);
                    }
                },
                onHover: function(evt, elements) {
                    if (!evt || !evt.native || !evt.native.target) return;
                    var clickable = elements && elements.length > 0 && elements[0].index < pieClassCount;
                    evt.native.target.style.cursor = clickable ? 'pointer' : 'default';
                },
                plugins: {
                    legend: { position:'right', onClick: function(e, item, legend) {
                            if (item && item.index < pieClassCount) {
                                showComponentDetail(OBJ_NAMES[item.index], item.index);
                            }
                        }, labels: { padding:8, font:{size:11}, boxWidth:12, color:'#374151',
                        generateLabels: function(chart) {
                            var ds = chart.data.datasets[0];
                            var total = ds.data.reduce(function(a,b){return a+b;},0);
                            return chart.data.labels.map(function(label, i) {
                                var pct = total > 0 ? ((ds.data[i]/total)*100).toFixed(1) : '0.0';
                                return { text: label + ' (' + pct + '%)', fillStyle: ds.backgroundColor[i], strokeStyle: '#fff', lineWidth: 1, index: i };
                            });
                        }
                    }},
                    tooltip: { backgroundColor:'rgba(15,23,42,.92)', padding:10,
                        callbacks: { label: function(ctx) { var mb=(ctx.parsed/1048576).toFixed(1); var tot=ctx.dataset.data.reduce(function(a,b){return a+b;},0); var pct=((ctx.parsed/tot)*100).toFixed(1); var hint = ctx.dataIndex < pieClassCount ? ' (클릭하여 상세 보기)' : ''; return ' '+ctx.label+': '+mb+' MB ('+pct+'%)'+hint; } }
                    }
                },
                animation: { duration:800, easing:'easeInOutQuart' }
            }
        });
    }

    // ── Bar Chart (Horizontal) ──
    var bCtx = document.getElementById('barChart');
    if (bCtx && OBJ_NAMES.length > 0) {
        var top10names = OBJ_NAMES.slice(0,10);
        var top10sizes = OBJ_SIZES.slice(0,10);
        var shortLabels = top10names.map(function(n) {
            var parts = n.split('.'); var s = parts[parts.length-1]||n;
            return s.length > 30 ? s.slice(0,30)+'...' : s;
        });
        var maxSize = Math.max.apply(null, top10sizes) || 1;
        var bgColors = top10sizes.map(function(s) {
            var ratio = s/maxSize;
            if (ratio > 0.6) return '#dc2626';
            if (ratio > 0.3) return '#d97706';
            return '#2563eb';
        });
        var sizesMB = top10sizes.map(function(s) { return (s/1048576).toFixed(2); });

        new Chart(bCtx, {
            type: 'bar',
            data: { labels: shortLabels, datasets: [{ label:'Retained Heap (MB)', data:sizesMB, backgroundColor:bgColors, borderRadius:4, hoverBackgroundColor:'#1e40af' }] },
            options: {
                indexAxis:'y', responsive:true, maintainAspectRatio:true,
                onClick: function(evt, elements) {
                    if (!elements || elements.length === 0) return;
                    var idx = elements[0].index;
                    if (idx >= 0 && idx < top10names.length) {
                        showComponentDetail(top10names[idx], idx);
                    }
                },
                onHover: function(evt, elements) {
                    if (!evt || !evt.native || !evt.native.target) return;
                    evt.native.target.style.cursor = (elements && elements.length > 0) ? 'pointer' : 'default';
                },
                plugins: {
                    legend:{display:false},
                    tooltip:{ backgroundColor:'rgba(15,23,42,.92)', padding:10,
                        callbacks:{ title:function(items){return top10names[items[0].dataIndex]||items[0].label;}, label:function(ctx){return ' '+ctx.parsed.x+' MB (클릭하여 상세 보기)';} }
                    }
                },
                scales: {
                    x:{ beginAtZero:true, grid:{color:'#f3f4f6'}, ticks:{color:'#6b7280',font:{size:11},callback:function(v){return v+' MB';}}, title:{display:true,text:'Retained Heap (MB)',color:'#374151',font:{size:12,weight:'600'}} },
                    y:{ grid:{display:false}, ticks:{color:'#1f2937',font:{size:11},autoSkip:false} }
                },
                animation:{duration:900,easing:'easeInOutQuart'}
            }
        });
    }

    // ── Treemap ──
    buildTreemap();
})();

// ── Treemap Builder ───────────────────────────────────
function buildTreemap() {
    var container = document.getElementById('treemap');
    if (!container || typeof OBJ_NAMES === 'undefined' || OBJ_NAMES.length === 0) return;

    var W = container.offsetWidth;
    var H = container.offsetHeight || 200;
    container.innerHTML = '';

    var items = [];
    var total = 0;
    for (var i = 0; i < OBJ_NAMES.length; i++) { items.push({name:OBJ_NAMES[i], size:OBJ_SIZES[i], pct:OBJ_PCTS[i], index:i, clickable:true}); total += OBJ_SIZES[i]; }
    if (TOTAL_BYTES > total) { items.push({name:'Others', size:TOTAL_BYTES-total, pct:((TOTAL_BYTES-total)/TOTAL_BYTES*100), clickable:false}); total = TOTAL_BYTES; }

    var colors = ['#2563eb','#3b82f6','#0891b2','#0d9488','#059669','#d97706','#dc2626','#7c3aed','#be185d','#475569','#94a3b8'];
    var tooltip = document.getElementById('treemapTooltip');

    // Simple squarified treemap
    function layout(items, x, y, w, h) {
        if (items.length === 0 || w <= 0 || h <= 0) return;
        if (items.length === 1) {
            createCell(items[0], x, y, w, h);
            return;
        }
        var totalSize = items.reduce(function(a,b){return a+b.size;},0);
        if (totalSize === 0) return;
        var half = totalSize / 2; var sum = 0; var splitIdx = 0;
        for (var i = 0; i < items.length; i++) {
            sum += items[i].size;
            if (sum >= half) { splitIdx = i + 1; break; }
        }
        if (splitIdx === 0) splitIdx = 1;
        if (splitIdx >= items.length) splitIdx = items.length - 1;

        var ratio = sum / totalSize;
        if (w >= h) {
            var sw = Math.round(w * ratio);
            layout(items.slice(0, splitIdx), x, y, sw, h);
            layout(items.slice(splitIdx), x + sw, y, w - sw, h);
        } else {
            var sh = Math.round(h * ratio);
            layout(items.slice(0, splitIdx), x, y, w, sh);
            layout(items.slice(splitIdx), x, y + sh, w, h - sh);
        }
    }

    function createCell(item, x, y, w, h) {
        var div = document.createElement('div');
        div.className = 'treemap-cell';
        div.style.left = x + 'px'; div.style.top = y + 'px';
        div.style.width = Math.max(w-1,0) + 'px'; div.style.height = Math.max(h-1,0) + 'px';
        div.style.background = colors[container.children.length % colors.length];
        if (w > 50 && h > 28) {
            var parts = item.name.split('.'); var short = parts[parts.length-1] || item.name;
            if (short.length > 20) short = short.slice(0,18) + '..';
            var label = document.createElement('div');
            label.className = 'cell-label';
            label.textContent = short;
            if (w > 80 && h > 44) {
                label.textContent += '\n' + (item.size/1048576).toFixed(1) + ' MB';
                label.style.fontSize = '10px';
            }
            div.appendChild(label);
        }
        div.addEventListener('mouseenter', function(e) {
            tooltip.style.display = 'block';
            tooltip.innerHTML = '<div class="tt-name">' + escHtml(item.name) + '</div>'
                + '<div class="tt-size">' + (item.size/1048576).toFixed(2) + ' MB</div>'
                + '<div class="tt-pct">' + item.pct.toFixed(2) + '% of heap</div>'
                + (item.clickable ? '<div class="tt-hint" style="margin-top:4px;font-size:10px;opacity:.75">클릭하여 상세 보기</div>' : '');
        });
        div.addEventListener('mousemove', function(e) {
            positionTooltip(tooltip, e);
        });
        div.addEventListener('mouseleave', function() { tooltip.style.display = 'none'; });
        if (item.clickable) {
            div.style.cursor = 'pointer';
            div.title = '클릭하여 상세 보기';
            div.addEventListener('click', function() {
                if (tooltip) tooltip.style.display = 'none';
                showComponentDetail(item.name, item.index);
            });
        }
        container.appendChild(div);
    }

    layout(items, 0, 0, W, H);
}

// 두 번째 escHtml 선언(중복) — Common.escHtml 별칭으로 통일.
var escHtml = Common.escHtml;

// 툴팁 위치 계산 — 오른쪽 넘침 시 왼쪽으로, 하단 넘침 시 위쪽으로
function positionTooltip(tt, e) {
    var ttW = tt.offsetWidth || 320;
    var ttH = tt.offsetHeight || 60;
    var mx = e.clientX, my = e.clientY;
    var left = mx + 14;
    var top = my - 10;
    if (left + ttW > window.innerWidth - 8) left = mx - ttW - 14;
    if (left < 8) left = 8;
    if (top + ttH > window.innerHeight - 8) top = my - ttH - 10;
    if (top < 8) top = 8;
    tt.style.left = left + 'px';
    tt.style.top = top + 'px';
}

// ── Stacked Bar Builder ───────────────────────────────
function buildStackedBar() {
    var container = document.getElementById('stackedBar');
    var legend = document.getElementById('stackedLegend');
    if (!container || typeof OBJ_NAMES === 'undefined' || TOTAL_BYTES <= 0) return;

    var colors = ['#2563eb','#0891b2','#059669','#d97706','#dc2626','#7c3aed','#be185d','#0d9488','#6366f1','#475569'];
    var tooltip = document.getElementById('treemapTooltip');
    var segments = [];
    var usedTotal = 0;

    for (var i = 0; i < OBJ_NAMES.length && i < 10; i++) {
        var pct = (OBJ_SIZES[i] / TOTAL_BYTES * 100);
        usedTotal += OBJ_SIZES[i];
        var parts = OBJ_NAMES[i].split('.');
        var shortName = parts[parts.length-1] || OBJ_NAMES[i];
        if (shortName.length > 20) shortName = shortName.slice(0,18) + '..';
        segments.push({ name: OBJ_NAMES[i], shortName: shortName, size: OBJ_SIZES[i], pct: pct, color: colors[i % colors.length], index: i, clickable: true });
    }

    var otherSize = TOTAL_BYTES - usedTotal;
    if (otherSize > 0) {
        segments.push({ name: 'Others', shortName: 'Others', size: otherSize, pct: (otherSize / TOTAL_BYTES * 100), color: '#d1d5db', clickable: false });
    }

    container.innerHTML = '';
    legend.innerHTML = '';

    segments.forEach(function(seg) {
        // Segment bar
        var div = document.createElement('div');
        div.className = 'stacked-segment';
        div.style.width = '0%';
        div.style.background = seg.color;

        if (seg.pct > 5) {
            var label = document.createElement('span');
            label.className = 'seg-label';
            label.textContent = seg.shortName + ' ' + seg.pct.toFixed(1) + '%';
            if (seg.name === 'Others') label.style.color = '#6b7280';
            div.appendChild(label);
        }

        div.addEventListener('mouseenter', function(e) {
            tooltip.style.display = 'block';
            tooltip.innerHTML = '<div class="tt-name">' + escHtml(seg.name) + '</div>'
                + '<div class="tt-size">' + (seg.size/1048576).toFixed(2) + ' MB</div>'
                + '<div class="tt-pct">' + seg.pct.toFixed(2) + '% of total heap</div>'
                + (seg.clickable ? '<div class="tt-hint" style="margin-top:4px;font-size:10px;opacity:.75">클릭하여 상세 보기</div>' : '');
        });
        div.addEventListener('mousemove', function(e) {
            positionTooltip(tooltip, e);
        });
        div.addEventListener('mouseleave', function() { tooltip.style.display = 'none'; });

        if (seg.clickable) {
            div.style.cursor = 'pointer';
            div.title = '클릭하여 상세 보기';
            div.addEventListener('click', function() {
                if (tooltip) tooltip.style.display = 'none';
                showComponentDetail(seg.name, seg.index);
            });
        } else {
            div.style.cursor = 'default';
        }

        container.appendChild(div);

        // Animate width
        setTimeout(function() { div.style.width = seg.pct + '%'; }, 100);

        // Legend item
        var li = document.createElement('div');
        li.className = 'stacked-legend-item';
        li.innerHTML = '<span class="stacked-legend-dot" style="background:' + seg.color + '"></span>'
            + '<span>' + escHtml(seg.shortName) + ' <strong>' + seg.pct.toFixed(1) + '%</strong> (' + (seg.size/1048576).toFixed(1) + ' MB)</span>';
        if (seg.clickable) {
            li.style.cursor = 'pointer';
            li.title = '클릭하여 상세 보기';
            li.addEventListener('click', function() {
                if (tooltip) tooltip.style.display = 'none';
                showComponentDetail(seg.name, seg.index);
            });
        }
        legend.appendChild(li);
    });
}

// ── Progress Bar Animation ────────────────────────────
setTimeout(function() {
    document.querySelectorAll('.pct-bar-fill').forEach(function(bar) {
        var w = bar.style.width;
        bar.style.width = '0%'; bar.style.transition = 'none';
        requestAnimationFrame(function() { requestAnimationFrame(function() {
            bar.style.transition = 'width .8s ease'; bar.style.width = w;
        }); });
    });
}, 200);

// ── Log Lazy Loading ──────────────────────────────────
var LOG_CHUNK = 10000;
var logOffset = 0, logLoaded = false, logLoading = false;
var _lc = null, _lst = null, _blm = null, _bla = null;

function _getLogEls() {
    if (!_lc)  _lc  = document.getElementById('logContent');
    if (!_lst) _lst = document.getElementById('logStatusTxt');
    if (!_blm) _blm = document.getElementById('btnLoadMore');
    if (!_bla) _bla = document.getElementById('btnLoadAll');
}

function loadThreadStacks() {
    var el = document.getElementById('threadStackContent');
    var status = document.getElementById('threadStackStatus');
    status.textContent = 'Loading...';
    console.log('[ThreadStacks] Loading thread stacks for:', FILENAME);
    var startTime = Date.now();
    fetch('/report/' + encodeURIComponent(FILENAME) + '/thread-stacks')
        .then(function(r) { return r.text(); })
        .then(function(text) {
            el.textContent = text;
            var lineCount = text.split('\n').length;
            var elapsed = Date.now() - startTime;
            status.textContent = lineCount + ' lines';
            console.log('[ThreadStacks] Loaded successfully:', lineCount, 'lines,', text.length, 'bytes,', elapsed + 'ms');
        })
        .catch(function(err) {
            status.textContent = 'Failed to load';
            console.error('[ThreadStacks] Failed to load:', err);
        });
}

function copyThreadStacks() {
    var el = document.getElementById('threadStackContent');
    var text = el.textContent;
    if (!text || text === 'Click Load to view thread stacks...') return;
    var status = document.getElementById('threadStackStatus');
    var prev = status.textContent;
    var btn = document.getElementById('btnCopyStacks');
    function onSuccess() { btn.textContent = 'Copied!'; setTimeout(function() { btn.textContent = 'Copy'; }, 1500); }
    function onFail() { btn.textContent = 'Failed'; setTimeout(function() { btn.textContent = 'Copy'; }, 1500); }
    if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(onSuccess).catch(function() { fallbackCopy(text) ? onSuccess() : onFail(); });
    } else {
        fallbackCopy(text) ? onSuccess() : onFail();
    }
}
function fallbackCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.cssText = 'position:fixed;left:-9999px;top:-9999px';
    document.body.appendChild(ta);
    ta.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch(e) {}
    document.body.removeChild(ta);
    return ok;
}

function loadLog() {
    if (logLoaded || logLoading) return;
    logLoading = true; _getLogEls();
    _lst.textContent = 'Loading...';
    fetch('/analyze/log/' + encodeURIComponent(FILENAME) + '?offset=0&limit=' + LOG_CHUNK)
        .then(function(r) {
            var total = parseInt(r.headers.get('X-Log-Total-Length')||'0',10);
            var more = r.headers.get('X-Log-Has-More') === 'true';
            return r.text().then(function(t) { return {t:t,total:total,more:more}; });
        })
        .then(function(d) {
            _lc.textContent = d.t;
            logOffset = d.t.length; logLoading = false; logLoaded = true;
            _updateLogStatus(logOffset, d.total, d.more);
            requestAnimationFrame(function() { _lc.scrollTop = 0; });
        })
        .catch(function(e) { _lc.textContent = 'Load failed: ' + e.message; logLoading = false; });
}

function loadMoreLog() {
    if (logLoading) return;
    logLoading = true; _getLogEls(); _lst.textContent = 'Loading more...';
    fetch('/analyze/log/' + encodeURIComponent(FILENAME) + '?offset=' + logOffset + '&limit=' + LOG_CHUNK)
        .then(function(r) {
            var total = parseInt(r.headers.get('X-Log-Total-Length')||'0',10);
            var more = r.headers.get('X-Log-Has-More') === 'true';
            return r.text().then(function(t) { return {t:t,total:total,more:more}; });
        })
        .then(function(d) {
            _lc.appendChild(document.createTextNode(d.t));
            logOffset += d.t.length; logLoading = false;
            _updateLogStatus(logOffset, d.total, d.more);
            requestAnimationFrame(function() { _lc.scrollTop = 999999; });
        })
        .catch(function(e) { _lst.textContent = 'Failed: ' + e.message; logLoading = false; });
}

function loadAllLog() {
    if (logLoading) return;
    if (!confirm('Load entire log (' + Math.round(LOG_TOTAL_LEN/1024) + ' KB)?')) return;
    logLoading = true; _getLogEls(); _chunkAll();
}
function _chunkAll() {
    if (logOffset >= LOG_TOTAL_LEN) { logLoading = false; _getLogEls(); _lst.textContent = 'Fully loaded'; _blm.disabled = true; _bla.disabled = true; return; }
    fetch('/analyze/log/' + encodeURIComponent(FILENAME) + '?offset=' + logOffset + '&limit=' + LOG_CHUNK)
        .then(function(r) { return r.text(); })
        .then(function(t) { _lc.appendChild(document.createTextNode(t)); logOffset += t.length; _getLogEls(); _lst.textContent = Math.round(logOffset/1024)+'/'+Math.round(LOG_TOTAL_LEN/1024)+' KB'; setTimeout(_chunkAll, 0); })
        .catch(function(e) { _lst.textContent = 'Failed: ' + e.message; logLoading = false; });
}

function _updateLogStatus(loaded, total, hasMore) {
    _getLogEls();
    _lst.textContent = Math.round(loaded/1024) + ' KB / ' + Math.round(total/1024) + ' KB';
    _blm.disabled = !hasMore; _bla.disabled = !hasMore;
}

function scrollLogBottom() { if (_lc) requestAnimationFrame(function() { _lc.scrollTop = 999999; }); }

// ── Resize handler for treemap ────────────────────────
var resizeTimer;
window.addEventListener('resize', function() {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function() { buildTreemap(); }, 300);
});

// ═══════════════════════════════════════════════════════
// AI INSIGHT MODULE — v3.0
// 1. 결과 저장/불러오기 (경로 표시)  2. 개선된 디자인
// 3. 진행 단계 표시                  4. 이탈 경고 + 방어 로직
// 5. 구조화 로그                     6. 상세 에러 분류 + 힌트
// ═══════════════════════════════════════════════════════

var _aiAnalysisInProgress = false;  // [4] 이탈 방어용 플래그
var _aiElapsedTimer       = null;   // 경과 시간 타이머
var _aiElapsedStart       = 0;
var _aiSavedPath          = null;   // [1] 저장된 파일 경로
var _aiUnsavedPayload     = null;   // 자동 저장 실패 시 보관해둔 인사이트 데이터 (수동 재시도용)

// ── [4] 브라우저 이탈 경고 (새로고침/탭닫기/주소직접입력) ─────────
window.addEventListener('beforeunload', function(e) {
    if (_aiAnalysisInProgress) {
        var msg = 'AI 분석이 진행 중입니다. 페이지를 벗어나면 분석이 중단될 수 있습니다.';
        e.preventDefault();
        e.returnValue = msg;
        return msg;
    }
});

// ── [4] SPA 링크 이탈 가로채기 (a 태그 클릭) ───────────────────
document.addEventListener('click', function(e) {
    if (!_aiAnalysisInProgress) return;
    var a = e.target.closest('a[href]');
    if (!a) return;
    var href = a.getAttribute('href');
    if (!href || href === '#' || href.startsWith('javascript:')) return;
    e.preventDefault();
    e.stopPropagation();
    showLeaveWarning(href);
}, true);

// ── [4] history.pushState 가로채기 ───────────────────────────
(function() {
    var origPush = history.pushState;
    history.pushState = function(state, title, url) {
        if (_aiAnalysisInProgress && url && url !== window.location.href) {
            showLeaveWarning(String(url));
            return;
        }
        return origPush.apply(history, arguments);
    };
    var origReplace = history.replaceState;
    history.replaceState = function(state, title, url) {
        if (_aiAnalysisInProgress && url && url !== window.location.href) {
            showLeaveWarning(String(url));
            return;
        }
        return origReplace.apply(history, arguments);
    };
})();

function showLeaveWarning(pendingHref) {
    var overlay = document.getElementById('aiLeaveWarning');
    if (!overlay) return;
    overlay.classList.add('show');
    overlay._pendingHref = pendingHref;
}
function confirmLeave() {
    _aiAnalysisInProgress = false;
    var overlay = document.getElementById('aiLeaveWarning');
    if (overlay) overlay.classList.remove('show');
    var href = overlay ? overlay._pendingHref : null;
    if (href) {
        window.location.href = href;
    } else {
        window.history.back();
    }
}
function cancelLeave() {
    var overlay = document.getElementById('aiLeaveWarning');
    if (overlay) overlay.classList.remove('show');
}

// ── [1] 저장 경로 팝업 표시 ──────────────────────────────────
function showSavedPathInfo() {
    if (_aiSavedPath) {
        alert('AI 인사이트 저장 위치: Database (MariaDB)\n\n삭제하기 전까지 언제든 불러올 수 있습니다.');
    }
}

// ── 초기화 ──────────────────────────────────────────────────
function initAiPanel() {
    // LLM 활성화 상태 확인
    fetch('/api/settings').then(function(r){ return r.json(); }).then(function(d) {
        var enabled = d.llm && d.llm.enabled;
        // 채팅 FAB 표시/숨김
        var fab = document.getElementById('aiChatFab');
        if (fab) fab.style.display = enabled ? '' : 'none';
        // 모델명 뱃지
        if (enabled && d.llm.model) {
            _aiChatModel = d.llm.model;
            var badge = document.getElementById('aiChatModelBadge');
            if (badge) badge.textContent = d.llm.model;
        }
        if (!enabled) {
            var btn = document.getElementById('aiStartBtn');
            if (btn) { btn.disabled = true; btn.style.opacity = '.45'; btn.style.cursor = 'not-allowed'; }
            var msg = document.getElementById('aiDisabledMsg');
            if (msg) { msg.style.removeProperty('display'); msg.style.display = 'block'; }
        }
    }).catch(function(){});

    // [1] 저장된 AI 인사이트 자동 로드
    if (typeof FILENAME !== 'undefined' && FILENAME) {
        fetch('/api/llm/insight/' + encodeURIComponent(FILENAME))
        .then(function(r){ return r.json(); })
        .then(function(d) {
            if (d.found) {
                console.log('[AI-Insight] 저장된 인사이트 로드 성공 — severity=' + d.severity);
                if (d.savedTo || d.savedPath) _aiSavedPath = d.savedTo || d.savedPath;
                showAiResult(d, true);
            }
        }).catch(function(e){
            console.warn('[AI-Insight] 저장된 인사이트 로드 실패:', e.message);
        });
    }
}

// ── 상태 관리 ─────────────────────────────────────────────────
function setAiState(state) {
    ['NotAnalyzed','Loading','Result','Error'].forEach(function(s) {
        var el = document.getElementById('aiState' + s);
        if (el) el.style.display = 'none';
    });
    var target = document.getElementById('aiState' + state);
    if (target) target.style.display = '';
    // 헤더 액션 버튼 표시/숨김
    var actions = document.getElementById('aiHeaderActions');
    if (actions) actions.style.display = (state === 'Result') ? 'flex' : 'none';
}

function updateAiBadges(state) {
    var badgeOn   = document.getElementById('aiHeaderBadge');
    var badgeOff  = document.getElementById('aiHeaderBadgeOff');
    var panelBadge = document.getElementById('aiPanelBadge');
    var cfg = {
        none:      { on:false, badge:'미분석',   bg:'#F3F4F6', color:'#9CA3AF', border:'#E5E7EB' },
        analyzing: { on:false, badge:'분석중…',  bg:'#EFF6FF', color:'#2563EB', border:'#BFDBFE' },
        done:      { on:true,  badge:'완료',     bg:'#DBEAFE', color:'#1D4ED8', border:'#93C5FD' },
        error:     { on:false, badge:'실패',     bg:'#FEE2E2', color:'#DC2626', border:'#FECACA' }
    };
    var c = cfg[state] || cfg.none;
    if (badgeOn)  badgeOn.style.display  = c.on ? '' : 'none';
    if (badgeOff) badgeOff.style.display = c.on ? 'none' : '';
    if (panelBadge) {
        panelBadge.textContent = c.badge;
        panelBadge.style.background  = c.bg;
        panelBadge.style.color       = c.color;
        panelBadge.style.borderColor = c.border;
    }
    // 사이드바 + 배너에 복제된 모든 aiNavStatus 요소 업데이트
    var navStatuses = document.querySelectorAll('.ai-nav-status');
    navStatuses.forEach(function(el) {
        el.textContent = c.badge;
        el.style.background = c.bg;
        el.style.color      = c.color;
    });
}

// ── [3] 진행 단계 업데이트 ────────────────────────────────────
var _STEP_MSGS = [
    null,
    { msg:'데이터 수집 중…',    detail:'분석 페이지에서 KPI · 누수 의심 항목 · 메모리 상위 클래스를 수집합니다.',       pct:15 },
    { msg:'프롬프트 구성 중…',  detail:'수집된 분석 데이터를 LLM 전용 구조화 텍스트로 변환합니다.',                   pct:35 },
    { msg:'LLM 분석 요청 중…',  detail:'AI 모델이 힙 덤프를 분석합니다. 모델에 따라 15~40초가 소요될 수 있습니다.', pct:65 },
    { msg:'결과 저장 중…',      detail:'분석 결과를 서버에 영속화하고 화면에 렌더링합니다.',                          pct:92 }
];

function setAiStep(stepNum) {
    for (var i = 1; i <= 4; i++) {
        var circle = document.querySelector('#aiStep' + i + ' .ai-step-circle');
        var stepEl = document.getElementById('aiStep' + i);
        var lineEl = document.getElementById('aiStepLine' + (i < 4 ? i : 3));
        if (!circle) continue;
        circle.classList.remove('ai-step-active', 'ai-step-done');
        if (stepEl) stepEl.classList.remove('active', 'done');
        if (lineEl) lineEl.classList.remove('done');
        if (i < stepNum) {
            circle.classList.add('ai-step-done');
            circle.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
            if (stepEl) stepEl.classList.add('done');
        } else if (i === stepNum) {
            circle.classList.add('ai-step-active');
            circle.textContent = String(i);
            if (stepEl) stepEl.classList.add('active');
        } else {
            circle.textContent = String(i);
        }
    }
    // 완료된 연결선 표시
    for (var j = 1; j <= 3; j++) {
        var line = document.getElementById('aiStepLine' + j);
        if (line) line.classList.toggle('done', j < stepNum);
    }
    var s = _STEP_MSGS[stepNum];
    if (s) {
        var msgEl    = document.getElementById('aiLoadingMsg');
        var detailEl = document.getElementById('aiLoadingDetail');
        var progEl   = document.getElementById('aiProgressBar');
        if (msgEl)    msgEl.textContent = s.msg;
        if (detailEl) detailEl.textContent = s.detail;
        if (progEl)   progEl.style.width = s.pct + '%';
    }
}

function startElapsedTimer() {
    _aiElapsedStart = Date.now();
    _aiElapsedTimer = setInterval(function() {
        var sec = Math.floor((Date.now() - _aiElapsedStart) / 1000);
        var el = document.getElementById('aiElapsedTime');
        if (el) el.textContent = '경과 시간: ' + sec + '초';
    }, 1000);
}
function stopElapsedTimer() {
    if (_aiElapsedTimer) { clearInterval(_aiElapsedTimer); _aiElapsedTimer = null; }
}

// ── 분석 시작 ─────────────────────────────────────────────────
function startAiAnalysis() {
    // 재분석 여부 판별 (이미 결과가 있으면 재분석)
    var isReanalyze = document.getElementById('aiStateResult') &&
                      document.getElementById('aiStateResult').style.display !== 'none';
    var title = document.getElementById('aiConfirmTitle');
    var desc = document.getElementById('aiConfirmDesc');
    var reWarn = document.getElementById('aiConfirmReanalyzeWarn');
    if (isReanalyze) {
        title.textContent = 'AI 재분석';
        desc.textContent = '기존 AI 분석 결과를 폐기하고 LLM API를 다시 호출하여 새로 분석합니다.';
        reWarn.style.display = '';
    } else {
        title.textContent = 'AI 분석 시작';
        desc.textContent = 'LLM API를 호출하여 힙 덤프 분석 결과를 AI가 종합 분석합니다.';
        reWarn.style.display = 'none';
    }
    var costWarn = document.getElementById('aiConfirmCostWarn');
    if (costWarn) {
        costWarn.style.display = '';
        fetch('/api/settings').then(function(r) { return r.json(); }).then(function(s) {
            var llm = s.llm || {};
            if (llm.provider === 'custom' && llm.sslVerify === false) {
                costWarn.style.display = 'none';
            }
        }).catch(function() {});
    }
    document.getElementById('aiConfirmModal').classList.add('open');
}

function closeAiConfirmModal() {
    document.getElementById('aiConfirmModal').classList.remove('open');
}

function confirmAiAnalysis() {
    closeAiConfirmModal();
    _doStartAiAnalysis();
}

function _doStartAiAnalysis() {
    _aiAnalysisInProgress = true;
    _aiSavedPath = null;
    _aiUnsavedPayload = null;
    setAiState('Loading');
    updateAiBadges('analyzing');
    setAiStep(1);
    startElapsedTimer();

    // 저장 배너 숨기기
    var savedBanner = document.getElementById('aiSavedPathBanner');
    var saveErrBanner = document.getElementById('aiSaveErrorBanner');
    if (savedBanner) savedBanner.style.display = 'none';
    if (saveErrBanner) saveErrBanner.style.display = 'none';

    // STEP 1: 데이터 수집
    var data = collectAnalysisData();

    // [5] 로그
    console.log('[AI-Insight] 분석 시작 — file=' + (data.filename || '') +
        ', kpis=' + (data.kpis ? data.kpis.length : 0) +
        ', suspects=' + (data.suspects ? data.suspects.length : 0) +
        ', topConsumers=' + (data.topConsumers ? data.topConsumers.length : 0) +
        ', jvmXms=' + (data.jvmXms || 'N/A') + ', jvmXmx=' + (data.jvmXmx || 'N/A'));

    // STEP 2: 프롬프트 구성
    setTimeout(function() {
        setAiStep(2);
        var prompt = buildAnalysisPrompt(data);

        // [5] 로그
        console.log('[AI-Insight] 프롬프트 구성 완료 — promptLen=' + prompt.length);

        // STEP 3: LLM 요청
        setTimeout(function() {
            setAiStep(3);
            requestAiAnalysis(data, prompt);
        }, 400);
    }, 300);
}

// ── 데이터 수집 ───────────────────────────────────────────────
function collectAnalysisData() {
    var data = { filename: typeof FILENAME !== 'undefined' ? FILENAME : '' };
    data.kpis = [];
    document.querySelectorAll('.kpi-item').forEach(function(item) {
        var label = item.querySelector('.kpi-label');
        var value = item.querySelector('.kpi-value');
        if (label && value) data.kpis.push({ label: label.textContent.trim(), value: value.textContent.trim() });
    });
    data.suspects = [];
    document.querySelectorAll('#panel-suspects .suspect-item').forEach(function(s) {
        var title = s.querySelector('.suspect-title-text');
        var desc  = s.querySelector('.suspect-desc');
        if (title) data.suspects.push({
            title: title.textContent.trim(),
            desc:  desc ? desc.textContent.trim().substring(0, 600) : ''
        });
    });
    // 컬럼: [0]순위, [1]ClassName, [2]Objects수, [3]RetainedHeap, [4]% of Total
    data.topConsumers = [];
    document.querySelectorAll('#topObjectsTable tbody tr').forEach(function(r, i) {
        if (i >= 10) return;
        var cells = r.querySelectorAll('td');
        if (cells.length >= 5) {
            var cnCell = cells[1].querySelector('.class-name-cell');
            data.topConsumers.push({
                rank:      cells[0].textContent.trim(),
                className: cnCell ? cnCell.textContent.trim() : cells[1].textContent.trim(),
                count:     cells[2].textContent.trim(),
                size:      cells[3].textContent.trim(),
                pct:       cells[4].textContent.trim()
            });
        }
    });
    var xmsEl = document.getElementById('aiJvmXms');
    var xmxEl = document.getElementById('aiJvmXmx');
    data.jvmXms = xmsEl ? xmsEl.value.trim() : '';
    data.jvmXmx = xmxEl ? xmxEl.value.trim() : '';
    return data;
}

// ── 프롬프트 생성 ─────────────────────────────────────────────
function buildAnalysisPrompt(data) {
    var p = [];
    p.push('Java 힙 덤프 분석 결과를 해석해주세요. 파일: ' + data.filename);
    p.push('');
    if (data.jvmXms || data.jvmXmx) {
        p.push('== JVM 힙 설정 ==');
        if (data.jvmXms) p.push('-Xms (초기 힙 크기): ' + data.jvmXms);
        if (data.jvmXmx) p.push('-Xmx (최대 힙 크기): ' + data.jvmXmx);
        p.push('※ 위 JVM 설정과 실제 힙 사용량을 비교하여 힙 여유 공간, 메모리 압박 수준, Xmx 증설 필요 여부를 판단해주세요.');
        p.push('');
    }
    if (data.kpis && data.kpis.length) {
        p.push('== 힙 통계 ==');
        data.kpis.forEach(function(k) { p.push(k.label + ': ' + k.value); });
        p.push('');
    }
    if (data.topConsumers && data.topConsumers.length) {
        p.push('== Top Memory Consumers (' + data.topConsumers.length + '개) ==');
        data.topConsumers.forEach(function(c, i) {
            p.push((i+1) + '. ' + c.className + ' — 크기: ' + c.size + ', 객체수: ' + c.count + ' (' + c.pct + ')');
        });
        p.push('');
    }
    if (data.suspects && data.suspects.length) {
        p.push('== Leak Suspects ==');
        data.suspects.forEach(function(s, i) {
            p.push('Suspect ' + (i+1) + ': ' + s.title);
            if (s.desc) p.push('  ' + s.desc);
        });
        p.push('');
    }
    p.push('위 데이터를 기반으로 아래 JSON 형식으로만 응답하세요.');
    p.push('절대 마크다운 코드블록(```)을 사용하지 마세요. 순수 JSON만 반환하세요.');
    var schema = '{"summary":"전체 요약(2-3문장)","rootCause":"근본 원인 분석(상세)","recommendations":"최우선 조치 3개만 우선순위 순서로 (정확히 1. 2. 3. 형식, 4개 이상 금지)","severity":"Critical|High|Medium|Low 중 하나","severityDesc":"위험도 판단 근거"';
    if (data.jvmXms || data.jvmXmx) {
        schema += ',"jvmAdvice":"JVM 힙 설정 분석: 현재 설정 대비 사용량 평가, 힙 여유 공간 비율, Xmx 증설/축소 권고 및 권장 값"';
    }
    schema += '}';
    p.push(schema);
    return p.join('\n');
}

// ── LLM 요청 ──────────────────────────────────────────────────
function requestAiAnalysis(data, prompt) {
    fetch('/api/llm/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: prompt, filename: data.filename, save: true })
    })
    .then(function(r) {
        if (!r.ok && r.status !== 400) throw new Error('HTTP ' + r.status + ' ' + r.statusText);
        return r.json();
    })
    .then(function(result) {
        // STEP 4: 결과 저장
        setAiStep(4);
        var progEl = document.getElementById('aiProgressBar');
        if (progEl) progEl.style.width = '100%';

        setTimeout(function() {
            stopElapsedTimer();
            _aiAnalysisInProgress = false;
            if (result.success) {
                // [1] 저장 경로 보관
                if (result.savedTo || result.savedPath) _aiSavedPath = result.savedTo || result.savedPath;
                showAiResult(result, !!result.saved);
                // [5] 성공 로그
                var data_map = result.data || {};
                console.log('[AI-Insight] 분석 성공 — severity=' + data_map.severity +
                    ', saved=' + result.saved +
                    ', path=' + (result.savedPath || 'N/A') +
                    ', latency=' + result.latencyMs + 'ms');
            } else {
                showAiError(result.error || 'AI 분석 실패', result.errorCode);
                // [5] 실패 로그
                console.warn('[AI-Insight] 분석 실패 — errorCode=' + result.errorCode + ', error=' + result.error);
            }
        }, 500);
    })
    .catch(function(e) {
        stopElapsedTimer();
        _aiAnalysisInProgress = false;
        var msg = e.message || String(e);
        var code = 'CLIENT_ERROR';
        // [6] 네트워크 에러 분류
        if (msg.indexOf('Failed to fetch') !== -1 || msg.indexOf('NetworkError') !== -1 || msg.indexOf('ERR_') !== -1) {
            msg = '네트워크 오류: 서버에 연결할 수 없습니다. 서버가 실행 중인지 확인하세요.';
            code = 'NETWORK_ERROR';
        } else if (msg.indexOf('timeout') !== -1 || msg.indexOf('408') !== -1 || msg.indexOf('504') !== -1) {
            msg = '요청 타임아웃: LLM 응답 대기 시간이 초과되었습니다. Settings에서 타임아웃을 늘리거나 빠른 모델을 선택하세요.';
            code = 'TIMEOUT';
        } else if (msg.indexOf('HTTP 5') !== -1) {
            msg = '서버 오류: LLM 서버에서 오류가 발생했습니다. 잠시 후 다시 시도하세요. (' + msg + ')';
            code = 'SERVER_ERROR';
        }
        console.warn('[AI-Insight] 클라이언트 오류 — code=' + code + ', msg=' + msg);
        showAiError(msg, code);
    });
}

// ── [1] AI 인사이트 삭제 ───────────────────────────────────────
function deleteAiInsight() {
    if (!confirm('저장된 AI 인사이트를 삭제하시겠습니까?\n삭제 후 재분석 전까지 결과를 볼 수 없습니다.')) return;
    var fn = typeof FILENAME !== 'undefined' ? FILENAME : '';
    if (!fn) return;
    fetch('/api/llm/insight/' + encodeURIComponent(fn), { method: 'DELETE' })
    .then(function(r){ return r.json(); })
    .then(function(d) {
        if (d.success) {
            _aiSavedPath = null;
            setAiState('NotAnalyzed');
            updateAiBadges('none');
            renderOverviewInsight(null); // Overview 요약 카드 미생성 상태로 복귀
            console.log('[AI-Insight] 인사이트 삭제 완료');
        } else {
            alert('삭제에 실패했습니다. 파일이 이미 없거나 서버 오류입니다.');
        }
    }).catch(function(e){ console.warn('[AI-Insight] 삭제 실패:', e.message); });
}

// ── 결과 표시 ─────────────────────────────────────────────────
// 위험도 아이콘은 폰트(이모지) 의존성을 없애기 위해 인라인 SVG 원형 사용
function _sevSvg(fill, strokeAttr) {
    return '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 16 16" style="vertical-align:middle;display:inline-block">'
        + '<circle cx="8" cy="8" r="6.5" fill="' + fill + '"' + (strokeAttr || '') + '/></svg>';
}
var _SEV_CONFIG = {
    Critical: { bg:'linear-gradient(135deg,#FEF2F2,#FFE4E4)', border:'#FECACA', color:'#DC2626', icon: _sevSvg('#DC2626'), textColor:'#7F1D1D' },
    High:     { bg:'linear-gradient(135deg,#FFF7ED,#FFEDD5)', border:'#FED7AA', color:'#EA580C', icon: _sevSvg('#F97316'), textColor:'#7C2D12' },
    Medium:   { bg:'linear-gradient(135deg,#FFFBEB,#FEF3C7)', border:'#FDE68A', color:'#D97706', icon: _sevSvg('#EAB308'), textColor:'#78350F' },
    Low:      { bg:'linear-gradient(135deg,#F0FDF4,#DCFCE7)', border:'#86EFAC', color:'#16A34A', icon: _sevSvg('#22C55E'), textColor:'#14532D' },
    Unknown:  { bg:'linear-gradient(135deg,#F9FAFB,#F3F4F6)', border:'#E5E7EB', color:'#6B7280', icon: _sevSvg('#F3F4F6', ' stroke="#9CA3AF" stroke-width="1"'), textColor:'#374151' }
};

function showAiResult(result, isSaved) {
    setAiState('Result');
    updateAiBadges('done');

    var data = result.data || result;
    var severity = data.severity || 'Unknown';
    var sevCfg = _SEV_CONFIG[severity] || _SEV_CONFIG.Unknown;

    // 위험도 배너
    var banner = document.getElementById('aiSeverityBanner');
    if (banner) {
        banner.style.background = sevCfg.bg;
        banner.style.border = '1px solid ' + sevCfg.border;
        banner.style.color = sevCfg.textColor;
    }
    var iconEl = document.getElementById('aiSeverityIcon');
    if (iconEl) iconEl.innerHTML = sevCfg.icon;
    var sevEl = document.getElementById('aiSeverity');
    if (sevEl) { sevEl.textContent = severity; sevEl.style.color = sevCfg.color; }

    // 내용 카드
    setTextWithLineBreaks(document.getElementById('aiSummary'),         data.summary);
    setTextWithLineBreaks(document.getElementById('aiRootCause'),       data.rootCause);
    setNumberedList(document.getElementById('aiRecommendations'), data.recommendations);

    // 위험 요소 카드 (severityDesc가 있으면 항상 표시)
    var riskCard = document.getElementById('aiRiskCard');
    var riskDesc = document.getElementById('aiRiskDesc');
    if (riskCard && riskDesc && data.severityDesc && data.severityDesc.trim()) {
        riskCard.style.display = '';
        setTextWithLineBreaks(riskDesc, data.severityDesc);
    } else if (riskCard) {
        riskCard.style.display = 'none';
    }

    // JVM 힙 설정 분석 카드
    var jvmCard = document.getElementById('aiJvmAdviceCard');
    var jvmDesc = document.getElementById('aiJvmAdviceDesc');
    if (jvmCard && jvmDesc && data.jvmAdvice && data.jvmAdvice.trim()) {
        jvmCard.style.display = '';
        setTextWithLineBreaks(jvmDesc, data.jvmAdvice);
    } else if (jvmCard) {
        jvmCard.style.display = 'none';
    }

    // 메타 정보
    var modelEl = document.getElementById('aiModelInfo');
    if (modelEl) {
        var m = result.model || data.model;
        modelEl.textContent = m ? 'Model: ' + m : '';
    }
    var atEl = document.getElementById('aiAnalysedAt');
    if (atEl) {
        // 신규 완료 응답에 analysedAt 이 없을 때(저장 실패 등) 현재 시각으로 폴백 — 빈 값 방지
        var ts = result.analysedAt || data.analysedAt || Date.now();
        atEl.textContent = ts ? new Date(ts).toLocaleString('ko-KR', {month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'}) + ' 분석' : '';
    }
    var latEl = document.getElementById('aiLatencyInfo');
    if (latEl) {
        var ms = result.latencyMs || data.latencyMs;
        latEl.textContent = ms ? '응답: ' + (ms/1000).toFixed(1) + 's' : '';
    }

    // Overview 의 AI 인사이트 요약 카드 동기화
    renderOverviewInsight(data);

    // [1] 저장 표시
    var savedEl = document.getElementById('aiSavedIndicator');
    var saveErrEl = document.getElementById('aiSaveError');
    if (savedEl) savedEl.style.display = isSaved ? '' : 'none';
    if (saveErrEl) saveErrEl.style.display = (!isSaved && result.saveError) ? '' : 'none';

    // [1] 저장 배너 표시
    var pathBanner = document.getElementById('aiSavedPathBanner');
    var saveErrBanner = document.getElementById('aiSaveErrorBanner');
    var saveErrText = document.getElementById('aiSaveErrorText');
    if (pathBanner) {
        var sp = result.savedTo || result.savedPath || (result.data && (result.data.savedTo || result.data.savedPath)) || _aiSavedPath;
        if (isSaved && sp) {
            _aiSavedPath = sp;
            pathBanner.style.display = 'flex';
        } else {
            pathBanner.style.display = 'none';
        }
    }
    if (saveErrBanner && saveErrText) {
        if (!isSaved && result.saveError) {
            saveErrText.textContent = '분석 결과는 정상 수신되었으나 DB 저장에 실패했습니다 ('
                + result.saveError + '). "저장 재시도" 를 누르거나, 페이지를 벗어나기 전에 재시도하세요.';
            saveErrBanner.style.display = 'flex';
            // 재시도용 payload 보관 — 서버가 retryPayload 를 안 주면 result 자체에서 재구성
            _aiUnsavedPayload = result.retryPayload || _buildRetryPayloadFromResult(result);
            var retryBtn = document.getElementById('aiSaveRetryBtn');
            if (retryBtn) { retryBtn.disabled = false; retryBtn.style.opacity = ''; }
        } else {
            saveErrBanner.style.display = 'none';
            if (isSaved) _aiUnsavedPayload = null;
        }
    }
}

// 컨트롤러가 retryPayload 를 안 보내준 경우(예: 구버전) 응답 자체에서 재조립
function _buildRetryPayloadFromResult(result) {
    var payload = {};
    if (result.model) payload.model = result.model;
    if (result.latencyMs != null) payload.latencyMs = result.latencyMs;
    var data = result.data || {};
    Object.keys(data).forEach(function(k){ payload[k] = data[k]; });
    return payload;
}

// 수동 재시도 — POST /api/llm/insight/save
function retrySaveAiInsight() {
    var fn = typeof FILENAME !== 'undefined' ? FILENAME : '';
    if (!fn) { alert('파일명을 확인할 수 없습니다.'); return; }
    if (!_aiUnsavedPayload) { alert('재시도할 인사이트 데이터가 없습니다. 재분석을 실행하세요.'); return; }

    var btn = document.getElementById('aiSaveRetryBtn');
    var originalText = btn ? btn.innerHTML : '';
    if (btn) {
        btn.disabled = true;
        btn.style.opacity = '.6';
        btn.innerHTML = '저장 중&hellip;';
    }

    fetch('/api/llm/insight/save', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ filename: fn, insightData: _aiUnsavedPayload })
    })
    .then(function(r){ return r.json().then(function(d){ return { ok: r.ok, body: d }; }); })
    .then(function(res) {
        if (res.ok && res.body && res.body.success) {
            console.log('[AI-Insight] 수동 저장 성공');
            _aiUnsavedPayload = null;
            // 배너/상태 전환
            var saveErrBanner = document.getElementById('aiSaveErrorBanner');
            if (saveErrBanner) saveErrBanner.style.display = 'none';
            var savedEl = document.getElementById('aiSavedIndicator');
            if (savedEl) savedEl.style.display = '';
            var saveErrEl = document.getElementById('aiSaveError');
            if (saveErrEl) saveErrEl.style.display = 'none';
            var pathBanner = document.getElementById('aiSavedPathBanner');
            if (pathBanner) pathBanner.style.display = 'flex';
            _aiSavedPath = res.body.savedTo || 'database';
        } else {
            var errMsg = (res.body && res.body.error) ? res.body.error : ('HTTP ' + (res.body && res.body.errorCode || 'ERROR'));
            console.warn('[AI-Insight] 수동 저장 실패:', errMsg);
            alert('저장에 실패했습니다: ' + errMsg + '\n서버 로그를 확인하세요.');
            if (btn) { btn.disabled = false; btn.style.opacity = ''; btn.innerHTML = originalText; }
        }
    })
    .catch(function(e) {
        console.warn('[AI-Insight] 수동 저장 중 네트워크 오류:', e);
        alert('네트워크 오류로 저장에 실패했습니다: ' + (e.message || e) + '\n네트워크 상태 확인 후 다시 시도하세요.');
        if (btn) { btn.disabled = false; btn.style.opacity = ''; btn.innerHTML = originalText; }
    });
}

// ── [6] 에러 표시 (개선: 에러 코드별 힌트) ────────────────────
var _ERROR_HINTS = {
    LLM_DISABLED:    'Settings → AI/LLM Configuration에서 LLM 기능을 활성화하세요.',
    NO_API_KEY:      'Settings → AI/LLM Configuration에서 API 키를 설정하세요.',
    NO_API_URL:      'Settings에서 API URL이 올바르게 설정되었는지 확인하세요.',
    EMPTY_PROMPT:    '힙 덤프 분석이 완료된 후 AI 인사이트를 실행하세요. 분석 결과 데이터가 없으면 프롬프트를 생성할 수 없습니다.',
    EMPTY_RESPONSE:  '다른 모델(claude-sonnet-4-5, gpt-5-mini)을 사용하거나 Settings에서 max_output_tokens를 4,000 이상으로 늘려보세요.',
    AUTH_ERROR:      'API 키가 유효한지 확인하세요. 키가 만료되었거나 잘못 입력되었을 수 있습니다.',
    NOT_FOUND:       'API URL 끝에 /chat/completions가 포함되어 있는지 확인하세요.',
    RATE_LIMIT:      '잠시 후 다시 시도하거나, 다른 API 키 또는 모델을 사용하세요.',
    SERVER_ERROR:    'LLM 서버에 일시적인 문제가 발생했습니다. 잠시 후 다시 시도하세요.',
    TIMEOUT:         'Settings에서 타임아웃(readTimeout)을 늘리거나, 응답 속도가 빠른 모델(gpt-5-mini, claude-haiku)을 선택하세요.',
    CONNECT_FAILED:  'API URL이 올바른지, 서버가 실행 중인지 확인하세요.',
    UNKNOWN_HOST:    'API URL의 호스트명을 확인하세요. DNS 해석에 실패했습니다.',
    JSON_PARSE_WARN: 'AI가 JSON 형식이 아닌 텍스트를 반환했습니다. 위 요약에서 분석 내용을 확인하세요.',
    NETWORK_ERROR:   '서버가 실행 중인지 확인하세요. 브라우저 네트워크 탭에서 상세 오류를 확인할 수 있습니다.',
    SAVE_FAILED:     '분석 결과는 수신되었습니다. 디스크 공간이 부족하거나 디렉토리 권한 문제일 수 있습니다.',
    BAD_REQUEST:     '요청 형식이 잘못되었습니다. 모델명이 허용 목록에 있는지 Settings에서 확인하세요.'
};

function showAiError(msg, errorCode) {
    setAiState('Error');
    updateAiBadges('error');
    setTextWithLineBreaks(document.getElementById('aiErrorMsg'), msg || 'AI 분석 실패');

    var codeEl = document.getElementById('aiErrorCode');
    if (codeEl) {
        if (errorCode) {
            codeEl.textContent = 'ERROR CODE: ' + errorCode;
            codeEl.style.display = '';
        } else {
            codeEl.style.display = 'none';
        }
    }

    // [6] 에러 코드별 힌트 표시
    var hintEl = document.getElementById('aiErrorHint');
    if (hintEl) {
        var hint = _ERROR_HINTS[errorCode];
        if (hint) {
            hintEl.innerHTML = '<strong>해결 방법:</strong> ' + escapeHtml(hint);
            hintEl.style.display = '';
        } else {
            hintEl.style.display = 'none';
        }
    }
}

// ── XSS-safe 텍스트 렌더링 ────────────────────────────────────
// escapeHtml → Common.escHtml (common.js). `&#39;` vs `&#039;` 차이는 양쪽 모두 동일한 ' 엔터티 표현이라 시맨틱 동일.
var escapeHtml = Common.escHtml;
function setTextWithLineBreaks(el, text) {
    if (!el) return;
    el.innerHTML = escapeHtml(text || '-').replace(/\n/g, '<br>');
    el.style.whiteSpace = 'pre-wrap';
}

/**
 * 번호 목록 텍스트를 정렬된 <ol> 리스트로 렌더링.
 * "1. xxx 2. yyy" 또는 "1. xxx\n2. yyy" 등 다양한 패턴 지원.
 * 번호 패턴이 감지되지 않으면 일반 텍스트로 폴백.
 */
function setNumberedList(el, text) {
    if (!el) return;
    if (!text || !text.trim()) { el.innerHTML = escapeHtml('-'); return; }

    // 줄바꿈으로 이미 분리된 경우와 한 줄에 연속된 경우 모두 처리
    // "1. xxx 2. yyy" → 번호 앞에 줄바꿈 삽입 후 분리
    var normalized = text.replace(/(\d+)\s*[.)]\s*/g, '\n$1. ').trim();
    // 줄 단위 분리 후 빈 줄 제거
    var lines = normalized.split('\n').map(function(l) { return l.trim(); }).filter(function(l) { return l.length > 0; });

    // 번호 패턴 감지: "숫자." 또는 "숫자)" 로 시작하는 항목이 2개 이상이면 리스트
    var numbered = [];
    var i;
    for (i = 0; i < lines.length; i++) {
        var m = lines[i].match(/^(\d+)\.\s*(.*)/);
        if (m) {
            numbered.push({ num: parseInt(m[1], 10), text: m[2] });
        } else if (numbered.length > 0) {
            // 번호 없는 이어지는 줄은 이전 항목에 병합
            numbered[numbered.length - 1].text += ' ' + lines[i];
        }
    }

    if (numbered.length >= 2) {
        // 권장 조치 정책: 최우선 조치 3개만 노출. LLM 이 4개 이상 반환했거나
        // 기존 저장된 인사이트가 더 많은 항목을 가진 경우에도 첫 3개로 truncate.
        if (numbered.length > 3) numbered = numbered.slice(0, 3);
        var html = '<ol style="margin:0;padding-left:22px;list-style:none;counter-reset:rec-counter">';
        for (i = 0; i < numbered.length; i++) {
            html += '<li style="margin-bottom:10px;padding-left:8px;position:relative">'
                  + '<span style="position:absolute;left:-22px;width:20px;height:20px;border-radius:50%;'
                  + 'background:#EEF2FF;color:#4F46E5;font-size:11px;font-weight:700;'
                  + 'display:inline-flex;align-items:center;justify-content:center;top:2px">'
                  + (i + 1) + '</span>'
                  + escapeHtml(numbered[i].text)
                  + '</li>';
        }
        html += '</ol>';
        el.innerHTML = html;
        el.style.whiteSpace = 'normal';
    } else {
        // 번호 목록이 아니면 일반 텍스트로 폴백
        setTextWithLineBreaks(el, text);
    }
}

// ══════════════════════════════════════════════════════════
// ── AI 플로팅 채팅 시스템 ─────────────────────────────────
// ══════════════════════════════════════════════════════════

var _aiChatMessages = [];
var _aiChatOpen = false;
var _aiChatSending = false;
var _aiChatContextBuilt = null;
var _aiChatModel = '';
var _aiChatExpanded = false;
var _aiChatSessionId = null;
var _aiChatRestoreAttempted = false;

function toggleAiChat() {
    var panel = document.getElementById('aiChatPanel');
    _aiChatOpen = !_aiChatOpen;
    if (_aiChatOpen) {
        panel.classList.add('open');
        if (!_aiChatContextBuilt) {
            buildChatContext();
            restoreChatHistory();
        }
        // 모바일에서는 자동 포커스 생략 — 채팅 오픈만으로 가상 키보드가 뜨는 것을 방지.
        // (데스크톱은 바로 입력 가능하도록 기존대로 포커스)
        var input = document.getElementById('aiChatInput');
        if (input && !window.matchMedia('(max-width: 900px)').matches) {
            setTimeout(function(){ input.focus(); }, 200);
        }
    } else {
        panel.classList.remove('open');
    }
}

function buildChatContext() {
    var data = collectAnalysisData();
    var lines = [];
    lines.push('[분석 대상 파일: ' + data.filename + ']');
    lines.push('');
    if (data.kpis && data.kpis.length) {
        lines.push('== 힙 통계 ==');
        data.kpis.forEach(function(k) { lines.push(k.label + ': ' + k.value); });
        lines.push('');
    }
    if (data.topConsumers && data.topConsumers.length) {
        lines.push('== Top Memory Consumers ==');
        data.topConsumers.forEach(function(c, i) {
            lines.push((i+1) + '. ' + c.className + ' — 크기: ' + c.size + ', 객체수: ' + c.count + ' (' + c.pct + ')');
        });
        lines.push('');
    }
    if (data.suspects && data.suspects.length) {
        lines.push('== Leak Suspects ==');
        data.suspects.forEach(function(s, i) {
            lines.push('Suspect ' + (i+1) + ': ' + s.title);
            if (s.desc) lines.push('  ' + s.desc);
        });
        lines.push('');
    }
    // AI 인사이트 결과 포함
    var summaryEl = document.getElementById('aiSummary');
    var rootEl = document.getElementById('aiRootCause');
    var recsEl = document.getElementById('aiRecommendations');
    if (summaryEl && summaryEl.textContent.trim()) {
        lines.push('== AI 인사이트 요약 (이전 분석 결과) ==');
        var sevEl = document.getElementById('aiSeverity');
        if (sevEl) lines.push('위험도: ' + sevEl.textContent.trim());
        lines.push('요약: ' + summaryEl.textContent.trim());
        if (rootEl && rootEl.textContent.trim()) lines.push('근본 원인: ' + rootEl.textContent.trim());
        if (recsEl && recsEl.textContent.trim()) lines.push('권장 조치: ' + recsEl.textContent.trim());
        lines.push('');
    }
    _aiChatContextBuilt = lines.join('\n');
}

function showChatWelcome() {
    var container = getChatContainer();
    var div = document.createElement('div');
    div.className = 'ai-chat-msg ai-chat-msg-welcome';
    div.innerHTML = '<strong>AI 채팅</strong>에 오신 것을 환영합니다.<br>'
        + '현재 분석 결과를 기반으로 질문할 수 있습니다.<br>'
        + '<span style="font-size:11px;color:#6B7280;margin-top:4px;display:inline-block">'
        + '예: "메모리 누수의 근본 원인이 뭔가요?", "JVM 튜닝 방법을 알려주세요"</span>';
    container.appendChild(div);
}

// ── 이전 대화 복원 ─────────────────────────────────────
function restoreChatHistory() {
    if (_aiChatRestoreAttempted) { showChatWelcome(); return; }
    _aiChatRestoreAttempted = true;

    var fn = typeof FILENAME !== 'undefined' ? FILENAME : '';
    if (!fn) { showChatWelcome(); return; }

    var container = getChatContainer();
    var loader = document.createElement('div');
    loader.className = 'ai-chat-msg ai-chat-msg-welcome';
    loader.id = 'aiChatRestoreLoader';
    loader.textContent = '이전 대화를 불러오는 중…';
    container.appendChild(loader);

    fetch('/api/ai-chat/sessions?filename=' + encodeURIComponent(fn), { credentials: 'same-origin' })
        .then(function(r) {
            if (r.redirected || r.status === 401 || r.status === 403 || r.status === 302) {
                throw new Error('auth');
            }
            var ct = r.headers.get('content-type') || '';
            if (ct.indexOf('application/json') < 0) throw new Error('auth');
            return r.json();
        })
        .then(function(sessions) {
            var ldr = document.getElementById('aiChatRestoreLoader');
            if (ldr && ldr.parentNode) ldr.parentNode.removeChild(ldr);
            if (!sessions || !sessions.length) {
                showChatWelcome();
                return;
            }
            // 가장 최근 updatedAt 세션 선택 (서버 쿼리 이미 desc 정렬)
            var s = sessions[0];
            _aiChatSessionId = s.id;
            if (s.model) {
                _aiChatModel = s.model;
                var badge = document.getElementById('aiChatModelBadge');
                if (badge) badge.textContent = s.model;
            }
            loadChatMessages(s.id);
        })
        .catch(function(e) {
            var ldr = document.getElementById('aiChatRestoreLoader');
            if (ldr && ldr.parentNode) ldr.parentNode.removeChild(ldr);
            showChatWelcome();
            if (e && e.message !== 'auth') {
                console.warn('[AI-Chat] 이전 대화 로드 실패:', e.message);
            }
        });
}

function loadChatMessages(sessionId) {
    var container = getChatContainer();
    fetch('/api/ai-chat/sessions/' + sessionId + '/messages', { credentials: 'same-origin' })
        .then(function(r) { return r.json(); })
        .then(function(messages) {
            if (!messages || !messages.length) {
                showChatWelcome();
                return;
            }
            // UI 렌더 (항상 수행)
            var lastDate = '';
            messages.forEach(function(m) {
                var d = extractChatDate(m.createdAt);
                if (d && d !== lastDate) {
                    appendChatDateSep(d);
                    lastDate = d;
                }
                renderRestoredMessage(m.role, m.content, m.createdAt);
            });

            if (LLM_CHAT_RESTORE_INCLUDE_HISTORY) {
                // Option 1: LLM 컨텍스트에 포함
                _aiChatMessages = messages.map(function(m) {
                    return { role: m.role, content: m.content || '' };
                });
                trimChatMessages();  // 24000자 컷오프
            } else {
                // Option 3: UI만 표시, LLM은 신규 대화로 인식
                _aiChatMessages = [];
                appendContextResetNotice();
            }

            container.scrollTop = container.scrollHeight;
        })
        .catch(function(e) {
            console.warn('[AI-Chat] 메시지 로드 실패:', e && e.message);
            showChatWelcome();
        });
}

function renderRestoredMessage(role, content, createdAt) {
    var container = getChatContainer();
    var wrapper = document.createElement('div');
    wrapper.style.cssText = 'display:flex;flex-direction:column;' + (role === 'user' ? 'align-items:flex-end' : 'align-items:flex-start');

    var div = document.createElement('div');
    div.className = 'ai-chat-msg ai-chat-msg-' + role;
    if (role === 'assistant') {
        div.innerHTML = renderChatMarkdown(content || '');
    } else {
        div.textContent = content || '';
    }

    var timeDiv = document.createElement('div');
    timeDiv.className = 'ai-chat-msg-time';
    timeDiv.textContent = extractChatTime(createdAt);

    wrapper.appendChild(div);
    wrapper.appendChild(timeDiv);
    container.appendChild(wrapper);
}

function appendContextResetNotice() {
    var container = getChatContainer();
    var notice = document.createElement('div');
    notice.className = 'ai-chat-msg-context-reset';
    notice.style.cssText = 'text-align:center;font-size:11px;color:#9CA3AF;padding:8px 0;border-top:1px dashed #E5E7EB;margin:8px 0 4px';
    notice.textContent = '— 여기까지 이전 대화 (AI는 기억하지 않음) —';
    container.appendChild(notice);
}

function appendChatDateSep(dateStr) {
    var container = getChatContainer();
    var sep = document.createElement('div');
    sep.style.cssText = 'text-align:center;font-size:10px;color:#9CA3AF;padding:6px 0;margin:4px 0';
    sep.textContent = dateStr;
    container.appendChild(sep);
}

function extractChatDate(dt) {
    if (!dt) return '';
    var d = _parseChatDateTime(dt);
    if (!d) return '';
    var today = new Date();
    var yday = new Date(); yday.setDate(today.getDate() - 1);
    var sameDay = function(a, b) {
        return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
    };
    if (sameDay(d, today)) return '오늘';
    if (sameDay(d, yday)) return '어제';
    return d.getFullYear() + '년 ' + (d.getMonth() + 1) + '월 ' + d.getDate() + '일';
}

function extractChatTime(dt) {
    var d = _parseChatDateTime(dt);
    if (!d) {
        var now = new Date();
        return now.getHours().toString().padStart(2, '0') + ':' + now.getMinutes().toString().padStart(2, '0');
    }
    var h = d.getHours();
    var m = d.getMinutes();
    var ap = h < 12 ? '오전' : '오후';
    var h12 = h % 12 || 12;
    return ap + ' ' + h12 + ':' + (m < 10 ? '0' : '') + m;
}

function _parseChatDateTime(dt) {
    if (!dt) return null;
    try {
        // ISO 8601, LocalDateTime.toString(), epoch millis 등 다양한 포맷 수용
        var d;
        if (typeof dt === 'number') { d = new Date(dt); }
        else { d = new Date(dt); }
        if (isNaN(d.getTime())) return null;
        return d;
    } catch (e) { return null; }
}

function sendChatMessage() {
    if (_aiChatSending) return;
    var input = getChatInput();
    var text = input.value.trim();
    if (!text) return;

    // user 메시지 추가
    _aiChatMessages.push({ role: 'user', content: text });
    renderChatMessage('user', text);
    input.value = '';
    input.style.height = 'auto';

    // 토큰 관리: 오래된 메시지 제거
    trimChatMessages();

    // 타이핑 인디케이터
    _aiChatSending = true;
    getChatSendBtn().disabled = true;
    var typing = showChatTyping();

    // 세션 자동 생성 후 스트리밍 시작
    ensureChatSession().then(function() {
        doStreamRequest(typing);
    }).catch(function(e) {
        hideChatTyping(typing);
        renderChatError('세션 생성 실패: ' + e.message);
        _aiChatMessages.pop();
        _aiChatSending = false;
        getChatSendBtn().disabled = false;
    });
}

function ensureChatSession() {
    if (_aiChatSessionId) return Promise.resolve();
    return fetch('/api/ai-chat/sessions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            filename: typeof FILENAME !== 'undefined' ? FILENAME : '',
            title: '새 채팅'
        })
    })
    .then(function(r) {
        if (r.redirected || r.status === 401 || r.status === 403 || r.status === 302) {
            throw new Error('로그인이 만료되었습니다. 페이지를 새로고침 해주세요.');
        }
        var ct = r.headers.get('content-type') || '';
        if (!ct.includes('application/json')) {
            throw new Error('로그인이 만료되었습니다. 페이지를 새로고침 해주세요.');
        }
        return r.json();
    })
    .then(function(d) {
        if (d.success) {
            _aiChatSessionId = d.sessionId;
        } else {
            throw new Error(d.error || 'Session creation failed');
        }
    });
}

function doStreamRequest(typing) {
    // 스트리밍 assistant 메시지 버블 미리 생성
    var streamBubble = createStreamBubble();
    var fullText = '';
    var streamUrl = _aiChatSessionId
        ? '/api/ai-chat/sessions/' + _aiChatSessionId + '/stream'
        : '/api/llm/chat/stream';

    fetch(streamUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            messages: _aiChatMessages,
            context: _aiChatContextBuilt || '',
            filename: typeof FILENAME !== 'undefined' ? FILENAME : ''
        })
    })
    .then(function(response) {
        if (!response.ok) throw new Error('HTTP ' + response.status);
        hideChatTyping(typing);
        typing = null;

        var reader = response.body.getReader();
        var decoder = new TextDecoder();
        var buffer = '';

        function processStream() {
            return reader.read().then(function(result) {
                if (result.done) {
                    // 스트림 종료 — 남은 버퍼 처리
                    if (buffer.trim()) processSSEBuffer(buffer);
                    finishStream();
                    return;
                }
                buffer += decoder.decode(result.value, { stream: true });
                // SSE 이벤트 파싱 (줄바꿈 구분)
                var lines = buffer.split('\n');
                buffer = lines.pop(); // 마지막 미완성 라인은 버퍼에 유지
                for (var i = 0; i < lines.length; i++) {
                    processSSELine(lines[i]);
                }
                return processStream();
            });
        }

        var currentEvent = '';

        function processSSELine(line) {
            if (line.indexOf('event:') === 0) {
                currentEvent = line.substring(6).trim();
            } else if (line.indexOf('data:') === 0) {
                var data = line.substring(5).trim();
                handleSSEEvent(currentEvent, data);
                currentEvent = '';
            }
        }

        function processSSEBuffer(buf) {
            var lines = buf.split('\n');
            for (var i = 0; i < lines.length; i++) processSSELine(lines[i]);
        }

        function handleSSEEvent(event, data) {
            try {
                var parsed = JSON.parse(data);
                if (event === 'start') {
                    if (parsed.model) {
                        _aiChatModel = parsed.model;
                        var badge = document.getElementById('aiChatModelBadge');
                        if (badge) badge.textContent = parsed.model;
                    }
                } else if (event === 'chunk') {
                    if (parsed.text) {
                        fullText += parsed.text;
                        updateStreamBubble(streamBubble, fullText);
                    }
                } else if (event === 'done') {
                    // 완료 처리는 finishStream에서
                } else if (event === 'error') {
                    removeStreamBubble(streamBubble);
                    streamBubble = null;
                    var errMsg = (parsed.errorCode ? '[' + parsed.errorCode + '] ' : '') + (parsed.error || 'Unknown error');
                    renderChatError(errMsg);
                    _aiChatMessages.pop();
                }
            } catch (e) {
                // JSON 파싱 실패 무시
            }
        }

        function finishStream() {
            if (fullText && streamBubble) {
                // 최종 마크다운 렌더링 (커서 제거)
                streamBubble.innerHTML = renderChatMarkdown(fullText);
                _aiChatMessages.push({ role: 'assistant', content: fullText });
            } else if (!fullText && streamBubble) {
                removeStreamBubble(streamBubble);
                if (_aiChatMessages.length && _aiChatMessages[_aiChatMessages.length - 1].role === 'user') {
                    _aiChatMessages.pop();
                }
            }
            _aiChatSending = false;
            getChatSendBtn().disabled = false;
            var inp = getChatInput();
            if (inp) inp.focus();
        }

        return processStream();
    })
    .catch(function(e) {
        if (typing) hideChatTyping(typing);
        if (streamBubble) removeStreamBubble(streamBubble);
        renderChatError('네트워크 오류: ' + e.message);
        _aiChatMessages.pop();
        _aiChatSending = false;
        getChatSendBtn().disabled = false;
    });
}

function createStreamBubble() {
    var container = getChatContainer();
    var wrapper = document.createElement('div');
    wrapper.style.cssText = 'display:flex;flex-direction:column;align-items:flex-start';
    wrapper.className = 'ai-chat-stream-wrapper';

    var div = document.createElement('div');
    div.className = 'ai-chat-msg ai-chat-msg-assistant';
    div.innerHTML = '<span class="ai-chat-cursor">&#9612;</span>';

    var timeDiv = document.createElement('div');
    timeDiv.className = 'ai-chat-msg-time';
    var now = new Date();
    timeDiv.textContent = now.getHours().toString().padStart(2, '0') + ':' + now.getMinutes().toString().padStart(2, '0');

    wrapper.appendChild(div);
    wrapper.appendChild(timeDiv);
    container.appendChild(wrapper);
    container.scrollTop = container.scrollHeight;
    return div;
}

function updateStreamBubble(bubble, text) {
    if (!bubble) return;
    bubble.innerHTML = renderChatMarkdown(text) + '<span class="ai-chat-cursor">&#9612;</span>';
    var container = getChatContainer();
    container.scrollTop = container.scrollHeight;
}

function removeStreamBubble(bubble) {
    if (bubble && bubble.parentNode && bubble.parentNode.parentNode) {
        bubble.parentNode.parentNode.removeChild(bubble.parentNode);
    }
}

function trimChatMessages() {
    var maxChars = 24000;
    var total = _aiChatMessages.reduce(function(s, m) { return s + (m.content ? m.content.length : 0); }, 0);
    while (total > maxChars && _aiChatMessages.length > 1) {
        var removed = _aiChatMessages.shift();
        total -= (removed.content ? removed.content.length : 0);
    }
}

function renderChatMessage(role, content) {
    var container = getChatContainer();
    var wrapper = document.createElement('div');
    wrapper.style.cssText = 'display:flex;flex-direction:column;' + (role === 'user' ? 'align-items:flex-end' : 'align-items:flex-start');

    var div = document.createElement('div');
    div.className = 'ai-chat-msg ai-chat-msg-' + role;

    if (role === 'assistant') {
        div.innerHTML = renderChatMarkdown(content);
    } else {
        div.textContent = content;
    }

    var timeDiv = document.createElement('div');
    timeDiv.className = 'ai-chat-msg-time';
    var now = new Date();
    timeDiv.textContent = now.getHours().toString().padStart(2, '0') + ':' + now.getMinutes().toString().padStart(2, '0');

    wrapper.appendChild(div);
    wrapper.appendChild(timeDiv);
    container.appendChild(wrapper);
    container.scrollTop = container.scrollHeight;
}

function renderChatError(msg) {
    var container = getChatContainer();
    var div = document.createElement('div');
    div.className = 'ai-chat-msg ai-chat-msg-error';
    div.textContent = msg;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
}

function showChatTyping() {
    var container = getChatContainer();
    var div = document.createElement('div');
    div.className = 'ai-chat-typing';
    div.id = 'aiChatTypingIndicator';
    div.innerHTML = '<div class="ai-chat-typing-dot"></div><div class="ai-chat-typing-dot"></div><div class="ai-chat-typing-dot"></div>';
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
    return div;
}

function hideChatTyping(el) {
    if (el && el.parentNode) el.parentNode.removeChild(el);
}

function renderChatMarkdown(text) {
    if (!text) return '';
    // escapeHtml 먼저
    var s = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    // 코드 블록 (```)
    s = s.replace(/```(\w*)\n?([\s\S]*?)```/g, function(m, lang, code) {
        return '<pre><code>' + code.trim() + '</code></pre>';
    });
    // 인라인 코드
    s = s.replace(/`([^`]+)`/g, '<code>$1</code>');
    // bold
    s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    // 줄바꿈
    s = s.replace(/\n/g, '<br>');
    // pre 태그 안의 <br> 제거
    s = s.replace(/<pre><code>([\s\S]*?)<\/code><\/pre>/g, function(m, c) {
        return '<pre><code>' + c.replace(/<br>/g, '\n') + '</code></pre>';
    });
    return s;
}

function resetAiChat() {
    _aiChatMessages = [];
    _aiChatContextBuilt = null;
    _aiChatSessionId = null;
    _aiChatRestoreAttempted = true;  // 리셋 후 재복원 차단 (사용자 의도 존중)
    var container = getChatContainer();
    container.innerHTML = '';
    buildChatContext();
    showChatWelcome();
    var badge = document.getElementById('aiChatModelBadge');
    if (badge) badge.textContent = _aiChatModel || '';
}

function confirmNewChat() {
    var m = document.getElementById('newChatModal');
    if (m) m.classList.add('open');
}
function closeNewChatModal() {
    var m = document.getElementById('newChatModal');
    if (m) m.classList.remove('open');
}
function doNewChat() {
    closeNewChatModal();
    resetAiChat();
}

function handleChatKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendChatMessage();
    }
    if (e.key === 'Escape') {
        toggleAiChat();
    }
}

/** 현재 활성 메시지 컨테이너 반환 */
function getChatContainer() {
    return document.getElementById('aiChatMessages');
}
/** 현재 활성 입력 요소 반환 */
function getChatInput() {
    return document.getElementById('aiChatInput');
}
/** 현재 활성 전송 버튼 반환 */
function getChatSendBtn() {
    return document.getElementById('aiChatSendBtn');
}

// ── 플로팅 채팅 확대/축소 토글 ────────────────────────────

function toggleChatExpand() {
    var panel = document.getElementById('aiChatPanel');
    _aiChatExpanded = !_aiChatExpanded;
    var btn = document.getElementById('aiChatExpandBtn');
    if (_aiChatExpanded) {
        panel.classList.add('expanded');
        if (btn) btn.title = '축소';
        if (btn) btn.innerHTML = '<svg viewBox="0 0 24 24" style="width:14px;height:14px;stroke:currentColor;fill:none;stroke-width:2"><path d="M4 14h6v6M20 10h-6V4M14 10l7-7M3 21l7-7"/></svg>';
    } else {
        panel.classList.remove('expanded');
        if (btn) btn.title = '확대';
        if (btn) btn.innerHTML = '<svg viewBox="0 0 24 24" style="width:14px;height:14px;stroke:currentColor;fill:none;stroke-width:2"><path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7"/></svg>';
    }
    // 스크롤 하단 유지
    var container = getChatContainer();
    setTimeout(function() { container.scrollTop = container.scrollHeight; }, 350);
}

// textarea 자동 높이 조절
(function() {
    var intv = setInterval(function() {
        var ta = document.getElementById('aiChatInput');
        if (!ta) return;
        clearInterval(intv);
        ta.addEventListener('input', function() {
            this.style.height = 'auto';
            this.style.height = Math.min(this.scrollHeight, 100) + 'px';
        });
    }, 500);
})();

// ── 호스트명 칩 인라인 편집 ───────────────────────────────
// 덤프 출처 호스트명(analysis_history.server_name) 수동 편집.
// SSH 전송 시 자동 기록되지만 수동 업로드는 비어 있어 운영자가 직접 입력.
function startHostEdit() {
    var chip = document.querySelector('.host-chip');
    if (!chip || chip.querySelector('.host-chip-input')) return; // 이미 편집 중
    var nameEl = document.getElementById('hostChipName');
    var editBtn = document.getElementById('hostChipEdit');
    if (!nameEl || !editBtn) return;
    var current = nameEl.classList.contains('host-chip-empty') ? '' : nameEl.textContent.trim();

    nameEl.style.display = 'none';
    editBtn.style.display = 'none';

    var input = document.createElement('input');
    input.type = 'text';
    input.className = 'host-chip-input';
    input.value = current;
    input.maxLength = 100;
    input.placeholder = '호스트명 입력';

    var save = document.createElement('button');
    save.type = 'button';
    save.className = 'host-chip-save';
    save.textContent = '저장';

    var cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.className = 'host-chip-cancel';
    cancel.textContent = '취소';

    function cleanup() {
        input.remove(); save.remove(); cancel.remove();
        nameEl.style.display = '';
        editBtn.style.display = '';
    }
    cancel.onclick = cleanup;
    save.onclick = function() { saveHostEdit(input.value, cleanup); };
    input.addEventListener('keydown', function(e) {
        if (e.key === 'Enter') { e.preventDefault(); save.onclick(); }
        else if (e.key === 'Escape') { cleanup(); }
    });

    chip.appendChild(input);
    chip.appendChild(save);
    chip.appendChild(cancel);
    input.focus();
    input.select();
}

function saveHostEdit(value, done) {
    Common.fetchJSON('/api/history/' + encodeURIComponent(FILENAME) + '/hostname',
            { method: 'POST', body: JSON.stringify({ hostname: (value || '').trim() }) })
        .then(function(d) {
            var nameEl = document.getElementById('hostChipName');
            var hn = (d && d.hostname) ? d.hostname : '';
            if (hn) {
                nameEl.textContent = hn;
                nameEl.classList.remove('host-chip-empty');
            } else {
                nameEl.textContent = '미지정';
                nameEl.classList.add('host-chip-empty');
            }
            if (done) done();
        })
        .catch(function(e) {
            alert('호스트명 저장 실패: ' + (e && e.message ? e.message : e));
            if (done) done();
        });
}

// ── JEUS Instance/Domain 칩 인라인 편집 ───────────────────
// jeus.server.name(Instance) / jeus.domain.name(Domain) 을 System Properties 에서 자동 식별.
// 미식별이거나 수동 업로드 덤프는 운영자가 직접 입력(analysis_history.jeus_instance/jeus_domain 영속화).
// 수동값이 비면 자동 식별값(JEUS_*_AUTO)으로 폴백 표시.
function startJeusEdit(field) {
    var chip = document.getElementById('jeusChip-' + field);
    if (!chip || chip.querySelector('.host-chip-input')) return; // 이미 편집 중
    var nameEl = document.getElementById(field === 'instance' ? 'jeusInstanceName' : 'jeusDomainName');
    var editBtn = chip.querySelector('.host-chip-edit');
    if (!nameEl || !editBtn) return;
    var current = nameEl.classList.contains('host-chip-empty') ? '' : nameEl.textContent.trim();

    nameEl.style.display = 'none';
    editBtn.style.display = 'none';

    var input = document.createElement('input');
    input.type = 'text';
    input.className = 'host-chip-input';
    input.value = current;
    input.maxLength = 100;
    input.placeholder = (field === 'instance' ? 'Instance' : 'Domain') + ' 입력';

    var save = document.createElement('button');
    save.type = 'button';
    save.className = 'host-chip-save';
    save.textContent = '저장';

    var cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.className = 'host-chip-cancel';
    cancel.textContent = '취소';

    function cleanup() {
        input.remove(); save.remove(); cancel.remove();
        nameEl.style.display = '';
        editBtn.style.display = '';
    }
    cancel.onclick = cleanup;
    save.onclick = function() { saveJeusEdit(field, input.value, cleanup); };
    input.addEventListener('keydown', function(e) {
        if (e.key === 'Enter') { e.preventDefault(); save.onclick(); }
        else if (e.key === 'Escape') { cleanup(); }
    });

    chip.appendChild(input);
    chip.appendChild(save);
    chip.appendChild(cancel);
    input.focus();
    input.select();
}

function saveJeusEdit(field, value, done) {
    var body = {};
    body[field] = (value || '').trim();
    Common.fetchJSON('/api/history/' + encodeURIComponent(FILENAME) + '/jeus',
            { method: 'POST', body: JSON.stringify(body) })
        .then(function(d) {
            applyJeusChip('instance', d ? d.instance : '');
            applyJeusChip('domain', d ? d.domain : '');
            if (done) done();
        })
        .catch(function(e) {
            alert('JEUS 정보 저장 실패: ' + (e && e.message ? e.message : e));
            if (done) done();
        });
}

// 수동값이 있으면 그 값, 없으면 자동 식별값(JEUS_*_AUTO), 둘 다 없으면 '미지정' 표시.
function applyJeusChip(field, manual) {
    var nameEl = document.getElementById(field === 'instance' ? 'jeusInstanceName' : 'jeusDomainName');
    if (!nameEl) return;
    var auto = field === 'instance'
        ? (typeof JEUS_INSTANCE_AUTO !== 'undefined' ? JEUS_INSTANCE_AUTO : '')
        : (typeof JEUS_DOMAIN_AUTO !== 'undefined' ? JEUS_DOMAIN_AUTO : '');
    var eff = (manual && manual.length) ? manual : (auto || '');
    if (eff) {
        nameEl.textContent = eff;
        nameEl.classList.remove('host-chip-empty');
    } else {
        nameEl.textContent = '미지정';
        nameEl.classList.add('host-chip-empty');
    }
}

// ── 초기화 실행 ─────────────────────────────────────────
if (typeof FILENAME !== 'undefined') {
    initAiPanel();
}
