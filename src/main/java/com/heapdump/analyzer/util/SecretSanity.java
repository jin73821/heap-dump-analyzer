package com.heapdump.analyzer.util;

/**
 * 복호화된 시크릿(비밀번호/API 키/OTP seed 등)의 위생 검사.
 *
 * <p>존재 이유 — "복호화 실패"를 잡는 검사로는 부족하기 때문이다.
 * {@link AesEncryptor} 의 마커 없는 64 HEX 모호 구간에서는 잘못된 IV 로 복호화해도
 * CBC 특성상 두 번째 블록이 정상 복원되고 PKCS5 패딩까지 유효해 <b>예외가 나지 않는다</b>.
 * 그 결과 {@code 쓰레기 16바이트 + 원문} 이 조용히 반환되고, UTF-8 디코딩에서
 * 쓰레기 바이트가 U+FFFD 로 치환되며 <b>비가역</b>으로 굳는다.
 * 따라서 판별자는 예외 유무가 아니라 <b>복호화 결과값 자체</b>여야 한다.
 *
 * <p>판정은 결정적 규칙만 사용한다. 엔트로피·길이·출력가능 문자 비율 같은 휴리스틱은
 * 정상 시크릿을 손상으로 오판할 수 있어 쓰지 않는다. 아래 규칙은 사람이 입력하거나
 * 시스템이 발급하는 시크릿(Base64 +/=, Base32 OTP seed, 한글, 공백, 이모지,
 * 특수문자 비밀번호)에는 절대 나타나지 않는다.
 *
 * <ol>
 *   <li>U+FFFD (REPLACEMENT CHARACTER) — 비가역 UTF-8 손실의 확정 증거</li>
 *   <li>C0 제어문자 U+0000~U+001F 및 DEL U+007F — HTTP 헤더/Basic 자격증명에 들어갈 수 없음</li>
 *   <li>C1 제어문자 U+0080~U+009F — 정상 시크릿에 존재 불가</li>
 * </ol>
 *
 * <p>검출력: 오복호화된 쓰레기는 균등 랜덤 16바이트라 위 규칙 어디에도 안 걸릴 확률이
 * 대략 {@code (95/256)^16 = 1e-7} 이다.
 */
public final class SecretSanity {

    // 소스에 보이지 않는 제어문자를 직접 넣지 않기 위해 코드포인트로 표기한다.
    private static final int REPLACEMENT_CHAR = 0xFFFD;  // U+FFFD
    private static final int C0_MAX = 0x1F;              // C0 제어문자 상한
    private static final int DEL    = 0x7F;
    private static final int C1_MIN = 0x80;              // C1 제어문자 구간
    private static final int C1_MAX = 0x9F;

    private SecretSanity() {}

    /** 미설정(null/빈 문자열)은 손상이 아니므로 true. */
    public static boolean isClean(String secret) {
        return describe(secret) == null;
    }

    /**
     * 손상 사유를 반환. 정상이면 null.
     *
     * <p>반환 문자열에 <b>시크릿 원문을 절대 포함하지 않는다</b> — 이 값은 로그와
     * 관리자 UI 에 그대로 노출되기 때문. 사유·개수·첫 위치만 담는다.
     */
    public static String describe(String secret) {
        if (secret == null || secret.isEmpty()) return null;

        int replacement = 0;
        int control = 0;
        int firstBad = -1;

        for (int i = 0; i < secret.length(); i++) {
            int c = secret.charAt(i);
            boolean bad = true;
            if (c == REPLACEMENT_CHAR) {
                replacement++;
            } else if (c <= C0_MAX || c == DEL) {
                control++;
            } else if (c >= C1_MIN && c <= C1_MAX) {
                control++;
            } else {
                bad = false;
            }
            if (bad && firstBad < 0) firstBad = i;
        }

        if (replacement == 0 && control == 0) return null;

        StringBuilder sb = new StringBuilder("복호화 결과에 ");
        if (replacement > 0) {
            sb.append("손상 문자(U+FFFD) ").append(replacement).append("개");
            if (control > 0) sb.append(", ");
        }
        if (control > 0) {
            sb.append("제어문자 ").append(control).append("개");
        }
        sb.append(" 포함 (첫 위치 ").append(firstBad).append(") — 재입력 필요");
        return sb.toString();
    }
}
