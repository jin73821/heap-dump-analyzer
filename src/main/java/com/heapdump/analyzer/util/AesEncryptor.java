package com.heapdump.analyzer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-CBC 기반 프로퍼티 암호화/복호화 유틸리티.
 * 암호화 결과는 HEX(16진수) 문자열로 출력.
 *
 * 사용법:
 *   암호화: bash heap_enc.sh "평문"
 *   복호화: bash heap_dec.sh "암호문"
 *
 * application.properties에서 ENC(...) 형식으로 사용:
 *   spring.datasource.password=ENC(hex문자열)
 *
 * 암호화 키 설정:
 *   환경변수 HEAP_ANALYZER_ENCRYPTION_KEY에 키를 설정.
 *   미설정 시 내장 기본 키를 사용하며 경고 로그를 출력합니다.
 *
 * IV(Initialization Vector):
 *   암호화 시 매번 SecureRandom으로 16바이트 랜덤 IV를 생성하여
 *   암호문 앞에 붙여 저장합니다 (형식: "v2" + HEX[IV 32자][CipherText]).
 *
 * 저장 형식 3종 (모두 복호화 호환):
 *   1. v2 + HEX  — 현재 형식. 버전 마커로 형식이 명확히 구분됨.
 *   2. HEX > 64자 — 마커 이전의 랜덤 IV 형식. 앞 16바이트가 IV.
 *   3. HEX <= 64자 — 레거시 고정 IV 형식.
 *
 * ⚠️ "v2" 마커가 필요한 이유:
 *   마커가 없으면 길이로만 형식을 판별해야 하는데, 랜덤 IV 형식은 평문이 15바이트
 *   이하일 때 IV(16) + 암호문 1블록(16) = 32바이트 = **정확히 64 HEX 문자**가 되어
 *   레거시(고정 IV, 평문 16~31바이트)와 길이가 겹친다. 이때 고정 IV로 복호화하면
 *   CBC 특성상 두 번째 블록은 정상 복원되고 PKCS5 패딩까지 유효해 **예외 없이**
 *   `쓰레기 16바이트 + 평문` 이 반환된다(조용한 데이터 손상). 15자 이하 비밀번호가
 *   정확히 이 경우라 실사용에서 밟기 쉬운 함정이었다.
 */
public class AesEncryptor {

    private static final Logger logger = LoggerFactory.getLogger(AesEncryptor.class);

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final String DEFAULT_KEY_SEED = "HeapDumpAnalyzer2026!@#SecretKey";
    private static final int IV_LENGTH = 16;

    /** 랜덤 IV 형식임을 명시하는 버전 마커 (신규 암호문에만 부착). */
    private static final String V2_PREFIX = "v2";

    // 레거시 고정 IV 암호문의 최대 HEX 길이 (AES 블록 2개 = 32바이트 = 64 HEX 문자)
    private static final int LEGACY_MAX_HEX_LENGTH = 64;

    private static final String KEY_SEED;

    static {
        String envKey = System.getenv("HEAP_ANALYZER_ENCRYPTION_KEY");
        if (envKey != null && !envKey.isEmpty()) {
            KEY_SEED = envKey;
        } else {
            KEY_SEED = DEFAULT_KEY_SEED;
            System.err.println("[AesEncryptor] WARNING: HEAP_ANALYZER_ENCRYPTION_KEY 환경변수가 설정되지 않았습니다. 기본 키를 사용합니다. 운영 환경에서는 반드시 환경변수를 설정하세요.");
        }
    }

    private static SecretKeySpec getKey() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha.digest(KEY_SEED.getBytes(StandardCharsets.UTF_8));
        // AES-256: SHA-256 해시 전체 32바이트 사용
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 레거시 호환용 고정 IV 생성
     */
    private static IvParameterSpec getLegacyIv() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] ivBytes = sha.digest((KEY_SEED + "IV").getBytes(StandardCharsets.UTF_8));
        return new IvParameterSpec(Arrays.copyOf(ivBytes, IV_LENGTH));
    }

    // ── HEX 변환 ────────────────────────────────────────

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    // ── 암호화 / 복호화 ─────────────────────────────────

    /**
     * AES-256-CBC 암호화 (랜덤 IV).
     * 결과: "v2" + [IV 16바이트 = 32 HEX 문자][암호문 HEX]
     */
    public static String encrypt(String plainText) {
        try {
            SecureRandom random = new SecureRandom();
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, getKey(), ivSpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // IV + 암호문을 합쳐서 반환
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return V2_PREFIX + bytesToHex(combined);
        } catch (Exception e) {
            throw new RuntimeException("AES 암호화 실패", e);
        }
    }

    /**
     * 복호화 1건의 해석 결과.
     *
     * @param value     복호화된 평문 (실패 시 null)
     * @param healthy   {@link SecretSanity} 통과 여부. 실패했거나 손상 문자가 있으면 false
     * @param issue     손상/실패 사유 (정상이면 null). <b>시크릿 원문은 포함하지 않는다</b>
     * @param format    실제로 채택된 해석
     * @param recovered 모호 구간에서 랜덤 IV 해석으로 자동 복구됐는지
     */
    public record Decrypted(String value, boolean healthy, String issue, Format format, boolean recovered) {
        public enum Format { PLAIN, V2, RANDOM_IV, LEGACY, FAILED }

        public boolean isFailed() { return format == Format.FAILED; }
    }

    /**
     * AES-256-CBC 복호화. 저장 형식 3종을 자동 판별합니다.
     *   - "v2" 마커 있음        → 랜덤 IV (마커 제거 후 앞 16바이트가 IV)
     *   - 마커 없음 + HEX > 64  → 랜덤 IV (마커 도입 이전 암호문)
     *   - 마커 없음 + HEX <= 64 → 레거시 고정 IV
     *
     * 마커 없는 정확히 64 HEX 는 두 형식이 겹치는 모호 구간이라 양쪽을 모두 복호화한 뒤
     * 위생 검사로 채택한다 ({@link #resolveAmbiguous}). 그 외의 판별 규칙은 기존 동작
     * 그대로 유지한다 — 이미 저장된 값의 복호화 결과가 바뀌면 안 되기 때문.
     *
     * 실패 시 기존과 동일하게 {@link RuntimeException} 을 던진다. 예외 없이 상태를
     * 받고 싶으면 {@link #decryptIfEncryptedChecked} 를 쓸 것.
     */
    public static String decrypt(String cipherText) {
        Decrypted d = decryptInternal(cipherText);
        if (d.isFailed()) {
            throw new RuntimeException("AES 복호화 실패: " + d.issue());
        }
        return d.value();
    }

    /**
     * {@link #decryptIfEncrypted} 의 예외 없는 버전. <b>절대 throw 하지 않는다.</b>
     *
     * 선택 기능(RAG/SSO)의 시크릿을 {@code @PostConstruct} 에서 읽을 때 사용한다 —
     * 복호화 실패로 앱 전체가 기동 불가가 되는 것을 막고, 대신 손상 상태를 노출해
     * 사용자가 재입력할 수 있게 한다.
     */
    public static Decrypted decryptIfEncryptedChecked(String value) {
        if (value == null) {
            return new Decrypted(null, true, null, Decrypted.Format.PLAIN, false);
        }
        if (value.startsWith("ENC(") && value.endsWith(")")) {
            return decryptInternal(value.substring(4, value.length() - 1));
        }
        String issue = SecretSanity.describe(value);
        return new Decrypted(value, issue == null, issue, Decrypted.Format.PLAIN, false);
    }

    // ── 형식 판별 (내부 단일 경로) ──────────────────────

    private static Decrypted decryptInternal(String cipherText) {
        if (cipherText == null) {
            return new Decrypted(null, false, "암호문이 null", Decrypted.Format.FAILED, false);
        }

        // 1) v2 마커 — 형식이 확정이므로 단일 시도 (폴백 없음)
        if (cipherText.startsWith(V2_PREFIX)) {
            return attempt(cipherText.substring(V2_PREFIX.length()), Decrypted.Format.V2);
        }

        // 2) 마커 없는 정확히 64 HEX — 레거시(평문 16~31B)와 랜덤 IV(평문 ≤15B)가 겹치는 모호 구간
        if (cipherText.length() == LEGACY_MAX_HEX_LENGTH && isHex(cipherText)) {
            return resolveAmbiguous(cipherText);
        }

        // 3) 나머지는 길이로 판별 + 반대 형식 폴백 (기존 동작)
        Decrypted first = cipherText.length() <= LEGACY_MAX_HEX_LENGTH
                ? attempt(cipherText, Decrypted.Format.LEGACY)
                : attempt(cipherText, Decrypted.Format.RANDOM_IV);
        if (!first.isFailed()) return first;

        Decrypted fallback = cipherText.length() <= LEGACY_MAX_HEX_LENGTH
                ? attempt(cipherText, Decrypted.Format.RANDOM_IV)
                : attempt(cipherText, Decrypted.Format.LEGACY);
        return fallback.isFailed() ? first : fallback;
    }

    /**
     * 마커 없는 64 HEX 의 두 해석을 모두 시도해 채택한다.
     *
     * <p>왜 위생 검사가 유일한 판별자인가 — 마커 없는 랜덤 IV 값 {@code [IV][C]} 를
     * 레거시(고정 IV)로 풀면 두 번째 블록이 {@code D(C) xor IV = 원문} 이라
     * <b>PKCS5 패딩이 항상 유효</b>하다. 즉 레거시 해석은 언제나 예외 없이 "성공"하고
     * 결과만 {@code 쓰레기 16바이트 + 원문} 이다. 예외 유무로는 절대 구분할 수 없다.
     */
    private static Decrypted resolveAmbiguous(String hex) {
        Decrypted legacy = attempt(hex, Decrypted.Format.LEGACY);
        Decrypted random = attempt(hex, Decrypted.Format.RANDOM_IV);
        boolean legacyOk = !legacy.isFailed() && legacy.healthy();
        boolean randomOk = !random.isFailed() && random.healthy();

        if (legacyOk && randomOk) {
            // 양쪽 다 정상으로 보이면 기존 동작(레거시)을 보존한다.
            logger.warn("[AES] 모호한 64 HEX 암호문 — 레거시/랜덤 IV 양쪽 해석이 모두 정상. 레거시로 채택");
            return legacy;
        }
        if (legacyOk) return legacy;
        if (randomOk) {
            logger.info("[AES] 모호한 64 HEX 자동 복구 — 랜덤 IV 해석 채택 (레거시 해석은 부적합: {})",
                    legacy.issue());
            return new Decrypted(random.value(), true, null, Decrypted.Format.RANDOM_IV, true);
        }

        logger.error("[AES] 64 HEX 암호문의 두 해석 모두 실패/손상 — legacy={}, randomIv={}",
                legacy.issue(), random.issue());
        return legacy.isFailed() ? random : legacy;
    }

    /** 한 가지 형식으로 복호화 시도. 예외는 FAILED 결과로 흡수한다. */
    private static Decrypted attempt(String hex, Decrypted.Format format) {
        try {
            String value = (format == Decrypted.Format.LEGACY)
                    ? decryptLegacy(hex)
                    : decryptRandomIv(hex);
            String issue = SecretSanity.describe(value);
            return new Decrypted(value, issue == null, issue, format, false);
        } catch (Exception e) {
            return new Decrypted(null, false,
                    e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""),
                    Decrypted.Format.FAILED, false);
        }
    }

    /** HEX 문자만으로 이루어졌는지 (모호 구간 판정용 — v2 같은 마커를 걸러낸다). */
    static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /**
     * 랜덤 IV 복호화 (앞 16바이트 = IV)
     */
    private static String decryptRandomIv(String hexCipherText) throws Exception {
        byte[] combined = hexToBytes(hexCipherText);
        byte[] iv = Arrays.copyOf(combined, IV_LENGTH);
        byte[] encrypted = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new IvParameterSpec(iv));
        byte[] decrypted = cipher.doFinal(encrypted);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * 레거시 복호화 (고정 IV 방식)
     */
    private static String decryptLegacy(String hexCipherText) throws Exception {
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, getKey(), getLegacyIv());
        byte[] decoded = hexToBytes(hexCipherText);
        byte[] decrypted = cipher.doFinal(decoded);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * ENC(...) 형식이면 복호화, 아니면 원문 반환
     */
    public static String decryptIfEncrypted(String value) {
        if (value != null && value.startsWith("ENC(") && value.endsWith(")")) {
            String encrypted = value.substring(4, value.length() - 1);
            return decrypt(encrypted);
        }
        return value;
    }

    /**
     * CLI: 암호화/복호화 도구
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("  encrypt <plaintext>   → AES-256-CBC 암호화 (HEX 출력, 랜덤 IV)");
            System.out.println("  decrypt <ciphertext>  → AES-256-CBC 복호화 (HEX 입력, 레거시 호환)");
            System.out.println();
            System.out.println("환경변수 HEAP_ANALYZER_ENCRYPTION_KEY로 암호화 키 설정 (권장)");
            System.exit(1);
        }
        String action = args[0];
        String input = args[1];

        if ("encrypt".equalsIgnoreCase(action)) {
            String encrypted = encrypt(input);
            System.out.println("Algorithm: AES-256-CBC / PKCS5Padding (Random IV)");
            System.out.println("Plain:     " + input);
            System.out.println("Encrypted: " + encrypted);
            System.out.println("Property:  ENC(" + encrypted + ")");
        } else if ("decrypt".equalsIgnoreCase(action)) {
            String decrypted = decryptIfEncrypted(input);
            System.out.println("Decrypted: " + decrypted);
        } else {
            System.err.println("Unknown action: " + action);
            System.exit(1);
        }
    }
}
