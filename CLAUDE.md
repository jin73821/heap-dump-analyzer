# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language

Always respond in Korean (한국어). Code and technical identifiers remain in English.

## 문서 지도

**해당 영역을 고치기 전에 아래 문서를 먼저 읽을 것.** 자동으로 로드되지 않으며, 규약과 함정 대부분이 여기 있다.

| 작업 영역 | 문서 |
|---|---|
| 컨트롤러·서비스·DTO·유틸 위치, 라우트, 리팩토링 이력 | `docs/guide/ARCHITECTURE.md` |
| MAT 분석·저장·캐시·설정 영속화·시크릿·코어 덤프·PDF·원격 전송 | `docs/guide/BACKEND_NOTES.md` |
| 템플릿·CSS·JS 공통 모듈, 화면별 규약 | `docs/guide/FRONTEND.md` |
| LLM·RAG·Chroma | `docs/guide/LLM_RAG.md` |
| 로그인·세션·CSRF·2FA·계정 정책·DB 장애 표시 | `docs/guide/AUTH_SECURITY.md` |
| GC 로그 | `docs/guide/GC_LOG.md` |
| Kubernetes·폐쇄망 설치 패키지 | `docs/guide/DEPLOYMENT.md` |
| 테스트 영역별 건수 | `docs/guide/TEST_INVENTORY.md` |
| 완료된 계획·과거 보고서 | `docs/old/` |

소스 주석의 `CLAUDE.md 함정 N` 은 아래 색인으로 찾는다. 문서 안에서는 `grep -n '^N\. ' <문서>` 로 바로 찾을 수 있다.

| 문서 | 함정 번호 |
|---|---|
| `FRONTEND.md` | 4 · 6 · 7 · 8 · 11 · 12 · 13 · 14 · 17 · 18 · 19 · 22 · 23 · 26 · 30 · 33 · 35 · 36 · 59 · 65 |
| `AUTH_SECURITY.md` | 1 · 2 · 3 · 15 · 21 · 27 · 38 · 47 · 69 · 70 |
| `BACKEND_NOTES.md` | 5 · 9 · 10 · 16 · 20 · 24 · 25 · 28 · 29 · 34 · 37 · 45 · 46 · 48 · 56 · 71 |
| `LLM_RAG.md` | 31 · 32 · 39 · 40 · 41 · 42 · 43 · 44 |
| `GC_LOG.md` | 49 · 50 · 51 · 52 · 53 · 54 · 55 · 57 · 58 · 60 · 61 · 62 · 63 · 64 · 66 · 67 · 68 |

새 규약·함정은 해당 영역 문서에 넣는다. 함정은 마지막 번호 다음 번호를 쓰고 위 색인에도 추가한다. 소스 주석이 번호로 참조하므로 **기존 번호를 바꾸거나 재사용하지 말 것.**

## Project Overview

Java Spring Boot **4.1.1** + Java **17** (런타임 OpenJDK 21) 웹앱. 분석 대상 3종: 힙 덤프(Eclipse MAT CLI — .hprof/.bin/.dump) · 코어 덤프(gdb) · GC 로그(자체 파서). MariaDB(`192.168.56.9:3306/HEAPDB`) + Spring Security **7.1** 세션 기반(Spring Session **4.1** JDBC). Spring Framework **7.0** · Hibernate **7.4** · Tomcat **11** · **Jackson 3**(`tools.jackson.*`) + jakarta 네임스페이스. 이력: 2026-05-19 Boot 2.7→3.5(`docs/old/BOOT3_MIGRATION_PLAN.md`), **2026-09-11 Boot 3.5→4.1**(`docs/BOOT4_MIGRATION_PLAN.md` — 사내 CVSS 점검 조치 2단계).

**오픈소스 취약점 조치 (2026-09-11, `docs/SECURITY_AUDIT_OSS_CVE.md`):** 기준은 **사내 점검표에 기재된 "최신 버전" 이상**이다(최소 수정 버전이 아니다).
- `pom.xml` `<properties>` 의 BOM 오버라이드 2종 — `tomcat.version` 11.0.25 / `logback.version` 1.6.3 — 은 **점검 기준 충족용**이다. 지우면 Boot 관리값(11.0.24 / 1.5.38)으로 내려간다.
- ⚠ Boot 4 에서 `jackson-bom.version` 은 **Jackson 3 BOM** 이다. 2.x 값을 넣으면 안 된다.
- `spring-boot-starter-logging` 직접 선언 + `log4j-to-slf4j` exclusion 은 **log4j-api 를 의도적으로 제거**한 것이다(앱은 Log4j2 API 미사용).
- OWASP sanitizer 20260313.1 은 guava 를 끌어오지 않는다. **guava·log4j-api·Jackson 2 databind 를 다시 들이는 의존성을 추가하지 말 것.**
- **새 JSON 코드는 `tools.jackson.databind.*` 로 작성**한다. `com.fasterxml.jackson.annotation.*` 만 그대로다.
  - 예외는 unchecked `JacksonException` 이다. Jackson 호출만 감싼 `catch (IOException)` 은 컴파일 오류가 난다.
  - `JsonNode.fields()` 는 없어졌고 `properties()` 를 쓴다.
  - 매퍼는 불변이라 설정 변경은 `rebuild()…build()` 로 한다.
- Spring Session 은 **`spring-boot-starter-session-jdbc`** 가 있어야 자동설정된다. `spring-session-jdbc` 만 두면 조용히 메모리 세션으로 떨어진다.

## Build & Run

```bash
mvn clean package -DskipTests           # 빌드 (10~13초)
mvn test                                 # 단위 테스트 전체 1085건 (라이브 4건 기본 skip, 약 35초). 영역별 내역은 docs/guide/TEST_INVENTORY.md
mvn test -Dtest=HttpHeaderValuesTest     # 단일 클래스
mvn test -Dtest='HttpHeaderValuesTest#메서드명'   # 단일 메서드
mvn test -Dtest=ChromaLiveIntegrationTest -Dchroma.live=true   # Chroma 라이브 통합 (실행 중인 Chroma 스택 필요)
java -jar target/heap-analyzer-2.6.2.jar   # 버전은 pom.xml <version>과 항상 일치
bash restart.sh                          # 운영(18080) 재기동
```

**린터 없음:** `pom.xml` 에 checkstyle·spotless 등이 없다. 소스 규약은 테스트가 지킨다(예: `EmptyCatchGuardTest`).

**Maven 요구사항:** Maven 3.6.3+ (Boot 4.1 기준). 본 시스템 설정: Maven 3.9.9 (`/opt/apache-maven-3.9.9`, alternatives 수동 모드 + `/etc/profile.d/maven.sh` 로 `MAVEN_HOME` 설정). 신규 운영 환경 배포 시 동일 버전 설치 필수.

**CRITICAL:** 모든 프론트엔드 리소스(CSS/HTML/JS)는 JAR 내부에 있음. **어떤 변경이든 `mvn clean package -DskipTests && bash restart.sh` 필수.** 빌드+기동 약 20~24초.

**버전 변경 체크리스트:** `pom.xml <version>` 변경 시 **UI 표기(`fragments/banner.html`·`index.html`·`progress.html`) 갱신**만 하면 된다. **쉘 스크립트는 전부 버전 비의존** — `run.sh`/`restart.sh`/`stop.sh` 는 `env.sh` 를 source 해 `target/heap-analyzer-*.jar` 최신본을 자동 탐색하고(`APP_VERSION` 은 파일명에서 역산), 프로세스 매칭도 버전 무관 패턴(`APP_PROC_PATTERN`)이라 **구버전 JAR 로 떠 있는 프로세스도 정상 종료**한다(2026-08-10 이전엔 버전 하드코딩 grep 이라 첫 재기동 때 포트 18080 충돌이 났다). `heap_enc.sh`/`heap_dec.sh` 도 2026-07-31 부터 JAR 자동 탐색.

**기동 스크립트 설정은 `env.sh` 단일 지점:** 경로/포트(`SERVER_PORT`)/JVM 힙(`JVM_XMS`·`JVM_XMX`)/GC 로그/truststore(`TRUSTSTORE`·`TRUSTSTORE_PASS`)/대기시간(`STOP_WAIT_SECS`·`RESTART_STOP_WAIT_SECS`·`BOOT_WAIT_SECS`)/기동 판정 패턴이 모두 여기 있고, 공용 헬퍼(`app_pids`/`require_jar`/`build_trust_opts`/`stop_app`/`start_app`/`wait_for_boot`)도 여기 있다. 세 스크립트는 얇은 래퍼일 뿐이니 **값이든 로직이든 스크립트 본문에 리터럴을 새로 넣지 말 것.** 모든 변수는 `${VAR:-기본값}` 이라 환경변수로 override 가능(`SERVER_PORT=18081 bash run.sh`). 운영 비밀값(`HEAP_ANALYZER_ENCRYPTION_KEY` 등)은 `env.local.sh`(있으면 자동 source, `.gitignore` 대상)에 export.

**기동 검증:**
```bash
sleep 18 && grep -E "Started HeapAnalyzerApplication|FAILED|Exception in thread" \
  /opt/genspark/webapp_dump/logs/heapdump-analyzer.log | tail -3
```

**MariaDB 미연결 시 기동 실패** (Spring Session JDBC가 SPRING_SESSION 자동 생성). DB 점검: `mysql -h 192.168.56.9 -u heap_user -p<REDACTED> HEAPDB -e "..."`.

## Architecture

Spring MVC + JPA + MariaDB. **하이브리드 저장**: 메타데이터 + 분석 상세는 DB(`analysis_history` 요약, `analysis_result_detail` 상세 JSON, `analysis_dominator_refs` 사전계산 refs, `ai_insights`, `ai_chat_*`, `login_history`, `memo_history` 등), MAT 산출물(ZIP/`.index`/`.threads`/`mat.log`)만 파일 시스템. **2026-07-31 이전의 `data/{base}/result.json`·`dominator-refs.json` 은 폐지** — 기동 시 DB 이관 후 삭제된다.

**디렉토리:**
```
/opt/heapdumps/
├── dumpfiles/    원본 보존 (.hprof/.gz). 업로드 도착지
├── data/         결과 산출물(mat.log/*.zip/*.index/*.threads) + settings.json
│              디렉토리명 = **확장자 포함 파일명**(`data/jeus_admin.hprof/`, `data/jeus_admin.hprof.gz/`)
│              내부 파일명은 MAT 가 생성한 hprof base 기준 유지
└── tmp/          분석 중 복사본 (분석 후 항상 삭제)
```

**분석 흐름:** Upload → dumpfiles → tmp/ copy → MAT CLI(tmp 대상) → 성공 시 tmp 삭제 + data/{filename}/ 에 MAT 산출물 저장 + 상세 JSON 은 `analysis_result_detail` 저장 + (옵션)dumpfiles gzip / 실패 시 tmp만 삭제. **MAT CLI 성공 직후 `enrichWideHistogram()` 이 격리 디렉토리 `tmp/histq-*` 에서 `histogram` 단독 쿼리를 1회 더 돌려 Class Histogram 을 25→500행으로 바꾼다**(2026-09-13, 소형 덤프 ~3초, 실패 시 25행 유지, 킬 스위치 `mat.histogram.wide-query.enabled` — 함정 48).

## 어디서든 지킬 규칙

영역 문서를 읽지 않은 작업에도 적용되는 요약이다.

- **운영 MariaDB 는 읽기 전용으로 검증한다.** UNIQUE 키에 `ON DUPLICATE KEY UPDATE` 금지 (함정 20).
- **재기동은 JAR 이름이 들어가지 않은 단독 명령으로 실행한다.** `stop_app` 이 JAR 이름이 든 명령줄의 프로세스를 호출 셸까지 죽인다 (함정 45).
- **새 ADMIN 변경 API:** `SecurityConfig` 의 authorize 와 CSRF 두 곳을 함께 고친다. 관리·변경 작업은 `[Domain] action=… by={who}` 감사 로그를 남긴다.
- **빈 catch 금지.** JS 는 `Common.logIgnored`/`logError`, Java 는 `logger.debug`/`warn` 을 쓴다.
- **런타임 설정 추가는 5곳 동기화** (`HeapDumpConfig` `@Value` · `init()` · 3-hook). 변경은 `HeapDumpAnalyzerService` setter 를 거친다 (함정 40).
- **새 MAT 호출은 `runMatSingleQuery`/`runMatCliWithProgress` 경유,** 새 LLM 호출은 `acquireGate` + `Lease` 를 거친다.
- **새 배경 폴러는 `SessionTimeout.managedInterval` 경유,** 자동 갱신에 `location.reload()` 금지 (함정 38·69).
- **파일명을 응답 헤더·`redirect:` 에 직접 붙이지 말고 `HttpHeaderValues` 를 쓴다** (함정 71).
- **새 시크릿 필드는 `SecretValue` + `putSecret()` 패턴을 쓴다.**

## Changelog

모든 변경 내용은 `docs/CHANGELOG.md`에 누적 기록. 작업 완료 후 날짜·대상 파일·상세 내역과 함께 추가.
