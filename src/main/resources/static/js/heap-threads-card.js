/* heap-threads-card.js — 힙 결과 화면(analyze.html)의 '스레드 분석 요약' 카드 (2.7.1)
 * Thread Overview 패널 맨 위 카드(#htcCard)를 GET /api/thread-dump/heap/{fn}/summary 로 채운다.
 * 힙 덤프의 스레드 정보(.threads + Thread Overview)를 스레드 덤프 규칙으로 분석한 요약 — 상세는 /thread-dump/heap/{fn}.
 * 요약을 만들 수 없으면(산출물 없음 등) 카드에 사유만 보인다. 폴링하지 않는다(한 번 조회). */
(function () {
    'use strict';
    var TAG = '[HeapThreadsCard]';
    function byId(id) { return document.getElementById(id); }
    /** 스레드 덤프 화면 경로 — 프록시(/dump) 경유면 접두사를 붙인다(CLAUDE.md 함정 92). */
    function tdPath(p) { return window.Common && Common.appPath ? Common.appPath(p) : p; }
    var esc = function (s) { return window.Common ? Common.escHtml(String(s == null ? '' : s)) : String(s == null ? '' : s); };
    function fmtNum(n) { return n == null ? '–' : Number(n).toLocaleString('en-US'); }
    var STATE_KO = { RUNNABLE: 'RUNNABLE', BLOCKED: 'BLOCKED', WAITING: 'WAITING', TIMED_WAITING: 'TIMED_WAITING', UNKNOWN: '알 수 없음' };

    function kpi(k, v, s) {
        return '<div class="htc-kpi"><div class="k">' + esc(k) + '</div><div class="v" title="' + esc(v) + '">' + esc(v) + '</div>'
            + (s ? '<div class="s" title="' + esc(s) + '">' + esc(s) + '</div>' : '') + '</div>';
    }

    function render(d) {
        var card = byId('htcCard'), body = byId('htcBody');
        if (!card || !body) return;
        card.style.display = '';
        var exp = byId('htcExportBtn');
        if (exp) exp.hidden = !d.available;   // 스레드 정보를 만들 수 있을 때만 내보낼 수 있다
        if (!d.available) {
            body.innerHTML = '<div class="htc-empty">' + esc(d.error || '스레드 정보를 만들지 못했습니다.') + '</div>';
            return;
        }
        var st = d.states || {};
        var h = '<div class="htc-grid">';
        h += kpi('스레드', fmtNum(d.threads), 'daemon ' + fmtNum(d.daemon));
        h += kpi('RUNNABLE', fmtNum(st.RUNNABLE || 0), 'BLOCKED ' + fmtNum(st.BLOCKED || 0));
        h += kpi('WAITING', fmtNum((st.WAITING || 0) + (st.TIMED_WAITING || 0)), 'TIMED ' + fmtNum(st.TIMED_WAITING || 0));
        var top = (d.topMemory || [])[0];
        h += kpi('힙 점유 최대', top ? top.retainedText : '–', top ? top.name : '스레드별 점유 정보 없음');
        h += '</div>';
        var fs = d.findings || [];
        h += '<div class="htc-sec">소견</div>';
        h += fs.length ? fs.map(function (f) {
            return '<div class="htc-find sev-' + esc(f.severity) + '"><span class="htc-sev">' + esc(f.severity) + '</span><span>' + esc(f.title) + '</span></div>';
        }).join('') : '<div class="htc-empty">규칙 기반 이상 징후가 없습니다(스냅샷 비교·CPU·synchronized 락 관계는 힙 덤프에 없어 판정하지 않았습니다).</div>';
        var mem = d.topMemory || [];
        if (mem.length) {
            h += '<div class="htc-sec">힙 점유가 큰 스레드</div><table class="htc-table"><thead><tr><th>스레드</th><th>상태</th><th class="num">Retained</th><th>위치</th></tr></thead><tbody>'
                + mem.map(function (m) {
                    return '<tr><td>' + esc(m.name) + '</td><td>' + esc(STATE_KO[m.state] || m.state) + '</td><td class="num">' + esc(m.retainedText) + '</td><td class="fr">' + esc(m.top) + '</td></tr>';
                }).join('') + '</tbody></table>';
        }
        var groups = d.groups || [];
        if (groups.length) {
            h += '<div class="htc-sec">같은 곳에 모인 스레드</div><table class="htc-table"><tbody>'
                + groups.map(function (g) {
                    return '<tr><td class="num">' + fmtNum(g.count) + '개</td><td>' + esc(g.state) + '</td><td class="fr">' + esc(g.top) + '</td></tr>';
                }).join('') + '</tbody></table>';
        }
        var links = d.linkedThreadDumps || [];
        h += '<div class="htc-links">' + (links.length
            ? '연결된 스레드 덤프: ' + links.map(function (l) { return '<a href="' + tdPath('/thread-dump/analyze/' + encodeURIComponent(l.filename)) + '">' + esc(l.filename) + '</a>'; }).join(', ')
            : '같은 시점의 스레드 덤프가 있으면 스레드 덤프 결과 화면의 ‘힙 덤프’ 칩에서 이 힙 덤프를 연결하세요. 스냅샷 비교·락 관계까지 함께 볼 수 있습니다.') + '</div>';
        body.innerHTML = h;
    }

    function load() {
        return Common.fetchJSON('/api/thread-dump/heap/' + encodeURIComponent(FILENAME) + '/summary')
            .then(render)
            .catch(function (e) { if (window.Common) Common.logIgnored(TAG + ' 요약 조회', e); });
    }

    // ── 스레드 덤프 파일로 내보내기 ── 이 페이지는 토스트가 보이지 않아(common.css 없음) 카드 안 한 줄로 결과를 알린다.
    // 성공하면 요약을 다시 받아 '연결된 스레드 덤프' 에 새 파일이 보이게 한다. 이미 있으면(409) 목록 보기 / 새 이름으로 를 고른다.
    var _exporting = false;
    function msg(cls, html) {
        var el = byId('htcExportMsg');
        if (!el) return;
        el.className = 'htc-export-msg' + (cls ? ' ' + cls : '');
        el.innerHTML = html;
        el.hidden = !html;
    }
    function listLink(name) {
        return '<a href="' + tdPath('/thread-dump?file=' + encodeURIComponent(name)) + '">스레드 덤프 목록에서 보기</a>';
    }
    function htcExport(newName) {
        if (_exporting || typeof FILENAME === 'undefined') return;
        _exporting = true;
        var btn = byId('htcExportBtn');
        if (btn) { btn.disabled = true; btn.textContent = '내보내는 중…'; }
        msg('', '스레드 덤프 파일을 만드는 중입니다…');
        var done = function () { _exporting = false; if (btn) { btn.disabled = false; btn.textContent = '스레드 덤프로 내보내기'; } };
        Common.fetchJSON('/api/thread-dump/heap/' + encodeURIComponent(FILENAME) + '/export' + (newName ? '?newName=true' : ''), { method: 'POST' })
            .then(function (d) {
                done();
                var tail = listLink(d.filename) + ' · <a href="' + tdPath('/thread-dump/analyze/' + encodeURIComponent(d.filename)) + '">결과 보기</a>';
                var head = '<b>' + esc(d.filename) + '</b> (스레드 ' + fmtNum(d.threads) + '개)';
                if (d.status === 'SUCCESS') msg('ok', '내보내고 분석까지 마쳤습니다 — ' + head + '. 스레드 덤프 목록에 완료 상태로 올라갔습니다. ' + tail);
                else if (d.status === 'ERROR') msg('warn', '파일은 내보냈지만 분석에 실패했습니다 — ' + head + '. ' + esc(d.analysisError || '') + ' ' + tail);
                else msg('ok', '내보냈고 분석 중입니다 — ' + head + '. 앞선 분석이 끝나는 대로 이어서 분석합니다. ' + tail);
                load();
            })
            .catch(function (e) {
                done();
                var b = null;
                try { b = e && e.body ? JSON.parse(e.body) : null; } catch (x) { Common.logIgnored(TAG + ' 내보내기 오류 본문 파싱', x); }
                if (b && b.code === 'EXPORT_EXISTS') {
                    msg('warn', '이미 내보낸 파일이 있습니다 — <b>' + esc(b.filename) + '</b>. ' + listLink(b.filename)
                        + ' · <button type="button" onclick="htcExport(true)">새 이름으로 내보내기</button>');
                    return;
                }
                Common.logError(TAG + ' 내보내기 실패', e);
                msg('bad', esc(e && e.sessionExpired ? '세션이 만료되었습니다. 다시 로그인하세요.' : ((b && b.error) || (e && e.message) || '내보내지 못했습니다.')));
            });
    }
    window.htcExport = htcExport;

    document.addEventListener('DOMContentLoaded', function () {
        var card = byId('htcCard');
        if (!card || typeof FILENAME === 'undefined' || !FILENAME) return;
        load();
    });
})();
