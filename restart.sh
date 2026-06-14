#ps -ef | grep heap-analyzer-2.1.1.jar | grep -v grep | awk '{print "kill -15 " $2}' | sh;
#nohup java -jar /opt/genspark/webapp_dump/target/heap-analyzer-2.1.1.jar --server.port=18080 &

JAR=/opt/genspark/webapp_dump/target/heap-analyzer-2.1.1.jar
LOG_DIR=/opt/genspark/webapp_dump/logs
NOHUP_LOG="$LOG_DIR/nohup.out"

# 기존 프로세스 종료 — SIGTERM 후 완전 종료까지 wait
# (안 기다리고 새로 띄우면, 이전 프로세스의 fd 가 살아 있어 nohup.out 끝쪽에
#  종료 로그를 뒤늦게 기록 → tail 이 부팅 로그 대신 종료 로그만 보여주는 현상 발생)
RUNNING_PIDS=$(ps -ef | grep heap-analyzer-2.1.1.jar | grep -v grep | awk '{print $2}')
if [ -n "$RUNNING_PIDS" ]; then
    echo "[restart] SIGTERM 전송: PID=$RUNNING_PIDS — 종료 대기 중..."
    kill -15 $RUNNING_PIDS 2>/dev/null
    for i in $(seq 1 20); do
        sleep 1
        REMAINING=$(ps -ef | grep heap-analyzer-2.1.1.jar | grep -v grep | awk '{print $2}')
        if [ -z "$REMAINING" ]; then
            echo "[restart] 이전 프로세스 종료 완료 (${i}s)"
            break
        fi
    done
    REMAINING=$(ps -ef | grep heap-analyzer-2.1.1.jar | grep -v grep | awk '{print $2}')
    if [ -n "$REMAINING" ]; then
        echo "[restart] 20초 내 종료 실패: PID=$REMAINING — 기동 중단"
        echo "[restart] 강제 종료 후 다시 시도하세요: kill -9 $REMAINING"
        exit 1
    fi
fi

# 기동 — setsid 로 새 session/process group 에 분리해서 띄움
# (비대화식 셸은 job control 이 꺼져 있어 `nohup ... &` 만으로는 자바 프로세스가
#  셸과 같은 PGID 를 공유 → 터미널 Ctrl+C(SIGINT) 가 자바 앱에도 전달되어
#  "[Shutdown] Application is shutting down (signal received)" 로그가 남는 문제 발생.
#  setsid 로 새 PGID 에 옮기면 Ctrl+C 가 도달하지 않음.)
# stdbuf -oL -eL : 파일로 redirect 되어도 라인 단위로 flush
# < /dev/null    : stdin 도 명시적으로 분리
: > "$NOHUP_LOG"

# 사내 사설 CA 인증서가 추가된 truststore 사용 (PKIX 검증용)
# 파일 미존재 시에는 JDK 기본 cacerts 사용 (기동에 영향 없음)
TRUSTSTORE=/opt/genspark/webapp_dump/certs/heap-truststore.jks
TRUST_OPTS=""
if [ -f "$TRUSTSTORE" ]; then
    TRUST_OPTS="-Djavax.net.ssl.trustStore=$TRUSTSTORE -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=JKS"
    echo "[restart] Using custom truststore: $TRUSTSTORE"
else
    echo "[restart] Custom truststore not found at $TRUSTSTORE — using JDK default cacerts"
fi

# JVM 힙 설정: 초기 256 MB / 최대 1 GB
JVM_HEAP_OPTS="-Xms256m -Xmx1g"

# GC 로깅: 로테이션 20MB × 5파일
GC_LOG_OPTS="-Xlog:gc*:file=$LOG_DIR/gc.log:time,uptime,level,tags:filecount=5,filesize=20m"

setsid nohup stdbuf -oL -eL java $JVM_HEAP_OPTS $GC_LOG_OPTS -Dfile.encoding=UTF-8 $TRUST_OPTS -jar "$JAR" --server.port=18080 \
    < /dev/null > "$NOHUP_LOG" 2>&1 &
PID=$!
disown $PID 2>/dev/null || true
echo "[restart] Started: PID=$PID  (log: $NOHUP_LOG)"
echo "[restart] (Ctrl+C 는 로그 스트리밍만 중단합니다. 앱은 계속 실행됩니다.)"
echo "----------------------------------------------------------------------"

# 부팅 완료/실패 메시지가 나올 때까지 nohup.out 을 터미널로 스트리밍
stdbuf -oL tail -n +1 -F "$NOHUP_LOG" 2>/dev/null &
TAIL_PID=$!

# Ctrl+C 처리: tail 만 중단하고 플래그 set (자바는 setsid 로 분리되어 영향 없음)
INTERRUPTED=0
on_interrupt() {
    INTERRUPTED=1
    kill "$TAIL_PID" 2>/dev/null
}
trap on_interrupt INT TERM

for i in $(seq 1 60); do
    [ $INTERRUPTED -eq 1 ] && break
    if ! kill -0 "$PID" 2>/dev/null; then
        sleep 1
        break
    fi
    if grep -qE "Started HeapAnalyzerApplication|APPLICATION FAILED TO START|Exception in thread \"main\"|\[Shutdown\] Application is shutting down" "$NOHUP_LOG" 2>/dev/null; then
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
    if kill -0 "$PID" 2>/dev/null; then
        echo "[restart] 로그 스트리밍 중단 (Ctrl+C). 앱은 계속 실행 중 (PID=$PID)."
        echo "[restart] 'tail -f $NOHUP_LOG' 로 추적, 'bash stop.sh' 로 종료."
        exit 0
    else
        echo "[restart] 중단됨. 앱이 실행 중이 아닙니다. '$NOHUP_LOG' 를 확인하세요."
        exit 1
    fi
fi

if kill -0 "$PID" 2>/dev/null && grep -q "Started HeapAnalyzerApplication" "$NOHUP_LOG" 2>/dev/null; then
    echo "[restart] Done. (앱 정상 기동, PID=$PID. 'tail -f $NOHUP_LOG' 로 계속 추적 가능)"
elif grep -qE "APPLICATION FAILED TO START|Exception in thread \"main\"" "$NOHUP_LOG" 2>/dev/null; then
    echo "[restart] 기동 실패: '$NOHUP_LOG' 를 확인하세요."
    exit 1
elif grep -q "\[Shutdown\] Application is shutting down" "$NOHUP_LOG" 2>/dev/null; then
    echo "[restart] 기동 중 종료 신호 수신: '$NOHUP_LOG' 를 확인하세요."
    exit 1
elif ! kill -0 "$PID" 2>/dev/null; then
    echo "[restart] 프로세스가 종료되었습니다 (PID=$PID). '$NOHUP_LOG' 를 확인하세요."
    exit 1
else
    echo "[restart] 기동 미완료 (60초 타임아웃). '$NOHUP_LOG' 를 확인하세요."
fi
