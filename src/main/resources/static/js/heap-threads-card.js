/* heap-threads-card.js — 힙 결과 화면(analyze.html)의 '스레드 분석 요약' 카드 (2.7.1)
 * Thread Overview 패널 맨 위 카드(#htcCard)를 GET /api/thread-dump/heap/{fn}/summary 로 채운다.
 * 힙 덤프의 스레드 정보(.threads + Thread Overview)를 스레드 덤프 규칙으로 분석한 요약 — 상세는 /thread-dump/heap/{fn}.
 * 요약을 만들 수 없으면(산출물 없음 등) 카드에 사유만 보인다. 폴링하지 않는다(한 번 조회). */
(function () {
    'use strict';
    var TAG = '[HeapThreadsCard]';
    function byId(id) { return document.getElementById(id); }
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
            ? '연결된 스레드 덤프: ' + links.map(function (l) { return '<a href="/thread-dump/analyze/' + encodeURIComponent(l.filename) + '">' + esc(l.filename) + '</a>'; }).join(', ')
            : '같은 시점의 스레드 덤프가 있으면 스레드 덤프 결과 화면의 ‘힙 덤프’ 칩에서 이 힙 덤프를 연결하세요. 스냅샷 비교·락 관계까지 함께 볼 수 있습니다.') + '</div>';
        body.innerHTML = h;
    }

    document.addEventListener('DOMContentLoaded', function () {
        var card = byId('htcCard');
        if (!card || typeof FILENAME === 'undefined' || !FILENAME) return;
        Common.fetchJSON('/api/thread-dump/heap/' + encodeURIComponent(FILENAME) + '/summary')
            .then(render)
            .catch(function (e) { if (window.Common) Common.logIgnored(TAG + ' 요약 조회', e); });
    });
})();
