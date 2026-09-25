# WAS 정상 · DB 비정상 시 웹페이지 표시 방안 (설계, 2026-09-26)

> **상태: 2026-09-26 구현·배포·실검증 완료**(①~④ 전부, 검증 결과는 `docs/CHANGELOG.md` 2026-09-26 항목). 구현 중 드러난 차이 두 가지 — ⓐ 로그인 GET 도 CSRF 토큰을 세션(=DB)에 쓰므로 장애 중엔 새 브라우저도 503 페이지를 본다(③의 `unavailable` 분기는 부분 장애용) ⓑ `DataSourceConfig` 의 직접 생성 빈에는 `hikari.*` 가 바인딩되지 않아 `@ConfigurationProperties` + `HikariDataSource` 반환형으로 바꿨다. 아래는 원 설계 문서.
> 조사 기준 코드: `SecurityConfig`·`GlobalExceptionHandler`·`application.properties`·`HeapSystemApiController`·`session-timeout.js`·`common.js`.

## Part B. WAS 정상 · DB 비정상 시 웹페이지 표시 방안 (설계)

### B-1. 현재 동작 (조사 결과)

| 항목 | 현재 |
|---|---|
| DB 예외 처리 | `GlobalExceptionHandler` 는 `IllegalArgumentException` 만. `DataAccessException` 계열 처리 없음 → Boot 기본 `/error`(Whitelabel HTML / JSON) |
| 커스텀 오류 페이지 | `templates/error/` 없음, `ErrorController` 없음, `server.error.*` 키 없음 |
| Actuator / 헬스 | `spring-boot-starter-actuator` 미포함. DB 헬스는 `GET /api/settings` 의 `conn.isValid(3)` 하나뿐(설정 화면 `Disconnected` 배지) |
| `/api/system/status` | DB 를 보지 않는다(MAT/디스크/JVM/큐). 배너 카드에 **DB 항목 없음**. 5xx 면 배너는 조용히 옛 캐시 유지 |
| 세션 | Spring Session JDBC. 쿠키가 있는 모든 요청은 `SessionRepositoryFilter` 가 **컨트롤러 진입 전에** DB 조회(`findById`) → 예외가 필터 체인에서 터져 `@ControllerAdvice` 로 못 잡는다 |
| `/error` 인가 | `permitAll` 아님(SecurityConfig:46). `spring.session.servlet.filter-dispatcher-types=request,async` 라 ERROR 디스패치는 세션 필터를 안 탄다 → **오류 페이지 렌더가 익명으로 취급돼 페이지는 `/login` 302, `/api/**` 는 401 `SESSION_EXPIRED`** 로 바뀔 가능성이 높다(추정, 실검증 필요). 결과적으로 사용자는 "DB 가 죽었다" 가 아니라 "세션이 만료됐다" 를 본다 |
| 로그인 | `CustomUserDetailsService` 가 DB 예외를 그대로 던지면 `InternalAuthenticationServiceException` → failureHandler 의 `else` 분기 → `/login?error=true` = **"아이디 또는 비밀번호가 올바르지 않습니다"** 오표시 |
| Hikari | `spring.datasource.hikari.*` 미설정 → 기본 `connectionTimeout` 30초. DB 호스트가 패킷을 버리면 요청마다 최대 30초 멈춤 |
| 기동 | DB 없으면 기동 실패(`UserService.initDefaultAdmin` `@PostConstruct`, `LeakRuleSeeder`, `ddl-auto=update`). 따라서 "DB 비정상" = **기동 후 DB 가 끊긴 상황** 이 실질 범위 |
| 프런트 | `Common.fetchJSON` 은 401/429 만 특별 처리, 5xx 는 `HTTP 500: …` 원문 토스트. `session-timeout.js` keepalive 는 401/403 만 만료로 본다 |

### B-2. 목표 상태

1. DB 장애를 **세션 만료·로그인 실패로 오인시키지 않는다.** 화면·API·로그인 세 경로 모두 "DB 연결 불가" 를 명시한다.
2. 이미 열린 화면은 **깨지지 않고 '자료를 불러올 수 없음' 상태로 남는다.** 자동 갱신은 멈추지 않되 실패를 배지로 알리고, 복구되면 저절로 되돌아온다.
3. 장애 중 **요청이 30초씩 멈추지 않는다.**
4. 정적 리소스·로그인 화면·오류 페이지는 DB 없이 렌더된다.

### B-3. 표시 방안 (계층별)

**① 서버: DB 상태 판정의 단일 출처 — `DbHealthService`**
- `DataSource.getConnection()` + `isValid(2)` 를 **5초 TTL 캐시**로 감싼 `isUp()` / `lastError()` / `downSince()`. 요청마다 커넥션을 잡지 않는다.
- Hikari 명시 설정: `spring.datasource.hikari.connection-timeout=5000`, `validation-timeout=2000`, `initialization-fail-timeout` 은 기동 fail-fast 유지. k8s `config/application.properties` 에도 동일 키(치환 전용 동기화라 없으면 반영 안 됨).
- `/api/system/status` 에 `dbUp`, `dbError`, `dbDownSince` 필드 추가. 이 엔드포인트가 배너의 60초/설정 주기 폴링 대상이라 **모든 페이지가 자동으로 DB 상태를 받는다.**

**② 세션 필터 단계 장애 — 오류 페이지가 세션 없이 렌더되게**
- `SecurityConfig`: `.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` 을 `authorizeHttpRequests` 맨 앞에 추가(ERROR 디스패치는 세션 필터를 타지 않으므로 익명이며, 이걸 허용하지 않으면 오류가 로그인 리다이렉트로 바뀐다). `/error` 자체도 `permitAll`.
- 커스텀 `ErrorController`(또는 `templates/error/503.html` + `ErrorAttributes` 커스터마이즈): 예외 체인에 `DataAccessResourceFailureException` / `CannotCreateTransactionException` / `CannotGetJdbcConnectionException` / `JDBCConnectionException` / `SQLTransientConnectionException` 이 있으면 **503 + `Retry-After`** 로 통일하고
  - HTML 요청 → **배너 없는 자립형 `error/db-unavailable.html`**(`login.html` 처럼 세션·CSRF meta 불필요, `_csrf` 참조 금지 — CSRF 토큰 저장소도 세션이다). 내용: "데이터베이스에 연결할 수 없습니다 / 애플리케이션 서버는 정상 / 발생 시각 / 담당자 안내 / [다시 시도] 버튼(자동 재시도 30초)".
  - `/api/**` → JSON `{success:false, code:"DB_UNAVAILABLE", error:"데이터베이스에 연결할 수 없습니다…"}` 503. 기존 3필드 계약 유지.
- 판정은 `DbHealthService.isUp()` 이 아니라 **예외 타입**으로 한다(예외가 진실, 캐시는 표시용).

**③ 로그인 화면**
- failureHandler 에 `InternalAuthenticationServiceException` 분기 → `/login?error=unavailable` → `AuthController` 가 `unavailableMessage` "데이터베이스에 연결할 수 없어 로그인할 수 없습니다. 잠시 후 다시 시도해 주세요." 로 별도 렌더(`login.html` `msg-disabled` 계열 스타일 재사용). 기존 `errorMessage` 와 분리해야 오답 안내를 막는다.
- 로그인 GET 은 `settings.json` 만 읽으므로 DB 없이 열린다(현행 유지). 단, 세션 쿠키가 있는 브라우저의 POST 는 `CsrfFilter` 가 세션을 읽다 죽어 ② 경로로 간다 — ② 가 먼저 있어야 한다.

**④ 프런트 공통 — `Common.fetchJSON` 503 승격 + 배너 DB 점**
- `Common.fetchJSON`: 503 이고 본문 `code === 'DB_UNAVAILABLE'` 이면 `err.dbUnavailable = true` + 한국어 메시지(401 `sessionExpired`/429 `rateLimited` 와 같은 승격 규약). 페이지 JS 는 `e.dbUnavailable` 로 분기해 "저장 실패: DB 연결 불가" 를 띄우고 **재로그인 유도를 하지 않는다.**
- `session-timeout.js`: keepalive·폴러가 503 `DB_UNAVAILABLE` 을 받으면 만료 판정에서 제외(현행은 401/403 만 만료라 이미 안전하지만, ② 가 잘못돼 401 로 새면 오판 — ② 적용 후 회귀 테스트로 고정).
- 배너 System Status 카드에 **DB 행(점: 정상/연결 불가 + 끊긴 시각 툴팁)** 추가. `dbUp=false` 면 `document.body.classList.add('db-down')` 을 켜고 상단에 얇은 전역 스트립(`.db-down-strip`: "데이터베이스 연결 불가 — 표시된 자료는 마지막 성공 시점 기준입니다") 을 배너가 자체 주입. 복구되면 제거. **좌측 색 띠 금지**(메모리 규칙) — 옅은 배경 + 1px 테두리 + 배지.
- 자동 갱신(Part A 의 `LiveRefresh`)은 503 을 받으면 **기존 내용을 지우지 않고** '마지막 갱신 HH:MM:SS · 갱신 실패(DB)' 배지만 바꾼다. 알림 벨은 `state.error` 에 "DB 연결 불가" 를 넣어 말풍선에 보인다.

**⑤ 장애 중에도 살아 있어야 하는 것 / 살릴 수 없는 것**
- 살아 있음: 정적 리소스, `/login` GET, 오류 페이지, `/api/system/status`(DB 필드만 false), 세션 **없는** 새 브라우저의 로그인 폼 표시.
- 살릴 수 없음(명시): 인증 자체(사용자 조회), 세션이 있는 사용자의 모든 페이지·API(세션 조회가 DB), 분석 결과 조회·저장(`analysis_result_detail`), ACK, 메모. 이들은 ② 의 503 안내로 통일한다. 메모리 캐시(`HeapAnalysisResultCache`)가 있어도 세션 필터가 먼저 막으므로 "읽기 전용 열람" 은 세션 저장소를 바꾸지 않는 한 불가 — **범위 밖으로 명시**.

### B-4. 구현 시 순서·검증 (별도 단계용)
1. Hikari 타임아웃 + `DbHealthService` + `/api/system/status` 필드 (무해, 먼저)
2. `SecurityConfig` ERROR 디스패치 permitAll + `ErrorController` + `error/db-unavailable.html` + API 503 JSON
3. 로그인 failureHandler `unavailable` 분기
4. `Common.fetchJSON` 503 승격 + 배너 DB 행 + 전역 스트립 + LiveRefresh/벨 실패 표시
5. 검증: 운영 DB 를 내릴 수 없으므로 **`iptables -A OUTPUT -d 192.168.56.9 -p tcp --dport 3306 -j DROP`(패킷 드롭 = 최악 케이스)와 `REJECT`(즉시 거부) 두 가지**로 개발 환경에서 재현. 확인 항목: 페이지 503 안내(로그인 리다이렉트 아님) / `/api/**` 503 JSON / 로그인 `unavailable` 문구 / 응답 지연 ≤5초 / 배너 DB 점·스트립 / 복구 후 스트립 자동 제거·세션 유지. MockMvc: `SecurityConfig` ERROR 디스패치 허용, `ErrorController` 예외→503 매핑, failureHandler 분기, `Common.fetchJSON` 승격은 JS 라 템플릿/모듈 문자열 스모크.

### B-5. 위험·주의
- ERROR 디스패치를 `permitAll` 해도 오류 페이지 템플릿이 `${_csrf}` 나 배너를 참조하면 세션 접근으로 다시 죽는다 — **자립형 템플릿** 필수.
- `spring.session.servlet.filter-dispatcher-types` 는 손대지 않는다(중복 INSERT 방지 사유가 있다).
- 503 코드는 `LLM_DISABLED` 등 "200 유지" 규약과 무관하지만, `Common.fetchJSON` 을 안 쓰는 수동 fetch 경로(multipart·`readJsonSafe`)는 `httpMsg(503)` 문구를 따로 둬야 한다.
- Hikari `connection-timeout` 을 줄이면 **정상 시 부하 피크에서 풀 고갈이 5초 만에 오류**가 된다. 풀 크기(기본 10) 실측 후 결정.
