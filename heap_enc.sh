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

if [ -z "$1" ]; then
    echo "사용법: bash heap_enc.sh \"암호화할 평문\""
    echo "예시:   bash heap_enc.sh \"my_password\""
    echo ""
    echo "환경변수 HEAP_ANALYZER_ENCRYPTION_KEY로 암호화 키를 설정할 수 있습니다."
    echo "미설정 시 기본 키를 사용합니다 (운영 환경에서는 반드시 설정하세요)."
    echo "환경변수 HEAP_ANALYZER_JAR 로 사용할 JAR 경로를 직접 지정할 수 있습니다."
    exit 1
fi

# JAR 자동 탐색 — pom.xml <version> 변경에 자동 추종 (버전 하드코딩 시 버전업마다 스크립트가 깨짐).
# target/ 의 heap-analyzer-*.jar 중 가장 최근 빌드본 사용. -sources 는 제외.
JAR_PATH="${HEAP_ANALYZER_JAR:-}"
if [ -z "$JAR_PATH" ]; then
    JAR_PATH=$(ls -t "$SCRIPT_DIR"/target/heap-analyzer-*.jar 2>/dev/null \
               | grep -v -- '-sources\.jar$' | head -1)
fi

if [ -z "$JAR_PATH" ] || [ ! -f "$JAR_PATH" ]; then
    echo "[ERROR] JAR 파일을 찾을 수 없습니다: $SCRIPT_DIR/target/heap-analyzer-*.jar"
    echo "먼저 빌드를 실행하세요: mvn clean package -DskipTests"
    exit 1
fi

# Boot 3.2+ 는 PropertiesLauncher 가 org.springframework.boot.loader.launch 로 이동.
# 신규 위치 우선 시도 → 미존재(구 Boot 2 JAR) 시 구 위치로 폴백.
run_aes() {
    java -cp "$JAR_PATH" \
        -Dloader.main=com.heapdump.analyzer.util.AesEncryptor \
        "$1" encrypt "$2"
}

OUT=$(run_aes org.springframework.boot.loader.launch.PropertiesLauncher "$1" 2>&1); RC=$?
if [ $RC -ne 0 ] && echo "$OUT" | grep -q "Could not find or load main class"; then
    OUT=$(run_aes org.springframework.boot.loader.PropertiesLauncher "$1" 2>&1); RC=$?
fi

printf '%s\n' "$OUT"
exit $RC
