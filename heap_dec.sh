#!/bin/bash
# ============================================================
# heap_dec.sh — AES 복호화 스크립트
# ============================================================
#
# 사용방법:
#   bash heap_dec.sh "암호화된 문자열"
#   bash heap_dec.sh "ENC(암호화된 문자열)"
#
# 예시:
#   bash heap_dec.sh "n8cFXzF2gULZrTFBeoAo6g=="
#   bash heap_dec.sh "ENC(n8cFXzF2gULZrTFBeoAo6g==)"
#
# 출력:
#   Decrypted: shinhan@10
#
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_PATH="$SCRIPT_DIR/target/heap-analyzer-2.0.0.jar"

if [ -z "$1" ]; then
    echo "사용법: bash heap_dec.sh \"암호화된 문자열\""
    echo "예시:   bash heap_dec.sh \"n8cFXzF2gULZrTFBeoAo6g==\""
    echo "        bash heap_dec.sh \"ENC(n8cFXzF2gULZrTFBeoAo6g==)\""
    exit 1
fi

if [ ! -f "$JAR_PATH" ]; then
    echo "[ERROR] JAR 파일을 찾을 수 없습니다: $JAR_PATH"
    echo "먼저 빌드를 실행하세요: mvn clean package -DskipTests"
    exit 1
fi

INPUT="$1"
# ENC()로 감싸여 있지 않으면 자동으로 감싸기
if [[ "$INPUT" != ENC\(*\) ]]; then
    INPUT="ENC($INPUT)"
fi

java -cp "$JAR_PATH" \
    -Dloader.main=com.heapdump.analyzer.util.AesEncryptor \
    org.springframework.boot.loader.PropertiesLauncher decrypt "$INPUT"
