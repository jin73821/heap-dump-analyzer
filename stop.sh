RUNNING_PIDS=$(ps -ef | grep heap-analyzer-2.1.1.jar | grep -v grep | awk '{print $2}')
if [ -z "$RUNNING_PIDS" ]; then
    echo "[stop] 실행 중인 프로세스가 없습니다."
    exit 0
fi

echo "[stop] SIGTERM 전송: PID=$RUNNING_PIDS"
ps -ef | grep heap-analyzer-2.1.1.jar | grep -v grep | awk '{print "kill -15 " $2}' | sh

# 최대 15초 대기하면서 종료 확인
for i in $(seq 1 15); do
    sleep 1
    REMAINING=$(ps -ef | grep heap-analyzer-2.1.1.jar | grep -v grep | awk '{print $2}')
    if [ -z "$REMAINING" ]; then
        echo "[stop] 종료 완료. (${i}s)"
        exit 0
    fi
done

echo "[stop] 15초 내 종료되지 않음 — 남은 PID=$REMAINING"
echo "[stop] 강제 종료가 필요하면: kill -9 $REMAINING"
exit 1
