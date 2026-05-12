# Phase 4A — 서비스 분리 상세 계획

## Context

`SECURITY_REFACTOR_PLAN.md` 의 Phase 4A 후속 작업. Phase 6-1 (HeapAnalysisResultCache 분리, 2026-05-12 완료) 이후 남은 3 개 sub-task 를 단계적 PR 로 분할.

`HeapDumpAnalyzerService.java` 는 현재 **3,581 라인** 으로 분석 + LLM + RAG + 파일 관리 + 설정 영속화 + AI 인사이트 등 7~8 개 책임이 혼재. 한 번에 모두 분리하면 회귀 위험 큼. **결합도 낮은 순으로 1 개 PR 씩 분리**.

### 전체 목표 (Phase 4A 종료 시)

- `HeapDumpAnalyzerService` → ~1,500 라인 (분석 + executor + queue + 캐시 위임)
- `LlmConfigService` (신규) — LLM 설정 + HTTP 호출 + SSL 토글
- `RagConfigService` (신규) — RAG 26 필드 + 암호화 + settings 영속화
- `FileManagementService` (신규) — 디렉토리 조작 + 압축 + 중복 체크
- `AiInsightManager` (신규, 선택) — AI 인사이트 저장/로드/삭제

### 비범위 (Phase 4A 에서 제외)

- Controller 분리 (Phase 4B, 별도 사이클)
- 설정 영속화 통합 (각 서비스가 자신의 settings 책임 — 결합 유지)
- HTTP 빌더 추상화 (provider 별 분기를 한 곳에 정리하는 작업은 별도)

---

## 진행 순서 (5 개 PR 분할)

각 PR 은 빌드/기동/회귀 검증 모두 독립 통과. 한 PR 실패 시 다음으로 넘어가지 않고 롤백.

### PR 1 — FileManagementService **Phase 1** (안전한 유틸 메서드)

**난이도**: 낮음. 외부 의존성 거의 없는 순수 유틸/조회 메서드만.

**대상 파일**
- 신규: `src/main/java/com/heapdump/analyzer/service/FileManagementService.java`
- 수정: `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`

**이전 대상 (분석 서비스 호출 의존성 0)**
- `listFiles()` (라인 ~2468–2509) — dumpfiles/ 목록
- `checkDuplicate(filename, size, hash)` (라인 ~2372–2428)
- `computePartialHash(file, bytes)` (라인 ~2430–2449)
- `generateUniqueName(filename, dir)` (라인 ~2451–2462)
- `isValidHeapDumpFile(name)` (라인 ~3449–3454)
- `stripExtension(name)` (라인 ~3456–3466)
- `getExtension(name)` (라인 ~3471–3474)
- 디렉토리 helper: `dumpFilesDirectory()` (라인 ~517–519)

**Facade 유지 전략**: `HeapDumpAnalyzerService` 의 동명 메서드는 그대로 두고, 내부적으로 `fileManagementService.xxx()` 위임 호출. **외부 시그니처 무변경** → Controller 측 수정 없음 (PR 1 의 PR 1 와 동일 패턴).

**완료 조건**
- `mvn clean package -DskipTests` 성공
- `bash restart.sh` 후 `Started HeapAnalyzerApplication` + `Restored N saved results from disk`
- 회귀: `/files` 페이지 정렬·검색, 업로드 중복 체크 (`POST /api/upload/check`)
- `git diff --stat`: HeapDumpAnalyzerService 라인 수 ~150 라인 감소 (3,581 → ~3,430)

---

### PR 2 — LlmConfigService

**난이도**: 중간. RAG 와 디커플링이 이미 컨트롤러 레벨에서 되어 있어 의외로 깔끔.

**대상 파일**
- 신규: `src/main/java/com/heapdump/analyzer/service/LlmConfigService.java`
- 수정: `HeapDumpAnalyzerService.java`
- 수정: `HeapDumpController.java` (4 호출 라인) / `AiChatController.java` (1 호출 라인)

**이전 대상**

**필드 (12 개, 라인 ~83–94)**
- `llmEnabled`, `llmProvider`, `llmApiUrl`, `llmModel`, `llmApiKey`
- `llmMaxInputTokens`, `llmMaxOutputTokens`
- `llmTimeoutConnectSeconds`, `llmTimeoutReadSeconds`
- `llmChatSystemPrompt`, `llmChatRestoreIncludeHistory`, `llmSslVerify`

**메서드**
- Getter/setter 17 개 (라인 ~1157–1222) — `getLlmProvider`/`setLlmEnabled`/`setLlmConfig`/`setLlmApiKey`/`getLlmApiKeyMasked`/`isLlmApiKeySet`/`setLlmSslVerify` 등
- `testLlmConnection()` (라인 ~1429–1512)
- `callLlmAnalysis(prompt)` (라인 ~1607–1750)
- `callLlmChat(messages, systemPrompt)` (라인 ~1807–1950)
- `callLlmChatStream(messages, systemPrompt, onDelta, onDone, onError)` (라인 ~1985–2100)
- `disableSslVerification()` (라인 ~1229–1243) — HTTPS SSL 비활성화 헬퍼

**Settings 영속화 (LlmConfigService 로 이동)**
- `loadPersistedSettings()` 의 LLM 12 필드 복원 부분 (라인 ~813–850)
- `persistSettings()` 의 LLM Map 저장 부분 (라인 ~993–1004)
- `syncApplicationProperties()` 의 `llm.*` 키 11 개 부분 (라인 ~1065–1075)
- 환경변수 `LLM_API_KEY` override (라인 ~164–167)

**호출자 변경**
- `HeapDumpController`:
  - L1554 `analyzerService.testLlmConnection()` → `llmConfigService.testLlmConnection()`
  - L1585 `callLlmAnalysis(prompt)` → 동일 이동
  - L1697 `callLlmChat(...)` → 동일
  - L1757 `callLlmChatStream(...)` → 동일
- `AiChatController` L328: `callLlmChatStream(...)` → 동일

**RAG 결합도**: 매우 낮음. RagService.fetchContextForLlm() 호출은 컨트롤러 레벨에서 일어남 → LLM 메서드의 `systemPrompt` 파라미터로만 전달. LlmConfigService 는 RAG 를 알 필요 없음.

**Facade 옵션**: 외부 API 호환성 유지 위해 `HeapDumpAnalyzerService` 에 위임 메서드 1 개씩 남길지 결정 — *권장: 위임 메서드 없이 컨트롤러가 LlmConfigService 직접 주입* (분리 의도 명확화).

**완료 조건**
- `/settings/llm` 페이지 — 4 provider 중 1 개로 connection test 성공
- `/analyze/{filename}` → AI 인사이트 생성 + 플로팅 채팅 (멀티턴 + 스트리밍) 정상
- `/ai-chat` 세션 사이드바 — 신규 세션 생성, 스트리밍 응답
- `git diff --stat`: HeapDumpAnalyzerService 라인 수 ~700 라인 감소

---

### PR 3 — RagConfigService

**난이도**: 높음. `RagService` (581 라인) 와 `EmbeddingService` (233 라인) 가 현재 `HeapDumpAnalyzerService` 를 주입받아 getter 호출. DI 재배선 필요.

**대상 파일**
- 신규: `src/main/java/com/heapdump/analyzer/service/RagConfigService.java`
- 수정: `HeapDumpAnalyzerService.java`
- 수정: `RagService.java`, `EmbeddingService.java` (생성자 주입 변경)
- 수정: `HeapDumpController.java` (setRagConfig 등 호출 위치)

**이전 대상**

**필드 (26 개, 라인 ~97–128)**
- 기본: `ragEnabled`/`ragElasticsearchUrl`/`ragAuthType`/`ragUsername`/`ragPassword`/`ragApiKey`/`ragIndex` (7)
- 검색: `ragSslVerify`/`ragSearchMode`/`ragTextField`/`ragTopK`/`ragMinScore`/`ragTimeoutSeconds` (6)
- 청킹: `ragChunkingEnabled`/`ragChunkingStrategy`/`ragChunkingSize`/`ragChunkingOverlap`/`ragChunkingMaxChunksPerDoc`/`ragChunkingMaxTotalChars` (6)
- Semantic-server: `ragSemanticQueryType`/`ragSemanticModelId`/`ragSemanticTokensField`/`ragSemanticField` (4)
- Embedding/kNN: `ragEmbeddingProvider`/`ragEmbeddingApiUrl`/`ragEmbeddingApiKey`/`ragEmbeddingModel`/`ragEmbeddingDimension`/`ragEmbeddingTimeoutSeconds`/`ragKnnVectorField`/`ragKnnNumCandidates` (8)

**메서드**
- Getter 28 개 (라인 ~1246–1279)
- 마스킹 3 개 (라인 ~1285–1300): `getRagPasswordMasked`/`getRagApiKeyMasked`/`getRagEmbeddingApiKeyMasked`
- Setter: `setRagEnabled` (L1303), `setRagConfig` (L1313–1332), `setRagSemanticConfig` (L1338–1350), `setRagEmbeddingConfig` (L1356–1373), `setRagChunkingConfig` (L1376–1390)
- 암호화: `encryptForStorage()` (라인 ~1395–1403) — AesEncryptor wrapper

**Settings 영속화 (RagConfigService 로 이동)**
- `loadPersistedSettings()` RAG 복원 부분 (라인 ~852–895) — `AesEncryptor.decryptIfEncrypted()` 호출 포함
- `persistSettings()` RAG 저장 (라인 ~1006–1018, ~1026–1029, ~1031–1038) — ENC 암호화 포함
- `syncApplicationProperties()` `rag.*` 키 부분 (라인 ~1080–1100)

**호출자 재배선 (중요)**
- `RagService` 생성자 (라인 36–39): `HeapDumpAnalyzerService analyzerService` → `RagConfigService ragConfig`
  - `fetchContextForLlm()` 의 6 개 getter (`isRagEnabled`/`isRagChunkingEnabled`/`getRagChunkingMaxTotalChars` 등) → `ragConfig.xxx()`
  - `search()` 12 개 getter (L224–238) → `ragConfig.xxx()`
  - `testConnection()` 7 개 (L324–331) → `ragConfig.xxx()`
  - `buildSemanticQuery()` 3 개 (L419–421) → `ragConfig.xxx()`
- `EmbeddingService` 생성자 (L40–42): 동일 패턴, 5 개 getter (L60–64) → `ragConfig.xxx()`
- `HeapDumpController` 의 `setRagConfig`/`setRagEmbeddingConfig` 등 호출 → `ragConfigService.xxx()`

**위험 포인트**
- `ragPassword`/`ragApiKey`/`ragEmbeddingApiKey` 의 ENC 암호화 흐름: settings.json 저장 시 ENC, 메모리는 평문. RagConfigService 이전 시 동일 패턴 유지 확인 필수.
- 환경변수 override (있다면) 검증.

**완료 조건**
- `/settings/rag` 페이지 — semantic-server / semantic-client / keyword 3 가지 모드로 연결 테스트 성공
- 분석 페이지 AI 채팅 → RAG context 가 systemPrompt 끝에 주입되는지 (`[참고 자료 (RAG)]` 마커 확인)
- settings.json 의 `rag.password`/`rag.apiKey`/`rag.embedding.apiKey` 가 `ENC(...)` 형식 유지
- `git diff --stat`: HeapDumpAnalyzerService 라인 수 ~600 라인 감소

---

### PR 4 — FileManagementService **Phase 2** (I/O 메서드)

**난이도**: 중간. 분석 서비스가 일부 호출하지만 위임 가능.

**대상 파일**
- 수정: `FileManagementService.java` (PR 1 에서 신규 생성된 클래스에 추가)
- 수정: `HeapDumpAnalyzerService.java`

**추가 이전 대상**
- `uploadFile(MultipartFile)` (라인 ~2319–2368)
- `deleteFile(filename)` (라인 ~2566–2634) — heap dump 만 삭제
- `getFile(filename)` (라인 ~2542–2564) — 다운로드용
- `compressDumpFile(file)` (라인 ~3342–3405) — gzip
- `decompressDumpFile(file)` (라인 ~3411–3447)
- `cleanupDuplicateGzFiles(files)` (라인 ~2515–2540)
- `findReportZip(filename, type)` (라인 ~3310–3315)
- `hasReportZip(filename)` (라인 ~3317–3319)
- 디렉토리 helper: `tmpDirectory()` (L513–515), `resultDirectory(filename)` (L3299–3301), `resultJsonFile(filename)` (L3302–3304)
- 마이그레이션 (private 유지, 일회성): `migrateOldResultDirs` (L410–445), `migrateDumpFilesToNewDir` (L491–509), `migrateStrayArtifacts` (L465–489), `copyDirectoryRecursively` (L447–460)

**분석 서비스에 남기는 메서드 (분석 파이프라인 결합 높음)**
- `cleanupTmpDir()` (라인 ~704–716) — analyzeWithProgress 마무리
- `moveZipsToResultDir()` (라인 ~3476–3509) — MAT 결과 이동
- `moveArtifactsToResultDir()` (라인 ~3510–3534) — .index/.threads 이동
- `deleteHistory()` (라인 ~2641–2710) — DB 트랜잭션 + FS 삭제 혼합 → DB 부분은 분석 서비스, FS 부분만 FileManagementService 위임

**호출자 변경**
- `HeapDumpController` 의 `uploadFile`/`deleteFile`/`getFile`/`listFiles` 호출 → 모두 facade 위임으로 무변경 처리 가능

**완료 조건**
- 업로드 (50MB+ heap dump) → dumpfiles/ 도착 → 분석 큐 → SSE 진행 → 결과 + 자동 gzip 압축 확인
- `/files` bulk-delete (heap dump 만)
- `/history` bulk-delete (heap dump + 분석 기록)
- `/download/{filename}` 정상 (gz 포함)
- `git diff --stat`: HeapDumpAnalyzerService 라인 수 ~600 라인 추가 감소

---

### PR 5 (선택) — AiInsightManager 분리

**난이도**: 낮음. 단순하지만 부피 작아 후순위.

**대상 파일**
- 신규: `src/main/java/com/heapdump/analyzer/service/AiInsightManager.java`
- 수정: `HeapDumpAnalyzerService.java`

**이전 대상 (라인 ~1522–1599)**
- `saveAiInsight(filename, data)` — DB + 파일 폴백
- `loadAiInsight(filename)` — DB + 파일 폴백
- `deleteAiInsight(filename)` — DB + 파일 정리
- `migrateAiInsightsToDb()` (라인 ~328–362) — 마이그레이션 (일회성)

**호출자**
- `HeapDumpAnalyzerService.deleteHistory()` 내부 호출 (라인 ~2702–2706) → `aiInsightManager.deleteAiInsight()` 위임
- `HeapDumpController` (AI 인사이트 저장/조회 엔드포인트)

**완료 조건**
- 분석 후 AI 인사이트 생성 + 페이지 표시
- 분석 기록 삭제 시 AI 인사이트도 삭제

**보류 결정 권한**: PR 1~4 완료 후 라인 수 감축이 충분하면 PR 5 는 별도 사이클로 이연 가능.

---

## 의존성 그래프

```
PR 1 (FileMgmt Phase 1)
  ↓ (FileManagementService 클래스 존재 필요)
PR 4 (FileMgmt Phase 2)        ← PR 2/3 와 독립

PR 2 (LlmConfig)               ← 다른 PR 과 독립
PR 3 (RagConfig)               ← 다른 PR 과 독립 (RagService/EmbeddingService 재배선만)
PR 5 (AiInsight, 선택)         ← 다른 PR 과 독립
```

권장 진행 순서: **PR 1 → PR 2 → PR 3 → PR 4 → PR 5**.

PR 1 을 pilot 로 우선 진행해 facade 패턴/생성자 주입/회귀 테스트 절차를 검증한 뒤 나머지로 확장.

---

## 회귀 체크리스트 (전 PR 공통)

각 PR 마무리에 다음 시퀀스 실행:

```bash
mvn clean package -DskipTests \
  && bash restart.sh \
  && sleep 18 \
  && grep -E "Started HeapAnalyzerApplication|FAILED|Exception in thread" \
       /opt/genspark/webapp_dump/logs/heapdump-analyzer.log | tail -3
```

추가 회귀 체크 (PR 별로 다름):

| 항목 | PR 1 | PR 2 | PR 3 | PR 4 | PR 5 |
|---|:---:|:---:|:---:|:---:|:---:|
| 로그인 (admin/shinhan@10) | ✓ | ✓ | ✓ | ✓ | ✓ |
| `/` 대시보드 KPI/차트 | ✓ | ✓ | ✓ | ✓ | ✓ |
| `/files` 정렬·검색·삭제 | ✓ | | | ✓ | |
| `/history` 정렬·삭제 | ✓ | | | ✓ | ✓ |
| `POST /api/upload/check` 중복 체크 | ✓ | | | ✓ | |
| 업로드 → 분석 → 결과 | | | | ✓ | |
| `/settings/llm` 테스트 호출 | | ✓ | | | |
| `/analyze/{f}` AI 인사이트 + 채팅 | | ✓ | ✓ | | ✓ |
| `/settings/rag` 3 모드 연결 테스트 | | | ✓ | | |
| RAG context systemPrompt 주입 | | | ✓ | | |
| `settings.json` ENC 형식 유지 | | ✓ | ✓ | | |
| `/admin/users` 3 탭 | ✓ | ✓ | ✓ | ✓ | ✓ |

---

## 라인 수 추적

| PR | HeapDumpAnalyzerService 변화 | 신규 클래스 라인 |
|---|---|---|
| 시작 | 3,581 | — |
| PR 1 | ~3,430 (–150) | FileManagementService ~180 |
| PR 2 | ~2,730 (–700) | LlmConfigService ~750 |
| PR 3 | ~2,130 (–600) | RagConfigService ~650 |
| PR 4 | ~1,530 (–600) | FileManagementService +600 |
| PR 5 | ~1,430 (–100) | AiInsightManager ~120 |

PR 1~5 완료 후 `HeapDumpAnalyzerService` 약 **1,430 라인 (-60%)** — 분석 + executor + queue + 캐시 위임만 잔존.

---

## CHANGELOG

각 PR 마무리마다 `CHANGELOG.md` 에 `[YYYY-MM-DD] Phase 7-N — ...` entry 추가 (Phase 6 와 구분).

---

## 참고

- 분석 결과 (Explore 에이전트 출력) 기반 — 라인 번호는 가이드 (실제 변경 시 grep 으로 정확 재확인).
- `util/AesEncryptor.java` — ENC 암복호화. PR 3 RagConfigService 에서 활용.
- `util/FormatUtils.java`, `util/HtmlSanitizer.java`, `util/FilenameValidator.java` — Phase 1~3 의 utility, 본 작업 영향 외.
- `service/HeapAnalysisResultCache.java` — Phase 6-1 신규 클래스, 본 작업 영향 외.
- `RagService.java` / `EmbeddingService.java` — PR 3 에서 생성자 시그니처 변경 (DI 재배선).
