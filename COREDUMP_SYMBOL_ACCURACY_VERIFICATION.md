# 코어덤프 별도 서버 분석 — 심볼 정확도 검증 보고서

- **검증일:** 2026-08-22
- **질문:** 원격(운영) 서버에서 직접 분석하지 않고, 코어파일+실행파일만 분석 서버로 가져와 GDB 분석을 수행해도 정확도에 문제가 없는가? 특정 환경변수·라이브러리 등이 추가로 필요한가?
- **환경:** 분석 서버 Rocky Linux 8.10 / GNU gdb 8.2 / glibc 2.28 / gcc 8.5 — 운영 분석기와 동일 호스트에서 통제 실험.
- **방법:** 커스텀 공유 라이브러리(`libcrashy.so`) 내부에서 SIGSEGV 하는 C 프로그램으로 코어 생성 후, **라이브러리 파일 상태만 바꿔가며** 동일 코어·동일 GDB 인자로 백트레이스를 비교. 시스템 `core_pattern`(systemd-coredump 파이프)·`ulimit -c 0` 을 건드리지 않기 위해 코어는 `gdb generate-core-file` 로 생성.

## 결론 요약

| 항목 | 결론 |
|---|---|
| **환경변수** | **불필요 (확정).** 크래시 프로세스의 환경변수는 코어 메모리에 이미 포함되며(`strings core \| grep MYENV_MARKER` = 1건 실존), GDB 를 `env -i`(환경변수 전무)로 실행해도 백트레이스가 **바이트 단위로 동일**했다. 분석 서버의 환경변수는 결과에 어떤 영향도 없다. |
| **공유 라이브러리 — 없음** | `Syms Read=No` + `??` 프레임 + 명시적 warning. 현행 앱의 품질 경고(`assessAnalysisQuality`)가 이미 커버하는 **정직한 실패**. |
| **공유 라이브러리 — 같은 경로에 다른 빌드** | ⚠ **최대 리스크 (실증).** gdb 8.2 는 build-id 를 검증하지 않아 **경고 0건**으로 가짜 함수명·가짜 인자값·가짜 소스 라인을 출력한다. `Syms Read=Yes` 라 신뢰할 수 있는 결과처럼 보인다. |
| **sysroot 번들** | `-iex "set sysroot <번들>"` 로 원본 서버 라이브러리를 절대경로 미러 구조로 제공하면 **정확도 완전 복원**. 단 번들에 누락된 라이브러리는 호스트 폴백이 사라져 `No` 가 되므로 **전체 라이브러리 수집이 필요**. |
| **실행파일(exec)** | 코어 단독으로는 GDB 가 링크맵에 접근하지 못해 공유 라이브러리를 아예 로드하지 않는다(sysroot 도 무력). **exec 페어링이 라이브러리 심볼 해석의 전제조건** — 현행 EXEC_MISSING 안내와 일치. |

**종합:** 별도 서버 분석은 ① 실행파일 페어링 + ② 원본 서버 공유 라이브러리 번들(sysroot) 이 갖춰지면 원본 서버 직접 분석과 **동일한 정확도**를 낸다. 환경변수·OS 설정은 불필요. 현행 구현(코어+exec 만 전송)은 라이브러리 프레임에서 ②의 공백이 있으며, 특히 "같은 경로 다른 버전" 상황은 경고 없이 틀리므로 번들 수집 기능이 필요하다.

---

## 실험 상세

`$S` = 실험 디렉토리. 픽스처:

- `libcrashy_v1.c` — `lib_entry() → lib_crash_here()` 에서 NULL 쓰기 SIGSEGV. **크래시 당시 실제 로드된 빌드.**
- `libcrashy_v2.c` — 같은 이름의 함수들 + 순서·크기를 바꾼 **재컴파일 빌드** (코드 시프트 유발). "분석 서버에 같은 경로로 존재하는 다른 버전" 역할.
- build-id 상이 확인 (`readelf -n`): v1 `5df9a49d…`, v2 `cc737a7a…`
- 코어 생성: `MYENV_MARKER=verify123 LD_LIBRARY_PATH=$S/livepath gdb --batch --nx -ex run -ex "generate-core-file $S/out/core.v1" $S/out/crasher`

### 케이스 A — 라이브러리 원경로 실존 (원본 서버 직접 분석과 동일 조건)

```
gdb --batch --nx -ex "set pagination off" crasher core.v1 -ex "info sharedlibrary" -ex bt
```
```
0x…570  0x…672  Yes         $S/livepath/libcrashy.so
0x…c40  0x…4bd  Yes (*)     /lib64/libc.so.6
#0  0x00007ffff7bcb64e in lib_crash_here () at …/libcrashy_v1.c:2
#1  0x00007ffff7bcb66f in lib_entry () at …/libcrashy_v1.c:4
#2  0x000000000040061f in main () at …/main.c:2
```
→ 기준선. 정확한 크래시 지점.

### 케이스 B — 라이브러리 제거 (분석 서버에 경로 없음)

```
warning: Could not load shared library symbols for $S/livepath/libcrashy.so.
                    No          $S/livepath/libcrashy.so
#0  0x00007ffff7bcb64e in ?? ()
#1  0x00007fffffffdbb0 in ?? ()
#2  0x00007ffff7bcb66f in ?? ()
#4  0x000000000040061f in main () at …/main.c:2
```
→ `??` 로 정직하게 실패 + 스택 스캔 노이즈 프레임(#1, 스택 주소) 유입. 현행 앱의 `Syms Read=No` 경고·GARBAGE 분류가 커버하는 상황.

### 케이스 C — 같은 경로에 다른 빌드 (v2) ⚠ 핵심

```
0x…5a0  0x…6ee  Yes         $S/livepath/libcrashy.so     ← Yes! 신뢰돼 보임
#0  0x00007ffff7bcb64e in __do_global_dtors_aux () from $S/livepath/libcrashy.so
#1  0x00007fffffffdbb0 in ?? ()
#2  0x00007ffff7bcb66f in totally_different_fn (x=0) at …/libcrashy_v2.c:2
```
- 실제 크래시 함수 `lib_crash_here` → **`__do_global_dtors_aux` 로 둔갑**
- 콜러 `lib_entry` → **`totally_different_fn (x=0)`** — 존재하지도 않는 호출에 **인자값(x=0)과 소스 파일·라인까지 날조**
- `grep -icE "warn|build.?id|mismatch"` = **0건.** gdb 8.2 는 build-id 불일치를 침묵.

→ **"분석 서버에 같은 경로의 라이브러리가 있는" 경우가 "없는" 경우보다 위험하다.** 예: 원본 RHEL7(glibc 2.17) 코어를 분석기 Rocky8(glibc 2.28)에서 열면 `/lib64/libc.so.6` 프레임이 조용히 오심볼된다.

### 케이스 D — sysroot 번들로 복원

번들 = 절대경로 미러 구조(`$S/bundle` + 원경로 그대로). 원경로는 비운 상태.

```
gdb --batch --nx -iex "set sysroot $S/bundle" -ex "set pagination off" crasher core.v1 -ex "info sharedlibrary" -ex bt
```
```
0x…570  0x…672  Yes  $S/bundle$S/livepath/libcrashy.so   ← 번들에서 해석
                No   /lib64/libc.so.6                    ← 번들에 없어 호스트 폴백 상실
#0  lib_crash_here () at …/libcrashy_v1.c:2              ← 케이스 A 와 동일 복원
#1  lib_entry () at …/libcrashy_v1.c:4
#2  main () at …/main.c:2
```
- 케이스 A 와 동일한 정확도 복원. **호스트에 같은 경로의 잘못된 빌드가 남아 있어도 sysroot 가 우선**함도 별도 실행으로 확인.
- ⚠ 부작용: sysroot 를 설정하면 번들에 없는 라이브러리(libc/ld 등)는 **호스트 폴백 없이 `No`** 가 된다 (`warning: Could not load shared library symbols for 2 libraries, e.g. /lib64/libc.so.6.`). → 번들에는 코어가 참조하는 **전체 라이브러리 + ld.so(인터프리터)** 를 담아야 한다.

### 케이스 D-2 — `-ex` 후행 순서 (positional 로드 뒤 `set sysroot`)

```
#0  0x00007ffff7bcb64e in ?? ()          ← 초기 정지 프레임: sysroot 적용 전 해석(오염)
#0  lib_crash_here () at …libcrashy_v1.c:2   ← 이후 bt: set sysroot 가 solib 재해석 트리거
```
- gdb 8.2 는 `set sysroot` 변경 시 solib 를 **다시 읽으므로** 후행 명령은 복구된다. 그러나 초기 출력이 오염되고, 호스트 경로를 한 번 읽은 뒤 다시 읽는 이중 비용이 있다 (호스트에 잘못된 빌드가 있으면 초기 프레임 라인이 케이스 C 처럼 오심볼로 찍힘도 확인).
- → **구현은 `-iex`(파일 로드 전 실행) 채택** — 결정적이고 출력이 깨끗하다.

### 케이스 D-3 — 코어 단독 (exec 미페어링)

```
gdb --batch --nx -ex "set sysroot $S/bundle" -ex "core-file core.v1" -ex bt
#0~#11 전부 ?? ()
```
- 실행파일이 없으면 GDB 가 동적 링커의 링크맵(r_debug)에 접근하지 못해 **공유 라이브러리를 아예 로드하지 않는다** — sysroot 가 있어도 무력.
- → exec 페어링이 라이브러리 심볼 해석의 전제조건. 현행 `guidanceKind=EXEC_MISSING` 안내가 정확하며, sysroot 추가는 코어 단독 분기에서도 무해(no-op).

### 케이스 F — 개별 라이브러리 파일만 있을 때 (`solib-search-path`) — 2026-08-22 추가

번들에 **절대경로 미러 구조 없이** `libcrashy.so` 하나만 평평하게 둔 상태(= 운영자가 `.so` 파일 하나만 업로드). 분석 서버 원경로에는 잘못된 빌드(v2)가 있는 조건.

```
gdb --batch --nx -iex "set sysroot $S/bundle" -iex "set solib-search-path $S/bundle" \
    crasher core.v1 -ex "info sharedlibrary" -ex bt
```
```
0x…570  0x…672  Yes  $S/bundle/libcrashy.so       ← 미러 구조 없이도 basename 으로 찾음
#0  lib_crash_here () at …/libcrashy_v1.c:2       ← 원경로의 v2 를 제치고 정확 해석
#1  lib_entry () at …/libcrashy_v1.c:4
```

**F-2 — `solib-search-path` 단독은 무효 (중요):** sysroot 없이 `-iex "set solib-search-path …"` 만 주면 gdb 가 **코어에 기록된 원경로 파일을 먼저 찾아버려** 잘못된 v2 심볼이 그대로 나온다(케이스 C와 동일 출력). 즉 두 옵션은 **반드시 함께** 줘야 하며, 이 사실을 모르면 "설정했는데 왜 안 먹지"로 헤매게 된다.

→ 이 결과를 근거로 업로드에서 형식·구조 제약을 없앴다. 원본 경로를 모르는 개별 `.so`, 구조 없이 압축된 아카이브 모두 번들에 담기면 `set solib-search-path`(번들 내 파일 보유 디렉토리 목록)로 탐색된다. 미러 구조가 있으면 sysroot 가 먼저 정확 경로로 매칭하고, 없으면 basename 으로 폴백하는 2단 구조다.

### 케이스 E — 환경변수 무관성

```
strings core.v1 | grep -c MYENV_MARKER   → 1   (크래시 프로세스 env 는 코어 안에 있다)
env -i /usr/bin/gdb --batch --nx crasher core.v1 -ex bt
diff (케이스 A bt) (env -i bt)           → 동일 (IDENTICAL)
```
- 추가 통제: 파일시스템 상태를 고정(v2 잔존)한 채 전체 env vs `env -i` 를 비교했을 때도 출력이 완전 동일 — 차이를 만드는 변수는 **오직 라이브러리 파일 상태**뿐임을 교차 확인.

---

## 설계 반영 (v2.3.6 고도화)

1. **sysroot 번들 지원** — `{coredump.directory}/sysroots/{coreFilename}/`. 존재 시 `buildGdbCommand()` 가 `-iex "set sysroot …"` + `-iex "set solib-search-path …"` 부착 (D/D-2/F 근거).
2. **번들 수집 2경로** — ① 원격 자동: 1차 분석 result.json 의 공유 라이브러리+매핑 경로 목록을 출처 서버에서 `tar -czh`(심볼릭 링크 실체화) 스트리밍 수집. ② 수동 업로드: **형식 무관** — tar/tar.gz/tgz/tar.bz2/tar.xz/zip 아카이브 + 개별 `.so` 파일, 여러 개 동시 (매직 바이트로 판정, zip-slip/링크/폭탄 가드).
3. **전체 수집 원칙** — 코어가 참조하는 모든 `.so` + ld.so 를 번들에 포함 (D 의 호스트 폴백 상실 근거). 누락분은 "번들에 없는 라이브러리 N건" 경고.
4. **신설 경고** — 원격 출처 코어가 번들 없이 분석 서버 로컬 라이브러리로 심볼 해석(`Syms Read=Yes`)된 경우: "버전 불일치 시 잘못된 함수명이 경고 없이 표시될 수 있음" (C 근거 — 이 상황은 GDB 가 침묵하므로 앱이 대신 경고해야 한다).
5. **exec 페어링 유지** — 코어 단독 한계(D-3)는 sysroot 로 해소되지 않음. 기존 EXEC_MISSING 안내 유지.

## 재현 커맨드 전문

```bash
S=<작업디렉토리>; mkdir -p "$S"/{src,livepath,bundle,out}
# 픽스처 소스는 본 문서 "실험 상세" 참조 (libcrashy_v1.c / libcrashy_v2.c / main.c)
gcc -shared -fPIC -g -O0 -o "$S/livepath/libcrashy.so" "$S/src/libcrashy_v1.c"
cp "$S/livepath/libcrashy.so" "$S/out/libcrashy_v1.so.bak"
gcc -shared -fPIC -g -O0 -o "$S/out/libcrashy_v2.so" "$S/src/libcrashy_v2.c"
gcc -g -O0 -o "$S/out/crasher" "$S/src/main.c" -L"$S/livepath" -lcrashy
MYENV_MARKER=verify123 LD_LIBRARY_PATH="$S/livepath" \
  gdb --batch --nx -ex run -ex "generate-core-file $S/out/core.v1" "$S/out/crasher"
# A: 그대로 실행 / B: mv livepath/libcrashy.so → 제거 / C: cp v2 → livepath
gdb --batch --nx -ex "set pagination off" "$S/out/crasher" "$S/out/core.v1" -ex "info sharedlibrary" -ex bt
# D: rm livepath/libcrashy.so; mkdir -p "$S/bundle$S/livepath"; cp v1.bak → 번들
gdb --batch --nx -iex "set sysroot $S/bundle" -ex "set pagination off" \
    "$S/out/crasher" "$S/out/core.v1" -ex "info sharedlibrary" -ex bt
# D-3: gdb --batch --nx -ex "set sysroot $S/bundle" -ex "core-file $S/out/core.v1" -ex bt
# E: strings "$S/out/core.v1" | grep -c MYENV_MARKER ; env -i /usr/bin/gdb --batch --nx "$S/out/crasher" "$S/out/core.v1" -ex bt
```
