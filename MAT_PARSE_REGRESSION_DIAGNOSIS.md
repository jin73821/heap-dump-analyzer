# MAT 파싱 속도 회귀(5분 → 17분) 진단 세션 기록

> 작성일: 2026-06-30 · 대상 회귀: 8GB 힙덤프 MAT 파싱이 기존 5분 이내 → 약 17분으로 3배 증가
> 관련 배포: `78f2898 feat: Dominator 트리 reconnect 수정 + MAT 동적 동시성 게이트 + Loaded Classes 최적화`
> 플랜 파일: `/root/.claude/plans/2026-06-30-12-24-48-908-pool-2-thread-2-declarative-lantern.md`

---

## 1. 세션 진행 요약

| 단계 | 내용 | 상태 |
|------|------|------|
| 원인 조사 | `78f2898` diff 전수 확인 — 파싱 코드 미변경 결론 | ✅ 완료 |
| 진단 계측(B-3) | 슬롯 대기 vs MAT 실제 파싱 시간 분리 + 가용메모리 로깅 추가 | ✅ 코드 반영·컴파일 통과 |
| CHANGELOG | `[2026-06-30]` 항목 기록 | ✅ 완료 |
| **사내망 배포·계측** | 운영 빌드 반영 후 회귀 재현 로그 수집 | ⏳ **사용자 진행 예정** |
| 경합 점검 | 수집 로그 분석 → H1(경합) 확정/반증 | ⏳ 로그 수령 후 |
| 후속 조치(B-1/B-2) | 경합 확정 시 동시 한도 조정 | ⏳ 점검 후 |

---

## 2. 원인 분석 (현재까지 결론)

### 2-1. 파싱 코드는 바뀌지 않았다
`78f2898` 의 `HeapDumpAnalyzerService.java` diff 를 전수 확인한 결과, **메인 분석 MAT 호출 경로(`runMatCliWithProgress`)의 파싱 비용에 영향을 주는 코드는 전혀 변경되지 않음**:
- MAT 커맨드(overview/top_components/suspects/`dominator_tree` query) 동일
- MAT `-Xmx`(MemoryAnalyzer.ini), `-keep_unreachable_objects`, GC 플래그, `MALLOC_ARENA_MAX=2` 모두 동일
- 추가된 것: ① 전역 `matSlots` 동시성 게이트, ② 분석 후 DomRefs `precompute`(백그라운드), ③ lazy 경로 symlink/`alignHprofMtimeToIndex`

→ 파싱 3배 증가는 **파싱 알고리즘이 아니라 자원(메모리/IO) 경합** 때문이며, 그 경합을 처음 만들어낸 것이 ①+② 다.

### 2-2. 유력 메커니즘 (H1)
- **`78f2898` 이전:** 한 번에 MAT 프로세스는 **분석 1개만** 실행(precompute 자체가 없었음). 분석 MAT 가 호스트 RAM·페이지캐시·IO 독점.
- **`78f2898` 이후:** `computeMaxConcurrentMat()` 가 `min(floor((hostRAM×0.8 − appXmx)/matXmx), cpus)` 로 동시 한도 **N** 산정. RAM 큰 운영 호스트면 **N ≥ 2** → 직전 덤프의 **precompute MAT**(큰 `-Xmx` + tmp 에 8GB 재해제본 `.precompute.hprof` 가 페이지캐시 점유, 분석 후 ~2.4분 지속)가 **다음 덤프의 분석 MAT 와 동시 실행** 가능. 큰 MAT JVM 2개 + 8GB hprof 2벌이 물리 RAM 초과 → **스왑/페이지캐시 축출 → 분석 파싱 thrashing(5분→17분)**.

> 최초 제공 로그(12:24–12:42, 단일 덤프)에는 동시 MAT 가 안 보였음 → 회귀는 **덤프 연속/동시 처리 시** 나타난다는 가설.

### 2-3. 보조 가설 (확인 필요)
- **H2 — MAT `-Xmx` 부족:** 8.5GB hprof·90M objects·keep_unreachable 를 작은 MAT 힙(예: 2GB)으로 파싱 시 GC thrash. ("5분 baseline" 이 더 큰 `-Xmx` 시절이었을 가능성)
- **H3 — keep_unreachable_objects:** 로그상 ENABLED. 도달불가 객체까지 보존 → 파싱 비용 급증. 배포와 무관하게 토글되었을 수 있음.

---

## 3. 이번에 반영한 계측 (B-3, 동작 불변)

**파일:** `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`

1. **신규 헬퍼** `hostAvailableRamMb()` — `/proc/meminfo` 의 `MemAvailable` 를 MB 로 반환(실패 시 -1).
2. **`runMatCliWithProgress()`** 의 `matSlots.acquire()` 전후를 계측:
   - spawn 직전: `[MAT Concurrency] 분석 '{file}' MAT spawn 직전 — 한도={N}, 가용슬롯={}, 가용메모리={}MB`
   - 완료 후: `[MAT CLI] 파싱 분리 측정 — 슬롯 대기={}ms, MAT 서브프로세스={}ms, 종료후 가용메모리={}MB, 파일={}`

> 기존엔 `[Analysis] Done ... in {}ms` 단일 합산값(큐대기+copy+MAT+parse)뿐이라 "파싱이 느림"의 실제 원인(슬롯 대기 vs 연산/메모리 thrashing)을 구분 못 했음. 이제 **슬롯 대기 ms 와 MAT 서브프로세스 실제 wall time ms 가 분리**되어 로그에 남는다.

precompute·gzip 동작은 **변경하지 않음**(사용자 요구).

---

## 4. 사내망 배포 절차 (사용자 수행)

```bash
# 운영 디렉토리에서
mvn clean package -DskipTests        # 빌드 (10~13초)
bash restart.sh                       # 18080 재기동
# 기동 검증
sleep 18 && grep -E "Started HeapAnalyzerApplication|FAILED|Exception in thread" \
  logs/heapdump-analyzer.log | tail -3
```
⚠️ 버전(JAR명) 변경은 없으므로 포트 충돌 이슈는 해당 없음.

**기동 직후 반드시 1줄 확보** (동시 한도 N 의 근거값):
```bash
grep "\[MAT Concurrency\] 동시 MAT 프로세스 한도" logs/heapdump-analyzer.log | tail
```

---

## 5. 회귀 재현 & 로그 수집 가이드

H1(경합)을 드러내려면 **단일 덤프가 아니라 연속/동시 처리**가 핵심:

1. (권장) **대용량 덤프 2개를 연속 분석.** 첫 덤프 분석 완료 직후 precompute(~2.4분)가 도는 동안 **둘째 덤프 분석을 시작**. 둘째 덤프의 파싱 시간이 느려지는지가 핵심.
2. 또는 평소처럼 운영하다가 느린 분석이 1건 잡히면 그 파일명/시각 기록.

**가져올 로그(전체가 부담되면 아래 grep 결과):**
```bash
# 동시성/계측 핵심 라인 전부
grep -E "\[MAT Concurrency\]|\[MAT CLI\] 파싱 분리 측정|\[MAT CLI\] Completed|Running MAT CLI|Report phase|dom-ref-precompute|\[MAT Lazy\]|\[DomRefs\]|\[Analysis\] (Done|Queued|Dequeued)|\[Compress\]" \
  logs/heapdump-analyzer.log > /tmp/mat_regression_capture.log

# (가능하면) 느린 파싱 시점의 메모리/스왑
free -g ; vmstat 5 5
```
- 느린 분석의 `Running MAT CLI` ~ `Report phase: overview` 구간 시각, 그리고 같은 시간대에 **다른 파일의** `dom-ref-precompute` / `[MAT Lazy]` 가 겹쳤는지가 판정 포인트.

---

## 6. 로그 수령 후 경합 점검 체크리스트 (다음 세션에서 수행)

가져온 로그로 아래를 순서대로 확인 → H1/H2/H3 판정:

- [ ] **A. 동시 한도 N** — `[MAT Concurrency] 동시 MAT 프로세스 한도 = N (matXmx=.., appXmx=.., hostRAM=.., cpus=..)`.
      `N == 1` 이면 경합 불가 → H1 기각, H2/H3 로 이동. `N ≥ 2` 면 경합 가능.
- [ ] **B. 슬롯 대기 vs 실제 파싱** — 느린 분석의 `[MAT CLI] 파싱 분리 측정` 라인:
      - `슬롯 대기` 가 크면 → 다른 MAT(precompute/lazy)에 막힌 것(직접 경합).
      - `슬롯 대기` ≈ 0 인데 `MAT 서브프로세스` 가 크면 → **메모리 thrashing**(C 로 확증) 또는 H2/H3.
- [ ] **C. 메모리 압박** — `spawn 직전 ... 가용메모리={}MB` 와 `종료후 가용메모리={}MB`, 그리고 `free`/`vmstat` 의 swap-in(si/so).
      파싱 중 가용메모리 급감 + 스왑 발생 → **thrashing 확정**.
- [ ] **D. 시간대 겹침** — 느린 분석 파싱 구간에 다른 파일의 `dom-ref-precompute 전용 해제` / `[MAT Lazy] Query` 가 동시에 찍혔는지.
- [ ] **E. MAT `-Xmx` 적정성** — `matXmx` 가 덤프 live(예: 5.1GB)/객체수(90M) 대비 과소하면 H2.
- [ ] **F. keep_unreachable** — `MAT option: -keep_unreachable_objects ENABLED` 가 5분 baseline 시절에도 있었는지(H3).

**판정 → 조치 매핑:**
- N≥2 + (B 슬롯대기 큼 또는 C 스왑/D 겹침) → **H1 확정** → §7 B-1 적용·검증.
- N==1 또는 (B 슬롯대기≈0 + C 스왑없음) → H1 기각 → E/F 로 H2/H3 점검 → MAT `-Xmx` 상향 또는 keep_unreachable 검토.

---

## 7. 후속 조치 (점검 결과에 따라)

- **B-1 (1순위, 저위험):** 경합(H1) 확정 시 외부 `application.properties` 에 `mat.max-concurrent-processes=1` → 재기동. `computeMaxConcurrentMat()` override(>0) 분기로 **모든 MAT 직렬화**(구 동작 복원). 동일 덤프 재분석 파싱이 ~5분 복귀하면 H1 최종 확정. precompute/gzip 은 그대로 수행되되 분석 MAT 와 동시 실행만 안 됨.
- **B-2 (2순위, 근본):** `computeMaxConcurrentMat` 의 RAM 여유 계수 보수화(0.80→0.65 등) + `doPrecomputeDominatorRefs` 진입부에서 foreground 분석 활성 시 precompute MAT spawn 양보/연기(결과물 불변, 타이밍만 이동).
- **B-4 (조건부):** H2 면 `MemoryAnalyzer.ini -Xmx` 상향(설정 UI `setMatHeapSize` → 자동 재산정). H3 면 `/settings` keep_unreachable 토글 검토.

---

## 8. 변경 파일 인덱스
- `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java` — `hostAvailableRamMb()` 신규, `runMatCliWithProgress()` 계측 2곳
- `CHANGELOG.md` — `[2026-06-30]` 항목
- (이 문서) `MAT_PARSE_REGRESSION_DIAGNOSIS.md`
