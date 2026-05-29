package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.AiChatMessage;
import com.heapdump.analyzer.model.entity.AiChatSession;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.repository.AiChatMessageRepository;
import com.heapdump.analyzer.repository.AiChatSessionRepository;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class AiChatController {

    private static final Logger logger = LoggerFactory.getLogger(AiChatController.class);

    private final AiChatSessionRepository sessionRepo;
    private final AiChatMessageRepository messageRepo;
    private final AnalysisHistoryRepository historyRepo;
    private final HeapDumpAnalyzerService analyzerService;
    private final com.heapdump.analyzer.config.HeapDumpConfig config;
    private final RagService ragService;

    public AiChatController(AiChatSessionRepository sessionRepo,
                            AiChatMessageRepository messageRepo,
                            AnalysisHistoryRepository historyRepo,
                            HeapDumpAnalyzerService analyzerService,
                            com.heapdump.analyzer.config.HeapDumpConfig config,
                            RagService ragService) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.historyRepo = historyRepo;
        this.analyzerService = analyzerService;
        this.config = config;
        this.ragService = ragService;
    }

    // ── 페이지 라우팅 ────────────────────────────────────────────

    @GetMapping("/ai-chat")
    public String aiChatPage(org.springframework.ui.Model model, Authentication authentication) {
        model.addAttribute("isAdmin", isAdmin(authentication));
        model.addAttribute("currentUser", authentication != null ? authentication.getName() : "");
        return "ai-chat";
    }

    // ── 세션 CRUD API ────────────────────────────────────────────

    /**
     * 세션 목록 조회 (최신순).
     * - 비-ADMIN: 본인 세션만 (username 파라미터 무시).
     * - ADMIN  : 모든 사용자 세션. user 파라미터 지정 시 해당 사용자만 필터링.
     * 응답에 username 필드 포함 — 프론트가 사용자 태그 표시.
     */
    @GetMapping("/api/ai-chat/sessions")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> listSessions(
            Authentication authentication,
            @RequestParam(required = false) String filename,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String q) {
        String currentUser = authentication.getName();
        boolean admin = isAdmin(authentication);

        // 파라미터 정규화: trim + empty → null. 분기 가독성 향상 + 검색 경로와 정책 통일.
        String qNorm = (q == null) ? null : q.trim();
        if (qNorm != null && qNorm.isEmpty()) qNorm = null;
        String filenameNorm = (filename == null || filename.isEmpty()) ? null : filename;
        String userNorm     = (user     == null || user.isEmpty())     ? null : user;
        // 비-ADMIN 은 user 파라미터를 무시하고 본인 username 강제 (기존 정책 유지)
        String effectiveUsername = admin ? userNorm : currentUser;

        List<AiChatSession> sessions;
        if (qNorm != null) {
            // LIKE wildcard escape: '|' 가 ESCAPE 문자 (Repository @Query 와 동일). 먼저 '|' 자체를 이중화한 뒤 %/_ escape.
            String escaped = qNorm.replace("|", "||")
                                  .replace("%", "|%")
                                  .replace("_", "|_");
            sessions = sessionRepo.searchSessions("%" + escaped + "%", effectiveUsername, filenameNorm);
            logger.debug("[AI-Chat] search q='{}' user={} file={} hits={}",
                qNorm, effectiveUsername, filenameNorm, sessions.size());
        } else if (admin) {
            // ADMIN: user 필터 우선, 그 다음 filename, 둘 다 있으면 username + filename
            if (effectiveUsername != null && filenameNorm != null) {
                sessions = sessionRepo.findByUsernameAndFilenameOrderByUpdatedAtDesc(effectiveUsername, filenameNorm);
            } else if (effectiveUsername != null) {
                sessions = sessionRepo.findByUsernameOrderByUpdatedAtDesc(effectiveUsername);
            } else if (filenameNorm != null) {
                sessions = sessionRepo.findByFilenameOrderByUpdatedAtDesc(filenameNorm);
            } else {
                sessions = sessionRepo.findAllByOrderByUpdatedAtDesc();
            }
        } else {
            // USER: 본인 것만
            if (filenameNorm != null) {
                sessions = sessionRepo.findByUsernameAndFilenameOrderByUpdatedAtDesc(currentUser, filenameNorm);
            } else {
                sessions = sessionRepo.findByUsernameOrderByUpdatedAtDesc(currentUser);
            }
        }

        // 분석 인스턴스(filename + analyzedAt) 매칭으로 분석 상태 판정
        // - currentAnalysis : 현재 분석과 동일 (정상 연결)
        // - previousAnalysis: filename은 존재하지만 analyzedAt이 다름 (재업로드/재분석된 이전 분석의 채팅)
        // - analysisDeleted : filename에 해당하는 분석이 아예 없음 (분석 기록 삭제됨)
        List<Map<String, Object>> result = sessions.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("title", s.getTitle());
            m.put("username", s.getUsername());
            m.put("filename", s.getFilename());
            m.put("analyzedAt", s.getAnalyzedAt() != null ? s.getAnalyzedAt().toString() : null);
            m.put("model", s.getModel());
            m.put("messageCount", s.getMessageCount());
            m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
            m.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);

            String state = "general"; // filename 없는 일반 채팅
            if (s.getFilename() != null && !s.getFilename().isEmpty()) {
                if (s.getAnalyzedAt() == null) {
                    // 마이그레이션 이전 데이터 — analysis_history에 filename row 존재 여부만 확인
                    state = historyRepo.existsByFilename(s.getFilename()) ? "currentAnalysis" : "analysisDeleted";
                } else if (historyRepo.existsByFilenameAndAnalyzedAt(s.getFilename(), s.getAnalyzedAt())) {
                    state = "currentAnalysis";
                } else if (historyRepo.existsByFilename(s.getFilename())) {
                    state = "previousAnalysis";
                } else {
                    state = "analysisDeleted";
                }
            }
            m.put("analysisState", state);
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /** 새 세션 생성 */
    @PostMapping("/api/ai-chat/sessions")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createSession(
            Principal principal,
            @RequestBody Map<String, String> body) {
        String username = principal.getName();
        String filename = body.get("filename");
        String title = body.get("title");

        AiChatSession session = new AiChatSession();
        session.setUsername(username);
        session.setFilename(filename != null && !filename.isEmpty() ? filename : null);
        // 분석에 연결된 채팅이라면 현재 AnalysisHistory의 analyzedAt을 스냅샷으로 저장.
        // 같은 파일명으로 분석이 새로 이뤄지면 analyzedAt이 갱신되므로, 이 세션은 자연히 "이전 분석"의 채팅으로 분리된다.
        if (session.getFilename() != null) {
            historyRepo.findByFilename(session.getFilename())
                    .ifPresent(h -> session.setAnalyzedAt(h.getAnalyzedAt()));
        }
        session.setTitle(title != null && !title.isEmpty() ? title : "새 채팅");
        session.setModel(analyzerService.getLlmModel());
        session.setMessageCount(0);
        sessionRepo.save(session);

        logger.info("[AI-Chat] 세션 생성 — id={}, user={}, file='{}', analyzedAt={}",
                session.getId(), username, filename, session.getAnalyzedAt());

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("sessionId", session.getId());
        res.put("title", session.getTitle());
        return ResponseEntity.ok(res);
    }

    /** 세션 삭제 — owner 또는 ADMIN */
    @DeleteMapping("/api/ai-chat/sessions/{id}")
    @ResponseBody
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> deleteSession(
            Authentication authentication,
            @PathVariable Long id) {
        String username = authentication.getName();
        Optional<AiChatSession> opt = sessionRepo.findById(id);
        if (opt.isEmpty() || !canAccess(opt.get(), authentication)) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "세션을 찾을 수 없습니다."));
        }
        messageRepo.deleteBySessionId(id);
        sessionRepo.deleteById(id);
        logger.info("[AI-Chat] 세션 삭제 — id={}, by={}, owner={}",
            id, username, opt.get().getUsername());
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 세션 제목 수정 — owner 또는 ADMIN */
    @PutMapping("/api/ai-chat/sessions/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateSession(
            Authentication authentication,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Optional<AiChatSession> opt = sessionRepo.findById(id);
        if (opt.isEmpty() || !canAccess(opt.get(), authentication)) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "세션을 찾을 수 없습니다."));
        }
        AiChatSession session = opt.get();
        String title = body.get("title");
        if (title != null && !title.isEmpty()) {
            session.setTitle(title);
            sessionRepo.save(session);
        }
        return ResponseEntity.ok(Map.of("success", true, "title", session.getTitle()));
    }

    /** ADMIN 전용: 채팅 작성자 username distinct 목록 (사용자 필터 셀렉트 옵션) */
    @GetMapping("/api/ai-chat/users")
    @ResponseBody
    public ResponseEntity<?> listChatUsers(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body(Map.of("success", false, "error", "ADMIN 권한이 필요합니다."));
        }
        return ResponseEntity.ok(sessionRepo.findDistinctUsernames());
    }

    // ── 메시지 CRUD API ──────────────────────────────────────────

    /** 세션의 메시지 목록 조회 — owner 또는 ADMIN */
    @GetMapping("/api/ai-chat/sessions/{sessionId}/messages")
    @ResponseBody
    public ResponseEntity<?> getMessages(
            Authentication authentication,
            @PathVariable Long sessionId) {
        Optional<AiChatSession> opt = sessionRepo.findById(sessionId);
        if (opt.isEmpty() || !canAccess(opt.get(), authentication)) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "세션을 찾을 수 없습니다."));
        }
        List<AiChatMessage> messages = messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<Map<String, Object>> result = messages.stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", m.getId());
            map.put("role", m.getRole());
            map.put("content", m.getContent());
            map.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /** 메시지 저장 (user 또는 assistant) */
    @PostMapping("/api/ai-chat/sessions/{sessionId}/messages")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveMessage(
            Authentication authentication,
            @PathVariable Long sessionId,
            @RequestBody Map<String, String> body) {
        Optional<AiChatSession> opt = sessionRepo.findById(sessionId);
        if (opt.isEmpty() || !canAccess(opt.get(), authentication)) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "세션을 찾을 수 없습니다."));
        }

        String role = body.get("role");
        String content = body.get("content");
        if (role == null || content == null || content.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "role/content 필수"));
        }

        AiChatMessage msg = new AiChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        messageRepo.save(msg);

        // 세션 업데이트: messageCount, title 자동 생성
        AiChatSession session = opt.get();
        session.setMessageCount(session.getMessageCount() + 1);
        if ("user".equals(role) && ("새 채팅".equals(session.getTitle()) || session.getTitle() == null)) {
            String autoTitle = content.length() > 40 ? content.substring(0, 40) + "..." : content;
            session.setTitle(autoTitle);
        }
        if (analyzerService.getLlmModel() != null) {
            session.setModel(analyzerService.getLlmModel());
        }
        sessionRepo.save(session);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("messageId", msg.getId());
        res.put("title", session.getTitle());
        return ResponseEntity.ok(res);
    }

    // ── 스트리밍 채팅 (세션 기반) ─────────────────────────────────

    @SuppressWarnings("unchecked")
    @PostMapping(value = "/api/ai-chat/sessions/{sessionId}/stream",
                 produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamChat(
            Authentication authentication,
            @PathVariable Long sessionId,
            @RequestBody Map<String, Object> body) {

        SseEmitter emitter = new SseEmitter(config.getSseEmitterTimeoutMinutes() * 60L * 1000);

        Optional<AiChatSession> opt = sessionRepo.findById(sessionId);
        if (opt.isEmpty() || !canAccess(opt.get(), authentication)) {
            try {
                emitter.send(SseEmitter.event().name("error")
                    .data("{\"errorCode\":\"NOT_FOUND\",\"error\":\"세션을 찾을 수 없습니다.\"}"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        String context = body.get("context") != null ? String.valueOf(body.get("context")) : "";

        if (messages == null || messages.isEmpty()) {
            try {
                emitter.send(SseEmitter.event().name("error")
                    .data("{\"errorCode\":\"EMPTY_MESSAGES\",\"error\":\"메시지가 비어있습니다.\"}"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        // 마지막 user 메시지 DB 저장
        Map<String, String> lastUserMsg = messages.get(messages.size() - 1);
        if ("user".equals(lastUserMsg.get("role"))) {
            try {
                AiChatMessage userMsg = new AiChatMessage();
                userMsg.setSessionId(sessionId);
                userMsg.setRole("user");
                userMsg.setContent(lastUserMsg.get("content"));
                AiChatMessage savedUser = messageRepo.save(userMsg);
                logger.info("[AI-Chat-Stream] User 메시지 저장 완료 — sessionId={}, msgId={}", sessionId, savedUser.getId());

                AiChatSession session = opt.get();
                session.setMessageCount(session.getMessageCount() + 1);
                if ("새 채팅".equals(session.getTitle()) || session.getTitle() == null) {
                    String c = lastUserMsg.get("content");
                    session.setTitle(c.length() > 40 ? c.substring(0, 40) + "..." : c);
                }
                sessionRepo.save(session);
            } catch (Exception e) {
                logger.error("[AI-Chat-Stream] User 메시지 저장 실패 — sessionId={}, error={}", sessionId, e.getMessage(), e);
            }
        }

        String systemPrompt = analyzerService.getLlmChatSystemPrompt();
        if (!context.trim().isEmpty()) {
            systemPrompt += "\n\n아래는 사용자가 현재 보고 있는 힙 덤프 분석 결과입니다. "
                + "이 데이터를 참고하여 질문에 답하세요:\n\n" + context;
        }
        // RAG 컨텍스트 주입 (활성화 시 마지막 사용자 메시지로 검색)
        if (lastUserMsg != null) {
            String ragContext = ragService.fetchContextForLlm(lastUserMsg.get("content"));
            if (!ragContext.isEmpty()) systemPrompt += ragContext;
        }
        // OOM 컨텍스트 주입 (세션에 바인딩된 분석에 OOM 스레드가 감지된 경우)
        String sessionFilename = opt.get().getFilename();
        if (sessionFilename != null && !sessionFilename.isEmpty()) {
            String oomSection = analyzerService.buildOomPromptSection(sessionFilename);
            if (!oomSection.isEmpty()) {
                systemPrompt += "\n\n" + oomSection;
                logger.info("[AI-Chat-Stream] OOM context injected for session {} ({}): {} char(s)",
                    sessionId, sessionFilename, oomSection.length());
            }
        }

        final String finalSystemPrompt = systemPrompt;
        final String model = analyzerService.getLlmModel();
        final Long sid = sessionId;

        new Thread(() -> {
            try {
                emitter.send(SseEmitter.event().name("start")
                    .data("{\"model\":\"" + (model != null ? model : "") + "\"}"));

                StringBuilder fullText = new StringBuilder();

                analyzerService.callLlmChatStream(messages, finalSystemPrompt,
                    chunk -> {
                        try {
                            fullText.append(chunk);
                            String escaped = chunk.replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                                .replace("\t", "\\t");
                            emitter.send(SseEmitter.event().name("chunk")
                                .data("{\"text\":\"" + escaped + "\"}"));
                        } catch (Exception e) {
                            logger.warn("[AI-Chat-Stream] Chunk 전송 실패 — sessionId={}", sid);
                        }
                    },
                    (ft, latencyMs) -> {
                        // assistant 메시지 DB 저장 (재시도 포함)
                        boolean saved = false;
                        Long savedMsgId = null;
                        for (int retry = 0; retry < 3 && !saved; retry++) {
                            try {
                                AiChatMessage assistantMsg = new AiChatMessage();
                                assistantMsg.setSessionId(sid);
                                assistantMsg.setRole("assistant");
                                assistantMsg.setContent(fullText.toString());
                                AiChatMessage result = messageRepo.save(assistantMsg);
                                savedMsgId = result.getId();
                                saved = true;
                            } catch (Exception e) {
                                logger.error("[AI-Chat-Stream] Assistant 메시지 저장 실패 (시도 {}/3) — sessionId={}, error={}",
                                    retry + 1, sid, e.getMessage());
                                if (retry < 2) {
                                    try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                                }
                            }
                        }

                        if (saved) {
                            logger.info("[AI-Chat-Stream] Assistant 메시지 저장 완료 — sessionId={}, msgId={}, length={}",
                                sid, savedMsgId, fullText.length());
                            try {
                                AiChatSession s = sessionRepo.findById(sid).orElse(null);
                                if (s != null) {
                                    s.setMessageCount(s.getMessageCount() + 1);
                                    if (model != null) s.setModel(model);
                                    sessionRepo.save(s);
                                }
                            } catch (Exception e) {
                                logger.warn("[AI-Chat-Stream] 세션 업데이트 실패 — sessionId={}", sid, e);
                            }
                        } else {
                            logger.error("[AI-Chat-Stream] Assistant 메시지 저장 최종 실패 — sessionId={}, contentLength={}", sid, fullText.length());
                        }

                        try {
                            emitter.send(SseEmitter.event().name("done")
                                .data("{\"latencyMs\":" + latencyMs + ",\"saved\":" + saved + "}"));
                            emitter.complete();
                        } catch (Exception e) {
                            logger.warn("[AI-Chat-Stream] SSE done 이벤트 전송 실패 — sessionId={}", sid);
                        }
                    },
                    (errorCode, errorMsg) -> {
                        logger.warn("[AI-Chat-Stream] LLM 에러 — sessionId={}, code={}, msg={}", sid, errorCode, errorMsg);
                        try {
                            String escapedMsg = errorMsg.replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n");
                            emitter.send(SseEmitter.event().name("error")
                                .data("{\"errorCode\":\"" + errorCode + "\",\"error\":\"" + escapedMsg + "\"}"));
                            emitter.complete();
                        } catch (Exception e) {
                            logger.warn("[AI-Chat-Stream] SSE error 이벤트 전송 실패 — sessionId={}", sid);
                        }
                    }
                );
            } catch (Exception e) {
                logger.error("[AI-Chat-Stream] 스트리밍 스레드 에러 — sessionId={}, error={}", sid, e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                        .data("{\"errorCode\":\"INTERNAL_ERROR\",\"error\":\"" + e.getMessage() + "\"}"));
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        }, "ai-chat-session-stream-" + System.currentTimeMillis()).start();

        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    // ── 권한 헬퍼 ───────────────────────────────────────────────

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) return false;
        for (GrantedAuthority a : authentication.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    /** ADMIN 이거나 세션 소유자(username 일치) 일 때만 true. */
    private boolean canAccess(AiChatSession session, Authentication authentication) {
        if (session == null || authentication == null) return false;
        if (isAdmin(authentication)) return true;
        return session.getUsername() != null && session.getUsername().equals(authentication.getName());
    }
}
