#!/bin/bash
# ============================================================
# heap_enc.sh — AES 암호화 스크립트
# ============================================================
#
# 사용방법:
#   bash heap_enc.sh "암호화할 평문"
#
# 예시:
#   bash heap_enc.sh "shinhan@10"
#   bash heap_enc.sh "my_secret_password"
#
# 출력:
#   Plain:     shinhan@10
#   Encrypted: n8cFXzF2gULZrTFBeoAo6g==
#   Property:  ENC(n8cFXzF2gULZrTFBeoAo6g==)
#
# application.properties에 적용:
#   spring.datasource.password=ENC(n8cFXzF2gULZrTFBeoAo6g==)
#
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_PATH="$SCRIPT_DIR/target/heap-analyzer-2.0.0.jar"

if [ -z "$1" ]; then
    echo "사용법: bash heap_enc.sh \"암호화할 평문\""
    echo "예시:   bash heap_enc.sh \"shinhan@10\""
    exit 1
fi

if [ ! -f "$JAR_PATH" ]; then
    echo "[ERROR] JAR 파일을 찾을 수 없습니다: $JAR_PATH"
    echo "먼저 빌드를 실행하세요: mvn clean package -DskipTests"
    exit 1
fi

java -cp "$JAR_PATH" \
    -Dloader.main=com.heapdump.analyzer.util.AesEncryptor \
    org.springframework.boot.loader.PropertiesLauncher encrypt "$1"
