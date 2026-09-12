package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.TargetServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 원격 전송 경로 가드 + scp 명령 조립 (2026-09-11).
 *
 * <p>{@code remotePath} 는 {@code /api/servers/{id}/transfer} 의 USER 권한 사용자 입력인데 종전 {@code buildScpCommand} 가
 * 이스케이프 없이 {@code bash -c} 문자열에 넣어 {@code $(…)} 가 로컬 sscuser 로 실행될 수 있었다.
 *
 * <p><b>회귀 이력:</b> 그 수정의 첫 배포본은 원격 경로를 {@code '"path"'} 로 감쌌는데, scp 가 따옴표가 글자로 남은 인자를 받아
 * OpenSSH 8.0 의 파일명 검사({@code fnmatch})에서 {@code protocol error: filename does not match request} 로 <b>모든 전송이
 * 실패</b>했다(aibank01d 실측). 골든 테스트는 문자열만 고정해 이를 못 잡았다 — 그래서 {@link #shellLayersAgreeOnFilename}
 * 이 로컬 셸 → 원격 셸 → scp 파일명 검사 3단계를 실제 bash/sh 로 흉내 내 "원격 셸이 푼 이름"과 "scp 가 기대하는 이름"이
 * 일치하는지 단언한다(SSH 불필요).
 */
class RemoteDumpPathGuardTest {

    private static TargetServer server() {
        TargetServer s = new TargetServer();
        s.setHost("10.0.0.5");
        s.setPort(22);
        s.setSshUser("sscuser");
        s.setDumpPath("/opt/dumps\n/data/heap dumps/");
        s.setCoreDumpPath("/var/crash");
        return s;
    }

    @Test
    @DisplayName("셸 메타문자·제어문자·상대경로·상위참조는 거부, 공백 경로는 허용")
    void validateRejectsShellMetaCharacters() {
        for (String bad : new String[]{"/d/$(id).hprof", "/d/`id`.hprof", "/d/a\".hprof", "/d/a\\b.hprof",
                "/d/a;rm.hprof", "/d/a|b", "/d/a&b", "/d/a\nb", "/d/a\0b", "relative.hprof", "/d/../etc/x",
                "", null, "/d/*.hprof", "/d/a>b"}) {
            assertThrows(IllegalArgumentException.class, () -> RemoteDumpService.validateRemotePath(bad), "허용되면 안 됨: " + bad);
        }
        assertDoesNotThrow(() -> RemoteDumpService.validateRemotePath("/data/heap dumps/java_pid12.hprof.gz"));
        assertDoesNotThrow(() -> RemoteDumpService.validateRemotePath("/opt/dumps/it's.hprof"));
    }

    @Test
    @DisplayName("heap/core 는 서버에 등록된 덤프 경로 하위여야 한다")
    void containmentFollowsConfiguredPaths() {
        TargetServer s = server();
        assertTrue(RemoteDumpService.isUnderConfiguredPaths(s, "/opt/dumps/a.hprof", "heap"));
        assertTrue(RemoteDumpService.isUnderConfiguredPaths(s, "/data/heap dumps/sub/a.hprof", "heap"), "끝 슬래시 경로도 정규화");
        assertFalse(RemoteDumpService.isUnderConfiguredPaths(s, "/opt/dumpsX/a.hprof", "heap"), "접두 문자열 일치가 아니라 디렉토리 하위");
        assertFalse(RemoteDumpService.isUnderConfiguredPaths(s, "/etc/passwd", "heap"));
        assertTrue(RemoteDumpService.isUnderConfiguredPaths(s, "/var/crash/core.1", "core"));
        assertFalse(RemoteDumpService.isUnderConfiguredPaths(s, "/opt/dumps/core.1", "core"), "코어는 코어 경로 기준");
    }

    @Test
    @DisplayName("scp 조립 골든 — 원격 층은 역슬래시 이스케이프, 로컬 대상은 단일 인용")
    void scpCommandGolden() {
        String cmd = RemoteDumpService.scpCommandString(server(), "/data/heap dumps/it's.hprof", "/tmp/heapdump_transfer_ab12_it's.hprof");
        assertEquals("scp -o StrictHostKeyChecking=no -o ConnectTimeout=10 -o BatchMode=yes -P 22"
                + " sscuser@10.0.0.5:\"/data/heap\\ dumps/it\\'s.hprof\""
                + " '/tmp/heapdump_transfer_ab12_it'\\''s.hprof'", cmd);
        assertEquals("sscuser@10.0.0.5:\"/tmp/heap_dump_11410.hprof\"",
                RemoteDumpService.scpCommandString(server(), "/tmp/heap_dump_11410.hprof", "/x").split(" ")[9],
                "평범한 경로는 v2.5.0 이전과 같은 모양");
        assertFalse(cmd.contains(":'\""), "따옴표가 scp 인자에 글자로 남는 형태로 되돌아가면 전송이 전부 실패한다");
    }

    @Test
    @DisplayName("scp 조립도 경로 검사를 스스로 수행한다 (단독 호출 방어)")
    void scpCommandRejectsUnsafePath() {
        assertThrows(IllegalArgumentException.class,
                () -> RemoteDumpService.scpCommandString(server(), "/opt/dumps/$(id).hprof", "/tmp/x"));
        assertThrows(IllegalArgumentException.class,
                () -> RemoteDumpService.scpCommandString(server(), "/opt/dumps/a\"b.hprof", "/tmp/x"));
    }

    @Test
    @DisplayName("이스케이프 — 허용 목록 밖 ASCII 만 역슬래시, 한글은 그대로")
    void escapeForRemoteShellUsesAllowList() {
        assertEquals("/tmp/heap_dump_11410.hprof", RemoteDumpService.escapeForRemoteShell("/tmp/heap_dump_11410.hprof"));
        assertEquals("/a\\ b/c\\'d\\~e\\^f", RemoteDumpService.escapeForRemoteShell("/a b/c'd~e^f"));
        assertEquals("/덤프/힙\\ 덤프.hprof", RemoteDumpService.escapeForRemoteShell("/덤프/힙 덤프.hprof"));
        assertEquals("/x/a-b_c.d+e:f@g%h,i=j", RemoteDumpService.escapeForRemoteShell("/x/a-b_c.d+e:f@g%h,i=j"));
    }

    // ── 셸 3단계 시뮬레이션 (실제 bash/sh 사용, SSH 불필요) ─────────────────

    private static final boolean UTF8_ARGS = "UTF-8".equalsIgnoreCase(System.getProperty("sun.jnu.encoding"));

    private static String run(List<String> argv) throws Exception {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor();
        if (p.exitValue() != 0) return null;
        return new String(out, StandardCharsets.UTF_8);
    }

    /** ① 로컬 bash -c 층: scp 를 argv 덤프 함수로 바꿔 scp 가 실제로 받는 원격 인자를 얻는다. */
    private static String scpSourceArg(String cmd) throws Exception {
        String out = run(List.of("bash", "-c", "scp(){ printf '%s\\0' \"$@\"; }; " + cmd));
        assertNotNull(out, "로컬 셸 층 실행 실패: " + cmd);
        for (String a : out.split("\0")) {
            int at = a.indexOf("@10.0.0.5:");
            if (at >= 0) return a.substring(at + "@10.0.0.5:".length());
        }
        throw new AssertionError("scp 원격 인자를 찾지 못함: " + out);
    }

    /** ② 원격 셸 층: 레거시 scp 는 원격에서 {@code scp -f <src>} 를 사용자 셸로 실행한다 — sh 가 푼 인자들. */
    private static List<String> remoteShellArgs(String src) throws Exception {
        String out = run(List.of("sh", "-c", "printf '%s\\0' " + src));
        assertNotNull(out, "원격 셸 층 실행 실패: " + src);
        List<String> args = new ArrayList<>();
        for (String a : out.split("\0")) if (!a.isEmpty()) args.add(a);
        return args;
    }

    /** ③ scp 파일명 검사: 받은 인자의 basename 을 패턴으로 서버가 돌려준 이름을 fnmatch — bash [[ == ]] 패턴 매칭으로 흉내. */
    private static boolean scpNameCheckPasses(String src, String returnedName) throws Exception {
        String pattern = src.substring(src.lastIndexOf('/') + 1);
        return run(List.of("bash", "-c", "[[ $1 == $2 ]]", "_", returnedName, pattern)) != null;
    }

    private static void assumeShells() {
        assumeTrue(new File("/bin/bash").canExecute() && new File("/bin/sh").canExecute(), "bash/sh 없음");
    }

    @Test
    @DisplayName("셸 3단계 — 원격 셸이 푼 경로 = 원래 경로, scp 파일명 검사 통과 (공백·작은따옴표·~·^·한글)")
    void shellLayersAgreeOnFilename() throws Exception {
        assumeShells();
        List<String> paths = new ArrayList<>(List.of(
                "/tmp/heap_dump_11410.hprof",
                "/data/heap dumps/java_pid1.hprof",
                "/opt/dumps/it's.hprof",
                "/opt/dumps/a~b^c d.hprof",
                "/usr/share/alsa/ucm2/NXP/iMX8/Librem_5/Librem 5.conf"));
        if (UTF8_ARGS) paths.add("/opt/덤프/힙 덤프 0911.hprof");
        for (String path : paths) {
            String src = scpSourceArg(RemoteDumpService.scpCommandString(server(), path, "/tmp/x"));
            assertFalse(src.contains("\""), "scp 인자에 따옴표가 글자로 남음: " + src);
            assertEquals(List.of(path), remoteShellArgs(src), "원격 셸이 푼 경로가 원래 경로와 다르다: " + src);
            String name = path.substring(path.lastIndexOf('/') + 1);
            assertTrue(scpNameCheckPasses(src, name), "scp 파일명 검사 실패(protocol error 재현): src=" + src);
        }
    }

    @Test
    @DisplayName("시뮬레이션이 실제 장애를 재현하는지 — 최초 v2.5.1 형태 '\"path\"' 는 파일명 검사에서 떨어져야 한다")
    void simulationReproducesQuotedArgumentFailure() throws Exception {
        assumeShells();
        String broken = "scp -P 22 sscuser@10.0.0.5:'\"/tmp/heap_dump_11410.hprof\"' '/tmp/x'";
        String src = scpSourceArg(broken);
        assertEquals("\"/tmp/heap_dump_11410.hprof\"", src, "로컬 셸이 큰따옴표를 남긴다");
        assertEquals(List.of("/tmp/heap_dump_11410.hprof"), remoteShellArgs(src), "원격 셸은 따옴표를 벗긴다");
        assertFalse(scpNameCheckPasses(src, "heap_dump_11410.hprof"),
                "요청 패턴 heap_dump_11410.hprof\" 와 돌려받은 이름이 달라 실패해야 한다(실측 오류와 동일)");
    }
}
