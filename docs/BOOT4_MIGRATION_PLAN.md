# Spring Boot 4.1 전환 계획 (오픈소스 취약점 조치 2단계)

> 작성: 2026-09-11 · 상태: **완료 (2026-09-11 운영 배포)** · 선행: 1단계 v2.4.2 (`SECURITY_AUDIT_OSS_CVE.md`)
> 결과 버전: heap-analyzer **2.5.0** — Boot 4.1.1 / Spring 7.0.9 / Security 7.1.1 / Data 4.1.1 / Session 4.1.1 / Hibernate 7.4.5 / Tomcat 11.0.25 / Jackson 3.1.5

## 0. 실행 결과 (2026-09-11)

| 항목 | 결과 |
|---|---|
| 백업 | `/opt/genspark/backup/boot4-20260911/` — HEAPDB mysqldump(8.9MB, 20 테이블) · 행 수 · 전환 전 스키마 · 2.4.2 JAR · settings.json · application.properties |
| 코드 변경 | Jackson 3 이관 15파일(import `tools.jackson.*`, `JsonNode.fields()`→`properties()`, `copy()`→`rebuild()…build()`, `catch (IOException)`→`JacksonException` 1곳) · Boot 패키지 이동 6클래스 · 테스트 1건(`getSettings().getMimeMappings()`) · 스타터 교체(`webmvc`, `session-jdbc`) · 설정 키 3개 |
| 점검 기준 | 새 JAR `BOOT-INF/lib` 18개 점검 항목 **전부 기재 최신 이상 또는 부재** (Jackson 2.x·guava·log4j-api 부재) |
| 단위 테스트 | **589건 통과** / 4 skip (전환 전과 동일) |
| Hibernate 7 DDL | `SchemaMigrator` 스크립트 전용 추출(DB 미실행)로 사전 검토 → **0건** (6.6 기준선도 0건). 양성 대조군(가짜 엔티티)으로 추출기 정상 동작 확인. 기동 로그의 DDL 실행 0건 |
| Jackson 3 역호환 | 운영 DB 의 Jackson 2 저장 JSON 전량 역직렬화 성공 — 분석 상세 23/23 · Dominator refs 5/5 · AI 인사이트 22/22. 기동 시 `Restored 23 saved results` |
| 세션 | 운영 `SPRING_SESSION_ATTRIBUTES` 8건을 Security 7.1 클래스패스로 역직렬화 **8/8 성공** → **TRUNCATE 불필요**(재로그인 강제 없음). 테이블 구조도 4.1.1 스키마와 동일 |
| properties-migrator | 경고 2건(`logging.file.max-size/max-history` → `logging.logback.rollingpolicy.*`) 반영 후 의존성 제거 |
| 기동·서빙 | 약 15초 기동 · 페이지 200 · CSS/JS `charset=UTF-8` · 미인증 API 401 JSON · 페이지 302 `/login` · 로그인 실패 `?error=true` · SAMEORIGIN · 신규 세션 JDBC 저장 |

### 0.1 Java 17 / 21 호환성 검증 (2026-09-11)

서버에는 JDK 17 이 설치돼 있지 않다(1.8 과 21 GA `21+35` 만 있다). 그래서 **Temurin tarball 을 임시 경로에 풀어** 검증했다. 시스템 alternatives 는 건드리지 않았다 — 패키지로 설치하면 기본 `java` 가 바뀌어 운영 기동 JDK 가 달라질 수 있기 때문이다.

| 검증 | JDK 17 (Temurin 17.0.20.1) | JDK 21 (Temurin 21.0.12.1) | JDK 21 (설치본 21+35) |
|---|---|---|---|
| 바이트코드 | 앱 219 · 로더 99 · 라이브러리 108 jar 전부 **major ≤ 61** → 17 에서 로드 가능 | ← | ← |
| JDK 내부 API (`jdeps --jdk-internals`) | `sun.misc.Signal`(앱 셧다운 훅) · `sun.misc.Unsafe`(spring-core 내장 objenesis) — 둘 다 `jdk.unsupported` 로 17/21 공식 개방 | ← | ← |
| 컴파일 + 전체 테스트 | `mvn clean package` **589 통과** / 실패 0 | **589 통과** / 실패 0 | **589 통과** / 실패 0 |
| 기동 (운영 슬롯, `JAVA_BIN` 교체) | 16.1초 · 결과 23건 복원 · 신규 WARN/ERROR 0 | 14.7초 · 동일 | 15.3초 · 동일 (**현재 운영 런타임**) |
| 스모크 | 페이지 200 · JS/CSS `charset=UTF-8` · 미인증 API 401 JSON · `/` 302 · 로그인 실패 `?error=true`(CSRF 폼) · `-Xlog:gc*` 정상 | ← | ← |

- 사본 빌드에서만 skip 이 2건 더 나왔다(6 vs 4). 원인은 JDK 가 아니라 사본에 `rag-data/` 를 복사하지 않은 것이다. 해당 두 클래스(`CsvCodecTest`·`RagCorpusImportTest`, 30건)를 코퍼스 파일과 함께 17/21 에서 재실행해 **전건 통과(skip 0)** 를 확인했다. 남는 skip 4건은 Chroma 라이브 테스트(`-Dchroma.live=true` 필요)로 저장소와 같다.
- `env.sh` 의 JVM 옵션(`-Xms/-Xmx`, `-Xlog:gc*`, `-Dfile.encoding`, truststore 3종)은 17/21 모두 유효하다.
- ⚠ 설치된 JDK 21 은 **2023-09 GA 빌드(21+35)** 로 3년치 보안 패치가 빠져 있다. 21.0.12 로 올리는 것을 권장한다. 앱 쪽 변경은 필요 없고 `JAVA_BIN` 또는 PATH 만 바꾸면 된다.
- 이 앱이 띄우는 MAT CLI(`/opt/mat`)는 자체 JVM 설정을 쓰므로 이번 검증 범위 밖이다.

⚠ 아래 3~5절은 착수 전에 작성한 계획 원문이다. 실제로는 세션 TRUNCATE 가 불필요했고, Hibernate DDL 은 0건이었다.

## 1. 왜 해야 하나

- **OSS 지원 종료:** Spring Boot 3.5 · Spring Framework 6.2 의 OSS 지원은 **2026-06-30 에 끝났다.**
  3.5.16 / 6.2.19 가 마지막 OSS 릴리스라 이후 Spring 쪽 CVE 는 OSS 패치가 나오지 않는다.
  Tomcat 10.1 · Jackson 2.x · Logback 1.x 는 계속 패치가 나오므로 1단계처럼 BOM 속성 오버라이드로 버틸 수 있지만, Spring 본체는 그럴 수 없다.
- **점검 기준:** CVSS 점검(2026-09) 기준 "최신 버전" 은 Tomcat 11.0.24 / Spring 7.0.8 / Security 7.1.0 / Data 4.1.0 / Boot 4.1.0 이다.
  이 5종은 Servlet 6.1 · Jakarta EE 11 기반이라 **Boot 4 없이는 올릴 수 없다.**
- **전환 대상:** Boot **4.1.x** (OSS 지원 2027-07-31, 상용 연장 2028-07-31). 4.2 가 나오면 같은 방식으로 따라간다.

## 2. 목표 버전

| 컴포넌트 | 1단계 (v2.4.2) | 2단계 (v2.5.0) | 수단 |
|---|---|---|---|
| spring-boot(-autoconfigure) | 3.5.16 | **4.1.1** | parent |
| Spring Framework | 6.2.19 | **7.0.9** | BOM |
| Spring Security | 6.5.11 | **7.1.1** | BOM |
| Spring Data (commons) | 3.5.13 | **4.1.1** (release train 2026.0.1) | BOM |
| Spring Session | 3.5.7 | **4.1.1** | BOM |
| Hibernate | 6.6.53 | **7.4.5** | BOM |
| Tomcat | 10.1.59 | **11.0.25** (BOM 은 11.0.24) | `tomcat.version` |
| Jackson | 2.22.2 | **3.1.5** (`tools.jackson`) — 2.x 제거 | BOM |
| Logback | 1.6.3 | 1.6.3 (BOM 은 1.5.38) | `logback.version` 유지 |
| log4j-api / guava | 제거 | 제거 유지 | exclusion 유지 |
| commons-lang3 | 3.20.0 (오버라이드) | 3.20.0 (BOM 관리값 → 오버라이드 삭제) | — |

⚠ Boot 4.1 BOM 의 `jackson-2-bom` 은 2.21.5 로 점검 기준(2.22.1)에 못 미친다. Jackson 2 를 남기는 폴백 경로를 택하면 `jackson-2-bom.version=2.22.2` 오버라이드가 필요하다.

## 3. 코드 영향도 (2026-09-11 조사 — main 155 / test 43 파일)

### 3.1 Jackson 3 — 기동 실패를 부르는 유일한 항목
- Boot 4 의 기본 매퍼는 Jackson 3 `JsonMapper` 다.
  **`CoreDumpAnalyzerService:59` 가 Boot 의 Jackson 2 `ObjectMapper` 를 생성자로 주입받으므로 그대로 두면 기동에 실패한다.**
- `new ObjectMapper()` 필드 8곳: `HeapDumpAnalyzerService:85` · `LlmConfigService:69` · `RagService:31` · `EmbeddingService:43` · `AiInsightManager:42` · `ChromaSearchService:49` · `PdfReportService:49` · `LeakRuleSeeder:34`
  - FQN 사용: `HeapDumpAnalyzerService:696,3935-3939` (`TypeReference`/`JsonNode`)
  - 테스트 4개: `CoreDumpRevisionTest` · `CoreDumpFileListTest` · `CoreDumpSysrootCommandTest` · `LeakSuspectAdvisorGoldenTest`
- 모델 DTO 14개는 `com.fasterxml.jackson.annotation.*` 만 쓴다. **이 패키지는 Jackson 3 에서도 그대로라 변경할 필요가 없다.**
- **권장안 — Jackson 3 로 이관:**
  - import 를 `tools.jackson.databind.*` 로 교체한다.
  - `readValue`/`writeValue` 가 **unchecked `JacksonException`** 을 던지므로, 본문에 Jackson 호출만 있는 `catch (IOException)` 은 컴파일 오류("never thrown")가 난다. 이런 catch 를 정리한다.
  - `SerializationFeature.INDENT_OUTPUT` 은 Jackson 3 에도 있다.
  - Jackson 3 기본값 차이를 점검한다. `SORT_PROPERTIES_ALPHABETICALLY` 가 기본 true 인데, 프런트는 필드명으로 읽으므로 무영향 예상이다. 날짜 직렬화 기본값도 확인한다.
- **폴백안:** `spring-boot-jackson2` 모듈 + `jackson-2-bom.version=2.22.2` 로 두고 `CoreDumpAnalyzerService` 만 매퍼를 직접 생성하게 바꾼다. (2.x 가 jar 에 남아 점검 대상으로 계속 잡힌다.)

### 3.2 Boot 모듈화에 따른 패키지 이동
| 위치 | 현재 import | 조치 |
|---|---|---|
| `config/DataSourceConfig:4`, `controller/HeapSystemApiController:16` | `boot.autoconfigure.jdbc.DataSourceProperties` | `spring-boot-jdbc` 모듈 패키지로 교체. `DataSourceConfig:46` `initializeDataSourceBuilder()` 도 재확인 |
| `service/HeapDumpAnalyzerService:25` | `boot.autoconfigure.web.servlet.MultipartProperties` | servlet 모듈 패키지로 교체 |
| `config/StaticResourceCharsetConfig:5-7` | `MimeMappings` / `WebServerFactoryCustomizer` / `ConfigurableServletWebServerFactory` | 새 패키지로 교체 (함정 35 회귀 방어 유지) |
| `StaticResourceCharsetConfigTest` | `TomcatServletWebServerFactory` | `spring-boot-tomcat` 모듈 패키지로 교체 |

스타터: `spring-boot-starter-web` → `spring-boot-starter-webmvc` (구 이름은 deprecated 별칭).

### 3.3 Spring Security 7.1
- `SecurityConfig` 는 이미 lambda DSL 이다. `AntPathRequestMatcher` · `authorizeRequests` · `.and()` 는 0건이다.
- `requestMatchers(String...)` 약 10곳은 7.x 에서 `PathPatternRequestMatcher` 로 해석된다. **경로 중간의 `**` 는 불허**되므로 패턴을 전수 점검한다.
- ADMIN 매처 ↔ CSRF 보호 목록 1:1 미러링(CLAUDE.md 인증 절)을 회귀 확인한다.
- `http.userDetailsService(...)`(`SecurityConfig:40`)가 7.x 에서도 유지되는지 확인한다.

### 3.4 Spring Session 4.1
- 직접 사용하는 곳:
  - `JdbcIndexedSessionRepository` — `HeapSystemApiController:55-74`, `setDefaultMaxInactiveInterval` `:581`
  - `FindByIndexNameSessionRepository` — `AdminController:52,358`
- 속성 `spring.session.jdbc.schema=classpath:org/springframework/session/jdbc/schema-mysql.sql` 의 경로가 4.x 에 있는지 확인한다.
- **메이저 전환이라 세션 직렬화가 비호환일 수 있다(함정 15).** 배포 시 `TRUNCATE SPRING_SESSION_ATTRIBUTES; TRUNCATE SPRING_SESSION;` 가 필요하고 **전 사용자가 재로그인**한다.
  운영 DB 쓰기이므로 배포 시점에 별도 승인을 받는다.

### 3.5 Hibernate 7.4 / JPA 3.2
- Hibernate 전용 API(`org.hibernate.*` · `@Type` · 네이티브 쿼리)는 0건이다. JPQL `@Query` 11개, `Specification` 8곳이 있다.
- `spring.jpa.hibernate.ddl-auto=update` 이므로 **전환 전 HEAPDB 를 mysqldump 로 백업**한다.
  첫 기동 로그의 `alter table` 을 전수 검토한다(함정 16 계열 — 컬럼 타입 축소 시도 여부).
- `columnDefinition` 17곳(TEXT/MEDIUMTEXT/LONGTEXT)은 그대로 유지한다.

### 3.6 Framework 7 기타
- `HttpHeaders` 는 더 이상 `MultiValueMap` 이 아니다. 우리 코드는 `add()` 만 쓰므로(`HeapReportApiController:110`, `CoreDumpApiController:166`) 무영향이다.
- RestTemplate · PathMatcher · `org.springframework.lang` · `ListenableFuture` 사용은 0건이다.

### 3.7 설정 · 테스트 · 배포
- `spring.session.store-type=jdbc` 는 Boot 3 에서 이미 무효이므로 삭제한다.
  전환 기간에만 `spring-boot-properties-migrator` 를 넣어 이름이 바뀐 키를 탐지하고, 확인 후 제거한다.
- 테스트: `@MockBean` · `@SpringBootTest` 는 0건이고 standalone MockMvc 3개뿐이다. JSON 단언이 Jackson 3 기본값에 영향받는지 확인한다.
- 운영 요건(Java 17+ / 런타임 JDK 21 / Maven 3.9.9)은 이미 충족한다.
- k8s 통합 이미지(`/opt/genspark/heapApp_k8s`)는 새 JAR 로 재빌드한다.

## 4. 진행 순서 (Boot 3 전환과 같은 5 phase)

1. **백업:** HEAPDB mysqldump. 현재 JAR · settings.json 보존.
2. **사전 정비 (Boot 3.5 에서 가능한 것):** `CoreDumpAnalyzerService` 매퍼 주입 제거, `spring.session.store-type` 삭제, Security 매처 패턴 점검.
3. **BIG BANG:** parent 4.1.1 · 스타터 이름 교체 · 패키지 이동 import · Jackson 3 이관 · `tomcat.version` 11.0.25.
4. **안정화:** properties-migrator 경고 해소 · Hibernate DDL 로그 검토 · 테스트 전건 통과.
5. **배포:** 세션 테이블 TRUNCATE(승인 후) → 기동 → 회귀 스모크 → 점검 도구 재스캔.

## 5. 검증

- `mvn test` 전건 통과 (1단계 기준 589건).
- `BOOT-INF/lib` 전 컴포넌트가 CVSS 점검 "최신 버전" 이상인지 스크립트로 확인한다.
- 전 페이지 200 · ADMIN API · CSRF 403 · 세션 만료 401 JSON · OTP / 비밀번호 만료 부분 인증 · PDF · SSE(분석 진행 / AI 채팅) · 대용량 업로드 · `/settings/rag` 가져오기.
- 점검 도구 재스캔: 잔여 0건(오탐 CVE-2011-4457 은 소명).
