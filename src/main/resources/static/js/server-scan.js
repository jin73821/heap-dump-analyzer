/* server-scan.js — 원격 덤프 스캔 결과 패널 공통 (2026-09-13)
 *
 * 사용 페이지: servers.html(Target Servers) · server-detail.html(서버 정보)
 *
 * 종전엔 servers.html 인라인에만 있었고 서버 정보 페이지의 '스캔'은 `N개 파일 발견` 토스트만 띄웠다 —
 * 파일이 발견돼도 목록·전송 버튼이 없어 Target Servers 로 돌아가 다시 스캔해야 했다. 두 페이지가 같은 패널을
 * 쓰도록 여기로 추출했다. 전송이 끝난(또는 이미 전송돼 있던) 미분석 파일에는 '분석 시작' 버튼이 붙고,
 * 누르면 /js/analyze-confirm.js 의 확인 모달을 거친다.
 *
 * API
 *   var scan = ServerScan.create({
 *       panel:    HTMLElement,                        // 패널 호스트(내용은 모듈이 그린다). 'open' 클래스로 표시
 *       toast:    function (msg, ok) {},              // 페이지 토스트
 *       onStatus: function (serverId, 'OK'|'FAIL', errMsg) {}   // 선택 — 연결 상태 배지 갱신
 *   });
 *   scan.scan(serverId, serverName);
 *   scan.close();
 *
 * 생성 HTML 은 inline onclick 없이 data-act + 패널 단위 이벤트 위임만 쓴다(전역 함수 이름 충돌 방지).
 * CSS 는 스스로 주입한다 — 두 페이지의 인라인 스타일이 달라 한쪽에만 두면 다른 쪽이 민짜가 된다(함정 17).
 */
(function (global) {
    'use strict';

    function ignored(tag, e) { if (global.Common) global.Common.logIgnored('[ServerScan] ' + tag, e); }
    function failed(tag, e)  { if (global.Common) global.Common.logError('[ServerScan] ' + tag, e); }
    function esc(s) { return global.Common.escHtml(s == null ? '' : String(s)); }

    var PAGE_SIZE = 20;
    var activeTransfers = 0;   // 모든 인스턴스 합계 — 세션 활동 가드용

    function injectStyle() {
        if (document.getElementById('serverScanStyle')) return;
        var st = document.createElement('style');
        st.id = 'serverScanStyle';
        st.textContent =
            '.scan-panel{background:#fff;border:1px solid #E5E7EB;border-radius:10px;padding:16px;margin-top:16px;display:none}' +
            '.scan-panel.open{display:block}' +
            '.scan-title{font-size:14px;font-weight:700;margin-bottom:12px;display:flex;align-items:center;justify-content:space-between;gap:8px}' +
            '.scan-title-actions{display:flex;gap:6px;align-items:center;flex-shrink:0}' +
            '.scan-close{padding:5px 12px;border:1px solid #E5E7EB;border-radius:5px;background:transparent;font-size:12px;' +
            'line-height:18px;cursor:pointer;color:#6B7280;font-family:inherit}' +
            '.scan-close:hover{background:#F9FAFB}' +
            '.scan-file{display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid #F3F4F6;font-size:13px;flex-wrap:wrap}' +
            '.scan-file:last-child{border-bottom:none}' +
            '.scan-transferred{color:#9CA3AF;font-size:11px}' +
            '.scan-analyzed{color:#059669;font-size:11px;font-weight:600}' +
            '.badge-core{font-size:10px;padding:2px 6px;border-radius:4px;background:#FEF3C7;color:#92400E;font-weight:700;flex-shrink:0}' +
            '.exec-info{font-size:11px;color:#6B7280;font-family:monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:0}' +
            '.exec-warn{font-size:11px;color:#D97706;flex-shrink:0}' +
            '.exec-cmd{font-size:11px;color:#9CA3AF;font-style:italic;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:0}' +
            '.exec-label{font-size:11px;color:#6B7280;flex-shrink:0;font-weight:600}' +
            '.btn-transfer{padding:4px 10px;border:1px solid #2563EB;border-radius:5px;background:#2563EB;color:#fff;font-size:11px;' +
            'line-height:16px;cursor:pointer;font-family:inherit;white-space:nowrap}' +
            '.btn-transfer:hover{background:#1D4ED8}' +
            '.btn-transfer:disabled{background:#9CA3AF;border-color:#9CA3AF;cursor:default}' +
            '.btn-exec-transfer{background:#7C3AED;border-color:#7C3AED}' +
            '.btn-exec-transfer:hover{background:#6D28D9}' +
            /* 분석 시작 — 전송(파랑)·실행파일(보라)과 구분되는 초록 + 재생 아이콘 */
            '.btn-analyze{display:inline-flex;align-items:center;gap:4px;background:#059669;border-color:#059669}' +
            '.btn-analyze:hover{background:#047857}' +
            '.btn-analyze svg{width:9px;height:9px;fill:currentColor}' +
            '.scan-status{display:inline-flex;align-items:center;gap:8px;flex-shrink:0}' +
            '.scan-elapsed{font-size:12px;color:#6B7280;text-align:center;padding:14px 12px}' +
            '.scan-elapsed .se-spin{display:inline-block;width:14px;height:14px;border:2px solid #E5E7EB;border-top-color:#2563EB;' +
            'border-radius:50%;animation:seSpin .8s linear infinite;vertical-align:-2px;margin-right:8px}' +
            '@keyframes seSpin{to{transform:rotate(360deg)}}' +
            '.scan-elapsed .se-slow{display:block;margin-top:8px;color:#D97706;font-size:11px}' +
            '.scan-msg{padding:12px;text-align:center;color:#9CA3AF}' +
            '.scan-msg.err{color:#EF4444}' +
            '.scan-banner{border-radius:8px;padding:10px 14px;margin-bottom:10px;font-size:12px;line-height:1.5}' +
            '.scan-banner.warn{background:#FFFBEB;border:1px solid #FDE68A;color:#92400E}' +
            '.scan-banner.err{background:#FEF2F2;border:1px solid #FECACA;color:#991B1B}' +
            '.scan-banner code{font-family:monospace}' +
            '.scan-pagination{display:flex;align-items:center;justify-content:center;gap:6px;flex-wrap:wrap;margin-top:12px;' +
            'padding-top:10px;border-top:1px solid #F3F4F6}' +
            '.scan-pagination button{min-width:30px;padding:4px 8px;border:1px solid #E5E7EB;border-radius:5px;background:#fff;' +
            'color:#374151;font-size:12px;cursor:pointer;font-family:inherit}' +
            '.scan-pagination button:hover:not(:disabled){background:#F3F4F6}' +
            '.scan-pagination button.active{background:#2563EB;border-color:#2563EB;color:#fff;font-weight:700}' +
            '.scan-pagination button:disabled{color:#D1D5DB;cursor:default}' +
            '.scan-pagination .sp-info{font-size:11px;color:#9CA3AF;margin:0 6px}' +
            '.transfer-progress{display:inline-flex;align-items:center;gap:8px;min-width:260px}' +
            '.transfer-bar{width:140px;height:8px;background:#E5E7EB;border-radius:4px;overflow:hidden}' +
            '.transfer-bar-fill{height:100%;border-radius:4px;background:#2563EB;width:0;transition:width .25s ease}' +
            '.transfer-bar-fill.indeterminate{width:100%;animation:transferPulse 1.5s ease-in-out infinite}' +
            '.transfer-bar-fill.done{background:#059669;animation:none;width:100%}' +
            '.transfer-bar-fill.fail{background:#EF4444;animation:none;width:100%}' +
            '.transfer-label{font-size:11px;color:#4B5563;white-space:nowrap;font-variant-numeric:tabular-nums}' +
            '.transfer-label.ok{color:#059669}' +
            '.transfer-err{font-size:11px;color:#DC2626;margin-top:4px;max-width:300px;word-break:break-all}' +
            '@keyframes transferPulse{0%{opacity:.4;transform:scaleX(.3);transform-origin:left}' +
            '50%{opacity:1;transform:scaleX(1);transform-origin:left}100%{opacity:.4;transform:scaleX(.3);transform-origin:right}}' +
            '@media (max-width:640px){' +
            '.scan-title{flex-wrap:wrap}' +
            '.transfer-progress{min-width:0;flex:1 1 100%}' +
            '.transfer-bar{flex:1;width:auto;min-width:60px}' +
            /* 상태(완료 라벨 + 분석 시작)가 한 줄을 다 먹으면 파일명이 0 폭으로 짓눌린다 — 좁은 폭에선 다음 줄로 내린다 */
            '.scan-status{flex:1 1 100%;flex-wrap:wrap;justify-content:flex-end}' +
            '.scan-status .transfer-label{white-space:normal}}' +
            '@media (min-width:1024px){.scan-file{font-size:14px;padding:10px 0}}';
        document.head.appendChild(st);
    }

    var PLAY_SVG = '<svg viewBox="0 0 10 10" aria-hidden="true"><polygon points="2 1 9 5 2 9"/></svg>';

    function fmtMB(bytes) {
        if (bytes == null || bytes < 0) return '–';
        var mb = bytes / (1024 * 1024);
        if (mb >= 1024) return (mb / 1024).toFixed(2) + ' GB';
        return mb.toFixed(1) + ' MB';
    }

    function parseLsDate(s) {
        if (!s) return '';
        // 백엔드 find -printf 는 "YYYY-MM-DD HH:MM" (정렬가능) 형식 → 그대로 표시
        if (/^\d{4}-\d{2}-\d{2}/.test(s)) return s;
        // 레거시 "Mon DD time|year" 형식 폴백
        var MONTHS = {Jan:'01',Feb:'02',Mar:'03',Apr:'04',May:'05',Jun:'06',
                      Jul:'07',Aug:'08',Sep:'09',Oct:'10',Nov:'11',Dec:'12'};
        var parts = s.trim().split(/\s+/);
        if (parts.length < 3) return s;
        var mon = MONTHS[parts[0]] || '??';
        var day = parts[1].length === 1 ? '0' + parts[1] : parts[1];
        var third = parts[2];
        if (third.indexOf(':') >= 0) return new Date().getFullYear() + '-' + mon + '-' + day + ' ' + third;
        return third + '-' + mon + '-' + day;
    }

    /** 응답을 텍스트로 받아 안전 파싱 — 프록시 HTML·세션 만료(401)에도 SyntaxError 대신 읽을 수 있는 문구(함정 14). */
    function readJsonSafe(r) {
        return r.text().then(function (t) {
            var d = null;
            try { d = t ? JSON.parse(t) : null; }
            catch (e) { ignored('JSON 아닌 응답 (HTTP ' + r.status + ')', e); }
            if (r.status === 401) return { success: false, message: '세션이 만료되었습니다. 다시 로그인해 주세요.' };
            if (d && typeof d === 'object') return d;
            return { success: false, message: '서버 응답을 해석할 수 없습니다 (HTTP ' + r.status + ')' };
        });
    }

    function jvmSuffix(jvm) {
        if (!jvm) return '';
        if (jvm.status === 'collected') {
            return ' · JVM' + (jvm.xms ? ' -Xms' + jvm.xms : '') + (jvm.xmx ? ' -Xmx' + jvm.xmx : '')
                + (jvm.pid ? ' (pid ' + jvm.pid + ')' : '');
        }
        if (jvm.status === 'ambiguous') return ' · JVM 후보 ' + jvm.candidateCount + '개 (분석 화면에서 선택)';
        return ' · JVM 미수집';
    }

    function create(cfg) {
        injectStyle();
        var panel = cfg.panel;
        var toast = cfg.toast || function () {};
        var onStatus = cfg.onStatus || function () {};

        var serverId = null, serverName = '', files = [], page = 0, distinctPaths = 1;
        var elapsedTimer = null, scanSeq = 0;

        panel.classList.add('scan-panel');
        panel.innerHTML =
            '<div class="scan-title">' +
            '  <span><span data-ss="name"></span> — Remote Dump Files</span>' +
            '  <div class="scan-title-actions">' +
            '    <button type="button" class="btn-transfer" data-act="transfer-all" hidden style="font-size:11px;padding:4px 12px">현재 페이지 전송</button>' +
            '    <button type="button" class="scan-close" data-act="close">&times; 닫기</button>' +
            '  </div>' +
            '</div>' +
            '<div data-ss="results"></div>';
        var nameEl = panel.querySelector('[data-ss="name"]');
        var resultsEl = panel.querySelector('[data-ss="results"]');
        var allBtn = panel.querySelector('[data-act="transfer-all"]');

        function findFile(path) {
            for (var i = 0; i < files.length; i++) if (files[i].path === path) return files[i];
            return null;
        }
        function listEl() { return resultsEl.querySelector('[data-ss="list"]'); }
        function rowEl(path) {
            var list = listEl();
            if (!list) return null;
            var rows = list.querySelectorAll('.scan-file');
            for (var i = 0; i < rows.length; i++) if (rows[i].getAttribute('data-row-path') === path) return rows[i];
            return null;
        }

        // ── 스캔 ─────────────────────────────────────────
        function startElapsed() {
            var t0 = Date.now();
            function tick() {
                var sec = Math.floor((Date.now() - t0) / 1000);
                var slow = sec >= 8
                    ? '<span class="se-slow">⏳ 대용량 디렉터리(예: /tmp, /var/crash) 스캔은 시간이 걸릴 수 있습니다. 잠시만 기다려 주세요…</span>'
                    : '';
                resultsEl.innerHTML = '<div class="scan-elapsed"><span class="se-spin"></span>스캔 중… ' + sec + '초 경과' + slow + '</div>';
            }
            stopElapsed();
            tick();
            elapsedTimer = setInterval(tick, 1000);
        }
        function stopElapsed() {
            if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null; }
        }

        function scan(id, name) {
            serverId = id;
            serverName = name || '';
            var seq = ++scanSeq;
            nameEl.textContent = serverName;
            allBtn.hidden = true;
            files = [];
            startElapsed();
            panel.classList.add('open');
            setTimeout(function () { panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); }, 80);

            var headers = {};
            if (global.Common && global.Common.csrfToken()) headers[global.Common.csrfHeaderName()] = global.Common.csrfToken();
            return fetch('/api/servers/' + encodeURIComponent(id) + '/scan', { method: 'POST', headers: headers, credentials: 'same-origin' })
                .then(readJsonSafe)
                .then(function (d) {
                    if (seq !== scanSeq) return d;   // 그 사이 다른 스캔이 시작됐다
                    stopElapsed();
                    renderScanResult(id, d);
                    return d;
                })
                .catch(function (e) {
                    if (seq !== scanSeq) return null;
                    stopElapsed();
                    failed('스캔 요청 실패', e);
                    resultsEl.innerHTML = '<div class="scan-msg err">요청 실패</div>';
                    return null;
                });
        }

        function renderScanResult(id, d) {
            var html = '';
            // 일부 경로 실패 — 전체 실패가 아닐 때만
            if (d.pathErrors && d.pathErrors.length > 0 && !d.error) {
                html += '<div class="scan-banner warn"><strong style="display:block;margin-bottom:6px">일부 경로 스캔 실패 ('
                      + d.pathErrors.length + '개)</strong>';
                for (var i = 0; i < d.pathErrors.length; i++) {
                    var pe = d.pathErrors[i];
                    html += '<div style="margin-bottom:2px"><code>' + esc(pe.dumpPath) + '</code> — ' + esc(pe.error) + '</div>';
                }
                html += '</div>';
            }
            if (d.error) {
                var code = d.errorCode || 'SSH_ERROR';
                var isPathIssue = (code === 'DUMP_PATH_NOT_FOUND' || code === 'DUMP_PATH_NOT_READABLE');
                var title = code === 'DUMP_PATH_NOT_FOUND' ? '덤프 경로 없음'
                          : code === 'DUMP_PATH_NOT_READABLE' ? '덤프 경로 권한 없음' : 'SSH/SCP Error';
                html += '<div class="scan-banner ' + (isPathIssue ? 'warn' : 'err') + '">'
                      + '<strong style="display:block;margin-bottom:4px">[' + esc(code) + '] ' + esc(title) + '</strong>'
                      + esc(d.error)
                      + (isPathIssue ? '<div style="margin-top:6px;font-size:11px;opacity:.85">서버 설정의 덤프 경로 또는 SSH 계정 권한을 확인해 주세요.</div>' : '')
                      + '</div>';
                onStatus(id, 'FAIL', d.error);
            } else if (d.success) {
                onStatus(id, 'OK');
            }

            if (!d.success && !d.error) {
                // 컨트롤러 예외(400 {success:false,message}) · 세션 만료 · 비 JSON 응답
                resultsEl.innerHTML = '<div class="scan-msg err">' + esc(d.message || '스캔 실패') + '</div>';
                return;
            }

            html += '<div data-ss="list"></div>';
            resultsEl.innerHTML = html;

            files = d.files || [];
            page = 0;
            var pathSet = {};
            files.forEach(function (f) { if (f.sourceDumpPath) pathSet[f.sourceDumpPath] = 1; });
            distinctPaths = Object.keys(pathSet).length;

            if (files.length > 0) renderPage();
            else if (!d.error) listEl().innerHTML = '<div class="scan-msg">덤프 파일이 없습니다.</div>';
            refreshAllBtn();
        }

        function refreshAllBtn() {
            var untransferred = files.filter(function (f) { return !f.transferred && !f._transferring; });
            allBtn.hidden = untransferred.length < 2;
        }

        // ── 행 렌더 ───────────────────────────────────────
        function renderStatus(f) {
            if (f._transferring) return '<span class="transfer-label">전송 중…</span>';
            if (!f.transferred) {
                return '<button type="button" class="btn-transfer" data-act="transfer" data-path="' + esc(f.path)
                     + '" data-file-type="' + esc(f.fileType || 'heap') + '">전송</button>';
            }
            var html;
            if (f._doneLabel) html = '<span class="transfer-label ok">' + esc(f._doneLabel) + '</span>';
            else if (f.analyzed) html = '<span class="scan-analyzed">전송됨(분석완료)</span>';
            else html = '<span class="scan-transferred">전송됨</span>';
            // 미분석 + 로컬 파일명을 아는 경우에만 — 모르면 엉뚱한 이름으로 분석이 시작된다
            if (!f.analyzed && f.localFilename) {
                html += '<button type="button" class="btn-transfer btn-analyze" data-act="analyze" data-path="' + esc(f.path)
                      + '" title="' + esc(f.localFilename) + ' 분석 시작">' + PLAY_SVG + '분석 시작</button>';
            }
            return html;
        }

        function renderRow(f) {
            var isCore = f.fileType === 'core';
            var html = '<div class="scan-file" data-row-path="' + esc(f.path) + '">';
            html += '<span style="flex:1;min-width:0;display:flex;align-items:center;gap:8px;overflow:hidden">'
                  + (isCore ? '<span class="badge-core">CORE</span>' : '')
                  + '<span style="font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + esc(f.filename) + '</span>'
                  + (f.date ? '<span style="font-size:12px;color:#9CA3AF;flex-shrink:0;white-space:nowrap">' + esc(parseLsDate(f.date)) + '</span>' : '')
                  + '</span>';
            html += '<span style="color:#6B7280;font-size:12px;flex-shrink:0">' + esc(f.formattedSize) + '</span>';
            html += '<span class="scan-status">' + renderStatus(f) + '</span>';
            if (distinctPaths > 1 && f.sourceDumpPath) {
                html += '<span style="flex:1 1 100%;font-size:10px;color:#9CA3AF;font-family:monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">↳ '
                      + esc(f.sourceDumpPath) + '</span>';
            }
            // 코어파일: 실행파일(execfn) + 전송버튼 / 실행명령(from, 참고) / 경고 — 두 번째 줄
            if (isCore && f.executablePath) {
                html += '<div style="flex:1 1 100%;display:flex;align-items:center;gap:8px;padding-top:4px;margin-top:2px;border-top:1px dashed #F3F4F6">'
                      + '<span class="exec-label">실행파일:</span>'
                      + '<span class="exec-info" title="' + esc(f.executablePath) + '">' + esc(f.executablePath) + '</span>';
                if (f._execDoneLabel) {
                    html += '<span class="transfer-label ok">' + esc(f._execDoneLabel) + '</span>';
                } else if (f.transferred) {
                    // 코어가 전송된 경우에만 페어링 전송 가능
                    html += '<button type="button" class="btn-transfer btn-exec-transfer" data-act="transfer" data-path="' + esc(f.executablePath)
                          + '" data-file-type="coreexec" data-pair-core="' + esc(f.filename)
                          + '" data-core-path="' + esc(f.path) + '">실행파일 전송</button>';
                } else {
                    html += '<span class="exec-warn" title="코어파일 전송 후 실행파일을 페어링 전송할 수 있습니다">코어 전송 후 가능</span>';
                }
                html += '</div>';
            }
            if (isCore && f.executableCommand) {
                html += '<div style="flex:1 1 100%;display:flex;align-items:center;gap:8px;padding-top:2px">'
                      + '<span class="exec-label">실행명령(참고):</span>'
                      + '<span class="exec-cmd" title="' + esc(f.executableCommand) + '">' + esc(f.executableCommand) + '</span>'
                      + '</div>';
            }
            if (isCore && f.executableWarning) {
                html += '<div style="flex:1 1 100%;padding-top:2px"><span class="exec-warn">⚠ ' + esc(f.executableWarning) + '</span></div>';
            }
            html += '</div>';
            return html;
        }

        /** 한 행만 다시 그린다 — 현재 페이지에 없으면 아무것도 안 한다(페이지 이동 시 상태에서 다시 그려진다). */
        function rerenderRow(f) {
            var old = rowEl(f.path);
            if (!old) return;
            var tmp = document.createElement('div');
            tmp.innerHTML = renderRow(f);
            if (tmp.firstChild) old.parentNode.replaceChild(tmp.firstChild, old);
        }

        function renderPage() {
            var list = listEl();
            if (!list) return;
            var total = files.length;
            var pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
            if (page >= pageCount) page = pageCount - 1;
            if (page < 0) page = 0;
            var start = page * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, total);
            var html = '';
            for (var j = start; j < end; j++) html += renderRow(files[j]);

            if (pageCount > 1) {
                html += '<div class="scan-pagination">';
                html += '<button type="button" data-act="page" data-page="' + (page - 1) + '"' + (page === 0 ? ' disabled' : '') + '>‹</button>';
                var from = Math.max(0, page - 2), to = Math.min(pageCount - 1, page + 2);
                if (from > 0) {
                    html += '<button type="button" data-act="page" data-page="0">1</button>';
                    if (from > 1) html += '<span class="sp-info">…</span>';
                }
                for (var p = from; p <= to; p++) {
                    html += '<button type="button" data-act="page" data-page="' + p + '" class="' + (p === page ? 'active' : '') + '">' + (p + 1) + '</button>';
                }
                if (to < pageCount - 1) {
                    if (to < pageCount - 2) html += '<span class="sp-info">…</span>';
                    html += '<button type="button" data-act="page" data-page="' + (pageCount - 1) + '">' + pageCount + '</button>';
                }
                html += '<button type="button" data-act="page" data-page="' + (page + 1) + '"' + (page === pageCount - 1 ? ' disabled' : '') + '>›</button>';
                html += '<span class="sp-info">' + (start + 1) + '–' + end + ' / 총 ' + total + '개</span>';
                html += '</div>';
            } else {
                html += '<div class="scan-pagination"><span class="sp-info">총 ' + total + '개</span></div>';
            }
            list.innerHTML = html;
        }

        // ── 전송 ─────────────────────────────────────────
        function transferFile(btn, callback) {
            var path = btn.getAttribute('data-path');
            var fileType = btn.getAttribute('data-file-type') || 'heap';
            var pairCore = btn.getAttribute('data-pair-core');
            var corePath = btn.getAttribute('data-core-path');
            var parent = btn.parentNode;
            var target = fileType === 'coreexec' ? findFile(corePath) : findFile(path);
            var sid = serverId;
            var gen = scanSeq;   // 전송 중 다른 서버를 다시 스캔하면 같은 경로의 남의 행을 고쳐 그리지 않도록

            btn.style.display = 'none';
            var prevErr = parent.querySelector('.transfer-err');
            if (prevErr) prevErr.remove();
            var prog = document.createElement('div');
            prog.className = 'transfer-progress';
            prog.innerHTML = '<div class="transfer-bar"><div class="transfer-bar-fill indeterminate"></div></div>'
                           + '<span class="transfer-label">전송 시작 중...</span>';
            parent.appendChild(prog);
            var fill = prog.querySelector('.transfer-bar-fill');
            var label = prog.querySelector('.transfer-label');

            var url = '/api/servers/' + encodeURIComponent(sid) + '/transfer/stream?remotePath=' + encodeURIComponent(path)
                    + '&fileType=' + encodeURIComponent(fileType);
            if (pairCore) url += '&pairCore=' + encodeURIComponent(pairCore);

            function fail(text, detail) {
                fill.classList.remove('indeterminate');
                fill.className = 'transfer-bar-fill fail';
                label.textContent = text;
                label.style.color = '#EF4444';
                if (detail) {
                    var errEl = document.createElement('div');
                    errEl.className = 'transfer-err';
                    errEl.textContent = detail;
                    parent.appendChild(errEl);
                }
                btn.style.display = '';
                btn.disabled = false;
                btn.textContent = '재시도';
            }
            function finish() {
                activeTransfers = Math.max(0, activeTransfers - 1);
                if (gen === scanSeq) {
                    if (fileType !== 'coreexec' && target) target._transferring = false;
                    // 전송 중 페이지를 넘겼다 돌아오면 진행바가 붙어 있던 행은 이미 교체돼 있다 — 상태로 다시 그린다
                    if (target && !panel.contains(prog)) rerenderRow(target);
                    refreshAllBtn();
                }
                if (callback) callback();
            }

            var es;
            try { es = new EventSource(url); }
            catch (e) {
                failed('전송 스트림 열기 실패', e);
                fail('연결 실패');
                toast('전송 스트림 열기 실패', false);
                if (callback) callback();
                return;
            }
            activeTransfers++;
            if (fileType !== 'coreexec' && target) target._transferring = true;
            refreshAllBtn();
            var doneReceived = false;

            es.addEventListener('progress', function (ev) {
                try {
                    var d = JSON.parse(ev.data);
                    var bytes = d.bytes != null ? d.bytes : 0;
                    var total = d.total != null ? d.total : -1;
                    if (total > 0) {
                        fill.classList.remove('indeterminate');
                        var pct = Math.max(0, Math.min(100, (bytes / total) * 100));
                        fill.style.width = pct.toFixed(1) + '%';
                        label.textContent = fmtMB(bytes) + ' / ' + fmtMB(total) + ' (' + pct.toFixed(1) + '%)';
                    } else {
                        label.textContent = fmtMB(bytes) + ' 전송 중...';   // 총량 미상 — indeterminate 유지
                    }
                } catch (e) {
                    // 진행률 프레임 하나만 건너뛴다 — 다음 프레임이 곧 갱신한다.
                    ignored('전송 진행률 프레임 처리 실패', e);
                }
            });

            es.addEventListener('done', function (ev) {
                doneReceived = true;
                es.close();
                var d = {};
                try { d = JSON.parse(ev.data); }
                catch (e) {
                    // 완료 프레임을 못 읽으면 아래에서 '실패'로 표시된다 — 실제 결과와 다를 수 있어 반드시 남긴다.
                    failed('전송 완료 프레임 파싱 실패 — 결과를 확인할 수 없다', e);
                }
                if (d.success) {
                    var doneText = '완료' + (d.fileSize ? ' (' + fmtMB(d.fileSize) + ')' : '') + jvmSuffix(d.jvm);
                    toast((d.filename || '파일') + ' 전송 완료', true);
                    // gen 이 바뀌었으면 결과 목록이 이미 다른 스캔 것이다 — 상태는 건드리지 않고 떠 있는 진행바만 완료로 표시
                    if (target && gen === scanSeq) {
                        if (fileType === 'coreexec') {
                            target._execDoneLabel = doneText;
                            target._execTransferred = true;
                        } else {
                            target.transferred = true;
                            target.analyzed = false;
                            target._doneLabel = doneText;
                            if (d.filename) target.localFilename = d.filename;
                        }
                        target._transferring = false;
                        rerenderRow(target);   // '완료' 라벨 + '분석 시작'(또는 실행파일 전송) 버튼으로 교체
                    } else {
                        fill.className = 'transfer-bar-fill done';
                        label.textContent = doneText;
                        label.style.color = '#059669';
                    }
                } else {
                    fail('실패', d.message || '전송 실패');
                    toast('SCP Transfer Failed: ' + (d.message || ''), false);
                }
                finish();
            });

            es.onerror = function () {
                if (doneReceived) return;   // 정상 종료 후 재연결 시도로 발생하는 onerror — 무시
                es.close();
                fail('연결 오류');
                toast('전송 스트림 오류', false);
                finish();
            };
        }

        function transferAll() {
            var list = listEl();
            if (!list) return;
            // 현재 페이지의 코어/힙 전송 버튼만 (실행파일 전송 제외)
            var btns = Array.prototype.filter.call(list.querySelectorAll('[data-act="transfer"]:not(:disabled)'),
                function (b) { return !b.classList.contains('btn-exec-transfer'); });
            if (btns.length === 0) { toast('전송할 파일이 없습니다.', false); return; }
            allBtn.disabled = true;
            allBtn.textContent = '전송 중...';
            var queue = btns.slice();
            (function next() {
                if (queue.length === 0) {
                    allBtn.disabled = false;
                    allBtn.textContent = '현재 페이지 전송';
                    refreshAllBtn();
                    return;
                }
                transferFile(queue.shift(), next);
            })();
        }

        // ── 분석 시작 ───────────────────────────────────────
        function startAnalysis(path) {
            var f = findFile(path);
            if (!f || !f.localFilename) return;
            var isCore = f.fileType === 'core';
            var note = '';
            if (isCore && f.executablePath && !f._execTransferred) {
                note = '실행파일(' + f.executablePath + ')이 이 화면에서 전송되지 않았습니다. 실행파일이 연결되지 않으면 '
                     + 'GDB 가 함수명을 ?? 로 표시할 수 있으니 필요하면 먼저 \'실행파일 전송\'을 하세요. 이미 연결했다면 무시해도 됩니다.';
            }
            if (!global.AnalyzeConfirm) {   // 모듈 미로드 — 확인 없이 이동하지 않는다
                failed('analyze-confirm.js 미로드', null);
                toast('분석 확인 창을 열 수 없습니다. 페이지를 새로고침해 주세요.', false);
                return;
            }
            global.AnalyzeConfirm.open({
                filename: f.localFilename,
                kind: isCore ? 'core' : 'heap',
                size: f.formattedSize,
                source: serverName,
                note: note
            });
        }

        // ── 이벤트 위임 ─────────────────────────────────────
        panel.addEventListener('click', function (e) {
            var el = e.target.closest ? e.target.closest('[data-act]') : null;
            if (!el || !panel.contains(el) || el.disabled) return;
            var act = el.getAttribute('data-act');
            if (act === 'transfer') transferFile(el);
            else if (act === 'analyze') startAnalysis(el.getAttribute('data-path'));
            else if (act === 'transfer-all') transferAll();
            else if (act === 'close') close();
            else if (act === 'page') {
                page = parseInt(el.getAttribute('data-page'), 10) || 0;
                renderPage();
                panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }
        });

        function close() {
            panel.classList.remove('open');
        }

        return { scan: scan, close: close };
    }

    // SCP 전송 SSE 는 요청 진입 시 1회만 세션을 갱신한다 — 긴 전송 중 무동작 만료로 끊기지 않게 활동으로 선언(함정 38)
    if (global.SessionTimeout && global.SessionTimeout.registerActivityGuard) {
        global.SessionTimeout.registerActivityGuard(function () { return activeTransfers > 0; });
    }

    global.ServerScan = { create: create };
})(window);
