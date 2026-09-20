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
        else if (state === 'needHost') el.textContent = '호스트네임을 입력하세요';
        else if (state === 'ready') el.innerHTML = CHART_ICON + (detail > 1 ? ' GC 로그 ' + detail + '개 업로드' : ' GC 로그 분석 시작');
        else if (state === 'uploading') el.textContent = '업로드 중 ' + detail;
        else if (state === 'starting') el.textContent = '분석 시작 중…';
    }

    // ── 업로드 존 (2026-09-20: 회전 로그를 한 번에 올릴 수 있게 다중 선택) ──
    var _files = [];

    function onGcFileSelect(input) {
        // 파일 선택창은 목록을 '교체' 한다 — 나눠 고르려면 직전 선택과 합쳐야 한다(rag-settings 드롭존과 같은 규칙)
        addFiles(input.files);
    }

    function addFiles(list) {
        if (!list) return;
        for (var i = 0; i < list.length; i++) {
            var f = list[i];
            var dup = _files.findIndex(function (x) { return x.name === f.name; });
            if (dup >= 0) _files[dup] = f;   // 같은 이름은 last-wins — 고친 파일 대신 옛 내용이 올라가지 않게
            else _files.push(f);
        }
        renderFiles();
    }

    function removeFile(name) {
        _files = _files.filter(function (f) { return f.name !== name; });
        renderFiles();
    }

    function renderFiles() {
        var nameEl = document.getElementById('gcFileName');
        var chips = document.getElementById('gcFileChips');
        var zone = document.getElementById('gcDropZone');
        var clearBtn = document.getElementById('gcClearBtn');
        if (_files.length) {
            var total = _files.reduce(function (a, f) { return a + f.size; }, 0);
            nameEl.textContent = _files.length === 1
                ? _files[0].name + ' (' + Common.formatBytes(_files[0].size) + ')'
                : _files.length + '개 · ' + Common.formatBytes(total);
            zone.classList.add('has-file');
            if (clearBtn) clearBtn.style.display = '';
        } else {
            nameEl.textContent = '';
            zone.classList.remove('has-file');
            if (clearBtn) clearBtn.style.display = 'none';
        }
        if (chips) {
            chips.innerHTML = _files.length < 2 ? '' : _files.map(function (f) {
                return '<span class="gcl-chip-file"><span class="gcl-chip-name" title="' + esc(f.name) + '">' + esc(f.name) + '</span>'
                    + '<button type="button" class="gcl-chip-x" onclick="removeGcFile(\'' + esc(f.name).replace(/'/g, "\\'") + '\')" aria-label="제외">&times;</button></span>';
            }).join('');
        }
        syncCta();
        hideError();
    }

    /** 호스트네임이 비면 시작할 수 없다 — 서버도 NO_HOSTNAME 으로 막지만 여기서 먼저 알린다(2026-09-20). */
    function syncCta() {
        var btn = document.getElementById('uploadBtn');
        var host = ((document.getElementById('gcServerName') || {}).value || '').trim();
        if (!_files.length) { btn.disabled = true; setCta('empty'); return; }
        if (!host) { btn.disabled = true; setCta('needHost'); return; }
        btn.disabled = false;
        setCta('ready', _files.length);
    }

    function onOriginInput() { syncCta(); }

    function clearGcZone(e) {
        if (e) { e.preventDefault(); e.stopPropagation(); }
        var input = document.getElementById('gcFileInput');
        if (input) input.value = '';
        _files = [];
        renderFiles();
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
            addFiles(e.dataTransfer && e.dataTransfer.files);
        });
        // 페이지 어디에 떨어뜨려도 브라우저가 파일로 이동하지 않게
        window.addEventListener('dragover', function (e) { e.preventDefault(); });
        window.addEventListener('drop', function (e) { e.preventDefault(); });
        var clearBtn = document.getElementById('gcClearBtn');
        if (clearBtn) clearBtn.style.display = 'none';
    }

    // ── 업로드 제출 ───────────────────────────────────────────
    var _uploading = false;

    /**
     * 파일을 하나씩 순서대로 올린다 — 한 번에 묶어 보내지 않는 이유는 기존 엔드포인트가 형식 판별·중복명 409·등록·
     * 자동 매칭까지 완결하기 때문이다(실패도 파일 단위로 격리된다). 진행률은 전체 바이트 기준 합산.
     */
    function startUpload() {
        if (!_files.length || _uploading) return;
        var host = ((document.getElementById('gcServerName') || {}).value || '').trim();
        if (!host) { showError('GC 로그를 수집한 서버(호스트네임)를 입력하세요.'); return; }
        var inst = ((document.getElementById('gcInstanceName') || {}).value || '').trim();
        _uploading = true;
        hideError();
        var btn = document.getElementById('uploadBtn');
        var fill = document.getElementById('uploadProgressFill');
        var status = document.getElementById('uploadStatus');
        btn.disabled = true;
        var total = _files.reduce(function (a, f) { return a + f.size; }, 0);
        var done = 0, okNames = [], failures = [];

        function finish() {
            _uploading = false;
            if (fill) fill.style.width = '0';
            btn.disabled = false;
            syncCta();
            if (failures.length) showError(failures.join(' / '));
            if (!okNames.length) { if (status) status.textContent = ''; return; }
            if (status) status.textContent = okNames.length + '개 업로드 완료';
            if (okNames.length === 1) { setCta('starting'); startAnalysisAndGo(okNames[0]); return; }
            offerCombine(okNames);   // 여러 개 — 이어 붙일지 묻는다
        }

        function next(i) {
            if (i >= _files.length) { finish(); return; }
            var f = _files[i];
            if (status) status.textContent = '업로드 중 (' + (i + 1) + '/' + _files.length + ') ' + f.name;
            var fd = new FormData();
            fd.append('gcLogFile', f, f.name);
            fd.append('serverName', host);
            if (inst) fd.append('instanceName', inst);
            var xhr = new XMLHttpRequest();
            xhr.open('POST', '/api/gc-log/upload');
            var h = csrfHeaders(false);
            Object.keys(h).forEach(function (k) { xhr.setRequestHeader(k, h[k]); });
            xhr.upload.addEventListener('progress', function (e) {
                if (!e.lengthComputable) return;
                var sent = done + e.loaded;
                var pct = total > 0 ? Math.round(100 * sent / total) : 0;
                if (fill) fill.style.width = pct + '%';
                setCta('uploading', pct + '% · ' + Common.formatBytes(sent) + ' / ' + Common.formatBytes(total));
            });
            xhr.addEventListener('load', function () {
                var d = readJsonSafe(xhr, xhr.status);
                if (xhr.status >= 200 && xhr.status < 300 && d.success) okNames.push(d.filename);
                else failures.push(f.name + ': ' + (d.error || d.message || httpMsg(xhr.status)));
                done += f.size;
                next(i + 1);
            });
            xhr.addEventListener('error', function () {
                failures.push(f.name + ': 네트워크 오류');
                done += f.size;
                next(i + 1);
            });
            xhr.send(fd);
        }
        setCta('uploading', '0%');
        next(0);
    }

    /**
     * 여러 개를 올린 뒤 — 회전 로그면 이어 붙여 한 번에 분석하는 편이 낫다.
     * 대표는 <b>가장 최근 로그</b>(목록에서 고를 때와 같은 규칙)이고, 순서·일자 판정은 서버가 한다.
     */
    function offerCombine(names) {
        var body = document.getElementById('listCombineBody');
        if (!body) { window.location.reload(); return; }
        _listSel = names.slice();
        body.innerHTML = '<p>' + names.length + '개를 올렸습니다. 같은 JVM 의 회전 로그라면 <b>이어 붙여</b> 한 번에 분석할 수 있습니다.</p>'
            + '<div class="gcl-list-names">' + names.map(function (n) { return '<span class="gcl-prev-step">' + esc(n) + '</span>'; }).join('') + '</div>'
            + '<p class="gcl-list-note">순서는 파일 이름이 아니라 로그 안의 첫 시각으로 정해집니다. '
            + '일자가 다르면 분석명이 기간 표기로 바뀝니다.</p>';
        document.getElementById('listCombineErr').classList.remove('show');
        document.getElementById('listCombineModal').classList.add('open');
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
        if (e.target.closest('a, button, input')) return;
        if (_sel && _sel.active) {   // 선택 모드에서는 행 클릭이 체크 토글이다(이동하지 않는다)
            var c = tr.querySelector('.row-check');
            if (c) { c.checked = !c.checked; _sel.updateCount(); }
            return;
        }
        var fn = tr.dataset.filename, status = tr.dataset.status;
        glOpenFile(fn, status);
    }

    // ── 선택 모드 · 목록에서 이어붙이기 (2026-09-20) ──────────
    // 고정 id 계약(selectModeBtn/selectModeBtnLabel/selectActionBar/saCount/saDelBtn/allCheck)은 select-mode.js 몫.
    var _sel = null, _listSel = [];

    // ── 구성 파일 접기 (2026-09-20) ──────────────────────────
    // 이어 붙인 분석의 구성 파일은 그 자체로 분석 대상이 아니다 — 목록에 '미분석' 으로 남으면 같은 로그를 또 분석한다.
    // Files 페이지의 'deleted 표시' 와 같은 역할의 토글로 열어 둔다.
    var MEMBERS_KEY = 'gcLogShowMembers';
    var _showMembers = false;
    var _historyRender = null;

    function initShowMembers() {
        var chk = document.getElementById('hiShowMembers');
        if (!chk) return;
        try { _showMembers = localStorage.getItem(MEMBERS_KEY) === 'true'; }
        catch (e) { ignored('구성 파일 표시 설정 읽기', e); }
        chk.checked = _showMembers;
        var n = document.querySelectorAll('#hiTbody tr[data-partof]:not([data-partof=""])').length;
        var cnt = document.getElementById('hiMembersCnt');
        if (cnt) cnt.textContent = n ? '(' + n + ')' : '';
    }

    function onShowMembersChange() {
        var chk = document.getElementById('hiShowMembers');
        _showMembers = !!(chk && chk.checked);
        try { localStorage.setItem(MEMBERS_KEY, String(_showMembers)); }
        catch (e) { ignored('구성 파일 표시 설정 저장', e); }
        if (_historyRender) _historyRender();
    }

    function initSelectMode() {
        if (!window.SelectMode || !document.getElementById('hiTable')) return;
        _sel = SelectMode.create({
            tableId: 'hiTable', tbodyId: 'hiTbody', bodyClass: 'action-bar-open',
            onCountChange: function (checked) {
                var btn = document.getElementById('saCombineBtn');
                if (btn) btn.disabled = checked.length < 2;   // 이어 붙이려면 2개 이상
            }
        });
    }
    function toggleSelectMode() { if (_sel) _sel.toggle(); }
    function exitSelectMode() { if (_sel) _sel.exit(); }
    function onRowCheck(e) { if (e) e.stopPropagation(); if (_sel) _sel.updateCount(); }
    function onSelectAll() { if (_sel) _sel.onSelectAll(); }

    function selectedFilenames() {
        if (!_sel) return [];
        return _sel.selectedChecks().map(function (c) {
            var tr = c.closest('tr');
            return tr ? tr.dataset.filename : null;
        }).filter(Boolean);
    }

    function openCombineFromList() {
        var names = selectedFilenames();
        if (names.length < 2) return;
        _listSel = names;
        var body = document.getElementById('listCombineBody');
        body.innerHTML = '<p>고른 로그 <b>' + names.length + '개</b>를 이어 붙여 한 번에 분석합니다.</p>'
            + '<div class="gcl-list-names">' + names.map(function (n) { return '<span class="gcl-prev-step">' + esc(n) + '</span>'; }).join('') + '</div>'
            + '<p class="gcl-list-note">순서는 파일 이름이 아니라 <b>로그 안의 첫 시각</b>으로 정해집니다. '
            + '결과는 가장 최근 로그의 분석에 담기고, 나머지는 그 구성 파일이 됩니다.</p>';
        document.getElementById('listCombineErr').classList.remove('show');
        document.getElementById('listCombineModal').classList.add('open');
    }

    function closeListCombine() {
        var m = document.getElementById('listCombineModal');
        if (m) m.classList.remove('open');
    }

    function listCombineError(msg) {
        var el = document.getElementById('listCombineErr');
        el.textContent = msg;
        el.classList.add('show');
        var ok = document.getElementById('listCombineOk');
        ok.disabled = false;
        ok.textContent = '이어 붙여 분석';
    }

    /**
     * 대표는 <b>가장 최근 로그</b>다. 어느 것이 최근인지는 화면이 추측하지 않고 서버의 미리보기 순서를 쓴다
     * (파일 이름은 시간순이 아니다 — 함정 60).
     */
    function confirmListCombine() {
        if (_listSel.length < 2) return;
        var ok = document.getElementById('listCombineOk');
        ok.disabled = true;
        ok.textContent = '처리 중…';
        var first = _listSel[0];
        var rest = _listSel.slice(1);
        fetch('/api/gc-log/' + encodeURIComponent(first) + '/combine/preview',
                { method: 'POST', headers: csrfHeaders(true), body: JSON.stringify({ filenames: rest }) })
            .then(function (r) { return r.text().then(function (t) { return readJsonSafe(t, r.status); }); })
            .then(function (d) {
                if (!d.success) throw new Error(d.error || '미리보기 실패');
                var order = (d.order || []).map(function (o) { return o.filename; });
                var rep = order.length ? order[order.length - 1] : first;
                var members = order.filter(function (n) { return n !== rep; });
                return fetch('/api/gc-log/' + encodeURIComponent(rep) + '/combine',
                        { method: 'POST', headers: csrfHeaders(true), body: JSON.stringify({ filenames: members }) })
                    .then(function (r) { return r.text().then(function (t) { return readJsonSafe(t, r.status); }); })
                    .then(function (c) {
                        if (!c.success) throw new Error(c.error || '이어붙이기 실패');
                        window.location.href = '/gc-log/analyze/' + encodeURIComponent(rep);
                    });
            })
            .catch(function (e) { failed('이어붙이기', e); listCombineError(e.message || '이어붙이기에 실패했습니다.'); });
    }

    /** 선택 삭제 — 파일까지 지운다(목록의 개별 삭제 모달과 같은 기본값). */
    function deleteSelected() {
        var names = selectedFilenames();
        if (!names.length) return;
        if (!window.confirm('선택한 GC 로그 ' + names.length + '개를 원본 파일과 함께 삭제합니다. 계속할까요?')) return;
        var left = names.length, failures = 0;
        names.forEach(function (n) {
            fetch('/api/gc-log/' + encodeURIComponent(n) + '?deleteFile=true', { method: 'DELETE', headers: csrfHeaders(false) })
                .then(function (r) { if (!r.ok) failures++; })
                .catch(function (e) { failed('GC 로그 삭제', e); failures++; })
                .then(function () {
                    if (--left === 0) {
                        if (failures) toast(failures + '개를 삭제하지 못했습니다.', 'danger');
                        setTimeout(function () { window.location.reload(); }, 400);
                    }
                });
        });
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
                // 구성 파일(다른 분석에 이어 붙인 로그)은 토글을 켜야 보인다 — 접어 두지 않으면 같은 로그를 또 분석한다
                if (!_showMembers && r.dataset.partof) return false;
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
            if (_sel && _sel.active) _sel.updateCount();   // 페이지·필터가 바뀌면 보이는 행 기준으로 다시 센다
            renderPg(pgBar, pgList, pgInfo, total, pages, start, end, PAGE, cur, function (p) { cur = p; render(); });
        }
        _historyRender = function () { cur = 1; render(); };
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
        initShowMembers();
        initHistory();
        initSelectMode();
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
    window.removeGcFile = removeFile;
    window.onOriginInput = onOriginInput;
    window.toggleSelectMode = toggleSelectMode;
    window.exitSelectMode = exitSelectMode;
    window.onRowCheck = onRowCheck;
    window.onSelectAll = onSelectAll;
    window.openCombineFromList = openCombineFromList;
    window.closeListCombine = closeListCombine;
    window.confirmListCombine = confirmListCombine;
    window.deleteSelected = deleteSelected;
    window.onShowMembersChange = onShowMembersChange;
})();
