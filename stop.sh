#!/bin/bash
# ============================================================
# stop.sh — 앱 종료 (SIGTERM 후 대기)
# ============================================================
# 설정값은 전부 env.sh 에 있다. 버전/경로 하드코딩 금지.
#   예) STOP_WAIT_SECS=30 bash stop.sh
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/env.sh"

TAG=stop

if [ -z "$(app_pids)" ]; then
    echo "[$TAG] 실행 중인 프로세스가 없습니다."
    exit 0
fi

if stop_app "$TAG" "$STOP_WAIT_SECS"; then
    exit 0
fi

echo "[$TAG] ${STOP_WAIT_SECS}초 내 종료되지 않음 — 남은 PID=$REMAINING_PIDS"
echo "[$TAG] 강제 종료가 필요하면: kill -9 $REMAINING_PIDS"
exit 1
