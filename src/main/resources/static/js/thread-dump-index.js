/* thread-dump-index.js — 스레드 덤프 목록·업로드 화면 (thread-dump/index.html, 2026-11-26)
 * 업로드(순차 XHR → POST /api/thread-dump/upload) · 목록 표(TableGrid: 정렬·검색·상태 필터·페이지) · 다운로드 · 삭제 모달.
 * 서버가 내용을 확인해 스레드 덤프가 아니면 거부한다 — 거부 사유는 파일별로 그대로 보여 준다.
 * 모달은 showModal/hideModal 만 거친다(.open 을 직접 만지지 않는다 — ModalMotionTest).
 *
 * 2.7.1 이어 붙이기 — 행 체크박스로 2개 이상 고르면 '이어 붙여 분석'(미리보기 → POST /api/thread-dump/combine).
 * 여러 파일을 올린 직후(이 화면·대시보드)에는 sessionStorage 'thdSuggest' 에 이름을 남기고, 목록이 열리면
 * POST /api/thread-dump/group-suggestion 으로 같은 JVM 으로 보이는 묶음을 받아 제안한다(자동으로 묶지 않는다). */
(function () {
    'use strict';

    var TAG = '[ThreadDumpIndex]';
    function ignored(where, e) { if (window.Common) window.Common.logIgnored(TAG + ' ' + where, e); }
    function failed(where, e)  { if (window.Common) window.Common.logError(TAG + ' ' + where, e); }
    var esc = function (s) { return window.Common ? Common.escHtml(String(s == null ? '' : s)) : String(s == null ? '' : s); };
    function byId(id) { return document.getElementById(id); }

    // ── 모달 열기·닫기 연출 (gc-log 화면과 같은 규칙) ──────────
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

    // ── 업로드 ────────────────────────────────────────────────
    var _files = [];
    var _uploading = false;

    function setCta() {
        var btn = byId('thdUploadBtn'), label = byId('thdUploadLabel'), name = byId('thdFileName'), zone = byId('thdDropZone');
        if (!btn) return;
        if (_uploading) return;
        btn.disabled = _files.length === 0;
        label.textContent = _files.length === 0 ? '스레드 덤프 파일을 선택하세요'
            : (_files.length === 1 ? '업로드하고 분석 시작' : _files.length + '개 파일 업로드');
        name.textContent = _files.length === 0 ? '' : (_files.length === 1 ? _files[0].name : _files[0].name + ' 외 ' + (_files.length - 1) + '개');
        if (zone) zone.classList.toggle('has-file', _files.length > 0);
    }

    function pickFiles(list) {
        _files = Array.prototype.slice.call(list || []);
        var max = window.THD_MAX_BYTES || 0;
        var err = byId('thdUploadErr');
        if (err) { err.classList.remove('visible'); err.innerHTML = ''; }
        if (max > 0) {
            var big = _files.filter(function (f) { return f.size > max; });
            if (big.length) {
                _files = _files.filter(function (f) { return f.size <= max; });
                showUploadError('크기 상한을 넘는 파일을 제외했습니다', big.map(function (f) { return { name: f.name, ok: false, msg: '상한 초과' }; }));
            }
        }
        setCta();
    }

    function showUploadError(title, rows) {
        var err = byId('thdUploadErr');
        if (!err) return;
        var h = '<b>' + esc(title) + '</b>';
        if (rows && rows.length) {
            h += '<ul class="thd-upload-list">' + rows.map(function (r) {
                return '<li><span class="' + (r.ok ? 'ok' : 'ng') + '">' + (r.ok ? '완료' : '실패') + '</span><span class="nm">' + esc(r.name) + '</span>'
                    + (r.msg ? '<span>' + esc(r.msg) + '</span>' : '') + '</li>';
            }).join('') + '</ul>';
        }
        err.innerHTML = h;
        err.classList.add('visible');
    }

    /** 응답 본문이 JSON 이 아닐 수 있다(프록시 413·로그인 페이지) — 던지지 않고 읽는다. */
    function readJsonSafe(text) {
        try { return JSON.parse(text); } catch (e) { ignored('업로드 응답 파싱', e); return null; }
    }
    function httpMsg(status, body) {
        if (body && (body.error || body.message)) return body.error || body.message;
        if (status === 401 || status === 403) return '세션이 만료되었습니다. 다시 로그인하세요.';
        if (status === 413) return '업로드 크기 상한을 넘는 파일입니다.';
        if (status === 503) return '서버를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도하세요.';
        return '업로드 실패 (HTTP ' + status + ')';
    }

    function uploadOne(file, serverName, onProgress) {
        return new Promise(function (resolve) {
            var xhr = new XMLHttpRequest();
            xhr.open('POST', '/api/thread-dump/upload');
            var hn = Common.csrfHeaderName && Common.csrfHeaderName(), tk = Common.csrfToken && Common.csrfToken();
            if (hn && tk) xhr.setRequestHeader(hn, tk);
            xhr.upload.addEventListener('progress', function (ev) { if (ev.lengthComputable) onProgress(ev.loaded / ev.total); });
            xhr.addEventListener('load', function () {
                var body = readJsonSafe(xhr.responseText);
                if (xhr.status >= 200 && xhr.status < 300 && body && body.success) resolve({ ok: true, name: body.filename || file.name });
                else resolve({ ok: false, name: file.name, msg: httpMsg(xhr.status, body) });
            });
            xhr.addEventListener('error', function () { resolve({ ok: false, name: file.name, msg: '네트워크 오류' }); });
            xhr.addEventListener('abort', function () { resolve({ ok: false, name: file.name, msg: '취소됨' }); });
            var fd = new FormData();
            fd.append('threadDumpFile', file, file.name);
            if (serverName) fd.append('serverName', serverName);
            xhr.send(fd);
        });
    }

    function startUpload() {
        if (_uploading || !_files.length) return;
        _uploading = true;
        var btn = byId('thdUploadBtn'), label = byId('thdUploadLabel'), fill = byId('thdUploadFill');
        var serverName = (byId('thdServerName').value || '').trim();
        var files = _files.slice(), results = [];
        btn.disabled = true;
        var err = byId('thdUploadErr'); if (err) { err.classList.remove('visible'); err.innerHTML = ''; }
        var step = function (i) {
            if (i >= files.length) { finishUpload(results); return; }
            label.textContent = '업로드 중 ' + (i + 1) + '/' + files.length + ' …';
            uploadOne(files[i], serverName, function (ratio) {
                if (fill) fill.style.width = Math.round(100 * (i + ratio) / files.length) + '%';
            }).then(function (r) { results.push(r); step(i + 1); });
        };
        step(0);
    }

    function finishUpload(results) {
        _uploading = false;
        var fill = byId('thdUploadFill'); if (fill) fill.style.width = '0%';
        var ok = results.filter(function (r) { return r.ok; }), ng = results.filter(function (r) { return !r.ok; });
        _files = [];
        var input = byId('thdFileInput'); if (input) input.value = '';
        setCta();
        if (ng.length) {
            showUploadError(ok.length ? (ok.length + '개 올리고 ' + ng.length + '개는 실패했습니다') : '업로드하지 못했습니다', results);
            if (ok.length) toast(ok.length + '개 파일을 올렸습니다. 목록을 새로 고치면 보입니다.', 'info');
            return;
        }
        if (ok.length === 1) {
            // 한 개면 바로 결과 화면으로 — 미분석 파일을 열면 분석이 시작된다
            window.location.href = '/thread-dump/analyze/' + encodeURIComponent(ok[0].name);
            return;
        }
        Common.flashToast ? Common.flashToast(ok.length + '개 파일을 올렸습니다. 목록에서 분석을 시작하세요.', 'success') : null;
        rememberSuggest(ok.map(function (r) { return r.name; }));
        window.location.href = '/thread-dump';
    }

    function initUpload() {
        var zone = byId('thdDropZone'), input = byId('thdFileInput'), btn = byId('thdUploadBtn'), clear = byId('thdClearBtn');
        if (!zone || !input) return;
        input.addEventListener('change', function () { pickFiles(input.files); });
        ['dragenter', 'dragover'].forEach(function (ev) {
            zone.addEventListener(ev, function (e) { e.preventDefault(); zone.classList.add('dragover'); });
        });
        ['dragleave', 'drop'].forEach(function (ev) {
            zone.addEventListener(ev, function (e) { e.preventDefault(); zone.classList.remove('dragover'); });
        });
        zone.addEventListener('drop', function (e) { if (e.dataTransfer && e.dataTransfer.files) pickFiles(e.dataTransfer.files); });
        if (clear) clear.addEventListener('click', function (e) { e.preventDefault(); e.stopPropagation(); input.value = ''; pickFiles([]); });
        if (btn) btn.addEventListener('click', startUpload);
        // 업로드 중에는 세션 만료·이탈을 막는다(끝나는 작업만 — 함정 38)
        if (window.SessionTimeout && SessionTimeout.registerActivityGuard) SessionTimeout.registerActivityGuard(function () { return _uploading; });
        if (window.registerUnloadGuard) window.registerUnloadGuard(function () { return _uploading; });
        setCta();
    }

    // ── 목록 표 ───────────────────────────────────────────────
    var SEV_RANK = { Critical: 5, High: 4, Medium: 3, Low: 2, Info: 1 };
    var grid = null;

    function initTable() {
        var tbody = byId('thdTbody');
        if (!tbody || !window.TableGrid) return;
        var rows = Array.prototype.slice.call(tbody.querySelectorAll('tr'));
        rows.forEach(function (tr) {
            tr.setAttribute('data-sev', String(SEV_RANK[tr.getAttribute('data-severity')] || 0));
            tr.setAttribute('data-search', ((tr.getAttribute('data-name') || '') + ' ' + (tr.getAttribute('data-server') || '')).toLowerCase());
        });
        var search = byId('thdSearch'), status = byId('thdStatusFilter');
        function applyFilter() {
            var q = (search.value || '').trim().toLowerCase(), st = status.value;
            var shown = grid.allRows.filter(function (tr) {
                if (st !== 'all' && tr.getAttribute('data-status') !== st) return false;
                if (q && tr.getAttribute('data-search').indexOf(q) === -1) return false;
                return true;
            });
            grid.setFiltered(shown);
            var cnt = byId('thdCount');
            if (cnt) cnt.textContent = shown.length === grid.allRows.length ? grid.allRows.length + '건' : shown.length + ' / ' + grid.allRows.length + '건';
        }
        grid = TableGrid.create({
            tbodyId: 'thdTbody', headerSelector: '#thdTable th.sortable', pageSizeKey: 'threadDumpListPageSize',
            applyFilter: applyFilter, defaultSort: { key: 'created', dir: 'desc', type: 'str' }, sortAttrPrefix: 'data-',
            pageSizes: [20, 50, 100], defaultPageSize: 20, pageSizeSelectId: 'thdPageSize', noMatchId: 'thdNoMatch',
            paginationBarId: 'thdPg', pgInfoId: 'thdPgInfo', pgListId: 'thdPgList', detach: true
        });
        window.thdGrid = grid;
        grid.init(rows);
        var deb = null;
        search.addEventListener('input', function () { clearTimeout(deb); deb = setTimeout(applyFilter, 150); });
        status.addEventListener('change', applyFilter);
        applyFilter();
    }

    // ── 분석 중 행 갱신 — 끝나면 화면을 다시 그린다 ───────────
    var _poll = null;
    function watchAnalyzing() {
        if (!grid) return;
        var names = grid.allRows.filter(function (tr) { return tr.getAttribute('data-status') === 'ANALYZING'; })
            .map(function (tr) { return tr.getAttribute('data-filename'); });
        if (!names.length) return;
        var pending = names.slice();
        var tick = function () {
            var checks = pending.map(function (fn) {
                return Common.fetchJSON('/api/thread-dump/' + encodeURIComponent(fn) + '/status')
                    .then(function (d) { return d.status === 'ANALYZING' ? fn : null; })
                    .catch(function (e) { ignored('상태 조회 ' + fn, e); return fn; });
            });
            Promise.all(checks).then(function (still) {
                var left = still.filter(Boolean);
                if (left.length < pending.length) {
                    // 하나라도 끝났다 — 안내만 띄우고 사용자가 직접 새로 고치게 한다(자동 갱신에 location.reload 금지 — 함정 69)
                    if (_poll && _poll.stop) _poll.stop();
                    _poll = null;
                    toast('분석이 끝난 파일이 있습니다. 새로 고치면 결과가 보입니다.', 'success');
                }
                pending = left;
            });
        };
        if (window.SessionTimeout && SessionTimeout.managedInterval) _poll = SessionTimeout.managedInterval(tick, 5000);
    }

    // ── 다운로드·삭제 ─────────────────────────────────────────
    function thdDownload(filename) {
        var url = '/api/thread-dump/download/' + encodeURIComponent(filename);
        if (window.Common && Common.downloadFile) Common.downloadFile(url, { fallbackName: filename });
        else window.location.href = url;
    }

    var _deleteTarget = null;
    function thdOpenDelete(filename, deleted) {
        _deleteTarget = filename;
        byId('thdDeleteFilename').textContent = filename;
        var gone = deleted === 'true';
        byId('thdDeleteFileRow').style.display = gone ? 'none' : '';
        byId('thdDeleteFileChk').checked = true;
        var err = byId('thdDeleteErr'); err.textContent = ''; err.classList.remove('show');
        byId('thdDeleteBtn').disabled = false;
        showModal(byId('thdDeleteModal'));
        byId('thdDeleteBtn').focus();
    }
    function thdCloseDelete() { hideModal(byId('thdDeleteModal')); }
    function thdConfirmDelete() {
        if (!_deleteTarget) return;
        var btn = byId('thdDeleteBtn'); btn.disabled = true;
        var deleteFile = byId('thdDeleteFileChk').checked;
        Common.fetchJSON('/api/thread-dump/' + encodeURIComponent(_deleteTarget) + '?deleteFile=' + deleteFile, { method: 'DELETE' })
            .then(function () {
                if (Common.flashToast) Common.flashToast('삭제했습니다: ' + _deleteTarget, 'success');
                window.location.href = '/thread-dump';
            })
            .catch(function (e) {
                failed('삭제 실패', e);
                btn.disabled = false;
                var err = byId('thdDeleteErr'); err.textContent = errMsg(e, '삭제하지 못했습니다.'); err.classList.add('show');
            });
    }

    // ── 이어 붙이기(2.7.1) ────────────────────────────────────
    function fmtSize(b) {
        if (b == null) return '';
        if (b < 1024) return b + ' B';
        var u = ['KB', 'MB', 'GB'], v = b / 1024, i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return v.toFixed(1) + ' ' + u[i];
    }
    function fmtTs(ms) {
        if (ms == null) return '시각 없음';
        var d = new Date(ms);
        function p(n) { return (n < 10 ? '0' : '') + n; }
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    }
    function selected() {
        var out = [];
        // 페이지를 넘겨도 선택이 남도록 표 전체 행(detach 된 행 포함)에서 읽는다
        (grid ? grid.allRows : []).forEach(function (tr) {
            var c = tr.querySelector('input.thd-sel');
            if (c && c.checked) out.push(c.getAttribute('data-filename'));
        });
        return out;
    }
    function thdOnSelect() {
        var n = selected().length;
        var bar = byId('thdCombineBar');
        if (!bar) return;
        bar.classList.toggle('show', n > 0);
        byId('thdCombineCount').textContent = n + '개 선택' + (n === 1 ? ' — 이어 붙이려면 하나 더 고르세요' : '');
        byId('thdCombineBtn').disabled = n < 2;
    }
    function thdClearSelection() {
        (grid ? grid.allRows : []).forEach(function (tr) { var c = tr.querySelector('input.thd-sel'); if (c) c.checked = false; });
        thdOnSelect();
    }
    var _combineNames = null;
    function thdOpenCombine() {
        var names = selected();
        if (names.length < 2) return;
        _combineNames = names;
        var list = byId('thdCombineList'), sum = byId('thdCombineSummary'), btn = byId('thdCombineConfirmBtn');
        list.innerHTML = '<li><span></span><span class="meta">순서를 확인하는 중…</span></li>';
        sum.textContent = ''; sum.classList.remove('warn');
        var err = byId('thdCombineErr'); err.textContent = ''; err.classList.remove('show');
        btn.disabled = true;
        showModal(byId('thdCombineModal'));
        Common.fetchJSON('/api/thread-dump/combine/preview', { method: 'POST', body: JSON.stringify({ filenames: names }) })
            .then(function (d) {
                list.innerHTML = (d.files || []).map(function (f, i) {
                    var meta = (f.snapshots ? '스냅샷 ' + f.snapshots + '개' : '덤프 없음') + ' · ' + (f.firstTs != null ? fmtTs(f.firstTs) : '시각 없음') + ' · ' + fmtSize(f.size);
                    return '<li><span class="nm">' + esc(f.filename) + (i === 0 ? ' <span class="thd-group-badge">대표</span>' : '') + '</span><span class="meta">' + esc(meta) + '</span>'
                        + (f.note ? '<span class="note">' + esc(f.note) + '</span>' : '') + '</li>';
                }).join('');
                var s = (d.order === 'timestamp' ? '덤프 시각순으로 이어 붙입니다.' : '시각이 없는 파일이 있어 고른 순서대로 이어 붙입니다.')
                    + ' 스냅샷 모두 ' + d.snapshots + '개.';
                if (d.overLimit) { s += ' 상한(' + d.maxSnapshots + '개)을 넘어 마지막 ' + d.maxSnapshots + '개만 분석합니다.'; sum.classList.add('warn'); }
                sum.textContent = s;
                btn.disabled = false;
                btn.focus();
            })
            .catch(function (e) { err.textContent = errMsg(e, '미리보기를 만들지 못했습니다.'); err.classList.add('show'); });
    }
    function thdCloseCombine() { hideModal(byId('thdCombineModal')); }
    function thdConfirmCombine() {
        if (!_combineNames) return;
        var btn = byId('thdCombineConfirmBtn'); btn.disabled = true;
        Common.fetchJSON('/api/thread-dump/combine', { method: 'POST', body: JSON.stringify({ filenames: _combineNames }) })
            .then(function (d) { window.location.href = '/thread-dump/analyze/' + encodeURIComponent(d.representative); })
            .catch(function (e) {
                failed('이어 붙이기 실패', e);
                btn.disabled = false;
                var err = byId('thdCombineErr'); err.textContent = errMsg(e, '이어 붙이지 못했습니다.'); err.classList.add('show');
            });
    }

    // ── 업로드 직후 제안 ──────────────────────────────────────
    var SUGGEST_KEY = 'thdSuggest';
    function rememberSuggest(names) {
        if (!names || names.length < 2) return;
        try { sessionStorage.setItem(SUGGEST_KEY, JSON.stringify(names)); } catch (e) { ignored('제안 대상 저장', e); }
    }
    var _groups = [];
    function checkSuggest() {
        var names = null;
        try {
            names = JSON.parse(sessionStorage.getItem(SUGGEST_KEY) || 'null');
            sessionStorage.removeItem(SUGGEST_KEY);   // 한 번만 — 새로 고침마다 다시 묻지 않는다
        } catch (e) { ignored('제안 대상 읽기', e); }
        if (!Array.isArray(names) || names.length < 2) return;
        Common.fetchJSON('/api/thread-dump/group-suggestion', { method: 'POST', body: JSON.stringify({ filenames: names }) })
            .then(function (d) {
                _groups = d.groups || [];
                if (!_groups.length) return;
                byId('thdSuggestList').innerHTML = _groups.map(function (g, i) {
                    return '<div class="thd-suggest-group"><label><input type="checkbox" class="thd-suggest-chk" data-idx="' + i + '" checked>'
                        + '<span><b>' + g.files.length + '개 파일</b> · 스냅샷 ' + g.snapshots + '개<br><span class="thd-member-note" style="font-family:var(--font-mono)">'
                        + g.files.map(esc).join('<br>') + '</span></span></label><div class="why">' + esc(g.reason || '') + '</div></div>';
                }).join('');
                var err = byId('thdSuggestErr'); err.textContent = ''; err.classList.remove('show');
                byId('thdSuggestBtn').disabled = false;
                showModal(byId('thdSuggestModal'));
            })
            .catch(function (e) { ignored('이어 붙이기 제안 조회', e); });
    }
    function thdCloseSuggest() { hideModal(byId('thdSuggestModal')); }
    function thdConfirmSuggest() {
        var picks = Array.prototype.slice.call(document.querySelectorAll('.thd-suggest-chk')).filter(function (c) { return c.checked; })
            .map(function (c) { return _groups[+c.getAttribute('data-idx')]; });
        if (!picks.length) { thdCloseSuggest(); return; }
        var btn = byId('thdSuggestBtn'); btn.disabled = true;
        var reps = [];
        var next = function (i) {
            if (i >= picks.length) {
                if (reps.length === 1) window.location.href = '/thread-dump/analyze/' + encodeURIComponent(reps[0]);
                else { if (Common.flashToast) Common.flashToast(reps.length + '개 묶음의 분석을 시작했습니다.', 'success'); window.location.href = '/thread-dump'; }
                return;
            }
            Common.fetchJSON('/api/thread-dump/combine', { method: 'POST', body: JSON.stringify({ filenames: picks[i].files }) })
                .then(function (d) { reps.push(d.representative); next(i + 1); })
                .catch(function (e) {
                    failed('제안 묶음 이어 붙이기 실패', e);
                    btn.disabled = false;
                    var err = byId('thdSuggestErr'); err.textContent = errMsg(e, '이어 붙이지 못했습니다.'); err.classList.add('show');
                });
        };
        next(0);
    }

    document.addEventListener('DOMContentLoaded', function () {
        try { initUpload(); } catch (e) { failed('업로드 초기화', e); }
        try { initTable(); } catch (e) { failed('목록 표 초기화', e); }
        try { watchAnalyzing(); } catch (e) { failed('분석 중 감시', e); }
        document.addEventListener('keydown', function (ev) { if (ev.key === 'Escape') { thdCloseDelete(); thdCloseCombine(); thdCloseSuggest(); } });
        try { thdOnSelect(); checkSuggest(); } catch (e) { failed('이어 붙이기 초기화', e); }
        // 대시보드·Files 업로드에서 넘어온 경우(?file=) 그 행을 찾기 쉽게 검색어로 넣는다
        try {
            var f = new URLSearchParams(window.location.search).get('file');
            var search = byId('thdSearch');
            if (f && search && grid) { search.value = f; search.dispatchEvent(new Event('input')); }
        } catch (e) { ignored('file 파라미터 처리', e); }
    });

    window.thdDownload = thdDownload;
    window.thdOpenDelete = thdOpenDelete;
    window.thdCloseDelete = thdCloseDelete;
    window.thdConfirmDelete = thdConfirmDelete;
    window.thdOnSelect = thdOnSelect;
    window.thdClearSelection = thdClearSelection;
    window.thdOpenCombine = thdOpenCombine;
    window.thdCloseCombine = thdCloseCombine;
    window.thdConfirmCombine = thdConfirmCombine;
    window.thdCloseSuggest = thdCloseSuggest;
    window.thdConfirmSuggest = thdConfirmSuggest;
})();
