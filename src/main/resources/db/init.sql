-- ============================================================
-- Heap Dump Analyzer — MariaDB 초기화 스크��트
-- MariaDB 11.2.3 (192.168.56.9:3306)
-- root 계정으로 실행
-- ============================================================

CREATE DATABASE IF NOT EXISTS HEAPDB
  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- DB 사용자 생성: 비밀번호는 배포 환경에 맞게 직접 설정하세요.
-- 예시: CREATE USER IF NOT EXISTS 'heap_user'@'%' IDENTIFIED BY '<YOUR_DB_PASSWORD>';
-- GRANT ALL PRIVILEGES ON HEAPDB.* TO 'heap_user'@'%';
-- FLUSH PRIVILEGES;
--
-- application.properties에서 암호화된 비밀번호 설정:
--   spring.datasource.password=ENC(<암호화된 비밀번호>)
--   암호화: bash heap_enc.sh "<비밀번호>"

-- 테이블은 JPA ddl-auto=update 로 자동 생성됩니다.
-- 이 스크립트는 DB 초기 생성용입니다.
