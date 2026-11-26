package com.heapdump.analyzer.util;

import com.heapdump.analyzer.service.llmguard.InputVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 채팅 SSE 이벤트의 수동 JSON 조립 공통화 (AiChatController / HeapAiApiController).
 * 이스케이프 규칙은 기존 인라인 코드와 동일하게 유지 — chunk 는 \\ \" \n \r \t,
 * error 는 \\ \" \n (기존 에러 경로가 \r\t 를 이스케이프하지 않던 동작 보존).
 */
public final class SseJson {

    private static final Logger logger = LoggerFactory.getLogger(SseJson.class);
    /** 관리자가 입력한 규칙명이 들어가는 페이로드는 수작업 조립 대신 Jackson 으로만 직렬화한다. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SseJson() {}

    /** {"text": chunk} — 스트리밍 청크 페이로드. */
    public static String chunk(String text) {
        String escaped = text == null ? "" : text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "{\"text\":\"" + escaped + "\"}";
    }

    /** {"errorCode": code, "error": msg} — 에러 페이로드. */
    public static String error(String code, String msg) {
        String escaped = msg == null ? "" : msg.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "{\"errorCode\":\"" + code + "\",\"error\":\"" + escaped + "\"}";
    }

    /** error 이벤트 전송 후 스트림 종료. 전송 실패(클라이언트 disconnect 등)는 무시. */
    public static void sendError(SseEmitter emitter, String code, String msg) {
        try {
            emitter.send(SseEmitter.event().name("error").data(error(code, msg)));
            emitter.complete();
        } catch (Exception e) {
            logger.debug("[SSE] error 이벤트 전송 실패(클라이언트 disconnect 추정) code={}: {}", code, e.toString());
        }
    }

    /**
     * {@code done} 페이로드 — {@code {"latencyMs":…, "saved":…, "outputGuard":{…}}}.
     * saved·outputGuard 는 null 이면 뺀다(갱신하지 않은 클라이언트는 latencyMs 만 본다).
     */
    public static String done(long latencyMs, Boolean saved, Map<String, Object> outputGuard) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("latencyMs", latencyMs);
        if (saved != null) m.put("saved", saved);
        if (outputGuard != null) m.put("outputGuard", outputGuard);
        return MAPPER.writeValueAsString(m);
    }

    /** 입력 점검 거부 페이로드 — {@code {"errorCode","error","guard":{…}}}. */
    public static String guard(InputVerdict v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("errorCode", v.errorCode());
        m.put("error", v.message());
        m.put("guard", v.guardPayload());
        return MAPPER.writeValueAsString(m);
    }

    /**
     * 입력 점검 거부(CONFIRM/BLOCK)를 {@code error} 이벤트로 보내고 스트림을 닫는다.
     * 갱신하지 않은 클라이언트에도 {@code error} 문구는 그대로 보인다.
     */
    public static void sendGuard(SseEmitter emitter, InputVerdict v) {
        try {
            emitter.send(SseEmitter.event().name("error").data(guard(v)));
            emitter.complete();
        } catch (Exception e) {
            logger.debug("[SSE] guard 이벤트 전송 실패(클라이언트 disconnect 추정) code={}: {}", v.errorCode(), e.toString());
        }
    }
}
