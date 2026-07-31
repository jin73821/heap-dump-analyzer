package com.heapdump.analyzer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 설정 복원 단계 격리 테스트 ({@code loadPersistedSettings} 의 L0 수정).
 *
 * <p>회귀 배경: 예전에는 JSON 파싱과 복원 로직이 하나의 try 로 묶여 있었고
 * {@code catch (Exception)} 이 무엇이 터지든 "깨진 JSON" 으로 간주해 settings.json 을
 * {@code .corrupted} 로 밀어낸 뒤 <b>LLM/RAG/2FA/비밀번호정책/원격 설정 전량을
 * 기본값으로 리셋</b>했다. AES 복호화 예외 하나로 전 설정이 날아가는 구조였다.
 *
 * <p>이제 복원 실패는 그룹 단위로 격리돼 예외가 전파되지 않고 실패 그룹명만 수집된다.
 */
class SettingsRestoreIsolationTest {

    @Test
    @DisplayName("한 단계가 예외를 던져도 전파되지 않고 실패 그룹으로만 수집된다")
    void failingStepIsIsolated() {
        List<String> failed = new ArrayList<>();

        assertDoesNotThrow(() -> HeapDumpAnalyzerService.applyStep(failed, "rag", () -> {
            throw new RuntimeException("AES 복호화 실패");
        }));

        assertIterableEquals(List.of("rag"), failed);
    }

    @Test
    @DisplayName("한 단계 실패가 이후 단계 실행을 막지 않는다 — 나머지 설정은 복원된다")
    void laterStepsStillRunAfterFailure() {
        List<String> failed = new ArrayList<>();
        List<String> executed = new ArrayList<>();

        HeapDumpAnalyzerService.applyStep(failed, "llm", () -> executed.add("llm"));
        HeapDumpAnalyzerService.applyStep(failed, "rag", () -> {
            throw new IllegalStateException("boom");
        });
        HeapDumpAnalyzerService.applyStep(failed, "twoFactor", () -> executed.add("twoFactor"));

        assertIterableEquals(List.of("llm", "twoFactor"), executed);
        assertIterableEquals(List.of("rag"), failed);
    }

    @Test
    @DisplayName("모두 성공하면 실패 목록이 비어 있다 — 이때만 properties 동기화가 실행된다")
    void allSuccessLeavesFailedEmpty() {
        List<String> failed = new ArrayList<>();

        HeapDumpAnalyzerService.applyStep(failed, "scalar", () -> { });
        HeapDumpAnalyzerService.applyStep(failed, "remote", () -> { });

        assertTrue(failed.isEmpty());
    }

    @Test
    @DisplayName("여러 단계가 실패하면 모두 수집된다")
    void multipleFailuresAreAllCollected() {
        List<String> failed = new ArrayList<>();

        HeapDumpAnalyzerService.applyStep(failed, "rag", () -> { throw new RuntimeException("a"); });
        HeapDumpAnalyzerService.applyStep(failed, "twoFactor", () -> { throw new RuntimeException("b"); });

        assertEquals(2, failed.size());
        assertIterableEquals(List.of("rag", "twoFactor"), failed);
    }
}
