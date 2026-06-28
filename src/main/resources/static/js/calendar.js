/*
 * KRDS 스타일 커스텀 캘린더 위젯 — 단일 달력 기간(범위) 선택 공통 라이브러리
 *
 * 하나의 달력에서 시작일·종료일을 모두 선택한다 (KRDS Date Range 패턴).
 *   - 첫 클릭          : 시작일 지정 (종료일 초기화)
 *   - 둘째 클릭        : 시작일 이후면 종료일, 이전이면 시작일 재지정
 *   - 둘 다 지정 후 클릭: 새 시작일로 초기화
 *   - hover           : 시작일만 지정된 상태에서 마우스 오버 시 범위 미리보기
 *   - 확인            : 선택을 확정하고 onChange 호출 / 취소·바깥클릭: draft 폐기
 *
 * 사용법:
 *   Calendar.attach({
 *       startInputId: 'dateStart',
 *       endInputId:   'dateEnd',
 *       areaId:       'calArea',       // 단일 달력 팝업 영역 (생략 시 'calArea')
 *       storageKey:   'files',         // localStorage: <prefix>PeriodStart / <prefix>PeriodEnd
 *       onChange:     function(start, end) { ... }   // 확인/지우기 시 호출
 *   });
 *
 * Calendar.getRange() → { start: Date|null, end: Date|null }
 * Calendar.open()      → 달력 토글
 * Calendar.clear()     → 두 입력 비우고 onChange 호출
 *
 * 페이지당 1개의 캘린더 인스턴스만 지원 (한 페이지에 기간 한 쌍).
 */
(function() {
    var _opts = null;
    var _c = { start: null, end: null };   // committed (확정값)
    var _d = { start: null, end: null };   // draft (달력 내 임시 선택)
    var _view = { year: 0, month: 0 };
    var _hover = null;                      // 범위 미리보기용 hover 날짜

    function pad2(n) { return String(n).padStart(2, '0'); }
    function toISO(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
    function toDisplay(d) { return d.getFullYear() + '.' + pad2(d.getMonth() + 1) + '.' + pad2(d.getDate()); }
    function fromISO(s) {
        var p = s.split('-');
        return new Date(parseInt(p[0], 10), parseInt(p[1], 10) - 1, parseInt(p[2], 10));
    }
    function sameDay(a, b) {
        return a && b && a.getFullYear() === b.getFullYear()
            && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
    }
    // 날짜만 비교 (시각 무시): a<b → 음수, a==b → 0, a>b → 양수
    function cmp(a, b) {
        return new Date(a.getFullYear(), a.getMonth(), a.getDate()).getTime()
             - new Date(b.getFullYear(), b.getMonth(), b.getDate()).getTime();
    }

    function startEl() { return document.getElementById(_opts.startInputId); }
    function endEl()   { return document.getElementById(_opts.endInputId); }
    function areaEl()  { return document.getElementById(_opts.areaId || 'calArea'); }
    function rangeBox() { var a = areaEl(); return a ? a.closest('.calendar-range') : null; }
    function storageKey(side) { return _opts.storageKey + 'Period' + (side === 'start' ? 'Start' : 'End'); }

    function renderInputs(s, e) {
        var si = startEl(), ei = endEl();
        if (si) si.value = s ? toDisplay(s) : '';
        if (ei) ei.value = e ? toDisplay(e) : '';
    }

    function validate() {
        var invalid = !!(_c.start && _c.end && cmp(_c.start, _c.end) > 0);
        var box = rangeBox();
        if (box) box.classList.toggle('invalid', invalid);
    }

    function notifyChange() {
        validate();
        if (typeof _opts.onChange === 'function') _opts.onChange(_c.start, _c.end);
    }

    function open() {
        var area = areaEl();
        if (!area) return;
        if (area.classList.contains('open')) { close(); return; }
        // 확정값을 draft 로 복사 후 편집 시작
        _d.start = _c.start ? new Date(_c.start) : null;
        _d.end   = _c.end ? new Date(_c.end) : null;
        _hover = null;
        var base = _d.start || new Date();
        _view.year = base.getFullYear();
        _view.month = base.getMonth();
        render();
        area.classList.add('open');
        clampToViewport(area);
    }

    // 팝업이 뷰포트 우측을 벗어나면 좌측으로 이동 보정 (모바일 등).
    function clampToViewport(area) {
        area.style.left = '0px';
        var rect = area.getBoundingClientRect();
        var vw = document.documentElement.clientWidth;
        var overflowR = rect.right - (vw - 8);
        if (overflowR > 0) {
            var shift = Math.min(overflowR, rect.left - 4);
            if (shift > 0) area.style.left = (-shift) + 'px';
        }
    }

    // 달력 닫기 — draft 폐기, 입력 표시를 확정값으로 되돌림
    function close() {
        var area = areaEl();
        if (area) { area.classList.remove('open'); area.style.left = ''; }
        _hover = null;
        renderInputs(_c.start, _c.end);
    }

    // 날짜 클릭 → draft 갱신 (라이브 미리보기)
    function pickDate(d) {
        if (!_d.start || (_d.start && _d.end)) {
            _d.start = d; _d.end = null;
        } else if (cmp(d, _d.start) < 0) {
            _d.start = d;
        } else {
            _d.end = d;
        }
        _hover = null;
        renderInputs(_d.start, _d.end);
        render();
    }

    // draft + hover 기준으로 [lo, hi] 범위 산출
    function effectiveRange() {
        var lo = _d.start, hi = _d.end;
        if (lo && !hi && _hover) {
            if (cmp(_hover, lo) >= 0) hi = _hover;
            else { hi = lo; lo = _hover; }
        }
        return { lo: lo, hi: hi };
    }

    // 셀에 범위 강조 클래스만 다시 칠함 (hover 시 전체 재렌더 없이 호출)
    function paintRange() {
        var area = areaEl();
        if (!area) return;
        var r = effectiveRange(), lo = r.lo, hi = r.hi;
        area.querySelectorAll('.calendar-tbl td').forEach(function(td) {
            td.classList.remove('period', 'start', 'end');
            var btn = td.querySelector('.btn-set-date');
            if (!btn) return;
            var d = fromISO(btn.dataset.iso);
            if (lo && hi) {
                var isLo = sameDay(d, lo), isHi = sameDay(d, hi);
                if (isLo || isHi) {
                    td.classList.add('period');
                    if (isLo) td.classList.add('start');
                    if (isHi) td.classList.add('end');
                } else if (cmp(d, lo) > 0 && cmp(d, hi) < 0) {
                    td.classList.add('period');
                }
            } else if (lo && sameDay(d, lo)) {
                td.classList.add('period', 'start', 'end');
            }
        });
    }

    function render() {
        var year = _view.year, month = _view.month;
        var area = areaEl();
        var today = new Date(); today.setHours(0, 0, 0, 0);
        var firstDay = new Date(year, month, 1);
        var lastDay = new Date(year, month + 1, 0);
        var startWeekday = firstDay.getDay();
        var daysInMonth = lastDay.getDate();
        var prevLastDay = new Date(year, month, 0).getDate();

        var nowY = new Date().getFullYear();
        var yearStart = nowY - 20, yearEnd = nowY + 1;
        var yearOpts = '';
        for (var y = yearStart; y <= yearEnd; y++) {
            yearOpts += '<li><button type="button" class="' + (y === year ? 'active' : '') +
                        '" data-y="' + y + '">' + y + '년</button></li>';
        }
        var monthOpts = '';
        for (var m = 0; m < 12; m++) {
            monthOpts += '<li><button type="button" class="' + (m === month ? 'active' : '') +
                         '" data-m="' + m + '">' + pad2(m + 1) + '월</button></li>';
        }

        var rows = '';
        var dayCounter = 1 - startWeekday;
        for (var rIdx = 0; rIdx < 6; rIdx++) {
            var cells = '';
            for (var c = 0; c < 7; c++, dayCounter++) {
                var cellDate, cellClass = '', dayNum;
                if (dayCounter < 1) {
                    dayNum = prevLastDay + dayCounter;
                    cellDate = new Date(year, month - 1, dayNum);
                    cellClass = 'old';
                } else if (dayCounter > daysInMonth) {
                    dayNum = dayCounter - daysInMonth;
                    cellDate = new Date(year, month + 1, dayNum);
                    cellClass = 'new';
                } else {
                    dayNum = dayCounter;
                    cellDate = new Date(year, month, dayNum);
                }
                if (c === 0) cellClass += (cellClass ? ' ' : '') + 'day-off';
                if (cellDate.getTime() === today.getTime()) cellClass += ' today';

                cells += '<td class="' + cellClass + '">' +
                         '<button type="button" class="btn-set-date" data-iso="' + toISO(cellDate) + '">' +
                         '<span>' + dayNum + '</span></button></td>';
            }
            rows += '<tr>' + cells + '</tr>';
            if (dayCounter > daysInMonth) break;
        }

        area.innerHTML =
            '<div class="calendar-wrap bottom range" aria-label="기간 선택 달력">' +
                '<div class="calendar-head">' +
                    '<button type="button" class="btn-cal-move prev" data-act="prev"><span class="sr-only">이전 달</span></button>' +
                    '<div class="calendar-switch-wrap">' +
                        '<div class="calendar-drop-down">' +
                            '<button type="button" class="btn-cal-switch year" data-toggle="dd" aria-label="연도 선택">' + year + '년</button>' +
                            '<div class="calendar-select calendar-year-wrap"><ul class="sel year">' + yearOpts + '</ul></div>' +
                        '</div>' +
                        '<div class="calendar-drop-down">' +
                            '<button type="button" class="btn-cal-switch month" data-toggle="dd" aria-label="월 선택">' + pad2(month + 1) + '월</button>' +
                            '<div class="calendar-select calendar-mon-wrap"><ul class="sel month">' + monthOpts + '</ul></div>' +
                        '</div>' +
                    '</div>' +
                    '<button type="button" class="btn-cal-move next" data-act="next"><span class="sr-only">다음 달</span></button>' +
                '</div>' +
                '<div class="calendar-body"><div class="calendar-table-wrap"><table class="calendar-tbl">' +
                    '<caption>' + year + '년 ' + (month + 1) + '월</caption>' +
                    '<thead><tr><th>일</th><th>월</th><th>화</th><th>수</th><th>목</th><th>금</th><th>토</th></tr></thead>' +
                    '<tbody>' + rows + '</tbody>' +
                '</table></div></div>' +
                '<div class="calendar-selinfo">' +
                    '<span class="seg ' + (_d.start ? 'on' : '') + '">' + (_d.start ? toDisplay(_d.start) : '시작일') + '</span>' +
                    '<span class="sep">~</span>' +
                    '<span class="seg ' + (_d.end ? 'on' : '') + '">' + (_d.end ? toDisplay(_d.end) : '종료일') + '</span>' +
                '</div>' +
                '<div class="calendar-footer"><div class="calendar-btn-wrap">' +
                    '<button type="button" class="krds-btn small text" data-act="today">오늘</button>' +
                    '<button type="button" class="krds-btn small tertiary" data-act="cancel">취소</button>' +
                    '<button type="button" class="krds-btn small primary" data-act="confirm">확인</button>' +
                '</div></div>' +
            '</div>';

        paintRange();
        bindEvents(area);

        var actY = area.querySelector('.calendar-year-wrap .active');
        if (actY) {
            var ul = actY.parentNode.parentNode;
            ul.parentNode.scrollTop = Math.max(0, actY.offsetTop - 60);
        }
    }

    function bindEvents(area) {
        area.querySelectorAll('.btn-cal-move').forEach(function(b) {
            b.addEventListener('click', function(ev) {
                ev.stopPropagation();
                var delta = (b.dataset.act === 'prev') ? -1 : 1;
                _view.month += delta;
                if (_view.month < 0) { _view.month = 11; _view.year--; }
                else if (_view.month > 11) { _view.month = 0; _view.year++; }
                render();
            });
        });
        area.querySelectorAll('.btn-cal-switch').forEach(function(b) {
            b.addEventListener('click', function(ev) {
                ev.stopPropagation();
                var dd = b.nextElementSibling;
                var isOpen = dd.classList.contains('open');
                area.querySelectorAll('.calendar-select.open').forEach(function(s) { s.classList.remove('open'); });
                if (!isOpen) dd.classList.add('open');
            });
        });
        area.querySelectorAll('.sel.year button').forEach(function(b) {
            b.addEventListener('click', function(ev) {
                ev.stopPropagation();
                _view.year = parseInt(b.dataset.y, 10);
                render();
            });
        });
        area.querySelectorAll('.sel.month button').forEach(function(b) {
            b.addEventListener('click', function(ev) {
                ev.stopPropagation();
                _view.month = parseInt(b.dataset.m, 10);
                render();
            });
        });
        area.querySelectorAll('.btn-set-date').forEach(function(b) {
            b.addEventListener('click', function(ev) {
                ev.stopPropagation();
                pickDate(fromISO(b.dataset.iso));
            });
            b.addEventListener('mouseenter', function() {
                if (_d.start && !_d.end) { _hover = fromISO(b.dataset.iso); paintRange(); }
            });
        });
        var tbody = area.querySelector('.calendar-tbl tbody');
        if (tbody) {
            tbody.addEventListener('mouseleave', function() {
                if (_hover) { _hover = null; paintRange(); }
            });
        }
        area.querySelectorAll('.calendar-btn-wrap .krds-btn').forEach(function(b) {
            b.addEventListener('click', function(ev) {
                ev.stopPropagation();
                var act = b.dataset.act;
                if (act === 'today') {
                    // 오늘 날짜를 바로 선택(클릭과 동일 규칙) + 화면을 이번 달로 이동
                    var t = new Date(); t.setHours(0, 0, 0, 0);
                    _view.year = t.getFullYear();
                    _view.month = t.getMonth();
                    pickDate(t);
                } else if (act === 'cancel') {
                    close();
                } else if (act === 'confirm') {
                    confirmSelection();
                }
            });
        });
    }

    function confirmSelection() {
        // 종료일 없이 시작일만 고른 경우 단일 일자(시작=종료)로 확정
        _c.start = _d.start ? new Date(_d.start) : null;
        _c.end   = _d.end ? new Date(_d.end) : (_d.start ? new Date(_d.start) : null);
        saveStorage();
        renderInputs(_c.start, _c.end);
        var area = areaEl();
        if (area) { area.classList.remove('open'); area.style.left = ''; }
        _hover = null;
        notifyChange();
    }

    function saveStorage() {
        if (_c.start) localStorage.setItem(storageKey('start'), toISO(_c.start));
        else localStorage.removeItem(storageKey('start'));
        if (_c.end) localStorage.setItem(storageKey('end'), toISO(_c.end));
        else localStorage.removeItem(storageKey('end'));
    }

    function clearAll() {
        _c.start = _c.end = null;
        _d.start = _d.end = null;
        _hover = null;
        renderInputs(null, null);
        var box = rangeBox();
        if (box) box.classList.remove('invalid');
        localStorage.removeItem(storageKey('start'));
        localStorage.removeItem(storageKey('end'));
        var area = areaEl();
        if (area) { area.classList.remove('open'); area.style.left = ''; }
        notifyChange();
    }

    function restoreFromStorage() {
        var sIso = localStorage.getItem(storageKey('start'));
        var eIso = localStorage.getItem(storageKey('end'));
        if (sIso) _c.start = fromISO(sIso);
        if (eIso) _c.end = fromISO(eIso);
        renderInputs(_c.start, _c.end);
        validate();
    }

    function attach(opts) {
        _opts = opts || {};
        _c = { start: null, end: null };
        _d = { start: null, end: null };
        _hover = null;
        var si = startEl(), ei = endEl();
        if (si) si.addEventListener('click', open);
        if (ei) ei.addEventListener('click', open);

        restoreFromStorage();

        // 페이지 1개 인스턴스 — document-level 핸들러도 1회만
        if (!attach._docBound) {
            document.addEventListener('click', function(ev) {
                if (ev.target.closest('.calendar-range')) return;
                close();
            });
            document.addEventListener('keydown', function(ev) {
                if (ev.key === 'Escape') close();
            });
            attach._docBound = true;
        }
    }

    function getRange() {
        return { start: _c.start, end: _c.end };
    }

    window.Calendar = {
        attach: attach,
        open: open,
        close: close,
        clear: clearAll,
        getRange: getRange
    };
})();
