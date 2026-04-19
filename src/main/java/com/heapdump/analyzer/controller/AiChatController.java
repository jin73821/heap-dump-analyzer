package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.AiChatMessage;
import com.heapdump.analyzer.model.entity.AiChatSession;
import com.heapdump.analyzer.repository.AiChatMessageRepository;
import com.heapdump.analyzer.repository.AiChatSessionRepository;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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
    private final HeapDumpAnalyzerService analyzerService;
    private final com.heapdump.analyzer.config.HeapDumpConfig config;

    public AiChatController(AiChatSessionRepository sessionRepo,
                            AiChatMessageRepository messageRepo,
                            HeapDumpAnalyzerService analyzerService,
                            com.heapdump.analyzer.config.HeapDumpConfig config) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.analyzerService = analyzerService;
        this.config = config;
    }

    // ── 페이지 라우팅 ────────────────────────────────────────────

    @GetMapping("/ai-chat")
    public String aiChatPage() {
        return "ai-chat";
    }

    // ── 세션 CRUD API ────────────────────────────────────────────

    /** 현재 사용자의 전체 세션 목록 (최신순) */
    @GetMapping("/api/ai-chat/sessions")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> listSessions(
            Principal principal,
            @RequestParam(required = false) String filename) {
        String username = principal.getName();
        List<AiChatSession> sessions;
        if (filename != null && !filename.isEmpty()) {
            sessions = sessionRepo.findByUsernameAndFilenameOrderByUpdatedAtDesc(username, filename);
        } else {
            sessions = sessionRepo.findByUsernameOrderByUpdatedAtDesc(username);
        }
        List<Map<String, Object>> result = sessions.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("title", s.getTitle());
            m.put("filename", s.getFilename());
            m.put("model", s.getModel());
            m.put("messageCount", s.getMessageCount());
            m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
            m.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
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
        session.setTitle(title != null && !title.isEmpty() ? title : "새 채팅");
        session.setModel(analyzerService.getLlmModel());
        session.setMessageCount(0);
        sessionRepo.save(session);

        logger.info("[AI-Chat] 세션 생성 — id={}, user={}, file='{}'", session.getId(), username, filename);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("sessionId", session.getId());
        res.put("title", session.getTitle());
        return ResponseEntity.ok(res);
    }

    /** 세션 삭제 */
    @DeleteMapping("/api/ai-chat/sessions/{id}")
    @ResponseBody
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> deleteSession(
            Principal principal,
            @PathVariable Long id) {
        String username = principal.getName();
        Optional<AiChatSession> opt = sessionRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getUsername().equals(username)) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "세션을 찾을 수 없습니다."));
        }
        messageRepo.deleteBySessionId(id);
        sessionRepo.deleteById(id);
        logger.info("[AI-Chat] 세션 삭제 — id={}, user={}", id, username);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 세션 제목 수정 */
    @PutMapping("/api/ai-chat/sessions/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateSession(
            Principal principal,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String username = principal.getName();
        Optional<AiChatSession> opt = sessionRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getUsername().equals(username)) {
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

    // ── 메시지 CRUD API ──────────────────────────────────────────

    /** 세션의 메시지 목록 조회 */
    @GetMapping("/api/ai-chat/sessions/{sessionId}/messages")
    @ResponseBody
    public ResponseEntity<?> getMessages(
            Principal principal,
            @PathVariable Long sessionId) {
        String username = principal.getName();
        Optional<AiChatSession> opt = sessionRepo.findById(sessionId);
        if (opt.isEmpty() || !opt.get().getUsername().equals(username)) {
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
            Principal principal,
            @PathVariable Long sessionId,
            @RequestBody Map<String, String> body) {
        String username = principal.getName();
        Optional<AiChatSession> opt = sessionRepo.findById(sessionId);
        if (opt.isEmpty() || !opt.get().getUsername().equals(username)) {
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
            Principal principal,
            @PathVariable Long sessionId,
            @RequestBody Map<String, Object> body) {

        String username = principal.getName();
        SseEmitter emitter = new SseEmitter(config.getSseEmitterTimeoutMinutes() * 60L * 1000);

        Optional<AiChatSession> opt = sessionRepo.findById(sessionId);
        if (opt.isEmpty() || !opt.get().getUsername().equals(username)) {
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
}
