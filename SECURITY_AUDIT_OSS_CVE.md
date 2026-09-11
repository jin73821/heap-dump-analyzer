# 오픈소스 라이브러리 버전 취약점 조치 결과 보고서

> 점검 자료: `CVSS.txt` (대상 `heap-analyzer-2.4.1.jar` `BOOT-INF/lib`) — **59건 / 고유 CVE 53개 / 라이브러리 17종**
> 조치 기준: **점검표에 기재된 "최신 버전" 이상**
> 조치일: **2026-09-11** · 조치 버전: heap-analyzer **2.4.2**(1단계) → **2.5.0**(2단계, 운영 배포)
> 상태: **조치 완료** — CVE 53개 전부 영향 범위 이탈 + **점검표 전 컴포넌트가 기재 최신 이상 또는 부재** (7절)
> - 1단계(v2.4.2): 라이브러리 버전 상향 + log4j-api·guava 제거
> - 2단계(v2.5.0): Spring Boot 4.1 전환(OSS 지원 라인) — `BOOT4_MIGRATION_PLAN.md`
> ⚠ 아래 1~6절은 1단계 시점 기록이다. 최종 상태는 7절을 볼 것.

---

## 1. 요약

| 구분 | 결과 |
|---|---|
| 점검 CVE (고유) | 53개 |
| 1단계 후 **영향 범위에 남은 CVE** | **0개** — GHSA/NVD/Tomcat·Logback 공지의 영향 범위와 해석 버전을 기계 대조 |
| 기재 최신 이상 달성 | Jackson·Logback·commons-lang3·OWASP sanitizer (+ 컴포넌트 제거 2종: log4j-api·guava) |
| 기재 최신 미달 (CVE 는 해소됨) | Tomcat · Spring Framework · Spring Security · Spring Data · Spring Boot — **2단계(Boot 4.1 전환)에서 충족** |
| 오탐 | CVE-2011-4457 (OWASP sanitizer) 1건 — 5절 |

**왜 두 단계인가:** 점검표의 "최신 버전"은 **메이저 최신판**이다(Tomcat 11 / Spring 7 / Boot 4). 최소 수정 버전이 아니다.
53개 CVE 는 모두 현재 메이저 라인의 패치 버전에서 이미 수정됐으므로 1단계에서 **취약점부터 즉시 제거**했다.
Tomcat 11·Spring 7·Security 7.1·Data 4.1 은 Servlet 6.1·Jakarta EE 11 기반이라 Spring Boot 4 없이는 올릴 수 없다.
또 Boot 3.5 / Framework 6.2 의 **OSS 지원이 2026-06-30 에 종료**됐으므로, OSS 지원 라인인 Boot 4.1(2027-07-31 까지)로 전환하는 작업을 2단계로 둔다.

## 2. 컴포넌트별 조치

| 컴포넌트 | 검출 | 점검표 최신 | **1단계 (적용)** | 기재 최신 충족 | 2단계 |
|---|---|---|---|---|---|
| tomcat-embed-core / websocket | 10.1.54 | 11.0.24 | **10.1.59** | 미달 (CVE 해소) | 11.0.25 |
| jackson-core / databind | 2.21.2 | 2.22.1 | **2.22.2** | ✅ | Jackson 3.1.5 |
| spring-web / webmvc / core / expression | 6.2.18 | 7.0.8 | **6.2.19** | 미달 (CVE 해소) | 7.0.9 |
| spring-data-commons | 3.5.11 | 4.1.0 | **3.5.13** | 미달 (CVE 해소) | 4.1.1 |
| spring-security-web / config | 6.5.10 | 7.1.0 | **6.5.11** | 미달 (CVE 해소) | 7.1.1 |
| spring-boot-autoconfigure | 3.5.14 | 4.1.0 | **3.5.16** | 미달 (CVE 해소) | 4.1.1 |
| logback-core / classic | 1.5.32 | 1.6.2 | **1.6.3** | ✅ | 유지 |
| log4j-api | 2.24.3 | 3.0.0-beta2 | **제거** | ✅ (컴포넌트 부재) | 유지 |
| guava | 30.1-jre | 33.6.0-jre | **제거** | ✅ (컴포넌트 부재) | 유지 |
| commons-lang3 | 3.17.0 | 3.20.0 | **3.20.0** | ✅ | 유지 |
| owasp-java-html-sanitizer | 20220608.1 | r239 | **20260313.1** | ✅ (5절) | 유지 |

### 적용 방법 (`pom.xml`)
- `spring-boot-starter-parent` 3.5.14 → **3.5.16** (3.5 라인 최종 OSS 패치). 이것으로 Spring 6.2.19 / Security 6.5.11 / Data 3.5.13 이 들어온다.
- BOM 속성 오버라이드: `tomcat.version=10.1.59`, `jackson-bom.version=2.22.2`, `logback.version=1.6.3`, `commons-lang3.version=3.20.0`
  ⚠ 전부 CVE 조치용이다. 삭제하면 Boot 관리값(취약 버전)으로 되돌아간다.
- **log4j-api 제거:**
  - 기재 최신 3.0.0-beta2 는 **프리릴리스**라 운영에 채택할 수 없다.
  - 이 앱에서 log4j-api 는 `spring-boot-starter-logging` 의 브리지 `log4j-to-slf4j` 를 통해서만 들어왔다. 소스·의존성 어디에도 Log4j2 API 사용이 없으므로 브리지를 exclusion 해 컴포넌트를 없앴다.
  - 여러 스타터가 전이로 끌어오기 때문에 로깅 스타터를 **직접 선언**(최근접 노드)해야 exclusion 이 확실히 적용된다.
- **guava 제거:** OWASP sanitizer 가 20240325.1 부터 guava 대신 자체 java8/java10-shim 을 쓴다. guava 를 끌어오던 것은 sanitizer 하나뿐이었다.
- 정리: `commons-io` 중복 선언(2.15.1/2.16.1) 중 2.15.1 을 삭제했다. 해석 결과는 종전에도 2.16.1 이라 동작 변화는 없다.

Tomcat 목표를 최소 수정판 10.1.56 이 아니라 **10.1.59** 로 잡은 이유: 10.1.59 에서 점검표 밖의 **CVE-2026-65182 (Important, Security constraint bypass)** 도 수정됐기 때문이다.

## 3. CVE별 조치 내역 (점검표 59행)

근거 표기:
- `GHSA`: GitHub Advisory DB 의 영향 범위·최초 수정 버전
- `Tomcat`: tomcat.apache.org/security-10
- `Logback`: logback.qos.ch/news
- `NVD`: nvd.nist.gov

| # | CVSS | CVE | 컴포넌트 | 최초 수정 | 근거 | 1단계 판정 |
|---:|---|---|---|---|---|---|
| 1 | 9.8 | CVE-2026-41293 | tomcat-embed-core | 10.1.55 | GHSA/Tomcat | 해소 (10.1.59) |
| 2 | 9.8 | CVE-2026-43512 | tomcat-embed-core | 10.1.55 | GHSA/Tomcat | 해소 |
| 3 | 9.1 | CVE-2026-43515 | tomcat-embed-core | 10.1.55 | GHSA/Tomcat | 해소 |
| 4 | 9.1 | CVE-2026-53434 | tomcat-embed-core | 10.1.56 | Tomcat | 해소 |
| 5 | 9.1 | CVE-2026-55276 | tomcat-embed-core | 10.1.56 | Tomcat | 해소 |
| 6 | 8.7 | CVE-2026-68494 | jackson-core | 2.21.4 | NVD/GHSA-r7wm-3cxj-wff9 | 해소 (2.22.2) |
| 7 | 8.1 | CVE-2026-54512 | jackson-databind | 2.21.4 | GHSA | 해소 (2.22.2) |
| 8 | 8.1 | CVE-2026-54513 | jackson-databind | 2.21.4 | GHSA | 해소 |
| 9 | 8.1 | CVE-2026-41855 | spring-web (실제 spring-jms) | 6.2.19 | GHSA | 해소 · 미포함 모듈 |
| 10 | 7.5 | CVE-2026-41284 | tomcat-embed-core | 10.1.55 | GHSA/Tomcat | 해소 |
| 11 | 7.5 | CVE-2026-43513 | tomcat-embed-core | 10.1.55 | GHSA/Tomcat | 해소 |
| 12 | 7.5 | CVE-2026-41850 | spring-expression | 6.2.19 | GHSA | 해소 |
| 13 | 7.5 | CVE-2026-41695 | spring-data-commons | 3.5.12 | GHSA | 해소 (3.5.13) |
| 14 | 7.5 | CVE-2026-41716 | spring-data-commons | 3.5.12 | GHSA | 해소 |
| 15 | 7.5 | CVE-2026-41842 | spring-webmvc | 6.2.19 | GHSA | 해소 |
| 16 | 7.3 | CVE-2026-42498 | tomcat-embed-core | 10.1.55 | GHSA/Tomcat | 해소 |
| 17 | 7.3 | CVE-2026-53404 | tomcat-embed-core | 10.1.56 | Tomcat | 해소 |
| 18 | 7.3 | CVE-2026-42498 | tomcat-embed-websocket | 10.1.55 | GHSA/Tomcat | 해소 |
| 19 | 7.1 | CVE-2026-41845 | spring-web | 6.2.19 | GHSA | 해소 |
| 20 | 7.1 | CVE-2026-41845 | spring-webmvc | 6.2.19 | GHSA | 해소 |
| 21 | 7.0 | CVE-2026-13006 | logback-core | 1.5.37 | NVD/Logback | 해소 (1.6.3) |
| 22 | 6.8 | CVE-2026-47838 | spring-security-web | 6.5.11 | GHSA | 해소 |
| 23 | 6.8 | CVE-2026-47838 | spring-security-config | 6.5.11 | GHSA | 해소 |
| 24 | 6.5 | CVE-2026-54518 | jackson-databind | 2.21.4 | GHSA | 해소 |
| 25 | 6.5 | CVE-2026-59888 | jackson-databind | 2.21.4 | GHSA | 해소 |
| 26 | 6.5 | CVE-2026-59889 | jackson-databind | 2.21.5 / 2.22.1 | GHSA | 해소 |
| 27 | 6.5 | CVE-2026-55955 | tomcat-embed-core | 10.1.56 | Tomcat | 해소 |
| 28 | 6.5 | CVE-2026-55956 | tomcat-embed-core | 10.1.56 | Tomcat | 해소 |
| 29 | 6.3 | CVE-2026-49844 | log4j-api | 2.25.5 | GHSA | **컴포넌트 제거** |
| 30 | 6.1 | CVE-2026-41706 | spring-security-web | 6.5.11 | GHSA | 해소 |
| 31 | 5.9 | CVE-2026-41840 | spring-web (실제 webflux) | 6.2.19 | GHSA | 해소 · 미포함 모듈 |
| 32 | 5.9 | CVE-2026-41711 | spring-data-commons | 3.5.12 | GHSA | 해소 |
| 33 | 5.9 | CVE-2026-41721 | spring-data-commons | 3.5.12 | GHSA | 해소 |
| 34 | 5.9 | CVE-2026-41841 | spring-webmvc | 6.2.19 | GHSA | 해소 |
| 35 | 5.9 | CVE-2026-41843 | spring-webmvc | 6.2.19 | GHSA | 해소 |
| 36 | 5.9 | CVE-2026-41846 | spring-webmvc | 6.2.19 | GHSA | 해소 |
| 37 | 5.5 | CVE-2023-2976 | guava | 32.0.0 | GHSA | **컴포넌트 제거** |
| 38 | 5.3 | CVE-2026-54514 | jackson-databind | 2.21.4 | GHSA | 해소 |
| 39 | 5.3 | CVE-2026-54515 | jackson-databind | 2.21.5 / 2.22.1 | GHSA | 해소 |
| 40 | 5.3 | CVE-2026-54516 | jackson-databind | 2.21.4 | GHSA | 해소 |
| 41 | 5.3 | CVE-2026-54517 | jackson-databind | 2.21.4 | GHSA | 해소 |
| 42 | 5.3 | CVE-2026-41853 | spring-web | 6.2.19 | GHSA | 해소 |
| 43 | 5.3 | CVE-2026-41001 | spring-boot-autoconfigure | 3.5.15 | GHSA | 해소 (3.5.16) |
| 44 | 5.3 | CVE-2025-48924 | commons-lang3 | 3.18.0 | GHSA | 해소 (3.20.0) |
| 45 | 5.3 | CVE-2026-41851 | spring-expression | 6.2.19 | GHSA | 해소 |
| 46 | 5.3 | CVE-2026-41851 | spring-core | 6.2.19 | GHSA | 해소 |
| 47 | 5.3 | CVE-2026-41853 | spring-webmvc | 6.2.19 | GHSA | 해소 |
| 48 | 5.0 | CVE-2026-40992 | spring-boot-autoconfigure (실제 starter-mail) | 3.5.15 | GHSA | 해소 · 미사용 기능 |
| 49 | 4.2 | CVE-2026-41839 | spring-web (실제 webflux) | 6.2.19 | GHSA | 해소 · 미포함 모듈 |
| 50 | 4.2 | CVE-2026-41854 | spring-web | 6.2.19 | GHSA | 해소 |
| 51 | 4.2 | CVE-2026-41844 | spring-webmvc | 6.2.19 | GHSA | 해소 |
| 52 | 3.7 | CVE-2026-43514 | tomcat-embed-core | 10.1.55 | GHSA/Tomcat | 해소 |
| 53 | 3.7 | CVE-2026-41852 | spring-expression | 6.2.19 | GHSA | 해소 |
| 54 | 3.7 | CVE-2026-41848 | spring-core | 6.2.19 | GHSA | 해소 |
| 55 | 3.3 | CVE-2020-8908 | guava | 32.0.0 | GHSA | **컴포넌트 제거** |
| 56 | 2.9 | CVE-2026-9828 | logback-classic | 1.5.33 | GHSA | 해소 |
| 57 | 2.9 | CVE-2026-10532 | logback-core | 1.5.34 | GHSA | 해소 |
| 58 | 2.9 | CVE-2026-9828 | logback-core | 1.5.33 | GHSA | 해소 |
| 59 | 2.6 | CVE-2011-4457 | owasp-java-html-sanitizer | r88 (2011) | GHSA | **오탐** (5절) |

## 4. 실제 노출도 평가 (참고 — 노출 여부와 무관하게 전부 패치)

| 분류 | 해당 CVE | 이 앱에서의 조건 |
|---|---|---|
| 미포함 모듈 | 41839·41840(WebFlux), 41855(spring-jms), 40992(Mail), 41001(Artemis) | 해당 jar·스타터 없음 |
| 미사용 기능 | 41293(HTTP/2), 43512(Digest 인증), 41284(WebDAV), 43514(AJP), 53404(RewriteValve), 47838(X.509), 41846(JSP 태그) | `server.http2` 미설정, 임베디드 Tomcat 기본 커넥터, 인증은 Spring Security 폼 로그인 |
| 전제 조건 부재 | 13006(Janino 필요), 54512·54513(Jackson 다형 타입), 41695·41711·41716·41721(Spring Data 웹 바인딩) | Janino 없음, `activateDefaultTyping`/`@JsonTypeInfo` 0건, 컨트롤러가 `Pageable` 을 요청 파라미터로 받지 않음 |
| **노출 가능** | 43515·42498(Tomcat), 41842·41843·41841(정적 리소스), 41853(multipart), 41845(JavaScriptUtils), 41850·41851(SpEL — Thymeleaf 경유) | 실제 사용 경로 — 즉시 조치 근거 |

## 5. 오탐 소명 — CVE-2011-4457 (owasp-java-html-sanitizer)

- 이 CVE 는 **2011년 리비전 r88 에서 수정**됐다(GHSA-pcm9-fp55-563v, 영향 범위 `< 88`).
- 점검표의 "최신 버전 r239" 는 프로젝트가 googlecode SVN 에 있던 시절의 리비전 명명이다. 2016년 이후 Maven Central 버전은 날짜형(`YYYYMMDD.N`)이다.
- 검출된 20220608.1 도 이미 수정본이었다. 이번에 Central 최신본 **20260313.1** 로 올렸다.
- 도구가 여전히 `20260313.1 < r239` 로 보고한다면, 버전 문자열을 사전순으로 비교한 결과이므로 본 절로 소명한다.

## 6. 검증

| 항목 | 결과 |
|---|---|
| 의존성 해석 | `mvn dependency:tree` — 목표 버전 전부 반영, **guava·log4j-api·log4j-to-slf4j 부재** 확인 |
| CVE 재판정 | 새 JAR `BOOT-INF/lib` 의 해석 버전을 CVE 53개 영향 범위와 기계 대조했다. **영향 범위 내 0개** (7개는 해당 컴포넌트가 jar 에 없음: 제거 2종 + 미포함 모듈) |
| 단위 테스트 | `mvn test` **589건 통과** / 라이브 4건 skip (조치 전과 동일) |
| sanitizer 회귀 | 운영 MAT 결과 ZIP 56개의 HTML **1,291개**를 조치 전 JAR / 조치 후 JAR 로 각각 `HtmlSanitizer.sanitize()` 해 비교했다. **차이 0건** |
| 빌드·기동 | `mvn clean package` 성공 → `Started HeapAnalyzerApplication in 15.064 seconds` (logback 1.6.3 상태 오류 없음) |
| 서빙 실측 | `/login`·CSS·JS 200 + `charset=UTF-8` 유지(함정 35) / 미인증 `/api/**` → 401 `SESSION_EXPIRED` JSON / 페이지 → 302 `/login` / `X-Frame-Options: SAMEORIGIN` |
| 기동 WARN | `No ZIP found for reportType='dominator_tree'` 8건은 **조치 전(2026-09-09 기동)에도 동일하게 발생**하던 기존 로그다. 이번 변경과 무관하다 |

**남은 확인 (운영자)**
- 로그인 후 기능 스모크: OTP, 업로드, 분석 진행 SSE, PDF, AI 채팅 스트림, `/settings/rag` 가져오기
- 점검 도구로 `heap-analyzer-2.4.2.jar` 재스캔
- k8s 이미지(`/opt/genspark/heapApp_k8s`)는 새 JAR 로 재빌드해야 반영된다

## 7. 2단계 — Boot 4.1 전환 (완료, 2026-09-11 · v2.5.0)

상세 절차와 결과는 `BOOT4_MIGRATION_PLAN.md` 에 있다. 이 단계로 **점검표 전 컴포넌트가 기재 최신 이상**이 됐다.

| 컴포넌트 | 점검표 최신 | **최종 (v2.5.0)** | 판정 |
|---|---|---|---|
| tomcat-embed-core / websocket | 11.0.24 | **11.0.25** | ✅ |
| jackson-core / databind | 2.22.1 | **Jackson 3.1.5** (`tools.jackson`) — com.fasterxml 2.x jar **부재** | ✅ |
| spring-web / webmvc / core / expression | 7.0.8 | **7.0.9** | ✅ |
| spring-data-commons | 4.1.0 | **4.1.1** | ✅ |
| spring-security-web / config | 7.1.0 | **7.1.1** | ✅ |
| spring-boot-autoconfigure | 4.1.0 | **4.1.1** | ✅ |
| logback-core / classic | 1.6.2 | **1.6.3** | ✅ |
| log4j-api | 3.0.0-beta2 | **부재** | ✅ |
| guava | 33.6.0-jre | **부재** | ✅ |
| commons-lang3 | 3.20.0 | **3.20.0** | ✅ |
| owasp-java-html-sanitizer | r239 | **20260313.1** | ✅ (5절) |

Jackson 은 버전을 올리는 대신 **Jackson 3(`tools.jackson.core`)로 이관**했다. 그 결과 점검표가 지목한 `com.fasterxml.jackson.core:jackson-core/databind` 가 jar 에서 없어졌다. Jackson 3.1.5 는 점검표 CVE 들의 3.x 영향 범위(`< 3.1.4`, 59889 는 `3.2.0`)에도 들지 않는다.
`jackson-annotations 2.21` 은 Jackson 3 가 공유하는 어노테이션 jar 라 남아 있으며, 점검 대상 CVE 가 없다.

검증:
- 단위 테스트 589건 통과
- Hibernate 7 DDL 0건 (사전 추출 + 기동 로그)
- 운영 DB 의 Jackson 2 저장 JSON 전량 호환
- 세션 역직렬화 8/8 → 전 사용자 재로그인 없이 전환
- 기동·서빙 스모크 정상
