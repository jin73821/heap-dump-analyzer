/**
 * Main JavaScript — Heap Dump Analyzer (MAT CLI Edition)
 */

document.addEventListener('DOMContentLoaded', function () {

    // ── 파일 선택 → 파일명 표시 ──────────────────────────────
    const fileInput    = document.getElementById('fileInput');
    const fileNameSpan = document.getElementById('fileName');

    if (fileInput && fileNameSpan) {
        fileInput.addEventListener('change', function (e) {
            const file = e.target.files[0];
            if (file) {
                const lower = file.name.toLowerCase();
                const validExts = ['.hprof', '.bin', '.dump', '.hprof.gz', '.bin.gz', '.dump.gz'];
                if (!validExts.some(ext => lower.endsWith(ext))) {
                    const ext = file.name.split('.').pop().toLowerCase();
                    fileNameSpan.textContent = 'Choose a file or drag it here';
                    fileInput.value = '';
                    showToast('Unsupported file type: .' + ext + '. Allowed: .hprof, .bin, .dump (+ .gz)', 'error');
                    return;
                }
                fileNameSpan.textContent = file.name;
            } else {
                fileNameSpan.textContent = 'Choose a file or drag it here';
            }
        });
    }

    // ── 드래그 앤 드롭 ───────────────────────────────────────
    const uploadBox = document.querySelector('.upload-box');

    if (uploadBox) {
        uploadBox.addEventListener('dragover', function (e) {
            e.preventDefault();
            uploadBox.style.backgroundColor = '#EFF6FF';
            uploadBox.style.borderColor     = '#2563EB';
        });

        uploadBox.addEventListener('dragleave', function (e) {
            e.preventDefault();
            uploadBox.style.backgroundColor = '#F9FAFB';
            uploadBox.style.borderColor     = '#3B82F6';
        });

        uploadBox.addEventListener('drop', function (e) {
            e.preventDefault();
            uploadBox.style.backgroundColor = '#F9FAFB';
            uploadBox.style.borderColor     = '#3B82F6';

            const files = e.dataTransfer.files;
            if (!files.length) return;

            const file       = files[0];
            const lower      = file.name.toLowerCase();
            const validExts  = ['.hprof', '.bin', '.dump', '.hprof.gz', '.bin.gz', '.dump.gz'];
            const isValid    = validExts.some(ext => lower.endsWith(ext));

            if (isValid) {
                // DataTransfer 객체를 input에 주입
                try {
                    fileInput.files = files;
                } catch (_) {
                    // 일부 브라우저에서 직접 할당 불가 → DataTransfer 사용
                    const dt = new DataTransfer();
                    dt.items.add(file);
                    fileInput.files = dt.files;
                }
                if (fileNameSpan) fileNameSpan.textContent = file.name;
            } else {
                showToast('Invalid file type. Please upload a .hprof, .bin, .dump, or .gz file.', 'error');
            }
        });
    }

    // ── 폼 제출 검증 + 로딩 표시 ────────────────────────────
    const uploadForm = document.getElementById('uploadForm');

    if (uploadForm) {
        uploadForm.addEventListener('submit', function (e) {
            const file = fileInput ? fileInput.files[0] : null;

            if (!file) {
                e.preventDefault();
                showToast('Please select a file to upload.', 'error');
                return;
            }

            // 확장자 검증
            const lower = file.name.toLowerCase();
            const validExts = ['.hprof', '.bin', '.dump', '.hprof.gz', '.bin.gz', '.dump.gz'];
            if (!validExts.some(ext => lower.endsWith(ext))) {
                e.preventDefault();
                const ext = file.name.split('.').pop().toLowerCase();
                showToast('Unsupported file type: .' + ext + '. Allowed: .hprof, .bin, .dump (+ .gz)', 'error');
                return;
            }

            // 2 GB 제한
            const MAX = 2 * 1024 * 1024 * 1024;
            if (file.size > MAX) {
                e.preventDefault();
                showToast('File size exceeds 2 GB. Please upload a smaller file.', 'error');
                return;
            }

            // 로딩 버튼
            const btn = uploadForm.querySelector('button[type="submit"]');
            if (btn) {
                btn.disabled     = true;
                btn.innerHTML    = '<span>⏳ Uploading &amp; Analyzing with MAT…</span>';
                btn.style.opacity = '0.75';
            }
        });
    }

    // ── Alert 자동 숨김 (5초 후) ────────────────────────────
    document.querySelectorAll('.alert').forEach(function (alert) {
        setTimeout(function () {
            alert.style.transition = 'opacity 0.5s ease';
            alert.style.opacity    = '0';
            setTimeout(() => alert.remove(), 500);
        }, 5000);
    });

    // ── 토스트 메시지 헬퍼 ───────────────────────────────────
    function showToast(message, type) {
        const existing = document.querySelector('.toast-msg');
        if (existing) existing.remove();

        const toast = document.createElement('div');
        toast.className  = 'toast-msg';
        toast.textContent = message;
        toast.style.cssText = [
            'position:fixed', 'bottom:24px', 'right:24px', 'z-index:9999',
            'padding:14px 20px', 'border-radius:8px',
            'font-size:14px', 'font-weight:500',
            'box-shadow:0 4px 12px rgba(0,0,0,0.15)',
            'transition:opacity 0.4s ease',
            type === 'error'
                ? 'background:#FEE2E2;color:#7F1D1D;border-left:4px solid #EF4444'
                : 'background:#D1FAE5;color:#065F46;border-left:4px solid #10B981'
        ].join(';');

        document.body.appendChild(toast);
        setTimeout(() => { toast.style.opacity = '0'; }, 4000);
        setTimeout(() => toast.remove(), 4500);
    }

});
