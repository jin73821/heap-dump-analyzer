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
 *       kind:      'heap' | 'core' | 'others',   // 기본 heap
 *       size:      '157 MB',                     // 선택 — 표시용
 *       source:    'WAS-01',                     // 선택 — 출처 서버
 *       note:      '추가 안내 문구',               // 선택 — 코어 실행파일 미전송 경고 등
 *       onConfirm: function (filename) { … }     // 선택 — 없으면 분석 진행 화면으로 이동
 *   })
 *   AnalyzeConfirm.fromLink(a)   // <a data-filename data-kind data-size data-source> 의 onclick 용. 항상 false 반환.
 *
 * CSS·DOM 은 스스로 주입하는 싱글턴이다 — 페이지마다 모달 골격(.modal-box/.modal-btns…) 정의가 달라(함정 17)
 * 공용 클래스에 기대면 페이지별로 모양이 갈린다. 그래서 전부 `.ac-` 네임스페이스로 자립한다.
 */
(function (global) {
    'use strict';

    function ignored(tag, e) { if (global.Common) global.Common.logIgnored('[AnalyzeConfirm] ' + tag, e); }

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
            '.ac-btn:disabled{opacity:.55;cursor:default}' +
            '.ac-btn:focus-visible{outline:2px solid #93C5FD;outline-offset:2px}' +
            '@media (max-width:640px){' +
            '.ac-ov{align-items:flex-end;padding:0}' +
            '.ac-box{max-width:100%;border-radius:14px 14px 0 0;padding-bottom:calc(24px + env(safe-area-inset-bottom))}' +
            '.ac-btns button{flex:1 1 0;padding:11px 10px}}';
        document.head.appendChild(st);
    }

    var ov = null, els = {}, state = null, lastFocus = null, queueSeq = 0;

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
            '  <div class="ac-warn" data-ac="warn" role="alert"></div>' +
            '  <div class="ac-queue unknown" data-ac="queue" aria-live="polite"></div>' +
            '  <div class="ac-warn" data-ac="note"></div>' +
            '  <div class="ac-btns">' +
            '    <button type="button" class="ac-btn ac-btn-cancel" data-ac="cancel">취소</button>' +
            '    <button type="button" class="ac-btn ac-btn-ok" data-ac="ok">분석 시작</button>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(ov);
        ov.querySelectorAll('[data-ac]').forEach(function (el) { els[el.getAttribute('data-ac')] = el; });
        els.box = ov.querySelector('.ac-box');

        els.cancel.addEventListener('click', close);
        els.ok.addEventListener('click', confirm);
        ov.addEventListener('click', function (e) { if (e.target === ov) close(); });
        document.addEventListener('keydown', function (e) {
            if (!ov.classList.contains('open')) return;
            if (e.key === 'Escape') { e.preventDefault(); close(); }
            // 포커스를 모달 안에 가둔다 — 버튼 두 개뿐이라 끝↔처음만 순환시키면 된다
            if (e.key === 'Tab') {
                var first = els.cancel, last = els.ok.disabled ? els.cancel : els.ok;
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
        state = { filename: opts.filename, kind: kind, onConfirm: opts.onConfirm };

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
        els.note.textContent = opts.note || '';
        els.note.hidden = !opts.note;

        els.ok.disabled = false;
        els.ok.textContent = extWarn ? '그래도 분석 시작' : '분석 시작';
        if (kind === 'core' || kind === 'gclog') { queueSeq++; els.queue.hidden = true; }   // 별도 실행기 — MAT 대기열과 무관
        else loadQueue(opts.filename);

        lastFocus = document.activeElement;
        ov.classList.add('open');
        // 되묻는 경우엔 Enter 한 번으로 진행되지 않도록 기본 포커스를 '취소'에 둔다
        (extWarn ? els.cancel : els.ok).focus();
    }

    function close() {
        if (!ov) return;
        ov.classList.remove('open');
        state = null;
        queueSeq++;
        if (lastFocus && typeof lastFocus.focus === 'function' && document.contains(lastFocus)) lastFocus.focus();
        lastFocus = null;
    }

    function confirm() {
        if (!state) return;
        var s = state;
        els.ok.disabled = true;              // 이동 전 이중 클릭 차단
        els.ok.textContent = '이동 중…';
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
