/* core-dump-index.js — 코어 파일 화면(/core-dump, templates/core-dump/index.html)
 *
 * 2026-11-26 개편(시안 A '한 표 작업대'): 좌측 '서버 코어 파일' 목록과 '분석 이력' 표를 한 표(#hiTable)로 합쳤다.
 *   - 새로 분석: 코어/실행 파일 칸(드래그·선택) + XHR 업로드(실제 진행률). 업로드는 multipart 라 Common.fetchJSON 대신 XHR.
 *   - 표: 상태 탭 · 검색(파일명·크래시 함수) · 업로드일 기간(calendar.js) · '같은 지점' 묶음 · 실행 파일 행 표시 ·
 *         정렬 · 페이지 · 행 펼치기(백트레이스 요약) · 키보드(↑↓ 이동, Enter 펼치기).
 *   - 표의 한 파일 = tr.cf-row + 바로 다음 tr.cf-detail(실행 파일 행은 상세 없음). 정렬·페이지는 이 쌍을 함께 옮긴다.
 *   - 서버 파일을 업로드 칸에 넣는 경로(예전 사이드바 클릭·드래그)는 '실행 파일 연결' 버튼(cfPrepareExec)·
 *     실행 파일 행의 '위 칸에 넣기'(cfUseExec)·?file=&exec= 링크로 남았다. 이후 분기(재분석 모달 등)는 종전 그대로.
 */
(function () {
    'use strict';

    /* 삼킨 예외 기록 (2026-09-09) — Common 이 없으면 no-op */
    var TAG = '[CoreDumpUpload]';
    function ignored(where, e) { if (window.Common) window.Common.logIgnored(TAG + ' ' + where, e); }
    function failed(where, e)  { if (window.Common) window.Common.logError(TAG + ' ' + where, e); }

    var _preloadedFilename = null;
    var _preloadedExecFilename = null;
    var _preloadedStatus = null;
    // 프리로드 시점의 서버 페어링 — 현재 선택과 비교해 "분석 조건이 바뀌었는지" 판정
    var _originalExecPairing = null;

    // banner.html 이 common.js 를 선로드 — Common.* 직접 위임
    function csrfHeaders(json) {
        var h = json ? { 'Content-Type': 'application/json' } : {};
        var t = Common.csrfToken();
        if (t) h[Common.csrfHeaderName()] = t;
        return h;
    }
    var fmtBytes = Common.formatBytes;

    function $(id) { return document.getElementById(id); }
    function cssEsc(s) { return (window.CSS && CSS.escape) ? CSS.escape(s) : String(s).replace(/["\\]/g, '\\$&'); }

    // ── 토스트 (alert 대체) ──────────────────────────────────────
    function toast(msg, type) {
        var wrap = $('cdToastWrap');
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

    // ── 표의 행 조회 ─────────────────────────────────────────────
    function rowOf(filename) {
        if (!filename) return null;
        return document.querySelector('#hiTbody tr.cf-row[data-filename="' + cssEsc(filename) + '"]');
    }
    function statusOf(filename) {
        var row = rowOf(filename);
        return row ? (row.dataset.status || 'NOT_ANALYZED') : 'NOT_ANALYZED';
    }

    // ── 업로드 칸 표시 ───────────────────────────────────────────
    function setUploadLabel(text) {
        var el = $('uploadBtnLabel');
        if (el) el.textContent = text;
    }
    /* 서버 파일(프리로드)이 분석 완료본이어도 문구는 'GDB 분석 시작' — 클릭 동작은 상태별 분기(startPreloadedAnalysis). */
    function applyCtaForStatus(status) {
        var btn = $('uploadBtn');
        if (!btn) return;
        btn.disabled = false;
        setUploadLabel(status === 'ANALYZING' ? '진행 확인' : 'GDB 분석 시작');
    }
    function resetCta() { setUploadLabel('GDB 분석 시작'); }

    /* 칸의 파일명도 표와 같은 가운데 말줄임 — 앞(.cf-fname-h)만 줄고 끝 12자(.cf-fname-t)는 남는다.
       기준(24자 초과·끝 12자)은 CoreFileRow.getNameHead/Tail 과 같다. textContent 는 여전히 전체 이름이다. */
    function setNameParts(el, name) {
        el.textContent = '';
        if (!name) return;
        var cut = name.length > 24 ? name.length - 12 : name.length;
        var head = document.createElement('span');
        head.className = 'cf-fname-h';
        head.textContent = name.substring(0, cut);
        el.appendChild(head);
        if (cut < name.length) {
            var tail = document.createElement('span');
            tail.className = 'cf-fname-t';
            tail.textContent = name.substring(cut);
            el.appendChild(tail);
        }
    }

    function setSlot(kind, name, sizeText) {
        var zone = $(kind + 'DropZone');
        setNameParts($(kind + 'FileName'), name);
        var size = $(kind + 'FileSize');
        if (size) size.textContent = sizeText || '';
        zone.classList.toggle('has-file', !!name);
        if (name) zone.title = name; else zone.removeAttribute('title');
    }
    function slotHasFile(kind) { return $(kind + 'DropZone').classList.contains('has-file'); }

    /** 서버에 있는 실행 파일 크기 — 실행 파일 행(연결 안 된 것)에만 있다. 모르면 ''. */
    function execSizeLabel(name) {
        var row = rowOf(name);
        return row && row.dataset.type === 'exec' ? (row.dataset.sizeLabel || '') : '';
    }

    function onCoreFileSelect(input) {
        var file = input.files[0];
        if (!file) return;
        _preloadedFilename = null; _preloadedStatus = null; _originalExecPairing = null;
        setSlot('core', file.name, fmtBytes(file.size));
        resetCta();
        $('uploadBtn').disabled = false;
        afterSlotChange();
    }

    function onExecFileSelect(input) {
        var file = input.files[0];
        if (!file) return;
        _preloadedExecFilename = null;
        setSlot('exec', file.name, fmtBytes(file.size));
        afterSlotChange();
    }

    /**
     * 칸 상태가 바뀔 때마다 안내를 다시 계산한다.
     * 두 칸이 각자 채워졌다는 사실은 칸 모양이 보여 주므로, 문구는 '실행 파일만 고른' 경우에만 남긴다.
     */
    function afterSlotChange() {
        var status = $('uploadStatus');
        if (status) status.textContent = (!slotHasFile('core') && slotHasFile('exec'))
            ? '실행 파일만 골랐습니다 — 코어 파일을 함께 선택하세요.' : '';
        updateExecSuggest();
    }

    function preloadExisting(filename, execName) {
        if (!filename) return;
        _preloadedFilename = filename;
        _preloadedStatus = statusOf(filename);
        _originalExecPairing = execName || null;
        var coreInput = $('coreFileInput');
        if (coreInput) coreInput.value = '';
        var row = rowOf(filename);
        setSlot('core', filename, row ? (row.dataset.sizeLabel || '') : '');
        applyCtaForStatus(_preloadedStatus);
        var execInput = $('execFileInput');
        if (execInput) execInput.value = '';
        _preloadedExecFilename = execName || null;
        setSlot('exec', execName || '', execName ? execSizeLabel(execName) : '');
        afterSlotChange();
    }

    function preloadExistingExec(execName) {
        if (!execName) return;
        _preloadedExecFilename = execName;
        var execInput = $('execFileInput');
        if (execInput) execInput.value = '';
        setSlot('exec', execName, execSizeLabel(execName));
        afterSlotChange();
    }

    function clearCoreZone(e) {
        if (e) e.stopPropagation();
        _preloadedFilename = null; _preloadedStatus = null; _originalExecPairing = null;
        var coreInput = $('coreFileInput');
        if (coreInput) coreInput.value = '';
        setSlot('core', '', '');
        $('uploadBtn').disabled = true;
        resetCta();
        var errBox = $('uploadErrorBox');
        if (errBox) errBox.classList.remove('visible');
        afterSlotChange();   // 실행 파일만 남았다면 그 상태의 문구로 되돌아간다
    }

    function clearExecZone(e) {
        if (e) e.stopPropagation();
        _preloadedExecFilename = null;
        var execInput = $('execFileInput');
        if (execInput) execInput.value = '';
        setSlot('exec', '', '');
        afterSlotChange();
    }

    function flashSlot(kind) {
        var zone = $(kind + 'DropZone');
        if (!zone) return;
        zone.scrollIntoView({ behavior: Common.reduceMotion && Common.reduceMotion() ? 'auto' : 'smooth', block: 'center' });
        zone.classList.add('is-flash');
        setTimeout(function () { zone.classList.remove('is-flash'); }, 900);
        var input = zone.querySelector('input[type=file]');
        if (input) {
            try { input.focus({ preventScroll: true }); }
            catch (err) { ignored('포커스 옵션 미지원 — 기본 focus', err); input.focus(); }
        }
    }

    /** 표에서 '실행 파일 연결/바꾸기/바꿔 재분석' — 서버의 코어를 업로드 칸에 올리고 실행 파일 칸으로 안내. */
    function cfPrepareExec(filename, execName) {
        preloadExisting(filename, execName || null);
        flashSlot('exec');
    }

    /** 실행 파일 행의 '위 칸에 넣기' — 서버의 실행 파일을 실행 파일 칸에 넣는다. */
    function cfUseExec(execName) {
        preloadExistingExec(execName);
        flashSlot(slotHasFile('core') ? 'exec' : 'core');
    }

    function focusUpload() { flashSlot('core'); }

    // ── 실행 파일 제안: 코어 이름(core.<프로그램>.<pid>)과 맞는, 어느 코어에도 연결되지 않은 서버 실행 파일 ──
    // 이미 다른 코어에 연결된 실행 파일은 제안하지 않는다 — 코어를 지우면 연결된 실행 파일도 함께 지워져
    // (CoreDumpAnalyzerService.deleteDump) 공유하던 다른 코어가 실행 파일을 잃는다.
    function programOf(name) {
        var t = String(name || '').split(/[.\-_]/);
        if (t.length >= 2 && t[0].toLowerCase() === 'core' && t[1] && !/^\d+$/.test(t[1])) return t[1];
        return null;
    }
    var _suggested = null;
    function updateExecSuggest() {
        var box = $('cfExecSuggest');
        if (!box) return;
        _suggested = null;
        var coreInput = $('coreFileInput');
        var coreName = _preloadedFilename || (coreInput && coreInput.files && coreInput.files[0] ? coreInput.files[0].name : null);
        var prog = (slotHasFile('core') && !slotHasFile('exec')) ? programOf(coreName) : null;
        if (prog) {
            var rows = document.querySelectorAll('#hiTbody tr.cf-row[data-type="exec"]');
            for (var i = 0; i < rows.length; i++) {
                var n = rows[i].dataset.filename || '';
                if (n === prog || n.indexOf(prog + '.') === 0) { _suggested = rows[i]; break; }
            }
        }
        box.hidden = !_suggested;
        if (!_suggested) return;
        $('cfExecSuggestName').textContent = _suggested.dataset.filename;
        var sz = _suggested.dataset.sizeLabel;
        $('cfExecSuggestSize').textContent = sz ? ' (' + sz + ')' : '';
    }

    // ── 서버 파일 프리로드 시 exec 선택 상태 ─────────────────────
    // 프리로드된 코어는 업로드 없이 서버 파일을 그대로 쓴다. 이때 사용자가 실행 파일을
    // 새로 넣거나/바꾸거나/뺐다면 서버 페어링을 먼저 맞춰야 분석에 반영된다.
    function currentExecSelection() {
        var execInput = $('execFileInput');
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

    function showUploadError(msg) {
        $('uploadErrorMsg').textContent = msg;
        $('uploadErrorBox').classList.add('visible');
    }

    // ── 업로드 제출 (XHR 실 진행률) ──────────────────────────────
    function startUpload() {
        if (_preloadedFilename) {
            startPreloadedAnalysis();
            return;
        }
        var coreInput = $('coreFileInput');
        if (!coreInput.files || !coreInput.files[0]) { toast('코어 덤프 파일을 선택해주세요.', 'danger'); return; }

        var btn = $('uploadBtn');
        var fill = $('uploadProgressFill');
        btn.disabled = true;
        $('uploadErrorBox').classList.remove('visible');
        var statusEl = $('uploadStatus');

        var fd = new FormData();
        fd.append('coreFile', coreInput.files[0]);
        var execInput = $('execFileInput');
        var localExec = !!(execInput.files && execInput.files[0]);
        if (localExec) fd.append('execFile', execInput.files[0]);
        // 서버에 있는 실행 파일을 고른 경우(제안·실행 파일 행) — 업로드 API 는 파일만 받으므로 업로드 뒤 따로 연결한다
        var existingExec = !localExec ? _preloadedExecFilename : null;

        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/api/core-dump/upload');
        var csrfToken = Common.csrfToken();
        if (csrfToken) xhr.setRequestHeader(Common.csrfHeaderName(), csrfToken);

        xhr.upload.onprogress = function (e) {
            if (!e.lengthComputable) return;
            var pct = Math.round(e.loaded / e.total * 100);
            if (fill) fill.style.width = pct + '%';
            setUploadLabel('업로드 중 ' + pct + '% · ' + fmtBytes(e.loaded) + ' / ' + fmtBytes(e.total));
        };
        xhr.onload = function () {
            if (xhr.status >= 200 && xhr.status < 300) {
                var d = null;
                try { d = JSON.parse(xhr.responseText); }
                catch (e) {
                    // 예전엔 삼키고 진행해 /core-dump/progress/undefined 로 이동했다.
                    failed('업로드 응답 파싱 실패 — 이동하지 않는다', e);
                }
                if (!d || !d.filename) {
                    uploadFailed('업로드는 끝났지만 서버 응답을 해석하지 못했습니다. 목록에서 상태를 확인해 주세요.');
                    return;
                }
                if (fill) fill.style.width = '100%';
                var go = function () {
                    setUploadLabel('분석 진행 확인 →');
                    statusEl.textContent = '업로드 완료 — 분석 화면으로 이동합니다.';
                    setTimeout(function () {
                        window.location.href = '/core-dump/progress/' + encodeURIComponent(d.filename);
                    }, 400);
                };
                if (!existingExec) { go(); return; }
                setUploadLabel('실행 파일 연결 중...');
                // 올라간 코어를 프리로드 상태로 바꿔 두면 연결이 실패해도 같은 버튼으로 다시 시도할 수 있다
                _preloadedFilename = d.filename; _preloadedStatus = 'NOT_ANALYZED'; _originalExecPairing = null;
                coreInput.value = '';
                syncExecPairing(d.filename).then(go).catch(function (e) {
                    failed('업로드 뒤 실행 파일 연결 실패', e);
                    if (fill) fill.style.width = '0%';
                    resetCta();
                    btn.disabled = false;
                    showUploadError('코어 파일은 올렸지만 실행 파일을 연결하지 못했습니다: ' + e.message
                        + ' — 버튼을 다시 누르면 연결부터 다시 시도합니다.');
                });
            } else {
                var msg = '서버 오류 (' + xhr.status + ')';
                try { msg = JSON.parse(xhr.responseText).message || msg; }
                catch (e) { ignored('오류 응답이 JSON 이 아님 — HTTP 상태 문구를 쓴다', e); }
                uploadFailed(msg);
            }
        };
        xhr.onerror = function () { uploadFailed('네트워크 오류로 업로드에 실패했습니다.'); };
        xhr.send(fd);

        function uploadFailed(msg) {
            if (fill) fill.style.width = '0%';
            resetCta();
            statusEl.textContent = '';
            showUploadError(msg);
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

        var btn = $('uploadBtn');
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
                showUploadError(e.message);
            });
    }

    // ── 재분석 확인 모달 (이미 분석된 코어 + exec 구성 변경) ─────
    var _reanalyzeTarget = null;

    function openReanalyzeModal(core) {
        _reanalyzeTarget = core;
        $('reanalyzeModalFilename').textContent = core;

        var sel = currentExecSelection();
        var diff = $('reanalyzeModalDiff');
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

        var err = $('reanalyzeModalErr');
        err.textContent = ''; err.classList.remove('visible');
        var ok = $('reanalyzeConfirmBtn');
        ok.disabled = false; ok.textContent = '새로 분석';
        $('reanalyzeViewBtn').disabled = false;

        showModal($('reanalyzeModal'));
        ok.focus();
    }

    function closeReanalyzeModal() {
        hideModal($('reanalyzeModal'));
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
        var ok = $('reanalyzeConfirmBtn');
        var view = $('reanalyzeViewBtn');
        var err = $('reanalyzeModalErr');
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
        // 작업 칸의 아이콘 버튼(.fb)은 글자 대신 회전 표시로 — textContent 를 바꾸면 아이콘이 지워진다
        var icon = !!(btn && btn.classList.contains('fb'));
        var label = btn && !icon ? btn.textContent : '';
        function restore() {
            if (!btn) return;
            btn.disabled = false;
            if (icon) btn.classList.remove('is-busy'); else btn.textContent = label;
        }
        if (btn) { btn.disabled = true; if (icon) btn.classList.add('is-busy'); else btn.textContent = '요청 중...'; }
        fetch('/api/core-dump/reanalyze/' + encodeURIComponent(filename), { method: 'POST', headers: csrfHeaders(true) })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (d.status === 'ok') {
                    window.location.href = '/core-dump/progress/' + encodeURIComponent(filename);
                } else {
                    toast('재분석 요청 실패: ' + (d.message || '알 수 없는 오류'), 'danger');
                    restore();
                }
            })
            .catch(function (e) {
                toast('재분석 중 오류: ' + e.message, 'danger');
                restore();
            });
    }

    // ── 모달 열기·닫기 연출 (2026-11-27) — GC 로그 화면(gc-log-index.js)과 같은 방식 ──────────────
    // 열기: .open(배경 페이드 + 상자가 살짝 올라오며 커진다). 닫기: .closing 연출이 끝난 뒤에 .open 을 뗀다
    // (먼저 떼면 display:none 이 되어 사라지는 모습이 통째로 잘린다). 닫히는 사이 다시 열리면 뒤늦은 떼기를 버린다.
    // .open 을 직접 붙이고 떼는 곳은 이 두 함수뿐이어야 한다(그 밖에서 만지면 연출을 건너뛴 경로가 생긴다).
    function showModal(ov) {
        if (!ov) return;
        ov._cfSeq = (ov._cfSeq || 0) + 1;
        ov.classList.remove('closing');
        ov.classList.add('open');
    }
    function hideModal(ov) {
        if (!ov || !ov.classList.contains('open') || ov.classList.contains('closing')) return;
        var seq = ov._cfSeq = (ov._cfSeq || 0) + 1;
        var fin = function () { if (ov._cfSeq === seq) ov.classList.remove('open', 'closing'); };
        if (!Common.afterMotion || Common.reduceMotion()) { fin(); return; }
        ov.classList.add('closing');
        Common.afterMotion(ov, 'animationend', 260, fin);
    }

    // ── 삭제 모달 ────────────────────────────────────────────────
    var _deleteTargetFilename = null;
    var _deleteReturnFocus = null;

    function deleteDump(filename) {
        _deleteTargetFilename = filename;
        _deleteReturnFocus = document.activeElement;
        $('deleteDumpModalFilename').textContent = filename;
        $('deleteFileChk').checked = false;
        var m = $('deleteDumpModal');
        showModal(m);
        var cancel = m.querySelector('.mbtn-cancel');
        if (cancel) cancel.focus();
    }
    function closeDumpDeleteModal() {
        hideModal($('deleteDumpModal'));
        _deleteTargetFilename = null;
        if (_deleteReturnFocus && document.contains(_deleteReturnFocus)) _deleteReturnFocus.focus();
        _deleteReturnFocus = null;
    }
    function confirmDumpDelete() {
        if (!_deleteTargetFilename) return;
        var filename = _deleteTargetFilename;
        var deleteFile = $('deleteFileChk').checked;
        var btn = $('deleteDumpConfirmBtn');
        btn.disabled = true; btn.textContent = '삭제 중…';
        var url = '/api/core-dump/' + encodeURIComponent(filename) + '?deleteFile=' + deleteFile;
        fetch(url, { method: 'DELETE', headers: csrfHeaders(true) })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (d.status === 'ok') { _deleteReturnFocus = null; closeDumpDeleteModal(); window.location.reload(); }
                else { toast('삭제 실패: ' + (d.message || '알 수 없는 오류'), 'danger'); btn.disabled = false; btn.textContent = '삭제'; }
            })
            .catch(function (e) {
                toast('삭제 중 오류: ' + e.message, 'danger'); btn.disabled = false; btn.textContent = '삭제';
            });
    }

    // ── 표: 상태 탭 · 검색 · 기간 · 같은 지점 · 실행 파일 표시 · 정렬 · 페이지 · 펼치기 · 키보드 ──
    function initTable() {
        var tbody = $('hiTbody');
        if (!tbody) return;
        var entries = Array.prototype.slice.call(tbody.querySelectorAll('tr.cf-row')).map(function (row) {
            var next = row.nextElementSibling;
            var ds = row.dataset;
            return {
                row: row,
                detail: (next && next.classList.contains('cf-detail')) ? next : null,
                chev: row.querySelector('.cf-chev'),
                open: false,
                isExec: ds.type === 'exec',
                status: ds.status || 'NOT_ANALYZED',
                text: ((ds.fname || '') + ' ' + (ds.fn || '')).toLowerCase()
            };
        });
        var search = $('hiSearch');
        var showExec = $('cfShowExec');
        var countEl = $('hiCount');
        var noResult = $('hiNoResult');
        var pgBar = $('hiPg'), pgList = $('hiPgList'), pgInfo = $('hiPgInfo');
        var sizeSel = $('cfPageSize');
        var tabs = Array.prototype.slice.call(document.querySelectorAll('.cf-tab'));
        var repeatBtns = Array.prototype.slice.call(document.querySelectorAll('.cf-repeat-btn'));
        var CORE_TOTAL = entries.filter(function (en) { return !en.isExec; }).length;
        var EXEC_TOTAL = entries.length - CORE_TOTAL;
        var STATUS_ORDER = { ERROR: 0, ANALYZING: 1, NOT_ANALYZED: 2, SUCCESS: 3, EXEC: 4 };
        var PS_KEY = 'coreFilesPageSize';

        var st = { status: 'all', repeat: null, sortKey: 'uploaded', sortDir: -1, page: 1, pageSize: 20 };
        try {
            var saved = parseInt(localStorage.getItem(PS_KEY), 10);
            if (saved === 20 || saved === 50 || saved === 100) st.pageSize = saved;
        } catch (e) { ignored('localStorage 읽기 불가 — 기본 20개', e); }
        if (sizeSel) sizeSel.value = String(st.pageSize);

        // 업로드일 기간 — Files 와 같은 calendar.js 위젯. 배너가 전역 로드하지만 없으면 필터 없이 동작
        var hasCal = !!(window.Calendar && $('hiCalArea'));
        function pad2(n) { return (n < 10 ? '0' : '') + n; }
        function dayKey(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }

        /** 상태 탭을 뺀 나머지 조건 — 탭 옆 건수는 이 결과로 센다. */
        function baseMatch(en) {
            if (en.isExec && !(showExec && showExec.checked)) return false;
            var q = search ? search.value.trim().toLowerCase() : '';
            if (q && en.text.indexOf(q) === -1) return false;
            if (st.repeat && en.row.dataset.repeat !== st.repeat) return false;
            if (hasCal) {
                var range = window.Calendar.getRange() || {};
                var from = range.start ? dayKey(range.start) : '';
                var to = range.end ? dayKey(range.end) : '';
                if (from || to) {
                    // data-uploaded 는 LocalDateTime ISO('2026-09-13T10:05:23') — 앞 10자 문자열 비교(하루 단위, 종료일 포함)
                    var d = (en.row.dataset.uploaded || '').substring(0, 10);
                    if (!d) return false;   // 기간을 골랐는데 날짜가 없는 행은 기간 안이라고 볼 근거가 없다
                    if (from && d < from) return false;
                    if (to && d > to) return false;
                }
            }
            return true;
        }
        function statusMatch(en) {
            if (st.status === 'all') return true;
            return !en.isExec && en.status === st.status;
        }
        function sortVal(en) {
            var ds = en.row.dataset;
            switch (st.sortKey) {
                case 'size': return parseFloat(ds.size) || 0;
                case 'status': return STATUS_ORDER.hasOwnProperty(en.status) ? STATUS_ORDER[en.status] : 9;
                case 'signal': return ds.signal || '';
                case 'fname': return (ds.fname || '').toLowerCase();
                default: return ds.uploaded || '';
            }
        }
        function sorted(list) {
            return list.map(function (en, i) { return { en: en, i: i, v: sortVal(en) }; })
                .sort(function (a, b) {
                    var av = a.v, bv = b.v;
                    // 빈 값(날짜·시그널 없음)은 방향과 무관하게 뒤로
                    if (av === '' && bv !== '') return 1;
                    if (bv === '' && av !== '') return -1;
                    if (av < bv) return -st.sortDir;
                    if (av > bv) return st.sortDir;
                    return a.i - b.i;
                })
                .map(function (x) { return x.en; });
        }

        function setOpen(en, open) {
            if (!en.detail) return;
            en.open = open;
            en.row.classList.toggle('is-open', open);
            en.detail.hidden = !open;
            if (en.chev) en.chev.setAttribute('aria-expanded', open ? 'true' : 'false');
        }

        function render() {
            var base = entries.filter(baseMatch);
            var counts = { all: 0, SUCCESS: 0, ERROR: 0, ANALYZING: 0, NOT_ANALYZED: 0 };
            base.forEach(function (en) {
                counts.all++;
                if (!en.isExec && counts.hasOwnProperty(en.status)) counts[en.status]++;
            });
            tabs.forEach(function (t) {
                var n = t.querySelector('.cf-tab-n');
                if (n) n.textContent = counts[t.dataset.status] != null ? counts[t.dataset.status] : 0;
                var on = t.dataset.status === st.status;
                if (t.getAttribute('aria-pressed') !== String(on)) t.setAttribute('aria-pressed', String(on));
            });

            var list = sorted(base.filter(statusMatch));
            var total = list.length;
            var pages = Math.max(1, Math.ceil(total / st.pageSize));
            if (st.page > pages) st.page = pages;
            if (st.page < 1) st.page = 1;
            var start = (st.page - 1) * st.pageSize, end = Math.min(start + st.pageSize, total);

            entries.forEach(function (en) {
                en.row.style.display = 'none';
                if (en.detail) en.detail.hidden = true;
            });
            for (var i = start; i < end; i++) {
                var en = list[i];
                en.row.style.display = '';
                tbody.appendChild(en.row);
                if (en.detail) { tbody.appendChild(en.detail); en.detail.hidden = !en.open; }
            }
            // 전체 = 코어 수(+ '실행 파일 표시' 를 켰으면 실행 파일 수) — 거른 결과와 같은 기준으로 센다
            var all = CORE_TOTAL + (showExec && showExec.checked ? EXEC_TOTAL : 0);
            if (countEl) countEl.textContent = (total === all ? total + '개' : total + '개 표시 · 전체 ' + all + '개');
            if (noResult) noResult.hidden = !(entries.length > 0 && total === 0);
            renderPg(pgBar, pgList, pgInfo, total, pages, start, end, st.pageSize, st.page, function (p) { st.page = p; render(); });
        }
        function refilter() { st.page = 1; render(); }

        // 상태 탭
        tabs.forEach(function (t) {
            t.addEventListener('click', function () { st.status = t.dataset.status; refilter(); });
        });
        // 같은 지점 묶음 — 다시 누르면 해제
        repeatBtns.forEach(function (b) {
            b.addEventListener('click', function () {
                st.repeat = (st.repeat === b.dataset.repeatKey) ? null : b.dataset.repeatKey;
                repeatBtns.forEach(function (o) {
                    var on = o.dataset.repeatKey === st.repeat;
                    o.setAttribute('aria-pressed', String(on));
                    var act = o.querySelector('.cf-repeat-act');
                    if (act) act.textContent = on ? '전체 보기' : '모아 보기';
                });
                refilter();
            });
        });
        if (search) search.addEventListener('input', refilter);
        if (showExec) showExec.addEventListener('change', refilter);
        if (sizeSel) sizeSel.addEventListener('change', function () {
            st.pageSize = parseInt(sizeSel.value, 10) || 20;
            try { localStorage.setItem(PS_KEY, String(st.pageSize)); }
            catch (e) { ignored('localStorage 쓰기 불가 — 이번 화면에서만 적용', e); }
            refilter();
        });
        if (hasCal) {
            // 첫 render() 전에 붙여야 localStorage 에 남은 기간이 초기 목록에 반영된다
            window.Calendar.attach({
                startInputId: 'hiDateStart', endInputId: 'hiDateEnd',
                areaId: 'hiCalArea',
                storageKey: 'coreHistory',
                onChange: refilter
            });
        }

        // 정렬 — 같은 열을 다시 누르면 방향 반전. 크기·업로드는 큰/최근 것부터, 나머지는 오름차순부터
        Array.prototype.slice.call(document.querySelectorAll('#hiTable th[data-sort-key]')).forEach(function (th) {
            var btn = th.querySelector('.cf-sortbtn');
            if (!btn) return;
            btn.addEventListener('click', function () {
                var key = th.dataset.sortKey;
                if (st.sortKey === key) st.sortDir = -st.sortDir;
                else { st.sortKey = key; st.sortDir = (key === 'size' || key === 'uploaded') ? -1 : 1; }
                // 표시는 Files(table-grid.js)와 같다 — 비활성 ▲(흐리게, CSS), 활성 th.sort-active + ▲ 오름 / ▼ 내림
                document.querySelectorAll('#hiTable th[data-sort-key]').forEach(function (o) {
                    o.removeAttribute('aria-sort');
                    o.classList.remove('sort-active');
                    var ind = o.querySelector('.cf-sort-ind');
                    if (ind) ind.textContent = '▲';
                });
                th.setAttribute('aria-sort', st.sortDir === 1 ? 'ascending' : 'descending');
                th.classList.add('sort-active');
                var ind = th.querySelector('.cf-sort-ind');
                if (ind) ind.textContent = st.sortDir === 1 ? '▲' : '▼';
                refilter();
            });
        });

        // 펼치기 — 화살표·파일명 버튼, 또는 행의 빈 곳 클릭
        entries.forEach(function (en) {
            if (!en.detail) return;
            var fname = en.row.querySelector('button.cf-fname');
            if (en.chev) en.chev.addEventListener('click', function () { setOpen(en, !en.open); });
            if (fname) fname.addEventListener('click', function () { setOpen(en, !en.open); });
            en.row.addEventListener('click', function (e) {
                if (e.target.closest('a, button, input, label, select')) return;
                if (window.getSelection && String(window.getSelection())) return;   // 글자를 고르던 중이면 접지 않는다
                setOpen(en, !en.open);
            });
        });

        // 상세 칸 colspan = 지금 보이는 열 수. 본문이 좁아지면 CSS 가 실행 파일·크기 열을 접는데(core-dump-index.css
        // @container), colspan 이 9 로 남아 있으면 '없는 열'이 생겨 table-layout: fixed 가 남는 폭을 크래시 지점 열과
        // 나눠 준다 — 표 오른쪽이 비고 크래시 지점이 반으로 줄었다. 두 줄 행 모드(<760px)에서는 칸이 block 이라 값이 무의미하다.
        var table = $('hiTable');
        var lastSpan = 0;
        function syncDetailSpan() {
            if (!table) return;
            var ths = table.querySelectorAll('thead th'), n = 0;
            for (var i = 0; i < ths.length; i++) if (window.getComputedStyle(ths[i]).display !== 'none') n++;
            if (!n || n === lastSpan) return;
            lastSpan = n;
            entries.forEach(function (en) {
                var cell = en.detail && en.detail.cells[0];
                if (cell) cell.colSpan = n;
            });
        }
        syncDetailSpan();
        if (table && window.ResizeObserver) new ResizeObserver(syncDetailSpan).observe(table);
        else window.addEventListener('resize', syncDetailSpan);

        // 키보드 — 행에 초점이 있을 때만(행 안 버튼은 제 동작 그대로)
        tbody.addEventListener('keydown', function (e) {
            var row = e.target;
            if (!row.classList || !row.classList.contains('cf-row')) return;
            var en = null;
            for (var i = 0; i < entries.length; i++) if (entries[i].row === row) { en = entries[i]; break; }
            if (!en) return;
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                e.preventDefault();
                var rows = Array.prototype.slice.call(tbody.querySelectorAll('tr.cf-row'))
                    .filter(function (r) { return r.style.display !== 'none'; });
                var idx = rows.indexOf(row) + (e.key === 'ArrowDown' ? 1 : -1);
                if (idx >= 0 && idx < rows.length) rows[idx].focus();
            } else if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                if (en.detail) setOpen(en, !en.open);
            }
            // Delete 키 삭제는 두지 않는다(2026-11-27 사용자 요청) — 삭제는 작업 칸의 휴지통 버튼으로만
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
            b.type = 'button';
            b.className = 'cd-pg-btn' + (opts.active ? ' active' : '');
            b.textContent = label;
            if (opts.label) b.setAttribute('aria-label', opts.label);
            if (opts.active) b.setAttribute('aria-current', 'page');
            if (opts.disabled) b.disabled = true;
            if (!opts.disabled && !opts.active) b.addEventListener('click', function () { goto(page); });
            pgList.appendChild(b);
        };
        add('‹', cur - 1, { disabled: cur === 1, label: '이전 페이지' });
        var set = {}; set[1] = true; set[pages] = true; set[cur] = true;
        for (var d = 1; d <= 2; d++) { if (cur - d >= 1) set[cur - d] = true; if (cur + d <= pages) set[cur + d] = true; }
        var nums = Object.keys(set).map(Number).sort(function (a, b) { return a - b; });
        var prev = 0;
        nums.forEach(function (p) {
            if (p - prev > 1) { var s = document.createElement('span'); s.className = 'cd-pg-ellipsis'; s.textContent = '…'; pgList.appendChild(s); }
            add(String(p), p, { active: p === cur }); prev = p;
        });
        add('›', cur + 1, { disabled: cur === pages, label: '다음 페이지' });
        if (pgInfo) pgInfo.textContent = (start + 1) + '–' + end + ' / ' + total;
    }

    // ── 업로드 칸 드래그 + URL preload + 초기화 ──────────────────
    document.addEventListener('DOMContentLoaded', function () {
        ['core', 'exec'].forEach(function (kind) {
            var zone = $(kind + 'DropZone');
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
                if (kind === 'core') {
                    $('coreFileInput').files = dt.files;
                    _preloadedFilename = null; _preloadedStatus = null; _originalExecPairing = null;
                    setSlot('core', file.name, fmtBytes(file.size));
                    $('uploadBtn').disabled = false;
                    resetCta();
                } else {
                    $('execFileInput').files = dt.files;
                    _preloadedExecFilename = null;
                    setSlot('exec', file.name, fmtBytes(file.size));
                }
                afterSlotChange();   // 드롭도 선택과 같은 규칙을 따른다
            });
        });

        var sugBtn = $('cfExecSuggestBtn');
        if (sugBtn) sugBtn.addEventListener('click', function () {
            if (_suggested) preloadExistingExec(_suggested.dataset.filename);
        });

        initTable();

        // Files 등에서 ?file=&exec= 로 들어오면 그 코어를 업로드 칸에 올려 둔다(표를 먼저 그려야 상태·크기를 읽는다)
        var params = new URLSearchParams(window.location.search);
        var preFile = params.get('file');
        var preExec = params.get('exec');
        if (preFile) {
            preloadExisting(preFile, preExec || null);
            history.replaceState({}, document.title, window.location.pathname);
        }

        // ESC 로 모달 닫기
        document.addEventListener('keydown', function (e) {
            if (e.key !== 'Escape') return;
            var del = $('deleteDumpModal');
            if (del && del.classList.contains('open')) closeDumpDeleteModal();
            var re = $('reanalyzeModal');
            if (re && re.classList.contains('open')) closeReanalyzeModal();
        });
    });

    window.onCoreFileSelect = onCoreFileSelect;
    window.onExecFileSelect = onExecFileSelect;
    window.clearCoreZone = clearCoreZone;
    window.clearExecZone = clearExecZone;
    window.startUpload = startUpload;
    window.reanalyze = reanalyze;
    window.focusUpload = focusUpload;
    window.cfPrepareExec = cfPrepareExec;
    window.cfUseExec = cfUseExec;
    window.deleteDump = deleteDump;
    window.closeDumpDeleteModal = closeDumpDeleteModal;
    window.confirmDumpDelete = confirmDumpDelete;
    window.closeReanalyzeModal = closeReanalyzeModal;
    window.viewExistingResult = viewExistingResult;
    window.confirmReanalyzeNew = confirmReanalyzeNew;
})();
