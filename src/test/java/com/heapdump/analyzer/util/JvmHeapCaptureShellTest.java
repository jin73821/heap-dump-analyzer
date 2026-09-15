package com.heapdump.analyzer.util;

import com.heapdump.analyzer.util.JvmHeapCapture.Candidate;
import com.heapdump.analyzer.util.JvmHeapCapture.Capture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 원격 수집 스크립트를 <b>실제 sh 로</b> 실행한다(2026-09-16). 골든 문자열 검사로는 awk·ps 문법 오류나
 * 인용 층 문제를 못 잡는다 — 운영에 나가서야 "후보 0개" 로 조용히 드러난다. Linux(/proc) 에서만 돈다.
 *
 * <p>검증: ① 이 테스트 JVM(comm=java)이 후보로 잡힌다 ② 이름이 java 가 아닌 프로세스라도 명령줄에 JVM 옵션이 있으면
 * 잡힌다(이름을 바꾼 java 바이너리·래퍼 모사) ③ 환경 정보 줄(OS·계정·/proc 마운트·ps)이 나온다 ④ 실제 전송 경로와 같은
 * {@code echo <base64> | base64 -d | sh} 형태로도 같은 결과다.
 */
class JvmHeapCaptureShellTest {

    private static boolean linuxWithProc() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux") && Files.isDirectory(Path.of("/proc/self"));
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) { in.transferTo(out); }
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "스크립트가 30초 안에 끝나지 않았다");
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("실제 sh 실행 — 테스트 JVM 과 '이름이 java 가 아닌 JVM 옵션 프로세스'를 모두 찾고 환경 정보를 싣는다")
    void scriptRunsUnderShAndFindsJvms() throws Exception {
        assumeTrue(linuxWithProc(), "Linux /proc 전용");
        // 이름(comm)은 bash, 명령줄에 JVM 옵션 — 이름을 바꾼 java 바이너리·래퍼로 띄운 WAS 를 흉내 낸다.
        // 'sleep 30; :' 로 두 명령을 줘야 bash 가 sleep 으로 exec 해 버리지 않고 자기 명령줄을 유지한다.
        Process fake = new ProcessBuilder("bash", "-c", "sleep 30; :", "wasd", "-Xmx64m", "-XX:+UseSerialGC").start();
        try {
            Thread.sleep(300);
            long self = ProcessHandle.current().pid();
            String out = run("sh", "-c", JvmHeapCapture.REMOTE_SCRIPT);
            assertTrue(out.contains("__END__"), "스크립트가 끝까지 돌지 않았다(문법 오류?): " + out);
            Capture cap = JvmHeapCapture.parseOutput(out, System.currentTimeMillis() / 1000L);
            assertFalse(cap.truncated(), out);
            assertNotNull(cap.remoteEnv(), "환경 정보 줄");
            assertEquals("Linux", cap.remoteEnv().os());
            assertTrue(cap.remoteEnv().psOk(), "ps -p $$ 성공");
            assertNotNull(cap.remoteEnv().user());

            Optional<Candidate> me = cap.candidates().stream().filter(c -> c.pid() == self).findFirst();
            assertTrue(me.isPresent() || cap.candidates().size() >= JvmHeapCapture.MAX_CANDIDATES,
                    "테스트 JVM(pid " + self + ", comm=java)이 후보에 없다: " + cap.candidates());
            Optional<Candidate> renamed = cap.candidates().stream().filter(c -> c.pid() == fake.pid()).findFirst();
            assertTrue(renamed.isPresent() || cap.candidates().size() >= JvmHeapCapture.MAX_CANDIDATES,
                    "이름이 java 가 아니어도 명령줄의 JVM 옵션으로 찾아야 한다(pid " + fake.pid() + "): " + cap.candidates());
            // 값(-Xmx64m)은 bash 명령줄 구조상 "메인 클래스 뒤 인자"라 파서가 옵션으로 보지 않는다 — 여기서는 발견만 본다

            // 실제 원격 전송 형태(base64 → sh)도 같은 결과
            String b64 = JvmHeapCapture.buildRemoteCommand(null);
            String out2 = run("sh", "-c", b64);
            Capture cap2 = JvmHeapCapture.parseOutput(out2, System.currentTimeMillis() / 1000L);
            assertFalse(cap2.truncated(), out2);
            assertTrue(cap2.candidates().stream().anyMatch(c -> c.pid() == fake.pid()) || cap2.candidates().size() >= JvmHeapCapture.MAX_CANDIDATES, out2);
            assertTrue(new String(Base64.getDecoder().decode(b64.substring(5, b64.indexOf(' ', 5))), StandardCharsets.UTF_8)
                    .contains("JPIDS=$("), "base64 본문이 새 스크립트");
        } finally {
            fake.destroyForcibly();
        }
    }
}
