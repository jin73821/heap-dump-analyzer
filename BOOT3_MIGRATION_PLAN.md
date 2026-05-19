# Boot 3 / Spring Security 6 Migration Plan

작성일: 2026-05-19
대상: heap-analyzer 2.0.3
저자: 마이그레이션 영향도 1차 조사 결과 종합

---

## 1. 목표 (Target State)

| 항목 | 현재 | 목표 | 비고 |
|---|---|---|---|
| Java compile target | 11 | **17** | JVM 은 이미 OpenJDK 21 — 코드 측 LTS 라인 |
| Spring Boot | 2.7.18 (EOL) | **3.4.x** | 현재 LTS sustained 라인 |
| Spring Framework | 5.3.31 | 6.2.x | Boot 3.4 BOM |
| Spring Security | 5.7.11 (EOL) | 6.4.x | jakarta 기반 |
| Hibernate ORM | 5.6.15.Final | 6.4.x | `MariaDB103Dialect` deprecated → 자동 감지 |
| Tomcat embed | 9.0.83 | 10.1.x | `jakarta.servlet` |
| Thymeleaf | 3.0.15 | 3.1.x | SpEL 제한 정책 강화 |
| Spring Session JDBC | 2.7.4 | 3.4.x | 스키마 호환성 확인 필수 |

**핵심 동기**: Boot 2.7 / Security 5.7 가 OSS·상용 지원 모두 종료 (2026-05 기준) → 신규 CVE 패치 부재.

---

## 2. 영향 인벤토리 (Impact Inventory)

### 2.1 Java 소스 — `javax.*` → `jakarta.*` 치환 대상 (28 파일)

**그룹 A — `javax.persistence.*` → `jakarta.persistence.*` (15 파일)**
JPA 엔티티 / Repository / Specification 전반. 단순 import 라인 교체.
- `entity/AiChatMessage.java`, `entity/AiChatSession.java`, `entity/AiInsight.java`
- `entity/AnalysisRequest.java`, `entity/User.java`, `entity/AccountRequest.java`
- `entity/Server.java`, `entity/TransferLog.java`, `entity/LoginHistory.java`
- `entity/ComparisonHistory.java`, `entity/LeakLibraryRule.java`, `entity/LeakFallbackRule.java`
- `repository/*` 중 `Specification` import (`javax.persistence.criteria.Predicate` → `jakarta.persistence.criteria.Predicate`)

**그룹 B — `javax.servlet.*` → `jakarta.servlet.*` (5 파일)**
- `controller/AccountRequestController.java`
- `controller/AdminController.java`
- `controller/GlobalExceptionHandler.java`
- `controller/HeapReportApiController.java`
- `listener/AuthEventListener.java`

**그룹 C — `javax.annotation.PostConstruct/PreDestroy` → `jakarta.annotation.*` (7 파일)**
- `config/HeapDumpConfig.java`, `config/LeakSuspectAdvisorBootstrap.java`
- `service/HeapDumpAnalyzerService.java` (PostConstruct + PreDestroy)
- `service/LlmConfigService.java`, `service/RagConfigService.java`
- `service/RemoteDumpService.java`, `service/UserService.java`

**그룹 D — `javax.transaction.Transactional` → `jakarta.transaction.Transactional` (1 파일)**
- `service/AiInsightManager.java`
- 권장: 이 기회에 `org.springframework.transaction.annotation.Transactional` 로 표준화 (rollback 시맨틱 명확)

### 2.2 변경 NO (그대로 유지)

| Import | 사용처 | 사유 |
|---|---|---|
| `javax.crypto.*` | `util/AesEncryptor.java` | JCA — jakarta 이전 대상 아님 |
| `javax.net.ssl.*` | `LlmConfigService`, `RagConfigService`, `RemoteDumpService` | JSSE — jakarta 이전 대상 아님 |
| `javax.sql.DataSource` | config 클래스 | JDBC — jakarta 이전 대상 아님 |
| `javax.xml.parsers.*` | `parser/MatReportParser.java` | JAXP — jakarta 이전 대상 아님 |

### 2.3 Spring Security 6.x API 변경 (`SecurityConfig.java`)

| 현재 (5.7) | 6.x | 비고 |
|---|---|---|
| `authorizeRequests()` | `authorizeHttpRequests()` | 5.7 부터 이미 권장. 6.x 에서 removed |
| `.antMatchers(...)` | `.requestMatchers(...)` | 6.x 에서 removed (이름만 변경) |
| `@EnableGlobalMethodSecurity(prePostEnabled=true)` | `@EnableMethodSecurity` | 6.x default = prePostEnabled=true |
| `.and()` chain DSL | lambda DSL | 6.x 강력 권장 — `http.formLogin(form -> form.loginPage(...))` |
| `.headers().frameOptions().sameOrigin()` | `.headers(h -> h.frameOptions(f -> f.sameOrigin()))` | 람다 |
| `.csrf().ignoringRequestMatchers(matcher)` | 동일 시그니처 유지 가능 | lambda 권장이지만 호환 |

### 2.4 의존성 (pom.xml) 변경

| 의존성 | 현재 | 목표 | 비고 |
|---|---|---|---|
| `spring-boot-starter-parent` | 2.7.18 | 3.4.x | BOM이 대부분 전이 의존성 처리 |
| `<java.version>` | 11 | 17 | maven-compiler-plugin `<release>17</release>` 명시 권장 |
| `thymeleaf-extras-springsecurity5` | (BOM) | `thymeleaf-extras-springsecurity6` | artifactId 변경 |
| `mariadb-java-client` | 3.1.4 | 3.4.x 이상 | 3.1.4 도 Boot 3 동작 가능하나 신규 권장 버전으로 |
| `owasp-java-html-sanitizer` | 20220608.1 | 유지 (또는 최신) | Java 8 호환, jakarta 무관 |
| `commons-io` | 2.15.1 | 유지 | 영향 없음 |
| `openhtmltopdf-core/pdfbox` | 1.0.10 | 1.0.10 또는 1.1.x 확인 | Java 17 호환성 검증 필요 |
| `lombok` | (BOM) | 1.18.30+ | Java 17/21 호환 |
| `spring-boot-starter-validation` | (BOM) | (BOM) | `jakarta.validation` 전환 자동 |

### 2.5 `application.properties` 변경 후보

```diff
- spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MariaDB103Dialect
+ # Hibernate 6.x 는 dialect 자동 감지. 명시 필요 시 org.hibernate.dialect.MariaDBDialect
```
- `spring.session.jdbc.schema=classpath:org/springframework/session/jdbc/schema-mysql.sql` — Spring Session 3.x 도 동일 경로 (확인 필요).
- 그 외 `server.*` / `logging.*` / `spring.datasource.*` / `spring.jpa.*` 키는 Boot 3 호환 그대로.

### 2.6 데이터베이스 영향

- **SPRING_SESSION / SPRING_SESSION_ATTRIBUTES** — Spring Session 3.x 기본 스키마는 2.x 와 호환 (컬럼 동일). 다만 첫 기동 시 `spring.session.jdbc.initialize-schema=always` 가 `CREATE TABLE IF NOT EXISTS` 라 안전.
- **JPA `ddl-auto=update`** — Hibernate 6 의 DDL 생성 규칙이 일부 변경. 운영 DB 에 적용 전 dev DB 에서 실제 ALTER 문 확인 권장.
- **MariaDB103Dialect 제거** — 자동 감지로 전환되면 column type 추론이 일부 바뀔 수 있음 (예: `enum` ↔ `varchar`). 운영 시동 전 schema diff 점검.

### 2.7 Thymeleaf 3.1 변경 영향

- **SpEL restricted expression policy 강화** — 이미 `th:onclick` 제한으로 `th:data-* + onclick` 패턴 사용 중 (CLAUDE.md pitfall #4). 추가 깨짐 가능성 낮음.
- **`th:utext` 와 `th:text` 의 sanitization 일부 강화** — banner.html / leak-rules.html 등 사용자 입력 노출 부분 시각 점검 필요.
- `thymeleaf-extras-springsecurity6` 의 `sec:authorize` / `sec:authentication` 시맨틱 5 와 동일.

### 2.8 테스트

- `src/test/` 가 사실상 비어 있음 (CLAUDE.md 명시) → 테스트 마이그레이션 부담 없음. 반대로 **자동 회귀 안전망이 없음** → 마이그레이션 시 수동 smoke test 가 유일 안전장치.

---

## 3. 위험 영역 Top 5 (Risk Map)

| # | 위험 | 영향도 | 완화 |
|---|---|---|---|
| 1 | **Hibernate 6 dialect 전환으로 인한 schema diff** — `ddl-auto=update` 가 운영 컬럼 type 자동 변경 가능 | 🔴 HIGH | dev DB clone 으로 사전 검증. 운영 적용 전 `ddl-auto=validate` 로 차이 확인 |
| 2 | **Spring Session JDBC 3.x 와 기존 세션 데이터 호환성** | 🟡 MED | 마이그레이션 전 `TRUNCATE SPRING_SESSION; TRUNCATE SPRING_SESSION_ATTRIBUTES;` 로 초기화 — 모든 사용자 재로그인 발생 |
| 3 | **openhtmltopdf 1.0.10 의 Java 17 / PDFBox 호환** — Boot 3 가 가져오는 commons-logging / fontbox 버전과 충돌 가능 | 🟡 MED | dev 에서 PDF 생성 smoke test (수동) |
| 4 | **MariaDB Connector/J 3.1.4 의 Java 17 호환** — 작동 보고 사례 있으나 최신 3.4.x 로 동시 bump 권장 | 🟡 MED | 함께 업그레이드 |
| 5 | **자동 테스트 부재** — 회귀 검증이 모두 수동 | 🔴 HIGH | Phase 4 smoke test 체크리스트 (아래) 엄격 실행 |

---

## 4. PR 분할 전략 (Phased Rollout)

각 phase 는 **독립 PR**, 이전 phase merge → smoke test → 다음 phase 진행.

### Phase 0 — Pre-flight (코드 변경 없음)

- branch: `migration/boot3`
- DB snapshot: `mysqldump HEAPDB > backup-pre-boot3.sql`
- `result.json` / `data/` 디렉토리 백업
- `CHANGELOG.md` 에 마이그레이션 시작 entry 추가
- **승인 게이트**: 운영자 점검 시간 확보

### Phase 1 — Java 17 baseline (Boot 2.7 유지) — LOW RISK

목표: JVM 컴파일 target 만 11 → 17 로 올려 **JVM 측 가설을 분리 검증**.

**변경:**
- `pom.xml`: `<java.version>11</java.version>` → `17`
- `pom.xml`: maven-compiler-plugin 명시 (Spring Boot 2.7 parent default 사용 시 생략 가능)

**검증:**
- `mvn clean package -DskipTests` 성공
- `bash restart.sh` 정상 기동
- 로그인 / 분석 / AI 인사이트 1회 smoke
- 롤백: `<java.version>11</java.version>` 복귀, 재빌드 (즉시)

**PR 크기**: ~3 라인. 안전망 역할.

### Phase 2 — SecurityConfig 사전 modernization (Boot 2.7 유지) — LOW RISK

목표: Boot 3 PR 의 diff 를 줄이기 위해 미리 5.7 호환 lambda DSL 로 전환.

**변경:**
- `SecurityConfig.java`:
  - `@EnableGlobalMethodSecurity(prePostEnabled=true)` → `@EnableMethodSecurity` (Spring Security 5.6+ 지원)
  - `authorizeRequests()` → `authorizeHttpRequests()` (5.7+ 권장 API)
  - `.and()` chain 을 lambda DSL 로 전환:
    ```java
    http
      .userDetailsService(userDetailsService)
      .authorizeHttpRequests(auth -> auth
          .antMatchers(...).permitAll()
          ...)
      .formLogin(form -> form.loginPage("/login")...)
      .logout(logout -> logout.logoutUrl("/logout")...)
      .headers(h -> h.frameOptions(f -> f.sameOrigin()))
      .csrf(csrf -> csrf.ignoringRequestMatchers(matcher));
    return http.build();
    ```
  - `antMatchers` 는 **이 단계에선 유지** (5.7 에 `requestMatchers` 존재하나 일관성 위해 Phase 3 와 함께)

**검증:**
- 빌드 / 기동 / 로그인 / `/admin/users` (ADMIN-only) / `/api/llm/test-connection` CSRF 동작 — 4 항목 필수
- ADMIN endpoint 1 개 비-ADMIN 계정으로 403 확인

**PR 크기**: ~60 라인. SecurityConfig.java 한 파일.

### Phase 3 — Boot 2.7.18 → Boot 3.4.x BIG BANG — HIGH RISK

목표: 한 번에 jakarta 전환 + Boot 3 BOM 적용.

**3.1 pom.xml 변경:**
```diff
- <version>2.7.18</version>
+ <version>3.4.5</version>           <!-- 최신 stable 시점 확인 -->

- <java.version>11</java.version>
+ <java.version>17</java.version>

- <artifactId>thymeleaf-extras-springsecurity5</artifactId>
+ <artifactId>thymeleaf-extras-springsecurity6</artifactId>

- <version>3.1.4</version>           <!-- mariadb-java-client -->
+ <version>3.4.1</version>
```

**3.2 javax → jakarta 일괄 치환 (28 파일):**

```bash
# group A: persistence
grep -rl "javax\.persistence" src/main/java | xargs sed -i 's|javax\.persistence|jakarta.persistence|g'

# group B: servlet
grep -rl "javax\.servlet" src/main/java | xargs sed -i 's|javax\.servlet|jakarta.servlet|g'

# group C: annotation (PostConstruct/PreDestroy 만!)
grep -rl "javax\.annotation\.\(PostConstruct\|PreDestroy\)" src/main/java | \
  xargs sed -i 's|javax\.annotation\.PostConstruct|jakarta.annotation.PostConstruct|g; s|javax\.annotation\.PreDestroy|jakarta.annotation.PreDestroy|g'

# group D: transaction (1 파일)
sed -i 's|javax\.transaction\.Transactional|jakarta.transaction.Transactional|g' \
  src/main/java/com/heapdump/analyzer/service/AiInsightManager.java
```

**주의: `javax.crypto.*` / `javax.net.ssl.*` / `javax.sql.*` / `javax.xml.*` 는 절대 치환 금지** — 위 grep 패턴이 정확히 그것들을 비껴가는지 dry-run 으로 확인 (`grep -n "javax\." src/main/java -r --include="*.java" | grep -v "crypto\|net\|sql\|xml"` 으로 사전 확인).

**3.3 SecurityConfig.java 추가 변경:**
- `antMatchers` → `requestMatchers` (전부)
- `HttpMethod.POST` import 그대로 (org.springframework.http)

**3.4 application.properties:**
```diff
- spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MariaDB103Dialect
+ # Hibernate 6 dialect 자동 감지 (MariaDB 자동 인식). 명시 필요 시:
+ # spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MariaDBDialect
```

**3.5 Spring Session 테이블 초기화 (선택):**
```sql
TRUNCATE SPRING_SESSION_ATTRIBUTES;
TRUNCATE SPRING_SESSION;
```
→ 모든 사용자 재로그인 필요. 미실행 시 기동 후 row 호환성 확인.

**검증 (smoke test 체크리스트):**

| 분류 | 검증 항목 |
|---|---|
| 인증 | `/login` 로그인 / 로그아웃 / `/login?error` / `/login?logout` |
| 권한 | ADMIN 계정 → `/admin/users` 4 탭, `/admin/leak-rules` CRUD |
| 권한 | USER 계정 → `/admin/*` 403 / `/api/llm/enabled` 403 |
| 파일 | upload (5GB 대용량 1개) / 중복 검사 / download |
| 분석 | 분석 실행 → SSE 진행 → 결과 페이지 → KPI/Top/Suspects/Threads/RawData |
| AI | AI 인사이트 실행 → 단계 indicator → 결과 저장 |
| AI Chat | `/ai-chat` 세션 생성 / 메시지 / 스트리밍 / 세션 재진입 |
| RAG | `/settings/rag` config save / `Test connection` |
| Server | `/servers` 추가 / SSH 스캔 / SCP 전송 / `/servers/logs` |
| Compare | `/compare?base=...&target=...` 결과 / Comparison History 저장 |
| PDF | 분석 페이지 → PDF 다운로드 (openhtmltopdf) |
| 세션 | 로그인 → 앱 재시작 → 세션 유지 (Spring Session JDBC) |
| CSRF | `/api/admin/active-sessions/{id}` DELETE (브라우저) — CSRF 헤더 자동 부착 확인 |

**PR 크기**: ~50 파일 (sed 자동 치환 + pom.xml + properties + SecurityConfig). 컴파일 에러 손볼 가능성 있음.

### Phase 4 — Post-upgrade 안정화

- 컴파일 경고 정리 (deprecated API 호출)
- 사용자 보고된 회귀 fix
- 운영 부하 모니터링 (Tomcat 10 / Hibernate 6 성능 차이)

### Phase 5 — 문서/메모리 갱신

- `CLAUDE.md` 의 "Spring Boot 2.7.18 + Java 11" 부분 → "Spring Boot 3.4.x + Java 17 (JVM 21)" 갱신
- `CHANGELOG.md` 종합 entry 추가
- 자동 memory:
  - `project_boot3_migration.md` 생성 (완료 시점 / 운영 영향 / 후속 TODO)

---

## 5. 롤백 전략

- 각 phase 가 독립 PR 이므로 git revert 1회로 phase 단위 롤백.
- **Phase 3 만 DB 상태 변경 위험** — Hibernate 6 가 `ALTER TABLE` 을 실행한 후엔 단순 코드 revert 만으론 부족.
  → Phase 0 에서 받은 `backup-pre-boot3.sql` 로 schema 재적용 가능.
- 운영 jar 보관: `target/heap-analyzer-2.0.3-boot2.jar` 로 백업 → 긴급 시 즉시 교체 가능.

---

## 6. 예상 소요

| Phase | 작업 시간 | 검증 시간 | 누적 |
|---|---|---|---|
| 0 | 0.5h | — | 0.5h |
| 1 | 0.5h | 0.5h | 1.5h |
| 2 | 1.5h | 1h | 4h |
| 3 | 4h | 4h | 12h |
| 4 | 가변 | 가변 | — |
| 5 | 1h | — | +1h |

**대략 dev 기준 2일** (Phase 3 의 컴파일 에러 수습 시간이 변수).

---

## 7. 진행 가이드 (다음 액션)

1. 본 plan 검토 → 승인 시 Phase 0 (브랜치 생성 + DB 백업) 시작 지시.
2. Phase 단위로 별도 대화 / 별도 PR 진행 권장 — 한 PR 에 모두 묶지 말 것.
3. Phase 1·2 는 운영 적용 후에도 안정성 검증되면 main merge 가능. Phase 3 는 **유지보수 점검 시간대 적용** 필수.

---

## 부록 A. javax → jakarta 치환 dry-run 검증 명령

```bash
# 치환 대상 사전 확인 (Phase 3 전 dry-run)
grep -rn "javax\." src/main/java --include="*.java" | \
  grep -vE "javax\.(crypto|net|sql|xml)" | \
  awk -F: '{print $1}' | sort -u
```
→ 위 목록 = 28 파일과 일치해야 함. 추가 파일 발견 시 별도 분류 후 진행.

## 부록 B. Boot 3 호환성 사전 검증 (선택)

```bash
# Boot 3 migrator 도구 활용 가능 (선택)
# spring-boot-properties-migrator 모듈을 일시 추가하면
# 기동 시 deprecated 프로퍼티 콘솔 경고
```
