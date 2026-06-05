/*
 * KRDS 스타일 커스텀 캘린더 위젯 — 기간(범위) 선택용 공통 라이브러리
 *
 * 사용법:
 *   Calendar.attach({
 *       startInputId: 'dateStart',
 *       endInputId:   'dateEnd',
 *       startAreaId:  'calArea-start',
 *       endAreaId:    'calArea-end',
 *       storageKey:   'files',           // localStorage prefix: <prefix>PeriodStart / <prefix>PeriodEnd
 *       onChange:     function(start, end) { ... }
 *   });
 *
 * Calendar.getRange()  → { start: Date|null, end: Date|null }
 * Calendar.clear()     → 두 입력 모두 비우고 onChange 호출
 *
 * 페이지당 1개의 캘린더 인스턴스만 지원 (한 페이지에 시작/종료 한 쌍).
 */
(function() {
    var _opts = null;
    var _state = {
        start: { value: null, viewYear: 0, viewMonth: 0 },
        end:   { value: null, viewYear: 0, viewMonth: 0 }
    };

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
    function storageKey(side) { return _opts.storageKey + 'Period' + (side === 'start' ? 'Start' : 'End'); }
    function inputEl(side) { return document.getElementById(side === 'start' ? _opts.startInputId : _opts.endInputId); }
    function areaEl(side) { return document.getElementById(side === 'start' ? _opts.startAreaId : _opts.endAreaId); }

    function notifyChange() {
        validateRange();
        if (typeof _opts.onChange === 'function') {
            _opts.onChange(_state.start.value, _state.end.value);
        }
    }

    function validateRange() {
        var s = _state.start.value, e = _state.end.value;
        var sIn = inputEl('start'), eIn = inputEl('end');
        var invalid = !!(s && e && s.getTime() > e.getTime());
        if (sIn) sIn.classList.toggle('invalid', invalid);
        if (eIn) eIn.classList.toggle('invalid', invalid);
    }

    function openCalendar(side) {
        var other = (side === 'start') ? 'end' : 'start';
        closeCalendar(other);
        var area = areaEl(side);
        if (!area) return;
        if (area.classList.contains('open')) { closeCalendar(side); return; }
        var current = _state[side].value || new Date();
        _state[side].viewYear = current.getFullYear();
        _state[side].viewMonth = current.getMonth();
        renderCalendar(side);
        area.classList.add('open');
        clampToViewport(area);
    }
    // 팝업이 뷰포트 우측을 벗어나면 좌측으로 이동 보정 (모바일 종료일 달력 등).
    // 우측 8px 여유 확보, 좌측은 최소 4px 까지만 이동 — 초소형 화면에서도 양쪽 잘림 최소화.
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
    function closeCalendar(side) {
        var area = areaEl(side);
        if (area) { area.classList.remove('open'); area.style.left = ''; }
    }
    function closeAll() { closeCalendar('start'); closeCalendar('end'); }

    function renderCalendar(side) {
        var state = _state[side];
        var year = state.viewYear, month = state.viewMonth;
        var area = areaEl(side);
        var selected = state.value;
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
        for (var r = 0; r < 6; r++) {
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
                if (sameDay(cellDate, selected)) cellClass += ' period start end';

                cells += '<td class="' + cellClass + '">' +
                         '<button type="button" class="btn-set-date" data-iso="' + toISO(cellDate) + '">' +
                         '<span>' + dayNum + '</span></button></td>';
            }
            rows += '<tr>' + cells + '</tr>';
            if (dayCounter > daysInMonth) break;
        }

        area.innerHTML =
            '<div class="calendar-wrap bottom single" aria-label="달력">' +
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
                '<div class="calendar-footer"><div class="calendar-btn-wrap">' +
                    '<button type="button" class="krds-btn small text" data-act="today">오늘</button>' +
                    '<button type="button" class="krds-btn small tertiary" data-act="cancel">취소</button>' +
                    '<button type="button" class="krds-btn small primary" data-act="confirm">확인</button>' +
                '</div></div>' +
            '</div>';

        bindEvents(side, area);

        var actY = area.querySelector('.calendar-year-wrap .active');
        if (actY) {
            var ul = actY.parentNode.parentNode;
            ul.parentNode.scrollTop = Math.max(0, actY.offsetTop - 60);
        }
    }

    function bindEvents(side, area) {
        var state = _state[side];

        area.querySelectorAll('.btn-cal-move').forEach(function(b) {
            b.addEventListener('click', function(ev) {
                ev.stopPropagation();
                var delta = (b.dataset.act === 'prev') ? -1 : 1;
                state.viewMonth += delta;
                if (state.viewMonth < 0) { state.viewMonth = 11; state.viewYear--; }
                else if (state.viewMonth > 11) { state.viewMonth = 0; state.viewYear++; }
                renderCalendar(side);
            });
        });
        area.querySelectorAll('.btn-cal-switch').forEach(function(b) {
            b.addEventListener('click', function(ev) {
                ev.stopPropagation();
                var dd = b.nextElementSibling;
                var open = dd.classList.contains('open');
                area.querySelectorAll('.calendar-select.open').forEach(function(s) { s.classList.remove('open'); });
                if (!open) dd.classList.add('open');
            });
        });
        area.querySelectorAll('.sel.year button').forEach(function(b) {
            b.addEventListener('click', function(ev) {
                ev.stopPropagation();
                state.viewYear = parseInt(b.dataset.y, 10);
                renderCalendar(side);
            });
        });
        area.querySelectorAll('.sel.month button').forEach(function(b) {
            b.addEventListener('click', function(ev) {
                ev.stopPropagation();
                state.viewMonth = parseInt(b.dataset.m, 10);
                renderCalendar(side);
            });
        });
        area.querySelectorAll('.btn-set-date').forEach(function(b) {
            b.addEventListener('click', function(ev) {
                ev.stopPropagation();
                var d = fromISO(b.dataset.iso);
                state.value = d;
                state.viewYear = d.getFullYear();
                state.viewMonth = d.getMonth();
                renderCalendar(side);
            });
        });
        area.querySelectorAll('.calendar-btn-wrap .krds-btn').forEach(function(b) {
            b.addEventListener('click', function(ev) {
                ev.stopPropagation();
                var act = b.dataset.act;
                if (act === 'today') {
                    var t = new Date(); t.setHours(0, 0, 0, 0);
                    state.value = t; state.viewYear = t.getFullYear(); state.viewMonth = t.getMonth();
                    renderCalendar(side);
                } else if (act === 'cancel') {
                    closeCalendar(side);
                } else if (act === 'confirm') {
                    confirmSide(side);
                }
            });
        });
    }

    function confirmSide(side) {
        var d = _state[side].value;
        var input = inputEl(side);
        var key = storageKey(side);
        if (d) {
            input.value = toDisplay(d);
            localStorage.setItem(key, toISO(d));
        } else {
            input.value = '';
            localStorage.removeItem(key);
        }
        closeCalendar(side);
        notifyChange();
    }

    function clearAll() {
        _state.start.value = null;
        _state.end.value = null;
        var sIn = inputEl('start'), eIn = inputEl('end');
        if (sIn) { sIn.value = ''; sIn.classList.remove('invalid'); }
        if (eIn) { eIn.value = ''; eIn.classList.remove('invalid'); }
        localStorage.removeItem(storageKey('start'));
        localStorage.removeItem(storageKey('end'));
        closeAll();
        notifyChange();
    }

    function restoreFromStorage() {
        var sIso = localStorage.getItem(storageKey('start'));
        var eIso = localStorage.getItem(storageKey('end'));
        if (sIso) {
            _state.start.value = fromISO(sIso);
            var sIn = inputEl('start');
            if (sIn) sIn.value = toDisplay(_state.start.value);
        }
        if (eIso) {
            _state.end.value = fromISO(eIso);
            var eIn = inputEl('end');
            if (eIn) eIn.value = toDisplay(_state.end.value);
        }
        validateRange();
    }

    function attach(opts) {
        _opts = opts || {};
        _state = {
            start: { value: null, viewYear: 0, viewMonth: 0 },
            end:   { value: null, viewYear: 0, viewMonth: 0 }
        };
        var sIn = inputEl('start'), eIn = inputEl('end');
        if (sIn) {
            sIn.addEventListener('click', function() { openCalendar('start'); });
        }
        if (eIn) {
            eIn.addEventListener('click', function() { openCalendar('end'); });
        }
        // 외부에서 .calendar-input 안의 버튼이 onclick="Calendar.open('start')" 식으로 호출 가능

        restoreFromStorage();

        // 페이지 1개 인스턴스 — document-level 핸들러도 1회만
        if (!attach._docBound) {
            document.addEventListener('click', function(ev) {
                if (ev.target.closest('.calendar-input') || ev.target.closest('.krds-calendar-area')) return;
                closeAll();
            });
            document.addEventListener('keydown', function(ev) {
                if (ev.key === 'Escape') closeAll();
            });
            attach._docBound = true;
        }
    }

    function getRange() {
        return { start: _state.start.value, end: _state.end.value };
    }

    window.Calendar = {
        attach: attach,
        open: openCalendar,
        close: closeCalendar,
        clear: clearAll,
        getRange: getRange
    };
})();
