package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.util.AuthUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 파일 조작 감사 로그의 수행자(by=) 가드 (2026-11-11).
 *
 * <p>로그에 누가 했는지 남기려면 핸들러가 인증 정보를 받아야 한다 — 파라미터가 없으면 by= 를 쓸 방법 자체가 없다.
 * 파일을 바꾸는(POST/DELETE) 핸들러와 파일을 내려주는 다운로드 핸들러가 {@link Authentication}/{@link Principal} 을
 * 받는지 리플렉션으로 확인한다. 새 엔드포인트를 추가하며 빠뜨리면 여기서 걸린다.
 */
class FileOpAuditActorTest {

    /** 파일 업로드·삭제·분류·페어링·다운로드·전송을 다루는 컨트롤러. */
    private static final List<Class<?>> FILE_CONTROLLERS = List.of(
            HeapFileApiController.class, HeapDumpViewController.class, HeapHistoryApiController.class,
            HeapAnalysisApiController.class, CoreDumpApiController.class, GcLogApiController.class);

    /** 조회만 하는 GET 은 대상이 아니다 — 다운로드(원본 파일을 밖으로 내보냄)만 감사 대상. */
    private static final Set<String> DOWNLOAD_PATH_HINTS = Set.of("download", "print-pdf");

    /** 예외로 둘 핸들러("Class#method") — 추가할 때는 이유를 주석으로 남길 것. */
    private static final Set<String> EXEMPT = Set.of(
            "GcLogApiController#combinePreview");   // 이어붙일 순서 계산만 — 파일·DB 를 바꾸지 않는다(POST 는 본문 전달용)

    @Test
    @DisplayName("파일 변경·다운로드 핸들러는 모두 Authentication/Principal 을 받는다")
    void fileHandlersReceiveActor() {
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Class<?> c : FILE_CONTROLLERS) {
            for (Method m : c.getDeclaredMethods()) {
                if (!isAuditTarget(m)) continue;
                String key = c.getSimpleName() + "#" + m.getName();
                if (EXEMPT.contains(key)) continue;
                checked++;
                boolean hasActor = Arrays.stream(m.getParameterTypes()).anyMatch(Principal.class::isAssignableFrom);
                if (!hasActor) missing.add(key);
            }
        }
        assertTrue(checked >= 20, "검사 대상이 너무 적다 — 매핑 탐지가 깨졌을 수 있다: " + checked);
        assertTrue(missing.isEmpty(), "수행자(by=)를 기록할 수 없는 파일 조작 핸들러: " + missing);
    }

    private static boolean isAuditTarget(Method m) {
        if (m.isAnnotationPresent(PostMapping.class) || m.isAnnotationPresent(DeleteMapping.class)) return true;
        GetMapping g = m.getAnnotation(GetMapping.class);
        if (g == null) return false;
        List<String> paths = new ArrayList<>(Arrays.asList(g.value()));
        paths.addAll(Arrays.asList(g.path()));
        return paths.stream().anyMatch(p -> DOWNLOAD_PATH_HINTS.stream().anyMatch(p::contains));
    }

    @Test
    @DisplayName("AuthUtil.who — 이름, 없으면 unknown")
    void whoFallsBackToUnknown() {
        assertEquals("unknown", AuthUtil.who(null));
        assertEquals("alice", AuthUtil.who(new UsernamePasswordAuthenticationToken("alice", "x")));
        assertEquals("bob", AuthUtil.who(() -> "bob"));
        assertEquals("unknown", AuthUtil.who(() -> " "));
    }
}
