# Heap Dump Analyzer — 변경 이력 (CHANGELOG)

## [2026-04-26] RAG 전용 페이지 분리 + 청킹 옵션 추가

**변경 파일:** `application.properties`, `HeapDumpConfig.java`, `HeapDumpAnalyzerService.java`,
`RagService.java`, `HeapDumpController.java`, `settings.html`, `rag-settings.html` (신규),
`fragments/banner.html`

### A. RAG 설정을 General 페이지 → 전용 페이지로 분리
- 신규 라우트 `GET /settings/rag` → `rag-settings.html` (LLM Configuration 페이지 패턴 재사용)
- `settings.html` (General)에서 RAG 카드/모달/JS 일괄 제거 → 링크 카드 1개로 대체 (LLM Configuration 카드와 동일 형태). 카드 클릭 시 `/settings/rag`로 이동
- 배너 Servers/Settings 서브메뉴 패턴 따라 Settings 서브메뉴에 `RAG Configuration` 항목 추가 (`fragments/banner.html`). `gb-nav-sub.open` max-height: 120px → 200px (4 → 5 항목 수용)
- 배너 active 처리: `/settings/rag` 경로 시 `gbNavSettingsRag` 활성화

### B. RAG 청킹 (post-retrieval) 옵션
- 신규 설정: `rag.chunking.{enabled,strategy,size,overlap,max-chunks-per-doc,max-total-chars}`
  - `strategy`: `fixed`(고정 길이 + overlap) | `paragraph`(빈 줄 단위) | `sentence`(문장 단위 + overlap)
  - `size`: 청크 최대 글자수 (기본 800, 100~8000 검증)
  - `overlap`: 인접 청크 중복 글자수 (기본 120, fixed/sentence에서 적용. size보다 작아야 함)
  - `max-chunks-per-doc`: 한 문서당 최대 청크 수 (기본 3, 1~20)
  - `max-total-chars`: LLM 컨텍스트 전체 글자수 한도 (기본 6000, 500~50000)
- `HeapDumpConfig` `@Value` 6개 + `HeapDumpAnalyzerService` `volatile` 필드 + 영속화(settings.json + application.properties 동기화) — 기존 RAG 패턴 재사용
- `setRagChunkingConfig()` 메서드: 입력값 범위 검증 + persist
- `RagService.fetchContextForLlm()`에 청킹 통합:
  - 청킹 ON: `chunkText(text, strategy, size, overlap, maxChunks)` 호출 → 자료별 청크를 `--- 자료 N.M ---` 헤더로 구분
  - `maxTotalChars` 한도 누적 체크 — 한도 초과 시 break (첫 청크가 한도를 넘으면 잘라서라도 일부 주입)
- `chunkText()` 구현:
  - **fixed**: `step = size - overlap`로 슬라이딩 윈도우 분할
  - **paragraph**: `\n{2,}` 빈 줄 분할 후 size 한도 내에서 머지, 단일 문단이 size 초과 시 fixed 폴백
  - **sentence**: `(?<=[\.!?。？！])\s+` 정규식으로 한국어/영문 문장 종결자 인식. tail overlap 유지
  - 모든 전략에서 `maxChunks` 초과 시 절단

### C. API
- `GET /api/settings/rag` 응답에 `chunking` 객체 + `availableChunkingStrategies` 배열 추가
- `POST /api/settings/rag/chunking` 신규 — 청킹 옵션 단독 저장 (연결 설정과 분리되어 있어 배포 후 청킹만 튜닝 가능)

### D. RAG Configuration 페이지 (`rag-settings.html`)
- 카드 구성: Enable RAG / Connection (URL, Index, SSL Verify) / Authentication (none|basic|api-key, 동적 필드 토글) / Search (Mode, Text Field, Top-K, Min Score, Timeout) / **Chunking** (Enable, Strategy, Size, Overlap, Max per Doc, Max Total) / Save All + Test Connection + Reload
- "Save All" 버튼: 연결 설정 + 청킹 옵션을 두 API에 순차 PUT (체이닝). 두 번째 실패 시 첫 번째는 이미 저장된 상태 — 실패 메시지로 알림
- "Test Connection" 버튼: 현재 폼 값으로 `/api/settings/rag/test` 호출 → cluster name/status, index 존재 여부 인라인 표시
- 비밀번호/API Key 입력은 빈 값이면 페이로드에서 제외 → 백엔드가 "기존 값 유지"로 해석 (저장된 마스킹 안내 표시)
- Phase 1 안내 배너: "Keyword(BM25)만 동작, Semantic 모드는 Phase 2 — RAG_PHASE2_PLAN.md 참고"
- CSRF 메타 태그 + `getCsrfHeaders()` 헬퍼 (다른 페이지 패턴 일관성)
- 입력 검증: URL 빈 값 거부, overlap >= size 거부 (toast)

### 검증
- 빌드 성공 (`mvn clean package -DskipTests`)
- `GET /api/settings/rag` → `chunking` 객체 정상 반환
- `POST /api/settings/rag/chunking` → settings.json + application.properties 양쪽 영속화 (paragraph/1000/150/4/8000 적용 확인)
- `/settings/rag` HTML 렌더링 — 모든 chunking 입력 필드 + 청킹 마커 12개 매치
- `/settings` General 페이지 — RAG 모달/JS 잔존 0, 링크 카드만 노출

---

## [2026-04-26] RAG (Elasticsearch) 연동 — Phase 1 (Keyword/BM25 + Settings UI)

**변경 파일:** `application.properties`, `HeapDumpConfig.java`, `HeapDumpAnalyzerService.java`,
`RagService.java` (신규), `HeapDumpController.java`, `AiChatController.java`, `settings.html`

### 배경
사내 운영 환경 ES를 RAG로 연동 가능하도록 Settings 화면에 ES 접속/검색 설정 추가. 사내 ES 매핑(임베딩 사전 색인 여부, inference endpoint 보유 여부)을 운영팀에 확인하기 전 단계라, 호환성 가장 높은 **Keyword(BM25) 모드를 Phase 1**로 구현. Semantic 모드(server-side text_expansion / client-side kNN)는 운영팀 답변 후 Phase 2에서 활성화 예정.

### A. 백엔드 설정/영속화 (LLM 설정 패턴 재사용)
- `application.properties` 신규 키: `rag.enabled`, `rag.elasticsearch.{url,auth-type,username,password,api-key,index,ssl-verify}`, `rag.search.{mode,text-field,top-k,min-score,timeout-seconds}`. 비밀번호/API Key는 `ENC(...)` AES-256-CBC 암호화 형식 (`AesEncryptor`).
- `HeapDumpConfig`: `@Value`로 13개 RAG 필드 + getter 추가
- `HeapDumpAnalyzerService`: `volatile` 런타임 필드 + 생성자 초기화(저장된 ENC 자동 복호화) + `loadPersistedSettings`/`persistSettings`/`syncApplicationProperties`에 RAG 항목 추가. 설정 저장 시 password/apiKey는 `encryptForStorage()`로 ENC(...) 변환 후 settings.json + application.properties에 동시 기록. `setRagConfig()`는 password/apiKey가 null이면 기존 값 유지 (마스킹된 폼 재제출 시 비밀 보존)

### B. RagService 신규 클래스
- `RagService.java` (신규): ES 검색 + 연결 테스트 전담. HeapDumpAnalyzerService의 런타임 getter를 통해 설정값 읽음
- `fetchContextForLlm(query)`: 활성화 시 검색 후 LLM 시스템 프롬프트에 `[참고 자료 (RAG)]` 형식으로 주입. 비활성/실패/빈 결과 시 빈 문자열 — 호출자는 무조건 안전하게 사용 가능
- `search(query, overrides)`: `POST {url}/{index}/_search` BM25 `match` 쿼리 (Phase 1). semantic-* 모드 선택 시 keyword 폴백 + 경고 로그
- `testConnection(overrides)`: `GET /_cluster/health` + `HEAD /{index}` 검증. cluster name/status, indexExists 반환
- 인증: `none` / `basic` (Authorization: Basic base64) / `api-key` (Authorization: ApiKey ...)
- SSL 검증 비활성 옵션 (자체 서명 인증서 사내 환경 대응) — `HttpsURLConnection` + 모든 cert 신뢰 TrustManager
- 검색 결과: `_score` >= minScore 필터 → preferredField → content/body/text/message 폴백

### C. API 엔드포인트
- `GET  /api/settings/rag` — 현재 설정 조회 (password/apiKey는 마스킹 + Set 플래그)
- `POST /api/settings/rag` — 설정 일괄 저장. password/apiKey는 키 자체가 없거나 null이면 기존 값 유지, 빈 문자열이면 삭제, 그 외는 갱신
- `POST /api/settings/rag/enabled` — 활성화 토글 단독
- `POST /api/settings/rag/test` — 연결 테스트 (request body의 overrides 우선, 누락 시 저장된 값 사용 → 저장 전 검증 가능)
- `POST /api/settings/rag/search` — 검색 프로브 (디버깅/검증용)

### D. LLM 채팅 흐름 통합
- `AiChatController.streamChat()`: 마지막 user 메시지로 `ragService.fetchContextForLlm()` 호출 → systemPrompt 끝에 추가
- `HeapDumpController.aiChat()` (legacy): 동일 패턴
- `HeapDumpController.aiChatStream()` (legacy): 동일 패턴
- 원샷 분석(`/api/llm/analyze`)은 prompt 자체가 분석 결과 데이터라 RAG 검색 키 부적합 → Phase 1에서는 미적용

### E. Settings 페이지 UI (DB 카드 패턴 재사용)
- 신규 카드 `RAG (Elasticsearch)`: ON/OFF 토글 + URL/Index/Mode/Auth/Top-K 요약 + "설정 변경" 버튼. ON 시 파란 뱃지
- 신규 모달 `ragEditModal`: URL/Index/Mode/Text Field/Top-K/Min Score/Timeout/Auth(none|basic|api-key)/Username/Password/API Key/SSL Verify. 인증 방식 변경 시 `onRagAuthChange()`로 관련 박스만 표시
- Phase 1 안내 박스: "Keyword(BM25) 모드만 동작. Semantic 모드는 사내 ES 매핑 확인 후 활성화 예정"
- "연결 테스트" 버튼: 현재 폼 값으로 `/api/settings/rag/test` 호출 → cluster name/status + index 존재 여부 인라인 표시 (성공: 녹색 / 경고: 노랑 / 실패: 빨강)
- 비밀번호/API Key 입력은 빈 값이면 페이로드에서 제외 → 백엔드가 "기존 값 유지"로 해석 (마스킹된 placeholder로 안내)
- `loadAllData()` → `loadRagSettings()` 추가, 설정 변경 후 자동 갱신

### 검증
- 빌드 성공 (`mvn clean package -DskipTests`)
- `GET /api/settings/rag` → 기본값 정상 반환
- `POST /api/settings/rag` → settings.json + application.properties 양쪽에 ENC(...) 형식 비밀번호 영속화 확인
- 마스킹 응답: `passwordMasked: "****23"` (마지막 2자리만 노출)
- `POST /api/settings/rag/test` (가짜 host) → `[UnknownHostException]` 명확한 에러 응답
- Settings 페이지 HTML 렌더링 — RAG 카드 + 모달 정상 노출

---

## [2026-04-26] 로그인 화면 사용자명 저장 기능 추가

**변경 파일:** `login.html`

- 로그인 폼에 "사용자명 저장" 체크박스 추가 (비밀번호 필드와 로그인 버튼 사이)
- `localStorage` 키 `heap_login_username`으로 username만 영속 (비밀번호는 저장하지 않음 — 보안)
- 페이지 로드 시 저장된 username 자동 채움 + 체크박스 자동 체크 + 포커스를 password 필드로 이동(사용성)
- 폼 submit 시 체크 상태에 따라 `setItem`/`removeItem` 분기. localStorage 차단 환경 대비 try/catch로 폴백 — 저장 실패해도 로그인 진행
- CSS: `.form-remember` (16px 체크박스, accent-color blue, 480px 이하 모바일에서 12px 폰트)

---

## [2026-04-21] 분석 페이지 플로팅 AI 채팅 대화 히스토리 복원 + LLM 설정 토글

**변경 파일:** `HeapDumpAnalyzerService.java`, `HeapDumpController.java`,
`application.properties`, `llm-settings.html`, `analyze.html`

### A. 플로팅 챗 대화 복원
- 분석 페이지 FAB 플로팅 채팅에서 같은 덤프(filename)에 대한 이전 AI 대화를 자동 복원
- 트리거: 채팅 패널 최초 open 시 `restoreChatHistory()` 호출 — `GET /api/ai-chat/sessions?filename=xxx` (본인 소유, updatedAt desc 정렬)로 최근 세션 1개 자동 바인딩, `GET /api/ai-chat/sessions/{id}/messages`로 대화 로드
- 신규 함수 (analyze.html): `restoreChatHistory()`, `loadChatMessages()`, `renderRestoredMessage()`, `appendContextResetNotice()`, `extractChatDate()`, `extractChatTime()`, `appendChatDateSep()`, `_parseChatDateTime()`
- 시나리오 분기:
  - 세션 0건 → 기존 welcome (첫 전송 시 세션 생성)
  - 세션 1건+ → 가장 최근 세션 복원, `_aiChatSessionId` 자동 설정 → `ensureChatSession()`이 재사용해 추가 메시지도 같은 세션에 이어짐
- 날짜 구분선 (오늘/어제/YYYY년 M월 D일) + 메시지별 시간 (오전/오후 H:MM) 표시 — `ai-chat.html` 패턴 재사용
- "초기화" 버튼 → "새 대화" 라벨 변경 + `_aiChatRestoreAttempted=true` 유지로 재복원 차단 (사용자 의도 존중, 과거 세션은 DB 보존)
- "새 대화" 버튼 클릭 시 **확인 모달** 노출 (`newChatModal`): "이전 대화는 DB에 보존 / 다음 메시지 전송 시 새 세션 생성" 안내 문구 + Cancel / 새 대화 시작 버튼
- 로딩 스피너, 인증 만료/네트워크 실패 시 welcome 폴백, `encodeURIComponent(FILENAME)` 적용
- 메시지 DB 저장 로직 무변경 — 기존 `/stream` 엔드포인트가 user/assistant 자동 저장 (기존)

### B. LLM 설정 — 이전 대화 컨텍스트 포함 토글
- 신규 설정 `llmChatRestoreIncludeHistory` (default: true, 기존 동작 호환)
  - ON: 복원된 이전 대화를 `_aiChatMessages`에 로드 → AI가 기억 (`trimChatMessages()` 24000자 컷 자동 적용)
  - OFF: UI에만 렌더, 컨텍스트는 빈 배열 → AI는 신규 대화로 인식. "— 여기까지 이전 대화 (AI는 기억하지 않음) —" 구분선 표시로 혼란 방지
- 신규 API: `POST /api/llm/chat-restore-mode {includeHistory: bool}`
- `/api/settings` 응답에 `llm.chatRestoreIncludeHistory` 필드 추가
- 설정 영속화: `settings.json` (`llmChatRestoreIncludeHistory`) + `application.properties` (`llm.chat.restore-include-history`) 양방향 동기화 — 기존 패턴 재사용
- LLM 설정 페이지(`/settings/llm`)에 "Chat History Restoration" 카드 + 토글 스위치 추가, 상태별 설명 문구 동적 전환, 토글 실패 시 원복
- 분석 페이지 Thymeleaf 모델에 `llmChatRestoreIncludeHistory` 주입 → JS 전역 `LLM_CHAT_RESTORE_INCLUDE_HISTORY`로 복원 로직이 조건 분기
- 모드 전환은 다음 페이지 로드 시 반영 (진행 중 대화 중단 방지)

---

## [2026-04-21] 분석 페이지 다이어그램 클릭 → Component Detail 모달 연결

**변경 파일:** `analyze.html`

- Overview 패널 4개 다이어그램 모두에서 클래스 클릭 시 Top Consumers 행 클릭과 동일한 `componentDetailModal` 열림
  - Memory Treemap 셀
  - Stacked Bar 세그먼트 및 Legend 항목
  - Pie Chart 슬라이스 및 범례(legend)
  - 수평 Bar Chart 막대
- 데이터 매칭: `OBJ_NAMES[i]`의 i가 Top Consumers `s.index`와 동일하므로 별도 역매칭 없이 `showComponentDetail(className, i)` 재사용
- `buildTreemap()` items / `buildStackedBar()` segments에 `index`/`clickable` 속성 추가 — "Others" 집계 셀은 `clickable:false`로 클릭 비활성 (cursor:default)
- Chart.js 다이어그램: `options.onClick` + `onHover`로 cursor pointer 자동 전환. Pie는 `pieClassCount` 경계로 "Others" 인덱스 차단
- 시각적 피드백: clickable 셀에 `cursor:pointer`, native `title="클릭하여 상세 보기"`, 커스텀 툴팁 하단에 힌트 텍스트 추가
- 모바일 touch → click 합성 호환: 클릭 시 `treemapTooltip` 명시 숨김 처리로 잔존 방지
- 모달/백엔드 수정 없음 — 호출부만 추가. parsed → raw → histogram fallback 체인이 모든 다이어그램에서 그대로 동작

---

## [2026-04-21] CLAUDE.md 갱신 (최근 변경 반영)

**변경 파일:** `CLAUDE.md`

- 통합 변경 반영: Comparison 네비, Bulk-delete API, 페이지네이션·정렬·다중선택 공통 패턴, deleted 가시성 제어, DB id 기반 순번, 모바일 Chat 탭, 채팅 커서 placeholder, 대시보드 intro 카드, Recent Files 카운터
- HeapDumpController 엔드포인트 목록에 bulk-delete API + Authentication/isAdmin 추가
- files/history 페이지 설명을 새 툴바 패턴 기준으로 갱신 (status dot 제거)
- compare.html을 picker 모드 포함하여 갱신
- Key Design Decisions에 9개 신규 패턴 추가

---

## [2026-04-21] AI 답변 대기 중 커서 깜빡임 수정

**변경 파일:** `ai-chat.html`, `analyze.html`

- 말풍선이 생성되었지만 첫 chunk가 아직 도착하지 않은 시점(초기 placeholder)에서 커서가 깜빡이지 않던 문제 수정
- 원인: 초기 cursor를 `<span style="color:#9CA3AF">▌</span>` (정적) 로 표시 후, 첫 chunk 도착 시에만 `chat-cursor`/`ai-chat-cursor` 클래스(애니메이션) 적용
- 수정: 초기 placeholder도 깜빡임 클래스(`chat-cursor` / `ai-chat-cursor`) 사용
- 적용 위치: AI Chat 페이지(`sendMessage`), 분석 페이지 플로팅 채팅(`createStreamBubble`)

---

## [2026-04-21] 정렬 기본값 변경 + 모바일 AI Chat 세션 탭 추가

**변경 파일:** `history.html`, `files.html`, `fragments/banner.html`, `ai-chat.html`

- **정렬 기본값**: 날짜 오름차순 → 내림차순(최신 → 오래된)으로 양 페이지 통일
- **모바일 AI Chat 채팅 목록 접근성 개선**:
  - 좌측 배너 모바일 탭에 "Chat" 탭 신설 (`data-tab="chat"`, 채팅 아이콘)
  - `body.has-chat-tab` 클래스로 ai-chat 페이지에서만 노출
  - banner.html에 `gbMobileChat` 슬롯 + `registerBannerChatTab()` 함수 추가
  - ai-chat.html: `DOMContentLoaded`에서 `.session-sidebar`를 cloneNode하여 배너 슬롯 등록 (내부 ID 제거로 충돌 방지)
  - `getElementById('sessionList'/'sessionFilter')` → `querySelectorAll('.session-list'/'.session-filter select')`로 전환, 원본+클론 동시 갱신
  - 필터 셀렉트는 `onFilterChange(this)` + `syncFilterValue()`로 양 인스턴스 동기화
  - 세션 클릭 시 모바일에서는 `closeMobileBanner()`로 배너 자동 닫고 채팅 본문 노출
  - `#gbMobileChat .session-sidebar`는 풀-블리드 표시되도록 CSS 오버라이드

---

## [2026-04-21] History/Files 테이블 헤더 클릭 정렬 기능

**변경 파일:** `HeapDumpController.java`, `history.html`, `files.html`

- **DTO 확장**: `AnalysisHistoryItem`에 raw 바이트 필드 추가 (`sizeBytes`, `originalSizeBytes`, `compressedSizeBytes`, `heapUsedBytes`)
- `buildHistory()`에서 위 값들을 entity/file로부터 채움
- **공통 정렬 동작**:
  - 헤더 클릭 시 오름차순 ↔ 내림차순 토글, 다른 헤더 클릭 시 asc부터
  - 활성 헤더에 ▲/▼ 인디케이터 (파란색), 비활성은 흐린 ▲
  - 정렬 후 검색/필터/페이지네이션 자동 재적용 (`sortRows()` → `applyFilter()`)
  - 한글 정렬: `localeCompare(s, 'ko')`
- **History 정렬 칼럼**: # (id), 결과(status), 파일명, 분석 시간, 힙 사용량, Suspects, 파일 크기, 서버, 날짜
- **Files 정렬 칼럼**: # (id), 파일명, 원본 크기, 압축 크기, AI 인사이트(severity rank), 날짜
- **기본 정렬: 날짜 오름차순** (오래된 → 최신, 양 페이지 동일)
- raw 숫자값은 `data-sort-*` 속성으로 행에 직렬화 후 클라이언트 정렬

---

## [2026-04-21] 대시보드 Analysis Files에서 deleted 항목 항상 숨김

**변경 파일:** `HeapDumpController.java`

- `index()` 메서드에서 `buildHistory()` 결과를 stream filter로 `fileDeleted=true` 제외
- 모든 계정(관리자 포함) 동일하게 적용
- `totalFileCount`, `hasMoreFiles`, `analyzedCount`, `totalSuspects` 등 파생 값도 자동으로 deleted 제외 기준
- History/Files 페이지는 기존 정책 유지 (관리자 토글로 표시 가능)

---

## [2026-04-21] History/Files 다중 선택 일괄 삭제 기능

**변경 파일:** `HeapDumpController.java`, `history.html`, `files.html`

- **신규 API**:
  - `POST /api/history/bulk-delete` — body `{filenames:[], deleteHeapDump:bool}` → 응답 `{success, failed, errors}`
  - `POST /api/files/bulk-delete` — body `{filenames:[]}` → 응답 동일
- **공통 UI 패턴 (history.html, files.html)**:
  - 검색바 우측에 "선택" 토글 버튼. 활성화 시 파란색 배경
  - 활성 시 좌측 첫 칼럼에 체크박스 표시 (CSS `select-mode` 클래스로 토글)
  - 헤더 체크박스로 현재 페이지 표시 행 일괄 선택/해제 (indeterminate 상태 지원)
  - 하단 고정 액션 바: "N건 선택됨 | 취소 | 선택 삭제"
  - 일괄 삭제 모달에서 확인 → API 호출 → 페이지 새로고침
- **History 모달**: "힙덤프 파일도 함께 삭제" 옵션 체크박스 (이미 deleted된 항목은 자동 건너뜀)
- **Files 모달**: 살아있는 파일은 `/api/files/bulk-delete` (heap dump 삭제), deleted 항목은 `/api/history/bulk-delete` (분석 기록 purge)로 자동 분리 호출
- 페이지/필터 이동 시에도 선택 카운트 자동 갱신 (`render()`에 훅)
- 부분 실패 시 실패 건수 + 에러 메시지를 alert로 표시

---

## [2026-04-21] deleted 기록 관리자 전용 표시/삭제 기능

**변경 파일:** `HeapDumpController.java`, `history.html`, `files.html`

- **서버 측 필터링**: 비관리자에게는 `fileDeleted=true` 항목을 응답에서 제외 (보안)
- `historyPage()`/`filesPage()`에 `Authentication` 파라미터 + `isAdmin` 모델 속성 추가
- `isAdmin()` 헬퍼 메서드 추가 (ROLE_ADMIN 검사)
- **관리자 전용 토글**: 검색바 우측에 "deleted 표시" 체크박스 (기본 off, `localStorage` 기억)
  - history.html: `historyShowDeleted`, files.html: `filesShowDeleted`
  - `applyFilter()`가 `data-deleted="true"` 행을 토글 상태에 따라 제외/포함
- **History 페이지**: `deletedCount` 통계 추가 (관리자가 보는 경우만 표시)
- **Files 페이지**: deleted 행에도 관리자 전용 영구 삭제(purge) 버튼 추가
  - `confirmPurge()` → 별도 모달 → POST `/history/delete/{filename}`
- `/history/delete/{filename}` 엔드포인트: Referer 기반 리다이렉트 (`/files`에서 호출 시 `/files`로 복귀)

---

## [2026-04-21] Recent Files 카운터 표시 개선

**변경 파일:** `index.html`

- 사이드바 "Recent Files (12)" 라벨이 실제 표시 개수(5개로 제한)와 불일치하던 문제 수정
- 전체가 5건 이하: "Recent Files (3)" — 단일 숫자
- 전체가 5건 초과: "Recent Files (5 / 12)" — 표시 / 전체 형태
- `fileCount`(전체)와 `#lists.size(files)`(서버에서 잘린 표시 개수)를 함께 사용

---

## [2026-04-21] Quick Actions 정리 및 Compare/Export 동선 재배치

**변경 파일:** `HeapDumpController.java`, `compare.html`, `fragments/banner.html`, `history.html`, `index.html`

- Compare Two Dumps → 좌측 네비게이션 "Comparison" 항목으로 이동
  - `/compare` 컨트롤러: `base`/`target` 파라미터 옵셔널화. 미입력 시 파일 선택 화면 노출
  - `compare.html`: `/api/history`에서 SUCCESS 분석 이력 fetch → base/target 셀렉트 자동 채움 + Compare 버튼
  - banner: History 다음에 "Comparison" 링크 + collapsed icon 추가
- Export History → History 페이지 topbar로 이동
  - `history.html` topbar에 Export 버튼 + 모달 + 다운로드 JS (기존 `/api/history` 재사용)
- Settings → Quick Actions에서 제거 (좌측 네비에 이미 존재)
- `index.html` Quick Actions 섹션 통째로 제거. 관련 모달(`#compareModal`, `#exportHistoryModal`) 및 JS(`openCompare`, `closeCompare`, `startCompare`, `exportHistory`, `closeExportHistory`, `doExportHistory`, Esc 키 핸들러) 정리

---

## [2026-04-21] 대시보드 상단에 앱 소개 문구 추가

**변경 파일:** `index.html`

- `.main-content` alerts 영역 직후, disk-warn-banner 직전에 intro 카드 삽입
- 문구: "Eclipse MAT 기반 분석기. 원격 서버에서 덤프를 자동 수집하고, 누수 의심 대상을 요약하며, AI 로 근본 원인을 찾아냅니다."
- info SVG 아이콘 + 그라데이션(#EFF6FF→#F5F3FF) + 1px #DBEAFE 테두리
- `.dashboard-intro` CSS 신설, `@media (min-width:1024px)`에서 14px / `@media (max-width:900px)`에서 12px·padding 축소
- 신규 사용자에게 도구 가치를 즉시 전달, 빈 상태에서도 항상 노출 (`th:if` 없음)

---

## [2026-04-21] Analysis History/Files 순번을 DB id 기반으로 변경

**변경 파일:** `HeapDumpController.java`, `history.html`, `files.html`

- 행 시프트 문제 해결: 표시 인덱스(`#lists.size - iter.index`) → DB `analysis_history.id` (영구 식별자)
- 행을 삭제해도 다른 행의 번호가 변하지 않음. 같은 레코드는 항상 같은 번호로 식별
- `AnalysisHistoryItem` DTO에 `Long id` 필드 추가, `buildHistory()`에서 `e.getId()`로 채움
- `history.html`: `${h.id}` (모든 항목이 분석 이력이라 항상 존재)
- `files.html`: `${h.id != null ? h.id : '-'}` — NOT_ANALYZED 파일은 DB 레코드 없으므로 `-` 표시

---

## [2026-04-21] Files 페이지에 페이지네이션 + 순번 칼럼 + 상태 인디케이터 제거

**변경 파일:** `files.html`

- `상태` dot 칼럼 + 범례(legend) 영역 제거 (`.hdot`, `.hd-*`, `.legend*`, `.ld-*` CSS 정리)
- 신규 `#` 순번 칼럼 추가 (오래된=1, 최신=마지막). 식: `${#lists.size(analysisHistory) - iter.index}`
- 검색바 우측에 "행 표시" 콤보박스 (20/30/50/100, 기본 20, `localStorage('filesPageSize')` 저장)
- 패널 하단 페이지네이션 바 (전체 ≤ 페이지 크기 시 자동 숨김), ‹ Prev / 슬라이딩 윈도우 / Next ›
- 기존 `filterFiles()` → `applyFilter() + render() + renderPagination()` 구조

---

## [2026-04-21] Analysis History 페이지네이션 + 행 표시 갯수 선택 기능

**변경 파일:** `history.html`, DB seed (analysis_history)

- 더미 데이터 30건 DB 삽입 (`dummy_dump_01..30.hprof`, SUCCESS 24/ERROR 6 비율, `file_deleted=true`, 30일에 걸친 `analyzed_at`)
- 테이블 우측 상단 "행 표시" 콤보박스 추가 (20/30/50/100, 기본 20, `localStorage` 저장)
- 패널 하단 페이지네이션 바 추가 (전체 건수가 페이지 크기 초과 시 자동 활성화)
- 페이지 버튼: ‹ Prev, 1 … 현재±2 … 마지막, Next ›. 활성 버튼 파란 배경
- 검색(`onSearchInput`)과 페이지 크기 변경(`onPageSizeChange`)은 항상 1페이지로 리셋
- 기존 `filterItems()` → `applyFilter()` + `render()` + `renderPagination()` 구조로 리팩터링

---

## [2026-04-21] Analysis History 순번 채번 방향 변경 (오래된=1, 최신=마지막)

**변경 파일:** `history.html`

- 목록은 최신 정렬(`analyzedAt DESC`)이지만 순번은 오래된 데이터부터 1로 시작하도록 변경
- 표현식 변경: `${iter.count}` → `${#lists.size(analysisHistory) - iter.index}`
- 결과: 상단(최신)이 가장 큰 번호, 하단(오래된)이 1번

---

## [2026-04-21] Analysis History 테이블에 순번 칼럼 추가 및 상태 인디케이터 제거

**변경 파일:** `history.html`

- `상태` 칼럼(컬러 dot)을 제거 (직전 변경에서 추가된 `결과` 뱃지 칼럼이 동일 정보 제공)
- 신규 `#` 순번 칼럼 추가 (`th:each` 변수에 `iter` 추가, `${iter.count}` 사용)
- `.td-seq` CSS 추가 (`width: 48px; text-align: center; tabular-nums`)
- `.hdot`, `.hd-ok`, `.hd-err`, `.td-status` CSS 제거

---

## [2026-04-21] Analysis History 테이블에 결과 칼럼 분리

**변경 파일:** `history.html`

- 파일명 칼럼에 인라인으로 표시되던 `success`/`failed`/`deleted` 뱃지를 별도 "결과" 칼럼으로 분리
- `<thead>`에 `<th class="td-result">결과</th>` 추가 (상태 칼럼 다음 위치)
- `.td-result` CSS 추가 (`width: 84px; white-space: nowrap;`)
- 결과를 한눈에 정렬된 형태로 확인 가능, 파일명은 깔끔하게 표시

---

## [2026-04-14] 분석 실행 시 배너 System Status 자동 갱신

**변경 파일:** `progress.html`

- 분석 시작(첫 RUNNING 수신), 완료(COMPLETED), 에러(ERROR) 시 `refreshBannerStatus()` 호출 추가
- 분석 중 디스크 사용량, JVM 메모리, 분석 큐 상태가 배너에 실시간 반영

---

## [2026-04-14] Accounts 페이지 레이아웃 가로 폭 확대

**변경 파일:** `admin/users.html`

- 컨테이너 `max-width` 1100px → 1400px로 변경

---

## [2026-04-14] AI 채팅 세션 생성 시 인증 만료 에러 처리

**변경 파일:** `analyze.html`, `ai-chat.html`

- 로그인 세션 만료 상태에서 채팅 시 "Unexpected token '<'" JSON 파싱 에러 발생하던 문제 수정
- 원인: 세션 만료 시 API가 로그인 페이지 HTML(302 리다이렉트)을 반환, 이를 JSON으로 파싱 시도
- analyze.html `ensureChatSession()`: 응답 Content-Type 및 리다이렉트 체크 추가
- ai-chat.html: 공통 `checkAuth()` 함수 추가, 세션 생성/스트리밍 fetch에 적용
- 인증 만료 시 "로그인이 만료되었습니다. 페이지를 새로고침 해주세요." 안내 메시지 표시

---

## [2026-04-14] AI 채팅 메시지 DB 저장 안정성 보강

**변경 파일:** `AiChatController.java`

- 스트리밍 콜백(onDone)에서 assistant 메시지 저장 실패가 `catch (Exception ignored)`로 무시되던 문제 수정
- Assistant 메시지 저장에 3회 재시도 로직 추가 (500ms 간격)
- User/Assistant 메시지 저장 성공/실패 로그 추가 (`[AI-Chat-Stream]` prefix)
- onChunk/onError/스레드 에러 등 모든 catch 블록에 로그 추가
- 클라이언트에 저장 성공 여부 전달 (`done` 이벤트에 `saved` 필드 추가)

---

## [2026-04-14] Raw Data 패널 (System Overview / Top Components / Suspect Details) iframe 로드 수정

**변경 파일:** `SecurityConfig.java`, `analyze.html`

- Raw Data 패널의 iframe이 로드되지 않던 버그 수정
- 원인 1: Spring Security 기본값 `X-Frame-Options: DENY`가 iframe 내 페이지 표시를 차단
- 수정 1: `SecurityConfig`에 `.headers().frameOptions().sameOrigin()` 추가하여 같은 도메인 iframe 허용 (clickjacking 보호 유지)
- 원인 2: `!iframe.src` 조건에서 브라우저가 `"about:blank"`를 반환하면 lazy-load 미실행
- 수정 2: `!iframe.getAttribute('src')`로 변경하여 HTML 속성 기반으로 판단
- iframe의 `sandbox="allow-same-origin allow-scripts"` 속성 제거 (보안 경고 해소 + 불필요한 제약 제거)

---

## [2026-04-14] 분석 페이지 좌측 배너 AI 인사이트 완료 표시 수정

**변경 파일:** `analyze.html`

- AI 인사이트 완료/분석중/실패 상태가 좌측 배너에 반영되지 않던 버그 수정
- 원인: 사이드바가 배너에 `cloneNode`로 복제되어 `id="aiNavStatus"` 요소가 2개 존재, `getElementById`는 첫 번째(원본)만 업데이트
- 수정: `querySelectorAll('.ai-nav-status')`로 원본 + 복제본 모두 업데이트하도록 변경

---

## [2026-04-14] AI Chat 날짜 구분선 및 시간 표시 추가

**변경 파일:** `ai-chat.html`

- 메시지 목록에서 날짜가 바뀌는 시점에 날짜 구분선 표시 (오늘/어제/YYYY년 M월 D일)
- 각 메시지 버블 하단에 시간 표시 (오전/오후 H:MM 형식)
- 새 메시지 전송 시에도 날짜 구분선 및 시간 자동 표시
- 스트리밍 응답 완료 시 assistant 메시지에도 시간 표시

---

## [2026-04-14] Target Server 상세 페이지 추가

**변경 파일:** `ServerController.java`, `server-detail.html` (신규), `servers.html`

- `/servers/{id}` 상세 페이지 신규 추가
  - 서버 정보 카드: 호스트, SSH 계정, 덤프 경로, 상태, 자동탐지, 마지막 확인/에러
  - 분석 이력 테이블: 해당 서버에서 전송된 힙덤프의 분석 결과 (상태, 파일명, Heap 사용량, Suspects, 분석 시간)
  - 전송 이력 테이블: SCP 전송 로그 (상태, 파일명, 원격 경로, 크기, 시간, 에러)
  - 연결 테스트 / 스캔 액션 버튼
- ServerController에 `AnalysisHistoryRepository`, `DumpTransferLogRepository` 의존성 추가
- 존재하지 않는 서버 ID 접근 시 `/servers`로 리다이렉트 (flash error)
- servers.html에서 서버 이름을 클릭 가능한 링크(`/servers/{id}`)로 변경

---

## [2026-04-14] Favicon 추가 및 앱 전체 아이콘 도넛 차트로 통일

**변경 파일:** `favicon.svg` (신규), `SecurityConfig.java`, `banner.html`, `login.html`, 전체 템플릿 13개

- 간결한 도넛 차트 스타일 SVG favicon 추가 (파란 배경 + 흰색 단일 세그먼트 + 중앙 점)
- 모든 HTML 템플릿(13개)에 `<link rel="icon" type="image/svg+xml" href="/favicon.svg">` 삽입
- Spring Security에 `/favicon.svg` 경로 인증 면제 추가 (로그인 페이지에서도 표시)
- 배너 헤더 로고 (gb-header-logo) 막대 차트 → 도넛 차트 아이콘으로 변경
- 로그인 페이지 로고 (login-logo-icon) 막대 차트 → 도넛 차트 아이콘으로 변경

---

## [2026-04-14] History 삭제 시 DB 레코드 미삭제 버그 수정

**변경 파일:** `AnalysisHistoryRepository.java`, `HeapDumpAnalyzerService.java`

- History 페이지에서 이력 삭제 시 `analysis_history` DB 레코드가 삭제되지 않아 테이블에 계속 표시되던 문제 수정
- `AnalysisHistoryRepository`에 `deleteByFilename()` 메서드 추가
- `HeapDumpAnalyzerService.deleteHistory()`에 DB 레코드 삭제 로직 추가 (analysis_history + ai_insights)
- `@Transactional` 어노테이션 추가로 삭제 트랜잭션 보장
- Files 페이지에서도 삭제된 이력의 분석 완료 상태가 정상적으로 반영됨

---

## [2026-04-13] 모바일 배너에 Analysis 탭 추가

**변경 파일:** `banner.html`, `analyze.html`

- 모바일 배너 드로어에 Analysis 탭 추가 (Navigation | Analysis 전환)
- `analyze.html`에서 사이드바(Heap Statistics, Analysis, AI Analysis, Actions, Tools, Raw Data, File Info)를 배너 Analysis 탭에 동적 복제/주입
- Analysis 탭 내 패널 선택 시 배너 자동 닫기 + 해당 패널 표시
- Upload 탭과 동일한 동적 슬롯 패턴: `registerBannerAnalysisTab()` 함수, `body.has-analysis-tab` CSS 클래스

---

## [2026-04-13] 모바일 Files/History 테이블 가로 스크롤 수정

**변경 파일:** `files.html`, `history.html`

- `.panel { overflow: hidden }` 때문에 모바일에서 테이블이 잘리고 가로 스크롤이 불가능했던 문제 수정
- 900px 이하에서 `.panel { overflow-x: auto; -webkit-overflow-scrolling: touch }` 추가
- 640px 이하에서 테이블 `min-width` 지정 (files: 500px, history: 600px) — 터치 스크롤 보장

---

## [2026-04-13] 모바일 배너 드로어 + Navigation/Upload 탭 추가

**변경 파일:** `banner.html`, `index.html`, `analyze.html`, `history.html`, `files.html`, `settings.html`, `compare.html`, `servers.html`, `server-logs.html`, `admin/users.html`, `ai-chat.html`, `llm-settings.html`

- 모바일(900px 이하)에서 좌측 배너를 슬라이드인 드로어로 동작하도록 변경 (기존 `display: none` → 오프스크린 드로어)
- 배너 상단에 Navigation/Upload 모바일 전용 탭 추가. 기본 탭은 Navigation
- Navigation 탭: System Status, 페이지 네비게이션(Dashboard, Files, History, AI Chat, Servers, Settings), 로그아웃
- Upload 탭: `index.html`(Dashboard)에서만 표시 — Upload 영역을 배너에 동적 주입
- 배너에 `gb-menu-btn` 공용 햄버거 버튼 CSS 정의, 모든 페이지의 topbar에 햄버거 버튼 추가
- 배너 오버레이 (`gb-banner-overlay`) 추가 — 배너 외부 클릭 시 자동 닫기
- 내비게이션 링크 클릭 시 모바일 배너 자동 닫기
- `index.html`, `analyze.html`의 기존 `mobile-menu-btn` CSS/JS를 `gb-menu-btn`/`toggleMobileBanner`로 통합

---

## [2026-04-13] Analysis History 서버 정보 자동 매핑 개선

**변경 파일:** `HeapDumpAnalyzerService.java`, `DumpTransferLogRepository.java`

- 원격 서버에서 전송된 파일의 분석 이력에 서버 이름이 "Local"로 표시되던 문제 수정
- `saveAnalysisToDb(result)` 호출 시 `DumpTransferLog`에서 전송 성공 기록을 조회하여 `serverId`/`serverName`을 자동 매핑
- `DumpTransferLogRepository`에 `findByFilenameAndTransferStatusOrderByCompletedAtDesc` 메서드 추가
- `HeapDumpAnalyzerService`에 `DumpTransferLogRepository`, `TargetServerRepository` 의존성 주입
- 기동 시 기존 history 레코드 중 서버 정보가 누락된 항목을 전송 로그 기반으로 자동 보정하는 `fixMissingServerInfoInHistory()` 추가

---

## [2026-04-13] 플로팅 채팅 전체화면 → 확대/축소 토글로 변경

**변경 파일:** `analyze.html`

- 헤더의 전체화면 버튼 및 전체화면 오버레이 HTML/CSS/JS 전체 제거
- 플로팅 채팅 헤더에 확대/축소 토글 버튼 추가 (화살표 아이콘)
- 확대 시: 420×560px → 700px×80vh, CSS transition으로 부드러운 전환
- 축소 시: 원래 크기로 복귀, 아이콘도 변경
- `_aiChatFullscreen` 변수 → `_aiChatExpanded`로 변경
- `getChatContainer()`, `getChatInput()`, `getChatSendBtn()` 단순화 (전체화면 분기 제거)
- 모바일(640px 이하) 확대 시 전체 너비

---

## [2026-04-13] AI 채팅 이력 DB 저장 + 전용 페이지 + 전체화면

**변경 파일:** `AiChatSession.java` (신규), `AiChatMessage.java` (신규), `AiChatSessionRepository.java` (신규), `AiChatMessageRepository.java` (신규), `AiChatController.java` (신규), `ai-chat.html` (신규), `banner.html`, `analyze.html`

### DB 엔티티 및 API
- **`ai_chat_sessions`** 테이블: 세션별 username, filename, title, model, messageCount 저장
- **`ai_chat_messages`** 테이블: 세션별 role, content (MEDIUMTEXT) 저장
- **`AiChatController`**: 세션 CRUD (`/api/ai-chat/sessions`), 메시지 조회/저장, 세션 기반 스트리밍 (`/api/ai-chat/sessions/{id}/stream`)
- 계정별(Spring Security `Principal`) 세션 격리 + 덤프 파일별 세션 분류
- 스트리밍 응답 완료 시 user/assistant 메시지 자동 DB 저장
- 첫 질문에서 세션 제목 자동 생성 (40자 truncate)

### AI Chat 전용 페이지 (`/ai-chat`)
- 좌측 세션 사이드바 (280px): 세션 목록, 새 채팅 버튼, 덤프 파일 필터
- 우측 채팅 영역: 메시지 표시, 스트리밍 입력, 마크다운 렌더링
- 세션 선택 시 기존 메시지 이력 로드
- 세션 삭제 (cascade: 메시지 함께 삭제)

### 배너 네비게이션
- History ↔ Servers 사이에 "AI Chat" 탭 추가 (말풍선 아이콘)
- 접힌 상태 아이콘도 동일하게 추가

### 분석 페이지 전체화면 전환
- 헤더에 "Chat" 전체화면 버튼 추가 (LLM 활성화 시만 표시)
- 전체화면 오버레이: 별도 입력/메시지 영역, 모델 뱃지, 초기화 버튼
- 플로팅 채팅 ↔ 전체화면 간 메시지 동기화

### 분석 페이지 채팅 세션 연동
- `sendChatMessage()` 시 세션 자동 생성 (`ensureChatSession()`)
- 세션 기반 스트리밍 엔드포인트 사용 → DB 자동 저장
- 채팅 관련 DOM 접근을 `getChatContainer()`, `getChatInput()`, `getChatSendBtn()` 헬퍼로 추상화 (전체화면/플로팅 모드 통합)

---

## [2026-04-13] AI 채팅 스트리밍 응답 구현

**변경 파일:** `HeapDumpAnalyzerService.java`, `HeapDumpController.java`, `analyze.html`

- AI 채팅 응답을 실시간 스트리밍으로 출력하도록 변경 (기존 전체 대기 → 토큰 단위 실시간 렌더링)
- **Backend**: `callLlmChatStream()` 메서드 추가 — LLM API에 `stream: true`로 요청, SSE 청크 파싱 (Claude `content_block_delta` / OpenAI `choices.delta.content`)
- **Backend**: `POST /api/llm/chat/stream` 엔드포인트 — `SseEmitter` 반환, 비동기 스레드에서 스트리밍 실행
- **Frontend**: `fetch` + `ReadableStream`으로 SSE 이벤트 실시간 수신, 텍스트 청크마다 assistant 버블에 마크다운 렌더링 업데이트
- 스트리밍 중 깜빡이는 커서(▌) 표시, 완료 시 커서 제거
- SSE 이벤트 구조: `start` (모델 정보), `chunk` (텍스트 조각), `done` (완료), `error` (오류)

---

## [2026-04-13] AI 플로팅 채팅 시스템 구현 + 채팅 시스템 프롬프트 설정

**변경 파일:** `HeapDumpAnalyzerService.java`, `HeapDumpController.java`, `analyze.html`, `llm-settings.html`

### 플로팅 AI 채팅 (analyze.html)
- 분석 페이지 우하단에 56px 원형 플로팅 채팅 버튼 추가 (파란-보라 그라데이션)
- LLM 활성화 시에만 표시, 비활성화 시 자동 숨김
- 채팅 패널 (420×560px): 헤더(모델명 뱃지, 초기화, 닫기), 메시지 영역, 입력 영역
- 멀티턴 대화 지원: 대화 이력을 매 요청마다 전체 전송
- 분석 컨텍스트 자동 주입: KPI, Top Consumers, Leak Suspects, AI 인사이트 결과를 시스템 프롬프트에 포함
- 마크다운 렌더링: bold, 인라인 코드, 코드 블록, 줄바꿈 지원
- 타이핑 인디케이터 (점 3개 bounce 애니메이션)
- 토큰 관리: 메시지 총 길이 초과 시 오래된 메시지 자동 제거
- Enter 전송, Shift+Enter 줄바꿈, Escape 패널 닫기
- 모바일 반응형 (640px 이하 전체 너비)

### 채팅 시스템 프롬프트 설정 (llm-settings.html)
- LLM Settings 페이지에 "Chat System Prompt" 카드 추가
- textarea로 시스템 프롬프트 편집, Save Prompt / Reset to Default 버튼
- `POST /api/llm/chat-prompt` 엔드포인트로 저장, settings.json에 영속화

### Backend
- `HeapDumpAnalyzerService`: `callLlmChat()` 메서드 추가 (멀티턴 messages 배열 수신, 마크다운 응답 반환)
- `HeapDumpAnalyzerService`: `llmChatSystemPrompt` volatile 필드 + `DEFAULT_CHAT_SYSTEM_PROMPT` 상수 + getter/setter + 영속화
- `HeapDumpController`: `POST /api/llm/chat` (채팅 API), `POST /api/llm/chat-prompt` (프롬프트 저장 API) 엔드포인트 추가
- `/api/settings` 응답에 `chatSystemPrompt` 필드 추가

---

## [2026-04-13] 관리자 계정 비활성화 방지 및 Admin → Settings 하위 Accounts 이동

**변경 파일:** `fragments/banner.html`, `UserService.java`, `admin/users.html`

- ADMIN 역할 계정은 항상 활성 상태 유지, 비활성화 불가 (백엔드 + 프론트엔드 모두 적용)
  - `UserService.updateUser()`: ADMIN 역할로 설정된 계정에 `enabled=false` 요청 시 예외 발생
  - 수정 모달: 역할이 ADMIN이면 상태 select 비활성화(disabled) + 값 강제 `true`
  - 역할 변경(USER↔ADMIN) 시 상태 select 연동 (`syncEnabledState()`)
- 배너 Navigation에서 Admin 독립 링크 제거 → Settings 하위 메뉴에 "Accounts" 탭으로 이동 (ADMIN 전용, `sec:authorize`)
- Settings 서브 메뉴 max-height 80px → 120px (3개 항목 수용: General, LLM Configuration, Accounts)
- `/admin/users` 경로 진입 시 Settings 서브 메뉴 자동 펼침 + Accounts 하이라이트

---

## [2026-04-13] 배너 Settings 위치 변경 및 하위 메뉴 구성

**변경 파일:** `fragments/banner.html`, `llm-settings.html`

- Navigation에서 Settings를 Admin 아래로 이동
- Settings를 서브 메뉴(아코디언)로 변경: General / LLM Configuration 두 개 탭 구성
- `/settings` 및 `/settings/llm` 경로 자동 하이라이트 및 서브 메뉴 자동 펼침 적용
- 접힌 상태(collapsed) 아이콘 순서도 동일하게 변경
- LLM Configuration 페이지의 Genspark API 설명을 한국어로 변경

---

## [2026-04-13] AI 인사이트 권장 조치 번호 목록 렌더링 개선

**변경 파일:** `analyze.html`

- `setNumberedList()` 함수 추가: 번호 목록 텍스트를 `<ol>` 리스트로 변환
  - `"1. xxx 2. yyy"` (한 줄 연속) 및 `"1. xxx\n2. yyy"` (줄바꿈 분리) 모두 지원
  - 번호 없는 이어지는 줄은 이전 항목에 자동 병합
  - 번호 원형 뱃지(보라색) 스타일 적용, 항목 간 여백 추가
  - 번호 패턴 미감지 시 기존 `setTextWithLineBreaks()` 폴백
- 권장 조치(`aiRecommendations`) 렌더링에 `setNumberedList()` 적용

---

## [2026-04-13] AI 인사이트 위험도 배너 레이아웃 개선

**변경 파일:** `analyze.html`

- 위험도 배너에서 `severityDesc` 설명 텍스트 제거 (하단 "잠재적 위험 요소" 카드에서 전체 표시)
- `severityDesc`가 있으면 길이와 관계없이 항상 "잠재적 위험 요소" 카드 표시 (기존: 80자 초과 시에만)
- 모바일(768px 이하) 위험도 배너: 가로→세로 레이아웃 전환 (`flex-direction:column`), 글자 세로 출력 문제 해결
- 모바일(480px 이하) 추가 조정: 아이콘/글자 크기 축소, 카드 패딩 조정

---

## [2026-04-13] Files 페이지 테이블 열 확장 (원본/압축 크기 분리, AI 인사이트 표시)

**변경 파일:** `HeapDumpController.java`, `HeapDumpAnalyzerService.java`, `files.html`

- 기존 "크기" 열을 "원본 크기"와 "압축 크기" 2개 열로 분리
  - 원본 크기: 항상 표시 (압축 파일은 formattedOriginalSize, 비압축 파일은 formattedSize)
  - 압축 크기: 압축 파일만 크기 + GZ 뱃지 표시, 비압축 파일은 "—"
- "AI 인사이트" 열 추가
  - AI 분석 결과가 있는 파일: 심각도별 색상 뱃지 (critical=빨강, warning=노랑, info=파랑, 기타=초록)
  - AI 분석 결과가 없는 파일: "—" 표시
- `AnalysisHistoryItem` DTO에 `hasAiInsight`, `aiInsightSeverity` 필드 추가
- `buildHistory()`에서 `AiInsightRepository`를 통해 AI 인사이트 존재 여부 일괄 조회
- `HeapDumpAnalyzerService`에 `getAiInsightRepository()` getter 추가
- 모바일(640px 이하)에서 압축 크기 열 숨김

---

## [2026-04-13] 사용자 계정 및 암호화 보안 취약점 7건 조치

**변경 파일:** `AesEncryptor.java`, `SecurityConfig.java`, `UserService.java`, `AdminController.java`, `init.sql`, `admin/users.html`, `heap_enc.sh`, `heap_dec.sh`

### 3.1 [높음] AES 암호화 키 환경변수 이동
- `KEY_SEED` 하드코딩 제거, 환경변수 `HEAP_ANALYZER_ENCRYPTION_KEY`에서 로드
- 미설정 시 기본 키 폴백 + 경고 로그 출력

### 3.2 [높음] AES IV 랜덤 생성
- 암호화 시 `SecureRandom`으로 16바이트 랜덤 IV 생성, 암호문 앞에 IV 결합
- 복호화 시 HEX 길이 기반으로 레거시(고정 IV) / 신규(랜덤 IV) 자동 판별
- 기존 `ENC(...)` 암호화 값 호환성 유지

### 3.3 [높음] 기본 관리자 비밀번호 외부화
- `shinhan@10` 하드코딩 제거, 환경변수 `HEAP_ADMIN_DEFAULT_PASSWORD`에서 로드
- 미설정 시 UUID 자동 생성 + warn 로그로 비밀번호 출력

### 3.4 [높음] init.sql DB 비밀번호 제거
- `IDENTIFIED BY 'heap_pass_2026'` 평문 비밀번호를 주석 가이드로 대체

### 3.5 [중간] /api/admin/** CSRF 보호 복원
- `SecurityConfig`에서 `/api/admin/**` 경로를 CSRF 면제에서 제외 (`ignoringRequestMatchers` 커스텀 매처)
- `admin/users.html`에 CSRF 메타 태그 추가, 모든 fetch 호출에 CSRF 헤더(`X-CSRF-TOKEN`) 전송

### 3.6 [중간] 비밀번호 정책 서버 검증 추가
- `UserService.validatePassword()` 메서드 추가: 최소 8자, 영문+숫자+특수문자 필수
- `createUser()`, `resetPassword()` 호출 시 검증 실행, 미충족 시 400 응답

### 3.7 [낮음] @PreAuthorize 이중 방어 추가
- `SecurityConfig`에 `@EnableGlobalMethodSecurity(prePostEnabled = true)` 추가
- `AdminController`에 `@PreAuthorize("hasRole('ADMIN')")` 클래스 레벨 적용

---

## [2026-04-13] 사용자 계정 및 암호화 보안 점검 보고서 작성

**생성 파일:** `SECURITY_AUDIT_ACCOUNT.md`

- 사용자 인증/인가, DB 비밀번호 암호화, 계정 관리 API 대상 보안 점검 수행
- 양호 항목 6건 (BCrypt 해싱, SQL Injection 방지, 인증/인가 구조 등)
- 취약점 7건 발견: AES 키 하드코딩(높음), IV 고정(높음), 기본 관리자 비밀번호 하드코딩(높음), init.sql 평문 비밀번호(높음), API CSRF 비활성화(중간), 비밀번호 정책 부재(중간), 메서드 레벨 권한 검증 없음(낮음)
- 각 취약점별 수정 예시 코드 및 개선 방안 포함

---

## [2026-04-13] 대시보드 "탐지 현황" 패��� 추가

**변경 파일:** `HeapDumpController.java`, `index.html`

- Analysis History 패널 제거 (배너 History 링크로 충분)
- "탐지 현황" 패널 추가: 심각도별 카드 (Critical/High/Medium/Low) + 파일별 suspect 목록
- `DetectionSummaryItem` inner DTO 추가 (파일명, suspect수, 심각도별 카운트)
- 각 파일의 `LeakSuspect.severity`를 집계하여 심각도별 통계 표시
- 파일명 클릭 → 분석 결과 페이지 이동 (view 버���)
- suspects 없으면 "탐지된 누수 의심 항목이 없습니다" 빈 상태 표시
- 모바일(480px 이하) 심각도 카드 2x2 레이아웃

---

## [2026-04-13] AI 인사이트 DB 저장으로 전환

**변경 파일:** `AiInsightEntity.java`(신규), `AiInsightRepository.java`(신규), `HeapDumpAnalyzerService.java`, `HeapDumpController.java`, `analyze.html`

- `ai_insights` 테이블 생성 (filename, model, severity, latency_ms, insight_data(MEDIUMTEXT), analysed_at)
- `saveAiInsight()`: DB에 저장 (기존 결과 있으면 UPDATE)
- `loadAiInsight()`: DB 조회 우선, 없으면 파일 폴백 + 자동 DB 마이그레이션
- `deleteAiInsight()`: DB 삭제 + 잔존 파일도 함께 삭제
- 앱 기동 시 기존 `ai_insight.json` 파일을 DB로 자동 마이그레이션 (1회)
- Controller 응답에서 `savedPath` → `savedTo: "database"` 변경
- 프론트엔드 저장 경로 표시: "Database (MariaDB)" 로 변경

---

## [2026-04-12] 배너 Servers 서브 메뉴 아코디언

**변경 파일:** `fragments/banner.html`

- Servers 메뉴를 아코디언 형태로 변경: 클릭 시 하위 메뉴 펼침/접기
- 하위 메뉴: "Target Servers" (`/servers`), "Transfer Logs" (`/servers/logs`)
- `/servers` 또는 `/servers/logs` 경로 접속 시 서브 메뉴 자동 펼침 + 부모/자식 모두 active 하이라이트
- CSS: `.gb-nav-sub` 슬라이드 트랜지션 (max-height), `.gb-nav-toggle` 화살표 회전 애니메이션

---

## [2026-04-12] 전송 진행바 + Transfer Logs 페이지

**변경 파일:** `servers.html`, `server-logs.html`(신규), `ServerController.java`

**전송 진행바:**
- 전송 버튼 클릭 시 애니메이션 프로그레스 바로 교체 (pulse 애니메이션)
- 성공: 초록색 바 + "완료" 라벨 / 실패: 빨간색 바 + "실패" + 에러 메시지 + 재시도 버튼
- 모두 전송 시에도 각 파일별 개별 진행바 표시

**Transfer Logs 페이지 (`/servers/logs`):**
- Servers topbar에 "Transfer Logs" 버튼 추가
- 서버별 아코디언 레이아웃 (첫 번째 서버 기본 열림)
- 아코디언 헤더: 서버명, 호스트, 통계 (Total / Success / Failed)
- 로그 테이블: 상태(뱃지), 파일명, 원격 경로, 크기, 시작/완료 시간, 에러 메시지
- 서버당 최근 50건 표시, 에러 컬럼 hover 시 전체 메시지 노출

---

## [2026-04-12] 스캔 전송 상태 판정 수정 + 모두 전송 버튼 + CSRF 수정

**변경 파일:** `DumpTransferLogRepository.java`, `RemoteDumpService.java`, `servers.html`, `files.html`, `history.html`, `index.html`, `analyze.html`, `progress.html`

- "전송됨" 판정 로직 수정: `existsByServerIdAndFilename` → `existsByServerIdAndFilenameAndTransferStatus(..., "SUCCESS")` (실패 로그만 있으면 미전송으로 표시)
- 스캔 결과에서 미전송 파일 2개 이상이면 "All Transfer" 버튼 표시 → 순차 전송
- `transferFile(btn, callback)` 콜백 파라미터 추가로 순차 전송 지원
- 모든 동적 POST 폼에 CSRF `_csrf` 토큰 추가 (files/history/index/analyze/progress 6개 페이지) — 403 Forbidden 수정
- 실패 전송 로그 DB 정리 (36건 FAILED → 삭제)

---

## [2026-04-12] SCP 2단계 전송 (Permission Denied 수정) + 임시 경로 설정 + 에러 누적 수정

**변경 파일:** `RemoteDumpService.java`, `HeapDumpConfig.java`, `application.properties`, `ServerController.java`, `settings.html`, `servers.html`

**SCP 2단계 전송:**
- Phase 1: SCP를 sscuser로 실행 → 임시 디렉토리(`/tmp/heapdump_transfer_<UUID>_<filename>`)에 저장
- Phase 2: `Files.move()`로 앱 실행 계정(root) 권한으로 `/opt/heapdumps/dumpfiles/`에 이동
- 모든 에러 경로에서 `cleanupTempFile()` 헬퍼로 임시 파일 정리

**Settings — SCP temp directory 설정 추가:**
- `application.properties`에 `remote.scp.temp-dir=/tmp` 기본값
- Settings 페이지에서 런타임 변경 가능 (입력 + Save)
- API: `GET/POST /api/servers/scp-temp-dir`

**servers.html — 전송 에러 누적 표시 버그 수정:**
- 실패 시 기존 에러 div(`.transfer-err`)를 제거 후 새 에러 추가 → 재시도해도 에러 1개만 표시

---

## [2026-04-12] SSH 로컬 실행 계정 설정 + 상태 커서 수정

**변경 파일:** `application.properties`, `HeapDumpConfig.java`, `RemoteDumpService.java`, `ServerController.java`, `settings.html`, `servers.html`

- `remote.ssh.local-user=sscuser` 설정 추가 — SSH/SCP를 `su - sscuser -c "..."` 로 실행
- 현재 프로세스 계정과 동일하면 su 없이 직접 실행
- Settings 페이지 Analysis Options에 "SSH local user" 입력 필드 추가 (런타임 변경 가능)
- API: `GET/POST /api/servers/ssh-local-user`
- Servers 페이지 상태 뱃지의 `cursor: help` (`?` 커서) 제거

---

## [2026-04-12] 서버 연결 상태 DB 영속화 (테스트/스캔/자동탐지 실패 시 상태 반영)

**변경 파일:** `TargetServer.java`, `RemoteDumpService.java`, `servers.html`

- `target_servers` 테이블에 `conn_status`(OK/FAIL/UNKNOWN), `last_error`, `last_checked_at` 컬럼 추가
- 연결 테스트 성공 → `conn_status=OK`, 실패 → `conn_status=FAIL` + `last_error` 저장
- 수동 스캔 성공 → `OK`, SSH 에러 → `FAIL` + 에러 메시지 저장
- 자동 탐지 성공 → `OK`, 실패 → `FAIL` + 에러 메시지 저장
- Servers 페이지 상태 컬럼: `정상`(초록) / `실패`(빨강, hover 시 에러 메시지 표시) / `미확인`(회색) / `비활성`(회색)
- 테스트/스캔 후 페이지 새로고침 없이 JS로 상태 뱃지 즉시 갱신

---

## [2026-04-12] 스캔 주기 설정 + SSH/SCP 에러 표시

**변경 파일:** `application.properties`, `HeapDumpConfig.java`, `RemoteDumpService.java`, `ServerController.java`, `settings.html`, `servers.html`

**Settings — Remote scan interval 설정 추가:**
- Analysis Options 카드에 스캔 주기 드롭다운 추가 (10초 ~ 1시간)
- `application.properties`에 `remote.scan.interval-sec=60` 기본값
- API: `GET/POST /api/servers/scan-interval` (조회/변경)
- 고정 `@Scheduled(60초)` → 동적 주기로 변경 (10초 체크 루프 + 설정 주기 경과 시 실행)

**Target Servers — SSH/SCP 에러 표시:**
- 수동 스캔 시 SSH 에러 발생하면 빨간색 에러 배너로 상세 메시지 표시 (exit code, stderr)
- SCP 전송 실패 시 버튼 옆에 에러 메시지 인라인 표시
- 자동 탐지 에러: 서버별 마지막 에러를 `lastAutoScanErrors` 맵에 기록
- Servers 페이지 상단에 자동 스캔 에러 배너 (30초마다 갱신)
- `cleanSshError()`: SSH 배너/MOTD 텍스트 제거, 핵심 에러만 추출

---

## [2026-04-12] Settings 페이지 Database 카드 추가

**변경 파일:** `settings.html`, `HeapDumpController.java`

- Settings 페이지에 Database 카드 추가: 접속 상태(Connected/Disconnected 뱃지), Host, Port, Database, Username, Version, History Records 표시
- "설정 변경" 버튼 → 모달에서 DBMS IP, 포트, DB명, 계정, 패스워드 입력 가능
- "연결 테스트" 버튼: 입력한 정보로 실제 DB 연결 테스트 (성공 시 DB 버전 표시)
- "저장" 버튼: application.properties에 AES 암호화된 패스워드로 저장, 재시작 안내
- API: `POST /api/settings/database/test` (연결 테스트), `POST /api/settings/database` (설정 저장)
- `/api/settings` 응답에 `database` 섹션 추가 (host, port, database, username, connected, version, historyCount)

---

## [2026-04-12] MariaDB 연동, 로그인/계정관리, 원격 서버 덤프 탐지 기능 추가

### Phase 1: MariaDB 연동 기초 설정
**변경 파일:** `pom.xml`, `application.properties`, `db/init.sql`
- spring-boot-starter-data-jpa, mariadb-java-client, spring-boot-starter-security, thymeleaf-extras-springsecurity5 의존성 추가
- MariaDB 192.168.56.9:3306/HEAPDB 연결 설정 (heap_user 계정)
- JPA ddl-auto=update 로 테이블 자동 생성

### Phase 2: JPA Entity + Repository
**신규 파일:** `model/entity/User.java`, `model/entity/TargetServer.java`, `model/entity/AnalysisHistoryEntity.java`, `model/entity/DumpTransferLog.java`, `repository/UserRepository.java`, `repository/TargetServerRepository.java`, `repository/AnalysisHistoryRepository.java`, `repository/DumpTransferLogRepository.java`
- 4개 Entity 클래스 (users, target_servers, analysis_history, dump_transfer_log 테이블)
- Spring Data JPA Repository 인터페이스

### Phase 3: Spring Security + 로그인/계정 관리
**신규 파일:** `config/SecurityConfig.java`, `service/CustomUserDetailsService.java`, `service/UserService.java`, `controller/AuthController.java`, `controller/AdminController.java`, `templates/login.html`, `templates/admin/users.html`
- Spring Security 기반 세션 인증 (모든 페이지 인증 필요, /login 공개)
- 기본 관리자 계정: admin / shinhan@10 (BCrypt)
- ADMIN 전용 계정 관리 페이지 (/admin/users): 사용자 추가/수정/삭제/비밀번호 초기화
- CSRF: API 경로(/api/**) 제외, Thymeleaf 페이지는 자동 토큰 삽입

### Phase 4: History DB 마이그레이션
**변경 파일:** `service/HeapDumpAnalyzerService.java`, `controller/HeapDumpController.java`
- 분석 성공/실패 시 analysis_history 테이블에 메타데이터 자동 저장
- buildHistory() DB 기반으로 전환 (DB 우선 조회 + 미분석 파일 폴백)
- 앱 기동 시 기존 result.json 데이터를 DB로 자동 마이그레이션 (1회)
- AnalysisHistoryItem에 serverName 필드 추가

### Phase 5: 원격 서버 덤프 탐지/전송
**신규 파일:** `service/RemoteDumpService.java`, `controller/ServerController.java`, `templates/servers.html`
- SSH/SCP 기반 원격 서버 연결 (sscuser 계정, BatchMode 키 인증)
- 서버 등록/수정/삭제, 연결 테스트, 수동 스캔, 파일 전송 기능
- 자동 탐지: @Scheduled(60초)로 auto_detect=true 서버의 새 덤프 자동 전송
- 전송 이력 dump_transfer_log 기록, 중복 전송 방지 (서버ID + 파일명)

### Phase 6: 배너/네비게이션 업데이트
**변경 파일:** `templates/fragments/banner.html`
- Servers 메뉴 추가 (모든 사용자)
- Admin 메뉴 추가 (ADMIN 역할만 표시, sec:authorize)
- 로그인 사용자명 표시 + Logout 버튼 (CSRF 폼 기반)
- Collapsed icons에 Servers 아이콘 추가

---

## [2026-04-12] Files, History 페이지 테이블 레이아웃 전환

**변경 파일:** `files.html`, `history.html`

**Files 페이지 (`/files`):**
- 기존 `.file-item` flex 리스트 → `<table class="ftable">` 테이블 형식으로 변경
- 컬럼: 상태, 파일명, 크기, 날짜, 작업 (아이콘 버튼)
- 테이블 헤더 고정, hover 행 하이라이트, 640px 이하에서 날짜 컬럼 숨김
- 기존 기능(검색 필터, 삭제/다운로드 모달, GZ 뱃지) 모두 유지

**History 페이지 (`/history`):**
- 기존 `.hi` 카드형 리스트 → `<table class="htable">` 테이블 형식으로 변경
- 컬럼: 상태, 파일명(뱃지 포함), 분석 시간, 힙 사용량, Suspects, 파일 크기, 날짜, 작업
- 640px 이하에서 힙 사용량/Suspects 컬럼 숨김
- 기존 기능(검색 필터, 삭제 모달, 링크) 모두 유지

---

## [2026-04-11] AI 인사이트 패널 UI 개선 및 분석 확인 모달 추가

**변경 파일:** `analyze.html`

**AI 인사이트 패널 레이아웃/폰트 확대:**
- 패널 헤더: 제목 14→17px, 부제 11→13px, 뱃지 10→12px, 버튼 12→13px
- 미분석 상태: 타이틀 17→20px, 설명 13→15px, 태그 11→13px, 시작 버튼 14→16px
- 분석 중 상태: step circle 32→36px, label 10→12px, 메시지 14→16px, 상세 12→14px
- 완료 상태: severity 20→24px, 본문 13.5→15px, 카드 타이틀 11→13px, 메타 10→12px
- 에러 상태: 타이틀 15→18px, 메시지 13→15px, 버튼 13→14px
- 저장 경로 배너: 14px, 코드 13px
- 패널 max-width 960px + 가운데 정렬 (margin: 0 auto)

**AI 분석 확인 모달 추가:**
- `startAiAnalysis()` 호출 시 즉시 실행하지 않고 확인 모달 표시
- 모달에 API 비용 발생 경고, 페이지 이탈 주의사항 안내
- 재분석 시에는 "기존 결과가 새 결과로 대체됩니다" 추가 경고 표시
- 확인 시 `confirmAiAnalysis()` → `_doStartAiAnalysis()`로 실제 분석 진행

**기타:**
- LLM 비활성화 안내 링크를 `/settings` → `/settings/llm`으로 변경
- 에러 상태 Settings 링크도 `/settings/llm`으로 변경

## [2026-04-11] AI / LLM Configuration을 별도 페이지로 분리

Settings 페이지의 AI / LLM Configuration 섹션을 독립 페이지(`/settings/llm`)로 분리.

**변경 파일:**
- `HeapDumpController.java` — `@GetMapping("/settings/llm")` 라우트 추가
- `settings.html` — LLM 카드를 링크 카드로 교체 (상태 뱃지 + 요약 정보 표시), LLM 관련 JS 코드 제거
- `llm-settings.html` — 새 페이지 생성 (Enable, Provider & Model, API Key, Token Limits 카드 구성)

**상세 내역:**
- Settings 페이지에서 LLM 카드 클릭 시 `/settings/llm`으로 이동
- 링크 카드에 ON/OFF 뱃지와 Provider/Model/API Key 상태 요약 표시
- LLM Settings 페이지에 breadcrumb 네비게이션 (Settings > AI / LLM Configuration)
- 배너 네비게이션에서 `/settings/llm` 접근 시 Settings 링크 하이라이트 유지

## [2026-04-11] Genspark LLM 연동 버그 수정

HTTP 404 Not Found 오류 발생하던 Genspark LLM 연동 문제 수정.

**근본 원인:** Genspark API 엔드포인트는 `/chat/completions` 경로가 필요한데,
`getDefaultApiUrl("genspark")`이 빈 문자열을 반환하고 Settings UI에서도 URL 미입력 상태가 유지되어
`base_url`만으로 요청이 전달되었기 때문에 404 발생.
추가로 모델명(`gpt-4o`, `claude-sonnet-4-20250514` 등)이 Genspark 허용 모델 목록에 없어 에러 발생.

[service/HeapDumpAnalyzerService.java]
- `getDefaultApiUrl("genspark")`: `""` → `"https://www.genspark.ai/api/llm_proxy/v1/chat/completions"` 수정
- `GENSPARK_MODELS` 정적 상수 추가 — Genspark 허용 모델 21종 (GPT-5 계열, Claude 4 계열, Kimi, MiniMax)

[controller/HeapDumpController.java]
- `/api/settings` 응답의 `providerModels.genspark`: 빈 목록 → `GENSPARK_MODELS` 목록으로 수정

[templates/settings.html]
- `_defaultUrls.genspark`: `""` → `"https://www.genspark.ai/api/llm_proxy/v1/chat/completions"` 수정
- `_providerModels.genspark`: 드롭다운 목록 추가 (GPT-5/Claude 4/Kimi/MiniMax 21종)
- Genspark 선택 시 안내 박스 표시 (`#gensparkHint`):
  - Base URL / 엔드포인트 / API Key 형식(gsk-...) / 허용 모델 안내
- `loadLlmSettings()`: provider 로드 시 gensparkHint 표시 여부 반영
- `onProviderChange()`: provider 전환 시 gensparkHint 표시/숨김 처리


다중 LLM 프로바이더(Claude, GPT, Genspark, Custom) 지원 AI 분석 기능 추가.
사용자가 명시적으로 "AI 분석 시작" 버튼을 클릭해야만 LLM 호출 발생.

[application.properties]
- `llm.enabled`, `llm.provider`, `llm.api.url`, `llm.model`, `llm.api.key` 등 9개 LLM 설정 속성 추가

[config/HeapDumpConfig.java]
- LLM 관련 `@Value` 필드 9개 + getter 메서드 추가

[service/HeapDumpAnalyzerService.java]
- LLM 런타임 설정 volatile 필드 (provider, apiUrl, model, apiKey 등)
- `setLlmEnabled()`, `setLlmConfig()`, `setLlmApiKey()` — 런타임 설정 변경 + settings.json 영속화
- `getLlmApiKeyMasked()` — API 키 마스킹 (앞 7자 + ... + 뒤 4자)
- `getDefaultApiUrl(provider)` — 프로바이더별 기본 API URL
- `testLlmConnection()` — 프로바이더별 연결 테스트 (Claude: x-api-key, 나머지: Bearer)
- `callLlmAnalysis(prompt)` — 프로바이더별 LLM API 호출 + JSON 응답 파싱
- `extractLlmText(resp)` — Claude/OpenAI 응답 형식 분기 텍스트 추출
- `persistSettings()` / `loadPersistedSettings()` / `syncApplicationProperties()`에 LLM 설정 추가
- 환경변수 `LLM_API_KEY` 우선 적용

[controller/HeapDumpController.java]
- `POST /api/llm/enabled` — LLM 활성화/비활성화
- `POST /api/llm/config` — provider, apiUrl, model, maxTokens 일괄 변경
- `POST /api/llm/apikey` — API 키 저장
- `POST /api/llm/test-connection` — 연결 테스트
- `POST /api/llm/analyze` — AI 분석 요청 (프롬프트 기반)
- `GET /api/settings` 응답에 `llm` 섹션 추가 (enabled, provider, providerModels 등)

[templates/settings.html]
- "AI / LLM Configuration" 카드 추가: LLM 활성화 토글, Provider 드롭다운 (Claude/GPT/Genspark/Custom), API URL, Model 선택/입력, API Key, Max Tokens, Save Config + Test Connection 버튼
- `onProviderChange()` — Provider 변경 시 API URL 자동 채움 + 모델 드롭다운/텍스트 입력 전환
- `saveLlmConfig()`, `saveLlmApiKey()`, `testLlmConnection()` JS 함수

[templates/analyze.html]
- 헤더에 "AI Analyzed" / "AI Not Analyzed" 배지 추가 (파란색/회색 전환)
- 사이드바에 "AI Analysis" 섹션 + "AI 인사이트" 네비게이션 버튼
- `panel-ai-insight` 패널: 4가지 상태 (미분석/분석중/완료/에러)
  - 미분석: dashed 박스 + "AI 분석 시작" 버튼 (LLM 비활성화 시 disabled + 안내문)
  - 분석중: 스피너
  - 완료: 요약/위험도/근본원인/권장조치 카드 4개 + 재분석 버튼
  - 에러: 에러 메시지 + 재시도 버튼
- `startAiAnalysis()`, `collectAnalysisData()`, `buildAnalysisPrompt()` 등 AI 분석 JS 함수

## [2026-04-09] 파일 업로드 중복 감지 시스템

[HeapDumpAnalyzerService.java]
- `checkDuplicate(filename, fileSize, partialHash)` — 기존 파일과 크기+첫 64KB SHA-256 해시로 비교
- `computePartialHash(File, int)` — 일반/GZ 파일 모두 지원하는 부분 해시 계산
- `generateUniqueName(filename, directory)` — `{base}_2.{ext}` 패턴 자동 이름 생성
- `java.security.MessageDigest` import 추가

[HeapDumpController.java]
- `POST /api/upload/check` 엔드포인트 추가 — 요청: `{filename, fileSize, partialHash}`, 응답: `{status, existingFilename, suggestedName}`
- 응답 status: `OK`, `DUPLICATE_CONTENT`, `DUPLICATE_NAME`

[index.html]
- `computePartialHash(file)` — Web Crypto API로 클라이언트 측 첫 64KB SHA-256 해시 계산
- `checkDuplicate(file)` — API 호출 후 결과에 따라 모달 분기
- `showDuplicateContentModal()` — "이미 존재하는 파일과 동일한 내용" 모달 (취소/업로드)
- `showDuplicateNameModal()` — "같은 이름, 다른 내용" 모달 (취소/덮어쓰기/이름변경)
- `startUploadRenamed()` — `FormData.append(file, newName)` 으로 이름 변경 업로드
- `doUpload()` 수정: 디스크 체크 후 `checkDuplicate()` 호출로 변경
- 디스크 경고 모달 "Upload anyway" → `checkDuplicate()` 호출로 변경

## [2026-04-09] 배너 접힌 상태 오버플로우 + 페이지 이동 깜빡임 수정

[fragments/banner.html]
- 접힌 아이콘 스트립: padding 축소(42px→6px), `overflow-y: auto`, `flex: 1`로 뷰포트 내 스크롤 처리
- 아이콘 `flex-shrink: 0`, `min-height: 32px`로 크기 유지
- 페이지 로드 깜빡임 방지: `<style>` 앞에 인라인 `<script>`로 `banner-collapsed` 즉시 적용
- `banner-no-transition` 클래스로 초기 로드 시 transition 비활성화, `requestAnimationFrame` 2프레임 후 복원
- 배너 헤더 하단 border 제거

## [2026-04-09] 배너 헤더/경계 디자인 C안 적용

[fragments/banner.html]
- 배너 `border-right` 제거 → `box-shadow: 1px 0 3px rgba(0,0,0,.06)` 미세한 그림자로 교체
- 배너 헤더 배경: 본체와 동일한 `#F9FAFB`로 통일 (경계선 최소화)
- 헤더 하단 `border-bottom: 1px solid #E5E7EB` 유지 (본체와 구분)
- 페이지 topbar의 `box-shadow`와 배너 그림자가 자연스럽게 연결

## [2026-04-08] 배너 System Status 캐시 + Refresh 버튼

[fragments/banner.html]
- `localStorage` 캐시 도입: 페이지 이동 시 캐시된 데이터로 즉시 렌더링 (깜빡임 방지)
- 자동 갱신 주기 15초 → 60초(1분)로 변경
- 캐시가 60초 미만이면 페이지 로드 시 API 호출 생략
- System Status 타이틀 우측에 Refresh 아이콘 버튼 추가 (회전 애니메이션)
- `fetchBannerStatus()` → `applyBannerStatus()` DOM 업데이트 분리
- `refreshBannerStatus()` 수동 갱신 함수 추가
- JS 변수 `js` → `js_el`로 변경 (예약어 충돌 방지)

## [2026-04-08] 페이지 topbar 로고 제거 + 제목 페이지명으로 변경

[index.html] 로고 제거, 제목 "Heap Dump Analyzer" → "Dashboard", Settings 버튼 제거
[analyze.html] 로고 제거, 제목 "Heap Dump Analyzer" → "Analysis" (filename-badge 유지)
[history.html] 로고 제거, 제목 → "History"
[files.html] 로고 제거, 제목 → "Files"
[settings.html] 로고 제거, 제목 → "Settings"
[compare.html] 로고 제거, 제목 "Heap Dump Compare" → "Compare"
[progress.html] 변경 없음 (이미 로고 없고 "Analyzing" 제목 적절)

## [2026-04-08] 배너 헤더 영역 추가

[fragments/banner.html]
- 배너를 `top: 0`부터 시작하도록 변경 (기존 `top: 52px`)
- 52px 헤더 영역 추가: 앱 로고(파란 아이콘) + "Heap Dump Analyzer" 타이틀 + "v2.0 · MAT CLI" 서브텍스트
- 배너 z-index 90 → 110으로 상향 (페이지 topbar 위에 표시)
- 접힌 상태에서 헤더는 로고 아이콘만 표시
- 토글 버튼 위치 `top: 8px` → `top: 62px`로 헤더 아래로 이동

## [2026-04-08] 좌측 배너 전역 적용 + 접기/펼치기 토글

[fragments/banner.html] (신규)
- 공통 배너 Thymeleaf fragment 생성 — CSS, HTML, JS 자체 포함
- `position: fixed; left: 0; top: 52px` 으로 전 페이지 최좌측 고정
- System Status: MAT CLI 상태, 디스크 사용량 바, JVM 메모리 바, 분석 큐 상태
- Navigation: Dashboard, Files, History, Settings (현재 페이지 하이라이트)
- 접기/펼치기 토글 버튼 (220px ↔ 44px) + `localStorage` 상태 저장
- 접힌 상태: 아이콘 스트립 표시 (MAT 상태 dot + 네비 아이콘)
- `/api/system/status` API에서 15초 간격으로 데이터 갱신
- 900px 이하 모바일에서 자동 숨김

[HeapDumpController.java]
- `GET /api/system/status` 엔드포인트 추가 — MAT CLI, 디스크, JVM, 큐 상태 JSON 반환
- index()의 배너 전용 model attribute 제거 (API로 이전)

[index.html]
- 인라인 배너 HTML/CSS/JS 제거, fragment include로 교체
- grid `300px 220px 1fr` → `300px 1fr` + `margin-left: var(--banner-w)`
- topbar `left: var(--banner-w)` 적용

[analyze.html]
- `.app-header`, `.sidebar`, `.main-content`에 `left/margin-left: var(--banner-w)` 적용
- 768px 이하 모바일에서 리셋

[progress.html, history.html, files.html, settings.html, compare.html]
- 각 페이지 topbar에 `left: var(--banner-w)` 적용
- container에 `padding-left: calc(var(--banner-w) + 20px)` 적용
- 900px 이하 모바일 리셋 미디어쿼리 추가

## [2026-04-08] 메인 홈페이지 좌측 배너 추가

[HeapDumpController.java]
- `index()` 메서드에 MAT CLI 상태 (`matCliReady`, `matCliStatus`), JVM 메모리 (`jvmUsedMb`, `jvmMaxMb`, `jvmUsedPercent`) model attribute 추가

[index.html]
- 레이아웃을 `300px 1fr` → `300px 220px 1fr` 3컬럼 grid로 변경
- 좌측 배너 (`<div class="left-banner">`) 추가: System Status 섹션 + Navigation 섹션
  - MAT CLI 상태: Ready/Not Ready 인디케이터 (초록/빨간 dot)
  - 디스크 사용량: 프로그레스 바 + 퍼센트 (70%/90% 기준 색상 변경)
  - JVM 메모리: 프로그레스 바 + MB 표시
  - 분석 큐 상태: `renderQueuePanel()`과 동기화되는 실시간 표시
  - 네비게이션 링크: Files, History, Settings, Compare (SVG 아이콘)
- 반응형: `@media (max-width: 900px)` 에서 배너 숨김 처리
- `@media (min-width: 1024px)` desktop 스케일링에 배너 폰트 크기 추가
- `updateBannerQueue()` 함수 추가: 큐 polling 결과를 배너 카드에 동기화

## [2026-04-08] Overview KPI 카드 모바일 3x2 그리드 레이아웃

[analyze.html]
- `@media (max-width:480px)`: KPI 바를 `flex-direction:column`(1열) → `grid-template-columns:repeat(3, 1fr)`(3열 2행)로 변경
- 아이콘 26px, 값 13px, 라벨 10px으로 축소하여 3열에 맞게 조정
- 패딩 8px 10px, gap 6px로 밀도 높임
- 3n번째 아이템 `border-right:none`, 마지막 행 `border-bottom:none` 처리

## [2026-04-08] Analysis 모달 모바일 레이아웃 최적화

[analyze.html]
- `@media (max-width:768px)`: 일반 모달(`.modal-box`) 패딩·너비 축소, 아이콘·타이틀·바디·버튼 폰트 크기 축소
- `@media (max-width:768px)`: Component Detail 모달 전용 — 너비 `calc(100vw - 24px)`, 최대높이 88vh, 내부 패딩 축소
- `@media (max-width:480px)`: 일반 모달 패딩 18→16px, 너비 `calc(100vw - 32px)`, border-radius 12px로 축소
- `@media (max-width:480px)`: 모달 아이콘 34px, 타이틀 15px, 바디 12px, 버튼 12px으로 모바일 최적화
- `@media (max-width:480px)`: Component Detail 모달 — 너비 `calc(100vw - 16px)`, 최대높이 90vh, 내부 padding 최소화
- 미사용 `.modal-content` 규칙을 실제 `.modal-box` 및 Component Detail 모달에 맞게 재작성

## [2026-04-07] Overview Treemap/StackedBar 툴팁 오른쪽 넘침 수정

[analyze.html]
- `positionTooltip(tt, e)` 공통 함수 추가: 마우스 위치 기준으로 툴팁 배치 시 화면 경계 체크
  - 오른쪽 넘침 → 마우스 왼쪽으로 표시
  - 하단 넘침 → 마우스 위쪽으로 표시
  - 좌측/상단 최소 8px 여백 보장
- Treemap `mousemove` 핸들러: 인라인 좌표 계산 → `positionTooltip()` 호출로 변경
- Stacked Bar `mousemove` 핸들러: 동일하게 `positionTooltip()` 호출로 변경

## [2026-04-07] 메타 카드 ? 팝오버 모달 경계 기준 위치 보정

[analyze.html]
- 팝오버 수평 위치 계산을 `window.innerWidth` → 모달 박스(`.modal-box`)의 실제 경계(`getBoundingClientRect()`) 기준으로 변경
- 오른쪽 카드의 팝오버가 모달 밖으로 나가지 않도록 `rightBound - popupW - 8` 보정
- 왼쪽 카드도 동일하게 `leftBound + 8` 보정
- 화살표 위치도 팝오버 내부 범위(10px ~ popupW-22px)로 클램핑하여 화살표가 팝오버 밖으로 나가지 않도록 처리

## [2026-04-07] 메타 카드 ? 팝오버 레이아웃 잘림 수정

[analyze.html]
- 팝오버를 `position:absolute` (카드 내부) → `position:fixed` + `document.body` 에 직접 추가로 변경
  - 부모 요소의 `overflow:auto/hidden`에 의해 잘리던 문제 해결
- 버튼 위치 기준 동적 배치: `getBoundingClientRect()`로 정확한 좌표 계산
  - 화면 하단 공간 부족 시 자동으로 위쪽에 표시
  - 좌우 화면 밖으로 나가지 않도록 8px 여백 보정
- 화살표를 CSS `::before` pseudo-element → DOM 요소(`.cd-help-arrow`)로 변경하여 버튼 중심에 정확히 위치
- 모달 본문 스크롤 시 팝오버 자동 닫힘 추가
- `closeComponentDetail()`에서 잔여 팝오버 정리 추가

## [2026-04-07] 메타 카드 ? 버튼 부연설명 팝오버 추가

[analyze.html]
- `META_HELP` 사전 추가: 8개 메타 카드 라벨에 대한 한국어 부연설명
  - 크기, Retained Heap, 클래스 수, 객체 수, 클래스 로더, 힙 점유율, 얕은 힙, 보유 힙
- `cdMetaCard()` 함수 수정: META_HELP에 해당 라벨이 있으면 `?` 버튼 자동 추가
- `toggleMetaHelp()` 함수 추가: 클릭 시 카드 아래 다크 팝오버 토글
  - 같은 카드 재클릭 시 닫기, 다른 카드 클릭 시 이전 것 닫고 새 것 열기
  - 외부 클릭 시 자동 닫힘
- `escAttr()` 헬퍼 함수 추가 (HTML 속성 이스케이프)
- CSS 추가: `.cd-help-btn` (15px 원형 버튼, hover 시 파란색), `.cd-help-popup` (다크 테마 팝오버, 화살표, 애니메이션)
- Top Consumers 상세 모달 + Histogram 폴백 모달 모두 동일하게 적용

## [2026-04-07] Top Consumers 모달 힙 점유율 정확도 수정 및 Retained Heap 표시

[analyze.html]
- 힙 점유율 데이터 소스 수정: `metadata.sizeBytes` (컴포넌트 전체 크기) → Top Consumers 테이블의 `data-pct` (MAT dominator 분석 기준) 우선 사용
  - 기존: metadata의 Size (클래스 로더가 로드한 전체 객체 크기) → 실제보다 과대 표시
  - 수정: Top Consumers 테이블의 퍼센트 (MAT가 계산한 정확한 retained heap 비율)
  - Top Consumers에 없는 클래스는 metadata.sizeBytes로 폴백
- `findClassInTopConsumers()` 확장: `pct`, `size` 필드 추가 반환
- 메타 카드에 Retained Heap 항목 추가 (Top Consumers의 실제 retained heap 바이트 값)

## [2026-04-07] Top Consumers 상세 모달 — 부연설명, 요약, 연관 정보 추가

[analyze.html]
- `renderParsedDetail()` 대폭 개선:
  - **클래스 부연설명**: `CLASS_DESCRIPTIONS` 사전 연동 — 해당 클래스에 대한 설명 카드 표시
  - **힙 점유율 카드 + 바**: metadata.sizeBytes / TOTAL_BYTES 기반 퍼센트 계산 및 시각화
  - **분석 요약 카드**: 힙 점유율 평가, 경고/오류 건수, Leak Suspect 연관, Histogram 순위, 객체 크기 분석 자동 생성
  - **Leak Suspect 링크**: 관련 suspect 발견 시 클릭으로 해당 suspect로 직접 이동
- `countSectionSeverities()` 함수 추가: 섹션 트리 재귀 탐색하여 warning/error 수 집계
- `generateAnalysisSummary()` 함수 추가: 다양한 지표 기반 분석 포인트 자동 생성
  - 힙 점유율 30%↑ 경고, 10%↑ 주의, 그 외 정상
  - 오류/경고 감지 알림
  - Leak Suspect 언급 감지
  - Histogram 순위/객체 수/Shallow Heap 정보
  - 객체당 평균 크기 분석 (1MB↑ 대형 객체 경고, 10만↑ 다수 객체 경고)
- 요약 카드 색상: 오류(빨강), 경고/고점유율(노랑), 정상(초록) 자동 구분

## [2026-04-07] Histogram 클래스 부연설명 사전 추가

[analyze.html]
- `CLASS_DESCRIPTIONS` 사전 추가: 20개 주요 클래스에 대한 한국어 부연설명
  - 원시 배열: `byte[]`, `char[]`, `int[]`, `long[]`, `short[]`, `boolean[]`, `double[]`, `float[]`
  - 문자열/기본: `java.lang.String`, `java.lang.Object[]`, `java.lang.Class`
  - 컬렉션: `ArrayList`, `HashMap`, `HashMap$Node`, `LinkedHashMap$Entry`, `ConcurrentHashMap$Node`, `TreeMap$Entry`
  - 참조: `WeakReference`, `SoftReference`, `Finalizer`
- 각 클래스에 아이콘, 설명(`desc`), 상세 조언(`detail`) 포함
- `renderHistogramFallback()`에서 해당 클래스일 때 파란색 설명 카드 표시
- Java 버전별 차이(예: Java 9+ Compact Strings), 성능 최적화 팁 포함

## [2026-04-07] Histogram 클래스 클릭 시 상세 정보 모달 표시

[analyze.html]
- Histogram 테이블 행에 클릭 핸들러 추가: 클릭 시 `showComponentDetail()` 호출
- 행에 `data-retained`, `data-shallow`, `data-objects` 속성 추가 (원본 바이트값)
- `findClassInHistogram()` 확장: `retainedBytes`, `shallowBytes`, `objectCount` 원본 값 반환
- `renderHistogramFallback()` 대폭 개선:
  - 순위 뱃지 표시 (Histogram #N)
  - 힙 점유율 계산 및 퍼센트 바 시각화 (30%↑ 빨강, 10%↑ 노랑, 그 외 파랑)
  - 관련 Leak Suspect 자동 검색 및 링크 (클릭 시 해당 suspect로 이동)
  - Top Consumers 연관 검색: 해당 클래스가 Top Consumers에 있으면 상세 분석 링크 표시
- `findRelatedLeakSuspects()` 헬퍼 함수 추가: 클래스명(전체/단순)으로 suspect 검색
- `findClassInTopConsumers()` 헬퍼 함수 추가: Top Consumers 테이블에서 클래스 검색
- 동작 흐름: Top Components에 있는 클래스 → 풍부한 상세 표시, 없는 클래스 → 개선된 Histogram 폴백 표시

## [2026-04-07] Histogram Retained/Shallow Heap 값 사람이 읽기 쉬운 형식으로 개선

[HistogramEntry.java]
- `getRetainedHeapHuman()` 메서드 추가: `>= 1,020,644,584` → `≥ 973.4 MB` 형식으로 변환
- `getShallowHeapHuman()` 메서드 추가: 바이트 값을 KB/MB/GB 단위로 자동 변환
- `formatBytes()` 헬퍼 메서드 추가 (B/KB/MB/GB 자동 단위 선택)

[analyze.html]
- Histogram 테이블의 Shallow Heap, Retained Heap 컬럼을 human-readable 형식으로 표시
- 정확한 바이트 값은 마우스 hover 시 툴팁으로 표시

## [2026-04-07] Top Consumers 컴포넌트 상세 모달 한국어 번역

[analyze.html]
- 컴포넌트 상세 모달의 모든 UI 텍스트를 한국어로 번역:
  - 모달 제목: "Component Detail" → "컴포넌트 상세"
  - 탭: "Analysis" → "분석 결과", "Raw Data" → "원본 데이터"
  - 버튼: "Close" → "닫기"
  - 로딩/에러 메시지 한국어화
- MAT 영문 콘텐츠 한국어 번역 시스템 추가 (프론트엔드 JS):
  - `MAT_TITLE_MAP`: 섹션 제목 번역 사전 (Miscellaneous→기타 분석, Map Collision Ratios→맵 충돌 비율 등 30+항목)
  - `MAT_HEADER_MAP`: 테이블 헤더 번역 사전 (Class Name→클래스명, Shallow Heap→얕은 힙 등 25+항목)
  - `MAT_TEXT_PATTERNS`: 정규식 기반 본문 텍스트 번역 패턴 (인스턴스 보유 바이트, 충돌 비율 감지, 메모리 축적 등 15+패턴)
  - `trTitle()`, `trHeader()`, `trText()` 번역 함수
- 메타데이터 카드 라벨 한국어화: Size→크기, Classes→클래스 수, Objects→객체 수, Class Loader→클래스 로더
- 뱃지 텍스트 한국어화: Warning→경고, Error→오류
- 테이블 하단 "more rows" 메시지 한국어화
- HTML 엔티티(`&gt;`, `&#61;` 등) 디코딩 처리 추가 (`decodeHtmlEntities()`)
- `MAT_SENTENCE_PATTERNS` 추가: 전체 문장 단위 번역 패턴 (참조 통계, Finalizer, 빈 컬렉션, 채움 비율 등 20+패턴)
  - Soft/Weak/Phantom Reference Statistics 본문 전체 번역
  - Finalizer Statistics 본문 번역
  - "No excessive usage of ..." 계열 문구 전체 번역
  - "No serious amount of ..." 계열 문구 전체 번역
  - Duplicate Strings "Found N occurrences of char[] with at least M instances..." 번역
  - "N × value (bytes)" 리스트 항목 번역 (bytes→바이트)
  - `&#34;` (큰따옴표) HTML 엔티티 디코딩 추가

## [2026-04-07] Raw Data Suspects 상세 페이지 클래스명 링크 활성화

[analyze.html]
- Suspects Details iframe 내 클래스명(FQCN) 링크 클릭 시 컴포넌트 상세 모달 열리도록 구현
- `activateClassLinksInIframe()` 함수 추가:
  - `javascript:void(0)` 링크 중 FQCN 패턴 매칭하여 활성화
  - 비활성 스타일(opacity:0.4, cursor:not-allowed) → 클릭 가능 스타일(파란색 밑줄)로 변경
  - 클릭 시 부모 window의 `showComponentDetail(className)` 호출
  - 중복 바인딩 방지 (`_classLinkActivated` 플래그)
- iframe 내부 네비게이션(서브 페이지 이동) 후에도 자동 재활성화 (load 이벤트 재바인딩)
- "First 10 of N objects" 등 비-클래스명 링크는 무시
- Top Components에 없는 클래스 클릭 시 Histogram 폴백 표시:
  - `findClassInHistogram()`: Histogram 테이블에서 클래스명으로 객체 수/Shallow Heap/Retained Heap 검색
  - `renderHistogramFallback()`: 검색된 기본 정보를 메타데이터 카드로 표시 + 안내 메시지
  - Histogram에도 없는 경우 기존 에러 메시지 유지
  - `formatBytesHuman()`: 바이트 수를 GB/MB/KB/B 단위로 자동 변환, `>=` 접두사를 `≥`로 표시
  - 클래스명을 별도 full-width 행으로 분리하여 긴 FQCN이 레이아웃을 침범하지 않도록 `word-break:break-all` 적용

## [2026-03-20] 메인 페이지 대시보드 레이아웃 리팩토링

[index.html]
- 대시보드 상단 레이아웃을 2컬럼 구조로 변경:
  - 왼쪽: FILES, DISK, ANALYZED, SUSPECTS 스탯 카드 2×2 그리드
  - 오른쪽: Analysis completed 프로그레스 바 + Analysis Queue 패널 세로 스택
- 기존 가로 일렬 stats-bar를 개별 stat-card 컴포넌트로 분리
- 모바일(≤900px): 1컬럼으로 폴백, 스탯 카드 2열 유지
- 모바일(≤480px): 스탯 카드 2열 유지

- Settings 모달 전면 폰트/스타일 개선:
  - General/System/API 탭 간 폰트 크기 통일: s-key 13px, s-val 13px, s-status 13px
  - s-val에서 모노스페이스 제거 → 경로(s-val-path)와 수치(s-val-mono)에만 선별 적용
  - 새 클래스 s-val-mono 추가: 숫자/버전/메모리 값에 모노스페이스 12px 적용
  - s-key에 font-weight:500 추가로 라벨 가독성 향상
  - s-group-title에 하단 구분선(border-bottom) 추가
  - s-api-method, s-api-path, s-copy-btn에 vertical-align:middle 정렬
  - 데스크톱(≥1024px) 미디어 쿼리에 Settings 폰트 스케일링 추가

## [2026-03-20] 메인 페이지 큐 상태 패널 + Settings 모달 크기 고정

[index.html]
- 메인 페이지에 Analysis Queue 패널 추가: 현재 분석 중인 파일과 대기 수를 실시간 표시
- 큐 활성 시 5초 간격 자동 폴링, 큐 비활성 시 패널 자동 숨김 + 폴링 중지
- Refresh 버튼으로 수동 새로고침 가능 (회전 애니메이션)
- Settings 모달에 height: 520px 고정하여 탭 전환 시 크기 변동 방지

## [2026-03-20] 분석 동시 실행 제한 — 큐 기반 순차 처리

[HeapDumpAnalyzerService.java]
- Semaphore(1) 기반 동시 분석 제한: 한 번에 1개 파일만 MAT CLI 분석 실행
- 추가 분석 요청은 자동으로 큐에 적재, 순서대로 처리
- 큐 대기 중 3초 간격으로 SSE를 통해 대기 상태(순서, 현재 분석 파일) 전송
- 클라이언트 연결 해제/인터럽트 시 큐에서도 안전하게 제거
- getQueueSize(), getCurrentAnalysisFilename() API 메서드 추가

[HeapDumpController.java]
- GET /api/queue/status 엔드포인트 추가 (큐 크기, 현재 분석 중인 파일 조회)

[AnalysisProgress.java]
- queuePosition, currentAnalysis 필드 추가
- queued() 팩토리 메서드에 큐 위치/현재 분석 파일 파라미터 추가

[progress.html]
- QUEUED 상태 처리: 큐 대기 배너 UI 추가 (보라색 그라디언트, 대기 순서/현재 분석 파일 표시)
- 큐 대기 → 분석 시작 전환 시 배너 자동 숨김
- 데스크톱/모바일 반응형 스타일 적용

## [2026-03-20] Analysis Files 페이지 다운로드 모달 추가

[files.html]
- 파일 다운로드 버튼 클릭 시 확인 모달 표시 (파일명, 파일 크기 정보 포함)
- index.html의 다운로드 모달 패턴과 동일한 UX 적용
- 다운로드 버튼 스타일(.btn-download) 추가

## [2026-03-20] Heap 데이터 없는 분석 결과를 ERROR(failed)로 표시

[HeapDumpAnalyzerService.java]
- 분석 완료 후 Heap 데이터가 없으면 (totalHeapSize=0, usedHeapSize=0) ERROR 상태로 저장
- SSE에 error 이벤트 전송 → progress.html에서 에러 배너 표시
- 디스크 복원 시 ERROR 상태 결과도 캐시에 로드 (기존에는 SUCCESS만 로드)
- 기존에 SUCCESS로 저장된 Heap 데이터 없는 결과를 자동으로 ERROR로 보정 + result.json 업데이트

[index.html]
- Analysis Files: ERROR 상태 파일에 "failed" 배지 표시 (클릭 시 재분석 페이지 이동)
- Recent Analysis: ERROR 항목에 "failed" 배지 + 빨간색 파일명 (클릭 시 재분석)

[files.html]
- ERROR 상태 파일에도 Analyze(Re-Analyze) 버튼 표시

## [2026-03-20] 메인페이지 레이아웃 개선: Analysis completed + Recent Analysis 패널

[index.html]
- "Analysis coverage" 프로그레스 바 라벨을 "Analysis completed"로 변경
- 메인 영역 2열 그리드 활용: 좌측 Analysis Files, 우측 Recent Analysis 패널 배치
- Recent Analysis 패널: 분석 수행 이력(성공/에러) 최근 10건 표시
  - 파일명(클릭 시 결과 페이지 이동), 상태 배지, 소요 시간, Heap 사용량, Suspect 수, 날짜
  - 데스크탑/모바일 반응형 스타일 적용

[HeapDumpController.java]
- AnalysisHistoryItem에 analysisTime, formattedAnalysisTime, heapUsed 필드 추가
- buildHistory()에서 분석 시간/Heap 사용량 데이터 설정
- formatDuration() 유틸 메서드 추가 (ms → 사람이 읽기 쉬운 형태)
- recentAnalyses 모델 속성 추가 (성공+에러 이력, 최대 10건)

## [2026-03-20] Analysis Files 페이지에 다운로드/삭제 기능 추가

[files.html]
- 각 파일 항목에 다운로드/삭제 SVG 아이콘 버튼 추가 (index.html Recent Files와 동일한 스타일)
  - View(눈 아이콘, 녹색), Analyze(돋보기, 파란색), Download(화살표), Delete(휴지통, hover 빨간색)
- 삭제 확인 모달 (파일명 표시, 취소/삭제 버튼, 배경 클릭으로 닫기)
- Thymeleaf 보안 정책 대응: th:onclick → th:data-filename + onclick 방식으로 변경

[HeapDumpController.java]
- deleteFile() 메서드에 Referer 헤더 확인 추가 — /files 페이지에서 삭제 시 /files로 리다이렉트

## [2026-03-20] 사이드바 Files → Recent Files 이름 변경, 상단 Compare dumps 버튼 제거

[index.html]
- 좌측 사이드바 "Files" → "Recent Files"로 이름 변경
- 상단 topbar에서 "Compare dumps" 버튼 제거 (Quick actions 및 사이드바 Compare 섹션으로 접근 가능)

## [2026-03-20] View all 링크 상시 표시 + stats-bar 모바일 2x2 레이아웃

[index.html]
- "View all N files →" 링크: 5개 초과 조건 제거, 항상 표시
- stats-bar 모바일(≤768px): 2x2 그리드 레이아웃 (위 2개 + 아래 2개)
- stats-bar 초소형(≤480px): 기존 세로 1열 → 2x2 유지, 아이콘/폰트 축소

## [2026-03-20] Analysis Files 전체 목록 페이지 추가

[HeapDumpController.java]
- GET /files 엔드포인트 추가 — 전체 파일 목록 페이지
- 메인 페이지: analysisHistory를 최대 5개만 표시, hasMoreFiles/totalFileCount 모델 속성 추가

[files.html] (신규)
- 전체 파일 목록 전용 페이지
- 검색(필터) 기능 포함 — 파일명 실시간 필터링
- 상단에 파일 수, 분석 완료 수, 전체 용량 표시
- 상태별 아이콘 + view/error/analyze 배지
- 반응형 대응 (모바일에서 날짜 컬럼 숨김)

[index.html]
- Analysis Files 패널에 "View all N files →" 링크 추가 (5개 초과 시 표시)

## [2026-03-20] 메인페이지 패널 통합 (Files overview 제거)

[index.html]
- "Files overview" 패널 삭제 — "Analysis history"와 역할 중복
- "Analysis history" → "Analysis Files"로 이름 변경
- Analysis Files 패널을 full-width로 변경 (grid2 내 full 클래스 적용)
- pending 상태 파일에 "analyze" 링크 버튼 추가 (기존 Files overview의 Analyze 기능 통합)
- .hb-analyze 스타일 추가 (파란색 배지)
- "New" 배지 제거
- Export 모달 텍스트 "Analysis history" → "Analysis files"로 변경

## [2026-03-20] 메인페이지 Stats 레이아웃 개선

[index.html]
- Stats 영역: 4개 개별 카드(stats-row) → 아이콘 포함 가로 1줄 바(stats-bar)로 변경
  · 파일/디스크/체크/경고 SVG 아이콘 + 컬러 배경 추가
  · Analyzed 항목에 "분석완료 / 전체" 형식 표시 (예: 3 / 5)
  · 세로 공간 약 60% 절약
- Analysis coverage 프로그레스 바 추가
  · 전체 파일 대비 분석 완료율을 시각적으로 표시
  · 파란색→초록색 그라데이션
- 반응형: 태블릿(≤900px) 2x2 그리드, 모바일(≤480px) 세로 1열 스택

## [2026-03-20] 분석 화면(analyze.html) 레이아웃 개선

[analyze.html]
- KPI 영역: 6개 개별 카드(kpi-grid) → 아이콘 포함 가로 1줄 바(kpi-bar)로 통합
  · 각 항목에 컬러 배경 아이콘(차트/삼각형/퍼센트/톱니/경고 등)
  · label을 값 아래로 이동하여 시선 흐름 개선
  · 세로 공간 약 60% 절약
- 차트 영역 최적화
  · 차트 최대 높이 320px → 260px
  · 카드 padding 24px → 16px, margin 20px → 14px
  · Stacked Bar 높이 36px → 28px
  · Treemap 높이 200px → 160px
  · 전체 컨텐츠 padding 24px → 16px/20px
- 반응형: 태블릿(≤768px) KPI 2열 래핑, 모바일(≤480px) 1열 세로 스택

## [2026-03-20] API/Settings 기능 고도화

[HeapDumpController.java]
- GET /api/settings 응답 확장:
  · system: Java 버전/벤더, OS, CPU 코어, JVM 메모리(max/total/used/free)
  · disk: 전체/사용/여유 공간, 사용률(%)
  · mat: 파일 존재, 실행 가능, 읽기 가능, ready 상태, statusMessage
  · files: 총 파일 수, 총 크기, 분석 완료 수

[index.html]
- Quick Actions의 "API / Settings" 버튼: window.open(raw JSON) → 3탭 Settings 모달
  · General 탭: MAT CLI 경로/상태(Ready/Not found), Keep Unreachable 설정,
    저장소 경로, 캐시 수, 디스크 사용량 프로그레스바
  · System 탭: Java 버전/벤더, OS, CPU 코어, JVM 메모리 사용량 프로그레스바
  · API 탭: 6개 API 엔드포인트 카드(GET/POST 뱃지, 설명, Copy 버튼), Raw JSON 링크
- Refresh 버튼으로 서버에서 최신 데이터 재조회
- 디스크/JVM 사용률 바에 임계값별 색상 변경 (파랑→주황→빨강)
- 반응형: 모바일/태블릿 대응

## [2026-03-20] MAT CLI 시작 로그 및 에러 메시지 정비

[HeapDumpConfig.java]
- System.out.println → SLF4J Logger 전환
- 시각적 박스 형태로 MAT CLI 상태를 시작 로그에 출력 (READY / NOT FOUND)
- 5단계 MAT CLI 검증 로직:
  1. 파일 존재 여부
  2. 일반 파일 여부 (디렉토리가 아닌지)
  3. 읽기 권한
  4. 실행 권한 (자동 부여 시도)
  5. 파일 크기 (0바이트 아닌지)
- 각 단계 실패 시 한국어 해결 방법 안내 (설치 URL, chmod 명령어 등)
- 힙 덤프 디렉토리 쓰기 권한 검증, 디스크 여유 공간 경고 (500MB 미만)
- isMatCliReady() / getMatCliStatusMessage() 메서드 추가

[HeapDumpAnalyzerService.java]
- 분석 시작 전 config.isMatCliReady() 사전 검증
  · 실패 시 사용자에게 "MAT CLI를 사용할 수 없습니다" 에러 + 상세 원인 전달
- MAT CLI 타임아웃 에러 메시지 한국어 개선 (원인 힌트 포함)
- extractMatErrorHint() 메서드 추가:
  · OutOfMemoryError → Xmx 값 증가 안내
  · SnapshotException → 파일 손상/형식 오류 안내
  · Permission denied → 권한 확인 안내
  · No space left → 디스크 정리 안내
  · 기타 Exception/Error → 마지막 에러 라인 추출
- MAT CLI exit code != 0 시 상세 에러 로깅 (WARN → ERROR 레벨)

## [2026-03-22] 분석 완료 후 .index/.threads 파일을 결과 디렉토리로 이동

[HeapDumpAnalyzerService.java]
- moveArtifactsToResultDir() 추가: 분석 완료 후 .index/.threads 파일을 결과 디렉토리로 이동
- migrateStrayArtifacts() 추가: 서버 시작 시 상위 디렉토리에 남은 기존 파일 마이그레이션
- loadThreadStacksText(): 결과 디렉토리 우선 탐색 후 상위 디렉토리 fallback

────────────────────────────────────────────────────────────────────────────────

## [2026-03-22] 에러 페이지 MAT Log 항상 표시 + 분석 실패 일자/상세 정보 추가

[HeapDumpController.java]
- 에러 시 errorDate(실패 일시), errorAnalysisTime(소요 시간), errorFileSize(파일 크기) 모델에 추가

[analyze.html — ERROR STATE]
- 분석 실패 상세 정보 표시: Failed at / Duration / File Size
- MAT Log 카드를 항상 표시 (로그가 없으면 "MAT CLI 로그가 없습니다" 안내 메시지)
- 기존 th:if 조건 제거하여 MAT Log 영역이 항상 보이도록 변경

## [2026-03-22] 분석 실패 화면에 Re-Analyze 버튼 및 전체 MAT Log 보기 기능 추가

[HeapDumpController.java]
- 에러 상태 시 matLogTotalLen에 실제 로그 길이 전달 (기존: 0 고정)

[analyze.html — ERROR STATE]
- 에러 카드에 Re-Analyze 버튼 추가 (/analyze/rerun/{filename}으로 이동)
- MAT CLI Log를 별도 카드로 분리 (기존: <details> 접기)
- 로그가 5000자 초과 시 "Load Full Log" 버튼으로 전체 로그 비동기 로딩
- 헤더에도 에러 상태에서 filename badge + Re-Analyze 버튼 표시

## [2026-03-22] 분석 실패 결과 페이지에서도 MAT Log 접근 보장

[HeapDumpAnalyzerService.java]
- 분석 exception 시 에러 결과에 mat.log 파일 내용 포함 (기존: matLog 미설정)
- getCachedResult에서 ERROR 결과도 디스크에서 복원 (기존: SUCCESS만 복원)
  → 서버 재시작 후에도 에러 결과의 MAT Log를 /analyze/log/ API로 접근 가능

## [2026-03-22] 분석 실패 파일 클릭 시 실패 결과 페이지로 이동

[index.html — 사이드바 Recent Files]
- ERROR 파일 빨간 버튼: /analyze/{f} → /analyze/result/{f} 변경 (실패 결과 페이지로 이동)

[index.html — Analysis History]
- "failed" 배지: /analyze/{f} → /analyze/result/{f} 변경

[files.html — Analysis Files]
- ERROR/NOT_ANALYZED 공용 버튼을 분리:
  - ERROR: /analyze/result/{f}로 이동하는 빨간 버튼 (.fb.e)
  - NOT_ANALYZED: /analyze/{f}로 이동하는 파란 버튼 (.fb.p)
- .fb.e 스타일 추가 (빨간 배경 #EF4444)

## [2026-03-22] 분석 실패 결과 영속화 (파일 삭제 전까지 유지)

[HeapDumpAnalyzerService.java]
- 분석 실패(Exception) 시 ERROR 상태의 HeapAnalysisResult를 생성하여 memCache + 디스크(result.json)에 저장
  - 기존: catch 블록에서 SSE 에러 메시지만 전송, 실패 정보 유실
  - 수정: 실패 파일도 tmp→final 이동 후 ERROR 결과 저장, 파일 삭제 시 함께 정리
- cloneWithoutLog()에 errorMessage 필드 복사 누락 수정
  - 기존: 디스크 저장 시 errorMessage가 null로 저장됨
  - 수정: errorMessage도 정상 복사되어 서버 재시작 후에도 에러 메시지 유지

## [2026-03-22] 메인 페이지 사이드바 분석 실패 파일 빨간색 표시

[HeapDumpController.java]
- `errorFiles` Set 추가: ERROR 상태 파일명을 템플릿에 전달

[index.html — 사이드바 Recent Files]
- ERROR 파일: 행 전체 빨간 배경/테두리 (`.file-err`), 파일명 빨간색, ext-badge 빨간색
- ERROR 파일: 빨간 Re-Analyze 버튼 (`.fb.e`) — 기존 파란 Analyze 버튼 대신 표시
- Tooltip Status: Analyzed / Failed / Not analyzed 3단계 구분
- 미분석 Analyze 버튼: analyzedFiles와 errorFiles 모두 아닌 경우에만 표시

[index.html — Analysis History 패널]
- ERROR 상태 파일명에 `.hname-err` 빨간색 적용

[files.html — Analysis Files 목록]
- ERROR 상태 파일명에 `.fname-err` 빨간색 적용

## [2026-03-22] 잘못된 파일 분석 시 성공으로 표시되는 버그 수정

[HeapDumpAnalyzerService.java]
- MAT CLI가 0이 아닌 exit code로 종료 시 즉시 RuntimeException 발생하도록 수정
  - 기존: exit code만 로그하고 파싱 단계로 계속 진행 → 다른 파일 결과를 매칭하여 성공으로 표시
  - 수정: 에러 메시지와 함께 예외 발생 → catch 블록에서 분석 실패 메시지를 클라이언트에 전송

[MatReportParser.java]
- findZipFile()에서 baseName 없이 keyword만으로 ZIP을 매칭하는 fallback 로직 제거
  - 기존: base 이름 불일치해도 keyword(overview, suspects 등)만 일치하면 다른 파일의 ZIP 반환
  - 수정: 반드시 baseName + keyword가 모두 일치하는 ZIP만 매칭

## [2026-03-23] MAT CLI 리포트별 진행률 개별 표시 기능

▶ 대상 파일:
  - src/main/java/com/heapdump/analyzer/model/AnalysisProgress.java
  - src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java
  - src/main/resources/templates/progress.html

▶ 변경 내역:
  1. AnalysisProgress 모델에 reportPhase 필드 추가 (overview, top_components, suspects)
     - reportLog() 팩토리 메서드 추가: 리포트 단계 정보 포함 SSE 이벤트 전송
  2. HeapDumpAnalyzerService - MAT CLI 출력 실시간 파싱으로 리포트 단계 감지
     - MAT CLI 출력에서 "org.eclipse.mat.api:overview/top_components/suspects" 문자열 감지
     - 단계별 진행률 범위 설정: init(15-40%), overview(40-55%), top_components(55-68%), suspects(68-80%)
     - 명령어 실행 순서 변경: overview → top_components → suspects (UI 흐름에 맞게)
  3. progress.html - 3개 리포트 하위 단계(sub-step) UI 추가
     - Overview 리포트 (📊), Top Components 리포트 (🔝), Leak Suspects 리포트 (🕵️)
     - 하위 단계 CSS 스타일 (들여쓰기, 작은 아이콘, 축소 폰트)
     - SSE 이벤트의 reportPhase 필드 기반 단계별 아이콘 상태 전환 (waiting → active → done)

────────────────────────────────────────────────────────────────────────────────

## [2026-03-25] 분석 진행 페이지 2열 레이아웃 (진행 카드 + MAT CLI 로그)

▶ 대상 파일:
  - src/main/resources/templates/progress.html

▶ 변경 내역:
  1. 진행 카드(좌) + MAT CLI 로그(우) 2열 그리드 레이아웃(progress-grid) 적용
     - max-width 780px → 1200px 확대
     - 데스크톱(>900px): 로그 콘솔 항상 표시, 토글 버튼 숨김
     - 로그 콘솔이 진행 카드 높이에 맞춰 flex 확장
  2. 태블릿/모바일(≤900px): 1열 폴백, 기존처럼 토글 버튼으로 로그 열기/닫기
  3. 화면 크기 변경 시 logVisible 상태 자동 동기화

## [2026-03-25] 분석 진행 페이지(progress.html) 전면 개편

▶ 대상 파일:
  - src/main/resources/templates/progress.html

▶ 스타일 개편:
  1. 헤더 영역 통합 — 기존 큰 <header> + 파일 배너를 52px 고정 상단바(progress-topbar)로 전환
     - 파일명을 pill-chip 형태로 컴팩트하게 표시
     - 경과 시간(elapsed-chip) 모노스페이스로 표시
  2. 진행바 + 단계 리스트를 하나의 통합 카드(progress-card)로 합침
  3. 타임라인 스타일 단계 리스트 — 수직 연결선(timeline-line)으로 단계 시각 연결
     - 이모지 아이콘 → 숫자 표시, 완료 시 ✓ 체크 애니메이션(scaleIn)
     - 완료된 단계의 연결선 초록색으로 변경
  4. 에러 배너 — 그라디언트에서 카드 스타일(border-left: 4px solid red)로 변경
     - 실패 단계명 표시, 재분석/홈 버튼 추가
  5. 진행바 높이 18px → 12px로 슬림화
  6. 반응형 개선 — 600px/480px 브레이크포인트 추가, 모바일 터치 타겟 44px 보장

▶ 기능 추가:
  1. 경과 시간 표시 — 상단바에 MM:SS 형식 실시간 타이머 (RUNNING 첫 수신 시 시작)
  2. 단계별 소요 시간 — 각 단계 완료 시 소요 시간 표시 (예: "2.3s")
  3. 페이지 타이틀 진행률 — 브라우저 탭에 "(45%) 분석 중..." 형식으로 표시
  4. 취소 버튼 — SSE 닫기 + 확인 다이얼로그 후 홈 이동
  5. 에러 시 재분석 버튼 — /analyze/rerun/{filename} 링크 활용
  6. 로그 라인 수 표시 — 로그 헤더에 "N lines" 실시간 카운트

▶ 버그 수정:
  1. 로그 자동 스크롤 로직 반전 수정
     - 기존: autoScroll = scrollTop > 50 (맨 위에서 auto-scroll 꺼짐 역설)
     - 수정: scrollHeight - scrollTop - clientHeight < 60 (바닥 근처 감지)
  2. 로그 라인 카운트(lines) 리셋 버그 수정
     - 기존: DOM 트리밍 시 logLineCount 감소 → UI 카운트도 감소 후 재누적
     - 수정: 총 수신 수(logTotalCount) 별도 분리, UI에는 감소하지 않는 총 수 표시

────────────────────────────────────────────────────────────────────────────────

## [2026-03-27] 모바일 홈페이지 가로 스크롤 오버플로우 수정

[index.html]
- html, body에 overflow-x: hidden 추가하여 가로 스크롤 방지
- 900px 이하: main-content에 max-width: 100vw 적용, fname-tooltip 비활성화
- 480px 이하: dashboard-left를 1열 레이아웃으로 변경 (stat-card 가로 넘침 방지)
- 480px 이하: ra-meta에 flex-wrap 적용 (분석 메타정보 줄바꿈)
- 480px 이하: history-item에 flex-wrap 적용 (파일명이 한 줄 전체 사용)
- 480px 이하: main-content 패딩 20px → 12px 축소

## [2026-03-27] 모바일 대시보드 통계 카드 2x2 그리드 레이아웃 적용

[index.html]
- 480px 이하 모바일에서 FILES, DISK, ANALYZED, SUSPECTS 통계 카드가 1열(1fr)로
  세로 배열되던 것을 2x2 그리드(1fr 1fr)로 변경

## [2026-03-27] 토스트 메시지 위치를 상단 중앙으로 변경

[index.html]
- toast() 함수의 위치를 우측하단(bottom:20px;right:20px)에서 상단 중앙(top:20px;left:50%;transform:translateX(-50%))으로 변경
- box-shadow 추가로 가시성 향상

## [2026-03-27] 모바일 topbar에 Settings 버튼 추가

[index.html]
- 모바일(≤900px)에서 Quick actions까지 스크롤하지 않아도 Settings 모달에 접근할 수 있도록
  topbar 우측에 Settings 버튼 추가 (데스크탑에서는 숨김)
- 톱니바퀴 SVG 아이콘 포함

## [2026-03-27] 파일 업로드 시 디스크 사용량 90% 이상 경고

[HeapDumpController.java]
- 업로드 성공 후 파일시스템 사용량 체크, 90% 이상이면 warning flash attribute로 경고 메시지 표시

[index.html]
- .alert-warning 스타일 추가 (노란색 배경, 주황색 테두리)
- warning flash attribute 표시 영역 추가

## [2026-03-27] 파일 업로드 전 디스크 사용량 사전 체크 추가

[HeapDumpController.java]
- GET /api/disk/check API 추가: 디스크 사용률, 전체/가용 용량, 90% 이상 warning 플래그 반환

[index.html]
- doUpload()에서 업로드 전 /api/disk/check 호출하여 사전 체크
- 90% 이상 시 경고 모달(showDiskWarningModal) 표시: 사용률, 가용 공간, 파일 정보 안내
- Cancel / Upload anyway 버튼으로 사용자 선택 가능
- 기존 업로드 로직을 startUpload()로 분리

## [2026-03-27] 메인 페이지 DISK 카드에 사용량 퍼센트 표시

[HeapDumpController.java]
- index() 메서드에 diskUsedPercent, diskUsed, diskTotal 모델 속성 추가

[index.html]
- DISK 카드 라벨 옆에 사용량 퍼센트 배지 표시
- 70% 미만: 초록색, 70~89%: 노란색, 90% 이상: 빨간색 배지

## [2026-03-27] Settings 버튼 통일 (Quick Actions에서 제거, 상단 Settings로 통일)

[index.html]
- Quick Actions에서 "API / Settings" 버튼 삭제
- 상단 topbar의 Settings 버튼을 모바일/데스크탑 모두 항상 표시하도록 변경
  (기존: 모바일에서 display:none → 변경: 항상 inline-flex)
- 모바일 미디어쿼리 내 중복 topbar-settings-btn 선언 제거

## [2026-03-27] 분석 이력 영속화 및 데이터 디렉토리 분리

[HeapDumpConfig.java]
- getDataDirectory() 메서드 추가 (heapdump.directory/data)
- initHeapDumpDirectory()에서 data 디렉토리 자동 생성

[HeapDumpAnalyzerService.java]
- resultDirectory() 경로를 /opt/heapdumps/data/{basename}/ 으로 변경
- deleteFile()에서 clearCache() 호출 제거 → 힙덤프만 삭제, 분석 결과 보존
- restoreResultsFromDisk() 리팩토링: data 디렉토리 스캔
- migrateOldResultDirs() 추가: 기존 결과 디렉토리를 data/로 자동 마이그레이션
- migrateStrayArtifacts() 타겟을 data 디렉토리로 변경
- getAllCachedResults(), getCacheKeys() 메서드 추가

[HeapDumpController.java]
- AnalysisHistoryItem에 fileDeleted, lastModified 필드 추가
- buildHistory(): 물리 파일 외 캐시에만 남은 분석 결과도 포함 (fileDeleted=true)
- getHistory() API: fileDeleted 필드 포함
- clearAllCache(): 캐시 키 기반으로 전체 삭제 방식 변경

[index.html]
- "Recent Analysis" → "Analysis history" 이름 변경
- "View all history →" 버튼 추가
- 삭제된 파일 표시: ra-deleted 스타일, "deleted" 배지
- Analysis Files 패널에서 삭제된 파일 표시 (hi-deleted, hb-del)
- 삭제 모달 메시지 변경 ("분석 결과는 보존됩니다")

[files.html]
- 삭제된 파일 분석 이력 표시 (fi-deleted 스타일, fi-del-badge)
- 삭제된 파일: download/delete/analyze 버튼 숨김, view만 표시
- 삭제 모달 메시지 변경

## [2026-03-27] Analysis History 전용 페이지 추가

[HeapDumpController.java]
- GET /history 엔드포인트 추가 (분석 수행된 항목만 필터하여 표시)
- successCount, errorCount, totalCount 통계 제공

[history.html] (신규)
- 분석 이력 전용 페이지: SUCCESS/ERROR 항목만 표시 (NOT_ANALYZED 제외)
- 각 항목에 분석 시간, 힙 사용량, suspect 수, 파일 크기, 날짜 표시
- 삭제된 파일 표시 (deleted 배지, 흐린 스타일)
- 검색 필터 기능
- SUCCESS 항목은 결과 보기 링크, ERROR+파일존재는 재분석 링크 제공

[index.html]
- "View all history" 링크 대상을 /files → /history 로 변경

## [2026-03-27] 분석 진행 화면 — 리포트별 실시간 프로그레스 표시

[HeapDumpAnalyzerService.java]
- MAT CLI 리포트 phase 감지 패턴 수정 (기존 "org.eclipse.mat.api:*" → 실제 출력 패턴)
  - "Subtask: System Overview" → overview phase
  - "Subtask: Top Component" (Reports/s) → top_components phase
  - "Subtask: Leak Suspects" → suspects phase
- 동일 phase 재진입 방지 (이미 감지된 phase는 스킵)
- SSE 전송 최적화: 진행률 변경 시 또는 50줄마다 1회 전송 (과도한 SSE 방지)
- lineCount 추적 추가

[progress.html]
- 리포트 서브스텝에 미니 프로그레스 바 추가 (overview/topcomp/suspects)
- MAT CLI 시작(init phase) 시 세 개의 빈 프로그레스 트랙 미리 표시
- 각 리포트 실행 중 해당 바가 파란색으로 채워지고, 완료 시 초록색 전환
- 리포트 순서 독립적 처리 (_lastReportPhase 기반, MAT CLI 실행 순서 변동 대응)
- setSubProgress(), completeSubProgress(), showSubProgressTracks() 함수 추가

## [2026-03-27] 분석 실패 UI 개선 — 파스텔 배경 + 실패 단계 강조

[progress.html]
- 에러 배너 배경을 파스텔 빨간색 그라데이션으로 변경 (FFF5F5 → FEE2E2)
- 에러 아이콘을 빨간 원형 ✗ 마크로 변경 (기존 노란 경고)
- 에러 메시지를 반투명 배경 박스로 감싸 가독성 향상
- 실패 단계 표시를 헤더 바로 아래에 배치, 빨간 텍스트로 명확히 표시
- 에러 시 프로그레스 카드 전체에 파스텔 빨간 배경 적용 (.card-error)
- 실패한 타임라인 단계: 빨간 배경/테두리로 강조, 라벨/설명 빨간색
- 실패 단계 이후 대기 중인 단계들을 흐리게 처리 (opacity: 0.35)
- active 단계 없이 에러 발생 시 마지막 비완료 단계를 에러로 처리
- 실패 단계의 desc를 "{단계명} 실패"로 변경 (예: "파일 확인 실패", "MAT CLI 실행 실패")

## [2026-03-27] 디스크 용량 부족 업로드 차단 + 90% 이상 경고 배너

[HeapDumpController.java]
- /api/disk/check 응답에 usableSpaceBytes (raw bytes) 필드 추가

[index.html]
- 업로드 시 파일 크기가 디스크 여유 공간보다 클 경우 업로드 차단 모달 표시
  (빨간 X 아이콘, 파일 크기 vs 여유 공간 비교, 한국어 안내 메시지)
- doUpload()에서 usableSpaceBytes와 file.size 비교 로직 추가
- 메인 홈페이지 대시보드 상단에 디스크 사용률 90% 이상 경고 배너 추가
  (노란 그라데이션 배경, 경고 아이콘, 사용률 수치 표시)
- disk-warn-banner CSS 스타일 추가

## [2026-03-27] 분석 완료 후 덤프 파일 gzip 압축

[HeapDumpAnalyzerService.java]
- 분석 성공 후 덤프 파일을 자동으로 gzip 압축 (compressDumpFile 메서드 추가)
- 압축 전 디스크 여유 공간이 원본 파일 크기 이상인지 점검, 부족 시 압축 건너뜀
- isValidHeapDumpFile()에 .hprof.gz, .bin.gz, .dump.gz 확장자 추가
- getFile()에서 .gz 압축 파일도 탐색하도록 수정
- deleteFile()에서 .gz 압축 파일도 함께 삭제하도록 수정
- stripExtension()에서 .gz 이중 확장자 처리 (.hprof.gz → base name)
- 재분석(re-analyze) 시 .gz 압축 파일 자동 감지 → 압축 해제 후 분석 → 완료 후 재압축
- decompressDumpFile 메서드 추가 (디스크 여유 공간 점검 포함)
- 원본과 .gz 동시 존재 시 중복 처리:
  · cleanupDuplicateGzFiles(): 원본이 있으면 .gz 삭제 (디스크 절약)
  · @PostConstruct 기동 시 중복 파일 자동 정리
  · analyzeWithProgress(): 분석 시작 시 원본+.gz 동시 존재하면 .gz 삭제
  · compressDumpFile(): 기존 .gz 존재 시 삭제 후 재압축
  · listFiles(): 원본과 .gz 중복 노출 방지, .gz는 원본 이름으로 표시

[HeapDumpController.java]
- downloadFile()에서 실제 파일명(.gz 포함)을 다운로드 파일명으로 사용하도록 수정

## [2026-03-29] Cache → Save 용어 통일 (디스크 저장이므로 Cache가 아님)

[index.html]
- "Cache results to disk" → "Save results to disk"
- "Clear all cache" → "Clear all results"
- "Remove all cached results" → "Remove all saved results"
- "Clear All Cache" 모달 제목 → "Clear All Results"
- "All cached analysis results..." → "All saved analysis results..."
- "Cached Results" (Settings) → "Saved Results"
- API 문서 /api/cache/clear → /api/results/clear
- fetch 호출 경로 /api/cache/clear → /api/results/clear

[analyze.html]
- "Cached results will be cleared..." → "Saved results will be cleared..."

[HeapDumpController.java]
- API 엔드포인트 /api/results/clear 추가 (기존 /api/cache/clear도 호환 유지)
- 주석 및 로그 메시지의 "캐시" → "저장 결과"로 변경

[HeapDumpAnalyzerService.java]
- 로그 메시지의 "cached results" → "saved results" 변경
- "disk cache" → "saved result" 변경

## [2026-03-29] Analysis History 페이지 히스토리 삭제 기능 추가

[HeapDumpAnalyzerService.java]
- deleteHistory() 메서드 추가: 힙덤프 파일 + 분석 결과 디렉토리 + 인덱스 파일 + 메모리 캐시 일괄 삭제

[HeapDumpController.java]
- GET /history/delete/{filename} 엔드포인트 추가
- 삭제 성공/실패 시 flash 메시지로 결과 전달 후 /history로 리다이렉트

[history.html]
- 각 히스토리 항목에 삭제 버튼(휴지통 아이콘) 추가
- 삭제 확인 모달 추가 (파일명 표시, 경고 메시지, 취소/삭제 버튼)
- 힙덤프 파일이 이미 삭제된 경우 모달 내 파일명 옆에 "힙덤프 파일 이미 삭제됨" 뱃지 표시

[HeapDumpController.java]
- Settings API 디스크 사용량 계산을 getFreeSpace() → getUsableSpace()로 통일
  (홈페이지 DISK %와 Settings Disk Usage % 수치 불일치 해소)

## [2026-03-29] MAT JVM 힙 메모리 설정 기능 추가

[HeapDumpAnalyzerService.java]
- getMatHeapSize(): MemoryAnalyzer.ini에서 -Xmx 값을 바이트 단위로 읽기
- getMatHeapSizeString(): -Xmx 문자열 반환 (예: "2048m")
- setMatHeapSize(): MemoryAnalyzer.ini의 -Xmx 값 변경
- 분석 시작 시 덤프 크기 × 2 > MAT 힙 설정이면 SSE 경고 메시지 전송

[AnalysisProgress.java]
- warningMessage 필드 추가
- warning() 팩토리 메서드 추가 (분석 계속 진행하면서 경고 전달)

[HeapDumpController.java]
- GET /api/mat/heap: 현재 MAT 힙 설정 조회
- POST /api/mat/heap?size=4096m: MAT 힙 설정 변경
- GET /api/settings 응답에 mat.heapSize, mat.heapBytes, mat.heapFormatted 추가

[index.html]
- Settings 모달 MAT Configuration에 "MAT JVM Heap (-Xmx)" 표시 및 편집 UI 추가
- 프리셋 선택 (1GB ~ 16GB) + Apply/Cancel 버튼
- 권장 사항 안내 문구 표시

[progress.html]
- 경고 배너 (warn-banner) 추가: 노란색 그라데이션, 경고 아이콘
- SSE warningMessage 수신 시 경고 배너 표시
- Dashboard Settings 링크 안내

## [2026-03-29] 분석 전 힙 메모리 부족 경고 모달 및 전역 설정 추가

[HeapDumpController.java]
- GET /api/mat/heap-check?filename= 추가: 분석 전 덤프 크기 × 2 vs MAT 힙 사전 체크

[progress.html]
- SSE 시작 전 /api/mat/heap-check 호출로 사전 체크
- 힙 부족 시 경고 모달 표시: 덤프 크기, 권장 힙, 현재 설정 정보
- "분석 계속" / "취소" 버튼으로 사용자 선택
- "다음부터 이 경고를 표시하지 않고 바로 분석 진행" 체크박스
- skipHeapWarning=true면 경고 없이 바로 분석 시작

[index.html]
- Settings 사이드바에 "Skip heap memory warning" 토글 추가
- localStorage 기반 전역 설정 (skipHeapWarning)

[HeapDumpAnalyzerService.java]
- 서버 측 SSE 경고 제거 (클라이언트 사전 체크로 대체)

## [2026-03-29] Settings 별도 페이지 분리 + 홈페이지 레이아웃 개편

[settings.html] — 신규 생성
- 별도 전용 페이지로 Settings 전면 개편 (기존 모달 방식에서 분리)
- 2-column 그리드 레이아웃으로 관리자 친화적 배치:
  · MAT Configuration: CLI 경로, 상태, JVM Heap 설정 (인라인 편집)
  · Analysis Options: Keep Unreachable, Auto-analyze, Save to disk, Skip heap warning (토글)
  · Storage: 덤프 디렉토리, 저장된 결과 수, 파일 수, 총 크기
  · Disk Usage: 사용량/전체, 여유 공간, 프로그레스바
  · System Info: Java, OS, CPU
  · JVM Memory: Max/Used/Free, 프로그레스바
  · API Endpoints: 2-column 카드 레이아웃
  · Danger Zone: Clear All Results (빨간 경계선)
- 확인 모달: Keep Unreachable 해제, Auto-analyze 활성화, Clear All Results

[HeapDumpController.java]
- GET /settings 엔드포인트 추가 → settings.html 반환

[index.html] — 구조 개편
- MAT OPTIONS 사이드바 섹션 제거 (Settings 페이지로 이관)
- Quick Actions를 왼쪽 사이드바로 이동 (Compare, Export History, Settings 링크)
- Quick Actions 메인 패널 제거
- topbar Settings 버튼을 /settings 페이지 링크로 변경 (모달 방식 폐지)
- Settings 모달 HTML/CSS/JS 전체 제거
- Keep Unreachable, Auto-Analyze, Clear Cache 모달 제거
- 관련 JS 함수 정리 (confirmKeepUnreachable, toggleMatHeapEdit, saveMatHeap 등)

## [2026-03-29] MAT -Xms 설정 추가 + 힙 메모리 경고 검증

[HeapDumpAnalyzerService.java]
- readIniJvmArg/writeIniJvmArg 공통 메서드로 리팩토링
- getMatInitialHeapSize(), getMatInitialHeapSizeString(), setMatInitialHeapSize() 추가

[HeapDumpController.java]
- GET /api/mat/heap: xmsSize, xmsBytes, xmsFormatted, physicalMemory 정보 추가
- POST /api/mat/heap: type=xms 파라미터 지원 (-Xms 변경)
- GET /api/settings: mat 섹션에 xmsSize, xmsBytes, xmsFormatted 추가

[settings.html]
- MAT Configuration에 "MAT JVM Initial Heap (-Xms)" 행 + 인라인 편집 추가
- "System Physical Memory" 표시 행 추가
- -Xmx Apply 시 경고 모달:
  · 시스템 물리 메모리 초과 시 경고 (스왑/OOM Killer 위험)
  · -Xms보다 작을 경우 경고 (JVM 시작 불가)
- -Xms Apply 시 경고 모달:
  · -Xmx보다 클 경우 경고
  · 시스템 물리 메모리 초과 시 경고
- 경고 모달에서 "강제 적용" 또는 "Cancel" 선택 가능

## [2026-03-29] 분석 페이지 헤더/사이드바를 메인 페이지 기준으로 통일

[analyze.html]
- 헤더 높이 56px → 52px (메인 페이지와 동일)
- 사이드바 너비 290px → 300px (메인 페이지와 동일)
- 헤더에 box-shadow, justify-content:space-between 추가
- 헤더 HTML을 메인 페이지와 동일한 로고 아이콘(파란 둥근 사각형 + 체크) + 제목 구조로 변경
- 파일명 뱃지를 브랜드 영역에 포함
- 버튼 스타일 통일 (색상 #6B7280, hover 배경)
- 데스크톱/태블릿/모바일 미디어쿼리 조정

## [2026-03-29] [보안] XSS 방어 강화 — OWASP HTML Sanitizer 도입 (Phase 1A)

[pom.xml]
- owasp-java-html-sanitizer 20220608.1 의존성 추가

[신규: util/HtmlSanitizer.java]
- OWASP PolicyFactory 기반 whitelist HTML 새니타이저
- MAT 리포트에서 사용하는 태그만 허용 (table, div, span, a, 서식 태그 등)
- body 추출 패턴 사전 컴파일 (static final Pattern)
- 깨진 href → javascript:void(0) 치환

[parser/MatReportParser.java]
- sanitizeHtml(): regex 기반 제거 → HtmlSanitizer.sanitize() 위임
- 약 30줄의 regex 체인 제거

[service/HeapDumpAnalyzerService.java]
- extractBodyContent(): 동일한 regex 기반 제거 → HtmlSanitizer.sanitize() 위임
- MatReportParser와의 중복 코드 완전 제거

## [2026-03-29] [보안] Path Traversal 차단 — Controller 파일명 검증 (Phase 1B)

[신규: util/FilenameValidator.java]
- 파일명 검증 유틸리티: null/empty, path traversal(.., /, \), null byte 차단
- 허용 확장자 whitelist: .hprof, .bin, .dump (+.gz 변형)
- Paths.get().getFileName()으로 경로 구성요소 제거

[controller/HeapDumpController.java]
- 16개 엔드포인트 + compare의 2개 파라미터에 FilenameValidator.validate() 적용
- @ExceptionHandler(IllegalArgumentException.class) 추가 → 400 응답
- 검증 실패 시 로그 기록: "[Validation] Rejected request: ..."

## [2026-03-29] [보안] 정보 노출 축소 — /api/settings 응답 최소화 (Phase 1C)

[controller/HeapDumpController.java]
- 절대 경로 마스킹: heapDumpDirectory, mat.path → "***/파일명" 형태로 변환
  - maskPath() 헬퍼 메서드 추가
- 서버 상세 정보 제거: javaVendor, osName, osArch 필드 삭제
- MAT 파일 권한 상세 제거: exists, executable, readable 필드 삭제
  - ready + statusMessage만 유지 (운영 상태 확인에 충분)
- matCliPath 최상위 필드 제거 (mat.path에서 마스킹된 값으로 제공)

[templates/settings.html]
- System Info 카드에서 Java Vendor, OS 행 제거
- JS loadAllData()에서 javaVendor, osName, osArch 참조 제거

## [2026-03-29] [보안] 파괴적 GET → POST 전환 (Phase 1D)

[controller/HeapDumpController.java]
- GET /delete/{filename} → POST 전환 (파일 삭제)
- GET /history/delete/{filename} → POST 전환 (히스토리 삭제)
- GET /analyze/rerun/{filename} → POST 전환 (캐시 삭제 후 재분석)
  → CSRF, 크롤러 오삭제, 링크 프리페치 등에 의한 의도치 않은 파괴적 동작 방지

[templates/index.html]
- showDeleteModal() → postRedirect() 헬퍼로 POST form 제출

[templates/files.html]
- btnConfirmDel 클릭 → 동적 form POST 제출로 변경

[templates/history.html]
- deleteLink <a> 태그 → submitDelete() 함수 + 동적 form POST 제출로 변경

[templates/analyze.html]
- Re-Analyze Thymeleaf <a> 링크 2개 → <form method="post"> + <button> 전환
- doReanalyze() JS 함수 → 동적 form POST 제출로 변경

[templates/progress.html]
- retryBtn → onclick="postRerun()" + 동적 form POST 제출로 변경

## [2026-03-29] [안전성] 스레드 풀 바운딩 (Phase 2A)

[service/HeapDumpAnalyzerService.java]
- Executors.newCachedThreadPool() → ThreadPoolExecutor(core=2, max=4, queue=8) 전환
  - core 2: 평상시 유지되는 스레드 (분석 1 + SSE 1)
  - max 4: 동시 요청 폭주 시 최대 스레드 수 제한
  - queue 8: 대기 작업 큐 크기 제한
  - CallerRunsPolicy: 큐 포화 시 호출자 스레드에서 실행 (요청 거부 대신 백프레셔)
  - keepAlive 60초: 유휴 스레드 자동 회수
- 기존 Semaphore(1) 분석 직렬화 + shutdown 로직은 그대로 유지

## [2026-03-29] [성능] Regex 패턴 사전 컴파일 (Phase 2B)

[parser/MatReportParser.java]
- 12개 static final Pattern 상수 추가:
  NON_DIGIT_PATTERN, COMMA_SPACE_PATTERN, WHITESPACE_PATTERN, HEX_ADDR_PATTERN,
  HEX_ADDR_EXTRACT_PATTERN, TOTAL_ENTRIES_PATTERN, ALL_OBJECTS_SUFFIX_PATTERN,
  PROBLEM_SUSPECT_PATTERN, ARROW_CHAR_PATTERN, ARROW_SPACE_PATTERN,
  ONLY_OBJECT_PATTERN, FIRST_N_OF_PATTERN, ALL_N_OBJECTS_PATTERN, ONLY_N_OBJECTS_PATTERN
- parseSuspectsZip() 로컬 Pattern.compile 2개 → PROBLEM_SUSPECT_PATTERN, TAG_PATTERN 재사용
- parseHistogramEntries() 로컬 Pattern.compile 1개 → TOTAL_ENTRIES_PATTERN
- parseThreadInfoEntries() 로컬 Pattern.compile 1개 → HEX_ADDR_EXTRACT_PATTERN
- 인라인 String.replaceAll() 전체 → Pattern.matcher().replaceAll() 전환
- stripTags() 헬퍼: HTML 태그 제거 + 공백 정규화 (5곳 중복 제거)
- digitsOnly() 헬퍼: 숫자 외 문자 제거 (11곳 중복 제거)

## [2026-03-29] [리팩토링] formatBytes() 통합 (Phase 3A)

[신규: util/FormatUtils.java]
- public static formatBytes(long) — 바이트 크기를 B/KB/MB/GB 문자열로 변환
- KB: %.1f, MB/GB: %.2f 포맷 통일

[controller/HeapDumpController.java]
- private formatBytes() → FormatUtils.formatBytes() 위임

[service/HeapDumpAnalyzerService.java]
- private formatBytes() → FormatUtils.formatBytes() 위임

[model/HeapAnalysisResult.java]
- private formatBytes() → FormatUtils.formatBytes() 위임
- (기존 KB %.2f → %.1f로 통일)

## [2026-03-29] [리팩토링] 하드코딩 값 설정 추출 (Phase 3B)

[config/HeapDumpConfig.java]
- 8개 @Value 프로퍼티 추가 (application.properties로 재정의 가능):
  - mat.timeout.minutes (기본 30) — MAT CLI 실행 타임아웃
  - sse.emitter.timeout.minutes (기본 35) — SSE Emitter 타임아웃
  - dashboard.history.max-display (기본 5) — 대시보드 히스토리 표시 수
  - disk.warning.usage-percent (기본 90) — 디스크 사용률 경고 임계값
  - disk.warning.free-space-mb (기본 500) — 디스크 여유 공간 경고 임계값
  - analysis.top-objects.max-display (기본 10) — Top Memory Objects 표시 수
  - analysis.mat-log.max-display-chars (기본 5000) — 에러 시 MAT 로그 표시 길이
  - analysis.progress.log-update-lines (기본 50) — 진행률 로그 업데이트 빈도
- initHeapDumpDirectory() 내 500MB 하드코딩 → diskWarningFreeSpaceMb 참조

[service/HeapDumpAnalyzerService.java]
- MAT_TIMEOUT_MINUTES 상수 제거 → config.getMatTimeoutMinutes()
- lineCount % 50 → config.getProgressLogUpdateLines()

[controller/HeapDumpController.java]
- config 의존성 주입 추가
- maxDisplay 5 → config.getDashboardHistoryMaxDisplay()
- SSE 35분 → config.getSseEmitterTimeoutMinutes()
- 디스크 경고 90% → config.getDiskWarningUsagePercent()
- MAT 로그 5000자 → config.getMatLogMaxDisplayChars()
- Top Objects limit(10) → config.getTopObjectsMaxDisplay()

## [2026-03-29] application.properties에 Phase 3B 설정값 8개 명시 추가

[resources/application.properties]
- MAT CLI 실행 설정 섹션 추가: mat.timeout.minutes, sse.emitter.timeout.minutes,
  analysis.progress.log-update-lines
- 분석 결과 표시 설정 섹션 추가: dashboard.history.max-display,
  analysis.top-objects.max-display, analysis.mat-log.max-display-chars
- 디스크 모니터링 임계값 섹션 추가: disk.warning.usage-percent, disk.warning.free-space-mb

## [2026-03-29] 웹 Settings 변경 시 설정 영속화 (settings.json)

[service/HeapDumpAnalyzerService.java]
- 런타임 설정 영속화: {heapdump.directory}/data/settings.json에 저장/로드
- loadPersistedSettings(): @PostConstruct에서 settings.json 로드
  → application.properties 기본값을 settings.json 값으로 덮어씀
- persistSettings(): 설정 변경 시 즉시 settings.json에 기록
- setKeepUnreachableObjects() 변경 시 자동 영속화
- 기존 문제: 웹에서 Keep unreachable 변경 후 서버 재시작 시 유실 → 해결

설정 우선순위: settings.json (웹 변경) > application.properties (기본값)

## [2026-03-29] settings.json 방어 로직 강화

[service/HeapDumpAnalyzerService.java]
- loadPersistedSettings() 방어 시나리오 6가지:
  1. 파일 미존재 → 기본값으로 새로 생성
  2. 빈 파일 (0 bytes) → 기본값으로 재생성
  3. null/빈 맵 → 기본값으로 재생성
  4. 예상 외 타입 (String 등) → Boolean.parseBoolean 변환 + 경고 로그
  5. 정상 Boolean → 직접 캐스팅
  6. 깨진 JSON → .corrupted 백업 후 기본값으로 재생성
- persistSettings() 방어:
  - data 디렉토리 미존재 시 자동 생성 (mkdirs)

## [2026-03-30] Leak Suspects 지능형 분석 시스템 구현

[model/LeakSuspect.java]
- category, explanation, advice, severity 필드 추가
- @AllArgsConstructor 제거, 명시적 2인자 생성자 유지 (기존 호환)
- @JsonIgnoreProperties(ignoreUnknown = true) 추가 (기존 캐시 호환)

[util/LeakSuspectAdvisor.java] (신규 → 전면 재작성)
- MAT 텍스트에서 구조화된 정보를 추출하는 SuspectContext 파서:
  · 인스턴스 수, 클래스명(FQ/단순), ClassLoader, 메모리 크기/비율
  · 축적 대상 클래스(accumulator), 참조 출처(referenced from)
- 알려진 라이브러리 자동 감지 (KnownLibrary, 16개 규칙):
  · APM: New Relic, Dynatrace
  · WAS: JEUS, Apache Tomcat
  · ORM: Hibernate
  · Framework: Spring, Netty, Jackson, Elasticsearch
  · Connection Pool: HikariCP
  · Logging: Log4j, Logback
  · JDK: ZipFile, Reflection, Class 메타데이터
- 동적 설명 생성: 실제 클래스명, 인스턴스 수, 메모리 비율, 축적 대상을 설명에 삽입
- 심각도 자동 산정: critical(≥50%), high(≥25%), medium(≥10%), low(<10%)
- fallback: 기존 키워드 규칙 8개 + 동적 컨텍스트 보강 (enrichExplanation)

[parser/MatReportParser.java]
- parseSuspectsZip()에서 LeakSuspect 생성 후 LeakSuspectAdvisor.analyze() 호출
- primary/fallback 모두 advisor 연동

[templates/analyze.html]
- severity 기반 동적 색상: critical(암적색), high(빨강), medium(주황), low(녹색)
  · severity가 null이면 기존 position 기반 fallback (이전 캐시 호환)
- suspect-header에 카테고리 뱃지(파란색 라벨) 추가
- suspect-body에 분석 설명 박스(파란 배경) + 권장 조치 박스(녹색 배경) 추가
- CSS: .severity-critical, .suspect-category, .suspect-explanation, .suspect-advice 등

## [2026-03-30] KnownLibrary 규칙 대폭 확장 (17개 → 56개)

[util/LeakSuspectAdvisor.java]
- KnownLibrary 규칙 39개 추가 (총 56개):
  · APM: Datadog, Pinpoint, Micrometer
  · WAS: WebLogic, WebSphere, JBoss/WildFly, Undertow
  · 한국형 프레임워크: WebSquare(인스웨이브), Inswave, ProWorks, WMatrix
  · ORM: MyBatis, EclipseLink, jOOQ
  · DB 드라이버: MySQL, Oracle, PostgreSQL, Tibero(TmaxSoft)
  · 커넥션 풀: DBCP, Tomcat DBCP, C3P0
  · 캐시: Ehcache, Caffeine, Hazelcast
  · 메시징: Kafka, RabbitMQ, LMAX Disruptor
  · Redis/NoSQL: Jedis, Lettuce, MongoDB
  · HTTP 클라이언트: Apache HttpClient, OkHttp
  · DI/직렬화: Google Guice, Guava, Gson
  · 리액티브/gRPC: Project Reactor, gRPC
  · JDK: NIO Buffer

## [2026-03-30] 대기열 알림 지연 수정

[service/HeapDumpAnalyzerService.java]
- 대기열 진입 시 첫 QUEUED SSE 메시지를 즉시 전송하도록 수정
- 기존: tryAcquire(3초) 타임아웃 후에야 첫 QUEUED 알림 전송 → 최대 3초 지연
- 수정: 세마포어 획득 실패 직후 즉시 QUEUED 메시지 전송, 이후 3초 간격 갱신 유지

## [2026-03-30] 히스토리 삭제 시 힙덤프 파일 삭제 선택 옵션 추가

[templates/history.html]
- 삭제 모달에 "힙덤프 파일도 함께 삭제" 체크박스 추가
- 기본 동작: 분석 결과 데이터만 삭제 (힙덤프 파일 보존)
- 체크 시: 힙덤프 파일(.hprof, .gz, tmp)도 함께 삭제
- 힙덤프 파일이 이미 삭제된 경우 체크박스 자동 숨김

[controller/HeapDumpController.java]
- /history/delete 엔드포인트에 deleteHeapDump 파라미터 추가 (기본값 false)

[service/HeapDumpAnalyzerService.java]
- deleteHistory() 메서드에 deleteHeapDump 파라미터 추가
- deleteHeapDump=false면 힙덤프 파일 삭제 단계 스킵
- MAT 인덱스 파일 삭제 시 힙덤프 파일 확장자(.hprof, .bin, .dump, .gz) 제외 필터 추가
  (기존: baseName.* 패턴이 힙덤프 파일까지 매칭하여 의도치 않은 삭제 발생)

## [2026-03-30] 분석 스레드 풀 개선 — MAT 출력 리더 분리 + 설정 외부화

[config/HeapDumpConfig.java]
- 분석 스레드 풀 설정 3개 추가 (core-size, max-size, queue-capacity)
- @PostConstruct에서 설정값 검증 (core < 3 경고, max < core 보정)

[service/HeapDumpAnalyzerService.java]
- executor를 하드코딩(core=2,max=4,queue=8)에서 application.properties 기반으로 변경
- MAT CLI 출력 리더를 CompletableFuture.runAsync(executor)에서 전용 데몬 스레드로 분리
  → 근본 원인: MAT 리더가 executor core 스레드를 점유하여 대기 태스크가 실행 불가였음
- RejectedExecutionHandler에 스레드 풀 고갈 에러 로그 추가
  (active/poolSize/queueSize/completedTasks 출력 + CallerRuns 대체 실행)

[application.properties]
- analysis.thread-pool.core-size=3 (기본값, 최소 3 권장)
- analysis.thread-pool.max-size=5
- analysis.thread-pool.queue-capacity=12

## [2026-03-30] 대기열 취소 시 태스크가 큐에 남는 버그 수정

[service/HeapDumpAnalyzerService.java]
- sendProgress()에서 IOException 발생 시 스레드 인터럽트 누락 수정
- 기존: IllegalStateException만 인터럽트, IOException은 debug 로그만 남김
  → 클라이언트 disconnect 후에도 세마포어 대기 루프가 중단되지 않아 큐에 잔류
- 수정: 모든 SSE 전송 실패를 disconnect로 간주하고 즉시 인터럽트
  → 세마포어 대기 루프의 tryAcquire()가 InterruptedException 발생 → finally에서 queueSize 감소

## [2026-03-30] 대기열 취소 — 전용 Cancel API + 모달 UI 추가

[controller/HeapDumpController.java]
- POST /api/analyze/cancel/{filename} 엔드포인트 추가

[service/HeapDumpAnalyzerService.java]
- activeTasks(ConcurrentHashMap) 맵으로 분석 Future 추적
- cancelAnalysis(filename) 메서드: 맵에서 Future 조회 → cancel(true) 호출
- analyzeWithProgress: Future를 맵에 저장, finally에서 제거

[templates/progress.html]
- 대기열 상태에서 취소 시 모달("대기열에서 제거하고 취소하시겠습니까?") 표시
- 모달 확인 시 POST /api/analyze/cancel API 호출로 서버 측 즉시 취소
- RUNNING 상태에서는 기존 confirm 다이얼로그 유지
- _isQueued 플래그로 QUEUED/RUNNING 상태 추적
- 힙 경고 모달용 cancelAnalysis()를 cancelHeapWarnModal()로 분리
  (동일 함수명 중복 정의로 후자가 전자를 덮어써 모달/API 호출 불가했던 버그 수정)

## [2026-03-30] analyze.html Re-Analyze 버튼 크기 통일

[templates/analyze.html]
- 헤더 .btn-sm에 line-height, box-sizing, font-family 추가
- 본문 에러 영역 Re-Analyze <button>과 Back to Home <a> 버튼에도 동일 속성 적용
- 분석 실패 시 <form><button>과 <a> 태그 버튼 크기 통일

## [2026-03-30] 힙덤프 파일 보호 — 디렉토리 구조 개편 + 압축 검증 + 설정 UI

[디렉토리 구조]
- /opt/heapdumps/dumpfiles/ 신규: 원본 힙덤프 저장 (업로드, 감지 대상)
- data/, tmp/ 역할 기존 유지
- 기동 시 루트의 .hprof/.bin/.dump 파일 → dumpfiles/ 자동 마이그레이션

[service/HeapDumpAnalyzerService.java]
- 업로드: tmp/ → dumpfiles/로 변경
- 분석: dumpfiles/ → tmp/ copy → MAT CLI → tmp 삭제 (원본 안전)
- 디스크 여유 공간 체크: tmp copy 전 sourceSize*2 이상 확인
- compressDumpFile() .gz 검증 강화 (0바이트 방지, 검증 실패 시 원본 보존)
- compressAfterAnalysis 설정으로 압축 ON/OFF 제어 (settings.json 영속)
- listFiles()에서 cleanupDuplicateGzFiles 호출 제거 (레이스 컨디션 방지)
- deleteFile(), deleteHistory(), getFile() → dumpfiles/ 기준 + 레거시 fallback

[model/HeapDumpFile.java] compressed, originalSize, compressedSize 필드 추가
[model/HeapAnalysisResult.java] originalFileSize 필드 추가
[config/HeapDumpConfig.java] getDumpFilesDirectory(), compress-after-analysis 설정
[controller/HeapDumpController.java] POST /api/settings/compress API 추가
[templates/settings.html] "Compress after analysis" 토글 스위치 추가
[templates/index.html] 파일 목록에 압축 상태 (GZ 배지 + 원본/압축 크기) 표시
[application.properties] analysis.compress-after-analysis=true 추가

## [2026-03-30] MAT CLI 출력물(ZIP/인덱스) tmp 디렉토리 탐색 누락 수정

[service/HeapDumpAnalyzerService.java]
- moveZipsToResultDir(): MAT CLI가 tmp에서 실행되므로 ZIP이 tmp에 생성됨
  → tmp + 루트 디렉토리 모두 탐색하도록 변경
- moveArtifactsToResultDir(): 동일하게 tmp + 루트 탐색으로 변경
- 원인: 디렉토리 구조 개편으로 MAT CLI 실행 위치가 변경되었으나
  ZIP/인덱스 파일 수집 로직이 기존 루트만 탐색하여 "Heap data not available" 발생

## [2026-03-30] application.properties ↔ settings.json 동기화

[service/HeapDumpAnalyzerService.java]
- syncApplicationProperties() 추가: 런타임 설정 변경 시 application.properties도 동기화
- findExternalPropertiesFile() 추가: JAR 디렉토리 → 소스 디렉토리 순으로 탐색
- 줄 단위 치환으로 주석/포맷 보존하며 해당 키 값만 교체
- persistSettings(), loadPersistedSettings() 양쪽에서 호출
- 동기화 대상: mat.keep.unreachable.objects, analysis.compress-after-analysis

## [2026-03-31] Analysis Files 페이지 — 인디케이터 범례 및 압축 용량 표시

[templates/files.html]
- 상단에 인디케이터 색상별 범례 추가 (녹색=분석 완료, 노란색=미분석, 빨간색=분석 오류)
- 압축된 파일(GZ)의 경우 원본 크기 → 압축 크기 형태로 표시 + GZ 배지
- 비압축 파일은 기존 단일 크기 표시 유지

[controller/HeapDumpController.java]
- AnalysisHistoryItem에 compressed, formattedOriginalSize, formattedCompressedSize 필드 추가
- buildHistory()에서 HeapDumpFile의 압축 정보를 AnalysisHistoryItem에 매핑

## [2026-03-31] 업로드 기능 — .gz 파일 업로드 지원 추가

[templates/index.html]
- accept 속성에 .gz 확장자 추가
- 안내 텍스트에 (.gz) 표기 추가
- validateFileExt() 함수: .hprof.gz, .bin.gz, .dump.gz 확장자 허용
- showExtWarning() 모달: Supported Formats에 .gz 배지 추가

[static/js/main.js]
- 파일 선택(change) 핸들러: .gz 복합 확장자 검증으로 변경
- 드래그앤드롭(drop) 핸들러: validExts에 .gz 확장자 추가
- 폼 제출(submit) 핸들러: .gz 복합 확장자 검증으로 변경

[service/HeapDumpAnalyzerService.java]
- 업로드 거부 시 에러 메시지에 .gz 포함

※ 백엔드(isValidHeapDumpFile, FilenameValidator)는 이미 .gz를 허용하고 있었음
  → 프론트엔드만 차단하고 있어 프론트엔드 검증 로직만 수정

## [2026-03-31] 파일 용량 표시 — 압축 파일 원본 크기 기준으로 개선

[service/HeapDumpAnalyzerService.java]
- listFiles(): 압축 파일의 originalSize 필드 설정 누락 수정
  (캐시에 원본 크기 있으면 originalSize + size 모두 원본 값으로 설정)

[controller/HeapDumpController.java]
- filesPage(): totalSize=원본 크기(size) 합산, diskSize=압축 파일은 compressedSize 사용
- index(): totalSize를 원본 크기 기준으로 계산 (size가 이미 원본 크기)
- /api/settings: totalSize 원본 기준 + diskSize 추가 응답

[templates/files.html]
- 상단 통계: "X.XX GB original" + 압축 파일 존재 시 "X.XX GB disk" 파란색으로 추가 표시

## [2026-03-31] 분석 중(progress) 페이지 배경색 통일

[templates/progress.html]
- body 배경색을 메인 페이지와 동일한 #F3F4F6으로 변경 (기존: style.css의 #fff)

## [2026-03-31] Re-analyze 시 덤프 파일 없으면 기존 분석 데이터 보존

[controller/HeapDumpController.java]
- rerunAnalysis(): 캐시 삭제 전 덤프 파일 존재 여부 확인 (원본 + .gz + 레거시 경로)
- 파일 없으면 clearCache() 호출하지 않고 기존 결과 페이지로 리다이렉트 + 경고 메시지

[templates/analyze.html]
- header 아래에 rerunError 배너 추가 (노란색 경고, ERROR/SUCCESS 양쪽 결과 페이지 대응)

## [2026-04-07] RAW DATA 영역에 MAT Main Overview + Dominator Tree 페이지 추가

[parser/MatReportParser.java]
- findReportZip(): 외부에서 ZIP 파일을 찾을 수 있도록 public 래퍼 메서드 추가

[service/HeapDumpAnalyzerService.java]
- findReportZip(): filename + reportType으로 MAT 리포트 ZIP 파일 조회
- hasReportZip(): ZIP 파일 존재 여부 확인

[controller/HeapDumpController.java]
- GET /report/{filename}/mat-page/{reportType}/** 엔드포인트 추가
  - MAT 리포트 ZIP 내 파일을 직접 서빙 (HTML, CSS, JS, 이미지)
  - 파일 확장자 기반 Content-Type 자동 설정
  - mat:// 프로토콜 링크 비활성화 처리
  - path traversal 방지 + reportType 화이트리스트 보안 적용
  - 브라우저 캐싱 (Cache-Control: max-age=3600)
- analyzeResult(): hasOverviewZip 모델 속성 추가

[templates/analyze.html]
- Raw Data 사이드바에 "Main Overview" 버튼 추가 (overview ZIP 존재 시 표시)
- iframe 기반 MAT Main Overview 패널 추가
  - 내부 페이지 링크 탐색 가능 (System Properties, Thread Overview, Top Consumers 등)
  - MAT code.js (접기/펼치기) 기능 동작
  - lazy-load + 자동 높이 조절
- iframe 기반 Dominator Tree 패널 추가 (Top Components ZIP)
  - 클래스 로더별 도미네이터 분석, Retained Set, Top Consumers 등 서브 페이지 탐색 가능
  - 차트 이미지, 접기/펼치기 기능 동작
- iframe lazy-load 로직을 범용 맵 기반으로 리팩터링

## [2026-04-07] Top Consumers 컴포넌트 상세 — 정제된 분석 뷰 추가

[model/ComponentDetailParsed.java] (신규)
- 컴포넌트 상세 파싱 결과 최상위 모델 (className, metadata, sections, parsedSuccessfully)

[model/ComponentMetadata.java] (신규)
- 컴포넌트 메타데이터: Size, Classes, Objects, Class Loader

[model/ComponentSection.java] (신규)
- 섹션 모델: TABLE/TREE/TEXT 타입, 심각도(severity), 테이블/텍스트 콘텐츠

[model/TableData.java] (신규)
- 테이블 데이터: headers, rows, rightAligned 정렬 힌트

[model/TreeNode.java] (신규)
- 트리 노드: label, objectRef, depth, children, columns

[model/HeapAnalysisResult.java]
- componentDetailParsedMap 필드 추가 (Map<String, ComponentDetailParsed>)

[model/MatParseResult.java]
- componentDetailParsedMap 필드 추가

[parser/MatReportParser.java]
- parseComponentDetail(): sanitize된 HTML → 구조화된 ComponentDetailParsed 변환
  - 메타데이터(Size/Classes/Objects/ClassLoader) 추출
  - h2~h5 섹션 헤더 + exp### 콘텐츠 쌍으로 섹션 분할
  - <table class="result"> 감지 → TABLE 타입 파싱
  - 리프 텍스트 섹션 → TEXT 타입 + 목록 항목 추출
  - 컨테이너 섹션(하위 섹션 있는) 중복 방지
  - 심각도: 제목 키워드 기반 추론 (memory waste, duplicate, collision 등)
- buildSectionTree(): heading level 기반 flat → 부모-자식 트리 변환
  - h3(Possible Memory Waste) → h4(Duplicate Strings, Empty Collections...) 계층 자동 구성
  - 빈 섹션(텍스트/테이블/children 없음) 자동 제거
- extractComponentDetailPages(): raw HTML 저장 시 동시에 구조화 파싱 수행
- parseTableHtml(), extractTextDescription(), extractListItems(), parseCountString() 헬퍼

[service/HeapDumpAnalyzerService.java]
- getParser() public 접근자 추가
- buildResult(): componentDetailParsedMap 결과 복사
- cloneWithoutLog(): componentDetailParsedMap 복사
- sanitizeCachedHtml(): 기존 캐시에 parsedMap 없으면 lazy 파싱 수행 (하위 호환)
- reparsComponentDetails(): parsedMap도 함께 복원

[controller/HeapDumpController.java]
- GET /report/{fn}/component-detail-parsed: JSON API 엔드포인트 추가
  - parsedMap → raw HTML 즉석 파싱 → 404 순서로 조회

[templates/analyze.html]
- CSS: .cd-tabs, .cd-meta, .cd-section, .cd-table, .cd-badge 등 정제된 뷰 스타일
- 모달: 탭 바 (Analysis / Raw Data) + 두 뷰 컨테이너 구조
- JS: showComponentDetail() → parsed JSON 우선 fetch, 실패 시 raw HTML 폴백
  - renderParsedDetail(): 메타데이터 카드 + 섹션별 렌더링
  - renderCdTable(): 정렬 힌트 포함 테이블, 100행 초과 시 truncate 안내
  - renderCdSection(): 접히는 섹션 + 하위 섹션 재귀 렌더링, 심각도 배지, children 개수 표시
  - switchCdTab(): Raw Data lazy 로드, 탭 전환
  - toggleCdSection(): 섹션 토글 애니메이션

## [2026-04-07] RAW DATA 패널 iframe 전환 (내부 링크 네비게이션 활성화)

[controller/HeapDumpController.java]
- hasSuspectsZip model attribute 추가

[templates/analyze.html]
- RAW DATA 3개 패널(System Overview, Top Components, Suspects)을 mat-frame → iframe 전환
  - 기존: HtmlSanitizer가 ZIP 내부 상대 경로 링크를 javascript:void(0)으로 변환하여 클릭 불가
  - 변경: mat-page API를 통한 iframe으로 전환하여 ZIP 내부 페이지 네비게이션 활성화
  - mat:// 프로토콜 링크는 MAT 데스크톱 전용이므로 비활성 유지
- 기존 Main Overview iframe + System Overview mat-frame → System Overview iframe 1개로 통합
- 기존 Top Components mat-frame + Dominator Tree iframe → Top Components iframe 1개로 통합
- Suspects mat-frame → Suspects iframe 전환
- 사이드바 메뉴 정리: Main Overview, Dominator Tree 항목 제거 (중복)
- iframe lazy-load 맵 업데이트
