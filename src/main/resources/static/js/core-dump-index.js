/* core-dump-index.js — 코어 덤프 대시보드 (index.html)
 * 업로드 드래그앤드롭 + 제출 + 이력 액션(재분석/삭제).
 * 업로드는 multipart FormData 라 Common.fetchJSON(JSON 전용) 대신 직접 fetch 사용. */
(function () {
    'use strict';

    var _preloadedFilename = null;
    var _preloadedExecFilename = null;

    function csrfHeaders(json) {
        var meta = document.querySelector('meta[name="_csrf"]');
        var metaH = document.querySelector('meta[name="_csrf_header"]');
        var h = json ? { 'Content-Type': 'application/json' } : {};
        if (meta && metaH) h[metaH.content] = meta.content;
        return h;
    }

    function onCoreFileSelect(input) {
        var file = input.files[0];
        if (!file) return;
        _preloadedFilename = null;
        document.getElementById('coreFileName').textContent = file.name;
        document.getElementById('coreDropZone').classList.add('has-file');
        document.getElementById('uploadBtn').disabled = false;
        document.getElementById('uploadStatus').textContent = '';
    }

    function onExecFileSelect(input) {
        var file = input.files[0];
        if (!file) return;
        _preloadedExecFilename = null;
        document.getElementById('execFileName').textContent = file.name;
        document.getElementById('execDropZone').classList.add('has-file');
    }

    // 좌측 패널/URL 에서 고른 "기존 서버 파일"을 업로드칸에 프리로드 (재업로드 X)
    function preloadExisting(filename, execName) {
        if (!filename) return;
        _preloadedFilename = filename;
        var coreInput = document.getElementById('coreFileInput');
        if (coreInput) coreInput.value = '';   // 실제 업로드 선택과 혼동 방지
        document.getElementById('coreFileName').textContent = filename;
        document.getElementById('coreDropZone').classList.add('has-file');
        document.getElementById('uploadBtn').disabled = false;
        if (execName) {
            _preloadedExecFilename = execName;
            var execInput = document.getElementById('execFileInput');
            if (execInput) execInput.value = '';
            document.getElementById('execFileName').textContent = execName;
            document.getElementById('execDropZone').classList.add('has-file');
            document.getElementById('uploadStatus').textContent =
                '✓ 서버 파일 준비 완료 (코어 + 실행 파일) — GDB 분석을 시작하세요.';
        } else {
            _preloadedExecFilename = null;
            var execInput2 = document.getElementById('execFileInput');
            if (execInput2) execInput2.value = '';
            document.getElementById('execFileName').textContent = '';
            document.getElementById('execDropZone').classList.remove('has-file');
            document.getElementById('uploadStatus').textContent =
                '✓ 서버 파일 준비 완료 — GDB 분석을 시작하세요.';
        }
    }

    function startUpload() {
        if (_preloadedFilename) {
            window.location.href = '/core-dump/progress/' + encodeURIComponent(_preloadedFilename);
            return;
        }
        var coreInput = document.getElementById('coreFileInput');
        if (!coreInput.files || !coreInput.files[0]) {
            alert('코어 덤프 파일을 선택해주세요.');
            return;
        }
        var btn = document.getElementById('uploadBtn');
        btn.disabled = true;
        btn.innerHTML = '업로드 중...';

        document.getElementById('uploadErrorBox').classList.remove('visible');

        var fd = new FormData();
        fd.append('coreFile', coreInput.files[0]);
        var execInput = document.getElementById('execFileInput');
        if (execInput.files && execInput.files[0]) fd.append('execFile', execInput.files[0]);

        var progressBar = document.getElementById('uploadProgress');
        var progressFill = document.getElementById('uploadProgressFill');
        var statusEl = document.getElementById('uploadStatus');

        progressBar.classList.add('visible');
        progressFill.style.width = '30%';
        statusEl.textContent = '파일 업로드 중...';

        fetch('/api/core-dump/upload', { method: 'POST', headers: csrfHeaders(false), body: fd })
            .then(function (r) {
                if (!r.ok) return r.json().then(function (d) { throw new Error(d.message || '서버 오류 (' + r.status + ')'); });
                return r.json();
            })
            .then(function (d) {
                progressFill.style.width = '100%';
                statusEl.textContent = '업로드 완료! 분석 페이지로 이동합니다...';
                setTimeout(function () {
                    window.location.href = '/core-dump/progress/' + encodeURIComponent(d.filename);
                }, 500);
            })
            .catch(function (e) {
                progressBar.classList.remove('visible');
                progressFill.style.width = '0%';
                statusEl.textContent = '';
                document.getElementById('uploadErrorMsg').textContent = e.message;
                document.getElementById('uploadErrorBox').classList.add('visible');
                btn.disabled = false;
                btn.innerHTML = '&#128269; GDB 분석 시작';
            });
    }

    function reanalyze(filename, btn) {
        if (btn) { btn.disabled = true; btn.textContent = '요청 중...'; }
        fetch('/api/core-dump/reanalyze/' + encodeURIComponent(filename), { method: 'POST', headers: csrfHeaders(true) })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (d.status === 'ok') {
                    window.location.href = '/core-dump/progress/' + encodeURIComponent(filename);
                } else {
                    alert('재분석 요청 실패: ' + (d.message || '알 수 없는 오류'));
                    if (btn) { btn.disabled = false; btn.innerHTML = '&#8635; 재분석'; }
                }
            })
            .catch(function (e) {
                alert('재분석 중 오류: ' + e.message);
                if (btn) { btn.disabled = false; btn.innerHTML = '&#8635; 재분석'; }
            });
    }

    var _deleteTargetFilename = null;

    function deleteDump(filename) {
        _deleteTargetFilename = filename;
        document.getElementById('deleteDumpModalFilename').textContent = filename;
        document.getElementById('deleteFileChk').checked = false;
        document.getElementById('deleteDumpModal').style.display = 'flex';
    }

    function closeDumpDeleteModal() {
        document.getElementById('deleteDumpModal').style.display = 'none';
        _deleteTargetFilename = null;
    }

    function confirmDumpDelete() {
        if (!_deleteTargetFilename) return;
        var filename = _deleteTargetFilename;
        var deleteFile = document.getElementById('deleteFileChk').checked;
        var btn = document.getElementById('deleteDumpConfirmBtn');
        btn.disabled = true;
        btn.textContent = '삭제 중…';
        var url = '/api/core-dump/' + encodeURIComponent(filename) + '?deleteFile=' + deleteFile;
        fetch(url, { method: 'DELETE', headers: csrfHeaders(true) })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (d.status === 'ok') {
                    closeDumpDeleteModal();
                    window.location.reload();
                } else {
                    alert('삭제 실패: ' + (d.message || '알 수 없는 오류'));
                    btn.disabled = false;
                    btn.textContent = '삭제';
                }
            })
            .catch(function (e) {
                alert('삭제 중 오류: ' + e.message);
                btn.disabled = false;
                btn.textContent = '삭제';
            });
    }

    // 드래그앤드롭 + URL preload
    document.addEventListener('DOMContentLoaded', function () {
        ['coreDropZone', 'execDropZone'].forEach(function (zoneId) {
            var zone = document.getElementById(zoneId);
            if (!zone) return;
            zone.addEventListener('dragover', function (e) { e.preventDefault(); zone.classList.add('dragover'); });
            zone.addEventListener('dragleave', function () { zone.classList.remove('dragover'); });
            zone.addEventListener('drop', function (e) {
                e.preventDefault();
                zone.classList.remove('dragover');
                var file = e.dataTransfer.files[0];
                if (!file) {
                    // 좌측 패널에서 드래그한 기존 서버 파일명
                    if (zoneId === 'coreDropZone') {
                        var nm = e.dataTransfer.getData('text/plain');
                        var ex = e.dataTransfer.getData('application/x-core-exec');
                        if (nm) preloadExisting(nm, ex || null);
                    }
                    return;
                }
                var dt = new DataTransfer();
                dt.items.add(file);
                if (zoneId === 'coreDropZone') {
                    document.getElementById('coreFileName').textContent = file.name;
                    zone.classList.add('has-file');
                    document.getElementById('uploadBtn').disabled = false;
                    document.getElementById('coreFileInput').files = dt.files;
                    _preloadedFilename = null;
                } else {
                    document.getElementById('execFileName').textContent = file.name;
                    zone.classList.add('has-file');
                    document.getElementById('execFileInput').files = dt.files;
                    _preloadedExecFilename = null;
                }
            });
        });

        var params = new URLSearchParams(window.location.search);
        var preFile = params.get('file');
        var preExec = params.get('exec');
        if (preFile) {
            preloadExisting(preFile, preExec || null);
            history.replaceState({}, document.title, window.location.pathname);
        }

        // 좌측 파일 패널: 드래그/클릭 프리로드 + 검색
        var fileItems = Array.prototype.slice.call(document.querySelectorAll('.cd-file-item'));
        fileItems.forEach(function (item) {
            item.addEventListener('dragstart', function (e) {
                e.dataTransfer.setData('text/plain', item.dataset.filename || '');
                if (item.dataset.exec) e.dataTransfer.setData('application/x-core-exec', item.dataset.exec);
                e.dataTransfer.effectAllowed = 'copy';
            });
            item.addEventListener('click', function () {
                fileItems.forEach(function (i) { i.classList.remove('is-selected'); });
                item.classList.add('is-selected');
                preloadExisting(item.dataset.filename, item.dataset.exec || null);
            });
        });

        // 좌측 파일 패널: 검색 + 상태/유형 필터 + 페이지네이션
        var search = document.getElementById('cdFileSearch');
        var searchBtn = document.getElementById('cdFileSearchBtn');
        var statusFilter = document.getElementById('cdFileStatusFilter');
        var typeFilter = document.getElementById('cdFileTypeFilter');
        var noResult = document.getElementById('cdFileNoResult');
        var countEl = document.getElementById('cdFileCount');
        var pgBar = document.getElementById('cdFilePg');
        var pgList = document.getElementById('cdFilePgList');
        var pgInfo = document.getElementById('cdFilePgInfo');

        var CD_PAGE_SIZE = 20;
        var cdCurPage = 1;

        function cdFilteredItems() {
            var q = search ? search.value.trim().toLowerCase() : '';
            var status = statusFilter ? statusFilter.value : 'all';
            var type = typeFilter ? typeFilter.value : 'all';
            return fileItems.filter(function (item) {
                if (q && (item.dataset.filename || '').toLowerCase().indexOf(q) === -1) return false;
                if (status !== 'all' && (item.dataset.status || 'NOT_ANALYZED') !== status) return false;
                if (type === 'exec' && !item.dataset.exec) return false;
                if (type === 'noexec' && item.dataset.exec) return false;
                return true;
            });
        }

        function renderFiles() {
            var filtered = cdFilteredItems();
            var total = filtered.length;
            var totalPages = Math.max(1, Math.ceil(total / CD_PAGE_SIZE));
            if (cdCurPage > totalPages) cdCurPage = totalPages;
            if (cdCurPage < 1) cdCurPage = 1;

            // 전부 숨김 후 현재 페이지 슬라이스만 표시
            fileItems.forEach(function (i) { i.style.display = 'none'; });
            var start = (cdCurPage - 1) * CD_PAGE_SIZE;
            var end = Math.min(start + CD_PAGE_SIZE, total);
            for (var i = start; i < end; i++) filtered[i].style.display = '';

            if (countEl) countEl.textContent = total;
            if (noResult) noResult.style.display = (fileItems.length > 0 && total === 0) ? 'block' : 'none';
            renderPagination(total, totalPages, start, end);
        }

        function renderPagination(total, totalPages, start, end) {
            if (!pgBar || !pgList) return;
            if (total <= CD_PAGE_SIZE) { pgBar.classList.remove('show'); pgList.innerHTML = ''; if (pgInfo) pgInfo.textContent = ''; return; }
            pgBar.classList.add('show');
            pgList.innerHTML = '';

            var addBtn = function (label, page, opts) {
                opts = opts || {};
                var b = document.createElement('button');
                b.className = 'cd-pg-btn' + (opts.active ? ' active' : '');
                b.textContent = label;
                if (opts.disabled) b.disabled = true;
                if (!opts.disabled && !opts.active) b.addEventListener('click', function () { gotoPage(page); });
                pgList.appendChild(b);
            };

            addBtn('‹', cdCurPage - 1, { disabled: cdCurPage === 1 });
            var pages = {};
            pages[1] = true; pages[totalPages] = true; pages[cdCurPage] = true;
            for (var d = 1; d <= 2; d++) {
                if (cdCurPage - d >= 1) pages[cdCurPage - d] = true;
                if (cdCurPage + d <= totalPages) pages[cdCurPage + d] = true;
            }
            var sorted = Object.keys(pages).map(Number).sort(function (a, b) { return a - b; });
            var prev = 0;
            sorted.forEach(function (p) {
                if (p - prev > 1) {
                    var s = document.createElement('span');
                    s.className = 'cd-pg-ellipsis'; s.textContent = '…';
                    pgList.appendChild(s);
                }
                addBtn(String(p), p, { active: p === cdCurPage });
                prev = p;
            });
            addBtn('›', cdCurPage + 1, { disabled: cdCurPage === totalPages });

            if (pgInfo) pgInfo.textContent = (start + 1) + '–' + end + ' / 전체 ' + total;
        }

        function gotoPage(p) {
            cdCurPage = p;
            renderFiles();
        }

        function onFilterChange() { cdCurPage = 1; renderFiles(); }

        if (search) {
            search.addEventListener('input', onFilterChange);
            search.addEventListener('keydown', function (e) {
                if (e.key === 'Enter') { e.preventDefault(); onFilterChange(); }
            });
        }
        if (searchBtn) searchBtn.addEventListener('click', onFilterChange);
        if (statusFilter) statusFilter.addEventListener('change', onFilterChange);
        if (typeFilter) typeFilter.addEventListener('change', onFilterChange);

        renderFiles();
    });

    window.onCoreFileSelect = onCoreFileSelect;
    window.onExecFileSelect = onExecFileSelect;
    window.startUpload = startUpload;
    window.reanalyze = reanalyze;
    window.deleteDump = deleteDump;
    window.closeDumpDeleteModal = closeDumpDeleteModal;
    window.confirmDumpDelete = confirmDumpDelete;
})();
