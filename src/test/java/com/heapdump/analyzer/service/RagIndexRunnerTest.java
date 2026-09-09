package com.heapdump.analyzer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 색인 실행기의 명령 조립·출력 파싱만 검증한다. <b>프로세스를 실제로 띄우지 않는다</b>
 * — 색인은 1~2분 걸리고 운영 컬렉션을 바꾸므로 테스트에서 돌릴 대상이 아니다.
 */
class RagIndexRunnerTest {

    @Test
    @DisplayName("명령은 인자 배열로 조립된다 (셸 문자열이 아니다)")
    void buildsArgArray() {
        List<String> cmd = RagIndexRunner.buildCommand("/opt/chroma/app/run-index.sh", "csv,user_docs", false);
        assertArrayEquals(new String[]{"bash", "/opt/chroma/app/run-index.sh", "--sources", "csv,user_docs"},
                cmd.toArray(new String[0]));
    }

    @Test
    @DisplayName("--reset 은 플래그로만 붙는다")
    void resetFlag() {
        assertEquals("--reset", RagIndexRunner.buildCommand("/s.sh", "all", true).get(4));
        assertEquals(4, RagIndexRunner.buildCommand("/s.sh", "all", false).size());
    }

    @Test
    @DisplayName("화이트리스트 밖 값은 거부한다 — 앱이 root 라 주입 피해가 무제한이다")
    void rejectsNonWhitelisted() {
        for (String bad : new String[]{"csv; rm -rf /", "all --reset", "../etc", "", "CSV", "leak_rules"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RagIndexRunner.buildCommand("/s.sh", bad, false), "허용하면 안 되는 값: " + bad);
        }
    }

    @Test
    @DisplayName("진행 라인을 파싱한다 (색인기가 비-TTY 에서 개행으로 찍는 형식)")
    void parsesProgress() {
        assertArrayEquals(new int[]{342, 834}, RagIndexRunner.parseProgress("  색인 342/834"));
        assertArrayEquals(new int[]{32, 84}, RagIndexRunner.parseProgress("색인  32/84"));
        assertNull(RagIndexRunner.parseProgress("소스 수집: ['csv']"));
        assertNull(RagIndexRunner.parseProgress(null));
    }

    @Test
    @DisplayName("완료 라인에서 색인 건수와 컬렉션 총계를 뽑는다")
    void parsesDone() {
        assertArrayEquals(new int[]{84, 834},
                RagIndexRunner.parseDone("완료: 84건 색인 → 컬렉션 총 834건"));
        assertNull(RagIndexRunner.parseDone("  색인 84/84"));
    }

    @Test
    @DisplayName("허용 목록은 관리 대상 조합만 담는다")
    void allowedSources() {
        assertEquals(java.util.Set.of("csv", "user_docs", "csv,user_docs", "all"), RagIndexRunner.ALLOWED_SOURCES);
    }
}
