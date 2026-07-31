package com.heapdump.analyzer.util;

/**
 * 시크릿 1건의 런타임 홀더 — 평문과 <b>로드 당시의 암호문</b>을 함께 들고 있는다.
 *
 * <p>해결하는 문제 2가지:
 *
 * <ol>
 *   <li><b>오염 세탁 루프.</b> 기존에는 {@code collectSettings()} 가 메모리의 평문을 항상
 *       재암호화해 되저장했다. 한 번 손상된 평문이 다시 봉인되면 원본 암호문이 영구히
 *       사라진다. 게다가 RAG 와 무관한 설정 변경 하나로도 이 경로가 돈다.</li>
 *   <li><b>기동 churn.</b> 랜덤 IV 라 재암호화 결과가 매번 달라져, 값이 안 바뀌어도
 *       settings.json / application.properties 가 매번 수정됐다.</li>
 * </ol>
 *
 * <p>해법: <b>사용자가 실제로 값을 바꿨을 때만 재암호화</b>하고, 그 외에는 로드 당시의
 * 암호문을 그대로 되돌려준다. 손상값은 저장은 보존하되 런타임 사용({@link #usable()})에서
 * 차단한다.
 */
public final class SecretValue {

    private volatile String plain;    // 복호화된 평문. 손상이면 사용 금지
    private volatile String cipher;   // 로드 당시의 "ENC(...)" 원본. 사용자가 바꾸면 null
    private volatile boolean healthy;
    private volatile String issue;

    private SecretValue(String plain, String cipher, boolean healthy, String issue) {
        this.plain = plain;
        this.cipher = cipher;
        this.healthy = healthy;
        this.issue = issue;
    }

    /** 미설정 상태. */
    public static SecretValue empty() {
        return new SecretValue("", null, true, null);
    }

    /**
     * 저장된 값("ENC(...)" 또는 평문)에서 로드. <b>예외를 던지지 않는다</b> —
     * 복호화 실패는 손상 상태로 표현되고 앱은 계속 기동한다.
     */
    public static SecretValue load(String stored) {
        if (stored == null || stored.isEmpty() || "null".equals(stored)) {
            return empty();
        }
        AesEncryptor.Decrypted d = AesEncryptor.decryptIfEncryptedChecked(stored);
        // 자동 복구된 값은 원본 암호문이 모호 형식이므로 보존하지 않는다 (forStorage 에서 v2 재봉인)
        String keepCipher = (isEncrypted(stored) && !d.recovered()) ? stored : null;
        return new SecretValue(d.value() != null ? d.value() : "", keepCipher, d.healthy(), d.issue());
    }

    /**
     * 다른 인스턴스의 상태를 그대로 흡수한다.
     * 보유 필드를 {@code final} 로 두고 재로드할 수 있게 하기 위한 것.
     */
    public void adoptFrom(SecretValue other) {
        this.plain = other.plain;
        this.cipher = other.cipher;
        this.healthy = other.healthy;
        this.issue = other.issue;
    }

    /** 사용자가 새 값을 지정. 이후 저장 시 재암호화된다. */
    public void set(String newPlain) {
        this.plain = newPlain != null ? newPlain : "";
        this.cipher = null;
        this.issue = SecretSanity.describe(this.plain);
        this.healthy = this.issue == null;
    }

    /** 런타임 소비용 — 손상값은 절대 밖으로 내보내지 않는다. */
    public String usable() {
        return healthy && plain != null ? plain : "";
    }

    /** 진단/마스킹용 원본 평문 (손상 여부 무관). */
    public String raw() {
        return plain != null ? plain : "";
    }

    public boolean isSet()     { return plain != null && !plain.trim().isEmpty(); }
    public boolean isHealthy() { return healthy; }
    public String  issue()     { return issue; }

    /**
     * settings.json / application.properties 에 기록할 값.
     *
     * <p><b>null 을 반환하면 호출자는 해당 키를 아예 기록하지 않아야 한다</b> —
     * 암호화가 실패했는데 빈 문자열을 쓰면 시크릿이 무경고로 삭제되기 때문이다.
     * 키를 생략하면 {@code applyFromSettings} 의 {@code containsKey} 가드와
     * {@code syncApplicationProperties} 의 라인 치환 특성상 기존 저장값이 그대로 유지된다.
     */
    public String forStorage() {
        // 1) 사용자가 명시적으로 비웠다 → 삭제
        if (plain == null || plain.isEmpty()) return "";

        // 2) 로드 후 변경 없음 → 원본 암호문 그대로 (재암호화 안 함 = churn 0, 손상 전파 0)
        if (cipher != null) {
            if (healthy && isAmbiguousLegacyFormat(cipher)) {
                // 모호한 64 HEX 는 이번 한 번만 v2 로 재봉인해 함정을 영구 제거한다
                String migrated = tryEncrypt(plain);
                if (migrated != null) {
                    cipher = migrated;
                    return migrated;
                }
            }
            return cipher;
        }

        // 3) 사용자가 바꾼 값 → 새로 암호화
        String encrypted = tryEncrypt(plain);
        return encrypted;   // 실패 시 null → 호출자가 키 생략
    }

    private static String tryEncrypt(String plain) {
        try {
            return "ENC(" + AesEncryptor.encrypt(plain) + ")";
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isEncrypted(String stored) {
        return stored.startsWith("ENC(") && stored.endsWith(")");
    }

    /** ENC(...) 안이 마커 없는 정확히 64 HEX 인지 — 레거시/랜덤 IV 가 겹치는 구간. */
    private static boolean isAmbiguousLegacyFormat(String storedCipher) {
        if (!isEncrypted(storedCipher)) return false;
        String body = storedCipher.substring(4, storedCipher.length() - 1);
        return body.length() == 64 && AesEncryptor.isHex(body);
    }
}
