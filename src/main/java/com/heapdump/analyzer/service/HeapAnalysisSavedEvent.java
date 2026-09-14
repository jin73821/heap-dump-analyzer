package com.heapdump.analyzer.service;

/**
 * 힙 분석 결과가 DB 에 저장된 직후 발행되는 이벤트 (2026-09-14).
 * {@code HeapDumpAnalyzerService} 가 GC 로그 서비스를 직접 주입하지 않도록 두는 얇은 결합 — 수신자는
 * {@link GcLogMatchService} (먼저 도착해 매칭 후보가 없던 GC 로그를 이 덤프로 재평가).
 */
public record HeapAnalysisSavedEvent(String filename) {}
