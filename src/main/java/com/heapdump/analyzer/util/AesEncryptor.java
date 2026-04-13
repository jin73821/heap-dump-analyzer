package com.heapdump.analyzer.util;

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
 *   암호문 앞에 붙여 저장합니다 (HEX: [IV 32자][CipherText]).
 *   레거시 암호문(32자 이하, 고정 IV 방식)도 복호화 호환됩니다.
 */
public class AesEncryptor {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final String DEFAULT_KEY_SEED = "HeapDumpAnalyzer2026!@#SecretKey";
    private static final int IV_LENGTH = 16;

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
     * 결과 HEX: [IV 16바이트][암호문] = [32 HEX 문자][암호문 HEX]
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
            return bytesToHex(combined);
        } catch (Exception e) {
            throw new RuntimeException("AES 암호화 실패", e);
        }
    }

    /**
     * AES-256-CBC 복호화.
     * 새 형식(랜덤 IV 포함, HEX 길이 > LEGACY_MAX_HEX_LENGTH)과
     * 레거시 형식(고정 IV, HEX 길이 <= LEGACY_MAX_HEX_LENGTH)을 자동 판별합니다.
     */
    public static String decrypt(String hexCipherText) {
        try {
            if (hexCipherText.length() <= LEGACY_MAX_HEX_LENGTH) {
                // 레거시 형식: 고정 IV로 복호화
                return decryptLegacy(hexCipherText);
            }
            // 새 형식: 앞 16바이트가 IV
            byte[] combined = hexToBytes(hexCipherText);
            byte[] iv = Arrays.copyOf(combined, IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new IvParameterSpec(iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 새 형식 실패 시 레거시 형식으로 재시도
            try {
                return decryptLegacy(hexCipherText);
            } catch (Exception ex) {
                throw new RuntimeException("AES 복호화 실패", e);
            }
        }
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
