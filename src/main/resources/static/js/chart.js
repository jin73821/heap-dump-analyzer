/**
 * Chart.js Configuration - Heap Dump Analyzer (MAT CLI Edition)
 *
 * 이 파일은 analyze.html 내 Thymeleaf inline-script로 세팅된
 * usedMemory / freeMemory / classNames / memorySizes 변수를 사용합니다.
 */

document.addEventListener('DOMContentLoaded', function () {

    // ── 데이터 존재 여부 확인 ──────────────────────────────────
    const hasMemoryData  = typeof usedMemory  !== 'undefined' && typeof freeMemory !== 'undefined';
    const hasTopObjects  = typeof classNames  !== 'undefined' && typeof memorySizes !== 'undefined'
                           && classNames.length > 0;

    // ── 공통 색상 팔레트 ────────────────────────────────────────
    const BLUE_PALETTE = [
        '#3B82F6','#2563EB','#1D4ED8','#60A5FA','#93C5FD',
        '#06B6D4','#0891B2','#0E7490','#67E8F9','#A5F3FC'
    ];

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 1. Doughnut Chart — 메모리 사용/여유 비율
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    const doughnutCtx = document.getElementById('doughnutChart');

    if (doughnutCtx && hasMemoryData) {
        const usedMB = (usedMemory / (1024 * 1024)).toFixed(2);
        const freeMB = (freeMemory / (1024 * 1024)).toFixed(2);

        new Chart(doughnutCtx, {
            type: 'doughnut',
            data: {
                labels: ['Used Memory', 'Free Memory'],
                datasets: [{
                    data: [usedMemory, freeMemory],
                    backgroundColor: ['#3B82F6', '#D1D5DB'],
                    borderColor: '#FFFFFF',
                    borderWidth: 3,
                    hoverOffset: 12
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: true,
                cutout: '65%',
                plugins: {
                    legend: {
                        position: 'bottom',
                        labels: {
                            padding: 20,
                            font: { size: 13, family: "'Segoe UI', sans-serif" },
                            color: '#374151',
                            usePointStyle: true,
                            pointStyleWidth: 14
                        }
                    },
                    tooltip: {
                        backgroundColor: 'rgba(17,24,39,0.9)',
                        titleFont: { size: 13, weight: 'bold' },
                        bodyFont:  { size: 12 },
                        padding: 12,
                        cornerRadius: 8,
                        callbacks: {
                            label: function (ctx) {
                                const val   = ctx.parsed || 0;
                                const mb    = (val / (1024 * 1024)).toFixed(2);
                                const total = ctx.dataset.data.reduce((a, b) => a + b, 0);
                                const pct   = ((val / total) * 100).toFixed(1);
                                return `${ctx.label}: ${mb} MB (${pct}%)`;
                            }
                        }
                    },
                    // 가운데 총 사용량 표시
                    beforeDraw: undefined
                },
                animation: {
                    animateRotate: true,
                    animateScale: true,
                    duration: 900,
                    easing: 'easeInOutQuart'
                }
            },
            plugins: [{
                id: 'centerText',
                afterDraw(chart) {
                    const { ctx, chartArea: { width, height, left, top } } = chart;
                    const usedPct = (usedMemory / (usedMemory + freeMemory) * 100).toFixed(1);
                    const cx = left + width  / 2;
                    const cy = top  + height / 2;

                    ctx.save();
                    ctx.textAlign    = 'center';
                    ctx.textBaseline = 'middle';
                    ctx.fillStyle    = '#1F2937';
                    ctx.font         = `bold ${Math.min(width, height) * 0.13}px 'Segoe UI'`;
                    ctx.fillText(usedPct + '%', cx, cy - 8);

                    ctx.fillStyle = '#6B7280';
                    ctx.font      = `${Math.min(width, height) * 0.07}px 'Segoe UI'`;
                    ctx.fillText('Used', cx, cy + 14);
                    ctx.restore();
                }
            }]
        });
    } else if (doughnutCtx) {
        showNoData(doughnutCtx, 'No memory data from MAT report');
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 2. Horizontal Bar Chart — Top 10 메모리 객체
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    const barCtx = document.getElementById('barChart');

    if (barCtx && hasTopObjects) {
        // 클래스명이 너무 길면 말줄임 처리
        const truncate = (str, maxLen = 40) =>
            str.length > maxLen ? '…' + str.slice(-maxLen) : str;

        const labels  = classNames.map(n => truncate(n));
        const sizesMB = memorySizes.map(s => (s / (1024 * 1024)).toFixed(3));

        // 값 크기에 따라 색상 강도 변화
        const maxSize = Math.max(...memorySizes);
        const bgColors = memorySizes.map(s => {
            const ratio = s / maxSize;
            // 진한 파랑(1.0) → 연한 파랑(0.3)
            const alpha = 0.35 + ratio * 0.65;
            return `rgba(59,130,246,${alpha.toFixed(2)})`;
        });

        new Chart(barCtx, {
            type: 'bar',
            data: {
                labels: labels,
                datasets: [{
                    label: 'Memory (MB)',
                    data: sizesMB,
                    backgroundColor: bgColors,
                    borderColor: '#2563EB',
                    borderWidth: 1,
                    borderRadius: 4,
                    hoverBackgroundColor: '#2563EB'
                }]
            },
            options: {
                indexAxis: 'y',
                responsive: true,
                maintainAspectRatio: true,
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        backgroundColor: 'rgba(17,24,39,0.9)',
                        titleFont: { size: 12, weight: 'bold' },
                        bodyFont:  { size: 12 },
                        padding: 10,
                        cornerRadius: 6,
                        callbacks: {
                            title: (items) => classNames[items[0].dataIndex] || items[0].label,
                            label: (ctx)   => ` Memory: ${ctx.parsed.x} MB`
                        }
                    }
                },
                scales: {
                    x: {
                        beginAtZero: true,
                        grid:  { color: '#E5E7EB' },
                        ticks: {
                            color: '#6B7280',
                            font:  { size: 11, family: "'Segoe UI', sans-serif" },
                            callback: v => v + ' MB'
                        },
                        title: {
                            display: true, text: 'Memory Size (MB)',
                            color: '#374151',
                            font: { size: 12, weight: '600' }
                        }
                    },
                    y: {
                        grid:  { display: false },
                        ticks: {
                            color: '#1F2937',
                            font:  { size: 11, family: "'Courier New', monospace" },
                            autoSkip: false
                        }
                    }
                },
                animation: { duration: 1000, easing: 'easeInOutQuart' }
            }
        });
    } else if (barCtx) {
        showNoData(barCtx, 'No top-object data from MAT report');
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 3. 프로그레스 바 진입 애니메이션
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    const progressBars = document.querySelectorAll('.progress-bar');
    if (progressBars.length > 0) {
        setTimeout(() => {
            progressBars.forEach(bar => {
                const target = bar.style.width;
                bar.style.width = '0%';
                bar.style.transition = 'none';
                requestAnimationFrame(() => {
                    requestAnimationFrame(() => {
                        bar.style.transition = 'width 0.8s ease';
                        bar.style.width = target;
                    });
                });
            });
        }, 300);
    }

    // ── 헬퍼: 데이터 없을 때 캔버스에 메시지 표시 ──────────
    function showNoData(canvas, message) {
        const ctx2d = canvas.getContext('2d');
        const W = canvas.parentElement.clientWidth || 300;
        const H = 200;
        canvas.width  = W;
        canvas.height = H;
        ctx2d.fillStyle    = '#9CA3AF';
        ctx2d.font         = '14px Segoe UI';
        ctx2d.textAlign    = 'center';
        ctx2d.textBaseline = 'middle';
        ctx2d.fillText(message, W / 2, H / 2);
    }

});
