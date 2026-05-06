# Heap Dump Analyzer — 사내 시스템 이관 가이드

Spring Boot 2.7.18 기반 Heap Dump 분석 도구를 사내 환경에 설치하기 위한 가이드입니다.

---

## 1. 사전 준비

| 항목 | 요구사항 |
|------|---------|
| OS | Linux (RHEL/CentOS 8+ 권장) |
| Java | OpenJDK 11 |
| DB | MariaDB 10.3+ (또는 호환 MySQL 5.7+) |
| Eclipse MAT CLI | `/opt/mat/ParseHeapDump.sh` (또는 임의 경로 → `application.properties` 수정) |
| 디스크 | 힙덤프 보관용 (덤프 1개당 최소 2배 여유 공간 필요) |
| 포트 | 18080 (운영) 또는 8080 (기본) |

---

## 2. DB 준비 가이드

### 2.1 MariaDB 설치 (이미 설치되어 있으면 생략)

**RHEL/CentOS 8:**
```bash
sudo dnf install -y mariadb-server mariadb
sudo systemctl enable --now mariadb
sudo mysql_secure_installation
```

**Ubuntu/Debian:**
```bash
sudo apt update
sudo apt install -y mariadb-server mariadb-client
sudo systemctl enable --now mariadb
sudo mysql_secure_installation
```

설치 확인:
```bash
mysql --version
sudo systemctl status mariadb
```

---

### 2.2 외부 접속 허용 (앱 서버와 DB 서버가 분리된 경우)

```bash
# bind-address 변경
sudo sed -i 's/^bind-address.*=.*127.0.0.1/bind-address = 0.0.0.0/' \
    /etc/my.cnf.d/mariadb-server.cnf

# 변경 적용
sudo systemctl restart mariadb

# 방화벽 허용 (앱 서버 IP만 허용 권장)
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" \
    source address="<앱서버IP>/32" port port="3306" protocol="tcp" accept'
sudo firewall-cmd --reload
```

---

### 2.3 데이터베이스 및 계정 생성

`root` 계정으로 접속 후 아래 SQL을 실행합니다.

```bash
sudo mysql -u root -p
```

```sql
-- ─────────────────────────────────────────────────────────────
-- 1) 데이터베이스 생성 (UTF-8 4-byte 지원, 한글/이모지 안전)
-- ─────────────────────────────────────────────────────────────
CREATE DATABASE IF NOT EXISTS HEAPDB
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────
-- 2) 애플리케이션 전용 계정 생성
--    비밀번호는 사내 보안 정책에 맞게 변경하세요.
-- ─────────────────────────────────────────────────────────────
CREATE USER IF NOT EXISTS 'heap_user'@'%'         IDENTIFIED BY '변경하세요_StrongPassword!';
CREATE USER IF NOT EXISTS 'heap_user'@'localhost' IDENTIFIED BY '변경하세요_StrongPassword!';

-- ─────────────────────────────────────────────────────────────
-- 3) 권한 부여 (DDL/DML — JPA ddl-auto=update가 테이블 자동 생성)
-- ─────────────────────────────────────────────────────────────
GRANT ALL PRIVILEGES ON HEAPDB.* TO 'heap_user'@'%';
GRANT ALL PRIVILEGES ON HEAPDB.* TO 'heap_user'@'localhost';
FLUSH PRIVILEGES;

-- ─────────────────────────────────────────────────────────────
-- 4) 확인
-- ─────────────────────────────────────────────────────────────
SHOW DATABASES LIKE 'HEAPDB';
SELECT user, host FROM mysql.user WHERE user = 'heap_user';
SHOW GRANTS FOR 'heap_user'@'%';

EXIT;
```

> **권한 최소화 옵션**: 운영 환경에서 `GRANT ALL` 대신 다음 권한만 부여해도 동작합니다.
> ```sql
> GRANT SELECT, INSERT, UPDATE, DELETE,
>       CREATE, ALTER, DROP, INDEX, REFERENCES
>     ON HEAPDB.* TO 'heap_user'@'%';
> ```

---

### 2.4 접속 테스트

앱 서버에서 DB로 연결되는지 확인합니다.

```bash
# 로컬 DB
mysql -u heap_user -p HEAPDB -e "SELECT NOW(), VERSION();"

# 원격 DB (앱 서버 → DB 서버)
mysql -h <DB서버IP> -P 3306 -u heap_user -p HEAPDB -e "SELECT NOW(), VERSION();"
```

`SELECT NOW()` 결과가 정상 출력되면 OK.

---

### 2.5 DB 비밀번호 암호화

`application.properties`에 평문 비밀번호를 저장하지 않고, AES-256 암호화 형식(`ENC(...)`)으로 저장합니다. DB 비밀번호 외에 **LLM API Key, RAG ES 비밀번호/API 키**도 모두 동일한 `ENC(...)` 형식을 사용합니다.

```bash
cd /opt/genspark/webapp_dump

# 암호화 (JAR 빌드 후 실행 가능)
bash heap_enc.sh "변경하세요_StrongPassword!"
```

출력 예시:
```
Plain:     변경하세요_StrongPassword!
Encrypted: 682d6e43ec2ac80b8856edffc9351e61
Property:  ENC(682d6e43ec2ac80b8856edffc9351e61)
```

> **암호화 키 설정 (권장)**: 기본 키는 운영에 부적합합니다. 환경변수로 별도 키를 지정하세요.
> ```bash
> export HEAP_ANALYZER_ENCRYPTION_KEY="<32자 이상 랜덤 문자열>"
> bash heap_enc.sh "비밀번호"
> ```
> 키는 `restart.sh` 또는 systemd unit 파일에 동일하게 설정해야 앱 기동 시 복호화됩니다.
>
> **`restart.sh`에 키 적용 예시** (현재 스크립트는 키를 export하지 않으므로 수동 보강 필요):
> ```bash
> #!/bin/bash
> ps -ef | grep heap-analyzer-2.0.1.jar | grep -v grep | awk '{print "kill -15 " $2}' | sh;
> export HEAP_ANALYZER_ENCRYPTION_KEY="<운영 환경 키>"
> nohup java -jar /opt/genspark/webapp_dump/target/heap-analyzer-2.0.1.jar --server.port=18080 &
> ```
> 키가 불일치하면 모든 `ENC(...)` 복호화가 실패하여 DB 연결/LLM 호출/RAG 검색이 모두 동작하지 않습니다.

---

### 2.6 application.properties 수정

`application.properties`는 JAR 내부(`BOOT-INF/classes/`)에 포함되어 빌드되므로, 사내 환경값으로 수정 후 **재빌드(`mvn clean package -DskipTests`)** 가 필요합니다. 외부 파일로 오버라이드하려면 JAR과 동일 디렉토리에 별도 `application.properties`를 두면 우선 적용됩니다.

#### (1) DataSource — 필수 변경

```properties
# ── MariaDB ──────────────────────────────────────────────────
spring.datasource.url=jdbc:mariadb://<DB서버IP>:3306/HEAPDB?useUnicode=true&characterEncoding=utf8mb4&serverTimezone=Asia/Seoul
spring.datasource.username=heap_user
spring.datasource.password=ENC(682d6e43ec2ac80b8856edffc9351e61)
spring.datasource.driver-class-name=org.mariadb.jdbc.Driver
spring.jpa.hibernate.ddl-auto=update
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MariaDB103Dialect
spring.jpa.open-in-view=false
```

#### (2) Spring Session JDBC — 필수 (기본값 그대로 유지)

⚠️ **이 블록이 누락되거나 DB가 미연결이면 앱이 기동하지 않습니다.** 매 요청마다 `LAST_ACCESS_TIME`을 갱신하므로 `heap_user`에 DDL/DML 권한이 모두 필요합니다.

```properties
# ── 세션 관리 (Spring Session JDBC, MariaDB 영속화) ───────────
server.servlet.session.timeout=60m
spring.session.store-type=jdbc
spring.session.jdbc.initialize-schema=always
spring.session.jdbc.schema=classpath:org/springframework/session/jdbc/schema-mysql.sql
spring.session.jdbc.cleanup-cron=0 */10 * * * *
```

기동 시 `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` 두 테이블이 자동 생성됩니다. 앱 재기동에도 로그인 세션이 유지됩니다.

#### (3) LLM 분석 — 사용 시 변경

`/settings/llm`에서 UI로도 변경 가능하나, 초기값은 `application.properties`에 둡니다. `llm.api.key`는 `ENC(...)`로 암호화 권장.

```properties
llm.enabled=true
llm.provider=genspark              # claude | gpt | genspark | custom
llm.api.url=https://www.genspark.ai/api/llm_proxy/v1/chat/completions
llm.model=claude-sonnet-4-5
llm.api.key=ENC(...)               # bash heap_enc.sh "<API 키>"
llm.max-input-tokens=8000
llm.max-output-tokens=4000
llm.timeout.connect-seconds=60
llm.timeout.read-seconds=120
llm.chat.restore-include-history=true
```

#### (4) RAG (Elasticsearch) — 사용 시 변경

`/settings/rag` UI로도 변경 가능. 모드는 `keyword` / `semantic-server` / `semantic-client` 중 사내 ES 매핑에 맞춰 선택.

```properties
rag.enabled=false                                  # 활성화 시 true
rag.elasticsearch.url=https://es.internal:9200
rag.elasticsearch.auth-type=basic                  # none | basic | api-key
rag.elasticsearch.username=elastic
rag.elasticsearch.password=ENC(...)
rag.elasticsearch.api-key=
rag.elasticsearch.index=heap-knowledge-base
rag.elasticsearch.ssl-verify=true                  # 자체 서명 시 false
rag.search.mode=keyword
rag.search.text-field=content                      # ES 매핑 필드명과 일치 필요
rag.search.top-k=5
rag.search.min-score=0.5
rag.search.timeout-seconds=15

# Phase 2: semantic-server (서버측 임베딩 — ELSER / semantic_text)
rag.search.semantic.query-type=text_expansion      # text_expansion | semantic
rag.search.semantic.model-id=.elser_model_2
rag.search.semantic.tokens-field=ml.tokens
rag.search.semantic.semantic-field=

# Phase 2: semantic-client (앱측 임베딩 + ES kNN)
rag.embedding.provider=openai                      # openai | cohere | custom
rag.embedding.api.url=https://api.openai.com/v1/embeddings
rag.embedding.api.key=ENC(...)
rag.embedding.model=text-embedding-3-small
rag.embedding.dimension=1536
rag.search.knn.vector-field=embedding
rag.search.knn.num-candidates=50

# 검색 결과 청킹 (LLM 컨텍스트 주입 전 분할)
rag.chunking.enabled=true
rag.chunking.strategy=paragraph                    # fixed | paragraph | sentence
rag.chunking.size=1000
rag.chunking.overlap=150
rag.chunking.max-chunks-per-doc=4
rag.chunking.max-total-chars=8000
```

#### (5) 기타 환경값 (필요 시 조정)

```properties
# 힙 덤프 디렉토리 / MAT CLI
heapdump.directory=/opt/heapdumps
mat.cli.path=/opt/mat/ParseHeapDump.sh
mat.keep.unreachable.objects=true
mat.timeout.minutes=30
sse.emitter.timeout.minutes=35                     # mat.timeout.minutes 보다 커야 함

# 분석 스레드풀 (MAT CLI는 Semaphore(1)로 직렬 처리)
analysis.thread-pool.core-size=3
analysis.thread-pool.max-size=5
analysis.thread-pool.queue-capacity=12
analysis.compress-after-analysis=true              # 분석 후 .hprof → .hprof.gz

# 원격 SSH/SCP 수집 (Target Servers 사용 시)
remote.ssh.local-user=sscuser                      # 별도 OS 계정 + SSH 키 교환 필요
remote.scp.temp-dir=/tmp
remote.scan.interval-sec=60

# 파일 업로드 / 로깅
spring.servlet.multipart.max-file-size=5GB
spring.servlet.multipart.max-request-size=5GB
logging.file.name=/opt/genspark/webapp_dump/logs/heapdump-analyzer.log
logging.file.max-size=50MB
logging.file.max-history=14
```

---

### 2.7 테이블 자동 생성 확인

앱을 처음 기동하면 JPA `ddl-auto=update` + Spring Session JDBC가 다음 11개 테이블을 자동 생성합니다.

| 테이블 | 생성 주체 | 용도 |
|--------|---------|------|
| `users` | JPA | 로그인 계정 (BCrypt, ADMIN/USER 권한, enabled flag) |
| `account_requests` | JPA | 계정 self-signup 신청 + 관리자 승인 (PENDING/APPROVED/REJECTED) |
| `login_history` | JPA | 로그인 성공/실패 이력 (username, IP, UA, session_id, failure_reason) |
| `target_servers` | JPA | 원격 덤프 수집 대상 서버 |
| `analysis_history` | JPA | 힙덤프 분석 이력 메타데이터 |
| `dump_transfer_log` | JPA | SCP 전송 로그 |
| `ai_insights` | JPA | AI 인사이트 결과 (MEDIUMTEXT JSON) |
| `ai_chat_sessions` | JPA | AI 채팅 세션 (계정별 격리) |
| `ai_chat_messages` | JPA | AI 채팅 메시지 (MEDIUMTEXT) |
| `SPRING_SESSION` | Spring Session JDBC | 세션 메타데이터 — **미생성 시 기동 실패** |
| `SPRING_SESSION_ATTRIBUTES` | Spring Session JDBC | 세션 속성 직렬화 (BLOB) |

기동 후 확인:
```bash
mysql -u heap_user -p HEAPDB -e "SHOW TABLES;"
# 11개 테이블이 모두 출력되어야 정상

# Spring Session JDBC 정상 동작 확인 (로그인 후)
mysql -u heap_user -p HEAPDB -e "SELECT PRINCIPAL_NAME, FROM_UNIXTIME(LAST_ACCESS_TIME/1000) FROM SPRING_SESSION;"
```

기본 관리자 계정이 자동 생성됩니다 (`UserService.initDefaultAdmin()`):
- ID: `admin`
- PW: `shinhan@10` → **이관 후 즉시 변경**
- 변경 경로: 로그인 → `/admin/users` (ADMIN 전용)

---

### 2.8 백업 / 복구

**백업:**
```bash
# 전체 덤프
mysqldump -u heap_user -p \
    --single-transaction \
    --default-character-set=utf8mb4 \
    HEAPDB > heapdb_$(date +%Y%m%d).sql

# 압축
gzip heapdb_$(date +%Y%m%d).sql
```

**복구:**
```bash
gunzip < heapdb_20260426.sql.gz | mysql -u heap_user -p HEAPDB
```

**자동 백업 (cron 예시):**
```bash
# /etc/cron.d/heapdb-backup
0 3 * * * root /usr/bin/mysqldump -u heap_user -p<PASSWORD> \
    --single-transaction HEAPDB \
    | gzip > /backup/heapdb_$(date +\%Y\%m\%d).sql.gz \
    && find /backup -name 'heapdb_*.sql.gz' -mtime +14 -delete
```

---

## 3. 디렉토리 준비

```bash
# 힙덤프 작업 디렉토리
sudo mkdir -p /opt/heapdumps/{dumpfiles,data,tmp}
sudo chown -R <앱실행계정>:<그룹> /opt/heapdumps
sudo chmod -R 750 /opt/heapdumps

# 로그 디렉토리
sudo mkdir -p /opt/genspark/webapp_dump/logs
sudo chown <앱실행계정>:<그룹> /opt/genspark/webapp_dump/logs
```

---

## 4. Eclipse MAT CLI 설치

```bash
# 다운로드 (예: 1.14.0 Linux x86_64)
cd /opt
sudo wget https://download.eclipse.org/mat/1.14.0/rcp/MemoryAnalyzer-1.14.0.20230315-linux.gtk.x86_64.zip
sudo unzip MemoryAnalyzer-*.zip -d /opt/
sudo mv /opt/mat /opt/mat
sudo chmod +x /opt/mat/ParseHeapDump.sh

# 동작 확인
/opt/mat/ParseHeapDump.sh
# → 사용법(usage)이 출력되면 OK
```

MAT 힙 메모리 조정 (`/opt/mat/MemoryAnalyzer.ini`):
```
-Xms2g
-Xmx8g
```

---

## 5. 애플리케이션 기동

```bash
cd /opt/genspark/webapp_dump
bash restart.sh

# 로그 확인
tail -f /opt/genspark/webapp_dump/logs/heapdump-analyzer.log

# 기동 성공 검증 (약 18초 후)
sleep 18 && grep -E "Started HeapAnalyzerApplication|FAILED|Exception in thread" \
  /opt/genspark/webapp_dump/logs/heapdump-analyzer.log | tail -3
```

기동 성공 시 `http://<서버IP>:18080` 접속 → 로그인 페이지(admin/shinhan@10).

---

## 6. 트러블슈팅

| 증상 | 원인 / 해결 |
|------|-------------|
| `Communications link failure` | 방화벽/`bind-address` 확인, `mysql -h` 접속 테스트 |
| `Access denied for user 'heap_user'` | 비밀번호 오타 또는 `'heap_user'@'%'`/`'@localhost'` 누락 |
| `Unknown database 'HEAPDB'` | DB 미생성 또는 대소문자 불일치 (Linux는 대소문자 구분) |
| `ENC(...) 복호화 실패` | `HEAP_ANALYZER_ENCRYPTION_KEY` 환경변수 불일치 (암호화/복호화 키가 동일해야 함). DB/LLM/RAG 모두 동일 키로 처리됨 |
| JPA 테이블 미생성 | `spring.jpa.hibernate.ddl-auto=update` 확인, `heap_user`에 DDL 권한 확인 |
| `SPRING_SESSION` 테이블 생성 실패 / 기동 실패 | Spring Session JDBC 블록 누락(2.6 (2)) 또는 `heap_user`에 CREATE/INDEX 권한 부족. `spring.session.jdbc.initialize-schema=always` 확인 |
| 재기동마다 강제 로그아웃 | `spring.session.store-type=jdbc` 미설정 → 메모리 세션으로 폴백된 상태. application.properties (2) 블록 추가 후 재빌드 |
| `/login`에 "계정 신청" 버튼 동작 안 함 (403) | `SecurityConfig`에서 `POST /api/account-requests` permitAll 누락 — 소스 수정 후 재빌드 |
| LLM 호출 시 401 / 모델 미인식 | `llm.provider`/`llm.api.url`/`llm.model` 조합 불일치. `/settings/llm` Test Connection 버튼으로 검증. provider별 헤더 차이(Claude=`x-api-key`, OpenAI/Genspark=`Authorization: Bearer`) |
| RAG 검색 결과 0건 | `rag.search.text-field`(예: `content`)가 ES 매핑 필드명과 일치하는지, `rag.search.min-score` 임계값이 과다한지 확인. `/settings/rag` Test Connection으로 인덱스 도큐먼트 수 확인 |
| RAG semantic 모드 에러 | `text_expansion`은 `model-id` 필수, `semantic`은 `semantic-field` 필수. 누락 시 keyword 폴백 없이 명시적 실패 (의도된 동작) |
| 한글 깨짐 | DB/Connection charset이 `utf8mb4`인지 확인 (`SHOW VARIABLES LIKE 'character_set%';`) |
| 5GB 초과 업로드 실패 | `spring.servlet.multipart.max-file-size` / `max-request-size` 함께 상향. Tomcat `maxPostSize=-1`은 Spring Boot가 자동 처리 |

---

## 7. 이관 후 활성화 작업 (기능별 가이드)

기본 설치 후 추가로 활성화할 수 있는 기능과 사전 작업 안내입니다.

### 7.1 계정 관리

- **기본 admin 비밀번호 변경** — 로그인 → `/admin/users` → "사용자" 탭 → admin 행의 [비밀번호 재설정]
- **계정 신청(Self-signup)** — 비로그인자가 `/login` 화면 하단 [계정 신청]에서 신청 → 관리자가 `/admin/users` → "계정 신청" 탭에서 승인/거부. 비밀번호는 신청 시점에 BCrypt 인코딩되어 저장됨 (평문 미저장)
- **로그인 접속 이력** — `/admin/users` → "접속 이력" 탭. 성공/실패, IP, User-Agent, 실패 사유
- **활성 세션 모니터링** — `/admin/users` → "현재 접속" 탭. 강제 종료 가능 (본인 세션은 서버에서 거부)

### 7.2 LLM 분석 활성화

1. `/settings/llm` 접속 (ADMIN 전용)
2. Provider 선택 (Claude / GPT / Genspark / Custom OpenAI 호환) → API URL / 모델명 / API Key 입력
3. [Test Connection] 으로 검증 → [저장] 시 `settings.json` + `application.properties`에 `ENC(...)` 형식으로 저장됨
4. 분석 결과 페이지 우하단 플로팅 채팅(FAB) 또는 `/ai-chat`에서 사용

### 7.3 RAG (Elasticsearch) 활성화

사전 준비:
- ES 인덱스 매핑 — 검색 모드별 요구 필드:
  - `keyword`: text 필드 (기본 `content`)
  - `semantic-server` (text_expansion): ELSER 모델로 인덱싱된 sparse vector 토큰 필드 (기본 `ml.tokens`)
  - `semantic-server` (semantic): ES 8.11+ `semantic_text` 필드
  - `semantic-client`: `dense_vector` 필드 (기본 `embedding`, dimension은 사용 임베딩 모델과 일치)
- 학습 데이터: `rag-data/rag-knowledge-20260430.csv` (84건 샘플) — 사내 ES CSV 인덱싱 도구로 투입

활성화:
1. `/settings/rag` → Enable 토글 ON
2. ES URL / 인증(none/basic/api-key) / 인덱스명 / 검색 모드 / 텍스트 필드 입력
3. [Test Connection] → 도큐먼트 수 확인
4. AI 채팅에서 RAG 토글 ON으로 답변 품질 검증

자세한 매핑 예시는 `RAG_PHASE2_PLAN.md` 및 `rag-data/README.md` 참조.

### 7.4 원격 SSH/SCP 자동 수집 활성화

사전 준비:
- 앱 서버에 OS 계정 `sscuser` 생성 (또는 `application.properties`의 `remote.ssh.local-user` 값 변경)
- 대상 서버에 `sscuser`의 SSH 공개키 등록 (passwordless SSH)
- 대상 서버 덤프 디렉토리 읽기 권한 확인

활성화:
1. `/servers` → [서버 추가]에서 호스트/SSH 계정/덤프 경로/자동탐지 ON 등록
2. 등록 후 [연결 테스트]로 검증
3. `dump_transfer_log` 테이블에 SCP 전송 이력 누적 — `/servers/logs`에서 조회

---

## 8. 이관 패키지 체크리스트

- [ ] `target/heap-analyzer-2.0.1.jar`
- [ ] `src/main/resources/application.properties` (사내 환경값으로 수정 — DB URL/계정/암호화 + Session JDBC + LLM/RAG 설정)
- [ ] `restart.sh` (`HEAP_ANALYZER_ENCRYPTION_KEY` export 추가)
- [ ] `heap_enc.sh`, `heap_dec.sh`
- [ ] `README-DEPLOY.md` (본 문서)
- [ ] `rag-data/rag-knowledge-20260430.csv`, `rag-data/README.md` (RAG 사용 시)
- [ ] DB: HEAPDB 생성 + heap_user 계정 + DDL/DML 권한 부여 완료
- [ ] DB 기동 검증: `SHOW TABLES;` 11개 모두 자동 생성 (`SPRING_SESSION*` 포함)
- [ ] Eclipse MAT CLI 설치 (`/opt/mat/ParseHeapDump.sh`) + `MemoryAnalyzer.ini` 힙 조정
- [ ] 디렉토리 권한 (`/opt/heapdumps/{dumpfiles,data,tmp}`, `/opt/genspark/webapp_dump/logs`)
- [ ] 방화벽 (앱 포트 18080, DB 포트 3306, SSH 22 if 원격 수집)
- [ ] **기본 admin 비밀번호 변경** (`/admin/users`)
- [ ] (선택) SSH/SCP 원격 수집용 `sscuser` 계정 + SSH 키 교환
- [ ] (선택) LLM API Key 입력 (`/settings/llm` Test Connection)
- [ ] (선택) ES(RAG) 접속 정보 + 인덱스 매핑 (`/settings/rag` Test Connection)
- [ ] (선택) 계정 신청(Self-signup) 정책 결정 (활성화 시 별도 차단 불필요, 비활성화는 SecurityConfig 수정 필요)
