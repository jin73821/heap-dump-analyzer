#!/bin/bash
# ============================================================
# restart.sh — 기존 프로세스 종료 후 재기동
# ============================================================
# 설정값은 전부 env.sh 에 있다. 버전/포트/경로 하드코딩 금지.
#   예) SERVER_PORT=18081 bash restart.sh
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/env.sh"

TAG=restart

require_jar "$TAG" || exit 1

# 기존 프로세스 종료 — SIGTERM 후 완전 종료까지 wait
# (안 기다리고 새로 띄우면, 이전 프로세스의 fd 가 살아 있어 nohup.out 끝쪽에
#  종료 로그를 뒤늦게 기록 → tail 이 부팅 로그 대신 종료 로그만 보여주는 현상 발생)
if ! stop_app "$TAG" "$RESTART_STOP_WAIT_SECS"; then
    echo "[$TAG] ${RESTART_STOP_WAIT_SECS}초 내 종료 실패: PID=$REMAINING_PIDS — 기동 중단"
    echo "[$TAG] 강제 종료 후 다시 시도하세요: kill -9 $REMAINING_PIDS"
    exit 1
fi

build_trust_opts "$TAG"

start_app "$TAG"
wait_for_boot "$TAG"
exit $?
