# 사용자 계정 및 암호화 보안 점검 보고서

> 점검일: 2026-04-13  
> 대상: 사용자 인증/인가, DB 비밀번호 암호화, 계정 관리 API  
> 상태: **미조치** — 추후 개선 예정

---

## 1. 점검 대상 파일

| 파일 | 역할 |
|------|------|
| `util/AesEncryptor.java` | AES-256-CBC 대칭 암호화 유틸리티 (DB 비밀번호 등 설정값 암호화) |
| `config/SecurityConfig.java` | Spring Security 설정 (인증/인가, CSRF, BCrypt) |
| `config/DataSourceConfig.java` | DB DataSource 설정 (ENC 형식 비밀번호 복호화) |
| `service/UserService.java` | 사용자 CRUD, 기본 관리자 계정 생성 |
| `service/CustomUserDetailsService.java` | Spring Security UserDetailsService 구현 |
| `controller/AuthController.java` | 로그인 페이지 렌더링 |
| `controller/AdminController.java` | 사용자 관리 REST API (CRUD) |
| `model/entity/User.java` | 사용자 JPA Entity |
| `repository/UserRepository.java` | 사용자 JPA Repository |
| `resources/db/init.sql` | DB 초기화 스크립트 |
| `templates/login.html` | 로그인 페이지 |
| `templates/admin/users.html` | 사용자 관리 페이지 |

---

## 2. 양호 항목

### 2.1 비밀번호 해싱 — BCrypt 사용
- `SecurityConfig.java`에서 `BCryptPasswordEncoder` Bean 등록
- `UserService.java`에서 사용자 생성/비밀번호 초기화 시 `passwordEncoder.encode()` 호출
- DB `users.password` 컬럼에 BCrypt 해시값 저장 (평문 저장 없음)

### 2.2 SQL Injection 방지
- `UserRepository`는 JPA `findByUsername()` 등 파라미터화 쿼리만 사용
- 직접 SQL 문자열 조합 없음

### 2.3 인증/인가 구조
- Spring Security `formLogin` + 세션 기반 인증
- `/admin/**`, `/api/admin/**` → `hasRole("ADMIN")` 제한
- 모든 요청에 인증 필요 (`anyRequest().authenticated()`)

### 2.4 로그인 에러 메시지
- "아이디 또는 비밀번호가 올바르지 않습니다" — 구체적 원인 미노출 (열거 공격 방지)

### 2.5 Admin 계정 삭제 방지
- `UserService.deleteUser()`에서 admin 계정 삭제 차단 로직 존재

### 2.6 DB 비밀번호 암호화 저장
- `application.properties`에 `ENC(682d6e43ec2ac80b8856edffc9351e61)` 형식으로 암호화 저장
- `DataSourceConfig`에서 `AesEncryptor.decryptIfEncrypted()`로 런타임 복호화

---

## 3. 취약점 목록

### 3.1 [높음] AES 암호화 키 소스코드 하드코딩

**파일:** `util/AesEncryptor.java`

```java
private static final String KEY_SEED = "HeapDumpAnalyzer2026!@#SecretKey";
```

**위험:** 소스코드 접근(Git, JAR 디컴파일) 시 `ENC(...)` 형식의 모든 암호화된 설정값(DB 비밀번호 등)을 즉시 복호화 가능.

**개선 방안:**
- 환경변수(`ENCRYPTION_KEY`)에서 키를 로드하도록 변경
- 또는 외부 키 관리 시스템(KMS, Vault) 연동
- 수정 후 기존 암호화된 값 재암호화 필요

**수정 예시:**
```java
private static final String KEY_SEED = System.getenv("HEAP_ANALYZER_ENCRYPTION_KEY");

static {
    if (KEY_SEED == null || KEY_SEED.isEmpty()) {
        throw new IllegalStateException("HEAP_ANALYZER_ENCRYPTION_KEY 환경변수가 설정되지 않았습니다.");
    }
}
```

---

### 3.2 [높음] IV(Initialization Vector) 고정 사용

**파일:** `util/AesEncryptor.java`

```java
byte[] ivBytes = Arrays.copyOf(
    MessageDigest.getInstance("SHA-256").digest((KEY_SEED + "IV").getBytes()), 16);
```

**위험:** 매 암호화마다 동일한 IV를 사용하므로, 같은 평문은 항상 같은 암호문을 생성. CBC 모드의 보안 이점이 무효화되어 패턴 분석에 취약.

**개선 방안:**
- 암호화 시 `SecureRandom`으로 16바이트 랜덤 IV 생성
- 암호문 앞에 IV를 붙여서 저장: `[IV 16bytes][CipherText]`
- 복호화 시 앞 16바이트를 IV로 분리하여 사용

**수정 예시:**
```java
public static String encrypt(String plainText) {
    SecureRandom random = new SecureRandom();
    byte[] iv = new byte[16];
    random.nextBytes(iv);
    IvParameterSpec ivSpec = new IvParameterSpec(iv);

    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
    byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

    // IV + 암호문을 합쳐서 반환
    byte[] combined = new byte[iv.length + encrypted.length];
    System.arraycopy(iv, 0, combined, 0, iv.length);
    System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
    return bytesToHex(combined);
}
```

> **참고:** IV 방식 변경 시 기존 암호화된 값과 호환성이 깨지므로, 마이그레이션 절차 필요.

---

### 3.3 [높음] 기본 관리자 비밀번호 하드코딩

**파일:** `service/UserService.java`

```java
@PostConstruct
public void initDefaultAdmin() {
    if (!userRepository.existsByUsername("admin")) {
        admin.setPassword(passwordEncoder.encode("shinhan@10"));  // 하드코딩
    }
}
```

**위험:** 소스코드를 아는 누구나 초기 관리자 계정(`admin` / `shinhan@10`)으로 로그인 가능. 배포 후 비밀번호를 변경하지 않으면 지속적인 위험.

**개선 방안 (택 1):**

**A) 환경변수에서 초기 비밀번호 읽기:**
```java
String defaultPassword = System.getenv("HEAP_ADMIN_DEFAULT_PASSWORD");
if (defaultPassword == null) {
    defaultPassword = UUID.randomUUID().toString();
    logger.warn("[UserService] 기본 관리자 비밀번호: {}", defaultPassword);
}
admin.setPassword(passwordEncoder.encode(defaultPassword));
```

**B) 첫 로그인 시 비밀번호 변경 강제:**
- `User` Entity에 `passwordChangeRequired` (boolean) 필드 추가
- 로그인 성공 후 해당 플래그가 true이면 비밀번호 변경 페이지로 리다이렉트

---

### 3.4 [높음] init.sql에 DB 비밀번호 평문 노출

**파일:** `resources/db/init.sql`

```sql
CREATE USER IF NOT EXISTS 'heap_user'@'%' IDENTIFIED BY 'heap_pass_2026';
```

**위험:** `application.properties`의 DB 비밀번호는 `ENC(...)` 형식으로 암호화했지만, init.sql에 동일 비밀번호가 평문으로 존재. Git 이력에도 남아 있음.

**개선 방안:**
- init.sql에서 비밀번호 제거, 플레이스홀더로 대체: `IDENTIFIED BY '${DB_PASSWORD}'`
- 별도 배포 가이드 문서에서 DB 사용자 생성 절차 안내
- Git 이력 정리 고려 (`git filter-branch` 또는 BFG Repo-Cleaner)

---

### 3.5 [중간] API CSRF 보호 비활성화

**파일:** `config/SecurityConfig.java`

```java
.csrf().ignoringAntMatchers("/api/**");
```

**위험:** `/api/admin/users` (사용자 생성), `/api/admin/users/{id}` (삭제) 등 관리자 API에도 CSRF 보호가 없음. 관리자가 악성 사이트를 방문하면, 해당 사이트에서 관리자 세션을 이용해 사용자 생성/삭제 요청 전송 가능 (CSRF 공격).

**개선 방안 (택 1):**

**A) `/api/admin/**`에 CSRF 보호 복원:**
```java
.csrf()
    .ignoringAntMatchers("/api/**")            // 일반 API는 유지
    .and()
    // 또는 admin API만 보호:
.csrf()
    .ignoringAntMatchers("/api/upload/**", "/api/analyze/**", "/api/settings/**",
                         "/api/cache/**", "/api/history/**", "/api/queue/**",
                         "/api/disk/**", "/api/system/**")
```

**B) API 호출 시 CSRF 토큰 전송:**
```javascript
// admin/users.html의 fetch 호출에 CSRF 토큰 추가
const csrfToken = document.querySelector('meta[name="_csrf"]').content;
const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;

fetch('/api/admin/users', {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        [csrfHeader]: csrfToken
    },
    body: JSON.stringify(data)
});
```

---

### 3.6 [중간] 비밀번호 정책 부재

**파일:** `service/UserService.java`, `controller/AdminController.java`

**현재 상태:**
- `createUser()`, `resetPassword()` 메서드에서 비밀번호 길이/복잡도 검증 없음
- 빈 문자열, 1글자 비밀번호도 설정 가능
- 클라이언트(`admin/users.html`)에서만 비밀번호 일치 확인 (서버 미검증)

**개선 방안:**
- 서버 측 비밀번호 정책 검증 추가

```java
private void validatePassword(String password) {
    if (password == null || password.length() < 8) {
        throw new IllegalArgumentException("비밀번호는 최소 8자 이상이어야 합니다.");
    }
    if (!password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) {
        throw new IllegalArgumentException("비밀번호는 영문과 숫자를 포함해야 합니다.");
    }
}
```

- `createUser()`, `resetPassword()` 호출 시 `validatePassword()` 실행
- 실패 시 400 응답과 함께 사유 반환

---

### 3.7 [낮음] 메서드 레벨 권한 검증 없음

**파일:** `controller/AdminController.java`

**현재 상태:** URL 패턴(`/api/admin/**` → `hasRole("ADMIN")`)으로만 보호. 컨트롤러 메서드에 `@PreAuthorize` 없음.

**위험:** URL 매핑 실수(예: `/api/users/{id}` 오타)나 향후 리팩토링 시 보호가 누락될 수 있음.

**개선 방안:**
```java
@RestController
@PreAuthorize("hasRole('ADMIN')")  // 클래스 레벨 이중 방어
public class AdminController {
    // ...
}
```
- `SecurityConfig`에 `@EnableGlobalMethodSecurity(prePostEnabled = true)` 추가 필요

---

## 4. 개선 우선순위 요약

| 순위 | 항목 | 심각도 | 수정 난이도 | 섹션 |
|------|------|--------|------------|------|
| 1 | AES 암호화 키 환경변수 이동 | 높음 | 낮음 | 3.1 |
| 2 | AES IV 랜덤 생성 | 높음 | 중간 | 3.2 |
| 3 | 기본 관리자 비밀번호 외부화 | 높음 | 낮음 | 3.3 |
| 4 | init.sql 비밀번호 제거 | 높음 | 낮음 | 3.4 |
| 5 | `/api/admin/**` CSRF 보호 적용 | 중간 | 중간 | 3.5 |
| 6 | 비밀번호 정책 서버 검증 추가 | 중간 | 낮음 | 3.6 |
| 7 | `@PreAuthorize` 이중 방어 추가 | 낮음 | 낮음 | 3.7 |

---

## 5. 수정 시 주의사항

- **3.1 + 3.2 동시 수정 권장:** AES 키와 IV 방식을 동시에 변경하면 마이그레이션을 한 번만 수행하면 됨
- **기존 암호화 값 마이그레이션:** AES 로직 변경 시, 기존 `ENC(...)` 값을 새 방식으로 재암호화하는 마이그레이션 스크립트 필요
- **heap_enc.sh / heap_dec.sh:** 프로젝트 루트의 암호화/복호화 셸 스크립트도 함께 업데이트 필요
- **테스트:** 변경 후 DB 연결, 로그인, 사용자 CRUD 전체 동작 검증 필수
