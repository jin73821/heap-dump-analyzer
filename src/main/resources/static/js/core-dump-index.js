/* core-dump-index.js — 코어 덤프 목록/업로드 페이지 (index.html)
 * 업로드 드래그앤드롭 + 제출(XHR 실 진행률) + 이력(검색/정렬/페이지네이션/행 액션).
 * 업로드는 multipart FormData 라 Common.fetchJSON(JSON 전용) 대신 XHR 직접 사용. */
(function () {
    'use strict';

    var _preloadedFilename = null;
    var _preloadedExecFilename = null;
    var _preloadedStatus = null;
    // 프리로드 시점의 서버 페어링 — 현재 선택과 비교해 "분석 조건이 바뀌었는지" 판정
    var _originalExecPairing = null;

    function csrfHeader() {
        var meta = document.querySelector('meta[name="_csrf"]');
        var metaH = document.querySelector('meta[name="_csrf_header"]');
        return (meta && metaH) ? { name: metaH.content, value: meta.content } : null;
    }
    function csrfHeaders(json) {
        var h = json ? { 'Content-Type': 'application/json' } : {};
        var c = csrfHeader();
        if (c) h[c.name] = c.value;
        return h;
    }
    function fmtBytes(n) {
        if (window.Common && Common.formatBytes) return Common.formatBytes(n);
        if (n < 1024) return n + ' B';
        var u = ['KB', 'MB', 'GB', 'TB'], i = -1;
        do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1);
        return n.toFixed(1) + ' ' + u[i];
    }

    // ── 토스트 (alert 대체) ──────────────────────────────────────
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
        var m = document.createElement('span');
        m.className = 'cd-toast-msg';
        m.textContent = msg;
        var x = document.createElement('button');
        x.className = 'cd-toast-close'; x.type = 'button'; x.setAttribute('aria-label', '닫기');
        x.textContent = '×';
        var kill = function () { if (t.parentNode) t.parentNode.removeChild(t); };
        x.addEventListener('click', kill);
        t.appendChild(ic); t.appendChild(m); t.appendChild(x);
        wrap.appendChild(t);
        setTimeout(kill, 4000);
    }

    // ── 업로드 CTA 라벨/동작 상태 ────────────────────────────────
    function setUploadLabel(html) {
        var el = document.getElementById('uploadBtnLabel');
        if (el) el.innerHTML = html;
    }
    var SEARCH_ICON = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"></circle><path d="M21 21l-4.3-4.3"></path></svg>';

    function applyCtaForStatus(status) {
        var btn = document.getElementById('uploadBtn');
        if (!btn) return;
        btn.disabled = false;
        if (status === 'SUCCESS') {
            setUploadLabel('결과 보기');
            btn.classList.remove('btn-primary'); btn.classList.add('btn-soft', 'soft-success');
        } else if (status === 'ANALYZING') {
            setUploadLabel('진행 확인');
            btn.classList.remove('btn-soft', 'soft-success'); btn.classList.add('btn-primary');
        } else {
            setUploadLabel(SEARCH_ICON + ' GDB 분석 시작');
            btn.classList.remove('btn-soft', 'soft-success'); btn.classList.add('btn-primary');
        }
    }
    function resetCta() {
        var btn = document.getElementById('uploadBtn');
        if (!btn) return;
        btn.classList.remove('btn-soft', 'soft-success'); btn.classList.add('btn-primary');
        setUploadLabel(SEARCH_ICON + ' GDB 분석 시작');
    }

    function statusOf(filename) {
        var item = document.querySelector('.cd-file-item[data-filename="' + (window.CSS && CSS.escape ? CSS.escape(filename) : filename) + '"]');
        return item ? (item.dataset.status || 'NOT_ANALYZED') : 'NOT_ANALYZED';
    }

    function onCoreFileSelect(input) {
        var file = input.files[0];
        if (!file) return;
        _preloadedFilename = null; _preloadedStatus = null; _originalExecPairing = null;
        document.getElementById('coreFileName').textContent = file.name;
        document.getElementById('coreDropZone').classList.add('has-file');
        resetCta();
        document.getElementById('uploadBtn').disabled = false;
        document.getElementById('uploadStatus').textContent = '';
        document.getElementById('uploadStatus').classList.remove('is-ready');
    }

    function onExecFileSelect(input) {
        var file = input.files[0];
        if (!file) return;
        _preloadedExecFilename = null;
        document.getElementById('execFileName').textContent = file.name;
        document.getElementById('execDropZone').classList.add('has-file');
    }

    function preloadExisting(filename, execName) {
        if (!filename) return;
        _preloadedFilename = filename;
        _preloadedStatus = statusOf(filename);
        _originalExecPairing = execName || null;
        var coreInput = document.getElementById('coreFileInput');
        if (coreInput) coreInput.value = '';
        document.getElementById('coreFileName').textContent = filename;
        document.getElementById('coreDropZone').classList.add('has-file');
        var status = document.getElementById('uploadStatus');
        applyCtaForStatus(_preloadedStatus);
        if (execName) {
            _preloadedExecFilename = execName;
            var execInput = document.getElementById('execFileInput');
            if (execInput) execInput.value = '';
            document.getElementById('execFileName').textContent = execName;
            document.getElementById('execDropZone').classList.add('has-file');
            status.textContent = '✓ 서버 파일 준비 완료 (코어 + 실행 파일)';
        } else {
            _preloadedExecFilename = null;
            var execInput2 = document.getElementById('execFileInput');
            if (execInput2) execInput2.value = '';
            document.getElementById('execFileName').textContent = '';
            document.getElementById('execDropZone').classList.remove('has-file');
            status.textContent = '✓ 서버 파일 준비 완료';
        }
        status.classList.add('is-ready');
    }

    function preloadExistingExec(execName) {
        if (!execName) return;
        _preloadedExecFilename = execName;
        var execInput = document.getElementById('execFileInput');
        if (execInput) execInput.value = '';
        document.getElementById('execFileName').textContent = execName;
        document.getElementById('execDropZone').classList.add('has-file');
        var status = document.getElementById('uploadStatus');
        status.textContent = '✓ 실행 파일 준비 완료 — 코어 파일을 함께 선택하세요.';
        status.classList.add('is-ready');
    }

    /**
     * 사이드바 선택 해제. 코어/실행파일은 드롭존이 각각 독립이므로 선택도 타입별로 관리한다.
     * type 생략 시 전체 해제.
     */
    function clearFileListSelection(type) {
        Array.prototype.slice.call(document.querySelectorAll('.cd-file-item.is-selected'))
            .forEach(function (i) {
                if (!type || (i.dataset.type || 'coredump') === type) i.classList.remove('is-selected');
            });
    }

    function clearCoreZone(e) {
        if (e) e.stopPropagation();
        _preloadedFilename = null; _preloadedStatus = null; _originalExecPairing = null;
        var coreInput = document.getElementById('coreFileInput');
        if (coreInput) coreInput.value = '';
        document.getElementById('coreFileName').textContent = '';
        document.getElementById('coreDropZone').classList.remove('has-file');
        document.getElementById('uploadBtn').disabled = true;
        resetCta();
        var st = document.getElementById('uploadStatus');
        st.textContent = ''; st.classList.remove('is-ready');
        var errBox = document.getElementById('uploadErrorBox');
        if (errBox) errBox.classList.remove('visible');
        clearFileListSelection('coredump');
    }

    function clearExecZone(e) {
        if (e) e.stopPropagation();
        _preloadedExecFilename = null;
        var execInput = document.getElementById('execFileInput');
        if (execInput) execInput.value = '';
        document.getElementById('execFileName').textContent = '';
        document.getElementById('execDropZone').classList.remove('has-file');
        clearFileListSelection('exec');
    }

    function focusUpload() {
        var card = document.getElementById('coreDropZone');
        if (!card) return;
        card.scrollIntoView({ behavior: 'smooth', block: 'center' });
        card.classList.add('dragover');
        setTimeout(function () { card.classList.remove('dragover'); }, 700);
    }

    // ── 서버 파일 프리로드 시 exec 선택 상태 ─────────────────────
    // 프리로드된 코어는 업로드 없이 서버 파일을 그대로 쓴다. 이때 사용자가 실행 파일을
    // 새로 넣거나/바꾸거나/뺐다면 서버 페어링을 먼저 맞춰야 분석에 반영된다.
    function currentExecSelection() {
        var execInput = document.getElementById('execFileInput');
        if (execInput && execInput.files && execInput.files[0]) {
            return { kind: 'upload', file: execInput.files[0], name: execInput.files[0].name };
        }
        if (_preloadedExecFilename) return { kind: 'existing', name: _preloadedExecFilename };
        return { kind: 'none', name: null };
    }

    function execSelectionChanged() {
        var sel = currentExecSelection();
        // 새 업로드는 이름이 같아도 내용이 다를 수 있으므로 항상 변경으로 본다
        if (sel.kind === 'upload') return true;
        return (sel.name || null) !== (_originalExecPairing || null);
    }

    function readJson(r) {
        return r.json().catch(function () {
            throw new Error('서버 응답을 해석할 수 없습니다 (HTTP ' + r.status + ')');
        });
    }

    /** UI 의 exec 선택을 서버 페어링에 반영. 성공 시 최종 exec 파일명(없으면 null) resolve. */
    function syncExecPairing(core) {
        var sel = currentExecSelection();
        var url = '/api/core-dump/' + encodeURIComponent(core) + '/exec';
        if (sel.kind === 'none') {
            if (!_originalExecPairing) return Promise.resolve(null);
            return fetch(url, { method: 'DELETE', headers: csrfHeaders(true) })
                .then(readJson)
                .then(function (d) {
                    if (d.status !== 'ok') throw new Error(d.message || '실행 파일 연결 해제에 실패했습니다.');
                    return null;
                });
        }
        var fd = new FormData();
        if (sel.kind === 'upload') fd.append('execFile', sel.file);
        else fd.append('execFilename', sel.name);
        // multipart — Content-Type 은 브라우저가 boundary 와 함께 설정해야 하므로 지정하지 않는다
        return fetch(url, { method: 'POST', headers: csrfHeaders(false), body: fd })
            .then(readJson)
            .then(function (d) {
                if (d.status !== 'ok') throw new Error(d.message || '실행 파일 연결에 실패했습니다.');
                return d.executableName;
            });
    }

    // ── 업로드 제출 (XHR 실 진행률) ──────────────────────────────
    function startUpload() {
        if (_preloadedFilename) {
            startPreloadedAnalysis();
            return;
        }
        var coreInput = document.getElementById('coreFileInput');
        if (!coreInput.files || !coreInput.files[0]) { toast('코어 덤프 파일을 선택해주세요.', 'danger'); return; }

        var btn = document.getElementById('uploadBtn');
        var fill = document.getElementById('uploadProgressFill');
        btn.disabled = true;
        document.getElementById('uploadErrorBox').classList.remove('visible');
        var statusEl = document.getElementById('uploadStatus');
        statusEl.classList.remove('is-ready');

        var fd = new FormData();
        fd.append('coreFile', coreInput.files[0]);
        var execInput = document.getElementById('execFileInput');
        if (execInput.files && execInput.files[0]) fd.append('execFile', execInput.files[0]);

        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/api/core-dump/upload');
        var c = csrfHeader();
        if (c) xhr.setRequestHeader(c.name, c.value);

        xhr.upload.onprogress = function (e) {
            if (!e.lengthComputable) return;
            var pct = Math.round(e.loaded / e.total * 100);
            if (fill) fill.style.width = pct + '%';
            setUploadLabel('업로드 중 ' + pct + '% · ' + fmtBytes(e.loaded) + ' / ' + fmtBytes(e.total));
        };
        xhr.onload = function () {
            if (xhr.status >= 200 && xhr.status < 300) {
                if (fill) fill.style.width = '100%';
                setUploadLabel('분석 진행 확인 →');
                var d = {};
                try { d = JSON.parse(xhr.responseText); } catch (e) {}
                statusEl.textContent = '업로드 완료! 분석 페이지로 이동합니다...';
                setTimeout(function () {
                    window.location.href = '/core-dump/progress/' + encodeURIComponent(d.filename);
                }, 400);
            } else {
                var msg = '서버 오류 (' + xhr.status + ')';
                try { msg = JSON.parse(xhr.responseText).message || msg; } catch (e) {}
                uploadFailed(msg);
            }
        };
        xhr.onerror = function () { uploadFailed('네트워크 오류로 업로드에 실패했습니다.'); };
        xhr.send(fd);

        function uploadFailed(msg) {
            if (fill) fill.style.width = '0%';
            resetCta();
            statusEl.textContent = '';
            document.getElementById('uploadErrorMsg').textContent = msg;
            document.getElementById('uploadErrorBox').classList.add('visible');
            btn.disabled = false;
        }
    }

    /**
     * 프리로드(서버 파일) 상태에서 CTA 를 눌렀을 때의 분기.
     * - 분석 중        → 진행 페이지
     * - 완료 + exec 그대로 → 기존 결과 페이지
     * - 완료 + exec 변경   → 재분석 확인 모달 (기존 결과는 리비전 보존)
     * - 미분석/실패        → exec 변경분 반영 후 분석 시작
     */
    function startPreloadedAnalysis() {
        var core = _preloadedFilename;
        if (!core) return;

        if (_preloadedStatus === 'ANALYZING') {
            window.location.href = '/core-dump/progress/' + encodeURIComponent(core);
            return;
        }
        if (_preloadedStatus === 'SUCCESS') {
            if (execSelectionChanged()) openReanalyzeModal(core);
            else window.location.href = '/core-dump/analyze/' + encodeURIComponent(core);
            return;
        }

        var btn = document.getElementById('uploadBtn');
        if (!execSelectionChanged()) {
            window.location.href = '/core-dump/progress/' + encodeURIComponent(core);
            return;
        }
        btn.disabled = true;
        setUploadLabel('실행 파일 연결 중...');
        syncExecPairing(core)
            .then(function () { window.location.href = '/core-dump/progress/' + encodeURIComponent(core); })
            .catch(function (e) {
                btn.disabled = false;
                resetCta();
                document.getElementById('uploadErrorMsg').textContent = e.message;
                document.getElementById('uploadErrorBox').classList.add('visible');
            });
    }

    // ── 재분석 확인 모달 (이미 분석된 코어 + exec 구성 변경) ─────
    var _reanalyzeTarget = null;

    function openReanalyzeModal(core) {
        _reanalyzeTarget = core;
        document.getElementById('reanalyzeModalFilename').textContent = core;

        var sel = currentExecSelection();
        var diff = document.getElementById('reanalyzeModalDiff');
        diff.innerHTML = '';
        var addRow = function (key, value, cls) {
            var row = document.createElement('div');
            row.className = 'modal-diff-row';
            var k = document.createElement('span');
            k.className = 'modal-diff-key';
            k.textContent = key;
            var v = document.createElement('span');
            v.className = 'modal-diff-val' + (cls ? ' ' + cls : '');
            v.textContent = value;
            row.appendChild(k); row.appendChild(v);
            diff.appendChild(row);
        };
        addRow('기존 분석', _originalExecPairing || '실행 파일 없음',
               _originalExecPairing ? '' : 'is-none');
        addRow('새 분석', sel.name || '실행 파일 없음',
               sel.name ? 'is-new' : 'is-none');

        var err = document.getElementById('reanalyzeModalErr');
        err.textContent = ''; err.classList.remove('visible');
        var ok = document.getElementById('reanalyzeConfirmBtn');
        ok.disabled = false; ok.textContent = '새로 분석';
        document.getElementById('reanalyzeViewBtn').disabled = false;

        var m = document.getElementById('reanalyzeModal');
        m.classList.add('open');
        ok.focus();
    }

    function closeReanalyzeModal() {
        document.getElementById('reanalyzeModal').classList.remove('open');
        _reanalyzeTarget = null;
    }

    function viewExistingResult() {
        if (!_reanalyzeTarget) return;
        window.location.href = '/core-dump/analyze/' + encodeURIComponent(_reanalyzeTarget);
    }

    /** exec 페어링 반영 → 기존 결과 리비전 보존 → 진행 페이지. */
    function confirmReanalyzeNew() {
        if (!_reanalyzeTarget) return;
        var core = _reanalyzeTarget;
        var ok = document.getElementById('reanalyzeConfirmBtn');
        var view = document.getElementById('reanalyzeViewBtn');
        var err = document.getElementById('reanalyzeModalErr');
        err.textContent = ''; err.classList.remove('visible');
        ok.disabled = true; view.disabled = true; ok.textContent = '준비 중...';

        syncExecPairing(core)
            .then(function () {
                return fetch('/api/core-dump/reanalyze/' + encodeURIComponent(core),
                             { method: 'POST', headers: csrfHeaders(true) }).then(readJson);
            })
            .then(function (d) {
                if (d.status !== 'ok') throw new Error(d.message || '재분석 요청에 실패했습니다.');
                window.location.href = '/core-dump/progress/' + encodeURIComponent(core);
            })
            .catch(function (e) {
                err.textContent = e.message;
                err.classList.add('visible');
                ok.disabled = false; view.disabled = false; ok.textContent = '새로 분석';
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
                    toast('재분석 요청 실패: ' + (d.message || '알 수 없는 오류'), 'danger');
                    if (btn) { btn.disabled = false; btn.textContent = '재분석'; }
                }
            })
            .catch(function (e) {
                toast('재분석 중 오류: ' + e.message, 'danger');
                if (btn) { btn.disabled = false; btn.textContent = '재분석'; }
            });
    }

    // ── 행 액션 (row-as-action) ──────────────────────────────────
    function cdRowAction(e, tr) {
        if (e && e.target && e.target.closest('.hi-actions')) return;
        var fn = tr.dataset.filename, status = tr.dataset.status;
        if (status === 'SUCCESS') window.location.href = '/core-dump/analyze/' + encodeURIComponent(fn);
        else if (status === 'ANALYZING') window.location.href = '/core-dump/progress/' + encodeURIComponent(fn);
        else reanalyze(fn, null);
    }

    // ── 삭제 모달 ────────────────────────────────────────────────
    var _deleteTargetFilename = null;

    function deleteDump(filename) {
        _deleteTargetFilename = filename;
        document.getElementById('deleteDumpModalFilename').textContent = filename;
        document.getElementById('deleteFileChk').checked = false;
        var m = document.getElementById('deleteDumpModal');
        m.classList.add('open');
        var cancel = m.querySelector('.mbtn-cancel');
        if (cancel) cancel.focus();
    }
    function closeDumpDeleteModal() {
        document.getElementById('deleteDumpModal').classList.remove('open');
        _deleteTargetFilename = null;
    }
    function confirmDumpDelete() {
        if (!_deleteTargetFilename) return;
        var filename = _deleteTargetFilename;
        var deleteFile = document.getElementById('deleteFileChk').checked;
        var btn = document.getElementById('deleteDumpConfirmBtn');
        btn.disabled = true; btn.textContent = '삭제 중…';
        var url = '/api/core-dump/' + encodeURIComponent(filename) + '?deleteFile=' + deleteFile;
        fetch(url, { method: 'DELETE', headers: csrfHeaders(true) })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (d.status === 'ok') { closeDumpDeleteModal(); window.location.reload(); }
                else { toast('삭제 실패: ' + (d.message || '알 수 없는 오류'), 'danger'); btn.disabled = false; btn.textContent = '삭제'; }
            })
            .catch(function (e) {
                toast('삭제 중 오류: ' + e.message, 'danger'); btn.disabled = false; btn.textContent = '삭제';
            });
    }

    // ── 사이드바 파일 목록: 드래그/클릭/검색/필터/페이지네이션 ──
    function initSidebar() {
        var fileItems = Array.prototype.slice.call(document.querySelectorAll('.cd-file-item'));
        var dragGhost = null;

        // 드래그 고스트 정리 — dragend 가 유실되는 경우(드롭 취소 등)를 대비해 단일 진입점
        function removeDragGhost() {
            if (dragGhost && dragGhost.parentNode) dragGhost.parentNode.removeChild(dragGhost);
            dragGhost = null;
        }

        fileItems.forEach(function (item) {
            var isExec = item.dataset.type === 'exec';

            item.addEventListener('dragstart', function (e) {
                if (isExec) {
                    e.dataTransfer.setData('application/x-core-exec-standalone', item.dataset.filename || '');
                } else {
                    e.dataTransfer.setData('text/plain', item.dataset.filename || '');
                    if (item.dataset.exec) e.dataTransfer.setData('application/x-core-exec', item.dataset.exec);
                }
                e.dataTransfer.effectAllowed = 'copy';

                // 기본 드래그 이미지는 스크롤 컨테이너에 잘리고 흐릿하게 렌더된다.
                // 카드를 그대로 복제해 화면 밖에 띄운 뒤 드래그 이미지로 지정 → 카드 디자인이
                // 커서에 붙어 따라온다. 복제본은 반드시 렌더된 상태여야 하므로 display:none 금지.
                removeDragGhost();
                var rect = item.getBoundingClientRect();
                var ghost = item.cloneNode(true);
                ghost.classList.add('cd-drag-ghost');
                ghost.classList.remove('is-selected');
                ghost.removeAttribute('id');
                ghost.style.width = rect.width + 'px';
                ghost.style.height = rect.height + 'px';
                document.body.appendChild(ghost);
                dragGhost = ghost;
                try {
                    e.dataTransfer.setDragImage(ghost, e.clientX - rect.left, e.clientY - rect.top);
                } catch (err) {
                    removeDragGhost();   // 미지원 브라우저 — 기본 드래그 이미지로 폴백
                }
                item.classList.add('is-dragging');
            });

            item.addEventListener('dragend', function () {
                item.classList.remove('is-dragging');
                removeDragGhost();
            });

            // 같은 항목을 다시 클릭하면 선택 해제 + 해당 드롭존 비움
            var activate = function () {
                if (item.classList.contains('is-selected')) {
                    if (isExec) { clearExecZone(); return; }
                    // 코어 해제 시 이 코어의 페어링으로 채워진 exec 존도 함께 해제.
                    // 사용자가 직접 넣은 exec(업로드 파일 또는 다른 독립 exec 선택)는 유지.
                    var pairedExec = item.dataset.exec || null;
                    var execInput = document.getElementById('execFileInput');
                    var manualUpload = !!(execInput && execInput.files && execInput.files[0]);
                    if (pairedExec && !manualUpload && _preloadedExecFilename === pairedExec) {
                        clearExecZone();
                    }
                    clearCoreZone();
                    return;
                }
                clearFileListSelection(isExec ? 'exec' : 'coredump');
                item.classList.add('is-selected');
                if (isExec) {
                    preloadExistingExec(item.dataset.filename);
                } else {
                    preloadExisting(item.dataset.filename, item.dataset.exec || null);
                    // 페어링된 exec 는 목록에 없음(숨김) — 수동 선택된 독립 exec 항목만 해제
                    clearFileListSelection('exec');
                }
            };
            item.addEventListener('click', activate);
            item.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); activate(); }
            });
        });

        var search = document.getElementById('cdFileSearch');
        var searchWrap = document.getElementById('cdFileSearchWrap');
        var searchClear = document.getElementById('cdFileSearchClear');
        var statusFilter = document.getElementById('cdFileStatusFilter');
        var typeFilter = document.getElementById('cdFileTypeFilter');
        var noResult = document.getElementById('cdFileNoResult');
        var countEl = document.getElementById('cdFileCount');
        var pgBar = document.getElementById('cdFilePg');
        var pgList = document.getElementById('cdFilePgList');
        var pgInfo = document.getElementById('cdFilePgInfo');
        var PAGE = 20, cur = 1;
        var TOTAL = fileItems.length;

        function filtered() {
            var q = search ? search.value.trim().toLowerCase() : '';
            var status = statusFilter ? statusFilter.value : 'all';
            var type = typeFilter ? typeFilter.value : 'all';
            return fileItems.filter(function (item) {
                if (q && (item.dataset.filename || '').toLowerCase().indexOf(q) === -1) return false;
                if (status !== 'all' && (item.dataset.status || 'NOT_ANALYZED') !== status) return false;
                if (type !== 'all' && (item.dataset.type || 'coredump') !== type) return false;
                return true;
            });
        }
        function render() {
            var f = filtered(), total = f.length;
            var pages = Math.max(1, Math.ceil(total / PAGE));
            if (cur > pages) cur = pages; if (cur < 1) cur = 1;
            fileItems.forEach(function (i) { i.style.display = 'none'; });
            var start = (cur - 1) * PAGE, end = Math.min(start + PAGE, total);
            for (var i = start; i < end; i++) f[i].style.display = '';
            if (countEl) countEl.textContent = (total === TOTAL) ? total : (total + ' / ' + TOTAL);
            if (noResult) noResult.style.display = (TOTAL > 0 && total === 0) ? 'block' : 'none';
            renderPg(pgBar, pgList, pgInfo, total, pages, start, end, PAGE, cur, function (p) { cur = p; render(); });
        }
        function onFilter() { cur = 1; render(); }
        if (search) {
            search.addEventListener('input', function () {
                if (searchWrap) searchWrap.classList.toggle('has-value', !!search.value);
                onFilter();
            });
        }
        if (searchClear) searchClear.addEventListener('click', function () {
            search.value = ''; if (searchWrap) searchWrap.classList.remove('has-value'); onFilter(); search.focus();
        });
        if (statusFilter) statusFilter.addEventListener('change', onFilter);
        if (typeFilter) typeFilter.addEventListener('change', onFilter);
        render();
    }

    // ── 이력 테이블: 검색/정렬/페이지네이션 ──────────────────────
    function initHistory() {
        var tbody = document.getElementById('hiTbody');
        if (!tbody) return;
        var rows = Array.prototype.slice.call(tbody.querySelectorAll('tr'));
        var search = document.getElementById('hiSearch');
        var countEl = document.getElementById('hiCount');
        var noResult = document.getElementById('hiNoResult');
        var pgBar = document.getElementById('hiPg');
        var pgList = document.getElementById('hiPgList');
        var pgInfo = document.getElementById('hiPgInfo');
        var PAGE = 20, cur = 1, TOTAL = rows.length;
        var sortKey = null, sortDir = 1;

        function filtered() {
            var q = search ? search.value.trim().toLowerCase() : '';
            var f = rows.filter(function (r) {
                return !q || (r.dataset.fname || '').toLowerCase().indexOf(q) !== -1;
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

    // 공통 페이지네이션 렌더
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
        add('‹', cur - 1, { disabled: cur === 1 });
        var set = {}; set[1] = true; set[pages] = true; set[cur] = true;
        for (var d = 1; d <= 2; d++) { if (cur - d >= 1) set[cur - d] = true; if (cur + d <= pages) set[cur + d] = true; }
        var sorted = Object.keys(set).map(Number).sort(function (a, b) { return a - b; });
        var prev = 0;
        sorted.forEach(function (p) {
            if (p - prev > 1) { var s = document.createElement('span'); s.className = 'cd-pg-ellipsis'; s.textContent = '…'; pgList.appendChild(s); }
            add(String(p), p, { active: p === cur }); prev = p;
        });
        add('›', cur + 1, { disabled: cur === pages });
        if (pgInfo) pgInfo.textContent = (start + 1) + '–' + end + ' / 전체 ' + total;
    }

    // ── 드롭존 + URL preload + 초기화 ────────────────────────────
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
                    if (zoneId === 'coreDropZone') {
                        var nm = e.dataTransfer.getData('text/plain');
                        var ex = e.dataTransfer.getData('application/x-core-exec');
                        if (nm) preloadExisting(nm, ex || null);
                    } else {
                        var exNm = e.dataTransfer.getData('application/x-core-exec-standalone');
                        if (exNm) preloadExistingExec(exNm);
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
                    _preloadedFilename = null; _preloadedStatus = null; resetCta();
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

        initSidebar();
        initHistory();

        // ESC 로 모달 닫기
        document.addEventListener('keydown', function (e) {
            if (e.key !== 'Escape') return;
            var del = document.getElementById('deleteDumpModal');
            if (del && del.classList.contains('open')) closeDumpDeleteModal();
            var re = document.getElementById('reanalyzeModal');
            if (re && re.classList.contains('open')) closeReanalyzeModal();
        });
    });

    window.onCoreFileSelect = onCoreFileSelect;
    window.onExecFileSelect = onExecFileSelect;
    window.clearCoreZone = clearCoreZone;
    window.clearExecZone = clearExecZone;
    window.startUpload = startUpload;
    window.reanalyze = reanalyze;
    window.cdRowAction = cdRowAction;
    window.focusUpload = focusUpload;
    window.deleteDump = deleteDump;
    window.closeDumpDeleteModal = closeDumpDeleteModal;
    window.confirmDumpDelete = confirmDumpDelete;
    window.closeReanalyzeModal = closeReanalyzeModal;
    window.viewExistingResult = viewExistingResult;
    window.confirmReanalyzeNew = confirmReanalyzeNew;
})();
