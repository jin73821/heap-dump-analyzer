/* select-mode.js — 데이터 그리드 다중 선택 모드 공통 모듈 (2026-08-02)
 *
 * files / history / comparison-history 에 3벌 복붙돼 있던 선택 토글 / 전체선택 /
 * 선택 카운트 / 하단 액션바 로직 통합. 페이지는 얇은 전역 위임 함수
 * (toggleSelectMode/exitSelectMode/updateSelectionCount/onRowCheck/onSelectAll)를
 * 유지해 HTML inline 핸들러를 보존한다.
 *
 * cfg:
 *   tableId    (필수) 대상 테이블 id — .select-mode 클래스 토글
 *   tbodyId    (필수) 행 tbody id — 가시 행(.row-check)만 집계
 *   bodyClass  (필수) body 에 토글할 클래스 (files: 'has-select-bar', 그 외: 'action-bar-open')
 *   onCountChange(selectedChecks)  카운트 갱신 훅 (files 의 다운로드 버튼 활성화 등)
 *
 * 고정 id 규약: selectModeBtn / selectModeBtnLabel / selectActionBar / saCount / saDelBtn / allCheck
 */
(function() {
    'use strict';

    function byId(id) { return document.getElementById(id); }

    window.SelectMode = {
        create: function(cfg) {
            var m = { active: false };

            /* 가시 행의 .row-check 목록 (페이지/필터/검색 가시 행만) */
            m.visibleChecks = function() {
                var out = [];
                document.querySelectorAll('#' + cfg.tbodyId + ' tr').forEach(function(tr) {
                    if (tr.style.display === 'none') return;
                    var c = tr.querySelector('.row-check');
                    if (c) out.push(c);
                });
                return out;
            };

            m.selectedChecks = function() {
                return m.visibleChecks().filter(function(c) { return c.checked; });
            };

            m.toggle = function() {
                m.active = !m.active;
                var table = byId(cfg.tableId);
                if (!table) return;
                if (m.active) {
                    table.classList.add('select-mode');
                    byId('selectModeBtn').classList.add('active');
                    byId('selectModeBtnLabel').textContent = '선택 종료';
                    byId('selectActionBar').classList.add('show');
                    document.body.classList.add(cfg.bodyClass);
                    m.updateCount();
                } else {
                    m.exit();
                }
            };

            m.exit = function() {
                m.active = false;
                var table = byId(cfg.tableId);
                if (table) table.classList.remove('select-mode');
                byId('selectModeBtn').classList.remove('active');
                byId('selectModeBtnLabel').textContent = '선택';
                byId('selectActionBar').classList.remove('show');
                document.body.classList.remove(cfg.bodyClass);
                document.querySelectorAll('.row-check').forEach(function(c) { c.checked = false; });
                var all = byId('allCheck');
                if (all) { all.checked = false; all.indeterminate = false; }
            };

            m.updateCount = function() {
                var sel = m.selectedChecks();
                var n = sel.length;
                byId('saCount').textContent = n;
                byId('saDelBtn').disabled = (n === 0);
                var visible = m.visibleChecks();
                var all = byId('allCheck');
                if (all) {
                    var checkedCount = visible.filter(function(c) { return c.checked; }).length;
                    all.checked = (visible.length > 0 && checkedCount === visible.length);
                    all.indeterminate = (checkedCount > 0 && checkedCount < visible.length);
                }
                if (cfg.onCountChange) cfg.onCountChange(sel);
            };

            m.onSelectAll = function() {
                var checked = byId('allCheck').checked;
                m.visibleChecks().forEach(function(c) { c.checked = checked; });
                m.updateCount();
            };

            return m;
        }
    };
})();
