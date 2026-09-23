/* analyze-confirm.js — 분석 시작 확인 모달 공통 (2026-09-13)
 *
 * 사용 페이지: servers.html · server-detail.html(스캔 패널의 '분석 시작') · index.html · files.html(Analyze 버튼)
 *
 * 분석 시작은 되돌리기 어려운 동작이다 — MAT 는 수 분~수십 분 CPU·메모리를 점유하고, 분석은 Semaphore(1)
 * 직렬이라 누가 먼저 눌렀느냐로 다른 사람의 대기 순서가 바뀐다. 그래서 버튼 한 번으로 바로 이동하지 않고
 * "무엇을 · 지금 대기열이 어떤 상태에서" 시작하는지 보여준 뒤 확인을 받는다.
 * 기존 페이지별 Others 경고 모달(index/files)도 이 모달로 통합했다. 힙 덤프 확장자(.hprof/.bin/.dump/.dmp[.gz])가
 * 아닌 파일은 유형과 무관하게 경고 블록 + "그래도 진행하시겠습니까?" 로 되묻는다(코어 제외).
 *
 * API
 *   AnalyzeConfirm.open({
 *       filename:  '로컬 파일명',                 // 필수 — dumpfiles/ 기준 이름(원격 원본명 아님)
 *       kind:      'heap' | 'core' | 'gclog' | 'others',   // 기본 heap
 *       size:      '157 MB',                     // 선택 — 표시용
 *       source:    'WAS-01',                     // 선택 — 출처 서버
 *       note:      '추가 안내 문구',               // 선택 — 코어 실행파일 미전송 경고 등
 *       onConfirm: function (filename) { … }     // 선택 — 없으면 분석 진행 화면으로 이동
 *   })
 *   AnalyzeConfirm.fromLink(a)   // <a data-filename data-kind data-size data-source> 의 onclick 용. 항상 false 반환.
 *
 * CSS·DOM 은 스스로 주입하는 싱글턴이다 — 페이지마다 모달 골격(.modal-box/.modal-btns…) 정의가 달라(함정 17)
 * 공용 클래스에 기대면 페이지별로 모양이 갈린다. 그래서 전부 `.ac-` 네임스페이스로 자립한다.
 *
 * GC 로그의 연속 회전본 제안 (2026-09-23)
 *   kind 가 gclog 면 열자마자 GET /api/gc-log/{fn}/group-suggestion 으로 이 파일이 속한 연속 회전본을 찾아
 *   '연결된 로그' 블록에 보인다. 원격 전송은 파일마다 따로 도착해 업로드 큐의 자동 묶기가 돌 자리가 없어서,
 *   Target Servers 에서 회전본 6개를 받아도 각각 단독 분석만 됐다(종전 결함). 제안 종류(mode):
 *     group    — 새로 찾은 묶음. [이어붙여 분석 (N개)] = POST /api/gc-log/{대표}/combine {via:'suggest'} 후 대표 결과 화면
 *                [이 파일만 분석] = 종전 동작. 대표는 가장 최근 조각(업로드 자동 묶기와 같은 규칙)이다.
 *     combined — 이미 이어 붙인 구성의 대표. 저장된 구성으로 분석(종전 동작 + 구성 목록 표시)
 *     member   — 다른 묶음의 구성 파일. [묶음 결과 보기 / 묶음으로 분석] 또는 [이 파일만 분석]
 *     single   — 묶을 것 없음. 같은 계열 후보가 있었을 때만 이유를 한 줄 보인다
 *   판정 중에는 시작 버튼을 잠근다(Enter 한 번에 단독 분석으로 새지 않게). 조회가 실패하면 조용히 단독으로 돌아간다 —
 *   제안은 편의이지 분석의 전제가 아니다. 8초가 지나도 응답이 없으면 단독 분석은 풀어 준다.
 */
(function (global) {
    'use strict';

    function ignored(tag, e) { if (global.Common) global.Common.logIgnored('[AnalyzeConfirm] ' + tag, e); }
    function failed(tag, e) { if (global.Common) global.Common.logError('[AnalyzeConfirm] ' + tag, e); }

    var KIND_LABEL = { heap: '힙 덤프', core: '코어 덤프', gclog: 'GC 로그', others: '기타(Others)' };

    /**
     * 힙 덤프 확장자 — .hprof/.bin/.dump/.dmp (+ .gz 압축). 업로드 큐(_HEAP_EXTS)·FilenameValidator 와 같은 목록이다.
     * ⚠ 단독 `.gz` 는 인정하지 않는다: 서버의 `hasRecognizedHeapDumpExtension` 은 `.gz` 만으로 통과시키지만
     *   `logs.tar.gz` 같은 파일도 걸리므로, 확인 창에서는 속이 무엇인지 모르는 압축 파일도 되묻는다.
     * ⚠ 유형(kind)과 무관하게 파일명으로 판정한다 — Files 의 '파일 분류'로 확장자 없는 파일을 heapdump 로
     *   분류하면 kind 는 heap 으로 오므로, kind==='others' 만 보면 경고가 빠진다.
     */
    var HEAP_EXT_RE = /\.(hprof|bin|dump|dmp)(\.gz)?$/i;
    function hasHeapDumpExtension(name) { return HEAP_EXT_RE.test(name || ''); }

    function injectStyle() {
        if (document.getElementById('analyzeConfirmStyle')) return;
        var st = document.createElement('style');
        st.id = 'analyzeConfirmStyle';
        st.textContent =
            '.ac-ov{position:fixed;inset:0;z-index:1000;background:rgba(17,24,39,.45);display:none;' +
            'align-items:center;justify-content:center;padding:16px}' +
            '.ac-ov.open{display:flex}' +
            '.ac-box{background:#fff;border-radius:12px;width:100%;max-width:460px;max-height:90vh;overflow-y:auto;' +
            'padding:24px;box-shadow:0 20px 60px rgba(0,0,0,.22);animation:acIn .18s ease;font-family:inherit;color:#1F2937}' +
            '@keyframes acIn{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:translateY(0)}}' +
            '.ac-hdr{display:flex;align-items:center;gap:12px;margin-bottom:14px}' +
            '.ac-icon{width:40px;height:40px;border-radius:50%;background:#DBEAFE;display:flex;align-items:center;' +
            'justify-content:center;flex-shrink:0}' +
            '.ac-icon svg{width:20px;height:20px;stroke:#2563EB;fill:none;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}' +
            '.ac-title{font-size:16px;font-weight:700;margin:0;line-height:1.3}' +
            '.ac-sub{font-size:12px;color:#6B7280;margin-top:2px}' +
            '.ac-info{background:#F9FAFB;border:1px solid #F3F4F6;border-radius:8px;padding:10px 14px;margin-bottom:12px}' +
            '.ac-row{display:flex;justify-content:space-between;align-items:baseline;gap:12px;font-size:12px;padding:3px 0}' +
            '.ac-row[hidden]{display:none}' +
            '.ac-lbl{color:#6B7280;flex-shrink:0}' +
            '.ac-val{font-weight:600;text-align:right;min-width:0;word-break:break-all}' +
            '.ac-val.mono{font-family:"JetBrains Mono","SF Mono","Fira Code",monospace;font-weight:500}' +
            '.ac-kind{display:inline-block;font-size:10px;font-weight:700;padding:1px 6px;border-radius:4px}' +
            '.ac-kind.heap{background:#DBEAFE;color:#1E40AF}' +
            '.ac-kind.core{background:#FEF3C7;color:#92400E}' +
            '.ac-kind.gclog{background:#DCFCE7;color:#166534}' +
            '.ac-kind.others{background:#F3F4F6;color:#4B5563}' +
            '.ac-queue{font-size:12px;line-height:1.55;border-radius:8px;padding:9px 12px;margin-bottom:12px;' +
            'background:#F0FDF4;color:#166534;border-left:3px solid #22C55E}' +
            '.ac-queue.busy{background:#F5F3FF;color:#5B21B6;border-left-color:#7C3AED}' +
            '.ac-queue.running{background:#EFF6FF;color:#1E40AF;border-left-color:#2563EB}' +
            '.ac-queue.unknown{background:#F9FAFB;color:#6B7280;border-left-color:#D1D5DB}' +
            '.ac-queue b{font-weight:700;word-break:break-all}' +
            '.ac-warn{font-size:12px;line-height:1.6;border-radius:8px;padding:9px 12px;margin-bottom:12px;' +
            'background:#FFFBEB;color:#92400E;border-left:3px solid #D97706}' +
            '.ac-warn[hidden],.ac-queue[hidden]{display:none}' +
            '.ac-warn .ac-ask{display:block;margin-top:6px;font-weight:700;color:#78350F}' +
            '.ac-warn code{font-family:"JetBrains Mono","SF Mono","Fira Code",monospace;word-break:break-all}' +
            '.ac-box.is-warn .ac-icon{background:#FEF3C7}' +
            '.ac-box.is-warn .ac-icon svg{stroke:#D97706}' +
            '.ac-btns{display:flex;justify-content:flex-end;gap:8px;margin-top:16px}' +
            '.ac-btn{padding:8px 18px;border-radius:7px;font-size:13px;font-weight:600;line-height:18px;' +
            'border:1px solid transparent;cursor:pointer;font-family:inherit}' +
            '.ac-btn-cancel{background:#F3F4F6;color:#374151}' +
            '.ac-btn-cancel:hover{background:#E5E7EB}' +
            '.ac-btn-ok{background:#2563EB;color:#fff}' +
            '.ac-btn-ok:hover{background:#1D4ED8}' +
            '.ac-btn-alt{background:#fff;color:#374151;border-color:#D1D5DB}' +
            '.ac-btn-alt:hover{background:#F9FAFB}' +
            '.ac-btn[hidden]{display:none}' +
            '.ac-btn:disabled{opacity:.55;cursor:default}' +
            '.ac-btn:focus-visible{outline:2px solid #93C5FD;outline-offset:2px}' +
            '@media (max-width:640px){' +
            '.ac-ov{align-items:flex-end;padding:0}' +
            '.ac-box{max-width:100%;border-radius:14px 14px 0 0;padding-bottom:calc(24px + env(safe-area-inset-bottom))}' +
            '.ac-btns{flex-wrap:wrap}' +
            '.ac-btns button{flex:1 1 0;padding:11px 10px}' +
            /* 버튼이 셋이면 주 동작을 맨 위 전폭으로 — 좁은 폭에 셋을 나란히 두면 라벨이 접힌다 */
            '.ac-btns.has-alt .ac-btn-ok{flex-basis:100%;order:-1}}';
        st.textContent += ORIGIN_CSS + GROUP_CSS;
        document.head.appendChild(st);
    }

    var ov = null, els = {}, state = null, lastFocus = null, queueSeq = 0, groupSeq = 0;

    /** 판정이 이보다 오래 걸리면 단독 분석은 풀어 준다(응답이 늦게 오면 그때 제안을 그린다). */
    var GROUP_SLOW_MS = 8000;

    /**
     * 연결된 로그 블록 — 옅은 배경 + 1px 테두리(좌측 색 띠 금지). 목록은 번호·파일명(+꼬리표)·시각 범위·크기 4열이고,
     * 좁은 폭에서는 시각·크기를 둘째 줄로 내린다.
     */
    var GROUP_CSS = ''
        + '.ac-box.is-wide{max-width:600px}'
        + '.ac-group{margin:0 0 12px;padding:11px 13px;border:1px solid #BBF7D0;border-radius:9px;background:#F7FEF9;font-size:12px;color:#1F2937}'
        + '.ac-group[hidden]{display:none}'
        + '.ac-group.is-quiet{border-color:#E5E7EB;background:#F9FAFB;color:#4B5563}'
        + '.ac-group.is-member{border-color:#BFDBFE;background:#F5F9FF}'
        + '.ac-group-title{font-size:13px;font-weight:700;color:#14532D;margin-bottom:3px;display:flex;align-items:center;gap:7px}'
        + '.ac-group.is-quiet .ac-group-title{color:#374151}'
        + '.ac-group.is-member .ac-group-title{color:#1E3A8A}'
        + '.ac-group-desc{line-height:1.55;color:#4B5563}'
        /* 파일명은 통째로 다음 줄로 넘기고, 그래도 넘칠 때만 쪼갠다(break-all 은 gc_20|2609… 처럼 아무 데서나 자른다) */
        + '.ac-group-desc code,.ac-group-notes code{font-family:"JetBrains Mono","SF Mono","Fira Code",monospace;font-size:11.5px;overflow-wrap:anywhere}'
        + '.ac-nowrap{white-space:nowrap}'
        + '.ac-spin{display:inline-block;width:12px;height:12px;border:2px solid #D1FAE5;border-top-color:#059669;border-radius:50%;'
        + 'animation:acSpin .8s linear infinite;flex-shrink:0}'
        + '@keyframes acSpin{to{transform:rotate(360deg)}}'
        + '.ac-axes{display:flex;flex-wrap:wrap;gap:5px;margin:8px 0 2px}'
        + '.ac-axis{font-size:11px;font-weight:600;padding:2px 8px;border-radius:999px;border:1px solid #BBF7D0;background:#fff;color:#166534}'
        + '.ac-axis.unk{border-color:#E5E7EB;color:#6B7280}'
        + '.ac-gl{list-style:none;margin:9px 0 0;padding:0;border:1px solid #E5E7EB;border-radius:7px;background:#fff;max-height:236px;overflow-y:auto}'
        + '.ac-gm{display:grid;grid-template-columns:20px minmax(0,1fr) auto auto;gap:4px 10px;align-items:center;'
        + 'padding:6px 10px;border-bottom:1px solid #F3F4F6;font-variant-numeric:tabular-nums}'
        + '.ac-gm:last-child{border-bottom:none}'
        + '.ac-gm.is-self{background:#F0FDF4}'
        + '.ac-gm-no{color:#9CA3AF;font-size:11px;text-align:right}'
        + '.ac-gm-main{display:flex;align-items:center;gap:5px;min-width:0}'
        + '.ac-gm-name{font-family:"JetBrains Mono","SF Mono","Fira Code",monospace;font-size:11.5px;color:#111827;'
        + 'overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0}'
        + '.ac-gm.is-missing .ac-gm-name{color:#9CA3AF;text-decoration:line-through}'
        + '.ac-gm-range{color:#4B5563;font-size:11.5px;white-space:nowrap}'
        + '.ac-gm-size{color:#6B7280;font-size:11px;white-space:nowrap;text-align:right;min-width:58px}'
        + '.ac-tag{font-size:10px;font-weight:700;padding:1px 5px;border-radius:4px;flex-shrink:0;white-space:nowrap}'
        + '.ac-tag.self{background:#DCFCE7;color:#166534}'
        + '.ac-tag.rep{background:#DBEAFE;color:#1E40AF}'
        + '.ac-tag.done{background:#F3F4F6;color:#4B5563}'
        + '.ac-tag.err{background:#FEE2E2;color:#991B1B}'
        + '.ac-tag.miss{background:#FEF3C7;color:#92400E}'
        + '.ac-group-notes{margin:8px 0 0;padding:0 0 0 16px;color:#4B5563;line-height:1.55}'
        + '.ac-group-notes li{margin:1px 0}'
        + '.ac-group-notes li.warn{color:#92400E}'
        + '.ac-group-foot{margin-top:7px;font-size:11px;color:#6B7280}'
        + '@media (max-width:640px){'
        + '.ac-gm{grid-template-columns:20px minmax(0,1fr) auto}'
        + '.ac-gm-range{grid-column:2/3}'
        + '.ac-gm-size{grid-column:3/4}}';

    /** 출처 입력 블록 스타일 — 모듈이 CSS 를 스스로 주입하는 이 파일의 규약을 따른다. */
    var ORIGIN_CSS = ''
        + '.ac-origin{margin:12px 0 0;padding:11px 13px;border:1px solid #BFDBFE;border-radius:9px;background:#EFF6FF}'
        + '.ac-origin[hidden]{display:none}'
        + '.ac-origin-title{font-size:12.5px;font-weight:700;color:#1E40AF;margin-bottom:3px}'
        + '.ac-origin-desc{font-size:11.5px;color:#475569;line-height:1.5;margin-bottom:8px}'
        + '.ac-origin-row{display:flex;align-items:center;gap:8px;margin-top:6px;font-size:12px;color:#374151}'
        + '.ac-origin-row>span{flex:0 0 86px}'
        + '.ac-origin-row input{flex:1 1 auto;min-width:0;padding:5px 9px;border:1px solid #CBD5E1;border-radius:6px;'
        + 'font-size:12.5px;font-family:inherit;color:#111827;background:#fff}'
        + '.ac-origin-row input:focus{outline:2px solid #93C5FD;outline-offset:1px}'
        + '.ac-req{color:#B91C1C;font-size:10px;font-weight:700}'
        + '.ac-opt{color:#9CA3AF;font-size:10px;font-weight:600}';

    function build() {
        if (ov) return;
        injectStyle();
        ov = document.createElement('div');
        ov.className = 'ac-ov';
        ov.id = 'analyzeConfirmModal';
        ov.innerHTML =
            '<div class="ac-box" role="dialog" aria-modal="true" aria-labelledby="acTitle">' +
            '  <div class="ac-hdr">' +
            '    <div class="ac-icon"><svg viewBox="0 0 24 24" data-ac="icon"><polygon points="6 4 20 12 6 20 6 4"/></svg></div>' +
            '    <div><h3 class="ac-title" id="acTitle">분석 시작 확인</h3>' +
            '    <div class="ac-sub" data-ac="sub">아래 파일의 분석을 시작합니다.</div></div>' +
            '  </div>' +
            '  <div class="ac-info">' +
            '    <div class="ac-row"><span class="ac-lbl">파일명</span><span class="ac-val mono" data-ac="filename"></span></div>' +
            '    <div class="ac-row"><span class="ac-lbl">유형</span><span class="ac-val"><span class="ac-kind" data-ac="kind"></span></span></div>' +
            '    <div class="ac-row" data-ac="sizeRow"><span class="ac-lbl">크기</span><span class="ac-val mono" data-ac="size"></span></div>' +
            '    <div class="ac-row" data-ac="sourceRow"><span class="ac-lbl">출처 서버</span><span class="ac-val" data-ac="source"></span></div>' +
            '  </div>' +
            '  <div class="ac-group" data-ac="group" aria-live="polite" hidden></div>' +
            '  <div class="ac-origin" data-ac="origin" hidden>' +
            '    <div class="ac-origin-title">이 GC 로그를 수집한 서버를 입력하세요</div>' +
            '    <div class="ac-origin-desc">분석명(<b>호스트네임_인스턴스명_일자</b>)과 힙 덤프 자동 매칭의 \'같은 서버\' 신호로 씁니다.</div>' +
            '    <label class="ac-origin-row"><span>호스트네임 <b class="ac-req">필수</b></span>' +
            '      <input type="text" data-ac="host" maxlength="100" placeholder="예: was01" autocomplete="off"></label>' +
            '    <label class="ac-origin-row"><span>인스턴스명 <span class="ac-opt">선택</span></span>' +
            '      <input type="text" data-ac="inst" maxlength="100" placeholder="예: container1" autocomplete="off"></label>' +
            '  </div>' +
            '  <div class="ac-warn" data-ac="warn" role="alert"></div>' +
            '  <div class="ac-queue unknown" data-ac="queue" aria-live="polite"></div>' +
            '  <div class="ac-warn" data-ac="note"></div>' +
            '  <div class="ac-btns" data-ac="btns">' +
            '    <button type="button" class="ac-btn ac-btn-cancel" data-ac="cancel">취소</button>' +
            '    <button type="button" class="ac-btn ac-btn-alt" data-ac="alt" hidden>이 파일만 분석</button>' +
            '    <button type="button" class="ac-btn ac-btn-ok" data-ac="ok">분석 시작</button>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(ov);
        ov.querySelectorAll('[data-ac]').forEach(function (el) { els[el.getAttribute('data-ac')] = el; });
        els.box = ov.querySelector('.ac-box');

        els.cancel.addEventListener('click', close);
        els.ok.addEventListener('click', confirm);
        els.alt.addEventListener('click', confirmAlone);
        els.host.addEventListener('input', syncButtons);
        ov.addEventListener('click', function (e) { if (e.target === ov) close(); });
        document.addEventListener('keydown', function (e) {
            if (!ov.classList.contains('open')) return;
            if (e.key === 'Escape') { e.preventDefault(); close(); }
            // 포커스를 모달 안에 가둔다 — 입력 칸(출처)·버튼 수가 상황마다 달라 보이는 것만 세어 끝↔처음을 잇는다
            if (e.key === 'Tab') {
                var f = focusables();
                if (!f.length) return;
                var first = f[0], last = f[f.length - 1];
                if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
                else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
            }
        });
        // 진행 화면에서 '뒤로'로 돌아오면 bfcache 가 '이동 중…'(disabled) 상태의 모달을 그대로 복원한다
        global.addEventListener('pageshow', function (e) { if (e.persisted) close(); });
    }

    function defaultTarget(kind, filename) {
        // 코어는 GDB 분석 진행 화면, GC 로그는 결과 페이지, 힙·기타는 MAT 진행 화면. 세 화면 모두 이미 SUCCESS 면 결과를 보여준다.
        // GC 로그의 ?start=1 은 '확인했으니 시작하라' 는 뜻 — 없으면 결과 페이지는 실패(ERROR) 기록을 보여 주기만 한다
        // (2026-09-14: 실패 행에서 '분석'을 눌러도 옛 오류만 다시 보이고 요청이 나가지 않았다).
        if (kind === 'core') return '/core-dump/progress/' + encodeURIComponent(filename);
        if (kind === 'gclog') return '/gc-log/analyze/' + encodeURIComponent(filename) + '?start=1';
        return '/analyze/' + encodeURIComponent(filename);
    }

    function setQueue(cls, html) {
        els.queue.className = 'ac-queue ' + cls;
        els.queue.innerHTML = html;
        els.queue.hidden = false;
    }

    /** 힙 분석 대기열 상태. 코어(GDB)는 별도 실행기라 이 대기열과 무관해 표시하지 않는다. */
    function loadQueue(filename) {
        var seq = ++queueSeq;
        setQueue('unknown', '분석 대기열을 확인하는 중…');
        if (!global.Common || !global.Common.fetchJSON) { els.queue.hidden = true; return; }
        global.Common.fetchJSON('/api/queue/status').then(function (d) {
            if (seq !== queueSeq || !state) return;   // 모달이 닫혔거나 다른 파일로 다시 열렸다
            var esc = global.Common.escHtml;
            var inProgress = (d.inProgressFiles || []).indexOf(filename) >= 0;
            var current = d.currentAnalysis;
            var qSize = d.queueSize || 0;
            var waiting = current ? Math.max(0, qSize - 1) : qSize;
            if (inProgress) {
                setQueue('running', '이 파일은 <b>이미 분석 중</b>입니다. 새로 시작하지 않고 진행 화면으로 이동합니다.');
                els.ok.textContent = '진행 화면 보기';
            } else if (!current && waiting === 0) {
                setQueue('', '진행 중인 분석이 없어 <b>바로 시작</b>됩니다.');
            } else {
                setQueue('busy', (current ? '현재 <b>' + esc(current) + '</b> 분석 중' : '분석 준비 중')
                    + (waiting > 0 ? ' · 대기 <b>' + waiting + '건</b>' : '')
                    + '<br>분석은 한 번에 1건씩 순서대로 실행되므로, 앞선 분석이 끝난 뒤 시작됩니다.');
            }
        }).catch(function (e) {
            if (seq !== queueSeq) return;
            // 대기열은 참고 정보 — 조회에 실패해도 분석 시작 자체는 막지 않는다
            ignored('대기열 상태 조회 실패', e);
            setQueue('unknown', '분석 대기열 상태를 확인하지 못했습니다. 앞선 분석이 있으면 순서대로 대기합니다.');
        });
    }

    function open(opts) {
        if (!opts || !opts.filename) return;
        build();
        var kind = KIND_LABEL[opts.kind] ? opts.kind : 'heap';
        state = { filename: opts.filename, kind: kind, onConfirm: opts.onConfirm, extWarn: false, busy: false, group: null };

        els.filename.textContent = opts.filename;
        els.kind.textContent = KIND_LABEL[kind];
        els.kind.className = 'ac-kind ' + kind;
        els.size.textContent = opts.size || '';
        els.sizeRow.hidden = !opts.size;
        els.source.textContent = opts.source || '';
        els.sourceRow.hidden = !opts.source;

        els.sub.textContent = kind === 'core'
            ? '아래 코어 덤프의 GDB 분석을 시작합니다.'
            : kind === 'gclog'
            ? '아래 GC 로그를 파싱해 일시정지·처리량·힙 추세를 분석합니다. 수 초에서 수십 초 걸립니다.'
            : '아래 파일의 Eclipse MAT 분석을 시작합니다. 덤프 크기에 따라 수 분 이상 걸릴 수 있습니다.';

        // 코어 파일은 원래 확장자가 없고(core, core.1234) GC 로그는 .log/.log.N/.gz 다 — 확장자 경고는 MAT 로 가는 힙·기타에만
        var extWarn = kind !== 'core' && kind !== 'gclog' && !hasHeapDumpExtension(opts.filename);
        state.extWarn = extWarn;
        if (extWarn) {
            var esc = global.Common.escHtml;
            els.warn.innerHTML = (kind === 'others' ? '<b>기타(Others)</b> 유형으로 분류된 파일입니다. ' : '')
                + '<code>' + esc(opts.filename) + '</code> 은(는) <b>힙 덤프 파일 확장자가 아닙니다</b>'
                + ' (.hprof · .bin · .dump · .dmp, .gz 압축 포함). 힙 덤프가 아니면 Eclipse MAT 이 인식하지 못해 '
                + '분석이 실패하거나 부정확한 결과가 나올 수 있습니다.'
                + '<span class="ac-ask">덤프 파일 확장자가 아닌데 분석을 진행하시겠습니까?</span>';
        }
        els.warn.hidden = !extWarn;
        els.box.classList.toggle('is-warn', extWarn);
        els.box.classList.remove('is-wide');
        els.note.textContent = opts.note || '';
        els.note.hidden = !opts.note;

        els.ok.disabled = false;
        els.ok.textContent = extWarn ? '그래도 분석 시작' : '분석 시작';
        els.alt.hidden = true;
        els.alt.disabled = false;
        els.alt.textContent = '이 파일만 분석';
        els.btns.classList.remove('has-alt');
        els.group.hidden = true;
        els.group.innerHTML = '';
        if (kind === 'core' || kind === 'gclog') { queueSeq++; els.queue.hidden = true; }   // 별도 실행기 — MAT 대기열과 무관
        else loadQueue(opts.filename);
        els.origin.hidden = true;
        if (kind === 'gclog') {
            loadOrigin(opts.filename, opts.source);
            loadGroup(opts.filename);
        }

        lastFocus = document.activeElement;
        ov.classList.add('open');
        // 되묻는 경우엔 Enter 한 번으로 진행되지 않도록 기본 포커스를 '취소'에 둔다.
        // GC 로그는 연결 로그 판정 중 시작 버튼이 잠겨 있어(포커스 불가) 취소에 두고, 판정이 끝나면 주 동작으로 옮긴다.
        (extWarn || kind === 'gclog' ? els.cancel : els.ok).focus();
    }

    function close() {
        if (!ov) return;
        ov.classList.remove('open');
        if (state && state.group && state.group.timer) clearTimeout(state.group.timer);
        state = null;
        queueSeq++;
        groupSeq++;
        if (lastFocus && typeof lastFocus.focus === 'function' && document.contains(lastFocus)) lastFocus.focus();
        lastFocus = null;
    }

    /** 모달 안에서 키보드로 닿는 요소 — 숨었거나 잠긴 것은 뺀다. */
    function focusables() {
        return Array.prototype.filter.call(els.box.querySelectorAll('button, input'), function (el) {
            return !el.disabled && !el.hidden && el.offsetParent !== null;
        });
    }

    /**
     * GC 로그의 출처(호스트네임) 확인 — 2026-09-20.
     *
     * <p>대시보드·Files 업로드 큐로 들어온 로그는 물을 자리가 없어 여기서 받는다. 비어 있으면 <b>입력 전까지
     * 시작 버튼을 잠근다</b>(서버도 {@code NO_HOSTNAME} 으로 막지만, 막힌 이유를 여기서 바로 보여 준다).
     * 값은 화면이 추측하지 않고 {@code /match} 응답(서버가 아는 진실)을 그대로 읽는다.
     */
    function loadOrigin(filename, sourceHint) {
        if (!global.Common || !global.Common.fetchJSON) return;
        var seq = ++queueSeq;
        global.Common.fetchJSON('/api/gc-log/' + encodeURIComponent(filename) + '/match').then(function (d) {
            if (seq !== queueSeq || !state || state.filename !== filename) return;
            var host = d && d.serverName ? String(d.serverName).trim() : '';
            if (host) { els.origin.hidden = true; syncButtons(); return; }
            els.origin.hidden = false;
            els.host.value = sourceHint || '';
            els.inst.value = (d && d.instance && d.instance.manual) ? d.instance.name : '';
            syncButtons();
            els.host.focus();
        }).catch(function (e) {
            // 출처 조회 실패는 분석 자체를 막지 않는다 — 서버가 한 번 더 검사한다
            ignored('GC 로그 출처 조회 실패', e);
        });
    }

    function originMissing() {
        return !els.origin.hidden && !(els.host.value || '').trim();
    }

    // ── 연결된 로그(연속 회전본) 제안 — 2026-09-23 ─────────────────

    function suggestion() { return state && state.group && state.group.data ? state.group.data : null; }

    function modeOf() { var d = suggestion(); return d ? d.mode : 'single'; }

    function memberOf(d, filename) {
        var ms = (d && d.members) || [];
        for (var i = 0; i < ms.length; i++) if (ms[i].filename === filename) return ms[i];
        return null;
    }

    /** 이 GC 로그가 속한 연속 회전본을 서버에 묻는다(쓰기 없음). 실패하면 조용히 단독 분석으로 남는다. */
    function loadGroup(filename) {
        if (!global.Common || !global.Common.fetchJSON) return;
        var seq = ++groupSeq;
        var g = state.group = { pending: true, slow: false, data: null, timer: null };
        els.group.className = 'ac-group';
        els.group.innerHTML = '<div class="ac-group-title"><span class="ac-spin" aria-hidden="true"></span>이어지는 로그를 확인하는 중…</div>'
            + '<div class="ac-group-desc">같은 서버에서 회전된 GC 로그가 더 있는지 봅니다.</div>';
        els.group.hidden = false;
        g.timer = setTimeout(function () {
            if (seq !== groupSeq || !g.pending) return;
            g.slow = true;
            var desc = els.group.querySelector('.ac-group-desc');
            if (desc) desc.textContent = '확인이 늦어지고 있습니다. 기다리거나 이 파일만 분석할 수 있습니다.';
            syncButtons();
        }, GROUP_SLOW_MS);
        syncButtons();
        global.Common.fetchJSON('/api/gc-log/' + encodeURIComponent(filename) + '/group-suggestion').then(function (d) {
            if (seq !== groupSeq || !state || state.filename !== filename) return;
            clearTimeout(g.timer);
            g.pending = false;
            g.data = d && d.success ? d : null;
            if (g.data) renderGroup(g.data); else els.group.hidden = true;
            syncButtons();
            focusPrimary();
        }).catch(function (e) {
            if (seq !== groupSeq || !state) return;
            clearTimeout(g.timer);
            // 제안은 편의다 — 기록 없음(404)·세션·네트워크 오류 모두 종전처럼 이 파일만 분석하게 둔다
            ignored('연결 로그 제안 조회 실패', e);
            g.pending = false;
            els.group.hidden = true;
            syncButtons();
        });
    }

    /** 판정이 끝났을 때 사용자가 아직 포커스를 옮기지 않았으면 주 동작 버튼으로 옮긴다. */
    function focusPrimary() {
        var a = document.activeElement;
        if (a && a !== els.cancel && a !== document.body && els.box.contains(a)) return;
        if (!els.ok.disabled) els.ok.focus();
    }

    function fmtRange(s, e) {
        if (!s && !e) return '시각 없음';
        var sameDay = s && e && s.slice(0, 10) === e.slice(0, 10);
        return (s ? s.slice(5, 16) : '?') + ' → ' + (e ? (sameDay ? e.slice(11, 16) : e.slice(5, 16)) : '?');
    }

    /** 서버가 준 로컬 시각 문자열(yyyy-MM-dd HH:mm:ss) 두 개의 간격 — 둘 다 같은 시간대라 차이만 본다. */
    function fmtSpan(s, e) {
        if (!s || !e) return '';
        var a = Date.parse(s.replace(' ', 'T')), b = Date.parse(e.replace(' ', 'T'));
        if (isNaN(a) || isNaN(b) || b <= a) return '';
        var min = Math.round((b - a) / 60000);
        if (min < 60) return min + '분';
        var h = Math.floor(min / 60), d = Math.floor(h / 24);
        if (d > 0) return d + '일' + (h % 24 ? ' ' + (h % 24) + '시간' : '');
        return h + '시간' + (min % 60 ? ' ' + (min % 60) + '분' : '');
    }

    function statusTag(st) {
        if (st === 'SUCCESS') return '<b class="ac-tag done">분석됨</b>';
        if (st === 'ERROR') return '<b class="ac-tag err">실패</b>';
        if (st === 'ANALYZING') return '<b class="ac-tag done">분석 중</b>';
        return '';
    }

    function renderGroup(d) {
        var esc = global.Common.escHtml, fb = global.Common.formatBytes;
        var box = els.group, mode = d.mode, ms = d.members || [];
        var rep = d.representative, self = d.filename;

        if (mode === 'single' || ms.length < 2) {
            // 같은 계열 후보가 있었는데 이어지지 않을 때만 짧게 알린다 — 후보가 없으면 블록 자체가 소음이다
            if ((d.candidates || 0) > 1 || (d.excludedGrouped || 0) > 0) {
                var why = d.reason ? esc(d.reason) + '.' : '';
                if ((d.excludedGrouped || 0) > 0) {
                    why += (why ? ' ' : '') + '같은 경로의 로그 ' + d.excludedGrouped + '개는 이미 다른 묶음에 속해 있습니다'
                        + ' — 그 묶음의 결과 화면 \'로그 이어붙이기\'에서 이 파일을 더할 수 있습니다.';
                }
                box.className = 'ac-group is-quiet';
                box.innerHTML = '<div class="ac-group-title">이어 붙일 로그를 찾지 못했습니다</div>'
                    + '<div class="ac-group-desc">' + why + ' 이 파일만 분석합니다.</div>';
                box.hidden = false;
            } else {
                box.hidden = true;
            }
            return;
        }

        var first = ms[0], last = ms[ms.length - 1];
        var span = fmtSpan(first.start, last.end);
        var range = first.start && last.end
            ? '<b class="ac-nowrap">' + esc(fmtRange(first.start, last.end)) + '</b>' + (span ? ' · <span class="ac-nowrap">' + esc(span) + '</span>' : '')
            : '';
        var repRow = memberOf(d, rep) || {};
        var title, desc;
        if (mode === 'group') {
            title = '이어지는 GC 로그 ' + ms.length + '개를 찾았습니다';
            desc = (d.serverName ? '<b>' + esc(d.serverName) + '</b> 의 ' : '')
                + (d.series ? '같은 경로(<code>' + esc(d.series) + '</code>)에서 ' : '')
                + '회전된 로그가 시간순으로 이어집니다. 함께 분석하면 전체 구간' + (range ? '(' + range + ')' : '')
                + '의 힙 추세와 Full GC 간격을 한 번에 봅니다. 결과는 가장 최근 로그 <code>' + esc(rep) + '</code> 에 저장됩니다.';
        } else if (mode === 'combined') {
            title = '이어 붙인 로그 ' + ms.length + '개로 분석합니다';
            desc = '이 분석에 저장된 구성 그대로 이어 붙입니다' + (range ? '(' + range + ')' : '')
                + '. 구성은 결과 화면의 \'로그 이어붙이기\'에서 바꿀 수 있습니다.';
        } else {
            var idx = 0;
            for (var k = 0; k < ms.length; k++) if (ms[k].filename === self) idx = k + 1;
            title = '이어 붙인 묶음의 구성 파일입니다';
            desc = '<code>' + esc(rep) + '</code> 묶음(' + ms.length + '개)의 ' + (idx ? idx + '번째 ' : '') + '로그입니다. '
                + (repRow.status === 'SUCCESS' ? '묶음 분석 결과가 이미 있습니다.'
                   : repRow.status === 'ANALYZING' ? '묶음을 분석하는 중입니다.'
                   : '묶음으로 분석하면 전체 구간' + (range ? '(' + range + ')' : '') + '을 한 번에 봅니다.');
        }

        var h = '<div class="ac-group-title">' + esc(title) + '</div><div class="ac-group-desc">' + desc + '</div>';

        if (d.axes) {
            var ax = d.axes, fmtHeap = d.heapMaxBytes ? '최대 힙 ' + fb(d.heapMaxBytes) : '';
            var jvmBits = [d.collector, fmtHeap].filter(Boolean).join(' · ');
            h += '<div class="ac-axes">'
                + axisChip(ax.time === 'MATCH', '✓ 시간 연속', '시간 연속 확인 못 함')
                + axisChip(ax.origin === 'MATCH', '✓ 같은 서버' + (d.serverName ? ' · ' + d.serverName : ''), '서버 정보 없음')
                + axisChip(ax.jvm === 'MATCH', '✓ 같은 JVM 설정' + (jvmBits ? ' · ' + jvmBits : ''), 'JVM 설정 근거 없음')
                + '</div>';
        }

        h += '<ol class="ac-gl">';
        ms.forEach(function (m, i) {
            var tags = '';
            if (m.filename === self) tags += '<b class="ac-tag self">선택</b>';
            if (m.filename === rep) tags += '<b class="ac-tag rep">대표</b>';
            if (m.missing) tags += '<b class="ac-tag miss">파일 없음</b>';
            else tags += statusTag(m.status);
            h += '<li class="ac-gm' + (m.filename === self ? ' is-self' : '') + (m.missing ? ' is-missing' : '') + '">'
                + '<span class="ac-gm-no">' + (i + 1) + '</span>'
                + '<span class="ac-gm-main"><span class="ac-gm-name" title="' + esc(m.filename) + '">' + esc(m.filename) + '</span>' + tags + '</span>'
                + '<span class="ac-gm-range">' + esc(fmtRange(m.start, m.end)) + '</span>'
                + '<span class="ac-gm-size">' + esc(fb(m.size)) + '</span>'
                + '</li>';
        });
        h += '</ol>';

        var notes = [];
        (d.warnings || []).forEach(function (w) { notes.push({ text: esc(w) }); });
        if (mode === 'group') {
            if (repRow.status === 'SUCCESS') {
                notes.push({ warn: true, text: '대표 로그 <code>' + esc(rep) + '</code> 의 기존 분석 결과는 이어 붙인 결과로 바뀝니다.' });
            }
            var solo = ms.filter(function (m) { return m.filename !== rep && m.status === 'SUCCESS'; }).length;
            if (solo > 0) notes.push({ text: '이미 단독 분석된 로그 ' + solo + '개의 결과는 그대로 남고, 묶음의 구성 파일로 표시됩니다.' });
            if ((d.excludedGrouped || 0) > 0) notes.push({ text: '같은 경로의 로그 ' + d.excludedGrouped + '개는 이미 다른 묶음에 속해 있어 제외했습니다.' });
            if (d.maxMembers && ms.length >= d.maxMembers) notes.push({ warn: true, text: '한 번에 이어 붙일 수 있는 상한(' + d.maxMembers + '개)까지만 묶었습니다.' });
        }
        var missing = ms.filter(function (m) { return m.missing; }).length;
        if (missing > 0) notes.push({ warn: true, text: '구성 파일 ' + missing + '개가 없습니다 — 결과 화면의 \'로그 이어붙이기\'에서 구성에서 뺀 뒤 분석하세요.' });
        if (notes.length) {
            h += '<ul class="ac-group-notes">' + notes.map(function (n) {
                return '<li' + (n.warn ? ' class="warn"' : '') + '>' + n.text + '</li>';
            }).join('') + '</ul>';
        }
        if (mode === 'group') {
            h += '<div class="ac-group-foot">파일 수정은 없습니다 — 묶음은 목록에서 대표 한 줄로 접히고, 결과 화면에서 풀거나 바꿀 수 있습니다.</div>';
        }

        box.className = 'ac-group' + (mode === 'member' ? ' is-member' : '');
        box.innerHTML = h;
        box.hidden = false;
        els.box.classList.add('is-wide');
    }

    function axisChip(ok, okText, unkText) {
        return '<span class="ac-axis' + (ok ? '' : ' unk') + '">' + global.Common.escHtml(ok ? okText : unkText) + '</span>';
    }

    /**
     * 버튼 두 개(주 동작·이 파일만)의 라벨과 잠김을 한 곳에서 정한다 — 출처 입력·판정 대기·제안 종류가 모두 여기 모인다.
     * 힙·코어는 부르지 않는다(대기열 조회가 '진행 화면 보기' 로 바꾼 라벨을 덮지 않게).
     */
    function syncButtons() {
        if (!state || state.kind !== 'gclog' || state.busy) return;
        var g = state.group, d = suggestion(), mode = modeOf();
        var n = d && d.members ? d.members.length : 0;
        var withAlt = !!d && n > 1 && (mode === 'group' || mode === 'member');
        els.alt.hidden = !withAlt;
        els.btns.classList.toggle('has-alt', withAlt);

        if (g && g.pending && !g.slow) {
            els.ok.disabled = true;
            els.ok.textContent = '연결 로그 확인 중…';
            return;
        }
        var needOrigin = originMissing();
        var label, okNeedsOrigin = true;
        if (mode === 'group' && n > 1) {
            label = '이어붙여 분석 (' + n + '개)';
        } else if (mode === 'member' && n > 1) {
            var st = (memberOf(d, d.representative) || {}).status;
            label = st === 'SUCCESS' ? '묶음 결과 보기' : st === 'ANALYZING' ? '묶음 진행 보기' : '묶음으로 분석 (' + n + '개)';
            okNeedsOrigin = false;   // 묶음 대표의 출처는 대표의 몫이다 — 이 파일의 호스트네임과 무관
        } else if (mode === 'combined' && n > 1) {
            label = '분석 시작 (' + n + '개 이어붙임)';
        } else {
            label = state.extWarn ? '그래도 분석 시작' : '분석 시작';
        }
        els.ok.disabled = okNeedsOrigin && needOrigin;
        els.ok.textContent = els.ok.disabled ? '호스트네임을 입력하세요' : label;
        els.alt.disabled = needOrigin;
    }

    /** 요청 중 — 두 버튼을 잠그고 누른 버튼에만 진행 문구를 단다. */
    function setBusy(btn, text) {
        state.busy = true;
        els.ok.disabled = true;
        els.alt.disabled = true;
        btn.textContent = text;
    }

    function clearBusy() {
        if (!state) return;
        state.busy = false;
        els.alt.textContent = '이 파일만 분석';
        syncButtons();
        if (state.kind !== 'gclog') { els.ok.disabled = false; els.ok.textContent = state.extWarn ? '그래도 분석 시작' : '분석 시작'; }
    }

    function showError(msg) {
        els.warn.textContent = msg;
        els.warn.hidden = false;
    }

    /** fetchJSON 오류에서 사람이 읽을 문구 — 서버의 {success,code,error} 본문을 꺼낸다(함정 14). */
    function errText(e, fallback) {
        if (e && (e.sessionExpired || e.rateLimited)) return e.message;
        try {
            var b = e && e.body ? JSON.parse(e.body) : null;
            if (b && b.error) return b.error;
        } catch (x) { ignored('오류 본문 파싱 — JSON 아님', x); }
        return fallback + (e && e.status ? ' (HTTP ' + e.status + ')' : '');
    }

    /** 입력한 출처를 저장한 뒤 이어서 진행한다. 실패하면 모달을 닫지 않고 이유를 보인다. */
    function saveOriginThen(filename, done) {
        var host = (els.host.value || '').trim();
        var inst = (els.inst.value || '').trim();
        var base = '/api/gc-log/' + encodeURIComponent(filename);
        global.Common.fetchJSON(base + '/hostname', { method: 'POST', body: JSON.stringify({ hostname: host }) })
            .then(function () {
                if (!inst) return null;
                return global.Common.fetchJSON(base + '/instance', { method: 'POST', body: JSON.stringify({ instance: inst }) });
            })
            .then(function () { done(); })
            .catch(function (e) {
                failed('GC 로그 출처 저장', e);
                clearBusy();
                showError('출처를 저장하지 못했습니다. 잠시 후 다시 시도하세요.');
            });
    }

    /** 주 동작 — 제안 종류에 따라 이어붙여 분석 / 묶음 보기 / 단독 분석. */
    function confirm() {
        if (!state || state.busy) return;
        var s = state, d = suggestion(), mode = modeOf();
        var n = d && d.members ? d.members.length : 0;
        if (mode === 'member' && n > 1) { openMemberGroup(s, d); return; }
        if (mode === 'group' && n > 1) { withOrigin(els.ok, d.representative, function () { startCombined(d); }); return; }
        withOrigin(els.ok, s.filename, function () { proceed(s, els.ok); });
    }

    /** '이 파일만 분석' — 제안을 받지 않고 종전처럼 이 파일 하나만. */
    function confirmAlone() {
        if (!state || state.busy) return;
        var s = state;
        withOrigin(els.alt, s.filename, function () { proceed(s, els.alt); });
    }

    /** 출처 입력 칸이 떠 있으면 분석 대상 행에 먼저 저장한다(이어붙이기는 대표 행이 분석 대상이다). */
    function withOrigin(btn, target, go) {
        if (!els.origin.hidden) {
            if (!(els.host.value || '').trim()) { els.host.focus(); return; }
            setBusy(btn, '저장 중…');
            saveOriginThen(target, go);
            return;
        }
        go();
    }

    /** 수락한 묶음을 이어 붙여 분석을 시작하고 대표의 결과 화면(진행 폴링)으로 간다. */
    function startCombined(d) {
        var rep = d.representative;
        var names = (d.members || []).map(function (m) { return m.filename; });
        setBusy(els.ok, '이어 붙이는 중…');
        global.Common.fetchJSON('/api/gc-log/' + encodeURIComponent(rep) + '/combine', {
            method: 'POST',
            body: JSON.stringify({ filenames: names, via: 'suggest' })
        }).then(function () {
            // 분석은 이미 제출됐다 — ?start=1 을 붙이지 않는다(결과 화면이 ANALYZING 을 보고 폴링한다)
            global.location.href = '/gc-log/analyze/' + encodeURIComponent(rep);
        }).catch(function (e) {
            failed('이어붙여 분석 시작', e);
            clearBusy();
            showError(errText(e, '이어 붙여 분석을 시작하지 못했습니다.'));
        });
    }

    /** 구성 파일에서 연 경우 — 묶음 대표의 결과(또는 분석 시작) 화면으로 간다. */
    function openMemberGroup(s, d) {
        var rep = d.representative;
        var st = (memberOf(d, rep) || {}).status;
        setBusy(els.ok, '이동 중…');
        global.location.href = '/gc-log/analyze/' + encodeURIComponent(rep)
            + (st === 'SUCCESS' || st === 'ANALYZING' ? '' : '?start=1');
    }

    function proceed(s, btn) {
        setBusy(btn || els.ok, '이동 중…');   // 이동 전 이중 클릭 차단
        if (typeof s.onConfirm === 'function') {
            close();
            s.onConfirm(s.filename);
            return;
        }
        global.location.href = defaultTarget(s.kind, s.filename);
    }

    /** `<a href="/analyze/…" data-filename …>` 의 onclick 용 — JS 가 없으면 href 로 그대로 이동한다. */
    function fromLink(a) {
        var ds = a.dataset || {};
        open({ filename: ds.filename, kind: ds.kind, size: ds.size, source: ds.source });
        return false;
    }

    global.AnalyzeConfirm = { open: open, close: close, fromLink: fromLink, hasHeapDumpExtension: hasHeapDumpExtension };
})(window);
