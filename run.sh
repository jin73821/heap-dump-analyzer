JAR=/opt/genspark/webapp_dump/target/heap-analyzer-2.0.1.jar
LOG_DIR=/opt/genspark/webapp_dump/logs
NOHUP_LOG="$LOG_DIR/nohup.out"

# 이미 실행 중이면 거절 (재기동은 restart.sh 사용)
RUNNING_PIDS=$(ps -ef | grep heap-analyzer-2.0.1.jar | grep -v grep | awk '{print $2}')
if [ -n "$RUNNING_PIDS" ]; then
    echo "[run] 이미 실행 중: PID=$RUNNING_PIDS"
    echo "[run] 'bash stop.sh' 후 다시 실행하거나 'bash restart.sh' 를 사용하세요."
    exit 1
fi

# nohup 기동 — stdout/stderr 를 nohup.out 으로 (앞 내용은 비우고 시작)
# stdbuf -oL -eL : 파일로 redirect 되어도 라인 단위로 flush (Spring Boot 부팅 로그가 한 줄씩 즉시 보이도록)
: > "$NOHUP_LOG"
nohup stdbuf -oL -eL java -jar "$JAR" --server.port=18080 > "$NOHUP_LOG" 2>&1 &
PID=$!
echo "[run] Started: PID=$PID  (log: $NOHUP_LOG)"
echo "----------------------------------------------------------------------"

# 부팅 완료 또는 실패 메시지가 나올 때까지 nohup.out 을 터미널로 스트리밍
# (Ctrl+C 로 즉시 detach 가능 — 앱은 계속 실행됨)
# stdbuf -oL : tail 의 출력도 라인 단위 flush 강제
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
echo "[run] Done. (앱은 계속 실행 중. 'tail -f $NOHUP_LOG' 로 계속 추적 가능)"
