#!/bin/bash
# ============================================================
# run.sh — 앱 기동 (이미 실행 중이면 거절)
# ============================================================
# 설정값은 전부 env.sh 에 있다. 버전/포트/경로 하드코딩 금지.
#   예) SERVER_PORT=18081 bash run.sh
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/env.sh"

TAG=run

# 이미 실행 중이면 거절 (재기동은 restart.sh 사용)
RUNNING_PIDS=$(app_pids)
if [ -n "$RUNNING_PIDS" ]; then
    echo "[$TAG] 이미 실행 중: PID=$(echo $RUNNING_PIDS)"
    echo "[$TAG] 'bash stop.sh' 후 다시 실행하거나 'bash restart.sh' 를 사용하세요."
    exit 1
fi

require_jar "$TAG" || exit 1
build_trust_opts "$TAG"

start_app "$TAG"
wait_for_boot "$TAG"
exit $?
