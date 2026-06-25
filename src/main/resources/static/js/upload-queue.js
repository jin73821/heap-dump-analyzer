/* ───────────────────────────────────────────────────────────────
 *  Heap Dump Analyzer — 공유 Multi-file Upload Queue
 *
 *  사용 페이지: index.html(/) · files.html(/files)
 *
 *  사전 정의 글로벌 (페이지 Thymeleaf 인라인 스크립트):
 *      MAX_UPLOAD_BYTES   number   업로드 최대 바이트
 *      MAX_UPLOAD_GB      number   업로드 최대 GB (표시용)
 *      ALLOW_ALL_EXT      boolean  확장자 whitelist 우회 여부
 *
 *  사전 정의 DOM (페이지 마크업):
 *      #uploadForm  #uploadZone  #fileInput  #uploadQueueUI  #uploadQueueList
 *
 *  외부 콜백 (선택):
 *      window.onUploadQueueDone  업로드 모달의 [확인] 클릭 시 호출. 기본은 page reload.
 *  ─────────────────────────────────────────────────────────────── */

(function(global) {
    'use strict';

    var _MAX_QUEUE = 5;
    var _uploadQueue = [];
    var _uploading = false;
    var _uploadCancelled = false;
    var _currentXhr = null;
    var _uploadMode = 'auto'; // 'auto' | 'heapdump' | 'coredump'

    /* ── 작은 유틸 ── */
    function fmtB(b) {
        if (b < 1024) return b + ' B';
        if (b < 1048576) return (b / 1024).toFixed(1) + ' KB';
        if (b < 1073741824) return (b / 1048576).toFixed(1) + ' MB';
        return (b / 1073741824).toFixed(2) + ' GB';
    }
    function fmtSpeed(bps) {
        if (!bps || bps <= 0) return '';
        if (bps < 1024) return bps.toFixed(0) + ' B/s';
        if (bps < 1048576) return (bps / 1024).toFixed(1) + ' KB/s';
        if (bps < 1073741824) return (bps / 1048576).toFixed(1) + ' MB/s';
        return (bps / 1073741824).toFixed(2) + ' GB/s';
    }
    function toast(msg, type) {
        var old = document.querySelector('.toast-msg');
        if (old) old.remove();
        var t = document.createElement('div');
        t.className = 'toast-msg'; t.textContent = msg;
        t.style.cssText = 'position:fixed;top:20px;left:50%;transform:translateX(-50%);z-index:9999;padding:12px 18px;'
            + 'border-radius:8px;font-size:13px;font-weight:500;transition:opacity .4s;box-shadow:0 4px 12px rgba(0,0,0,.15);'
            + (type === 'error'
                ? 'background:#FEE2E2;color:#7F1D1D;border-left:4px solid #EF4444'
                : 'background:#D1FAE5;color:#065F46;border-left:4px solid #10B981');
        document.body.appendChild(t);
        setTimeout(function() { t.style.opacity = '0'; }, 3500);
        setTimeout(function() { t.remove(); }, 4000);
    }
    var escapeHtml = (global.Common && global.Common.escHtml) ? global.Common.escHtml : function(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    };

    /* ── 부분 해시 (서버 중복 검사용) ── */
    function computePartialHash(file) {
        return new Promise(function(resolve, reject) {
            try {
                var slice = file.slice(0, 65536);
                var reader = new FileReader();
                reader.onload = function() {
                    try {
                        if (global.crypto && crypto.subtle) {
                            crypto.subtle.digest('SHA-256', reader.result).then(function(buf) {
                                var arr = Array.from(new Uint8Array(buf));
                                resolve(arr.map(function(b) { return b.toString(16).padStart(2, '0'); }).join(''));
                            }).catch(function() { resolve(simpleHash(reader.result)); });
                        } else { resolve(simpleHash(reader.result)); }
                    } catch (e) { resolve(simpleHash(reader.result)); }
                };
                reader.onerror = function() { reject(reader.error); };
                reader.readAsArrayBuffer(slice);
            } catch (e) { reject(e); }
        });
    }
    function simpleHash(buffer) {
        var bytes = new Uint8Array(buffer);
        var h1 = 0x811c9dc5, h2 = 0x01000193;
        for (var i = 0; i < bytes.length; i++) {
            h1 = Math.imul(h1 ^ bytes[i], h2);
            h2 = Math.imul(h2 ^ bytes[i], 0x01000193);
        }
        return (h1 >>> 0).toString(16).padStart(8, '0') + (h2 >>> 0).toString(16).padStart(8, '0');
    }

    /* ── 코어 덤프 파일 판별 ── */
    function isCoreDumpFilename(name) {
        var lower = name.toLowerCase();
        // core.* (core.1234 등) 또는 *.core (vmcore.core 등) 또는 정확히 "core"
        return lower === 'core' || lower.startsWith('core.') || lower.endsWith('.core');
    }
    var _HEAP_EXTS = ['.hprof', '.bin', '.dump', '.hprof.gz', '.bin.gz', '.dump.gz'];
    function isRecognizedHeapExt(name) {
        var l = name.toLowerCase();
        return _HEAP_EXTS.some(function(e) { return l.endsWith(e); });
    }
    function hasNoExtension(name) {
        var base = name.split('/').pop();
        return base.indexOf('.') === -1;
    }
    function resolveFileType(name) {
        if (_uploadMode === 'heapdump') return 'heapdump';
        if (_uploadMode === 'coredump') return 'coredump';
        // auto: 파일명 패턴으로 자동 판별
        if (isCoreDumpFilename(name)) return 'coredump';
        if (!isRecognizedHeapExt(name) && hasNoExtension(name)) return 'others';
        return 'heapdump';
    }

    /* ── 확장자 경고 모달 ── */
    function showExtWarning(filename, ext) {
        var overlay = document.getElementById('extWarningOverlay');
        if (overlay) overlay.remove();
        overlay = document.createElement('div');
        overlay.id = 'extWarningOverlay';
        overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:10000;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center';
        overlay.innerHTML =
            '<div style="background:#fff;border-radius:12px;padding:28px 32px;max-width:440px;width:90%;box-shadow:0 8px 32px rgba(0,0,0,.2)">'
            + '<div style="display:flex;align-items:center;gap:12px;margin-bottom:16px">'
            + '<div style="width:40px;height:40px;border-radius:50%;background:#FEE2E2;display:flex;align-items:center;justify-content:center;font-size:20px;flex-shrink:0">&#9888;</div>'
            + '<h3 style="font-size:16px;font-weight:700;color:#1F2937;margin:0">Unsupported File Type</h3>'
            + '</div>'
            + '<p style="font-size:13px;color:#374151;margin-bottom:12px">Selected file: <strong>' + escapeHtml(filename) + '</strong></p>'
            + '<p style="font-size:13px;color:#DC2626;margin-bottom:16px">Extension <strong>.' + escapeHtml(ext) + '</strong> is not supported for heap dump analysis.</p>'
            + '<div style="background:#F9FAFB;border-radius:8px;padding:12px 16px;margin-bottom:20px">'
            + '<div style="font-size:11px;font-weight:700;color:#6B7280;text-transform:uppercase;margin-bottom:6px">Supported Formats</div>'
            + '<div style="display:flex;gap:8px">'
            + '<span style="padding:4px 10px;background:#DBEAFE;color:#1D4ED8;border-radius:4px;font-size:12px;font-weight:600">.hprof</span>'
            + '<span style="padding:4px 10px;background:#DBEAFE;color:#1D4ED8;border-radius:4px;font-size:12px;font-weight:600">.bin</span>'
            + '<span style="padding:4px 10px;background:#DBEAFE;color:#1D4ED8;border-radius:4px;font-size:12px;font-weight:600">.dump</span>'
            + '<span style="padding:4px 10px;background:#D1FAE5;color:#065F46;border-radius:4px;font-size:12px;font-weight:600">.gz</span>'
            + '</div></div>'
            + '<button onclick="this.closest(\'#extWarningOverlay\').remove()" style="width:100%;padding:10px;background:#2563EB;color:#fff;border:none;border-radius:8px;font-size:14px;font-weight:600;cursor:pointer">OK</button>'
            + '</div>';
        document.body.appendChild(overlay);
        overlay.addEventListener('click', function(e) { if (e.target === overlay) overlay.remove(); });
    }

    /* ── 큐 진입 ── */
    function enqueueFiles(files) {
        if (_uploading) { toast('업로드가 진행 중입니다. 완료 후 다시 시도해주세요.', 'error'); return; }
        var valid = [], rejected = [], oversized = [];
        var heapExts = ['.hprof', '.bin', '.dump', '.hprof.gz', '.bin.gz', '.dump.gz'];
        for (var i = 0; i < files.length; i++) {
            var ftype = resolveFileType(files[i].name);
            var lower = files[i].name.toLowerCase();
            var extOk;
            if (ftype === 'coredump') {
                extOk = true; // 코어 덤프는 확장자 제한 없음
            } else {
                extOk = global.ALLOW_ALL_EXT || heapExts.some(function(ext) { return lower.endsWith(ext); });
            }
            if (!extOk) rejected.push(files[i].name);
            else if (files[i].size > global.MAX_UPLOAD_BYTES) oversized.push(files[i].name);
            else valid.push(files[i]);
        }
        if (rejected.length > 0 || oversized.length > 0) {
            showFileFilterModal(rejected, oversized, valid, function(proceed) {
                if (proceed && valid.length > 0) continueEnqueue(valid);
            });
            return;
        }
        if (valid.length === 0) return;
        continueEnqueue(valid);
    }

    function showFileFilterModal(rejected, oversized, valid, callback) {
        var ov = document.createElement('div'); ov.id = 'fileFilterOv';
        ov.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:10000;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center';
        var html = '<div style="background:#fff;border-radius:12px;padding:24px;max-width:460px;width:90%;max-height:80vh;overflow-y:auto">'
            + '<div style="display:flex;align-items:center;gap:12px;margin-bottom:16px">'
            + '<div style="width:40px;height:40px;border-radius:50%;background:#FEF3C7;display:flex;align-items:center;justify-content:center;flex-shrink:0">'
            + '<svg viewBox="0 0 24 24" fill="none" stroke="#F59E0B" stroke-width="2" style="width:22px;height:22px"><path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg></div>'
            + '<h3 style="font-size:16px;font-weight:700;color:#1F2937;margin:0">파일 검증 결과</h3></div>';
        if (rejected.length > 0) {
            html += '<div style="margin-bottom:12px"><div style="font-size:12px;font-weight:700;color:#DC2626;margin-bottom:6px">지원되지 않는 형식 (' + rejected.length + '개)</div>';
            rejected.forEach(function(n) {
                html += '<div style="font-size:12px;color:#6B7280;padding:3px 0;display:flex;align-items:center;gap:6px">'
                    + '<span style="color:#DC2626">✕</span> ' + escapeHtml(n) + '</div>';
            });
            html += '</div>';
        }
        if (oversized.length > 0) {
            html += '<div style="margin-bottom:12px"><div style="font-size:12px;font-weight:700;color:#F59E0B;margin-bottom:6px">용량 초과 — ' + global.MAX_UPLOAD_GB + ' GB 제한 (' + oversized.length + '개)</div>';
            oversized.forEach(function(n) {
                html += '<div style="font-size:12px;color:#6B7280;padding:3px 0;display:flex;align-items:center;gap:6px">'
                    + '<span style="color:#F59E0B">✕</span> ' + escapeHtml(n) + '</div>';
            });
            html += '</div>';
        }
        if (valid.length > 0) {
            html += '<div style="margin-bottom:16px"><div style="font-size:12px;font-weight:700;color:#059669;margin-bottom:6px">업로드 가능 (' + valid.length + '개)</div>';
            valid.forEach(function(f) {
                html += '<div style="font-size:12px;color:#6B7280;padding:3px 0;display:flex;align-items:center;gap:6px">'
                    + '<span style="color:#059669">✓</span> ' + escapeHtml(f.name) + ' <span style="color:#9CA3AF">(' + fmtB(f.size) + ')</span></div>';
            });
            html += '</div>';
            html += '<div style="display:flex;gap:8px">'
                + '<button id="ffCancel" style="flex:1;padding:10px;background:#F3F4F6;color:#374151;border:none;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer">취소</button>'
                + '<button id="ffProceed" style="flex:1;padding:10px;background:#2563EB;color:#fff;border:none;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer">' + valid.length + '개 파일 업로드</button>'
                + '</div>';
        } else {
            html += '<div style="font-size:13px;color:#6B7280;margin-bottom:16px">업로드 가능한 파일이 없습니다.</div>'
                + '<button id="ffCancel" style="width:100%;padding:10px;background:#2563EB;color:#fff;border:none;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer">확인</button>';
        }
        html += '</div>';
        ov.innerHTML = html;
        document.body.appendChild(ov);
        ov.querySelector('#ffCancel').onclick = function() { ov.remove(); callback(false); };
        var proceed = ov.querySelector('#ffProceed');
        if (proceed) proceed.onclick = function() { ov.remove(); callback(true); };
    }

    function continueEnqueue(valid) {
        if (valid.length > _MAX_QUEUE) {
            toast('최대 ' + _MAX_QUEUE + '개까지 동시 업로드 가능합니다. 처음 ' + _MAX_QUEUE + '개만 처리합니다.', 'error');
            valid = valid.slice(0, _MAX_QUEUE);
        }
        _uploadQueue = valid.map(function(f) { return { file: f, uploadName: f.name, status: 'pending', fileType: resolveFileType(f.name) }; });
        fetch('/api/disk/check').then(function(r) { return r.json(); }).then(function(d) {
            var totalSize = valid.reduce(function(s, f) { return s + f.size; }, 0);
            if (d.usableSpaceBytes != null && totalSize > d.usableSpaceBytes) {
                showDiskFullModal(d, { name: valid.length + '개 파일', size: totalSize }); return;
            }
            startDuplicateChecks(0);
        }).catch(function() { startDuplicateChecks(0); });
    }

    function startDuplicateChecks(idx) {
        if (idx >= _uploadQueue.length) { startQueueUploads(); return; }
        var item = _uploadQueue[idx];
        if (item.status === 'skipped' || item.fileType === 'coredump') { startDuplicateChecks(idx + 1); return; }
        computePartialHash(item.file).then(function(hash) {
            item._hash = hash;
            fetch('/api/upload/check', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ filename: item.uploadName, fileSize: item.file.size, partialHash: hash })
            }).then(function(r) { return r.json(); }).then(function(res) {
                if (res.status === 'DUPLICATE_CONTENT') {
                    showQueueDupContentModal(idx, res.existingFilename, function() { startDuplicateChecks(idx + 1); });
                } else if (res.status === 'DUPLICATE_NAME') {
                    showQueueDupNameBlockedModal(idx, res.existingFilename, function() { startDuplicateChecks(idx + 1); });
                } else {
                    startDuplicateChecks(idx + 1);
                }
            }).catch(function() { startDuplicateChecks(idx + 1); });
        }).catch(function() { startDuplicateChecks(idx + 1); });
    }

    function showQueueDupContentModal(idx, existingName, onDone) {
        var item = _uploadQueue[idx];
        var ov = document.createElement('div'); ov.id = 'dupOv';
        ov.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:10000;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center';
        ov.innerHTML = '<div style="background:#fff;border-radius:12px;padding:24px;max-width:420px;width:90%">'
            + '<div style="display:flex;align-items:center;gap:12px;margin-bottom:16px">'
            + '<div style="width:40px;height:40px;border-radius:50%;background:#DBEAFE;display:flex;align-items:center;justify-content:center;flex-shrink:0">'
            + '<svg viewBox="0 0 24 24" fill="none" stroke="#2563EB" stroke-width="2" style="width:22px;height:22px"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg></div>'
            + '<h3 style="font-size:16px;font-weight:700;color:#1F2937;margin:0">중복 파일 감지 (' + (idx + 1) + '/' + _uploadQueue.length + ')</h3></div>'
            + '<p style="font-size:13px;color:#374151;margin-bottom:8px">업로드 파일: <strong>' + escapeHtml(item.file.name) + '</strong></p>'
            + '<p style="font-size:13px;color:#374151;margin-bottom:16px">이미 존재하는 <strong>\'' + escapeHtml(existingName) + '\'</strong>과 동일한 내용입니다.</p>'
            + '<div style="display:flex;gap:8px">'
            + '<button id="dupSkip" style="flex:1;padding:10px;background:#F3F4F6;color:#374151;border:none;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer">건너뛰기</button>'
            + '<button id="dupProceed" style="flex:1;padding:10px;background:#2563EB;color:#fff;border:none;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer">업로드</button>'
            + '</div></div>';
        document.body.appendChild(ov);
        ov.querySelector('#dupSkip').onclick = function() { ov.remove(); item.status = 'skipped'; onDone(); };
        ov.querySelector('#dupProceed').onclick = function() { ov.remove(); onDone(); };
    }

    function showQueueDupNameBlockedModal(idx, existingName, onDone) {
        var item = _uploadQueue[idx];
        item.status = 'skipped';
        var ov = document.createElement('div'); ov.id = 'dupOv';
        ov.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:10000;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center';
        ov.innerHTML = '<div style="background:#fff;border-radius:12px;padding:24px;max-width:520px;width:90%">'
            + '<div style="display:flex;align-items:center;gap:12px;margin-bottom:16px">'
            + '<div style="width:40px;height:40px;border-radius:50%;background:#FEE2E2;display:flex;align-items:center;justify-content:center;flex-shrink:0">'
            + '<svg viewBox="0 0 24 24" fill="none" stroke="#DC2626" stroke-width="2" style="width:22px;height:22px"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg></div>'
            + '<h3 style="font-size:16px;font-weight:700;color:#1F2937;margin:0">동일 파일명 — 업로드 차단 (' + (idx + 1) + '/' + _uploadQueue.length + ')</h3></div>'
            + '<p style="font-size:13px;color:#374151;margin-bottom:8px">업로드 파일: <strong>' + escapeHtml(item.file.name) + '</strong></p>'
            + '<p style="font-size:13px;color:#374151;margin-bottom:16px">이미 같은 이름의 파일 <strong>\'' + escapeHtml(existingName) + '\'</strong>이(가) 존재합니다. 업로드 버튼으로는 동일 이름의 파일을 덮어쓰거나 추가할 수 없습니다. 파일명을 변경한 뒤 다시 시도해 주세요.</p>'
            + '<div style="display:flex;justify-content:flex-end">'
            + '<button id="dupBlockedOk" style="padding:10px 20px;background:#2563EB;color:#fff;border:none;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer">확인</button>'
            + '</div></div>';
        document.body.appendChild(ov);
        ov.querySelector('#dupBlockedOk').onclick = function() { ov.remove(); onDone(); };
    }

    /* ── 진행률 모달 ── */
    function showUploadProgressModal() {
        var existing = document.getElementById('uploadProgressOv');
        if (existing) existing.remove();
        var ov = document.createElement('div'); ov.id = 'uploadProgressOv';
        ov.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:10000;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center';
        var total = _uploadQueue.filter(function(q) { return q.status !== 'skipped'; }).length;
        ov.innerHTML = '<div style="background:#fff;border-radius:12px;padding:28px;max-width:600px;width:92%;max-height:84vh;overflow-y:auto">'
            + '<div style="display:flex;align-items:center;gap:14px;margin-bottom:20px">'
            + '<div style="width:46px;height:46px;border-radius:50%;background:#DBEAFE;display:flex;align-items:center;justify-content:center;flex-shrink:0">'
            + '<svg viewBox="0 0 24 24" fill="none" stroke="#2563EB" stroke-width="2" style="width:24px;height:24px"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg></div>'
            + '<div><h3 style="font-size:19px;font-weight:700;color:#1F2937;margin:0">파일 업로드</h3>'
            + '<div id="upModalSub" style="font-size:14px;color:#9CA3AF;margin-top:3px">0 / ' + total + ' 완료</div></div></div>'
            + '<div id="upModalList" style="margin-bottom:20px"></div>'
            + '<div id="upModalCancelWrap" style="text-align:center">'
            + '<button id="upModalCancelBtn" style="padding:10px 24px;background:#F3F4F6;color:#DC2626;border:1px solid #FECACA;border-radius:8px;font-size:15px;font-weight:600;cursor:pointer">업로드 취소</button>'
            + '</div>'
            + '<div id="upModalFooter" style="display:none;text-align:center">'
            + '<button id="upModalDoneBtn" style="padding:12px 28px;background:#2563EB;color:#fff;border:none;border-radius:8px;font-size:15px;font-weight:600;cursor:pointer">확인</button>'
            + '</div></div>';
        document.body.appendChild(ov);
        ov.querySelector('#upModalCancelBtn').onclick = cancelUploadQueue;
        ov.querySelector('#upModalDoneBtn').onclick = function() {
            ov.remove();
            if (typeof global.onUploadQueueDone === 'function') global.onUploadQueueDone();
            else global.location.reload();
        };
        renderUploadModalList();
    }

    function renderUploadModalList() {
        var list = document.getElementById('upModalList');
        if (!list) return;
        var html = '';
        var _UQ_ICON = {
            uploading: '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="#2563EB" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="8" y1="13" x2="8" y2="3"/><polyline points="3,8 8,3 13,8"/></svg>',
            done:      '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16"><circle cx="8" cy="8" r="7" fill="#10B981"/><polyline points="4.5,8.2 7,10.7 11.5,5.8" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>',
            error:     '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16"><circle cx="8" cy="8" r="7" fill="#EF4444"/><line x1="5.2" y1="5.2" x2="10.8" y2="10.8" stroke="#fff" stroke-width="1.8" stroke-linecap="round"/><line x1="10.8" y1="5.2" x2="5.2" y2="10.8" stroke="#fff" stroke-width="1.8" stroke-linecap="round"/></svg>',
            cancelled: '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16"><circle cx="8" cy="8" r="6.5" fill="none" stroke="#9CA3AF" stroke-width="1.5"/><line x1="4.5" y1="11.5" x2="11.5" y2="4.5" stroke="#9CA3AF" stroke-width="1.5"/></svg>',
            pending:   '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="#F59E0B" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="8" r="6.5"/><polyline points="8,4.5 8,8 10.5,9.5"/></svg>'
        };
        _uploadQueue.forEach(function(q, i) {
            if (q.status === 'skipped') return;
            var icon, cls, label;
            if (q.status === 'uploading') {
                icon = _UQ_ICON.uploading; cls = 'uploading';
                if (q._pct) {
                    label = q._pct + '%';
                    if (q._speed > 0) label += ' · ' + fmtSpeed(q._speed);
                } else label = '업로드 중';
            }
            else if (q.status === 'done') { icon = _UQ_ICON.done; cls = 'done'; label = '완료'; }
            else if (q.status === 'error') { icon = _UQ_ICON.error; cls = 'error'; label = q._error ? q._error : '실패'; }
            else if (q.status === 'cancelled') { icon = _UQ_ICON.cancelled; cls = 'skipped'; label = '취소됨'; }
            else { icon = _UQ_ICON.pending; cls = 'pending'; label = '대기'; }
            var typeBadge = '';
            if (q.fileType === 'coredump') {
                typeBadge = '<span style="display:inline-block;font-size:10px;padding:1px 5px;border-radius:3px;background:#FEF3C7;color:#92400E;font-weight:700;margin-left:4px;vertical-align:middle">CORE</span>';
            } else if (q.fileType === 'exec') {
                typeBadge = '<span style="display:inline-block;font-size:10px;padding:1px 5px;border-radius:3px;background:#EDE9FE;color:#5B21B6;font-weight:700;margin-left:4px;vertical-align:middle">EXEC</span>';
            } else if (q.fileType === 'others') {
                typeBadge = '<span style="display:inline-block;font-size:10px;padding:1px 5px;border-radius:3px;background:#F3F4F6;color:#6B7280;font-weight:700;margin-left:4px;vertical-align:middle">?</span>';
            }
            html += '<div class="uq-item" id="uqi' + i + '">'
                + '<span class="uq-icon">' + icon + '</span>'
                + '<span class="uq-name" title="' + escapeHtml(q.uploadName) + '">' + escapeHtml(q.uploadName) + typeBadge + ' <span style="color:#9CA3AF;font-size:13px">' + fmtB(q.file.size) + '</span></span>'
                + '<span class="uq-status ' + cls + '">' + label + '</span></div>';
            if (q.status === 'uploading') {
                html += '<div class="uq-bar-bg"><div class="uq-bar" id="uqbar' + i + '" style="width:' + (q._pct || 0) + '%"></div></div>';
            }
        });
        list.innerHTML = html;
        var done = _uploadQueue.filter(function(q) { return q.status === 'done'; }).length;
        var total = _uploadQueue.filter(function(q) { return q.status !== 'skipped'; }).length;
        var sub = document.getElementById('upModalSub');
        if (sub) sub.textContent = done + ' / ' + total + ' 완료';
    }

    function cancelUploadQueue() {
        var pending = _uploadQueue.filter(function(q) { return q.status === 'pending' || q.status === 'uploading'; }).length;
        var ov = document.createElement('div'); ov.id = 'cancelConfirmOv';
        ov.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:10001;background:rgba(0,0,0,.3);display:flex;align-items:center;justify-content:center';
        ov.innerHTML = '<div style="background:#fff;border-radius:12px;padding:24px;max-width:380px;width:90%;text-align:center">'
            + '<div style="width:44px;height:44px;background:#FEE2E2;border-radius:50%;display:flex;align-items:center;justify-content:center;margin:0 auto 12px">'
            + '<svg viewBox="0 0 24 24" fill="none" stroke="#DC2626" stroke-width="2" style="width:22px;height:22px"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg></div>'
            + '<div style="font-size:16px;font-weight:700;color:#1F2937;margin-bottom:8px">업로드를 취소하시겠습니까?</div>'
            + '<div style="font-size:13px;color:#6B7280;margin-bottom:16px">현재 진행 중이거나 대기 중인 <b>' + pending + '개</b> 파일의 업로드가 중단됩니다.<br>이미 완료된 파일은 유지됩니다.</div>'
            + '<div style="display:flex;gap:8px">'
            + '<button id="cancelNo" style="flex:1;padding:10px;background:#F3F4F6;color:#374151;border:none;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer">계속 업로드</button>'
            + '<button id="cancelYes" style="flex:1;padding:10px;background:#DC2626;color:#fff;border:none;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer">취소</button>'
            + '</div></div>';
        document.body.appendChild(ov);
        ov.querySelector('#cancelNo').onclick = function() { ov.remove(); };
        ov.querySelector('#cancelYes').onclick = function() { ov.remove(); doCancelUpload(); };
    }

    function doCancelUpload() {
        _uploadCancelled = true;
        if (_currentXhr) { _currentXhr.abort(); _currentXhr = null; }
        _uploadQueue.forEach(function(q) {
            if (q.status === 'pending') q.status = 'cancelled';
            if (q.status === 'uploading') q.status = 'cancelled';
        });
        _uploading = false;
        global.onbeforeunload = null;
        setUploadZoneDisabled(false);
        renderUploadModalList();
        var cancelWrap = document.getElementById('upModalCancelWrap');
        if (cancelWrap) cancelWrap.style.display = 'none';
        var footer = document.getElementById('upModalFooter');
        if (footer) footer.style.display = 'block';
        var sub = document.getElementById('upModalSub');
        var done = _uploadQueue.filter(function(q) { return q.status === 'done'; }).length;
        var total = _uploadQueue.filter(function(q) { return q.status !== 'skipped'; }).length;
        if (sub) sub.textContent = done + ' / ' + total + ' 완료 — 취소됨';
    }

    function startQueueUploads() {
        var pending = _uploadQueue.filter(function(q) { return q.status === 'pending'; });
        if (pending.length === 0) { toast('업로드할 파일이 없습니다.'); return; }
        _uploading = true;
        _uploadCancelled = false;
        _currentXhr = null;
        global.onbeforeunload = function(e) { e.preventDefault(); e.returnValue = ''; return ''; };
        setUploadZoneDisabled(true);
        showUploadProgressModal();
        processNextInQueue(0);
    }

    function processNextInQueue(idx) {
        if (_uploadCancelled) return;
        while (idx < _uploadQueue.length && _uploadQueue[idx].status !== 'pending') idx++;
        if (idx >= _uploadQueue.length) {
            _uploading = false;
            global.onbeforeunload = null;
            _currentXhr = null;
            renderUploadModalList();
            var cancelWrap = document.getElementById('upModalCancelWrap');
            if (cancelWrap) cancelWrap.style.display = 'none';
            var footer = document.getElementById('upModalFooter');
            if (footer) footer.style.display = 'block';
            var sub = document.getElementById('upModalSub');
            var done = _uploadQueue.filter(function(q) { return q.status === 'done'; }).length;
            var total = _uploadQueue.filter(function(q) { return q.status !== 'skipped'; }).length;
            if (sub) sub.textContent = done + ' / ' + total + ' 완료 — 업로드 종료';
            setUploadZoneDisabled(false);
            return;
        }
        var item = _uploadQueue[idx];
        item.status = 'uploading'; item._pct = 0;
        item._lastTs = Date.now(); item._lastLoaded = 0; item._speed = 0;
        renderUploadModalList();

        var xhr = new XMLHttpRequest();
        _currentXhr = xhr;
        xhr.upload.addEventListener('progress', function(e) {
            if (e.lengthComputable) {
                item._pct = Math.round(e.loaded / e.total * 100);
                var now = Date.now();
                var dt = (now - item._lastTs) / 1000;
                if (dt >= 0.25) {
                    var instSpeed = (e.loaded - item._lastLoaded) / dt;
                    item._speed = item._speed > 0 ? (item._speed * 0.7 + instSpeed * 0.3) : instSpeed;
                    item._lastTs = now;
                    item._lastLoaded = e.loaded;
                }
                var bar = document.getElementById('uqbar' + idx);
                var statusEl = document.getElementById('uqi' + idx);
                if (bar) bar.style.width = item._pct + '%';
                if (statusEl) {
                    var st = statusEl.querySelector('.uq-status');
                    if (st) {
                        var speedStr = item._speed > 0 ? ' · ' + fmtSpeed(item._speed) : '';
                        st.textContent = item._pct + '% (' + fmtB(e.loaded) + '/' + fmtB(e.total) + speedStr + ')';
                    }
                }
            }
        });
        xhr.addEventListener('load', function() {
            var ok = xhr.status >= 200 && xhr.status < 300;
            if (ok) {
                item.status = 'done';
            } else {
                item.status = 'error';
                try {
                    var j = JSON.parse(xhr.responseText);
                    if (j && j.message) item._error = j.message;
                } catch (e) { item._error = '서버 오류 (HTTP ' + xhr.status + ')'; }
            }
            renderUploadModalList();
            processNextInQueue(idx + 1);
        });
        xhr.addEventListener('error', function() {
            item.status = 'error';
            item._error = '네트워크 오류';
            renderUploadModalList();
            processNextInQueue(idx + 1);
        });
        if (item.fileType === 'coredump') {
            xhr.open('POST', '/api/core-dump/upload');
            var fd = new FormData();
            fd.append('coreFile', item.file, item.uploadName);
            xhr.send(fd);
        } else {
            xhr.open('POST', '/api/upload');
            var fd = new FormData();
            fd.append('file', item.file, item.uploadName);
            xhr.send(fd);
        }
    }

    function setUploadZoneDisabled(disabled, filename) {
        var zone = document.getElementById('uploadZone');
        var btn = zone ? zone.querySelector('.upload-zone-btn') : null;
        var icon = zone ? zone.querySelector('.upload-icon') : null;
        var title = zone ? zone.querySelector('.upload-zone-title') : null;
        var sub = zone ? zone.querySelector('.upload-zone-sub') : null;
        if (disabled) {
            if (icon) icon.style.display = 'none';
            if (title) { title.textContent = filename || 'Uploading…'; title.style.display = ''; title.style.fontSize = '12px'; title.style.color = '#2563EB'; title.style.wordBreak = 'break-all'; }
            if (sub) sub.style.display = 'none';
            if (zone) zone.style.borderColor = '#D1D5DB';
            if (btn) { btn.classList.add('uploading'); btn.disabled = true; }
        } else {
            if (icon) icon.style.display = '';
            if (title) { title.textContent = 'Drop file here'; title.style.fontSize = ''; title.style.color = ''; title.style.wordBreak = ''; }
            if (sub) sub.style.display = '';
            if (zone) zone.style.borderColor = '';
            if (btn) { btn.classList.remove('uploading'); btn.disabled = false; }
            var ui = document.getElementById('uploadQueueUI'); if (ui) ui.style.display = 'none';
        }
    }

    function showDiskFullModal(diskInfo, file) {
        var overlay = document.createElement('div');
        overlay.id = 'diskFullOverlay';
        overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:10000;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center';
        overlay.innerHTML = '<div style="background:#fff;border-radius:12px;padding:24px;max-width:380px;width:90%;text-align:center">'
            + '<div style="width:48px;height:48px;background:#FEE2E2;border-radius:50%;display:flex;align-items:center;justify-content:center;margin:0 auto 12px">'
            + '<svg viewBox="0 0 24 24" fill="none" stroke="#DC2626" stroke-width="2" style="width:24px;height:24px"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>'
            + '</div>'
            + '<div style="font-size:16px;font-weight:700;color:#DC2626;margin-bottom:8px">디스크 용량 부족</div>'
            + '<div style="font-size:13px;color:#78716C;margin-bottom:6px">'
            + '파일 크기: <b style="color:#1D4ED8">' + fmtB(file.size) + '</b>'
            + ' &nbsp;/ 디스크 여유: <b style="color:#DC2626">' + diskInfo.usableSpace + '</b>'
            + '</div>'
            + '<div style="font-size:13px;color:#92400E;margin-bottom:16px">'
            + '업로드할 파일의 용량이 디스크 여유 공간보다 큽니다.<br>불필요한 파일을 삭제한 후 다시 시도해 주세요.'
            + '</div>'
            + '<button onclick="document.getElementById(\'diskFullOverlay\').remove()" style="width:100%;padding:10px;background:#DC2626;color:#fff;border:none;border-radius:8px;font-size:14px;font-weight:600;cursor:pointer">확인</button>'
            + '</div>';
        document.body.appendChild(overlay);
        overlay.addEventListener('click', function(e) { if (e.target === overlay) overlay.remove(); });
    }

    /* ── 기본 UploadZone DOM 바인딩 ── */
    function bindZone(zoneId, fileInputId) {
        var zone = document.getElementById(zoneId);
        var input = document.getElementById(fileInputId);
        if (input) {
            input.addEventListener('change', function(e) {
                var files = Array.from(e.target.files);
                if (files.length > 0) enqueueFiles(files);
                input.value = '';
            });
        }
        if (zone) {
            zone.addEventListener('dragover', function(e) { e.preventDefault(); zone.style.borderColor = '#2563EB'; });
            zone.addEventListener('dragleave', function() { zone.style.borderColor = '#93C5FD'; });
            zone.addEventListener('drop', function(e) {
                e.preventDefault(); zone.style.borderColor = '#93C5FD';
                var files = Array.from(e.dataTransfer.files);
                if (files.length > 0) enqueueFiles(files);
            });
        }
    }

    function init() { bindZone('uploadZone', 'fileInput'); }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();

    /* ── 외부에 공개되는 API ── */
    global.UploadQueue = {
        enqueueFiles: enqueueFiles,
        bindZone: bindZone,
        setUploadMode: function(mode) { _uploadMode = mode; },
        getLastQueueTypes: function() {
            return _uploadQueue.map(function(item) {
                return { fileType: item.fileType, status: item.status, filename: item.uploadName };
            });
        },
        toast: toast,
        fmtB: fmtB,
        fmtSpeed: fmtSpeed,
        escapeHtml: escapeHtml
    };
    /* 기존 인라인 코드 호환 — saveSetting 등에서 toast() 글로벌 호출 사용 */
    global.toast = toast;
    global.fmtB = fmtB;
    global.fmtSpeed = fmtSpeed;
    global.escapeHtml = escapeHtml;
    global.enqueueFiles = enqueueFiles;
})(window);
