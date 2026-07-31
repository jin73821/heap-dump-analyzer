# 시크릿 암호화 — 후속 항목

2026-07-31 AES 조용한 손상 대응(CHANGELOG 참조) 작업 중 확인됐으나 **범위 밖으로 남긴** 항목.
각 항목은 독립적으로 착수 가능하다. 우선순위는 3 → 1 → 2 순(3 이 나머지의 전제).

---

## 1. LLM API 키가 평문으로 디스크에 저장됨

**상태:** 미해결. 문서와 코드가 불일치.

`LlmConfigService` 는 `llmApiKey` 를 **암호화하지 않는다**. `AesEncryptor` 를 import 조차 하지 않는다.

| 위치 | 코드 | 문제 |
|---|---|---|
| `service/LlmConfigService.java:83` | `this.llmApiKey = config.getLlmApiKey();` | 복호화 단계 없음 |
| `:203` | settings.json 복원 시에도 그대로 | 동일 |
| `:240` | `settings.put("llmApiKey", llmApiKey)` | **평문으로 settings.json 저장** |
| `:257` | `updates.put("llm.api.key", ...)` | **평문으로 application.properties 저장** |

결과적으로 실제 Anthropic 키(`sk-ant-api03-…`)가 `/opt/heapdumps/data/settings.json` 과
`application.properties` 에 평문으로 존재한다. `.gitignore` 의 `*.properties` 덕에 커밋은 안 되지만
디스크에는 그대로 남는다.

**문서 불일치:** `CLAUDE.md` 의 AES 항목과 `README-DEPLOY.md:132,200,207` 은 API 키가 `ENC(...)` 로
암호화된다고 서술한다.

**조치 방향:** RAG/2FA 와 동일 패턴 적용 — `SecretValue` 로 전환하고 `putSecret()` 으로 저장.
`llm.api.key` 는 `HeapDumpConfig:152` 에서 `@Value` 바인딩만 있고 복호화 단계가 없으므로 함께 추가해야 한다.

> ⚠️ 전환 시 기존 평문 값의 마이그레이션 경로 필요. 평문을 그대로 두면 `ENC(` 접두가 없어
> `decryptIfEncryptedChecked` 가 `PLAIN` 으로 통과시키므로 동작은 유지되고, 다음 저장 때 암호화된다.
> 반대로 지금 상태에서 `ENC(...)` 를 직접 넣으면 **리터럴 문자열이 `x-api-key` 헤더로 전송된다**
> (`LlmConfigService:359,526,725,890,1080`).

---

## 2. `users.otp_secret` rekey 마이그레이션 도구 부재

**상태:** 미해결. 항목 3의 전제 조건.

OTP seed 는 사용자별로 DB(`users.otp_secret`)에 `ENC(...)` 로 저장된다(`TwoFactorService:156`).
`HEAP_ANALYZER_ENCRYPTION_KEY` 를 도입하면 **관리자가 이 값들을 재암호화할 방법이 없다** —
평문 seed 를 알 수 없기 때문. 결과는 **전 사용자 OTP 재등록**이다.

2026-07-31 수정으로 손상 시 앱이 죽지는 않는다(`TwoFactorService:101` 이 fail-closed 로 처리하고
`INVALID` 반환 + ERROR 로그, 잠금 카운트는 올리지 않음). 하지만 로그인 자체는 불가능하다.

**조치 방향:** 구 키로 복호화 → 새 키로 재암호화하는 일회성 CLI.
- `AesEncryptor.main` 에 `rekey` 액션 추가
- 구 키는 `HEAP_ANALYZER_ENCRYPTION_KEY_OLD` 환경변수로 주입
- 대상: `users.otp_secret` 전 행 + settings.json/application.properties 의 모든 `ENC(...)`
- 드라이런 모드 필수(운영 DB 직접 UPDATE 이므로)

---

## 3. `HEAP_ANALYZER_ENCRYPTION_KEY` 가 어디에도 설정돼 있지 않음

**상태:** 미해결. `README-DEPLOY.md:483` 의 미완료 체크박스.

저장소 전체(`restart.sh` / `run.sh` / `stop.sh` / `.bashrc` / systemd / Dockerfile)에 export 가 없다.
즉 현재 모든 시크릿이 **내장 기본 시드**(`AesEncryptor.DEFAULT_KEY_SEED`)로 암호화돼 있고,
소스를 가진 사람은 누구나 복호화할 수 있다. git 이력상 기본 시드는 한 번도 바뀐 적이 없다.

**도입 전 반드시 갖춰야 할 것:**

1. ~~`loadPersistedSettings()` 의 전량 리셋 지뢰 제거~~ — **2026-07-31 완료(L0)**.
   이전에는 키를 바꾸는 순간 AES 복호화 예외가 "깨진 JSON" 으로 오인돼
   settings.json 이 `.corrupted` 로 밀려나고 전 설정이 기본값으로 리셋됐다.
2. `spring.datasource.password` 를 **새 키로 미리 재암호화**. 안 하면 기동 실패
   (`DataSourceConfig` 는 의도적으로 fail-fast 유지, 다만 원인이 드러나는 메시지를 던진다).
3. 위 **항목 2의 rekey 도구**. 없으면 전 사용자 OTP 재등록.

RAG password / API key / embedding API key / SSO client secret 은 관리자가 UI 에서
재입력하면 되므로 별도 도구가 필요 없다(손상 시 `/settings/rag` 에 경고와 지우기 경로가 표시된다).

---

## 4. `findExternalPropertiesFile()` 이 소스 트리를 런타임 쓰기 대상으로 잡음

**상태:** 개발 환경 한정 불편. 운영 영향 없음.

`HeapDumpAnalyzerService.findExternalPropertiesFile()` 은 ① JAR 디렉터리 ② `user.dir`
③ `src/main/resources/application.properties` 순으로 탐색한다. 현 개발 환경에서는 ①②가 없어
**소스 트리 파일이 런타임에 수정된다**.

2026-07-31 의 churn 제거로 "매 기동 수정"은 사라졌지만(값이 안 바뀌면 파일도 안 바뀜),
설정 변경 시에는 여전히 소스 파일이 수정된다.

**추가 함정(실측):** Spring 이 부팅 시 읽는 것은 **JAR 내부 사본**(`BOOT-INF/classes/application.properties`)
이다. 소스 트리 파일만 고치면 `RagConfigService.init()` 단계에서는 여전히 옛 값을 읽고,
그 뒤 `applyFromSettings`(settings.json)가 덮어쓴다. 시크릿을 파일로 직접 정리할 때는
**재빌드까지 해야** 기동 로그가 깨끗해진다.

---

## 참고 — 2026-07-31 에 해결된 것

이 문서에 없는 아래 항목은 이미 조치됐다. 상세는 `CHANGELOG.md` 2026-07-31 항목 참조.

- `AesEncryptor` 의 마커 없는 64 HEX 모호 구간 → 조용한 데이터 손상 (v2 마커 + 위생 검사 + 자동 복구)
- 손상 평문의 재암호화 세탁 루프 (`SecretValue` 가 로드 당시 암호문 보존)
- `encryptForStorage()` 가 암호화 실패 시 `""` 반환 → 시크릿 무경고 삭제 (`putSecret` 이 키 생략)
- `@PostConstruct` 복호화 미보호 → 선택 기능 때문에 앱 전체 기동 실패
- 손상 상태가 UI 에 정상으로 표시되던 문제 + 시크릿을 지울 수 없던 문제
