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

    // ── 모달 열기·닫기 연출 (2026-10-22) ─────────────────────────
    // 모양은 gc-log.css .gcl-modal(.open = 나타나기, .closing = 사라지기). 이 페이지의 모달(이어 붙여 분석·연속 로그 확인·삭제)은
    // 열고 닫을 때 이 둘만 거친다. ⚠ .open 은 사라지는 연출이 끝난 뒤에 떼고(먼저 떼면 연출이 잘린다), 끝 이벤트는
    // Common.afterMotion 의 시간 상한으로 기다리며, '동작 줄이기'면 기다리지 않는다. gc-log-analyze.js 와 같은 규칙.
    function showModal(ov) {
        if (!ov) return;
        ov._gclSeq = (ov._gclSeq || 0) + 1;   // 닫히는 사이 다시 열리면 뒤늦은 떼기를 버린다
        ov.classList.remove('closing');
        ov.classList.add('open');
    }
    function hideModal(ov) {
        if (!ov || !ov.classList.contains('open') || ov.classList.contains('closing')) return;
        var seq = ov._gclSeq = (ov._gclSeq || 0) + 1;
        var fin = function () { if (ov._gclSeq === seq) ov.classList.remove('open', 'closing'); };
        if (!Common.afterMotion || Common.reduceMotion()) { fin(); return; }
        ov.classList.add('closing');
        Common.afterMotion(ov, 'animationend', 260, fin);
    }

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
            autoGroupUploaded(okNames);   // 여러 개 — 연속 로그인지 서버가 판정해 묶는다(분석은 시작하지 않는다)
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
     * 여러 개를 올린 뒤 — <b>연속된 회전본인지 서버가 판정해 자동으로 묶는다</b>(2026-09-21).
     *
     * <p>⚠ 분석은 시작하지 않는다. 목록에서 묶음을 확인한 뒤 사용자가 '분석' 을 누른다.
     * 판정이 실패해도 파일은 안전하니 알리고 목록으로 보낸다(선택 모드로 직접 묶을 수 있다).
     */
    function autoGroupUploaded(names) {
        setCta('uploading', '연속 로그 판정 중…');
        var status = document.getElementById('uploadStatus');   // startUpload 의 지역 변수와 별개 — 여기서 다시 잡는다
        if (status) status.textContent = names.length + '개 업로드 완료 — 연속 로그인지 확인 중…';
        Common.fetchJSON('/api/gc-log/group/auto', { method: 'POST', body: JSON.stringify({ filenames: names }) })
            .then(function (d) { showGroupResult(names, d); })
            .catch(function (e) {
                failed('연속 로그 자동 판정', e);
                toast(serverErrMsg(e, '자동 묶기에 실패했습니다 — 목록에서 직접 묶을 수 있습니다.'), 'danger');
                setTimeout(function () { window.location.reload(); }, 800);
            });
    }

    /** 자동 판정 결과를 보여 준다 — 무엇이 묶였고 무엇이 왜 따로 남았는지. */
    function showGroupResult(names, d) {
        var groups = (d && d.groups) || [], singles = (d && d.singles) || [];
        var reasons = (d && d.singleReasons) || {};
        var body = document.getElementById('groupResultBody');
        if (!body) { window.location.reload(); return; }

        var html = '';
        if (groups.length) {
            html += '<p>' + names.length + '개를 올렸고 <b>' + groups.length + '개 묶음</b>으로 이어 붙였습니다. '
                 + '목록에서 확인한 뒤 <b>분석</b>을 눌러 주세요.</p>';
            groups.forEach(function (g) {
                html += '<div class="gcl-list-note" style="margin-top:8px"><b>' + esc(g.analysisName || g.representative) + '</b> '
                     + '<span class="hi-badge-parts">' + g.memberCount + '개</span></div>'
                     + '<div class="gcl-list-names">'
                     + (g.members || []).map(function (n) { return '<span class="gcl-prev-step">' + esc(n) + '</span>'; }).join('')
                     + '</div>';
                (g.warnings || []).forEach(function (w) {
                    html += '<p class="gcl-list-note">· ' + esc(w) + '</p>';
                });
                if (g.error) html += '<p class="gcl-list-note">· 묶지 못했습니다: ' + esc(g.error) + '</p>';
            });
        } else {
            html += '<p>' + names.length + '개를 올렸습니다. <b>연속된 로그로 보이지 않아</b> 따로 두었습니다.</p>';
        }
        if (singles.length) {
            html += '<p class="gcl-list-note" style="margin-top:10px">따로 둔 로그</p><div class="gcl-list-names">'
                 + singles.map(function (n) { return '<span class="gcl-prev-step" title="' + esc(reasons[n] || '') + '">' + esc(n) + '</span>'; }).join('')
                 + '</div>';
            var uniq = [];
            singles.forEach(function (n) { var r = reasons[n]; if (r && uniq.indexOf(r) === -1) uniq.push(r); });
            uniq.forEach(function (r) { html += '<p class="gcl-list-note">· ' + esc(r) + '</p>'; });
            html += '<p class="gcl-list-note">필요하면 목록에서 <b>선택 → 이어붙여 분석</b>으로 직접 묶을 수 있습니다.</p>';
        }
        body.innerHTML = html;
        showModal(document.getElementById('groupResultModal'));
    }

    /** 결과 모달을 닫으면 목록을 새로 그린다 — 묶음이 서랍으로 보여야 한다. */
    function closeGroupResult() {
        hideModal(document.getElementById('groupResultModal'));
        window.location.reload();
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

    // ── 서랍: 대표 행 + 접힌 구성 파일 (2026-09-21) ───────────
    // 종전에는 '전부 보임/전부 숨김' 토글이었다. 지금은 구성 파일이 대표 행 바로 아래 접혀 있고,
    // ▸ 버튼으로 그룹마다 여닫는다. 기존 체크박스는 '모든 그룹 펼치기' 로 의미만 바뀌었다(id·키 유지).
    var MEMBERS_KEY = 'gcLogShowMembers';      // 모두 펼치기 상태
    var OPEN_KEY = 'gcLogOpenGroups';          // 개별로 펼쳐 둔 대표 파일명
    var _showMembers = false;                  // = 모두 펼치기
    var _open = {};
    var _historyRender = null;

    /** localStorage 의 파일명 배열 → {이름: true}. 이력 표 서랍과 좌측 파일 패널 서랍이 키만 달리해 쓴다. */
    function loadNameSet(key) {
        var set = {};
        try {
            var raw = localStorage.getItem(key);
            var arr = raw ? JSON.parse(raw) : [];
            if (Object.prototype.toString.call(arr) === '[object Array]') {
                arr.forEach(function (n) { if (n) set[n] = true; });
            }
        } catch (e) { ignored('서랍 펼침 상태 읽기(' + key + ')', e); }
        return set;
    }

    function saveNameSet(key, set) {
        try {
            var arr = Object.keys(set).filter(function (k) { return set[k]; });
            localStorage.setItem(key, JSON.stringify(arr));
        } catch (e) { ignored('서랍 펼침 상태 저장(' + key + ')', e); }
    }

    function loadOpenGroups() { _open = loadNameSet(OPEN_KEY); }
    function saveOpenGroups() { saveNameSet(OPEN_KEY, _open); }

    function initShowMembers() {
        loadOpenGroups();
        var chk = document.getElementById('hiShowMembers');
        if (!chk) return;
        try { _showMembers = localStorage.getItem(MEMBERS_KEY) === 'true'; }
        catch (e) { ignored('모든 그룹 펼치기 설정 읽기', e); }
        chk.checked = _showMembers;
        var n = document.querySelectorAll('#hiTbody tr[data-partof]:not([data-partof=""])').length;
        var cnt = document.getElementById('hiMembersCnt');
        if (cnt) cnt.textContent = n ? '(' + n + ')' : '';
    }

    function onShowMembersChange() {
        var chk = document.getElementById('hiShowMembers');
        _showMembers = !!(chk && chk.checked);
        try { localStorage.setItem(MEMBERS_KEY, String(_showMembers)); }
        catch (e) { ignored('모든 그룹 펼치기 설정 저장', e); }
        if (_historyRender) _historyRender();
    }

    /**
     * ▸ 클릭 — 구성 파일 행의 display 와 aria-expanded 만 바꾼다.
     * ⚠ render() 를 부르지 말 것: 페이지·순번·정렬이 리셋된다.
     */
    function glToggleGroup(e, btn) {
        if (e) e.stopPropagation();
        var tr = btn.closest('tr');
        if (!tr) return;
        var fn = tr.dataset.filename;
        var open = btn.getAttribute('aria-expanded') !== 'true';
        _open[fn] = open;
        saveOpenGroups();
        applyGroupState(tr, open);
    }

    /** 대표 행의 토글 표시와 뒤따르는 구성 파일 행의 노출을 맞춘다. */
    function applyGroupState(repTr, open) {
        var btn = repTr.querySelector('.hi-group-toggle');
        if (btn) {
            btn.setAttribute('aria-expanded', String(open));
            var n = btn.dataset.members || '';
            btn.setAttribute('aria-label', '구성 파일 ' + n + '개 ' + (open ? '접기' : '펼치기'));
        }
        var node = repTr.nextElementSibling;
        while (node && node.classList.contains('hi-subrow')) {
            node.style.display = open ? '' : 'none';
            node = node.nextElementSibling;
        }
    }

    /** 구성에서 한 파일만 뺀다 — 남길 목록을 보내 다시 묶는다(분석은 시작하지 않는다). */
    function glDetachMember(rep, member) {
        var rest = [];
        document.querySelectorAll('#hiTbody tr.hi-subrow').forEach(function (tr) {
            if (tr.dataset.partof === rep && tr.dataset.filename !== member) rest.push(tr.dataset.filename);
        });
        linkGroup(rep, rest, member + ' 을(를) 묶음에서 뺐습니다.');
    }

    /** 구성 해제 — 구성원이 전부 단독 행으로 돌아온다. */
    function glUngroup(rep) { linkGroup(rep, [], '묶음을 해제했습니다.'); }

    function linkGroup(rep, members, okMsg) {
        Common.fetchJSON('/api/gc-log/' + encodeURIComponent(rep) + '/combine/link',
                { method: 'POST', body: JSON.stringify({ filenames: members }) })
            .then(function () {
                toast(okMsg, 'success');
                setTimeout(function () { window.location.reload(); }, 400);
            })
            .catch(function (e) {
                failed('구성 변경', e);
                toast(serverErrMsg(e, '묶음을 바꾸지 못했습니다.'), 'danger');
            });
    }

    /** Common.fetchJSON 의 오류에서 서버가 준 한국어 문구를 꺼낸다(없으면 기본 문구, 함정 14). */
    function serverErrMsg(e, fallback) {
        if (e && e.sessionExpired) return '로그인이 만료되었습니다. 다시 로그인해 주세요.';
        try {
            var d = JSON.parse(e.body);
            if (d && d.error) return d.error;
        } catch (ignore) { ignored('오류 본문 파싱', ignore); }
        return fallback;
    }

    // ── 이력 표 '⋯' 추가 작업 말풍선 (2026-10-22) ─────────────
    // 항목은 행의 <template class="hi-more-tpl"> 에 서버가 렌더해 둔다(노출 조건의 단일 출처). 여기서는 복제해 body 에
    // fixed 로 띄우기만 한다 — 표의 overflow-x 에 잘리지 않고, 항목 클릭이 행 클릭(glRowAction)으로 새지 않는다.
    // 실행은 항목 자신의 onclick 이 한다(deleteGcLog·glUngroup·AnalyzeConfirm.fromLink …).
    // 닫기: 같은 ⋯ 재클릭 · Esc(포커스 복귀) · 바깥 누름 · Tab(⋯ 에서 이어서 이동) · 표 다시 그리기. 스크롤·리사이즈는 따라간다.
    var _menu = null;   // { el, trigger, onKey, onDown, onReflow }

    function glOpenRowMenu(e, btn) {
        if (e) e.stopPropagation();
        if (_menu && _menu.trigger === btn) { closeRowMenu(true); return; }
        closeRowMenu(false);
        var holder = btn.closest('.hi-actions');
        var tpl = holder && holder.querySelector('template.hi-more-tpl');
        if (!tpl) return;

        var el = document.createElement('div');
        el.className = 'hi-menu';
        el.setAttribute('role', 'menu');
        el.setAttribute('aria-label', btn.getAttribute('aria-label') || '추가 작업');
        el.appendChild(tpl.content.cloneNode(true));
        tidySeparators(el);
        if (!el.querySelector('.hi-menu-item')) return;
        document.body.appendChild(el);

        // 항목을 누르면 실행(항목 onclick) 뒤에 닫는다. 포커스는 먼저 ⋯ 로 돌려 둔다 —
        // 이어서 열리는 확인 모달이 닫힐 때 그 자리(⋯)로 돌아오게(AnalyzeConfirm 은 열 때의 activeElement 를 기억한다).
        el.addEventListener('click', function (ev) {
            var item = ev.target.closest && ev.target.closest('.hi-menu-item');
            if (!item) return;
            if (btn.focus) btn.focus();
            // 다운로드 항목은 링크로 바로 열지 않는다 — 세션 만료·삭제된 파일이 화면 이동으로 보이지 않게(함정 71)
            if (item.hasAttribute('data-download')) { ev.preventDefault(); downloadFromMenu(item); }
            setTimeout(function () { if (_menu && _menu.el === el) closeRowMenu(false); }, 0);
        }, true);

        var m = {
            el: el, trigger: btn,
            onKey: function (ev) {
                var items = menuItems(el);
                var idx = items.indexOf(document.activeElement);
                if (ev.key === 'Escape') { ev.preventDefault(); closeRowMenu(true); }
                else if (ev.key === 'Tab') { closeRowMenu(true); }   // 기본 동작은 막지 않는다 — ⋯ 다음 요소로 이어서 간다
                else if (!el.contains(document.activeElement)) return;
                else if (ev.key === 'ArrowDown') { ev.preventDefault(); items[(idx + 1) % items.length].focus(); }
                else if (ev.key === 'ArrowUp') { ev.preventDefault(); items[(idx - 1 + items.length) % items.length].focus(); }
                else if (ev.key === 'Home') { ev.preventDefault(); items[0].focus(); }
                else if (ev.key === 'End') { ev.preventDefault(); items[items.length - 1].focus(); }
            },
            onDown: function (ev) {
                if (el.contains(ev.target) || btn.contains(ev.target)) return;
                closeRowMenu(false);
            },
            onReflow: function () {
                // 행이 사라졌거나(다시 그리기·페이지 이동) 접힌 서랍 안이면 닫는다
                if (!document.body.contains(btn) || !btn.offsetParent) { closeRowMenu(false); return; }
                placeRowMenu(el, btn);
            }
        };
        _menu = m;
        document.addEventListener('keydown', m.onKey, true);
        document.addEventListener('mousedown', m.onDown, true);
        document.addEventListener('touchstart', m.onDown, true);
        window.addEventListener('resize', m.onReflow);
        window.addEventListener('scroll', m.onReflow, true);
        btn.setAttribute('aria-expanded', 'true');
        placeRowMenu(el, btn);
        menuItems(el)[0].focus();
    }

    function downloadFromMenu(a) {
        var name = a.getAttribute('data-filename') || '';
        Common.downloadFile(a.getAttribute('href'), { fallbackName: name }).catch(function (e) {
            if (e.sessionExpired) { alert(e.message); window.location.href = '/login'; return; }
            toast('다운로드 실패: ' + name + ' — ' + e.message, 'danger');
        });
    }

    function closeRowMenu(returnFocus) {
        if (!_menu) return;
        var m = _menu;
        _menu = null;
        document.removeEventListener('keydown', m.onKey, true);
        document.removeEventListener('mousedown', m.onDown, true);
        document.removeEventListener('touchstart', m.onDown, true);
        window.removeEventListener('resize', m.onReflow);
        window.removeEventListener('scroll', m.onReflow, true);
        if (m.el.parentNode) m.el.parentNode.removeChild(m.el);
        m.trigger.setAttribute('aria-expanded', 'false');
        if (returnFocus && document.body.contains(m.trigger)) m.trigger.focus();
    }

    function menuItems(el) {
        return Array.prototype.slice.call(el.querySelectorAll('.hi-menu-item'));
    }

    /** 조건부 항목이 빠지면 구분선이 맨 앞·맨 뒤·연달아 남는다 — 그런 구분선을 지운다. */
    function tidySeparators(el) {
        var prevSep = true;   // 맨 앞 구분선도 지우도록 '직전이 구분선' 으로 시작
        Array.prototype.slice.call(el.children).forEach(function (c) {
            var isSep = c.classList.contains('hi-menu-sep');
            if (isSep && prevSep) { el.removeChild(c); return; }
            prevSep = isSep;
        });
        var last = el.lastElementChild;
        if (last && last.classList.contains('hi-menu-sep')) el.removeChild(last);
    }

    /** ⋯ 오른쪽 끝에 맞춘 말풍선. 아래가 모자라면 위로 뒤집고, 꼬리는 ⋯ 가운데를 가리킨다(server-scan placePicker 와 같은 규칙). */
    function placeRowMenu(el, btn) {
        var r = btn.getBoundingClientRect();
        var w = el.offsetWidth, h = el.offsetHeight;
        var vw = window.innerWidth, vh = window.innerHeight;
        var left = Math.min(Math.max(8, r.right + 6 - w), vw - w - 8);
        var up = r.bottom + 10 + h > vh - 8 && r.top - 10 - h >= 8;
        var top = up ? r.top - 10 - h : r.bottom + 10;
        el.classList.toggle('up', up);
        el.style.left = Math.round(left) + 'px';
        el.style.top = Math.round(Math.max(8, top)) + 'px';
        var arrowX = Math.min(Math.max(10, r.left + r.width / 2 - left - 7), w - 24);
        el.style.setProperty('--hi-arrow-x', Math.round(arrowX) + 'px');
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
        showModal(document.getElementById('listCombineModal'));
    }

    function closeListCombine() {
        hideModal(document.getElementById('listCombineModal'));
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
        showModal(document.getElementById('deleteGcModal'));
    }
    function closeGcDeleteModal() {
        hideModal(document.getElementById('deleteGcModal'));
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

    // ── 사이드 파일 패널: 검색/상태 필터/페이지네이션 + 서랍(2026-10-22) ────────
    // 최상위 단위(.gl-unit) = 단독 파일 또는 묶음(대표 카드 + 접힌 구성 파일). 필터·페이지·건수는 단위로 센다 —
    // 상태 필터는 대표의 상태로 거른다(구성 파일 자신은 늘 '미분석' 이지만 대표의 분석에 포함돼 있다).
    // 펼침 상태는 이력 표 서랍과 별개 키로 기억한다.
    var SIDE_OPEN_KEY = 'gcLogSideOpenGroups';
    var _sideOpen = {};
    var _sideReveal = null;   // (filename) → 해당 파일이 보이게 페이지·서랍을 맞춘다

    /** 묶음 단위의 손잡이 표시와 구성 파일 목록 노출을 맞춘다. */
    function applySideGroup(unit, open) {
        var btn = unit.querySelector('.gl-group-toggle');
        var box = unit.querySelector('.gl-members');
        if (!btn || !box) return;
        btn.setAttribute('aria-expanded', String(open));
        btn.setAttribute('aria-label', '구성 파일 ' + (btn.dataset.members || '') + '개 ' + (open ? '접기' : '펼치기'));
        box.hidden = !open;
    }

    /** 손잡이 클릭 — 펼침 상태만 바꾼다(페이지·필터는 그대로). */
    function glToggleSideGroup(btn) {
        var unit = btn.closest('.gl-unit');
        if (!unit) return;
        var open = btn.getAttribute('aria-expanded') !== 'true';
        _sideOpen[unit.dataset.filename] = open;
        saveNameSet(SIDE_OPEN_KEY, _sideOpen);
        applySideGroup(unit, open);
    }

    function initSidebar() {
        var list = document.getElementById('glFileList');
        if (!list) return;
        _sideOpen = loadNameSet(SIDE_OPEN_KEY);
        var units = Array.prototype.slice.call(list.children).filter(function (el) { return el.classList.contains('gl-unit'); });
        // 접힌 서랍 안의 파일명으로도 검색이 걸리게 미리 소문자 키를 만들어 둔다.
        units.forEach(function (u) {
            u._memberQuery = Array.prototype.map.call(u.querySelectorAll('.gl-member'), function (m) {
                return (m.dataset.filename || '').toLowerCase();
            }).join(' ');
        });
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
            return units.filter(function (u) {
                u._hitByMember = false;
                if (q) {
                    var own = (u.dataset.filename || '').toLowerCase().indexOf(q) !== -1;
                    var inMembers = u._memberQuery.indexOf(q) !== -1;
                    if (!own && !inMembers) return false;
                    u._hitByMember = !own && inMembers;   // 구성 파일로만 걸렸으면 서랍을 열어 보여 준다(기억은 하지 않는다)
                }
                if (st !== 'all' && u.dataset.status !== st) return false;
                return true;
            });
        }
        function render() {
            var f = filtered(), total = f.length;
            var pages = Math.max(1, Math.ceil(total / PAGE));
            if (cur > pages) cur = pages;
            units.forEach(function (u) { u.style.display = 'none'; });
            var start = (cur - 1) * PAGE, end = Math.min(start + PAGE, total);
            for (var i = start; i < end; i++) {
                f[i].style.display = '';
                applySideGroup(f[i], !!_sideOpen[f[i].dataset.filename] || f[i]._hitByMember);
            }
            if (countEl) countEl.textContent = total;
            if (noResult) noResult.style.display = total === 0 ? 'block' : 'none';
            if (clearBtn) clearBtn.style.display = search.value ? '' : 'none';
            renderPg(pgBar, pgList, pgInfo, total, pages, start, end, PAGE, cur, function (p) { cur = p; render(); });
        }
        search.addEventListener('input', function () { cur = 1; render(); });
        statusSel.addEventListener('change', function () { cur = 1; render(); });
        if (clearBtn) clearBtn.addEventListener('click', function () { search.value = ''; cur = 1; render(); search.focus(); });
        render();

        /** 파일(대표·구성 파일 모두)이 보이게 한다 — 필터에 걸려 있으면 풀고, 그 페이지로 가고, 구성 파일이면 서랍을 연다. */
        _sideReveal = function (filename) {
            var unit = null, target = null;
            units.forEach(function (u) {
                if (unit) return;
                if (u.dataset.filename === filename) { unit = u; target = u.querySelector('.cd-file-item'); return; }
                Array.prototype.forEach.call(u.querySelectorAll('.gl-member'), function (m) {
                    if (!unit && m.dataset.filename === filename) { unit = u; target = m; }
                });
            });
            if (!unit) return null;
            var idx = filtered().indexOf(unit);
            if (idx < 0) { search.value = ''; statusSel.value = 'all'; idx = filtered().indexOf(unit); }
            cur = Math.floor(idx / PAGE) + 1;
            if (target !== unit.querySelector('.cd-file-item')) {
                _sideOpen[unit.dataset.filename] = true;
                saveNameSet(SIDE_OPEN_KEY, _sideOpen);
            }
            render();
            return target;
        };
    }

    // ── 분석 이력: 검색/정렬/기간/페이지네이션 ────────────────
    function initHistory() {
        var tbody = document.getElementById('hiTbody');
        if (!tbody) return;
        // 대표 행만 페이지네이션·정렬 대상이고, 구성 파일 행은 부모 뒤에 따라다닌다(서랍).
        var all = Array.prototype.slice.call(tbody.querySelectorAll('tr'));
        var rows = [], memberMap = {};
        all.forEach(function (tr) {
            var p = tr.dataset.partof;
            if (p) { (memberMap[p] = memberMap[p] || []).push(tr); return; }
            rows.push(tr);
        });
        // 접힌 서랍 안의 파일명으로도 검색이 걸리게 미리 소문자 키를 만들어 둔다.
        rows.forEach(function (r) {
            var kids = memberMap[r.dataset.filename] || [];
            r._memberQuery = kids.map(function (m) { return (m.dataset.fname || '').toLowerCase(); }).join(' ');
        });
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
                // 구성 파일은 rows 에 없다(서랍 안) — 대신 검색어가 구성 파일명에 맞으면 그 그룹을 펼쳐 보여 준다.
                r._hitByMember = false;
                if (q) {
                    var own = (r.dataset.fname || '').toLowerCase().indexOf(q) !== -1
                           || (r.dataset.server || '').toLowerCase().indexOf(q) !== -1;
                    var inMembers = (r._memberQuery || '').indexOf(q) !== -1;
                    if (!own && !inMembers) return false;
                    r._hitByMember = !own && inMembers;
                }
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
            closeRowMenu(false);   // 행이 옮겨지거나 숨겨지면 말풍선이 엉뚱한 자리에 남는다
            var f = filtered(), total = f.length;
            var pages = Math.max(1, Math.ceil(total / PAGE));
            if (cur > pages) cur = pages; if (cur < 1) cur = 1;
            all.forEach(function (r) { r.style.display = 'none'; });
            var start = (cur - 1) * PAGE, end = Math.min(start + PAGE, total);
            for (var i = start; i < end; i++) {
                var rep = f[i];
                rep.style.display = '';
                var numCell = rep.querySelector('.hi-col-num');
                if (numCell) numCell.textContent = (i + 1);
                tbody.appendChild(rep);
                // 구성 파일은 닫혀 있어도 붙인다 — 대표 바로 뒤 자리를 지켜야 토글이 재배치 없이 동작한다.
                var kids = memberMap[rep.dataset.filename] || [];
                var open = _showMembers || !!_open[rep.dataset.filename] || rep._hitByMember;
                kids.forEach(function (k) { tbody.appendChild(k); });
                applyGroupState(rep, open);
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
                // 구성 파일이면 서랍 안에 접혀 있고, 다른 페이지에 있을 수도 있다 — 보이게 맞춘 뒤 옮겨 간다
                var item = _sideReveal ? _sideReveal(pre) : null;
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
    window.glToggleGroup = glToggleGroup;
    window.glDetachMember = glDetachMember;
    window.glUngroup = glUngroup;
    window.closeGroupResult = closeGroupResult;
    window.glToggleSideGroup = glToggleSideGroup;
    window.glOpenRowMenu = glOpenRowMenu;
})();
