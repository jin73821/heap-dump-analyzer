package com.heapdump.analyzer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 채팅 SSE 이벤트의 수동 JSON 조립 공통화 (AiChatController / HeapAiApiController).
 * 이스케이프 규칙은 기존 인라인 코드와 동일하게 유지 — chunk 는 \\ \" \n \r \t,
 * error 는 \\ \" \n (기존 에러 경로가 \r\t 를 이스케이프하지 않던 동작 보존).
 */
public final class SseJson {

    private static final Logger logger = LoggerFactory.getLogger(SseJson.class);

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
}
