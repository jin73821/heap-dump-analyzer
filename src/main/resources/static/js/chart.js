/**
 * Chart.js Configuration for Heap Dump Analyzer
 */

document.addEventListener('DOMContentLoaded', function() {
    
    // Check if chart data is available
    if (typeof usedMemory === 'undefined' || typeof freeMemory === 'undefined') {
        console.error('Chart data not available');
        return;
    }
    
    // ===== Doughnut Chart Configuration =====
    const doughnutCtx = document.getElementById('doughnutChart');
    
    if (doughnutCtx) {
        // Convert bytes to MB for better readability
        const usedMemoryMB = (usedMemory / (1024 * 1024)).toFixed(2);
        const freeMemoryMB = (freeMemory / (1024 * 1024)).toFixed(2);
        
        new Chart(doughnutCtx, {
            type: 'doughnut',
            data: {
                labels: ['Used Memory', 'Free Memory'],
                datasets: [{
                    data: [usedMemory, freeMemory],
                    backgroundColor: [
                        '#3B82F6',  // Blue for used memory
                        '#D1D5DB'   // Gray for free memory
                    ],
                    borderColor: '#FFFFFF',
                    borderWidth: 2,
                    hoverOffset: 10
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: true,
                plugins: {
                    legend: {
                        position: 'bottom',
                        labels: {
                            padding: 20,
                            font: {
                                size: 14,
                                family: "'Segoe UI', sans-serif"
                            },
                            color: '#333333',
                            usePointStyle: true
                        }
                    },
                    tooltip: {
                        backgroundColor: 'rgba(0, 0, 0, 0.8)',
                        titleFont: {
                            size: 14,
                            weight: 'bold'
                        },
                        bodyFont: {
                            size: 13
                        },
                        padding: 12,
                        cornerRadius: 6,
                        callbacks: {
                            label: function(context) {
                                const label = context.label || '';
                                const value = context.parsed || 0;
                                const valueMB = (value / (1024 * 1024)).toFixed(2);
                                const total = context.dataset.data.reduce((a, b) => a + b, 0);
                                const percentage = ((value / total) * 100).toFixed(2);
                                return label + ': ' + valueMB + ' MB (' + percentage + '%)';
                            }
                        }
                    }
                },
                animation: {
                    animateRotate: true,
                    animateScale: true,
                    duration: 1000,
                    easing: 'easeInOutQuart'
                }
            }
        });
    }
    
    // ===== Bar Chart Configuration =====
    const barCtx = document.getElementById('barChart');
    
    if (barCtx && typeof classNames !== 'undefined' && typeof memorySizes !== 'undefined') {
        // Convert bytes to MB for better readability
        const memorySizesMB = memorySizes.map(size => (size / (1024 * 1024)).toFixed(2));
        
        new Chart(barCtx, {
            type: 'bar',
            data: {
                labels: classNames,
                datasets: [{
                    label: 'Memory Size (MB)',
                    data: memorySizesMB,
                    backgroundColor: '#3B82F6',
                    borderColor: '#2563EB',
                    borderWidth: 1,
                    borderRadius: 4,
                    hoverBackgroundColor: '#2563EB'
                }]
            },
            options: {
                indexAxis: 'y',  // Horizontal bar chart
                responsive: true,
                maintainAspectRatio: true,
                plugins: {
                    legend: {
                        display: false
                    },
                    tooltip: {
                        backgroundColor: 'rgba(0, 0, 0, 0.8)',
                        titleFont: {
                            size: 14,
                            weight: 'bold'
                        },
                        bodyFont: {
                            size: 13
                        },
                        padding: 12,
                        cornerRadius: 6,
                        callbacks: {
                            label: function(context) {
                                return 'Memory: ' + context.parsed.x + ' MB';
                            }
                        }
                    }
                },
                scales: {
                    x: {
                        beginAtZero: true,
                        grid: {
                            color: '#E5E7EB',
                            borderColor: '#D1D5DB'
                        },
                        ticks: {
                            font: {
                                size: 12,
                                family: "'Segoe UI', sans-serif"
                            },
                            color: '#6B7280',
                            callback: function(value) {
                                return value + ' MB';
                            }
                        },
                        title: {
                            display: true,
                            text: 'Memory Size (MB)',
                            font: {
                                size: 13,
                                weight: 'bold'
                            },
                            color: '#333333'
                        }
                    },
                    y: {
                        grid: {
                            display: false
                        },
                        ticks: {
                            font: {
                                size: 11,
                                family: "'Courier New', monospace"
                            },
                            color: '#333333',
                            autoSkip: false
                        }
                    }
                },
                animation: {
                    duration: 1200,
                    easing: 'easeInOutQuart'
                }
            }
        });
    }
    
    // Add animation to progress bars
    const progressBars = document.querySelectorAll('.progress-bar');
    
    if (progressBars.length > 0) {
        setTimeout(function() {
            progressBars.forEach(function(bar) {
                const targetWidth = bar.style.width;
                bar.style.width = '0%';
                setTimeout(function() {
                    bar.style.width = targetWidth;
                }, 100);
            });
        }, 500);
    }
    
});
