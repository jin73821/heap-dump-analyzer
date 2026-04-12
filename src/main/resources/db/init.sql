-- ============================================================
-- Heap Dump Analyzer — MariaDB 초기화 스크��트
-- MariaDB 11.2.3 (192.168.56.9:3306)
-- root 계정으로 실행
-- ============================================================

CREATE DATABASE IF NOT EXISTS HEAPDB
  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'heap_user'@'%' IDENTIFIED BY 'heap_pass_2026';
GRANT ALL PRIVILEGES ON HEAPDB.* TO 'heap_user'@'%';
FLUSH PRIVILEGES;

-- 테이블은 JPA ddl-auto=update 로 자동 생성됩니다.
-- 이 스크립트는 DB/사용자 초기 생성용입니다.
