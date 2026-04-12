package com.heapdump.analyzer.util;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 */
public class AesEncryptor {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final String KEY_SEED = "HeapDumpAnalyzer2026!@#SecretKey";

    private static SecretKeySpec getKey() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha.digest(KEY_SEED.getBytes(StandardCharsets.UTF_8));
        // AES-256: SHA-256 해시 전체 32바이트 사용
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static IvParameterSpec getIv() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] ivBytes = sha.digest((KEY_SEED + "IV").getBytes(StandardCharsets.UTF_8));
        return new IvParameterSpec(Arrays.copyOf(ivBytes, 16));
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

    public static String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, getKey(), getIv());
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("AES 암호화 실패", e);
        }
    }

    public static String decrypt(String hexCipherText) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, getKey(), getIv());
            byte[] decoded = hexToBytes(hexCipherText);
            byte[] decrypted = cipher.doFinal(decoded);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES 복호화 실패", e);
        }
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
            System.out.println("  encrypt <plaintext>   → AES-256-CBC 암호화 (HEX 출력)");
            System.out.println("  decrypt <ciphertext>  → AES-256-CBC 복호화 (HEX 입력)");
            System.exit(1);
        }
        String action = args[0];
        String input = args[1];

        if ("encrypt".equalsIgnoreCase(action)) {
            String encrypted = encrypt(input);
            System.out.println("Algorithm: AES-256-CBC / PKCS5Padding");
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
