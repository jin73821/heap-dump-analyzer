package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.entity.AiInsightEntity;
import com.heapdump.analyzer.repository.AiInsightRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.transaction.Transactional;
import java.io.File;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * AI 인사이트 (분석 결과 데이터) 관리 (Phase 4A-5, Phase 7-5).
 *
 * 책임:
 *   - DB(`ai_insights` 테이블) 저장/조회/삭제
 *   - 파일 폴백 (`ai_insight.json` 레거시) + DB 마이그레이션
 *   - 기존 파일 기반 데이터를 DB 로 일회성 마이그레이션
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
        try {
            AiInsightEntity entity = aiInsightRepository.findByFilename(filename)
                    .orElse(new AiInsightEntity());
            entity.setFilename(filename);
            entity.setModel(insightData.get("model") != null ? String.valueOf(insightData.get("model")) : null);
            entity.setSeverity(insightData.get("severity") != null ? String.valueOf(insightData.get("severity")) : null);
            if (insightData.get("latencyMs") instanceof Number) {
                entity.setLatencyMs(((Number) insightData.get("latencyMs")).longValue());
            }
            Map<String, Object> toSave = new LinkedHashMap<>(insightData);
            toSave.put("analysedAt", System.currentTimeMillis());
            entity.setInsightData(objectMapper.writeValueAsString(toSave));
            entity.setAnalysedAt(LocalDateTime.now());
            aiInsightRepository.save(entity);
            logger.info("[AI-Insight] Saved to DB for '{}' (severity={})", filename, entity.getSeverity());
        } catch (Exception e) {
            logger.error("[AI-Insight] Failed to save to DB for '{}' — type={}, msg={}",
                filename, e.getClass().getSimpleName(), e.getMessage(), e);
            throw new RuntimeException("AI 인사이트 DB 저장 실패: " + e.getMessage(), e);
        }
    }

    /**
     * DB 에서 AI 인사이트 결과를 불러옴. 없으면 파일 폴백 후 DB 마이그레이션.
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
                saveAiInsight(filename, data);
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
     * 기존 ai_insight.json 파일들을 DB 로 일회성 마이그레이션.
     * `restoreResultsFromDisk()` 시점에 호출된다.
     */
    @SuppressWarnings("unchecked")
    public void migrateAiInsightsToDb() {
        File dataDir = new File(config.getDataDirectory());
        if (!dataDir.exists()) return;
        int migrated = 0;
        File[] subDirs = dataDir.listFiles(File::isDirectory);
        if (subDirs == null) return;
        for (File dir : subDirs) {
            File insightFile = new File(dir, AI_INSIGHT_FILE);
            if (!insightFile.exists()) continue;
            File resultFile = new File(dir, RESULT_JSON);
            String filename = null;
            if (resultFile.exists()) {
                try {
                    HeapAnalysisResult r = objectMapper.readValue(resultFile, HeapAnalysisResult.class);
                    filename = r.getFilename();
                } catch (Exception ignored) {}
            }
            if (filename == null) {
                filename = dir.getName() + ".hprof";
            }
            if (aiInsightRepository.existsByFilename(filename)) continue;
            try {
                Map<String, Object> data = objectMapper.readValue(insightFile, Map.class);
                saveAiInsight(filename, data);
                migrated++;
            } catch (Exception e) {
                logger.warn("[AI-Insight Migration] Failed for {}: {}", insightFile.getAbsolutePath(), e.getMessage());
            }
        }
        if (migrated > 0) {
            logger.info("[AI-Insight Migration] {} file-based insights migrated to database", migrated);
        }
    }
}
