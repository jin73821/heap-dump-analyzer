package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.TargetServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 원격 ssh/scp stderr 글자깨짐 (2026-09-15, 사내망 daniwb1p 실측).
 *
 * <p>사내 서버 sshd 로그인 배너는 CP949 인데, 로컬 OpenSSH(UTF-8 로케일)가 유효하지 않은 바이트를 {@code \ooo} 로 바꾸고
 * 우연히 UTF-8 로 읽히는 쌍은 통과시켜 {@code \300ΰ\241\265\310} 같은 문자열이 로그·전송 오류 문구에 남았다.
 * 그 배너가 오류 문구 앞 300자를 차지해 scp 의 진짜 실패 원인이 잘려 나갔다.
 *
 * <p>고정하는 것: ① 배너를 끄는 {@code LogLevel=ERROR} 가 ssh·scp 공통 옵션에 있고 {@code -q} 는 없다
 * ② 이스케이프가 섞인 줄은 원래 한글로 복원한다(strict 성공 시에만) ③ 정상 줄·원격 raw 바이트는 그대로
 * ④ 오류 문구가 길면 꼬리(마지막 줄)를 남긴다.
 */
class RemoteDumpStderrTest {

    /** 운영 로그에 찍힌 문자열 그대로 — Java 소스에서는 역슬래시를 두 번 쓴다. */
    private static final String ESCAPED_BANNER =
            "\\300ΰ\\241\\265\\310 \\273\\347\\277\\353\\300ڰ\\241 \\276ƴ\\321 \\260\\346\\277\\354 \\301\\357\\275\\303 "
            + "\\267α\\327\\276ƿ\\364 \\307\\330\\301ֽñ\\342 \\271ٶ\\370\\264ϴ\\331.";

    private static TargetServer server() {
        TargetServer s = new TargetServer();
        s.setHost("10.0.0.5");
        s.setPort(22);
        s.setSshUser("sscuser");
        return s;
    }

    @Test
    @DisplayName("OpenSSH 8진 이스케이프 배너 → 원래 CP949 한글로 복원, 같은 stderr 의 정상 UTF-8 줄은 그대로")
    void escapedBannerIsRestored() {
        String stderr = ESCAPED_BANNER + "\nfind: ‘/webadmin/domains/ccdomain/orchestration’: 허가 거부\n";
        String out = RemoteDumpService.decodeStderr(stderr.getBytes(StandardCharsets.UTF_8));
        String[] lines = out.split("\n");
        assertEquals("인가된 사용자가 아닌 경우 즉시 로그아웃 해주시기 바랍니다.", lines[0]);
        assertEquals("find: ‘/webadmin/domains/ccdomain/orchestration’: 허가 거부", lines[1],
                "원격 명령의 UTF-8 줄까지 MS949 로 읽으면 오히려 깨진다 — 줄 단위 복원");
        assertFalse(out.contains("\\300"));
    }

    @Test
    @DisplayName("원격 명령이 보낸 raw MS949 바이트 → 기존 폴백으로 한글 / 정상 UTF-8 → 변경 없음")
    void rawBytesKeepWorking() {
        byte[] ms949 = "find: 허가 거부".getBytes(Charset.forName("MS949"));
        assertEquals("find: 허가 거부", RemoteDumpService.decodeStderr(ms949));
        String utf8 = "scp: /data/java_pid1.hprof: 허가 거부";
        assertEquals(utf8, RemoteDumpService.decodeStderr(utf8.getBytes(StandardCharsets.UTF_8)));
        assertEquals("", RemoteDumpService.decodeStderr(new byte[0]));
    }

    @Test
    @DisplayName("복원해도 strict 디코딩이 안 되는 이스케이프 → 원문 유지(추측 복원 금지)")
    void undecodableEscapesStayLiteral() {
        String literal = "path C:\\377\\376 odd";
        assertEquals(literal, RemoteDumpService.decodeStderr(literal.getBytes(StandardCharsets.UTF_8)));
        assertEquals("plain \\100 ascii", RemoteDumpService.unescapeSshOctal("plain \\100 ascii"), "0x80 미만 이스케이프는 대상 아님");
    }

    @Test
    @DisplayName("오류 문구가 상한을 넘으면 앞을 자르고 마지막 줄(진짜 원인)을 남긴다")
    void cleanSshErrorKeepsTail() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append("인가된 사용자가 아닌 경우 즉시 로그아웃 해주시기 바랍니다 ").append(i).append('\n');
        sb.append("scp: /data/java_pid20662.hprof: Permission denied\n");
        String msg = RemoteDumpService.cleanSshError(sb.toString());
        assertTrue(msg.endsWith("scp: /data/java_pid20662.hprof: Permission denied"), msg);
        assertTrue(msg.startsWith("…"), msg);
        assertEquals(RemoteDumpService.ERROR_MSG_MAX + 1, msg.length());
        assertEquals("ssh: connect to host x port 22: Connection timed out",
                RemoteDumpService.cleanSshError("ssh: connect to host x port 22: Connection timed out\n"), "짧으면 그대로");
    }

    @Test
    @DisplayName("ssh·scp 모두 공통 옵션(LogLevel=ERROR) 사용, -q 금지")
    void commonOptionsCarryLogLevel() {
        String ssh = RemoteDumpService.buildSshCommandString(server(), "echo OK");
        String scp = RemoteDumpService.scpCommandString(server(), "/data/a.hprof", "/tmp/a.hprof");
        for (String cmd : new String[]{ssh, scp}) {
            assertTrue(cmd.contains(RemoteDumpService.SSH_COMMON_OPTS), cmd);
            assertTrue(cmd.contains(" -o LogLevel=ERROR "), "배너는 LogLevel INFO 이상에서만 출력된다: " + cmd);
            assertFalse(cmd.contains(" -q "), "-q 는 LogLevel=QUIET 라 접속 실패 원인까지 숨긴다: " + cmd);
        }
        assertTrue(ssh.startsWith("ssh -o StrictHostKeyChecking=no") && ssh.endsWith(" -p 22 sscuser@10.0.0.5 \"echo OK\""), ssh);
    }
}
