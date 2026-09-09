package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.RagKnowledgeDoc;
import com.heapdump.analyzer.repository.RagKnowledgeDocRepository;
import com.heapdump.analyzer.repository.RagLearningDocRepository;
import com.heapdump.analyzer.service.ChromaSearchService;
import com.heapdump.analyzer.service.RagCorpusService;
import com.heapdump.analyzer.service.RagIndexRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 코퍼스 API 라우팅·헤더 검증 (standalone MockMvc — Spring 컨텍스트/DB 없음).
 *
 * <p>실제 인가는 SecurityConfig 의 경로 패턴이 담당하므로 여기서는 <b>매핑이 존재하는지</b>와
 * 다운로드 헤더(charset·Content-Disposition·no-store), 가져오기의 dryRun/오류 계약을 본다.
 */
class RagCorpusEndpointTest {

    private RagLearningDocRepository learningRepo;
    private RagKnowledgeDocRepository knowledgeRepo;
    private ChromaSearchService chroma;
    private RagIndexRunner runner;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        learningRepo = Mockito.mock(RagLearningDocRepository.class);
        knowledgeRepo = Mockito.mock(RagKnowledgeDocRepository.class);
        chroma = Mockito.mock(ChromaSearchService.class);
        runner = Mockito.mock(RagIndexRunner.class);

        Mockito.when(learningRepo.findAllByOrderBySortOrderAscDocIdAsc()).thenReturn(List.of());
        Mockito.when(learningRepo.count()).thenReturn(0L);
        Mockito.when(learningRepo.findByDocIdIn(any())).thenReturn(List.of());
        Mockito.when(learningRepo.findByContentHashIn(any())).thenReturn(List.of());
        Mockito.when(learningRepo.findByTitleNormIn(any())).thenReturn(List.of());
        Mockito.when(knowledgeRepo.count()).thenReturn(0L);
        Mockito.when(knowledgeRepo.findAllByOrderByOriginIdAsc()).thenReturn(List.of());
        Mockito.when(knowledgeRepo.findByOriginIdIn(any())).thenReturn(List.of());
        Mockito.when(knowledgeRepo.findByContentHashIn(any())).thenReturn(List.of());
        Mockito.when(knowledgeRepo.findByTitleNormIn(any())).thenReturn(List.of());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("success", true);
        stats.put("sources", List.of(new LinkedHashMap<>(Map.of(
                "sourceType", "csv", "chunks", 170, "docs", 84, "syntheticChunks", 11, "excluded", false))));
        stats.put("totalChunks", 834);
        Mockito.when(chroma.sourceTypeStats()).thenReturn(stats);
        Mockito.when(runner.status()).thenReturn(new LinkedHashMap<>(Map.of("state", "none", "running", false)));

        RagCorpusService corpus = new RagCorpusService(learningRepo, knowledgeRepo);
        mvc = MockMvcBuilders.standaloneSetup(new RagCorpusController(corpus, chroma, runner)).build();
    }

    @Test
    @DisplayName("색인 현황은 Chroma 실측에 DB 건수를 붙여 돌려준다")
    void indexStatusJoinsDbCounts() throws Exception {
        mvc.perform(get("/api/settings/rag/index-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChunks").value(834))
                .andExpect(jsonPath("$.sources[0].sourceType").value("csv"))
                .andExpect(jsonPath("$.sources[0].managed").value(true))
                .andExpect(jsonPath("$.dbLearningRows").value(0));
    }

    @Test
    @DisplayName("학습 내보내기는 CSV charset 과 첨부 헤더를 명시한다")
    void learningExportHeaders() throws Exception {
        mvc.perform(get("/api/settings/rag/learning/export"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("charset=UTF-8")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    @DisplayName("지식 1건 내보내기는 .md, 여러 건이면 zip")
    void knowledgeExportShape() throws Exception {
        RagKnowledgeDoc d = doc(1L, "kb-1", "제목");
        Mockito.when(knowledgeRepo.findById(1L)).thenReturn(Optional.of(d));
        mvc.perform(get("/api/settings/rag/knowledge/export").param("ids", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("kb-1.md")));

        Mockito.when(knowledgeRepo.findById(2L)).thenReturn(Optional.of(doc(2L, "kb-2", "둘째")));
        mvc.perform(get("/api/settings/rag/knowledge/export").param("ids", "1,2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".zip")));
    }

    @Test
    @DisplayName("dryRun 은 판정만 하고 아무것도 저장하지 않는다")
    void dryRunDoesNotWrite() throws Exception {
        String csv = "id,category,title,content,tags,source,severity,created_at,synthetic\r\n"
                   + "oom-1,heap,제목,본문입니다,,,high,2026-04-25,false\r\n";
        mvc.perform(multipart("/api/settings/rag/learning/import")
                        .file(new MockMultipartFile("files", "a.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.totals.parsed").value(1))
                .andExpect(jsonPath("$.totals.new").value(1))
                .andExpect(jsonPath("$.totals.inserted").value(0));
        Mockito.verify(learningRepo, Mockito.never()).save(any());
    }

    @Test
    @DisplayName("검증 오류가 있으면 400 + 아무것도 저장하지 않는다")
    void validationErrorWritesNothing() throws Exception {
        String csv = "id,category,title,content,tags,source,severity,created_at,synthetic\r\n"
                   + "bad-1,heap,제목,본문,,,urgent,,false\r\n";
        mvc.perform(multipart("/api/settings/rag/learning/import")
                        .file(new MockMultipartFile("files", "a.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("dryRun", "false"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.files[0].errors[0].error", org.hamcrest.Matchers.containsString("severity")));
        Mockito.verify(learningRepo, Mockito.never()).save(any());
    }

    @Test
    @DisplayName("파일이 없으면 400")
    void noFilesIsBadRequest() throws Exception {
        mvc.perform(multipart("/api/settings/rag/knowledge/import"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("색인이 이미 실행 중이면 409 + 현재 상태")
    void busyIndexReturnsConflict() throws Exception {
        Mockito.doThrow(new IllegalStateException("색인이 이미 실행 중입니다"))
                .when(runner).start(anyString(), Mockito.anyBoolean(), anyString());
        mvc.perform(post("/api/settings/rag/index/run")
                        .contentType("application/json")
                        .content("{\"sources\":\"csv\",\"reset\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("허용되지 않은 색인 대상은 400 — 화이트리스트 밖 값이 명령으로 가면 안 된다")
    void badSourceIsRejected() throws Exception {
        Mockito.doThrow(new IllegalArgumentException("허용되지 않은 색인 대상입니다: csv; rm -rf /"))
                .when(runner).start(anyString(), Mockito.anyBoolean(), anyString());
        mvc.perform(post("/api/settings/rag/index/run")
                        .contentType("application/json")
                        .content("{\"sources\":\"csv; rm -rf /\"}"))
                .andExpect(status().isBadRequest());
    }

    private static RagKnowledgeDoc doc(Long id, String originId, String title) {
        RagKnowledgeDoc d = new RagKnowledgeDoc();
        d.setId(id);
        d.setOriginId(originId);
        d.setTitle(title);
        d.setContent("본문");
        d.setSeverity("info");
        return d;
    }

    // ── 예외 → JSON 계약 (2026-09-02) ─────────────────────────
    // 종전에는 try/catch 가 없어 DB 오류가 Spring 기본 500({timestamp,status,error,path})으로
    // 나갔다 — 화면이 찾는 한국어 `error` 가 없어 "조회 실패: HTTP 500: {...}" 가 표에 떴다.

    @Test
    @DisplayName("DB 오류는 우리 계약(success/code/error)을 지킨 500 으로 나간다")
    void unexpectedFailureIsJson() throws Exception {
        Mockito.when(knowledgeRepo.findAllByOrderByOriginIdAsc())
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB 연결 끊김"));

        mvc.perform(get("/api/settings/rag/knowledge/docs"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("RAG_CORPUS_ERROR"))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("색인 현황 조회 실패도 같은 계약 — 화면이 표에 그대로 쓸 수 있어야 한다")
    void indexStatusFailureIsJson() throws Exception {
        Mockito.when(learningRepo.count()).thenThrow(new IllegalStateException("세션 없음"));

        mvc.perform(get("/api/settings/rag/index-status"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("IllegalArgumentException 은 500 이 아니라 400 을 유지한다 (핸들러 우선순위)")
    void badInputStays400() throws Exception {
        Mockito.when(learningRepo.findAllByOrderBySortOrderAscDocIdAsc())
                .thenThrow(new IllegalArgumentException("정렬 키가 올바르지 않습니다"));

        mvc.perform(get("/api/settings/rag/learning/docs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("정렬 키가 올바르지 않습니다"));
    }

    @Test
    @DisplayName("Spring 이 정한 4xx(파라미터 누락 등)는 500 으로 바뀌지 않는다")
    void springBindingErrorsKeepTheirStatus() throws Exception {
        // ⚠ Exception 핸들러만 두면 이 요청이 400 → 500 이 된다(실제로 그렇게 됐다).
        mvc.perform(multipart("/api/settings/rag/knowledge/import"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("내보낼 문서가 없으면 평문이 아니라 JSON 404 — 화면이 사유를 읽을 수 있어야 한다")
    void emptyExportIsJson() throws Exception {
        Mockito.when(knowledgeRepo.findAllByOrderByOriginIdAsc()).thenReturn(List.of());

        mvc.perform(get("/api/settings/rag/knowledge/export"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("내보낼 문서가 없습니다")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"success\":false")));
    }
}
