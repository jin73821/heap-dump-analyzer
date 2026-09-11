package com.heapdump.analyzer.service;

import tools.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.entity.AiInsightEntity;
import com.heapdump.analyzer.repository.AiInsightRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.transaction.Transactional;
import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * AI 인사이트 (분석 결과 데이터) 관리 (Phase 4A-5, Phase 7-5).
 *
 * 책임:
 *   - DB(`ai_insights` 테이블) 저장/조회/삭제 — **유일한 영속 저장소**
 *   - 레거시 `ai_insight.json` 파일 폴백 + DB 마이그레이션
 *   - 마이그레이션(또는 DB 에 이미 보존됨) 확인 후 레거시 파일 제거
 *
 * AI 인사이트를 파일로 새로 기록하는 경로는 없다. data/{base}/ai_insight.json 은
 * 파일 기반 저장을 쓰던 시절의 잔존물이며, 기동 시 DB 로 옮겨진 뒤 삭제된다.
 */
@Component
public class AiInsightManager {

    private static final Logger logger = LoggerFactory.getLogger(AiInsightManager.class);
    private static final String AI_INSIGHT_FILE = "ai_insight.json";
    private static final String RESULT_JSON = "result.json";

    private final AiInsightRepository aiInsightRepository;
    private final FileManagementService fileMgmt;
    private final HeapDumpConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiInsightManager(AiInsightRepository aiInsightRepository,
                            FileManagementService fileMgmt,
                            HeapDumpConfig config) {
        this.aiInsightRepository = aiInsightRepository;
        this.fileMgmt = fileMgmt;
        this.config = config;
    }

    /**
     * AI 인사이트 결과를 DB 에 저장 (기존 결과 있으면 업데이트).
     * 실패 시 스택트레이스 포함 logger.error 후 RuntimeException 으로 rethrow —
     * 호출자(컨트롤러) 가 사용자에게 실패를 노출/재시도 안내할 수 있도록 전파한다.
     */
    public void saveAiInsight(String filename, Map<String, Object> insightData) {
        persistInsight(filename, insightData, true);
    }

    /**
     * DB 영속화 공통 경로.
     *
     * @param stampNow true  = 현재 시각으로 analysedAt 스탬프 (신규 분석 저장 경로)
     *                 false = 입력 맵의 analysedAt 를 그대로 보존 (레거시 파일 마이그레이션 경로)
     *                         — 마이그레이션 시각으로 덮어쓰면 원래 분석 시각이 유실된다.
     */
    private void persistInsight(String filename, Map<String, Object> insightData, boolean stampNow) {
        try {
            AiInsightEntity entity = aiInsightRepository.findByFilename(filename)
                    .orElse(new AiInsightEntity());
            entity.setFilename(filename);
            entity.setModel(insightData.get("model") != null ? String.valueOf(insightData.get("model")) : null);
            entity.setSeverity(insightData.get("severity") != null ? String.valueOf(insightData.get("severity")) : null);
            if (insightData.get("latencyMs") instanceof Number) {
                entity.setLatencyMs(((Number) insightData.get("latencyMs")).longValue());
            }
            if (stampNow) {
                // 분석 시각을 입력 맵에도 스탬프 → 호출자(컨트롤러)가 응답에 실어 신규 완료 즉시 표시 가능
                // (이전엔 복사본에만 찍혀 새 분석 후 새로고침 전까지 '분석 시각'이 빈 값이었음)
                insightData.put("analysedAt", System.currentTimeMillis());
            }
            Map<String, Object> toSave = new LinkedHashMap<>(insightData);
            entity.setInsightData(objectMapper.writeValueAsString(toSave));
            // analysed_at 컬럼은 JSON 의 analysedAt 과 항상 같은 값을 가리키도록 유도 (없으면 현재 시각)
            entity.setAnalysedAt(toLocalDateTime(epochMillisOf(insightData.get("analysedAt"))));
            aiInsightRepository.save(entity);
            logger.info("[AI-Insight] Saved to DB for '{}' (severity={})", filename, entity.getSeverity());
        } catch (Exception e) {
            logger.error("[AI-Insight] Failed to save to DB for '{}' — type={}, msg={}",
                filename, e.getClass().getSimpleName(), e.getMessage(), e);
            throw new RuntimeException("AI 인사이트 DB 저장 실패: " + e.getMessage(), e);
        }
    }

    /**
     * DB 에서 AI 인사이트 결과를 불러옴. 없으면 레거시 파일 폴백 →
     * DB 마이그레이션 → 파일 제거 (이후부터는 DB 단일 소스).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadAiInsight(String filename) {
        try {
            Optional<AiInsightEntity> opt = aiInsightRepository.findByFilename(filename);
            if (opt.isPresent()) {
                Map<String, Object> data = objectMapper.readValue(opt.get().getInsightData(), Map.class);
                logger.info("[AI-Insight] Loaded from DB for '{}' (analysedAt={})", filename, data.get("analysedAt"));
                return data;
            }
            File target = new File(fileMgmt.resultDirectory(filename), AI_INSIGHT_FILE);
            if (target.exists()) {
                Map<String, Object> data = objectMapper.readValue(target, Map.class);
                logger.info("[AI-Insight] Loaded from file for '{}', migrating to DB", filename);
                persistInsight(filename, data, false);   // 원본 분석 시각 보존
                discardMigratedFile(target, filename);   // DB 저장 성공 후에만 도달
                return data;
            }
            logger.debug("[AI-Insight] No saved insight for '{}'", filename);
            return null;
        } catch (Exception e) {
            logger.warn("[AI-Insight] Failed to load for '{}': {}", filename, e.getMessage());
            return null;
        }
    }

    /**
     * DB 에서 AI 인사이트 결과 삭제 + 파일 잔존도 제거.
     */
    @Transactional
    public boolean deleteAiInsight(String filename) {
        try {
            if (aiInsightRepository.existsByFilename(filename)) {
                aiInsightRepository.deleteByFilename(filename);
                logger.info("[AI-Insight] Deleted from DB for '{}'", filename);
                File target = new File(fileMgmt.resultDirectory(filename), AI_INSIGHT_FILE);
                if (target.exists()) target.delete();
                return true;
            }
            File target = new File(fileMgmt.resultDirectory(filename), AI_INSIGHT_FILE);
            if (target.exists() && target.delete()) {
                logger.info("[AI-Insight] Deleted file for '{}'", filename);
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.warn("[AI-Insight] Failed to delete for '{}': {}", filename, e.getMessage());
            return false;
        }
    }

    /**
     * data/ 하위에 남은 레거시 `ai_insight.json` 을 DB 로 옮기고 파일을 제거한다.
     * `restoreResultsFromDisk()` 시점에 호출된다.
     *
     * 처리 규칙:
     *   - DB 행 없음        → 파일 내용 저장(원본 analysedAt 보존) 후 파일 삭제
     *   - DB 행 있음 + 파일이 더 최신 → DB 갱신 후 파일 삭제
     *   - DB 행 있음 + 그 외 → 이미 보존된 내용이므로 파일만 삭제
     * DB 저장이 실패하면 파일을 지우지 않는다(다음 기동에서 재시도).
     */
    @SuppressWarnings("unchecked")
    public void migrateAiInsightsToDb() {
        File dataDir = new File(config.getDataDirectory());
        if (!dataDir.exists()) return;
        int migrated = 0;
        int removed = 0;
        File[] subDirs = dataDir.listFiles(File::isDirectory);
        if (subDirs == null) return;
        for (File dir : subDirs) {
            File insightFile = new File(dir, AI_INSIGHT_FILE);
            if (!insightFile.exists()) continue;
            String filename = resolveFilename(dir);
            try {
                Map<String, Object> data = objectMapper.readValue(insightFile, Map.class);
                Optional<AiInsightEntity> existing = aiInsightRepository.findByFilename(filename);
                if (existing.isEmpty()) {
                    persistInsight(filename, data, false);
                    migrated++;
                } else if (isFileNewerThanDb(data, existing.get())) {
                    logger.info("[AI-Insight Migration] File newer than DB row for '{}' — updating DB", filename);
                    persistInsight(filename, data, false);
                    migrated++;
                }
                // 여기 도달 = 내용이 DB 에 보존됨 → 레거시 파일 제거 (파일 기반 저장 폐기)
                if (discardMigratedFile(insightFile, filename)) removed++;
            } catch (Exception e) {
                logger.warn("[AI-Insight Migration] Failed for {}: {} — file kept for retry",
                    insightFile.getAbsolutePath(), e.getMessage());
            }
        }
        if (migrated > 0) {
            logger.info("[AI-Insight Migration] {} file-based insights migrated to database", migrated);
        }
        if (removed > 0) {
            logger.info("[AI-Insight Migration] {} legacy {} file(s) removed after DB persistence",
                removed, AI_INSIGHT_FILE);
        }
    }

    /**
     * 결과 디렉토리 → 덤프 파일명.
     * 결과 디렉토리는 파일명 스킴(확장자 포함)이므로 디렉토리명이 곧 파일명이다.
     * 구 스킴(확장자 제거 base) 잔존분은 result.json 이 남아 있으면 그 filename 을 우선한다.
     */
    private String resolveFilename(File dir) {
        File resultFile = new File(dir, RESULT_JSON);
        if (resultFile.exists()) {
            try {
                HeapAnalysisResult r = objectMapper.readValue(resultFile, HeapAnalysisResult.class);
                if (r.getFilename() != null && !r.getFilename().isEmpty()) return r.getFilename();
            } catch (Exception ignored) {}
        }
        String name = dir.getName();
        return name.indexOf('.') > 0 ? name : name + ".hprof";
    }

    /** 파일의 analysedAt 이 DB 행보다 최신인지. 비교 불가 시 파일 우선(내용 유실 방지). */
    @SuppressWarnings("unchecked")
    private boolean isFileNewerThanDb(Map<String, Object> fileData, AiInsightEntity entity) {
        Long fileTs = epochMillisOf(fileData.get("analysedAt"));
        if (fileTs == null) return false;   // 파일에 시각이 없으면 DB 를 신뢰
        Long dbTs = null;
        try {
            Map<String, Object> dbData = objectMapper.readValue(entity.getInsightData(), Map.class);
            dbTs = epochMillisOf(dbData.get("analysedAt"));
        } catch (Exception ignored) {}
        if (dbTs == null && entity.getAnalysedAt() != null) {
            dbTs = entity.getAnalysedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        return dbTs == null || fileTs > dbTs;
    }

    /** DB 저장이 확인된 레거시 파일 제거. 삭제 실패는 경고만 (다음 기동에서 재시도). */
    private boolean discardMigratedFile(File insightFile, String filename) {
        if (!insightFile.exists()) return false;
        if (insightFile.delete()) {
            logger.info("[AI-Insight] Legacy file removed after DB persistence: {} (filename={})",
                insightFile.getAbsolutePath(), filename);
            return true;
        }
        logger.warn("[AI-Insight] Failed to remove legacy file: {}", insightFile.getAbsolutePath());
        return false;
    }

    private static Long epochMillisOf(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static LocalDateTime toLocalDateTime(Long epochMillis) {
        return epochMillis == null
            ? LocalDateTime.now()
            : LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }
}
