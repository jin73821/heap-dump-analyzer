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

    function deleteDump(filename) {
        if (!confirm('"' + filename + '" 파일과 분석 결과를 삭제하시겠습니까?')) return;
        fetch('/api/core-dump/' + encodeURIComponent(filename), { method: 'DELETE', headers: csrfHeaders(true) })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (d.status === 'ok') window.location.reload();
                else alert('삭제 실패: ' + (d.message || '알 수 없는 오류'));
            })
            .catch(function (e) { alert('삭제 중 오류: ' + e.message); });
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
                if (!file) return;
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
            _preloadedFilename = preFile;
            document.getElementById('coreFileName').textContent = preFile;
            document.getElementById('coreDropZone').classList.add('has-file');
            document.getElementById('uploadBtn').disabled = false;
            if (preExec) {
                _preloadedExecFilename = preExec;
                document.getElementById('execFileName').textContent = preExec;
                document.getElementById('execDropZone').classList.add('has-file');
                document.getElementById('uploadStatus').textContent =
                    '✓ 서버 파일 준비 완료 (코어 + 실행 파일) — GDB 분석을 시작하세요.';
            } else {
                document.getElementById('uploadStatus').textContent =
                    '✓ 서버 업로드 완료 — GDB 분석을 시작하세요.';
            }
            history.replaceState({}, document.title, window.location.pathname);
        }
    });

    window.onCoreFileSelect = onCoreFileSelect;
    window.onExecFileSelect = onExecFileSelect;
    window.startUpload = startUpload;
    window.reanalyze = reanalyze;
    window.deleteDump = deleteDump;
})();
