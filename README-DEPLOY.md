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

`application.properties`에 평문 비밀번호를 저장하지 않고, AES-256 암호화 형식(`ENC(...)`)으로 저장합니다.

```bash
cd /opt/heap-analyzer

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

---

### 2.6 application.properties 수정

```properties
# ── MariaDB ──────────────────────────────────────────────────
spring.datasource.url=jdbc:mariadb://<DB서버IP>:3306/HEAPDB?useUnicode=true&characterEncoding=utf8mb4&serverTimezone=Asia/Seoul
spring.datasource.username=heap_user
spring.datasource.password=ENC(682d6e43ec2ac80b8856edffc9351e61)
spring.datasource.driver-class-name=org.mariadb.jdbc.Driver
spring.jpa.hibernate.ddl-auto=update
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MariaDB103Dialect
```

---

### 2.7 테이블 자동 생성 확인

앱을 처음 기동하면 JPA `ddl-auto=update`가 다음 테이블을 자동 생성합니다.

| 테이블 | 용도 |
|--------|------|
| `users` | 로그인 계정 (BCrypt) |
| `target_servers` | 원격 덤프 수집 대상 서버 |
| `analysis_history` | 힙덤프 분석 이력 메타데이터 |
| `dump_transfer_log` | SCP 전송 로그 |
| `ai_insights` | AI 인사이트 결과 (MEDIUMTEXT JSON) |
| `ai_chat_sessions` | AI 채팅 세션 (계정별 격리) |
| `ai_chat_messages` | AI 채팅 메시지 (MEDIUMTEXT) |

기동 후 확인:
```bash
mysql -u heap_user -p HEAPDB -e "SHOW TABLES;"
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
cd /opt/heap-analyzer
bash restart.sh

# 로그 확인
tail -f /opt/genspark/webapp_dump/logs/heapdump-analyzer.log
```

기동 성공 시 `http://<서버IP>:18080` 접속 → 로그인 페이지(admin/shinhan@10).

---

## 6. 트러블슈팅

| 증상 | 원인 / 해결 |
|------|-------------|
| `Communications link failure` | 방화벽/`bind-address` 확인, `mysql -h` 접속 테스트 |
| `Access denied for user 'heap_user'` | 비밀번호 오타 또는 `'heap_user'@'%'`/`'@localhost'` 누락 |
| `Unknown database 'HEAPDB'` | DB 미생성 또는 대소문자 불일치 (Linux는 대소문자 구분) |
| `ENC(...) 복호화 실패` | `HEAP_ANALYZER_ENCRYPTION_KEY` 환경변수 불일치 (암호화/복호화 키가 동일해야 함) |
| 테이블 미생성 | `spring.jpa.hibernate.ddl-auto=update` 확인, `heap_user`에 DDL 권한 확인 |
| 한글 깨짐 | DB/Connection charset이 `utf8mb4`인지 확인 (`SHOW VARIABLES LIKE 'character_set%';`) |

---

## 7. 이관 패키지 체크리스트

- [ ] `target/heap-analyzer-2.0.0.jar`
- [ ] `application.properties` (사내 환경값으로 수정 — DB URL/계정/암호화)
- [ ] `restart.sh`
- [ ] `heap_enc.sh`, `heap_dec.sh`
- [ ] `README-DEPLOY.md` (본 문서)
- [ ] DB: HEAPDB 생성 + heap_user 계정 + 권한 부여 완료
- [ ] Eclipse MAT CLI 설치 (`/opt/mat/ParseHeapDump.sh`)
- [ ] 디렉토리 권한 (`/opt/heapdumps/`, 로그 디렉토리)
- [ ] 방화벽 (앱 포트 18080, DB 포트 3306)
- [ ] (선택) SSH/SCP 원격 수집용 `sscuser` 계정 + SSH 키 교환
- [ ] (선택) LLM API Key, ES(RAG) 접속 정보
- [ ] **기본 admin 비밀번호 변경**
