/* table-grid.js — 클라이언트 데이터 그리드 공통 엔진 (2026-08-02)
 *
 * files / history / servers / comparison-history 4페이지에 복붙돼 있던
 * 정렬(헤더 ▲▼) + 페이지네이션(‹ 1 … ›) + 행표시 셀렉트(localStorage) 통합.
 * 페이지는 얇은 전역 위임 함수(gotoPage/onHeaderSort/...)를 유지해
 * HTML inline 핸들러(onclick="onHeaderSort(this)")를 그대로 보존한다.
 *
 * cfg:
 *   tbodyId          (필수) 행 tbody id
 *   headerSelector   (필수) 정렬 헤더 셀렉터 (예: '.htable th.sortable')
 *   pageSizeKey      (필수) 행표시 localStorage 키
 *   applyFilter      (필수) 페이지의 필터 함수 — 헤더 정렬 후 재호출됨
 *   defaultSort      { key, dir: 'asc'|'desc', type: 'num'|'str' }
 *   sortAttrPrefix   행 정렬값 속성 접두 (기본 'data-sort-', servers 는 'data-')
 *   sortValue(el,key) 정렬값 커스텀 훅 — null/undefined 반환 시 기본 속성 조회
 *   attachRow(tbody,row) 정렬 후 행 재부착 훅 (files 의 exec sub-row 페어)
 *   onRenderStart / showRow(row) / onAfterRender  렌더 훅
 *   pageSizeSelectId('pageSizeSelect') noMatchId('noMatch')
 *   paginationBarId('paginationBar') pgInfoId('pgInfo') pgListId('pgList')
 *   pageSizes        행표시 허용값 (기본 [20,30,50,100]) / defaultPageSize (기본 20)
 *   detach           true = 현재 페이지의 tr 만 tbody 에 붙인다 (2026-09-13, analyze 의 500행 표용).
 *                    나머지 행은 allRows 배열에만 존재하므로 `querySelectorAll('#tbody tr')` 로 전량을
 *                    훑는 코드는 grid.allRows 를 봐야 한다. 정렬은 배열만 바꾸고(DOM 재부착 없음),
 *                    렌더는 slice 를 DocumentFragment 로 한 번에 replaceChildren 한다 — display 토글
 *                    방식(기본)과 달리 안 보이는 행이 레이아웃·스타일 계산에 들어가지 않는다.
 *                    showRow 훅은 detach 에서 무시된다(행이 곧 DOM 에 있는 것이 표시).
 */
(function() {
    'use strict';

    function byId(id) { return document.getElementById(id); }

    window.TableGrid = {
        create: function(cfg) {
            var g = {
                allRows: [],
                filteredRows: [],
                pageSize: 20,
                currentPage: 1,
                sortKey: cfg.defaultSort ? cfg.defaultSort.key : null,
                sortDir: cfg.defaultSort ? (cfg.defaultSort.dir || 'asc') : 'asc',
                sortType: cfg.defaultSort ? (cfg.defaultSort.type || 'str') : 'str'
            };
            var attrPrefix = cfg.sortAttrPrefix || 'data-sort-';
            var pageSizes = cfg.pageSizes || [20, 30, 50, 100];
            var detach = !!cfg.detach;
            g.pageSize = cfg.defaultPageSize || 20;

            /* rows 미지정 시 tbody 의 전체 tr 수집. 행표시 셀렉트 localStorage 복원 + 기본 정렬. */
            g.init = function(rows) {
                g.allRows = rows || Array.prototype.slice.call(
                    document.querySelectorAll('#' + cfg.tbodyId + ' tr'));
                var saved = parseInt(localStorage.getItem(cfg.pageSizeKey) || String(g.pageSize), 10);
                if (pageSizes.indexOf(saved) !== -1) {
                    g.pageSize = saved;
                    var sel = byId(cfg.pageSizeSelectId || 'pageSizeSelect');
                    if (sel) sel.value = String(saved);
                }
                if (detach) {
                    var tb0 = byId(cfg.tbodyId);
                    if (tb0) tb0.replaceChildren();   // 행은 allRows 에만 — render() 가 페이지 슬라이스를 붙인다
                }
                g.sortRows();
                g.updateSortIndicators();
            };

            g.sortValue = function(el, key) {
                if (cfg.sortValue) {
                    var v = cfg.sortValue(el, key);
                    if (v !== null && v !== undefined) return v;
                }
                return el.getAttribute(attrPrefix + key) || '';
            };

            g.sortRows = function() {
                var dir = g.sortDir === 'asc' ? 1 : -1;
                var isNum = g.sortType === 'num';
                g.allRows.sort(function(a, b) {
                    var va = g.sortValue(a, g.sortKey);
                    var vb = g.sortValue(b, g.sortKey);
                    if (isNum) { va = parseFloat(va) || 0; vb = parseFloat(vb) || 0; return (va - vb) * dir; }
                    return va.localeCompare(vb, 'ko') * dir;
                });
                if (detach) return;   // 배열 순서가 곧 표시 순서 — DOM 재부착 불필요
                var tbody = byId(cfg.tbodyId);
                if (tbody) g.allRows.forEach(function(r) {
                    if (cfg.attachRow) cfg.attachRow(tbody, r);
                    else tbody.appendChild(r);
                });
            };

            g.onHeaderSort = function(th) {
                var key = th.getAttribute('data-sort-key');
                var type = th.getAttribute('data-sort-type') || 'str';
                if (g.sortKey === key) {
                    g.sortDir = (g.sortDir === 'asc') ? 'desc' : 'asc';
                } else {
                    g.sortKey = key;
                    g.sortDir = 'asc';
                }
                g.sortType = type;
                g.sortRows();
                g.updateSortIndicators();
                cfg.applyFilter();
            };

            g.updateSortIndicators = function() {
                document.querySelectorAll(cfg.headerSelector).forEach(function(th) {
                    var arrow = th.querySelector('.sort-arrow');
                    if (!arrow) return;
                    if (th.getAttribute('data-sort-key') === g.sortKey) {
                        th.classList.add('sort-active');
                        arrow.textContent = (g.sortDir === 'asc') ? '▲' : '▼';
                    } else {
                        th.classList.remove('sort-active');
                        arrow.textContent = '▲';
                    }
                });
            };

            /* 페이지 applyFilter 가 필터링 결과를 넘기는 진입점 — 1페이지로 리셋 후 렌더. */
            g.setFiltered = function(rows) {
                g.filteredRows = rows;
                g.currentPage = 1;
                g.render();
            };

            g.onPageSizeChange = function() {
                var sel = byId(cfg.pageSizeSelectId || 'pageSizeSelect');
                g.pageSize = parseInt(sel.value, 10) || (cfg.defaultPageSize || 20);
                if (pageSizes.indexOf(g.pageSize) === -1) g.pageSize = cfg.defaultPageSize || 20;
                localStorage.setItem(cfg.pageSizeKey, String(g.pageSize));
                g.currentPage = 1;
                g.render();
            };

            g.gotoPage = function(p) {
                var totalPages = Math.max(1, Math.ceil(g.filteredRows.length / g.pageSize));
                if (p < 1) p = 1; if (p > totalPages) p = totalPages;
                g.currentPage = p;
                g.render();
            };

            g.render = function() {
                if (cfg.onRenderStart) cfg.onRenderStart();
                if (!detach) g.allRows.forEach(function(el) { el.style.display = 'none'; });
                var total = g.filteredRows.length;
                var totalPages = Math.max(1, Math.ceil(total / g.pageSize));
                if (g.currentPage > totalPages) g.currentPage = totalPages;
                var start = (g.currentPage - 1) * g.pageSize;
                var end = Math.min(start + g.pageSize, total);
                if (detach) {
                    var frag = document.createDocumentFragment();
                    for (var d = start; d < end; d++) frag.appendChild(g.filteredRows[d]);
                    var tb = byId(cfg.tbodyId);
                    if (tb) tb.replaceChildren(frag);
                } else {
                    for (var i = start; i < end; i++) {
                        if (cfg.showRow) cfg.showRow(g.filteredRows[i]);
                        else g.filteredRows[i].style.display = '';
                    }
                }
                var noMatch = byId(cfg.noMatchId || 'noMatch');
                if (noMatch) noMatch.style.display = total === 0 ? 'block' : 'none';
                g.renderPagination(total, totalPages, start, end);
                if (cfg.onAfterRender) cfg.onAfterRender();
            };

            g.renderPagination = function(total, totalPages, start, end) {
                var bar = byId(cfg.paginationBarId || 'paginationBar');
                if (!bar) return;
                if (total <= g.pageSize) { bar.classList.remove('show'); return; }
                bar.classList.add('show');
                byId(cfg.pgInfoId || 'pgInfo').innerHTML =
                    '<strong>' + (total === 0 ? 0 : (start + 1)) + '–' + end + '</strong> / 전체 <strong>' + total + '</strong>건';

                var list = byId(cfg.pgListId || 'pgList');
                list.innerHTML = '';
                var addBtn = function(label, page, opts) {
                    opts = opts || {};
                    var b = document.createElement('button');
                    b.className = 'pg-btn' + (opts.active ? ' active' : '');
                    b.textContent = label;
                    if (opts.disabled) b.disabled = true;
                    else b.onclick = function() { g.gotoPage(page); };
                    list.appendChild(b);
                };
                var addEllipsis = function() {
                    var s = document.createElement('span');
                    s.className = 'pg-ellipsis'; s.textContent = '…';
                    list.appendChild(s);
                };

                addBtn('‹', g.currentPage - 1, { disabled: g.currentPage === 1 });

                // 슬라이딩 윈도우 (현재 페이지 ±2)
                var pages = new Set([1, totalPages, g.currentPage]);
                for (var d = 1; d <= 2; d++) {
                    if (g.currentPage - d >= 1) pages.add(g.currentPage - d);
                    if (g.currentPage + d <= totalPages) pages.add(g.currentPage + d);
                }
                var sorted = Array.from(pages).sort(function(a, b) { return a - b; });
                var prev = 0;
                sorted.forEach(function(p) {
                    if (p - prev > 1) addEllipsis();
                    addBtn(String(p), p, { active: p === g.currentPage });
                    prev = p;
                });

                addBtn('›', g.currentPage + 1, { disabled: g.currentPage === totalPages });
            };

            return g;
        }
    };
})();
