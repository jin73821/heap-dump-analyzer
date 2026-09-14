package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.RagKnowledgeDoc;
import com.heapdump.analyzer.model.entity.RagLearningDoc;
import com.heapdump.analyzer.service.ChromaSearchService;
import com.heapdump.analyzer.service.RagCorpusService;
import com.heapdump.analyzer.service.RagCorpusService.Existing;
import com.heapdump.analyzer.service.RagCorpusService.FileResult;
import com.heapdump.analyzer.service.RagCorpusService.Rec;
import com.heapdump.analyzer.service.RagIndexRunner;
import com.heapdump.analyzer.util.RagZipCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RAG 지식(md) / 학습(csv) 코퍼스 API + 색인 실행.
 *
 * <p>⚠ <b>경로를 {@code /api/settings/rag/} 밖으로 옮기지 말 것.</b> 인가는 SecurityConfig 의
 * {@code POST /api/settings/**} → ADMIN 패턴과 CSRF {@code uri.startsWith("/api/settings/")} 가
 * 덮고 있다. 옮기면 조용히 무방비가 된다.
 *
 * <p>⚠ <b>변경은 전부 POST</b> — ADMIN 매처가 {@code HttpMethod.POST} 한정이라
 * {@code @DeleteMapping} 으로 만들면 일반 USER 가 삭제할 수 있다.
 *
 * <p>GET 은 {@code anyRequest().hasAnyRole(ADMIN,USER)} 로 떨어져 USER 도 조회할 수 있다(의도).
 */
@RestController
public class RagCorpusController {

    private static final Logger logger = LoggerFactory.getLogger(RagCorpusController.class);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 컨트롤러 캡이 유일한 방어선 — multipart 한도가 20GB 라 프레임워크 백스톱이 없다. */
    private static final int MAX_FILES = 50;
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;

    private final RagCorpusService corpus;
    private final ChromaSearchService chroma;
    private final RagIndexRunner indexRunner;

    public RagCorpusController(RagCorpusService corpus, ChromaSearchService chroma, RagIndexRunner indexRunner) {
        this.corpus = corpus;
        this.chroma = chroma;
        this.indexRunner = indexRunner;
    }

    private static String who(Authentication auth) { return auth != null ? auth.getName() : "unknown"; }

    // ── 예외 → JSON (컨트롤러 스코프) ─────────────────────────────
    // 종전에는 try/catch 가 한 곳도 없어 DB·ZIP·파싱 오류가 **Spring 기본 500** 으로 나갔다.
    // 그 본문은 {timestamp,status,error,path} 라 화면 코드가 찾는 `error`(한국어)가 없고,
    // 서버 로그에도 어느 사용자의 어떤 요청이었는지 남지 않았다.
    // ⚠ Exception 핸들러만 두면 IllegalArgumentException 까지 500 이 된다(컨트롤러 스코프가
    //   @ControllerAdvice 보다 우선). 400 계약을 지키려면 둘을 함께 선언해야 한다.

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException e,
                                                             jakarta.servlet.http.HttpServletRequest req,
                                                             Authentication auth) {
        logger.warn("[RagCorpus] action=rejected uri={} by={} reason={}", req.getRequestURI(), who(auth), e.getMessage());
        return bad(e.getMessage() == null ? "요청이 올바르지 않습니다" : e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e,
                                                                jakarta.servlet.http.HttpServletRequest req,
                                                                Authentication auth) {
        // ⚠ Spring 이 이미 상태 코드를 정해 둔 요청 오류(파라미터 누락·본문 파싱 실패·multipart 오류
        //    ·타입 불일치 …)까지 500 으로 만들면 안 된다 — Spring 6 의 그 예외들은 ErrorResponse 를
        //    구현하므로 상태 코드를 그대로 존중하고 본문만 우리 계약으로 바꾼다.
        //    (실제로 이 가드가 없어 "파일 없이 import" 가 400 → 500 으로 바뀌었다.)
        if (e instanceof org.springframework.web.ErrorResponse) {
            org.springframework.http.HttpStatusCode sc = ((org.springframework.web.ErrorResponse) e).getStatusCode();
            logger.warn("[RagCorpus] action=rejected uri={} by={} status={} type={} msg={}",
                    req.getRequestURI(), who(auth), sc.value(), e.getClass().getSimpleName(), e.getMessage());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", false);
            m.put("code", "BAD_REQUEST");
            m.put("error", "요청이 올바르지 않습니다: " + e.getMessage());
            return ResponseEntity.status(sc).body(m);
        }
        logger.error("[RagCorpus] action=error uri={} by={} type={} msg={}",
                req.getRequestURI(), who(auth), e.getClass().getSimpleName(), e.getMessage(), e);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("code", "RAG_CORPUS_ERROR");
        // 원인 유형까지만 노출한다 — 스택/SQL 은 로그에만 남긴다.
        m.put("error", "요청을 처리하지 못했습니다 (" + e.getClass().getSimpleName() + ")"
                + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(m);
    }

    // ── 색인 현황 ────────────────────────────────────────

    @GetMapping("/api/settings/rag/index-status")
    public ResponseEntity<Map<String, Object>> indexStatus() {
        Map<String, Object> res = new LinkedHashMap<>(chroma.sourceTypeStats());
        long learningRows = corpus.learning().count();
        long knowledgeRows = corpus.knowledge().count();
        res.put("dbLearningRows", learningRows);
        res.put("dbKnowledgeRows", knowledgeRows);

        // 관리 대상 소스는 DB 와 대조해 "아직 색인 안 됨 / 색인에만 남음" 을 계산한다.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) res.get("sources");
        if (sources != null) {
            for (Map<String, Object> s : sources) {
                String st = String.valueOf(s.get("sourceType"));
                long rows = "csv".equals(st) ? learningRows : ("user_doc".equals(st) ? knowledgeRows : -1);
                if (rows < 0) continue;
                int docs = s.get("docs") instanceof Number ? ((Number) s.get("docs")).intValue() : 0;
                s.put("managed", true);
                s.put("dbRows", rows);
                s.put("unindexed", Math.max(0, rows - docs));
                s.put("orphans", Math.max(0, docs - rows));
            }
        }
        res.put("checkedAt", java.time.LocalDateTime.now().format(DT));
        return ResponseEntity.ok(res);
    }

    // ── 목록 · 단건 ─────────────────────────────────────

    @GetMapping("/api/settings/rag/knowledge/docs")
    public ResponseEntity<Map<String, Object>> knowledgeDocs() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (RagKnowledgeDoc d : corpus.knowledge().findAllByOrderByOriginIdAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("originId", d.getOriginId());
            m.put("title", d.getTitle());
            m.put("category", d.getCategory());
            m.put("tags", d.getTags());
            m.put("source", d.getSource());
            m.put("severity", d.getSeverity());
            m.put("synthetic", d.isSynthetic());
            m.put("enabled", d.isEnabled());
            // ⚠ 본문은 목록에 싣지 않는다 — MEDIUMTEXT 를 목록마다 끌고 오면 안 된다.
            m.put("chars", d.getContent() == null ? 0 : d.getContent().length());
            m.put("updatedAt", d.getUpdatedAt() == null ? null : d.getUpdatedAt().format(DT));
            m.put("updatedBy", d.getUpdatedBy());
            items.add(m);
        }
        return ResponseEntity.ok(Map.of("success", true, "count", items.size(), "items", items));
    }

    @GetMapping("/api/settings/rag/knowledge/docs/{id}")
    public ResponseEntity<Map<String, Object>> knowledgeDoc(@PathVariable Long id) {
        return corpus.knowledge().findById(id)
                .<ResponseEntity<Map<String, Object>>>map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("success", true);
                    m.put("id", d.getId());
                    m.put("originId", d.getOriginId());
                    m.put("title", d.getTitle());
                    m.put("content", d.getContent());
                    return ResponseEntity.ok(m);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "문서를 찾을 수 없습니다")));
    }

    @GetMapping("/api/settings/rag/learning/docs")
    public ResponseEntity<Map<String, Object>> learningDocs() {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        int synthetic = 0;

        for (RagLearningDoc d : corpus.learning().findAllByOrderBySortOrderAscDocIdAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("docId", d.getDocId());
            m.put("category", d.getCategory());
            m.put("title", d.getTitle());
            m.put("tags", d.getTags());
            m.put("severity", d.getSeverity());
            m.put("synthetic", d.isSynthetic());
            m.put("docDate", d.getDocDate());
            m.put("chars", d.getContent() == null ? 0 : d.getContent().length());
            m.put("updatedAt", d.getUpdatedAt() == null ? null : d.getUpdatedAt().format(DT));
            items.add(m);
            byCategory.merge(blankTo(d.getCategory(), "(미분류)"), 1, Integer::sum);
            bySeverity.merge(blankTo(d.getSeverity(), "info"), 1, Integer::sum);
            if (d.isSynthetic()) synthetic++;
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("count", items.size());
        res.put("items", items);
        res.put("byCategory", byCategory);
        res.put("bySeverity", bySeverity);
        res.put("syntheticCount", synthetic);
        return ResponseEntity.ok(res);
    }

    // ── 내보내기 ─────────────────────────────────────────

    @GetMapping("/api/settings/rag/learning/export")
    public ResponseEntity<byte[]> exportLearning(Authentication auth) {
        byte[] body = corpus.exportLearningCsv().getBytes(StandardCharsets.UTF_8);
        String name = "rag-learning-" + RagCorpusService.timestamp() + ".csv";
        // 사내 학습 코퍼스 전량이 파일로 나가는 동작이다 — 감사 기록 없이 두지 않는다.
        logger.info("[RagCorpus] action=export kind=learning scope=all rows={} bytes={} by={}",
                corpus.learning().count(), body.length, who(auth));
        return download(body, name, new MediaType("text", "csv", StandardCharsets.UTF_8));
    }

    /** 1건이면 {@code .md}, 여러 건이면 {@code .zip}. import 도 zip 을 받으므로 왕복이 성립한다. */
    @GetMapping("/api/settings/rag/knowledge/export")
    public ResponseEntity<byte[]> exportKnowledge(@RequestParam(required = false) String ids,
                                                 Authentication auth) throws Exception {
        List<RagKnowledgeDoc> docs = new ArrayList<>();
        if (ids == null || ids.isBlank()) {
            docs.addAll(corpus.knowledge().findAllByOrderByOriginIdAsc());
        } else {
            for (String raw : ids.split(",")) {
                try { corpus.knowledge().findById(Long.parseLong(raw.trim())).ifPresent(docs::add); }
                catch (NumberFormatException e) { logger.debug("[RagCorpus] 숫자가 아닌 id 건너뜀: {}", raw); }
            }
        }
        String scope = (ids == null || ids.isBlank()) ? "all" : "selected";
        if (docs.isEmpty()) {
            logger.warn("[RagCorpus] action=export kind=knowledge scope={} 거부 — 대상 없음 by={}", scope, who(auth));
            // ⚠ 화면이 JSON 계약({success,error})으로 읽으므로 본문도 그 모양이어야 한다 —
            //    평문이면 "HTTP 404" 로만 보인다.
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                    .body("{\"success\":false,\"code\":\"NOT_FOUND\",\"error\":\"내보낼 문서가 없습니다\"}"
                            .getBytes(StandardCharsets.UTF_8));
        }
        if (docs.size() == 1) {
            RagKnowledgeDoc d = docs.get(0);
            byte[] body = corpus.exportKnowledgeMd(d).getBytes(StandardCharsets.UTF_8);
            logger.info("[RagCorpus] action=export kind=knowledge scope={} format=md docs=1 origin={} bytes={} by={}",
                    scope, d.getOriginId(), body.length, who(auth));
            return download(body, d.getOriginId() + ".md", new MediaType("text", "markdown", StandardCharsets.UTF_8));
        }
        Map<String, String> entries = new LinkedHashMap<>();
        for (RagKnowledgeDoc d : docs) entries.put(d.getOriginId() + ".md", corpus.exportKnowledgeMd(d));
        byte[] zip = RagZipCodec.zipMarkdown(entries);
        logger.info("[RagCorpus] action=export kind=knowledge scope={} format=zip docs={} bytes={} by={}",
                scope, docs.size(), zip.length, who(auth));
        return download(zip, "rag-knowledge-" + RagCorpusService.timestamp() + ".zip", MediaType.APPLICATION_OCTET_STREAM);
    }

    // ── 가져오기 (검사 = dryRun, 적용 = dryRun false — 같은 코드 경로) ──

    @PostMapping("/api/settings/rag/learning/import")
    public ResponseEntity<Map<String, Object>> importLearning(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(defaultValue = "skip") String onDuplicate,
            @RequestParam(defaultValue = "false") boolean dryRun,
            Authentication auth) {
        return doImport(files, onDuplicate, dryRun, auth, true);
    }

    @PostMapping("/api/settings/rag/knowledge/import")
    public ResponseEntity<Map<String, Object>> importKnowledge(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(defaultValue = "skip") String onDuplicate,
            @RequestParam(defaultValue = "false") boolean dryRun,
            Authentication auth) {
        return doImport(files, onDuplicate, dryRun, auth, false);
    }

    private ResponseEntity<Map<String, Object>> doImport(MultipartFile[] files, String onDuplicate,
                                                         boolean dryRun, Authentication auth, boolean learning) {
        String dup = "overwrite".equalsIgnoreCase(onDuplicate) ? "overwrite" : "skip";
        String kind = learning ? "learning" : "knowledge";
        if (files == null || files.length == 0) return badLogged(kind, "가져올 파일이 없습니다", auth);
        if (files.length > MAX_FILES) {
            return badLogged(kind, "파일이 너무 많습니다 (상한 " + MAX_FILES + "개)", auth);
        }

        long totalBytes = 0;
        List<FileResult> results = new ArrayList<>();
        List<File> temps = new ArrayList<>();
        try {
            for (MultipartFile mf : files) {
                String name = mf.getOriginalFilename() == null ? "unnamed" : new File(mf.getOriginalFilename()).getName();
                if (mf.getSize() > MAX_FILE_BYTES) {
                    return badLogged(kind, name + ": 파일이 너무 큽니다 (상한 5MB)", auth);
                }
                totalBytes += mf.getSize();
                if (totalBytes > MAX_TOTAL_BYTES) {
                    return badLogged(kind, "전체 크기가 너무 큽니다 (상한 20MB)", auth);
                }

                byte[] bytes = mf.getBytes();
                if (!learning && RagZipCodec.looksLikeZip(bytes)) {
                    // ⚠ ZipFile 은 seekable 소스가 필요하다 — 임시 파일로 먼저 내린다.
                    File tmp = Files.createTempFile("rag-import-", ".zip").toFile();
                    temps.add(tmp);
                    Files.write(tmp.toPath(), bytes);
                    try {
                        for (Map.Entry<String, String> e : RagZipCodec.extractMarkdown(tmp).entrySet()) {
                            results.add(corpus.parseKnowledgeFile(e.getKey(), e.getValue()));
                        }
                    } catch (Exception ze) {
                        FileResult fr = new FileResult();
                        fr.filename = name;
                        fr.ok = false;
                        fr.errors.add(Map.of("row", 0, "error", String.valueOf(ze.getMessage())));
                        results.add(fr);
                    }
                    continue;
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                results.add(learning ? corpus.parseLearningFile(name, text) : corpus.parseKnowledgeFile(name, text));
            }
        } catch (Exception e) {
            logger.error("[RagCorpus] action=import kind={} 파일 읽기 실패 by={} — {}", kind, who(auth), e.toString(), e);
            return bad("파일을 읽지 못했습니다: " + e.getMessage());
        } finally {
            for (File t : temps) { if (!t.delete()) logger.warn("[RagCorpus] 임시 파일 삭제 실패: {}", t); }
        }

        // 오류가 하나라도 있으면 아무것도 쓰지 않는다 (부분 적용 혼란 방지)
        boolean anyError = results.stream().anyMatch(r -> !r.ok);
        List<Rec> all = new ArrayList<>();
        for (FileResult r : results) all.addAll(r.records);
        int before = all.size();
        List<Rec> kept = RagCorpusService.dedupeIntra(all);
        int intraDup = before - kept.size();

        Existing ex = learning ? corpus.existingLearning(kept) : corpus.existingKnowledge(kept);
        RagCorpusService.classify(kept, ex);

        Map<String, Integer> applied = Map.of("inserted", 0, "updated", 0, "skipped", 0);
        if (!dryRun && !anyError && !kept.isEmpty()) {
            applied = learning ? corpus.applyLearning(kept, dup, who(auth))
                               : corpus.applyKnowledge(kept, dup, who(auth));
            logger.info("[RagCorpus] action=import kind={} onDuplicate={} files={} inserted={} updated={} skipped={} intraDup={} by={}",
                    kind, dup, files.length,
                    applied.get("inserted"), applied.get("updated"), applied.get("skipped"), intraDup, who(auth));
        }

        if (anyError) {
            // 400 으로 거부하면서 서버에 흔적이 없으면 "왜 안 되냐"는 문의를 재현으로만 풀어야 한다.
            long badFiles = results.stream().filter(r -> !r.ok).count();
            logger.warn("[RagCorpus] action=import kind={} rejected dryRun={} files={} badFiles={} by={}",
                    kind, dryRun, files.length, badFiles, who(auth));
        } else if (dryRun) {
            logger.info("[RagCorpus] action=import-check kind={} files={} parsed={} intraDup={} by={}",
                    kind, files.length, kept.size(), intraDup, who(auth));
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", !anyError);
        res.put("dryRun", dryRun);
        res.put("onDuplicate", dup);
        res.put("files", renderFiles(results));
        res.put("totals", totals(kept, intraDup, applied));
        if (anyError) res.put("error", "검증 실패 — 아무것도 반영하지 않았습니다");
        return anyError ? ResponseEntity.badRequest().body(res) : ResponseEntity.ok(res);
    }

    private List<Map<String, Object>> renderFiles(List<FileResult> results) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FileResult fr : results) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("filename", fr.filename);
            f.put("ok", fr.ok);
            f.put("docs", fr.records.size());
            // 조용히 고치지 않는다 — 무엇을 복구했는지 검사 화면에 그대로 보인다.
            f.put("shifted", fr.shiftedRows);
            f.put("syntheticInferred", fr.syntheticInferred);
            f.put("quoteFixed", fr.quoteFixed);
            List<Map<String, Object>> recs = new ArrayList<>();
            for (Rec r : fr.records) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", r.key);
                m.put("title", r.title);
                m.put("status", r.status);
                m.put("flags", r.flags);
                m.put("existingId", r.existingId);
                m.put("warnings", r.warnings);
                recs.add(m);
            }
            f.put("records", recs);
            f.put("errors", fr.errors);
            out.add(f);
        }
        return out;
    }

    private Map<String, Object> totals(List<Rec> kept, int intraDup, Map<String, Integer> applied) {
        int dupId = 0, dupContent = 0, dupTitle = 0, fresh = 0;
        for (Rec r : kept) {
            switch (r.status) {
                case "DUP_ID": dupId++; break;
                case "DUP_CONTENT": dupContent++; break;
                case "DUP_TITLE": dupTitle++; break;
                default: fresh++;
            }
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("parsed", kept.size());
        t.put("new", fresh);
        t.put("dupId", dupId);
        t.put("dupContent", dupContent);
        t.put("dupTitle", dupTitle);
        t.put("intraDup", intraDup);
        t.putAll(applied);
        return t;
    }

    // ── 삭제 · 사용 토글 (⚠ POST — DELETE 는 ADMIN 매처 밖이다) ──

    @PostMapping("/api/settings/rag/learning/delete")
    public ResponseEntity<Map<String, Object>> deleteLearning(@RequestBody Map<String, Object> body, Authentication auth) {
        List<Long> ids = longs(body.get("ids"));
        if (ids.isEmpty()) return badLogged("learning", "삭제할 항목을 선택하세요", auth);
        corpus.learning().deleteAllById(ids);
        logger.info("[RagCorpus] action=delete kind=learning count={} ids={} by={}", ids.size(), ids, who(auth));
        return ResponseEntity.ok(Map.of("success", true, "deleted", ids.size()));
    }

    @PostMapping("/api/settings/rag/knowledge/delete")
    public ResponseEntity<Map<String, Object>> deleteKnowledge(@RequestBody Map<String, Object> body, Authentication auth) {
        List<Long> ids = longs(body.get("ids"));
        if (ids.isEmpty()) return badLogged("knowledge", "삭제할 항목을 선택하세요", auth);
        corpus.knowledge().deleteAllById(ids);
        logger.info("[RagCorpus] action=delete kind=knowledge count={} ids={} by={}", ids.size(), ids, who(auth));
        return ResponseEntity.ok(Map.of("success", true, "deleted", ids.size()));
    }

    @PostMapping("/api/settings/rag/knowledge/enabled")
    public ResponseEntity<Map<String, Object>> toggleKnowledge(@RequestBody Map<String, Object> body, Authentication auth) {
        List<Long> ids = longs(body.get("ids"));
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        if (ids.isEmpty()) return badLogged("knowledge", "대상을 선택하세요", auth);
        int n = 0;
        for (Long id : ids) {
            RagKnowledgeDoc d = corpus.knowledge().findById(id).orElse(null);
            if (d == null) continue;
            d.setEnabled(enabled);
            d.setUpdatedBy(who(auth));
            corpus.knowledge().save(d);
            n++;
        }
        logger.info("[RagCorpus] action=enabled kind=knowledge enabled={} count={} by={}", enabled, n, who(auth));
        return ResponseEntity.ok(Map.of("success", true, "changed", n));
    }

    // ── 색인 실행 ────────────────────────────────────────

    @PostMapping("/api/settings/rag/index/run")
    public ResponseEntity<Map<String, Object>> runIndex(@RequestBody(required = false) Map<String, Object> body,
                                                        Authentication auth) {
        String sources = body == null ? null : String.valueOf(body.getOrDefault("sources", "csv,user_docs"));
        if (sources == null || "null".equals(sources)) sources = "csv,user_docs";
        boolean reset = body != null && Boolean.TRUE.equals(body.get("reset"));
        try {
            indexRunner.start(sources, reset, who(auth));
            return ResponseEntity.accepted().body(ok(indexRunner.status()));
        } catch (IllegalStateException busy) {
            Map<String, Object> res = ok(indexRunner.status());
            res.put("success", false);
            res.put("error", busy.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(res);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @GetMapping("/api/settings/rag/index/status")
    public ResponseEntity<Map<String, Object>> indexRunStatus() {
        return ResponseEntity.ok(ok(indexRunner.status()));
    }

    @PostMapping("/api/settings/rag/index/cancel")
    public ResponseEntity<Map<String, Object>> cancelIndex(Authentication auth) {
        boolean cancelled = indexRunner.cancel(who(auth));
        Map<String, Object> res = ok(indexRunner.status());
        res.put("cancelled", cancelled);
        if (!cancelled) res.put("error", "실행 중인 색인이 없습니다");
        return ResponseEntity.ok(res);
    }

    // ── 내부 ────────────────────────────────────────────

    private static Map<String, Object> ok(Map<String, Object> payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.putAll(payload);
        return m;
    }

    /** 400 거부를 서버 로그에도 남긴다 — 화면 문구만 있으면 사후 추적이 안 된다. */
    private ResponseEntity<Map<String, Object>> badLogged(String kind, String msg, Authentication auth) {
        logger.warn("[RagCorpus] action=rejected kind={} by={} reason={}", kind, who(auth), msg);
        return bad(msg);
    }

    private static ResponseEntity<Map<String, Object>> bad(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", msg);
        return ResponseEntity.badRequest().body(m);
    }

    private static List<Long> longs(Object raw) {
        List<Long> out = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                try { out.add(Long.parseLong(String.valueOf(o).trim())); }
                catch (NumberFormatException e) { logger.debug("[RagCorpus] 숫자가 아닌 id 건너뜀: {}", o); }
            }
        }
        return out;
    }

    /** 다운로드 공통 — charset 명시(함정 35) + ASCII/RFC 5987 이중 파일명 + no-store. */
    private static ResponseEntity<byte[]> download(byte[] body, String filename, MediaType type) {
        String ascii = filename.replaceAll("[^\\x20-\\x7E]", "_");
        String utf8 = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(type);
        h.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8);
        h.setCacheControl("no-store");
        h.add("X-Content-Type-Options", "nosniff");
        return new ResponseEntity<>(body, h, HttpStatus.OK);
    }

    private static String blankTo(String s, String d) {
        return (s == null || s.trim().isEmpty()) ? d : s.trim().toLowerCase(Locale.ROOT);
    }
}
