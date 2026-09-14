/* gc-log-index.js — GC 로그 목록/업로드/이력 페이지 (gc-log/index.html, 2026-09-14)
 * core-dump-index.js 의 구조(드롭존 + XHR 업로드 + 이력 검색/정렬/기간/페이지네이션)를 GC 로그 한 슬롯으로 줄인 판.
 * 업로드는 multipart 라 Common.fetchJSON 대신 XHR — 응답은 텍스트로 받아 안전 파싱(함정 14). */
(function () {
    'use strict';

    var TAG = '[GcLogIndex]';
    function ignored(where, e) { if (window.Common) window.Common.logIgnored(TAG + ' ' + where, e); }
    function failed(where, e)  { if (window.Common) window.Common.logError(TAG + ' ' + where, e); }

    function csrfHeaders(json) {
        var h = json ? { 'Content-Type': 'application/json' } : {};
        var t = Common.csrfToken();
        if (t) h[Common.csrfHeaderName()] = t;
        return h;
    }
    var esc = function (s) { return window.Common ? Common.escHtml(String(s == null ? '' : s)) : String(s == null ? '' : s); };

    // ── 토스트 ────────────────────────────────────────────────
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

    /** 응답을 텍스트로 받아 JSON 이면 파싱, 아니면 {success:false,error} 로 정규화 (함정 14). */
    function readJsonSafe(xhrOrText, status) {
        var text = typeof xhrOrText === 'string' ? xhrOrText : xhrOrText.responseText;
        try {
            var d = JSON.parse(text);
            if (d && typeof d === 'object') return d;
        } catch (e) { ignored('JSON 파싱 실패(HTTP ' + status + ')', e); }
        return { success: false, error: httpMsg(status) };
    }
    function httpMsg(status) {
        if (status === 401) return '세션이 만료되었습니다. 다시 로그인하세요.';
        if (status === 413) return '파일이 업로드 상한을 넘습니다.';
        if (status === 0) return '네트워크 오류';
        return '서버 오류 (HTTP ' + status + ')';
    }

    // ── 업로드 CTA 문구 (2026-09-14) ─────────────────────────────
    //   코어 덤프 페이지처럼 상태가 문구에 드러나야 한다 — 종전엔 '업로드 후 분석 시작' 고정이라 업로드 중에도 그대로였다.
    var CHART_ICON = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="3 17 8 11 12 14 17 7 21 10"></polyline><line x1="3" y1="21" x2="21" y2="21"></line></svg>';
    function setCta(state, detail) {
        var el = document.getElementById('uploadBtnLabel');
        if (!el) return;
        if (state === 'empty') el.textContent = 'GC 로그 파일을 선택하세요';
        else if (state === 'ready') el.innerHTML = CHART_ICON + ' GC 로그 분석 시작';
        else if (state === 'uploading') el.textContent = '업로드 중 ' + detail;
        else if (state === 'starting') el.textContent = '분석 시작 중…';
    }

    // ── 업로드 존 ─────────────────────────────────────────────
    var _file = null;

    function onGcFileSelect(input) {
        var f = input.files && input.files[0];
        setFile(f || null);
    }
    function setFile(f) {
        _file = f;
        var nameEl = document.getElementById('gcFileName');
        var zone = document.getElementById('gcDropZone');
        var clearBtn = document.getElementById('gcClearBtn');
        var btn = document.getElementById('uploadBtn');
        if (f) {
            nameEl.textContent = f.name + ' (' + Common.formatBytes(f.size) + ')';
            zone.classList.add('has-file');
            if (clearBtn) clearBtn.style.display = '';
            btn.disabled = false;
            setCta('ready');
        } else {
            nameEl.textContent = '';
            zone.classList.remove('has-file');
            if (clearBtn) clearBtn.style.display = 'none';
            btn.disabled = true;
            setCta('empty');
        }
        hideError();
    }
    function clearGcZone(e) {
        if (e) { e.preventDefault(); e.stopPropagation(); }
        var input = document.getElementById('gcFileInput');
        if (input) input.value = '';
        setFile(null);
    }
    function focusUpload() {
        var zone = document.getElementById('gcDropZone');
        if (zone) { zone.scrollIntoView({ behavior: 'smooth', block: 'center' }); zone.classList.add('dragover'); setTimeout(function () { zone.classList.remove('dragover'); }, 900); }
    }
    function showError(msg) {
        var box = document.getElementById('uploadErrorBox');
        var m = document.getElementById('uploadErrorMsg');
        if (m) m.textContent = msg;
        if (box) box.style.display = 'block';
    }
    function hideError() {
        var box = document.getElementById('uploadErrorBox');
        if (box) box.style.display = 'none';
    }

    function initDropZone() {
        var zone = document.getElementById('gcDropZone');
        if (!zone) return;
        ['dragenter', 'dragover'].forEach(function (ev) {
            zone.addEventListener(ev, function (e) { e.preventDefault(); e.stopPropagation(); zone.classList.add('dragover'); });
        });
        ['dragleave', 'drop'].forEach(function (ev) {
            zone.addEventListener(ev, function (e) { e.preventDefault(); e.stopPropagation(); zone.classList.remove('dragover'); });
        });
        zone.addEventListener('drop', function (e) {
            var f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
            if (f) setFile(f);
        });
        // 페이지 어디에 떨어뜨려도 브라우저가 파일로 이동하지 않게
        window.addEventListener('dragover', function (e) { e.preventDefault(); });
        window.addEventListener('drop', function (e) { e.preventDefault(); });
        var clearBtn = document.getElementById('gcClearBtn');
        if (clearBtn) clearBtn.style.display = 'none';
    }

    // ── 업로드 제출 ───────────────────────────────────────────
    var _uploading = false;
    function startUpload() {
        if (!_file || _uploading) return;
        _uploading = true;
        hideError();
        var btn = document.getElementById('uploadBtn');
        var fill = document.getElementById('uploadProgressFill');
        var status = document.getElementById('uploadStatus');
        btn.disabled = true;
        if (status) status.textContent = '업로드 중…';
        var fd = new FormData();
        fd.append('gcLogFile', _file, _file.name);
        var server = (document.getElementById('gcServerName') || {}).value || '';
        if (server.trim()) fd.append('serverName', server.trim());

        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/api/gc-log/upload');
        var h = csrfHeaders(false);
        Object.keys(h).forEach(function (k) { xhr.setRequestHeader(k, h[k]); });
        setCta('uploading', '0%');
        xhr.upload.addEventListener('progress', function (e) {
            if (!e.lengthComputable) return;
            var pct = Math.round(100 * e.loaded / e.total);
            if (fill) fill.style.width = pct + '%';
            setCta('uploading', pct + '% · ' + Common.formatBytes(e.loaded) + ' / ' + Common.formatBytes(e.total));
        });
        xhr.addEventListener('load', function () {
            _uploading = false;
            var d = readJsonSafe(xhr, xhr.status);
            if (xhr.status >= 200 && xhr.status < 300 && d.success) {
                if (fill) fill.style.width = '100%';
                setCta('starting');
                if (status) status.textContent = '업로드 완료 — 분석을 시작합니다.';
                startAnalysisAndGo(d.filename);
            } else {
                if (fill) fill.style.width = '0';
                btn.disabled = false;
                setCta('ready');
                if (status) status.textContent = '';
                showError(d.error || d.message || httpMsg(xhr.status));
            }
        });
        xhr.addEventListener('error', function () {
            _uploading = false;
            if (fill) fill.style.width = '0';
            btn.disabled = false;
            setCta('ready');
            if (status) status.textContent = '';
            showError('네트워크 오류로 업로드하지 못했습니다.');
        });
        xhr.send(fd);
    }

    function startAnalysisAndGo(filename) {
        fetch('/api/gc-log/analyze/' + encodeURIComponent(filename), { method: 'POST', headers: csrfHeaders(true) })
            .then(function (r) { return r.text().then(function (t) { return readJsonSafe(t, r.status); }); })
            .then(function (d) {
                if (!d.success) toast('분석 시작 실패: ' + (d.error || ''), 'danger');
                window.location.href = '/gc-log/analyze/' + encodeURIComponent(filename);
            })
            .catch(function (e) {
                failed('분석 시작 요청 실패', e);
                window.location.href = '/gc-log/analyze/' + encodeURIComponent(filename);
            });
    }

    // ── 파일 패널 클릭 ────────────────────────────────────────
    function glOpenFile(filename, status) {
        if (!filename) return;
        if (status === 'SUCCESS' || status === 'ANALYZING') {
            window.location.href = '/gc-log/analyze/' + encodeURIComponent(filename);
            return;
        }
        if (window.AnalyzeConfirm) {
            AnalyzeConfirm.open({ filename: filename, kind: 'gclog' });   // 확인 시 ?start=1 로 이동
        } else {
            window.location.href = '/gc-log/analyze/' + encodeURIComponent(filename) + '?start=1';
        }
    }

    function glRowAction(e, tr) {
        if (e.target.closest('a, button')) return;
        var fn = tr.dataset.filename, status = tr.dataset.status;
        glOpenFile(fn, status);
    }

    // ── 삭제 ─────────────────────────────────────────────────
    var _deleteTarget = null;
    function deleteGcLog(filename) {
        _deleteTarget = filename;
        document.getElementById('deleteGcModalFilename').textContent = filename;
        var err = document.getElementById('deleteGcModalErr');
        if (err) { err.textContent = ''; err.classList.remove('show'); }
        document.getElementById('deleteGcModal').classList.add('open');
    }
    function closeGcDeleteModal() {
        document.getElementById('deleteGcModal').classList.remove('open');
        _deleteTarget = null;
    }
    function confirmGcDelete() {
        var filename = _deleteTarget;
        if (!filename) return;
        var deleteFile = document.getElementById('deleteGcFileChk').checked;
        var btn = document.getElementById('deleteGcConfirmBtn');
        btn.disabled = true;
        fetch('/api/gc-log/' + encodeURIComponent(filename) + '?deleteFile=' + deleteFile, { method: 'DELETE', headers: csrfHeaders(false) })
            .then(function (r) { return r.text().then(function (t) { return readJsonSafe(t, r.status); }); })
            .then(function (d) {
                btn.disabled = false;
                if (!d.success) {
                    var err = document.getElementById('deleteGcModalErr');
                    if (err) { err.textContent = d.error || '삭제 실패'; err.classList.add('show'); }
                    return;
                }
                closeGcDeleteModal();
                toast('삭제했습니다: ' + filename, 'success');
                setTimeout(function () { window.location.reload(); }, 600);
            })
            .catch(function (e) {
                btn.disabled = false;
                failed('삭제 요청 실패', e);
                var err = document.getElementById('deleteGcModalErr');
                if (err) { err.textContent = '네트워크 오류'; err.classList.add('show'); }
            });
    }

    // ── 사이드 파일 패널: 검색/상태 필터/페이지네이션 ────────
    function initSidebar() {
        var list = document.getElementById('glFileList');
        if (!list) return;
        var items = Array.prototype.slice.call(list.querySelectorAll('.cd-file-item'));
        var search = document.getElementById('glFileSearch');
        var clearBtn = document.getElementById('glFileSearchClear');
        var statusSel = document.getElementById('glFileStatusFilter');
        var noResult = document.getElementById('glFileNoResult');
        var countEl = document.getElementById('glFileCount');
        var pgBar = document.getElementById('glFilePg'), pgList = document.getElementById('glFilePgList'), pgInfo = document.getElementById('glFilePgInfo');
        var PAGE = 12, cur = 1;
        function filtered() {
            var q = (search.value || '').trim().toLowerCase();
            var st = statusSel.value;
            return items.filter(function (it) {
                if (q && it.dataset.filename.toLowerCase().indexOf(q) === -1) return false;
                if (st !== 'all' && it.dataset.status !== st) return false;
                return true;
            });
        }
        function render() {
            var f = filtered(), total = f.length;
            var pages = Math.max(1, Math.ceil(total / PAGE));
            if (cur > pages) cur = pages;
            items.forEach(function (it) { it.style.display = 'none'; });
            var start = (cur - 1) * PAGE, end = Math.min(start + PAGE, total);
            for (var i = start; i < end; i++) f[i].style.display = '';
            if (countEl) countEl.textContent = total;
            if (noResult) noResult.style.display = total === 0 ? 'block' : 'none';
            if (clearBtn) clearBtn.style.display = search.value ? '' : 'none';
            renderPg(pgBar, pgList, pgInfo, total, pages, start, end, PAGE, cur, function (p) { cur = p; render(); });
        }
        search.addEventListener('input', function () { cur = 1; render(); });
        statusSel.addEventListener('change', function () { cur = 1; render(); });
        if (clearBtn) clearBtn.addEventListener('click', function () { search.value = ''; cur = 1; render(); search.focus(); });
        render();
    }

    // ── 분석 이력: 검색/정렬/기간/페이지네이션 ────────────────
    function initHistory() {
        var tbody = document.getElementById('hiTbody');
        if (!tbody) return;
        var rows = Array.prototype.slice.call(tbody.querySelectorAll('tr'));
        var search = document.getElementById('hiSearch');
        var countEl = document.getElementById('hiCount');
        var noResult = document.getElementById('hiNoResult');
        var pgBar = document.getElementById('hiPg'), pgList = document.getElementById('hiPgList'), pgInfo = document.getElementById('hiPgInfo');
        var PAGE = 20, cur = 1, TOTAL = rows.length;
        var sortKey = null, sortDir = 1;
        var hasCal = !!(window.Calendar && document.getElementById('hiCalArea'));

        function pad2(n) { return (n < 10 ? '0' : '') + n; }
        function dayKey(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }

        function filtered() {
            var q = search ? search.value.trim().toLowerCase() : '';
            var range = hasCal ? window.Calendar.getRange() : {};
            var from = range.start ? dayKey(range.start) : '';
            var to = range.end ? dayKey(range.end) : '';
            var f = rows.filter(function (r) {
                if (q && (r.dataset.fname || '').toLowerCase().indexOf(q) === -1 && (r.dataset.server || '').toLowerCase().indexOf(q) === -1) return false;
                if (from || to) {
                    // data-uploaded 는 LocalDateTime ISO('2026-09-13T10:05:23') — 앞 10자 문자열 비교(하루 단위, 종료일 포함)
                    var d = (r.dataset.uploaded || '').substring(0, 10);
                    if (!d) return false;
                    if (from && d < from) return false;
                    if (to && d > to) return false;
                }
                return true;
            });
            if (sortKey) {
                f = f.slice().sort(function (a, b) {
                    var av = a.dataset[sortKey] || '', bv = b.dataset[sortKey] || '';
                    return av < bv ? -sortDir : av > bv ? sortDir : 0;
                });
            }
            return f;
        }
        function render() {
            var f = filtered(), total = f.length;
            var pages = Math.max(1, Math.ceil(total / PAGE));
            if (cur > pages) cur = pages; if (cur < 1) cur = 1;
            rows.forEach(function (r) { r.style.display = 'none'; });
            var start = (cur - 1) * PAGE, end = Math.min(start + PAGE, total);
            for (var i = start; i < end; i++) {
                f[i].style.display = '';
                var numCell = f[i].querySelector('.hi-col-num');
                if (numCell) numCell.textContent = (i + 1);
                tbody.appendChild(f[i]);
            }
            if (countEl) countEl.textContent = (total === TOTAL) ? (total + '건') : (total + ' / ' + TOTAL + '건');
            if (noResult) noResult.style.display = (TOTAL > 0 && total === 0) ? 'block' : 'none';
            renderPg(pgBar, pgList, pgInfo, total, pages, start, end, PAGE, cur, function (p) { cur = p; render(); });
        }
        if (search) search.addEventListener('input', function () { cur = 1; render(); });
        if (hasCal) {
            window.Calendar.attach({
                startInputId: 'hiDateStart', endInputId: 'hiDateEnd',
                areaId: 'hiCalArea',
                storageKey: 'gcLogHistory',
                onChange: function () { cur = 1; render(); }
            });
        }
        Array.prototype.slice.call(document.querySelectorAll('#hiTable th.sortable')).forEach(function (th) {
            th.addEventListener('click', function () {
                var key = th.dataset.sortKey;
                if (sortKey === key) sortDir = -sortDir; else { sortKey = key; sortDir = 1; }
                document.querySelectorAll('#hiTable th .hi-sort-ind').forEach(function (s) { s.textContent = ''; });
                var ind = th.querySelector('.hi-sort-ind');
                if (ind) ind.textContent = sortDir === 1 ? '▲' : '▼';
                cur = 1; render();
            });
        });
        render();
    }

    function renderPg(pgBar, pgList, pgInfo, total, pages, start, end, PAGE, cur, goto) {
        if (!pgBar || !pgList) return;
        if (total <= PAGE) { pgBar.classList.remove('show'); pgList.innerHTML = ''; if (pgInfo) pgInfo.textContent = ''; return; }
        pgBar.classList.add('show'); pgList.innerHTML = '';
        var add = function (label, page, opts) {
            opts = opts || {};
            var b = document.createElement('button');
            b.className = 'cd-pg-btn' + (opts.active ? ' active' : '');
            b.textContent = label;
            if (opts.disabled) b.disabled = true;
            if (!opts.disabled && !opts.active) b.addEventListener('click', function () { goto(page); });
            pgList.appendChild(b);
        };
        add('‹', cur - 1, { disabled: cur <= 1 });
        var lo = Math.max(1, cur - 2), hi = Math.min(pages, cur + 2);
        if (lo > 1) { add('1', 1); if (lo > 2) { var e1 = document.createElement('span'); e1.className = 'cd-pg-ellipsis'; e1.textContent = '…'; pgList.appendChild(e1); } }
        for (var p = lo; p <= hi; p++) add(String(p), p, { active: p === cur });
        if (hi < pages) { if (hi < pages - 1) { var e2 = document.createElement('span'); e2.className = 'cd-pg-ellipsis'; e2.textContent = '…'; pgList.appendChild(e2); } add(String(pages), pages); }
        add('›', cur + 1, { disabled: cur >= pages });
        if (pgInfo) pgInfo.textContent = (start + 1) + '–' + end + ' / ' + total;
    }

    // ── 초기화 ────────────────────────────────────────────────
    document.addEventListener('DOMContentLoaded', function () {
        initDropZone();
        initSidebar();
        initHistory();
        // ?file= 프리셀렉트 — 대시보드 업로드 큐에서 넘어온 경우 그 파일의 결과/분석으로 안내
        try {
            var params = new URLSearchParams(window.location.search);
            var pre = params.get('file');
            if (pre) {
                history.replaceState({}, document.title, window.location.pathname);
                var item = document.querySelector('.cd-file-item[data-filename="' + CSS.escape(pre) + '"]');
                if (item) { item.scrollIntoView({ block: 'center' }); item.focus(); }
                toast('업로드된 GC 로그: ' + pre + ' — 클릭해 분석을 시작하세요.', 'info');
            }
        } catch (e) { ignored('?file 프리셀렉트', e); }
    });

    window.onGcFileSelect = onGcFileSelect;
    window.clearGcZone = clearGcZone;
    window.focusUpload = focusUpload;
    window.startUpload = startUpload;
    window.glOpenFile = glOpenFile;
    window.glRowAction = glRowAction;
    window.deleteGcLog = deleteGcLog;
    window.closeGcDeleteModal = closeGcDeleteModal;
    window.confirmGcDelete = confirmGcDelete;
})();
