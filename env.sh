#!/bin/bash
# ============================================================
# env.sh — run.sh / stop.sh / restart.sh 공통 설정 + 헬퍼
# ============================================================
#
# 사용법: 각 스크립트 상단에서 source 한다.
#     SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
#     . "$SCRIPT_DIR/env.sh"
#
# 원칙:
#   1. 리터럴 하드코딩 금지 — 모든 값은 여기서 변수로 선언한다.
#   2. 모든 변수는 `VAR="${VAR:-기본값}"` 패턴이라 환경변수로 override 가능하다.
#      예) SERVER_PORT=18081 bash run.sh
#   3. JAR 버전은 어디에도 적지 않는다. target/ 의 최신 빌드본을 자동 탐색해
#      APP_VERSION 을 역산하므로 pom.xml <version> 변경에 자동 추종한다.
#      (heap_enc.sh / heap_dec.sh 와 동일한 방식)
#
# ============================================================

# ---------- 경로 ----------
APP_HOME=/opt/genspark/webapp_dump
APP_NAME="${APP_NAME:-heap-analyzer}"
TARGET_DIR="${TARGET_DIR:-$APP_HOME/target}"
LOG_DIR="${LOG_DIR:-$APP_HOME/logs}"
NOHUP_LOG="${NOHUP_LOG:-$LOG_DIR/nohup.out}"
GC_LOG="${GC_LOG:-$LOG_DIR/gc.log}"

# ---------- 실행 ----------
JAVA_BIN="${JAVA_BIN:-java}"
SERVER_PORT="${SERVER_PORT:-18080}"

# ---------- JVM ----------
JVM_XMS="${JVM_XMS:-256m}"
JVM_XMX="${JVM_XMX:-512m}"
JVM_HEAP_OPTS="${JVM_HEAP_OPTS:--Xms$JVM_XMS -Xmx$JVM_XMX}"
JVM_ENCODING_OPTS="${JVM_ENCODING_OPTS:--Dfile.encoding=UTF-8}"

# GC 로깅 로테이션
GC_LOG_FILE_COUNT="${GC_LOG_FILE_COUNT:-5}"
GC_LOG_FILE_SIZE="${GC_LOG_FILE_SIZE:-20m}"
GC_LOG_OPTS="${GC_LOG_OPTS:--Xlog:gc*:file=$GC_LOG:time,uptime,level,tags:filecount=$GC_LOG_FILE_COUNT,filesize=$GC_LOG_FILE_SIZE}"

# ---------- TLS truststore ----------
# 사내 사설 CA 인증서가 추가된 truststore (PKIX 검증용).
# 파일 미존재 시에는 JDK 기본 cacerts 사용 (기동에 영향 없음).
TRUSTSTORE="${TRUSTSTORE:-$APP_HOME/certs/heap-truststore.jks}"
TRUSTSTORE_PASS="${TRUSTSTORE_PASS:-changeit}"
TRUSTSTORE_TYPE="${TRUSTSTORE_TYPE:-JKS}"

# ---------- 대기 시간 (초) ----------
STOP_WAIT_SECS="${STOP_WAIT_SECS:-15}"            # stop.sh 종료 대기
RESTART_STOP_WAIT_SECS="${RESTART_STOP_WAIT_SECS:-20}"  # restart.sh 이전 프로세스 종료 대기
BOOT_WAIT_SECS="${BOOT_WAIT_SECS:-60}"            # 기동 완료 대기

# ---------- 기동 로그 판정 패턴 ----------
BOOT_OK_PATTERN="${BOOT_OK_PATTERN:-Started HeapAnalyzerApplication}"
BOOT_FAIL_PATTERN="${BOOT_FAIL_PATTERN:-APPLICATION FAILED TO START|Exception in thread \"main\"}"
BOOT_SHUTDOWN_PATTERN="${BOOT_SHUTDOWN_PATTERN:-\[Shutdown\] Application is shutting down}"

# ---------- 프로세스 매칭 ----------
# 버전을 특정하지 않고 heap-analyzer-*.jar 를 전부 잡는다.
# (구버전 JAR 명으로 떠 있는 프로세스를 stop/restart 가 못 죽여서 포트가 충돌하던 문제 방지)
APP_PROC_PATTERN="${APP_PROC_PATTERN:-$APP_NAME-[0-9][^[:space:]]*\.jar}"

# ---------- 로컬 override (선택) ----------
# 비밀정보(HEAP_ANALYZER_ENCRYPTION_KEY 등)는 env.local.sh 에 두고 export 한다.
# .gitignore 대상이며, 없으면 그냥 건너뛴다.
ENV_LOCAL="${ENV_LOCAL:-$APP_HOME/env.local.sh}"
[ -f "$ENV_LOCAL" ] && . "$ENV_LOCAL"

# ============================================================
# 헬퍼 함수
# ============================================================

# 실행 중인 앱 PID 목록 (없으면 빈 문자열)
app_pids() {
    if command -v pgrep >/dev/null 2>&1; then
        pgrep -f "$APP_PROC_PATTERN" 2>/dev/null
    else
        ps -eo pid=,args= | grep -E "$APP_PROC_PATTERN" | grep -v grep | awk '{print $1}'
    fi
    return 0
}

# target/ 의 최신 heap-analyzer-*.jar 탐색 → APP_JAR / APP_VERSION 설정.
# HEAP_ANALYZER_JAR 로 직접 지정 가능. -sources.jar 는 제외.
resolve_jar() {
    APP_JAR="${HEAP_ANALYZER_JAR:-}"
    if [ -z "$APP_JAR" ]; then
        APP_JAR=$(ls -t "$TARGET_DIR"/"$APP_NAME"-*.jar 2>/dev/null \
                  | grep -v -- '-sources\.jar$' | head -1)
    fi
    if [ -n "$APP_JAR" ]; then
        APP_VERSION=$(basename "$APP_JAR" | sed -e "s/^$APP_NAME-//" -e 's/\.jar$//')
    else
        APP_VERSION=""
    fi
}

# JAR 이 반드시 필요한 스크립트(run/restart)용 — 없으면 안내 후 실패
require_jar() {
    local tag="$1"
    resolve_jar
    if [ -z "$APP_JAR" ] || [ ! -f "$APP_JAR" ]; then
        echo "[$tag] JAR 파일을 찾을 수 없습니다: $TARGET_DIR/$APP_NAME-*.jar"
        echo "[$tag] 먼저 빌드를 실행하세요: mvn clean package -DskipTests"
        return 1
    fi
    return 0
}

# truststore 존재 여부에 따라 TRUST_OPTS 구성
build_trust_opts() {
    local tag="$1"
    if [ -f "$TRUSTSTORE" ]; then
        TRUST_OPTS="-Djavax.net.ssl.trustStore=$TRUSTSTORE -Djavax.net.ssl.trustStorePassword=$TRUSTSTORE_PASS -Djavax.net.ssl.trustStoreType=$TRUSTSTORE_TYPE"
        echo "[$tag] Using custom truststore: $TRUSTSTORE"
    else
        TRUST_OPTS=""
        echo "[$tag] Custom truststore not found at $TRUSTSTORE — using JDK default cacerts"
    fi
}

# SIGTERM 후 완전 종료까지 대기.
#   $1 = 로그 태그, $2 = 대기 초
#   0 = 종료 완료(또는 애초에 미실행), 1 = 타임아웃 (남은 PID 는 $REMAINING_PIDS)
stop_app() {
    local tag="$1" wait_secs="$2" i
    local pids
    pids=$(app_pids)
    REMAINING_PIDS=""
    if [ -z "$pids" ]; then
        return 0
    fi

    echo "[$tag] SIGTERM 전송: PID=$(echo $pids) — 종료 대기 중..."
    kill -15 $pids 2>/dev/null

    for i in $(seq 1 "$wait_secs"); do
        sleep 1
        pids=$(app_pids)
        if [ -z "$pids" ]; then
            echo "[$tag] 종료 완료 (${i}s)"
            return 0
        fi
    done

    REMAINING_PIDS=$(echo $(app_pids))
    return 1
}

# 앱 기동 (setsid 로 새 session/process group 에 분리).
#   비대화식 셸은 job control 이 꺼져 있어 `nohup ... &` 만으로는 자바 프로세스가
#   셸과 같은 PGID 를 공유 → 터미널 Ctrl+C(SIGINT) 가 자바 앱에도 전달되어
#   "[Shutdown] Application is shutting down (signal received)" 로그가 남는다.
#   setsid 로 새 PGID 에 옮기면 Ctrl+C 가 도달하지 않음.
#   stdbuf -oL -eL : 파일로 redirect 되어도 라인 단위 flush
#   < /dev/null    : stdin 명시적 분리
#   기동한 PID 는 APP_PID 에 담긴다.
start_app() {
    local tag="$1"
    mkdir -p "$LOG_DIR"
    : > "$NOHUP_LOG"

    setsid nohup stdbuf -oL -eL "$JAVA_BIN" $JVM_HEAP_OPTS $GC_LOG_OPTS $JVM_ENCODING_OPTS $TRUST_OPTS \
        -jar "$APP_JAR" --server.port="$SERVER_PORT" \
        < /dev/null > "$NOHUP_LOG" 2>&1 &
    APP_PID=$!
    disown "$APP_PID" 2>/dev/null || true

    echo "[$tag] Started: PID=$APP_PID  version=$APP_VERSION  port=$SERVER_PORT  (log: $NOHUP_LOG)"
    echo "[$tag] (Ctrl+C 는 로그 스트리밍만 중단합니다. 앱은 계속 실행됩니다.)"
    echo "----------------------------------------------------------------------"
}

# 부팅 완료/실패 메시지가 나올 때까지 nohup.out 을 터미널로 스트리밍한 뒤 상태 판정.
#   $1 = 로그 태그
#   반환: 0 = 정상 기동(또는 Ctrl+C 시점에 실행 중), 1 = 실패
wait_for_boot() {
    local tag="$1" i

    # stdbuf -oL : tail 의 출력도 라인 단위 flush 강제
    stdbuf -oL tail -n +1 -F "$NOHUP_LOG" 2>/dev/null &
    TAIL_PID=$!

    # Ctrl+C 처리: tail 만 중단하고 플래그 set (자바는 setsid 로 분리되어 영향 없음)
    INTERRUPTED=0
    on_interrupt() {
        INTERRUPTED=1
        kill "$TAIL_PID" 2>/dev/null
    }
    trap on_interrupt INT TERM

    for i in $(seq 1 "$BOOT_WAIT_SECS"); do
        [ $INTERRUPTED -eq 1 ] && break
        # 프로세스가 사라졌으면 즉시 중단
        if ! kill -0 "$APP_PID" 2>/dev/null; then
            sleep 1
            break
        fi
        if grep -qE "$BOOT_OK_PATTERN|$BOOT_FAIL_PATTERN|$BOOT_SHUTDOWN_PATTERN" "$NOHUP_LOG" 2>/dev/null; then
            sleep 1   # 마지막 줄까지 출력되도록 잠시 대기
            break
        fi
        sleep 1
    done

    kill "$TAIL_PID" 2>/dev/null
    wait "$TAIL_PID" 2>/dev/null
    trap - INT TERM
    echo "----------------------------------------------------------------------"

    # 최종 상태 판정
    if [ $INTERRUPTED -eq 1 ]; then
        if kill -0 "$APP_PID" 2>/dev/null; then
            echo "[$tag] 로그 스트리밍 중단 (Ctrl+C). 앱은 계속 실행 중 (PID=$APP_PID)."
            echo "[$tag] 'tail -f $NOHUP_LOG' 로 추적, 'bash stop.sh' 로 종료."
            return 0
        fi
        echo "[$tag] 중단됨. 앱이 실행 중이 아닙니다. '$NOHUP_LOG' 를 확인하세요."
        return 1
    fi

    if kill -0 "$APP_PID" 2>/dev/null && grep -q "$BOOT_OK_PATTERN" "$NOHUP_LOG" 2>/dev/null; then
        echo "[$tag] Done. (앱 정상 기동, PID=$APP_PID. 'tail -f $NOHUP_LOG' 로 계속 추적 가능)"
        return 0
    elif grep -qE "$BOOT_FAIL_PATTERN" "$NOHUP_LOG" 2>/dev/null; then
        echo "[$tag] 기동 실패: '$NOHUP_LOG' 를 확인하세요."
        return 1
    elif grep -qE "$BOOT_SHUTDOWN_PATTERN" "$NOHUP_LOG" 2>/dev/null; then
        echo "[$tag] 기동 중 종료 신호 수신: '$NOHUP_LOG' 를 확인하세요."
        return 1
    elif ! kill -0 "$APP_PID" 2>/dev/null; then
        echo "[$tag] 프로세스가 종료되었습니다 (PID=$APP_PID). '$NOHUP_LOG' 를 확인하세요."
        return 1
    fi

    echo "[$tag] 기동 미완료 (${BOOT_WAIT_SECS}초 타임아웃). '$NOHUP_LOG' 를 확인하세요."
    return 1
}

# source 시점에 JAR 정보 확정 (없어도 실패하지 않음 — stop.sh 는 JAR 이 필요 없다)
resolve_jar
