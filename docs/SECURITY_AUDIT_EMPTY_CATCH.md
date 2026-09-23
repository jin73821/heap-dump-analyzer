# 빈 catch 블록(04.02 오류 상황 대응 부재) 조치 결과 보고서

> 점검 문서: **SRM20260902-0223** (1차점검 2026-09-08)
> 지적 유형: `04.02. 오류 상황 대응 부재` — "빈 catch 블록" / 위험도 **보통** / **조치권고**
> 조치일: **2026-09-09** · 조치 버전: heap-analyzer **2.4.1**
> 상태: **조치 완료** — 지적 **47건 전건 조치** + 자진 발굴 **35건 추가 조치** (합계 82건)

---

## 1. 요약

| 구분 | 건수 | 상태 |
|---|---:|---|
| 점검 도구 지적 (`try/catch` 빈 블록) | 47 | 전건 조치 완료 |
| 자진 발굴 — 도구가 잡지 못한 템플릿 인라인 `try/catch` | 11 | 조치 완료 |
| 자진 발굴 — 빈 Promise `.catch()` 핸들러 (동일 성격) | 24 | 조치 완료 |
| **합계** | **82** | **잔여 0건** |
| 대상 제외 | — | `static/js/lib/chart.umd.min.js` (외부 라이브러리 minified) |

조치 후 **잔여 0건**임은 신규 회귀 테스트 `EmptyCatchGuardTest` 가 매 빌드마다 기계적으로 확인한다(5절).

## 2. 조치 원칙 — 형식적 로그 추가가 아니라 등급별 차등

빈 catch 는 "의도된 폴백"과 "조용한 실패"를 구분할 수 없게 만든다. 전수 검토 결과 두 부류가 섞여 있어
같은 방식으로 처리하지 않았다.

| 등급 | 정의 | 조치 방식 | 건수 |
|---|---|---|---:|
| **A** | 예외를 삼켜 **사용자에게 보이는 오작동**이 발생 | catch 범위 축소 + 실패 경로 복구(진행 표시 종료·오류 표시·이동 중단) + `console.warn/error` | 8 |
| **B** | 기능적으로 정당한 폴백(스토리지 차단·구형 브라우저·개별 SSE 프레임 등) | 폴백 로직 유지 + 진단 로그 1줄(`console.debug`) | 73 |
| **C** | 죽은 코드 / 방어 누락 등 구조 문제 | 제거 또는 방어 보강 | 2 |

**로그 정책(혼합):** 정상 폴백은 `console.debug` — 브라우저 기본 로그 레벨에서 보이지 않아 운영 콘솔을
오염시키지 않으면서 Verbose 로 진단할 수 있다. 실제 오류는 기존 관례대로 `console.warn/error` 로 노출한다.

**공용 헬퍼 도입** (`static/js/common.js`):

```js
Common.logIgnored(tag, e)   // 정당한 폴백 — console.debug
Common.logError(tag, e)     // 흡수했지만 알려야 하는 오류 — console.warn
```

두 함수는 `typeof` 가드만 사용해 **자기 자신이 절대 예외를 던지지 않는다**(헬퍼 내부에 try 를 두면 그
catch 가 다시 빈 블록이 되는 순환을 피하기 위함). 인증 전 화면인 `/login` 계열 3개 페이지와
`<head>` 선행 스크립트는 **의존성을 새로 추가하지 않기 위해** 인라인 `console.debug` 한 줄로 처리했다.

## 3. A급 — 실제 결함으로 확인되어 동작까지 수정한 항목

| # | 위치(점검 기준 라인) | 무엇이 잘못돼 있었나 | 조치 |
|---|---|---|---|
| 1 | `analyze.js:1260` `toggleDomDetail` | Dominator Refs SSE 핸들러 **본문 전체**를 `catch (x) {}` 로 감쌌다. JSON 파싱·렌더가 실패하면 `_domLazyEnd()` 가 호출되지 않아 **로딩 스켈레톤이 영구히 남는다**(사용자에게는 "아직 조회 중"으로 보임) | catch 를 좁히고, 실패 시 오류 문구 표시 + `_domLazyEnd('failed')` + AbortController 정리 + `console.warn` |
| 2 | `analyze.js:1542` `toggleClassInstances` | 동일 구조 — 클래스 인스턴스 조회 스피너가 남는다 | 동일 패턴으로 복구 |
| 3 | `analyze.js:1637` `loadClassLoaderClasses` | 동일 구조 — 클래스로더 로드 클래스 조회 스피너가 남는다 | 동일 패턴으로 복구 |
| 4 | `core-dump-index.js:280` | 업로드 응답 파싱 실패 시 `d = {}` 인 채 진행해 **`/core-dump/progress/undefined`** 로 이동 | 파싱 실패·`filename` 부재 시 이동하지 않고 업로드 실패로 안내 |
| 5 | `session-timeout.js:397` | 세션 만료 훅(**작성 중 메모의 localStorage 백업 지점**) 실패가 무음이라 메모 유실을 감지할 수 없다 | `console.warn` 으로 노출(나머지 훅 격리 실행은 유지) |
| 6 | `analyze.js:205` | Raw Data iframe 내 클래스 링크 활성화 실패가 무음 — 링크가 전부 죽어도 신호가 없다 | `console.warn` 노출 |
| 7 | `servers.html:1104` | 전송 완료(`done`) 프레임 파싱 실패 시 아무 기록 없이 "실패"로 표시 — 실제 결과와 다를 수 있다 | `console.warn` 노출(표시 동작은 유지) |
| 8 | `index.html:1198` | `keepUnreachable` 설정 서버 반영 실패를 삼키는데 화면에는 "Setting saved" 토스트가 뜬다 | `console.warn` 노출 |

## 4. 지적 47건 처리 내역 (점검 문서 행 순서)

라인 번호는 **점검 대상 산출물(SRM20260902-0223) 기준**이다. 조치 과정에서 주석·로그가 추가돼 현재 소스의
라인 번호는 뒤로 밀렸다.

### 4.1 `BOOT-INF/classes/static/js/analyze.js` — 7건

| 라인 | 함수 | 등급 | 조치 |
|---:|---|:--:|---|
| 62 | `applyPdfHtmlZoom` | B | iframe 미로드 폴백 유지 + `logIgnored` |
| 205 | iframe load 리스너 | **A** | 링크 활성화 실패를 `logError` 로 노출 |
| 1260 | `toggleDomDetail` | **A** | catch 범위 축소 + 스켈레톤 종료 + 오류 표시 |
| 1542 | `toggleClassInstances` | **A** | 동상 |
| 1637 | `loadClassLoaderClasses` | **A** | 동상 |
| 3379 | `fallbackCopy` | B | `execCommand` 실패 기록(`logIgnored`) |
| 4753 | `doStreamRequest` | B | 개별 SSE 프레임만 건너뜀을 명시 + `logIgnored` |

### 4.2 `common.js` — 3건

| 라인 | 위치 | 등급 | 조치 |
|---:|---|:--:|---|
| 68 | `fetchJSON` 429 본문 파싱 | B | JSON 아님을 기록 후 기본 문구 사용 |
| 163 | `flashToast` | B | sessionStorage 차단 기록 |
| 177 | `showPendingToasts` | B | 대기 토스트 복원 실패 기록 |

### 4.3 `core-dump-analyze.js` — 3건 / `core-dump-index.js` — 2건

| 파일:라인 | 등급 | 조치 |
|---|:--:|---|
| `core-dump-analyze.js:106` | B | PDF 확대 적용 보류 기록 |
| `core-dump-analyze.js:209` | B | 프레임 밀도 저장 실패 기록 |
| `core-dump-analyze.js:583` | B | 프레임 밀도 복원 실패 기록(기본값 사용) |
| `core-dump-index.js:280` | **A** | 응답 파싱 실패 시 이동 중단 + 실패 안내 |
| `core-dump-index.js:287` | B | 오류 응답이 JSON 이 아닐 때 상태 코드 문구 사용 |

### 4.4 `memo.js` — 10건 (전부 B)

`90` 오류 본문 파싱 · `180` 이탈 시 저장 호출 · `228` 창간 메시지 리스너(→`logError`) · `238` storage 폴백 파싱 ·
`248` BroadcastChannel 전송 · `253` storage 폴백 전송 · `266` 초안 인계 저장 · `289` 미저장 백업(→`logError`) ·
`301` 백업 삭제 · `328` 되돌리기 삭제 — 각 지점의 폴백 의미를 주석으로 남기고 로그 1줄 추가.
단, **미저장 메모 백업 실패(289)와 리스너 실패(228)** 는 데이터 유실과 직결돼 `logError`(warn) 로 승격했다.

### 4.5 `session-timeout.js` — 16건

| 라인 | 대상 | 등급 | 조치 |
|---:|---|:--:|---|
| 93 / 105 / 110 | 상태 복원·저장·삭제(localStorage) | B | 탭 로컬 폴백 유지 + 기록 |
| 117 | 탭 간 통지(BroadcastChannel) | B | 기록 |
| 162 | 활동 가드 실행 | B→warn | 가드 오류를 `logError` 로 노출 |
| 169 | `console.warn` 자체를 감싼 방어 try | B | `return false` 로 명시(콘솔 부재 환경) |
| 195 | 이동 차단 술어 | B→warn | `logError` |
| 234 | 관리 폴러 콜백 | B→warn | `logError`(폴링은 계속) |
| 246 | 관리 폴러 정지 | B | 기록 |
| 346 | 경고 모달 포커스 | B | 기록 |
| 391 | 만료 시각 공유 기록 | B | 기록 |
| 397 | **만료 훅 실행** | **A** | `logError` — 메모 백업 실패를 알린다 |
| 413 / 415 | 이탈 경고 해제 | B | 기록 |
| 417 | 배너 캐시 삭제 | B | 기록 |
| 423 | CSRF 토큰 조회 | B | 토큰 없이 로그아웃 시도함을 기록 |

### 4.6 템플릿 인라인 — 6건

| 파일:라인 | 등급 | 조치 |
|---|:--:|---|
| `leak-rules.html:319` | **C** | **죽은 코드 제거.** 저장값은 `'true'`/`'false'` 인데 `'1'` 과 비교해 한 번도 발화하지 않았고, 클래스도 `documentElement` 에 붙어 `body.banner-collapsed` 셀렉터와 어긋나 있었다. 접힘 상태 적용은 `fragments/banner.html` 이 담당한다 |
| `login.html:222` / `login-otp.html:97` / `login-otp-setup.html:117` | B | 구형 브라우저 `history.replaceState` 미지원 — 인라인 `console.debug` |
| `login.html:243` / `login.html:252` | B | 아이디 기억(localStorage) 복원·저장 실패 — 인라인 `console.debug` |

> 로그인 계열은 **인증 전 페이지라 외부 스크립트를 새로 싣지 않았다**(공격면 증가 회피). 같은 이유로
> `leak-rules.html` 의 `<head>` 스크립트는 `common.js` 보다 먼저 실행되므로 헬퍼를 쓸 수 없다.

## 5. 자진 발굴 35건 (도구 미지적 — 동일 성격이라 함께 조치)

| 파일 | 건수 | 성격 |
|---|---:|---|
| `templates/settings.html` | 4 | 설정 조회 `.catch` |
| `templates/ai-chat.html` | 4 | SSE 프레임 파싱 1 + 목록 조회 `.catch` 3 |
| `templates/servers.html` | 3 | 전송 SSE 2(1건 A급) + 스캔 오류 조회 1 |
| `templates/rag-settings.html` | 3 | JSON 폴백 2 + 큐 조회 1 |
| `templates/account.html` | 3 · `account-memo.html` 2 | 메모 폰트·자동저장 설정 저장 |
| `static/js/core-dump-analyze.js` | 3 | 소스 자동 로드·AI 인사이트·번들 상태 |
| `static/js/analyze.js` | 2 | 설정 조회 `.catch` |
| `templates/fragments/banner.html` | 2 | 이탈 가드 실행 + 상태 캐시 저장 |
| `templates/history.html` | 2 | 탐지 현황 설정 저장·주소 동기화 |
| 그 외 1건씩 | 9 | `admin/users.html`, `files.html`, `index.html`(A급), `progress.html`, `server-logs.html`, `memo.js`, `session-timeout.js` |

추가로 **`fragments/banner.html` 의 FOUC 스크립트에 예외 방어가 아예 없던 문제**(C급)를 함께 보강했다 —
localStorage 차단 환경에서 이 지점이 throw 하면 **이후 배너 스크립트 전체가 실행되지 않는다**.

## 6. 재발 방지 — 자동 검사

`src/test/java/com/heapdump/analyzer/EmptyCatchGuardTest.java` (신규, 2건)

- `src/main/resources/static/js/**.js`(외부 라이브러리 `lib/` 제외)와 `templates/**.html` 의
  인라인 `<script>` 전체를 스캔해 **실행문 없는 `catch` 블록이 0건**임을 단언한다. 위반 시 파일:라인 목록을
  그대로 실패 메시지에 출력한다.
- 문자열·템플릿 리터럴·정규식 리터럴·주석을 공백으로 치환하는 스캐너를 거치므로 `'http://…'` 같은
  문자열을 주석으로 오인하지 않는다.
- 스캐너가 조용히 망가져 테스트가 무의미하게 통과하는 것을 막기 위해 **스캐너 자체 검증 테스트**
  (`scannerDetectsKnownShapes`, 12개 형태)를 함께 둔다.

빌드마다 `mvn test` 로 실행되므로 신규 코드가 같은 결함을 재도입하면 **다음 정기 점검 전에 개발 단계에서** 실패한다.

## 7. 검증 결과

| 항목 | 결과 |
|---|---|
| 조치 전 스캔 | 82건 검출 |
| 조치 후 스캔 | **0건** |
| 단위 테스트 | **589건 전건 통과** (587 → 589, 신규 2건) |
| JS 구문 검사 | 수정한 6개 JS 파일 + 템플릿 인라인 26블록 — `node --check` / `vm.Script` 통과 |
| 빌드·기동 | `mvn clean package` 성공, `restart.sh` 후 `Started HeapAnalyzerApplication in 16.397 seconds` |
| 서빙 확인 | `/js/common.js` 에 헬퍼 2종 노출, `/login` 인라인 로그 3곳 반영, `Content-Type: text/javascript;charset=UTF-8` 정상 |

**변경 파일:** `static/js/{common,session-timeout,memo,analyze,core-dump-analyze,core-dump-index}.js` ·
`templates/{login,login-otp,login-otp-setup,leak-rules,history,account,account-memo,ai-chat,rag-settings,server-logs,servers,files,index,progress,settings}.html` ·
`templates/fragments/banner.html` · `templates/admin/users.html` · `templates/{analyze,core-dump/analyze,core-dump/index}.html`(캐시 키) ·
`src/test/java/com/heapdump/analyzer/{EmptyCatchGuardTest,CommonToastStackTest}.java`

---

## 8. 추록 — 2차 자체 점검: Java 서버 소스 (2026-09-14, heap-analyzer 2.5.2)

**배경:** 1차 조치 이후 GC 로그 분석·원격 JVM 수집·계정 잠금 등 소스가 크게 늘었다. 재점검 전에 점검기 규칙
("예외를 처리하는 코드 내용이 없는 예외 처리 블록")으로 **저장소 전체**를 다시 훑었다.

**발견:** JS·템플릿은 0건(1차 가드 유지). **Java(`src/main/java`) 69건.** 1차 가드(`EmptyCatchGuardTest`)가
JS·템플릿만 스캔해서 Java 쪽이 기계적으로 막히지 않았다. 대부분 1차 이전부터 있던 코드이고, 일부는 신규 코드다.
주석만 있는 블록(`/* 무시 */`, `// 클라이언트 disconnect`)도 포함했다.

### 8.1 등급별 조치

| 등급 | 정의 | 조치 | 건수 |
|---|---|---|---:|
| **A** | 삼킨 예외가 잘못된 결과를 조용히 만든다 | 동작 보정 + `logger.warn` | 1 |
| **W** | 동작은 폴백이 맞지만 운영자가 알아야 한다(설정값 손상, 임시 디렉토리 잔존, 요청과 다른 처리) | `logger.warn` | 13 |
| **B** | 정당한 폴백(클라이언트 disconnect 후 SSE 전송, 선택적 파싱, 비 Linux `/proc`, 프로세스 종료 후 출력 읽기) | `logger.debug` — 기본 로그 레벨에서 숨김 | 52 |
| **C** | 죽은 코드 — 이미 모든 예외를 흡수하는 헬퍼를 다시 try 로 감쌈 | try 제거(헬퍼 내부 catch 에 debug 로그) | 3 |
| **합계** | | | **69** |

### 8.2 A·W·C 상세

| 파일 | 위치 | 등급 | 내용 |
|---|---|:--:|---|
| `ChromaSearchService` | 색인 현황 집계 `count` 조회 | **A** | count 실패 시 `total=0` 이라 상한(`STATS_MAX_ITEMS`)에 걸려도 `partial=false` 가 됐다. 주석 그대로 "조용한 과소보고"였다. 이제 count 를 모르면 **스캔 건수가 상한에 닿았는지로 partial 을 추정**하고 warn 을 남긴다 |
| `HeapReportApiController` | Dominator Refs·ClassLoader Classes·ClassInst SSE 정리 람다 ×3 | W | MAT 작업 디렉토리 삭제 실패 → tmp 잔존. warn(경로 포함) |
| `HeapReportApiController` | `send{DomRef,Cl,Inst}Error` 호출부 ×3 | **C** | 헬퍼가 이미 `catch (Exception)` 으로 흡수하므로 바깥 try 는 도달 불가. 제거하고 헬퍼 내부에 debug 로그 |
| `AccountRequestController` | 계정 신청 승인 `role` 파싱 | W | 알 수 없는 권한 문자열은 **USER 로 승인**된다(종전 동작 유지). 요청과 다른 권한으로 승인됐다는 사실을 warn 으로 남긴다 |
| `PasswordPolicyConfigService` · `AccountLockPolicyConfigService` · `HeapDumpAnalyzerService`(×2) · `RemoteDumpService` | settings.json 숫자 설정 복원 | W | 손상된 값은 기존 값을 유지한다. 어떤 키가 무시됐는지 warn |
| `HeapDumpAnalyzerService` | OOM 상세 쿼리·SysProp 쿼리 임시 디렉토리 정리 ×3 | W | tmp 잔존 → warn |
| `CoreDumpSysrootService` | `abortExtract` 부분 산출물 정리 | W | 해제 중단 뒤 sysroot 에 파일이 남으면 다음 분석이 오염된 번들을 쓴다. 수동 확인 요청 warn |

B 52건은 태그 접두(`[CoreDump]`·`[RemoteDump]`·`[LLM-Stream]` 등)와 대상 값을 담아 debug 로 남겼다. 로그 레벨만 올리면 진단할 수 있다.
신규 로거는 `SecurityConfig`·`AccountRequestController`·`SseJson` 3곳이다(나머지는 기존 `logger` 재사용).

### 8.3 재발 방지 — 가드 범위 확장

`EmptyCatchGuardTest` 를 넓혔다(테스트 수는 그대로 2건).
- 스캔 대상에 **`src/main/java/**.java`** 를 추가했다(`catch (…) { }` 문 형태).
- 점검기가 빈 블록으로 볼 형태 2가지를 더 잡는다: **ES2019 바인딩 생략 `catch { }`**, **빈 문장만 있는 `catch (e) { ; }`**.
- 스캐너 자체 검증을 12 → **20 형태**로 늘렸다. 빈 화살표 `.catch(() => {})`, 식 본문 화살표(실행문), Java 다중 catch, Java 주석만 있는 블록, Java 로그 있는 블록, Java 문자 리터럴 `'{'` 가 추가분이다.
- 가드가 Java 를 실제로 잡는지 확인했다. 빈 catch 가 든 임시 Java 파일을 넣으면 `파일:라인` 과 함께 실패하고, 지우면 통과한다.

### 8.4 검증

| 항목 | 결과 |
|---|---|
| 조치 전 스캔(Java) | 69건 |
| 조치 후 스캔(Java·JS·템플릿) | **0건** |
| 컴파일·단위 테스트 | `mvn test` **775건 통과**(라이브 4 skip) |
| 빌드·기동 | `mvn clean package` 성공, `restart.sh` 후 `Started HeapAnalyzerApplication in 15.615 seconds`, 기동 중 신규 warn 0건 |

**저장소 밖(참고):** `/opt/chroma/app/indexer.py` 의 `collection(reset=True)` 에 `except Exception: pass` 1건이 있다. 이 저장소가 아니라 Chroma 색인기 트리이고, k8s 이미지에는 포함된다. 점검 대상에 들어가면 같은 원칙으로 조치해야 한다.

