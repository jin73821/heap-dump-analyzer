# 프레임워크 및 라이브러리 버전 명세서

| 항목 | 값 |
|---|---|
| 대상 시스템 | Heap Dump Analyzer (`com.heapdump:heap-analyzer`) |
| 애플리케이션 버전 | **2.3.4** (`pom.xml <version>`) |
| 산출물 | `target/heap-analyzer-2.3.4.jar` (fat JAR, 약 83 MB) |
| 기준일 | 2026-08-12 |
| 산출 근거 | `pom.xml`, `mvn -o dependency:tree` 실측, 운영 호스트 실측 |

> 본 명세서의 모든 버전은 **추정이 아닌 실측값**이다. 재현 명령은 §10 참조.
> Spring Boot BOM(`spring-boot-starter-parent:3.5.14`)이 관리하는 버전은 pom.xml에 명시돼 있지 않으므로, 아래 표의 값은 의존성 해석(resolve) 결과다.

> **범위:** 프레임워크 · 라이브러리 · 외부 연동 구성요소. **빌드/실행 플랫폼(JDK · Maven)은 본 명세서 범위에서 제외**한다(의도적 제외이며 누락이 아님). 단, 라이브러리 버전의 산출 근거인 `mvn dependency:tree` 는 §10 검증 절차로서 유지한다.

---

## 1. 실행 환경 (Runtime Platform)

| 구성요소 | 버전 | 비고 |
|---|---|---|
| OS | Rocky Linux 8.10 (Green Obsidian) | 커널 `4.18.0-553.100.1.el8_10.x86_64` |
| 서블릿 컨테이너 | 내장 Tomcat 10.1.54 | Jakarta Servlet 6.0 레벨 |
| 운영 포트 | 18080 (`env.sh` `SERVER_PORT`) | `application.properties` 기본값은 8080, 기동 시 `--server.port` 로 override |
| 운영 JVM 힙 | `-Xms256m -Xmx512m` | `env.sh` `JVM_XMS`/`JVM_XMX`, 환경변수 override 가능 |

---

## 2. 코어 프레임워크

| 프레임워크 | 버전 | 용도 | 관리 주체 |
|---|---|---|---|
| Spring Boot | **3.5.14** | 자동설정 · 의존성 BOM · 실행 JAR 패키징 | pom.xml 직접 지정 (parent BOM) |
| Spring Framework | **6.2.18** | DI 컨테이너 + MVC 웹 계층 | BOM (`spring-core`/`beans`/`context`/`web`/`webmvc`/`aop`/`tx`/`orm`/`jdbc`/`expression`/`jcl`) |
| Spring Security | **6.5.10** | 인증/인가 · CSRF · 세션 고정 보호 | BOM (`spring-security-core`/`config`/`web`/`crypto`) |
| Spring Data JPA | **3.5.11** | 리포지토리 추상화 · `Specification` 동적 쿼리 | BOM (`spring-data-commons` 3.5.11 동반) |
| Spring Session JDBC | **3.5.6** | 세션을 MariaDB(`SPRING_SESSION`)에 영속화 | BOM (`spring-session-core` 3.5.6) |
| Hibernate ORM | **6.6.49.Final** | JPA 구현체 · DDL 자동 생성 | BOM (`hibernate-commons-annotations` 7.0.3.Final) |
| Thymeleaf | **3.1.5.RELEASE** | 서버사이드 HTML 템플릿 엔진 | BOM (`thymeleaf`, `thymeleaf-spring6`) |
| Thymeleaf Security Extras | **3.1.5.RELEASE** | 템플릿 내 `sec:authorize` 권한 분기 | `thymeleaf-extras-springsecurity6` |
| Apache Tomcat (embed) | **10.1.54** | 내장 서블릿 컨테이너 | `tomcat-embed-core`/`-websocket`/`-el` |
| **Jackson** | **2.21.2** | **JSON 직렬화/역직렬화** — REST 응답, `analysis_result_detail.result_json`, `settings.json`, LLM/ES 응답 파싱 (§2.1 참조) | `jackson-databind`/`-core`, `jackson-annotations` 2.21, `datatype-jdk8`/`-jsr310`, `module-parameter-names` |
| HikariCP | **6.3.3** | JDBC 커넥션 풀 | BOM |
| Hibernate Validator | **8.0.3.Final** | Bean Validation 구현체 | BOM |
| Micrometer | **1.15.11** | 관측(Observation) API — Boot 기본 계측 | `micrometer-observation`/`-commons` |

### 2.1 Jackson — 이 앱에서의 역할

Spring Boot 가 `spring-boot-starter-web` 으로 자동 포함하는 **JSON 처리 라이브러리**(FasterXML). 이 앱은 자동 설정에 얹혀 쓰는 데 그치지 않고 **직접 API 를 호출**한다.

| 사용 방식 | 실측 | 대표 위치 |
|---|---|---|
| Spring MVC 기본 메시지 컨버터 | 전 `/api/**` 응답 | `@RestController` 2곳 + `@Controller` + 메서드 `@ResponseBody` **13개 컨트롤러** → 반환 객체를 JSON 으로 |
| `ObjectMapper` 직접 사용 | **9개 클래스** | 분석 상세 JSON 저장/복원, `settings.json` 영속화, AI 인사이트, Dominator Refs, 룰 시딩 |
| `JsonNode` 트리 파싱 | **7개 클래스** | LLM 응답(Claude/OpenAI 계열 스키마 차이 흡수), SSE 델타, Elasticsearch 검색 결과 |
| `@JsonIgnoreProperties` | **14개 모델** | 저장된 JSON 에 없던/사라진 필드가 있어도 역직렬화 실패하지 않도록 (스키마 진화 방어) |
| `@JsonIgnore` | 9곳 | 크기가 큰 필드를 저장 대상에서 제외 (예: `threadStacksText` → `.threads` 파일 lazy-load) |
| `jackson-datatype-jsr310` | 모듈 | `LocalDateTime` 등 Java 8 날짜 타입 직렬화 |

> Jackson 이 사실상 **DB 저장 포맷의 스키마**를 결정한다(`analysis_result_detail` · `analysis_dominator_refs` · `ai_insights` 가 모두 JSON 컬럼). 모델 필드를 추가/삭제할 때 `@JsonIgnoreProperties` 가 없으면 **기존 저장분 복원이 깨진다.**

### 로깅 스택

| 라이브러리 | 버전 |
|---|---|
| Logback | 1.5.32 (`logback-classic`/`-core`) |
| SLF4J API | 2.0.17 (`jul-to-slf4j`, `jcl-over-slf4j` 동일) |
| log4j-to-slf4j / log4j-api | 2.24.3 (브리지 전용, Log4j2 코어 미사용) |

### 기반 유틸 (전이 의존)

`snakeyaml 2.4` · `byte-buddy 1.17.8` · `antlr4-runtime 4.13.0` · `jandex 3.2.0` · `classmate 1.7.3` · `jaxb-runtime/jaxb-core/txw2 4.0.6` · `angus-activation 2.0.3` · `istack-commons-runtime 4.1.2` · `jboss-logging 3.6.3.Final` · `aspectjweaver 1.9.25.1` · `jspecify 1.0.0`

---

## 3. Jakarta EE API 레벨

Boot 3 마이그레이션(2026-05-19)으로 `javax.*` → `jakarta.*` 전환 완료. 스펙 레벨은 다음과 같다.

| 스펙 | API 버전 | 아티팩트 |
|---|---|---|
| Jakarta Persistence | 3.1.0 | `jakarta.persistence-api` |
| Jakarta Transactions | 2.0.1 | `jakarta.transaction-api` |
| Jakarta Annotations | 2.1.1 | `jakarta.annotation-api` |
| Jakarta Validation | 3.0.2 | `jakarta.validation-api` |
| Jakarta Inject | 2.0.1 | `jakarta.inject-api` |
| Jakarta XML Binding | 4.0.4 | `jakarta.xml.bind-api` |
| Jakarta Activation | 2.1.4 | `jakarta.activation-api` |
| Jakarta Servlet | 6.0 | Tomcat 10.1 내장 (별도 API JAR 없음) |

---

## 4. 직접 선언 라이브러리 (pom.xml 명시 버전)

BOM이 관리하지 않아 **업그레이드 시 pom.xml을 직접 고쳐야 하는** 항목들이다.

| 라이브러리 | 버전 | 용도 | 주요 전이 의존 |
|---|---|---|---|
| `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer` | **20220608.1** | MAT 리포트 HTML XSS 방어 (`HtmlSanitizer`) | **guava 30.1-jre**, jsr305 3.0.2, checker-qual 3.5.0, error_prone_annotations 2.3.4, j2objc-annotations 1.3 |
| `commons-io:commons-io` | **2.15.1** | 파일 I/O 유틸 (`FileUtils`) | — |
| `org.mariadb.jdbc:mariadb-java-client` | **3.4.2** | MariaDB JDBC 드라이버 | waffle-jna 3.3.0, jna/jna-platform 5.13.0, caffeine 3.2.3 |
| `com.openhtmltopdf:openhtmltopdf-core` | **1.0.10** | 분석 리포트 PDF 렌더링 | — |
| `com.openhtmltopdf:openhtmltopdf-pdfbox` | **1.0.10** | PDF 백엔드 (한글 폰트 임베딩) | **pdfbox/fontbox/xmpbox 2.0.24**, graphics2d 0.32, commons-logging 1.2 |
| `com.google.zxing:core` | **3.5.3** | OTP 등록 QR 생성 | — |
| `com.google.zxing:javase` | **3.5.3** | QR PNG 인코딩 | jcommander 1.82, jai-imageio-core 1.4.0 |
| `org.projectlombok:lombok` | **1.18.46** (BOM) | 보일러플레이트 제거 | `optional=true`, spring-boot-maven-plugin `excludes`로 **JAR 미포함** |

### ⚠ 업그레이드 검토 대상 (구버전 추이 의존)

BOM 밖에 있어 Boot 업그레이드만으로는 갱신되지 않고, 릴리스 시점이 오래된 항목이다. **취약점 스캐너(OWASP dependency-check 등) 결과를 근거로 판단할 것** — 아래는 "노후도" 지적이지 특정 CVE 확정이 아니다.

- **guava 30.1-jre** — sanitizer(20220608.1)가 끌어오는 값. sanitizer를 최신으로 올리거나 `dependencyManagement`로 guava를 상향 고정하는 두 경로가 있다.
- **pdfbox 2.0.24** — openhtmltopdf 1.0.10이 고정. openhtmltopdf 1.1.x 계열은 PDFBox 2.0.2x 상위/3.x 대응이 갈리므로 PDF 출력 회귀 검증(한글 폰트 임베딩) 필수.
- **owasp-java-html-sanitizer 20220608.1** — 2022년 릴리스. XSS 방어 경로의 핵심이라 우선순위 높음.

---

## 5. 테스트 스택

`spring-boot-starter-test:3.5.14` (scope: test) 로 일괄 도입. 총 **단위 테스트 328건 / 테스트 클래스 12개**.

| 라이브러리 | 버전 |
|---|---|
| JUnit Jupiter | 5.12.2 (`junit-platform` 1.12.2) |
| Mockito | 5.17.0 (`mockito-core`, `mockito-junit-jupiter`, byte-buddy-agent 1.17.8, objenesis 3.3) |
| AssertJ | 3.27.7 |
| Hamcrest | 3.0 |
| Awaitility | 4.2.2 |
| JSONassert | 1.5.3 |
| JsonPath | 2.9.0 (json-smart 2.5.2, accessors-smart 2.5.2, asm 9.7.1) |
| XMLUnit | 2.10.4 |
| Spring Test | 6.2.18 |

주요 회귀 방어 스위트: `AesEncryptorTest`(237) · `SecretSanityTest`(22) · `RagConfigServiceSecretTest`(11) · `LeakSuspectAdvisorGoldenTest`(10) · `ResultDirectorySchemeTest`(5) · `DominatorRefsEmptyGuardTest`(5) · `MatReportParserSuspectsTest`(5) · `SettingsRestoreIsolationTest`(4)

---

## 6. 프론트엔드

**빌드 도구 없음** (npm/webpack/babel 미사용). Thymeleaf 서버 사이드 렌더 + vanilla JS(ES5 스타일 문법). 모든 정적 리소스는 **JAR 내부에 번들**되며 외부 CDN 참조가 **0건**이다(폐쇄망 운영 전제).

| 구성요소 | 버전 | 위치 |
|---|---|---|
| **Chart.js** | **4.4.0** (UMD, minified, MIT) | `static/js/lib/chart.umd.min.js` — 유일한 서드파티 프론트 라이브러리 |
| CSS 프레임워크 | 없음 (자체 작성) | `common.css` / `analyze.css` / `core-dump.css` / `style.css` |
| 아이콘·폰트 | 외부 로드 없음 | — |

### 자체 JS 모듈 (버전 관리 = 캐시 무효화 쿼리)

`common.js`(Common 네임스페이스) · `table-grid.js` · `select-mode.js` · `float-tooltip.js` · `krds-tooltip.js`(KRDS component_08_05) · `memo.js` · `analyze.js` · `upload-queue.js` · `calendar.js` · `chart.js`(Chart.js 설정) · `core-dump-index.js` / `-analyze.js` / `-progress.js` · `main.js`

> 정적 리소스 버전 규약: `?v=YYYY-MM-DD[a-z]` 쿼리 파라미터로 캐시 무효화. CSS/JS 변경 시 전 페이지 일괄 갱신.

---

## 7. 외부 시스템 · CLI 의존성

애플리케이션 외부에 **별도로 설치·운영되는** 구성요소. 버전 불일치 시 기능 단위로 실패한다.

| 구성요소 | 실측 버전 | 연동 방식 | 필수 여부 |
|---|---|---|---|
| **Eclipse MAT** | **1.16.1** (빌드 `202501091339`) | `/opt/mat/ParseHeapDump.sh` CLI, 타임아웃 30분 | **필수** — 핵심 분석 엔진 |
| MAT 런처 (Equinox) | `org.eclipse.equinox.launcher 1.6.900.v20240613-2009` | `MemoryAnalyzer.ini` | 필수 |
| MAT JVM 힙 | `-Xms128m -Xmx2048m` | `MemoryAnalyzer.ini` | 동시 실행 슬롯 산정 근거(`recomputeMatConcurrency()`) |
| **MariaDB Server** | **11.2.3-MariaDB** | JDBC `192.168.56.9:3306/HEAPDB` (utf8mb4, Asia/Seoul) | **필수** — 미연결 시 기동 실패 |
| **GDB** | **8.2** (Rocky Linux `8.2-20.el8.0.1`) | `gdb.cli.path`, 타임아웃 10분 | 코어덤프 분석 기능 한정 |
| OpenSSH (`ssh`/`scp`) | OS 제공 | `runuser -l sscuser -c "scp ..."` 2단계 전송 | 원격 덤프 수집 한정 |
| **Elasticsearch** | 사내 서버 (버전 미확인) | REST `/_search`, `/_cluster` | RAG 기능 한정 (선택) |
| Node.js | 10.24.0 | 헤드리스 UI 검증(puppeteer-core 2.1.1) | 개발 검증 도구 (운영 무관) |

### Elasticsearch 요구 버전 (참고)

현재 설정값이 플레이스홀더(`https://es.example.local:9200`)라 **연동 대상 서버의 실제 버전은 미확인**이다. 앱이 사용하는 쿼리 API 기준 필요 조건은 다음과 같으므로, 사내 ES 도입 시 확인이 필요하다.

| 검색 모드 | 사용 API | 요구 조건 |
|---|---|---|
| `keyword` | BM25 `match` | ES 7.x 이상이면 무관 |
| `semantic-server` | `text_expansion`(ELSER) 또는 `semantic`(semantic_text) | ELSER 모델 배포 필요 — ES 8.x 계열 + ML 노드 |
| `semantic-client` | `knn` (앱이 임베딩 생성 후 벡터 질의) | `dense_vector` 필드 + kNN 지원 ES 8.x |

---

## 8. 외부 API 계약 버전 (LLM / 임베딩)

라이브러리가 아니라 **HTTP 계약**으로 물려 있는 버전들이다. SDK를 쓰지 않고 `HttpURLConnection`으로 직접 호출하므로, 아래 값이 사실상의 인터페이스 명세다.

| 대상 | 엔드포인트 | 버전 고정 값 |
|---|---|---|
| Anthropic (Claude) | `https://api.anthropic.com/v1/messages` | 헤더 **`anthropic-version: 2023-06-01`** — 코드 5곳에 하드코딩 |
| OpenAI 호환 | `https://api.openai.com/v1/chat/completions` | Chat Completions 계약 |
| Genspark | 사내 게이트웨이 (설정값) | `GENSPARK_MODELS` 목록으로 모델 제한 |
| Custom | 임의 (OpenAI 호환) | — |
| 임베딩 provider | openai / cohere / custom | `EmbeddingService`, `semantic-client` 모드 전용 |

현재 운영 설정 모델: `llm.provider=claude`, `llm.model=claude-sonnet-4-6` (`application.properties`, 런타임에 `/settings/llm` 에서 변경 가능 — 설정값이 최종 권위).

---

## 9. 버전 정합성 규약 (업그레이드 시 필독)

버전을 올릴 때 **코드 수정만으로 끝나지 않는** 항목들이다.

1. **앱 버전 변경 시** — `pom.xml <version>` 과 UI 표기 3곳(`fragments/banner.html`, `index.html`, `progress.html`)을 함께 갱신. 쉘 스크립트(`run.sh`/`restart.sh`/`stop.sh`/`heap_enc.sh`/`heap_dec.sh`)는 전부 버전 비의존이라 수정 불필요.
2. **Spring Session 3.x ↔ 2.x 직렬화 비호환** — Boot 버전을 올리거나 되돌릴 때 `TRUNCATE SPRING_SESSION_ATTRIBUTES; TRUNCATE SPRING_SESSION;` 필수. 전 사용자 재로그인 발생.
3. **Hibernate 6의 `@Lob String` 기본 매핑 축소** — Hibernate 5의 `longtext` → 6에서 `tinytext(255)`. 신규 String 컬럼이 255바이트를 넘을 수 있으면 `@Column(columnDefinition = "TEXT")` 명시.
4. **Jackson 모델 필드 변경 시** — `analysis_result_detail` 등 JSON 컬럼에 이미 저장된 값이 있으므로, 모델에 `@JsonIgnoreProperties(ignoreUnknown = true)` 를 유지해야 **기존 저장분 복원이 깨지지 않는다**(§2.1).
5. **Thymeleaf 3.1 fragment 문법** — `th:replace="~{fragments/banner :: banner}"` 형태 유지(3.0 문법 폐기).
6. **프론트 리소스는 JAR 내부** — CSS/HTML/JS 어떤 변경이든 재빌드 + 재기동 필수(약 20~24초).
7. **Chart.js 교체 시** — CDN 도입 금지(폐쇄망). `static/js/lib/` 에 파일을 교체하고 `?v=` 캐시 키를 갱신.

---

## 10. 재현·검증 명령

```bash
# 애플리케이션 버전
grep -m1 "<version>" pom.xml                     # 2.3.4 (parent 다음 블록)

# 전체 의존성 트리 (직접 + 전이, scope 포함)
mvn -o dependency:tree

# 특정 라이브러리 해석 버전 확인
mvn -o dependency:tree -Dincludes=org.hibernate.orm

# OS
cat /etc/redhat-release

# Eclipse MAT
ls /opt/mat/plugins | grep org.eclipse.mat.api    # org.eclipse.mat.api_1.16.1.*

# MariaDB 서버 (핸드셰이크 배너 — 자격증명 불필요)
python3 -c "import socket;s=socket.create_connection(('192.168.56.9',3306),3);\
print(s.recv(128)[5:].split(b'\x00')[0].decode())"

# Chart.js
head -5 src/main/resources/static/js/lib/chart.umd.min.js

# GDB
gdb --version | head -1
```

---

## 부록: 버전 이력 (주요 스택 전환)

| 시점 | 전환 내용 |
|---|---|
| 2026-05-19 | **Boot 2.7.18 → 3.5.14** / Security 5.7.11 → 6.5.x / Hibernate 5.6 → 6.6 / Tomcat 9 → 10. 50파일 변경, jakarta 네임스페이스 28파일 일괄 치환. 상세: `BOOT3_MIGRATION_PLAN.md` |
| 2026-07-31 | 저장 스킴 전환 — 분석 상세/Dominator Refs/AI 인사이트를 파일에서 **DB 단일 원본**으로 이관 |
| 2026-08-10 | 기동 스크립트 `env.sh` 단일 지점 통합 — 쉘 스크립트 **버전 하드코딩 제거** |

---

*본 문서는 실측 기준일(2026-08-12)의 스냅샷이다. 의존성 변경 시 `CHANGELOG.md` 기록과 함께 본 문서도 갱신할 것.*
