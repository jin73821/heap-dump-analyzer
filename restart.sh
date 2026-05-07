#ps -ef | grep heap-analyzer-2.0.1.jar | grep -v grep | awk '{print "kill -15 " $2}' | sh;                                                                    
#nohup java -jar /opt/genspark/webapp_dump/target/heap-analyzer-2.0.1.jar --server.port=18080 &

JAR=/opt/genspark/webapp_dump/target/heap-analyzer-2.0.1.jar
LOG_DIR=/opt/genspark/webapp_dump/logs
NOHUP_LOG="$LOG_DIR/nohup.out"

# 기존 프로세스 종료 — SIGTERM 후 완전 종료까지 wait
# (안 기다리고 새로 띄우면, 이전 프로세스의 fd 가 살아 있어 nohup.out 끝쪽에
#  종료 로그를 뒤늦게 기록 → tail 이 부팅 로그 대신 종료 로그만 보여주는 현상 발생)
RUNNING_PIDS=$(ps -ef | grep heap-analyzer-2.0.1.jar | grep -v grep | awk '{print $2}')
if [ -n "$RUNNING_PIDS" ]; then
    echo "[restart] SIGTERM 전송: PID=$RUNNING_PIDS — 종료 대기 중..."
    kill -15 $RUNNING_PIDS 2>/dev/null
    for i in $(seq 1 20); do
        sleep 1
        REMAINING=$(ps -ef | grep heap-analyzer-2.0.1.jar | grep -v grep | awk '{print $2}')
        if [ -z "$REMAINING" ]; then
            echo "[restart] 이전 프로세스 종료 완료 (${i}s)"
            break
        fi
    done
    REMAINING=$(ps -ef | grep heap-analyzer-2.0.1.jar | grep -v grep | awk '{print $2}')
    if [ -n "$REMAINING" ]; then
        echo "[restart] 20초 내 종료 실패: PID=$REMAINING — 기동 중단"
        echo "[restart] 강제 종료 후 다시 시도하세요: kill -9 $REMAINING"
        exit 1
    fi
fi

# nohup 기동 — stdout/stderr 를 nohup.out 으로 (앞 내용은 비우고 시작)
# stdbuf -oL -eL : 파일로 redirect 되어도 라인 단위로 flush (Spring Boot 부팅 로그가 한 줄씩 즉시 보이도록)
: > "$NOHUP_LOG"
nohup stdbuf -oL -eL java -jar "$JAR" --server.port=18080 > "$NOHUP_LOG" 2>&1 &
PID=$!
echo "[restart] Started: PID=$PID  (log: $NOHUP_LOG)"
echo "----------------------------------------------------------------------"

# 부팅 완료 또는 실패 메시지가 나올 때까지 nohup.out 을 터미널로 스트리밍
# (Ctrl+C 로 즉시 detach 가능 — 앱은 계속 실행됨)
# stdbuf -oL : tail 의 출력도 라인 단위 flush 강제 (백그라운드라도 즉시 한 줄씩 보이도록)
stdbuf -oL tail -n +1 -F "$NOHUP_LOG" 2>/dev/null &
TAIL_PID=$!
trap "kill $TAIL_PID 2>/dev/null" EXIT INT

for i in $(seq 1 60); do
    if grep -qE "Started HeapAnalyzerApplication|APPLICATION FAILED TO START|Exception in thread \"main\"" "$NOHUP_LOG" 2>/dev/null; then
        sleep 1   # 마지막 줄까지 출력되도록 잠시 대기
        break
    fi
    sleep 1
done

kill $TAIL_PID 2>/dev/null
trap - EXIT INT
echo "----------------------------------------------------------------------"
echo "[restart] Done. (앱은 계속 실행 중. 'tail -f $NOHUP_LOG' 로 계속 추적 가능)"
