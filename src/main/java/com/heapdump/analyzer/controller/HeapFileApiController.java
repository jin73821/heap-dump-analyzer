package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.service.CoreDumpAnalyzerService;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.util.AuthUtil;
import com.heapdump.analyzer.util.FilenameValidator;
import com.heapdump.analyzer.util.FormatUtils;
import com.heapdump.analyzer.util.HttpHeaderValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 파일 업로드/다운로드/일괄 삭제/중복 검사 API (Phase 4B-2).
 */
@Controller
public class HeapFileApiController {

    private static final Logger logger = LoggerFactory.getLogger(HeapFileApiController.class);

    private final HeapDumpAnalyzerService analyzerService;
    private final CoreDumpAnalyzerService coreDumpService;
    private final com.heapdump.analyzer.service.GcLogAnalyzerService gcLogService;

    /**
     * 스레드 덤프 저장소(2026-11-26) — 선택 주입. 생성자를 바꾸지 않은 것은 이 컨트롤러를 3-인자로 직접 만드는 테스트가 여럿이라서다.
     * null 이면 스레드 덤프 분류는 거부된다.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.heapdump.analyzer.service.ThreadDumpAnalyzerService threadDumpService;

    public HeapFileApiController(HeapDumpAnalyzerService analyzerService,
                                 CoreDumpAnalyzerService coreDumpService,
                                 com.heapdump.analyzer.service.GcLogAnalyzerService gcLogService) {
        this.analyzerService = analyzerService;
        this.coreDumpService = coreDumpService;
        this.gcLogService = gcLogService;
    }

    @PostMapping("/api/files/bulk-delete")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> bulkDeleteFiles(@RequestBody Map<String, Object> body,
                                                               Authentication authentication) {
        @SuppressWarnings("unchecked")
        List<String> filenames = (List<String>) body.getOrDefault("filenames", Collections.emptyList());
        String who = AuthUtil.who(authentication);
        logger.info("[BulkDeleteFile] action=bulk-delete requested={} by={}", filenames.size(), who);
        int success = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (String raw : filenames) {
            try {
                String safe = FilenameValidator.validate(raw);
                analyzerService.deleteFile(safe);
                logger.info("[BulkDeleteFile] action=delete file={} by={}", safe, who);
                success++;
            } catch (Exception e) {
                failed++;
                errors.add(raw + ": " + e.getMessage());
                logger.warn("[BulkDeleteFile] action=delete FAILED file='{}' by={} — {}", raw, who, e.getMessage());
            }
        }
        logger.info("[BulkDeleteFile] action=bulk-delete success={} failed={} by={}", success, failed, who);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", success);
        resp.put("failed", failed);
        resp.put("errors", errors);
        return ResponseEntity.ok(resp);
    }

    /**
     * XHR 큐 업로더용 JSON API. 성공 200 + {status:"ok", filename, size},
     * 실패 4xx/5xx + {status:"error", message}. CSRF 면제(/api/**).
     */
    @PostMapping("/api/upload")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> uploadFileApi(@RequestParam("file") MultipartFile file,
                                                             @RequestParam(value = "dupContentOf", required = false) String dupContentOf,
                                                             Authentication authentication) {
        String originalName = file.getOriginalFilename();
        Map<String, Object> resp = new LinkedHashMap<>();
        String who = authentication != null ? authentication.getName() : "unknown";
        logger.info("[Upload API] Request: filename={}, size={}, by={}", originalName, FormatUtils.formatBytes(file.getSize()), who);
        // 중복 내용 경고 모달에서 [업로드] 를 누른 경우 — 클라이언트만 아는 결정이라 여기서 남기지 않으면 흔적이 없다
        if (dupContentOf != null && !dupContentOf.isBlank()) {
            logger.warn("[Upload API] Proceeding despite duplicate content: '{}' has same content as existing '{}' (user ignored warning), by={}",
                    originalName, new File(dupContentOf).getName(), who);
        }
        try {
            if (file.isEmpty()) {
                resp.put("status", "error");
                resp.put("message", "파일이 비어있습니다.");
                return ResponseEntity.badRequest().body(resp);
            }
            String filename = analyzerService.uploadFile(file);
            String uploadedBy = authentication != null ? authentication.getName() : null;
            analyzerService.saveUploadRecord(filename, file.getSize(), uploadedBy);
            resp.put("status", "ok");
            resp.put("filename", filename);
            resp.put("size", file.getSize());
            logger.info("[Upload API] Success: {} (by={})", filename, who);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            logger.warn("[Upload API] Validation failed for '{}' by={}: {}", originalName, who, e.getMessage());
            resp.put("status", "error");
            resp.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        } catch (IOException e) {
            logger.error("[Upload API] IO error for '{}' by={}: {}", originalName, who, e.getMessage(), e);
            resp.put("status", "error");
            resp.put("message", "Upload failed: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    @PostMapping("/api/upload/check")
    @ResponseBody
    public ResponseEntity<Map<String, String>> checkUploadDuplicate(@RequestBody Map<String, Object> request,
                                                                    Authentication authentication) {
        String filename = (String) request.get("filename");
        Number fileSizeNum = (Number) request.get("fileSize");
        String partialHash = (String) request.get("partialHash");

        if (filename == null || fileSizeNum == null || partialHash == null) {
            Map<String, String> err = new LinkedHashMap<>();
            err.put("status", "OK");
            return ResponseEntity.ok(err);
        }

        filename = new File(filename).getName(); // path traversal 방지
        long fileSize = fileSizeNum.longValue();
        Map<String, String> result = analyzerService.checkDuplicate(filename, fileSize, partialHash);
        String status = result.get("status");
        String who = authentication != null ? authentication.getName() : "unknown";
        if ("DUPLICATE_NAME".equals(status)) {
            // 동일 이름은 정책상 차단 — 클라이언트가 이 파일을 건너뛰고 '업로드 차단' 모달만 띄운다(자동 이름 변경·덮어쓰기 없음)
            logger.warn("[Upload Check] Duplicate name: '{}' already exists ({}) — upload blocked, by={}",
                    filename, FormatUtils.formatBytes(fileSize), who);
        } else if ("DUPLICATE_CONTENT".equals(status)) {
            // 사용자가 [건너뛰기]/[업로드] 를 고른다 — [업로드] 면 /api/upload 의 dupContentOf 로 한 번 더 남는다
            logger.info("[Upload Check] Duplicate content: '{}' ({}) has same content as existing '{}' — awaiting user choice, by={}",
                    filename, FormatUtils.formatBytes(fileSize), result.get("existingFilename"), who);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/files/{filename:.+}/classify")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> classifyFile(
            @PathVariable String filename,
            @RequestBody Map<String, String> body,
            Authentication authentication) {
        filename = FilenameValidator.validateSafe(filename);
        String fileType = body.get("fileType");
        Set<String> allowed = Set.of("core", "exec", "heapdump", "others", "gclog", "threaddump");
        if (fileType == null || !allowed.contains(fileType)) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", "유효하지 않은 파일 유형입니다. (core|exec|heapdump|others|gclog|threaddump)");
            return ResponseEntity.badRequest().body(err);
        }
        String who0 = com.heapdump.analyzer.util.AuthUtil.who(authentication);
        // GC 로그·스레드 덤프는 라벨이 아니라 '저장소' 가 다르다 — 분석기가 자기 저장소의 파일만 읽으므로 분류를 바꾸면 파일을 옮긴다.
        // 규칙(2026-11-26): 대상 저장소가 편입(형식 검사 → 이동 → 등록)하고 원 저장소는 기록만 정리한다.
        //   형식 검사가 이동보다 먼저라 거부되면 파일은 제자리에 있다. 서비스끼리 주입하면 기동 순환이라 조율은 여기서 한다.
        File heapFile = new File(analyzerService.heapDumpFilesDirectory(), filename);
        File coreFile = new File(coreDumpService.dumpFilesDir(), filename);
        File gcFile = gcLogService.fileOf(filename);
        File tdFile = threadDumpService == null ? null : threadDumpService.fileOf(filename);
        boolean inHeap = heapFile.isFile(), inCore = coreFile.isFile(), inGc = gcFile.isFile(), inTd = tdFile != null && tdFile.isFile();
        try {
            if ("gclog".equals(fileType) || "threaddump".equals(fileType)) {
                boolean toGc = "gclog".equals(fileType);
                String targetName = toGc ? "GC 로그" : "스레드 덤프";
                if (!toGc && threadDumpService == null) return classifyError(400, "스레드 덤프 저장소를 사용할 수 없습니다.");
                // 출처 — 화면이 보낸 현재 유형(from)을 우선하고, 없으면 파일이 '정확히 한 저장소' 에만 있어야 한다
                String from = sourceStore(body.get("from"), inHeap, inCore, inGc, inTd, toGc ? "gclog" : "threaddump");
                if (from == null) {
                    int n = (inHeap ? 1 : 0) + (inCore ? 1 : 0) + (inGc ? 1 : 0) + (inTd ? 1 : 0);
                    if (n == 0) return classifyError(404, "파일을 찾을 수 없습니다: " + filename);
                    if ((toGc ? inGc : inTd) && n == 1) return classifyOk(filename, fileType, false);   // 이미 그 유형
                    return classifyError(409, "같은 이름의 파일이 여러 저장소에 있어 어느 것을 옮길지 정할 수 없습니다: " + filename);
                }
                // 옮기면 원 저장소의 분석 결과가 사라진다 — 힙·코어는 결과가 있으면 막고(현행), 분석 중은 어디든 막는다
                String blocked = switch (from) {
                    case "heap" -> analyzerService.findHistoryEntity(filename).map(h -> h.getStatus()).filter(st -> "SUCCESS".equals(st) || "ANALYZING".equals(st)).orElse(null);
                    case "core" -> coreDumpService.getEntity(filename).map(c -> c.getStatus()).filter(st -> "SUCCESS".equals(st) || "ANALYZING".equals(st)).orElse(null);
                    case "gclog" -> gcLogService.isAnalyzing(filename) ? "ANALYZING" : null;
                    default -> threadDumpService.isAnalyzing(filename) ? "ANALYZING" : null;
                };
                if (blocked != null) {
                    if ("ANALYZING".equals(blocked)) return classifyError("heap".equals(from) || "core".equals(from) ? 400 : 409, "분석이 진행 중인 파일은 분류를 바꿀 수 없습니다.");
                    return classifyError(400, ("heap".equals(from) ? "힙 덤프" : "코어 덤프") + " 분석 결과가 있는 파일은 " + targetName
                            + "로 분류할 수 없습니다. 분석 기록을 먼저 삭제하세요.");
                }
                File src = switch (from) { case "heap" -> heapFile; case "core" -> coreFile; case "gclog" -> gcFile; default -> tdFile; };
                String serverName = "heap".equals(from) ? analyzerService.resolveServerNameByFilename(filename) : null;
                if (toGc) gcLogService.adoptFile(src, who0, serverName);
                else threadDumpService.adoptFile(src, who0, serverName);
                // 원래 저장소에 남은 기록·라벨 정리 — 파일은 이미 옮겼으므로 실패해도 분류 자체는 성공으로 둔다(WARN)
                try {
                    switch (from) {
                        case "heap" -> analyzerService.deleteHistoryRecordOnly(filename);   // deleteHistory 는 이름 접두로 형제 파일까지 지운다 — 쓰지 않는다
                        case "core" -> coreDumpService.deleteHistoryOnly(filename);
                        case "gclog" -> gcLogService.forgetMovedFile(filename, who0);
                        default -> threadDumpService.forgetMovedFile(filename, who0);
                    }
                    analyzerService.removeFileClassification(filename);
                } catch (Exception cleanup) {
                    logger.warn("[ClassifyFile] 원래 저장소 기록 정리 실패 file={} from={} — {}", filename, from, cleanup.getMessage());
                }
                logger.info("[ClassifyFile] action=classify file={} type={} moved-from={} by={}", filename, fileType, from, who0);
                return classifyOk(filename, fileType, true);
            }
            if (inGc && !inHeap && !inCore) {
                // GC 로그 → 다른 유형: 힙덤프 저장소로 내보낸 뒤 기존 라벨 규칙을 그대로 적용한다
                gcLogService.releaseFile(filename, analyzerService.heapDumpFilesDirectory(), who0);
                analyzerService.saveFileClassification(filename, fileType);
                logger.info("[ClassifyFile] action=classify file={} type={} moved-from=gclog by={}", filename, fileType, who0);
                return classifyOk(filename, fileType, true);
            }
            if (inTd && !inHeap && !inCore) {
                // 스레드 덤프 → 다른 유형: 같은 방식
                threadDumpService.releaseFile(filename, analyzerService.heapDumpFilesDirectory(), who0);
                analyzerService.saveFileClassification(filename, fileType);
                logger.info("[ClassifyFile] action=classify file={} type={} moved-from=threaddump by={}", filename, fileType, who0);
                return classifyOk(filename, fileType, true);
            }
        } catch (IllegalStateException e) {
            logger.warn("[ClassifyFile] action=classify-rejected file={} type={} reason=CONFLICT by={} — {}", filename, fileType, who0, e.getMessage());
            return classifyError(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.warn("[ClassifyFile] action=classify-rejected file={} type={} reason=INVALID by={} — {}", filename, fileType, who0, e.getMessage());
            return classifyError(400, e.getMessage());
        } catch (IOException e) {
            logger.error("[ClassifyFile] 파일 이동 실패 file={} type={} by={} — {}", filename, fileType, who0, e.getMessage());
            return classifyError(500, "파일을 옮기지 못했습니다: " + e.getMessage());
        }
        try {
            String who = who0;
            analyzerService.saveFileClassification(filename, fileType);
            logger.info("[ClassifyFile] action=classify file={} type={} by={}", filename, fileType, who);
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "ok");
            resp.put("filename", filename);
            resp.put("fileType", fileType);
            return ResponseEntity.ok(resp);
        } catch (IOException e) {
            logger.error("[ClassifyFile] Failed for '{}' type={} by={}: {}", filename, fileType, who0, e.getMessage());
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", "분류 저장 실패: " + e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    /**
     * 옮길 파일이 있는 저장소(heap·core·gclog·threaddump). 화면이 보낸 현재 유형이 실제 파일 위치와 맞으면 그것을,
     * 아니면 대상이 아닌 저장소 중 파일이 있는 곳이 하나뿐일 때 그것을 돌려준다. 정할 수 없으면 null.
     */
    static String sourceStore(String fromHint, boolean inHeap, boolean inCore, boolean inGc, boolean inTd, String target) {
        String hinted = fromHint == null ? null : switch (fromHint) {
            case "gclog" -> inGc ? "gclog" : null;
            case "threaddump" -> inTd ? "threaddump" : null;
            case "core", "coredump", "exec", "coreexec" -> inCore ? "core" : (inHeap ? "heap" : null);   // 확장자 없는 파일의 core 라벨은 힙 저장소에 있다
            case "heapdump", "others" -> inHeap ? "heap" : null;
            default -> null;
        };
        if (hinted != null && !hinted.equals(target)) return hinted;
        String only = null;
        int n = 0;
        if (inHeap && !"heap".equals(target)) { only = "heap"; n++; }
        if (inCore && !"core".equals(target)) { only = "core"; n++; }
        if (inGc && !"gclog".equals(target)) { only = "gclog"; n++; }
        if (inTd && !"threaddump".equals(target)) { only = "threaddump"; n++; }
        boolean alreadyThere = "gclog".equals(target) ? inGc : inTd;
        return n == 1 && !alreadyThere ? only : null;
    }

    private static ResponseEntity<Map<String, Object>> classifyOk(String filename, String fileType, boolean moved) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "ok");
        resp.put("filename", filename);
        resp.put("fileType", fileType);
        resp.put("moved", moved);
        return ResponseEntity.ok(resp);
    }

    /** files.html 의 submitClassify 는 {status,message} 를 읽는다 — 전역 핸들러의 {success,code,error} 로 새지 않게 여기서 만든다. */
    private static ResponseEntity<Map<String, Object>> classifyError(int status, String message) {
        Map<String, Object> err = new HashMap<>();
        err.put("status", "error");
        err.put("message", message);
        return ResponseEntity.status(status).body(err);
    }

    @PostMapping("/api/files/{filename:.+}/pair")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> pairCoreExec(
            @PathVariable String filename,
            @RequestBody Map<String, String> body,
            Authentication authentication) {
        filename = FilenameValidator.validateSafe(filename);
        String execFilename = body.get("execFilename");
        String who = authentication != null ? authentication.getName() : "unknown";
        Map<String, Object> resp = new HashMap<>();
        try {
            if (execFilename == null || execFilename.trim().isEmpty()) {
                removeExecCopyFromCoreDirIfNeeded(filename);
                analyzerService.removeCoreExecPairing(filename);
                logger.info("[FilePair] action=unpair core={} by={}", filename, who);
                resp.put("status", "ok");
                resp.put("action", "unpaired");
            } else {
                execFilename = FilenameValidator.validateSafe(execFilename.trim());
                analyzerService.saveCoreExecPairing(filename, execFilename);
                logger.info("[FilePair] action=pair core={} exec={} by={}", filename, execFilename, who);
                // 코어파일이 코어덤프 디렉터리에 있고, 실행파일이 힙덤프 디렉터리에만 있으면 복사
                copyExecToCoreDirIfNeeded(filename, execFilename);
                resp.put("status", "ok");
                resp.put("action", "paired");
                resp.put("execFilename", execFilename);
            }
            return ResponseEntity.ok(resp);
        } catch (IOException e) {
            logger.error("[FilePair] Failed for '{}' by={}: {}", filename, AuthUtil.who(authentication), e.getMessage());
            resp.put("status", "error");
            resp.put("message", "페어링 저장 실패: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    @DeleteMapping("/api/files/{filename:.+}/pair")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> unpairCoreExec(
            @PathVariable String filename,
            Authentication authentication) {
        filename = FilenameValidator.validateSafe(filename);
        String who = authentication != null ? authentication.getName() : "unknown";
        Map<String, Object> resp = new HashMap<>();
        try {
            removeExecCopyFromCoreDirIfNeeded(filename);
            analyzerService.removeCoreExecPairing(filename);
            logger.info("[FilePair] action=unpair core={} by={}", filename, who);
            resp.put("status", "ok");
            return ResponseEntity.ok(resp);
        } catch (IOException e) {
            logger.error("[FilePair] Unpair failed for '{}' by={}: {}", filename, who, e.getMessage());
            resp.put("status", "error");
            resp.put("message", "페어링 해제 실패: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    /**
     * 코어파일이 코어덤프 디렉터리에 있고 실행파일이 힙덤프 디렉터리에만 존재하면
     * 코어덤프 디렉터리로 복사한다. GDB 분석 및 getExecFilename() 탐색이 코어덤프 디렉터리를
     * 기준으로 동작하기 때문에 복사가 필요하다.
     */
    private void copyExecToCoreDirIfNeeded(String coreFilename, String execFilename) {
        File coreInCoreDir = new File(coreDumpService.dumpFilesDir(), coreFilename);
        if (!coreInCoreDir.exists()) return; // 힙덤프 디렉터리의 "core" 타입 — 복사 불필요
        File execInCoreDir = new File(coreDumpService.dumpFilesDir(), execFilename);
        if (execInCoreDir.exists()) return;  // 이미 코어덤프 디렉터리에 있음
        try {
            File execInHeapDir = analyzerService.getFile(execFilename);
            Files.copy(execInHeapDir.toPath(), execInCoreDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("[FilePair] exec 파일 코어덤프 디렉터리로 복사 완료: {} → {}", execInHeapDir, execInCoreDir);
        } catch (IOException e) {
            logger.warn("[FilePair] exec 파일 복사 실패 (힙덤프 디렉터리에 없음): {} — {}", execFilename, e.getMessage());
        }
    }

    /**
     * 페어링 해제 시, exec 파일이 힙덤프 디렉터리에 원본이 있고
     * 코어덤프 디렉터리에 복사본만 있는 경우 복사본을 삭제한다.
     * 코어덤프 디렉터리에만 존재하는 파일(독립 업로드)은 삭제하지 않는다.
     */
    private void removeExecCopyFromCoreDirIfNeeded(String coreFilename) {
        try {
            String execFn = coreDumpService.getExecFilename(coreFilename);
            if (execFn == null) return;
            File execInCoreDir = new File(coreDumpService.dumpFilesDir(), execFn);
            if (!execInCoreDir.exists()) return;
            File execInHeapDir = analyzerService.getFile(execFn);
            if (execInHeapDir != null && execInHeapDir.exists()) {
                execInCoreDir.delete();
                logger.info("[FilePair] 페어링 해제: exec 복사본 삭제 {}", execFn);
            }
        } catch (Exception e) {
            logger.warn("[FilePair] exec 복사본 삭제 실패: {}", e.getMessage());
        }
    }

    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename, Authentication authentication) {
        filename = FilenameValidator.validate(filename);
        String who = AuthUtil.who(authentication);
        try {
            File file = analyzerService.getFile(filename);
            logger.info("[Download] action=download file={} size={} by={}", file.getName(), FormatUtils.formatBytes(file.length()), who);
            // 파일명은 반드시 HttpHeaderValues 경유 — 한글을 그대로 넣으면 Tomcat 이 헤더를 지운다(함정 71)
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, HttpHeaderValues.attachment(file.getName()))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(file.length())
                    .body(new FileSystemResource(file));
        } catch (IOException e) {
            logger.warn("[Download] action=download FAILED file={} by={} — {}", filename, who, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
