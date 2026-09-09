package com.heapdump.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rag-settings.html 템플릿 렌더 스모크 (2026-08-29, Chroma 모드 추가).
 *
 * <p>방어 대상: Thymeleaf 3 는 <b>{@code th:inline} 이 없는 평범한 {@code <script>} 안에서도</b>
 * 여는 대괄호 2연속({@code [[})을 인라인 표현식 시작으로 해석한다. 파싱이 실패하면 응답이 이미
 * chunked 로 나가던 중이라 종료 마커가 없어 브라우저에는 {@code ERR_INCOMPLETE_CHUNKED_ENCODING}
 * 빈 화면만 뜬다 — 정적 픽스처 검증으로는 절대 못 잡는 부류다.
 *
 * <p>707줄짜리 이 템플릿에는 그동안 스모크 테스트가 없었다. Chroma 카드를 추가하면서
 * 정확히 이 지뢰를 밟을 수 있는 편집을 했으므로 함께 도입한다.
 *
 * <p>여기서는 SpringTemplateEngine 없이 순수 Thymeleaf 엔진으로 템플릿을 직접 렌더해
 * <b>파싱 단계</b>만 확인한다. 배너 fragment 의 {@code sec:} 속성은 미등록 dialect 라 그대로
 * 통과하므로 별도 스텁이 필요 없다.
 */
class RagSettingsTemplateSmokeTest {

    /** {@code ${_csrf.token}} / {@code ${_csrf.headerName}} 해석용 최소 스텁. */
    public static final class CsrfStub {
        public String getToken()      { return "test-token"; }
        public String getHeaderName() { return "X-CSRF-TOKEN"; }
        public String getParameterName() { return "_csrf"; }
    }

    private static String render(String template, Map<String, Object> model) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        // 순수 TemplateEngine 은 OGNL 을 요구한다 — Boot 와 동일하게 SpEL 을 쓰는
        // SpringTemplateEngine 을 사용하고, SpEL 평가 컨텍스트를 직접 넣어준다.
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        // 배너 fragment 가 @{/logout} 같은 컨텍스트 상대 링크를 쓰므로 IWebContext 가 필요하다.
        // (없으면 배너에서 렌더가 중단돼 정작 검사하려는 본문까지 도달하지 못한다)
        JakartaServletWebApplication app = JakartaServletWebApplication.buildApplication(new MockServletContext());
        IWebExchange exchange = app.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse());

        WebContext ctx = new WebContext(exchange);
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(new StaticApplicationContext(), null));
        model.forEach(ctx::setVariable);
        return engine.process(template, ctx);
    }

    @Test
    @DisplayName("rag-settings.html 이 파싱/렌더된다 (Chroma 카드 포함)")
    void ragSettingsRenders() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);

        // 신규 Chroma 카드
        assertTrue(html.contains("id=\"cardChroma\""), "Chroma 카드가 렌더되지 않았다");
        assertTrue(html.contains("value=\"chroma\""), "Search Mode 에 chroma 옵션이 없다");
        assertTrue(html.contains("id=\"chromaUrl\""));
        assertTrue(html.contains("id=\"chromaCollection\""));
        assertTrue(html.contains("id=\"chromaSpace\""));
        assertTrue(html.contains("id=\"chromaToken\""));
        assertTrue(html.contains("/api/settings/rag/chroma/test"), "Chroma 테스트 엔드포인트 호출부가 없다");
        assertTrue(html.contains("local-onnx"), "임베딩 provider 에 local-onnx 가 없다");
        assertTrue(html.contains("id=\"minScoreHint\""), "모드별 Min Score 힌트 요소가 없다");

        // 2026-08-29 — Chroma 연동 항목 상시 표시 + 연동 상태 패널
        assertTrue(html.contains("id=\"cardChromaStatus\""), "Chroma 연동 상태 카드가 없다");
        assertTrue(html.contains("/api/settings/rag/chroma/status"), "상태 엔드포인트 호출부가 없다");
        assertTrue(html.matches("(?s).*<button[^>]*id=\"chromaStatusRefresh\"[^>]*refresh-btn.*")
                || html.matches("(?s).*<button[^>]*refresh-btn[^>]*id=\"chromaStatusRefresh\".*"),
                "새로고침 버튼에 refresh-btn 이 없다 — 비-ADMIN IIFE 가 비활성화한다");
        assertTrue(html.contains("id=\"chromaModeBadge\"") && html.contains("id=\"embModeBadge\""), "모드 배지가 없다");
        // 2026-08-29(2차) — 모드 중심 재구성: 검색 모드 카드 + ES/Chroma 상호 배제 + 2열 래퍼
        assertTrue(html.contains("id=\"cardSearchMode\""), "검색 모드 카드가 없다");
        assertTrue(html.contains("id=\"cardEsConnection\"") && html.contains("id=\"cardEsAuth\""),
                "ES 카드가 모드로 제어되는 id 를 갖고 있지 않다");
        assertTrue(html.contains("class=\"rag-cols\"") && html.contains("/.rag-cols"), "2열 래퍼가 없다");
        assertTrue(html.contains("class=\"mode-grid\"") && html.contains("onModeRadioChange"), "모드 라디오가 없다");
        // 라디오 4개 + 값 보관용 select 는 함께 있어야 한다(기존 JS 가 ragMode.value 를 읽는다)
        for (String m : new String[]{"keyword", "semantic-server", "semantic-client", "chroma"}) {
            assertTrue(html.contains("name=\"ragModeRadio\" id=\"mode") && html.contains("value=\"" + m + "\""),
                    "모드 " + m + " 선택지가 없다");
        }
        assertTrue(html.contains("id=\"ragMode\""), "값 보관용 select 가 사라졌다 — buildConnPayload 가 깨진다");
        // ES 계열 모드에서 Chroma 준비 상태를 알리는 요약 줄 + 미리보기 토글
        assertTrue(html.contains("id=\"backendSummary\"") && html.contains("id=\"chromaPeekBtn\""), "백엔드 요약 줄이 없다");
        assertTrue(html.contains("function toggleChromaPeek"), "Chroma 미리보기 토글이 없다");
        // 카드 가시성은 _show() 한 곳에서만 — 개별 style.display 직접 조작이 흩어지면 모드 배제가 샌다
        assertTrue(html.contains("_show('cardEsConnection'") && html.contains("_show('cardChroma'"),
                "모드별 카드 표시가 _show() 를 경유하지 않는다");

        // 결함 ①: label.tog 는 common.css .tog{display:none} 에 걸려 컨트롤 전체가 사라졌다
        assertTrue(html.contains("<input type=\"checkbox\" class=\"tog\" id=\"chromaSslVerify\""), "TLS 토글 input 마크업이 다르다");
        assertTrue(html.contains("<label class=\"tog-track\" for=\"chromaSslVerify\">"), "TLS 토글 track 이 없다");
        assertFalse(html.contains("<label class=\"tog\">"), "보이지 않는 label.tog 패턴이 남아 있다");
        // 결함 ②: 미정의 클래스
        assertFalse(html.contains("btn-test"), "미정의 btn-test 클래스가 남아 있다");
        // 결함 ③: chroma 모드 Test Connection 라우팅
        assertTrue(html.contains("id=\"ragTestBtn\"") && html.contains("function testChromaStack"), "chroma 모드 테스트 라우팅이 없다");
        // 결함 ④: local-onnx 안내
        assertTrue(html.contains("onEmbProviderChange"), "provider 변경 핸들러가 없다");
        assertTrue(html.contains("id=\"embSidecarFillBtn\"") && html.contains("id=\"ragEmbApiKeyRow\"")
                && html.contains("id=\"ragEmbModelRow\"") && html.contains("id=\"embKnnBox\""), "local-onnx 안내 요소가 없다");
        // 문구
        assertFalse(html.contains("세 가지 검색 모드"), "안내문이 아직 3개 모드다");
        assertFalse(html.contains("RAG (Elasticsearch) Configuration"), "제목이 아직 Elasticsearch 전용이다");
        // 저장 피드백은 토스트 1회 — 완료 메시지와 경고를 따로 띄우면 같은 좌표에 포개진다
        assertFalse(html.contains("주의: chroma 모드인데"), "저장 경고를 별도 토스트로 띄우고 있다");
        assertTrue(html.contains("RAG 설정 저장 완료 — 단,"), "경고가 완료 메시지에 합쳐지지 않았다");

        // 폴링 금지(함정 38): 이 페이지의 인라인 스크립트에 setInterval 이 있으면 안 된다
        // (배너 fragment 의 시스템 상태 폴러는 managedInterval 경유라 검사 범위에서 뺀다)
        int inlineStart = html.indexOf("var toast = Common.toast");
        assertTrue(inlineStart > 0, "인라인 스크립트 시작점을 찾지 못했다");
        assertFalse(html.substring(inlineStart).contains("setInterval"),
                "배경 폴링이 추가됐다 — SessionTimeout.managedInterval 없이는 금지");

        // 기존 모드가 그대로 남아 있어야 한다 (롤백 경로)
        assertTrue(html.contains("value=\"keyword\""));
        assertTrue(html.contains("value=\"semantic-server\""));
        assertTrue(html.contains("value=\"semantic-client\""));

        // 파싱이 중간에 끊기면 여기까지 오지 못한다 — 운영에서는 ERR_INCOMPLETE_CHUNKED_ENCODING.
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
        assertFalse(html.contains("th:replace"), "미처리 th:replace 가 남았다");
        assertTrue(html.contains("test-token"), "CSRF meta 가 렌더되지 않았다");
    }

    @Test
    @DisplayName("Save All / Test Connection / Reload 은 같은 높이로 렌더된다")
    void actionButtonsShareOneSizeRule() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);

        // 2026-08-30 제보: 세 버튼 높이가 31 / 35 / 33 px 로 제각각이었다. 원인은 두 개다.
        //  ① border 가 .btn-secondary 에만 있어 Reload 만 2px 크다.
        //  ② line-height 미지정 → 한글이 섞인 라벨("Test Connection (Chroma + 임베딩)")이
        //     fallback 폰트의 큰 행상자를 그대로 높이로 가져가 4px 더 크다.
        // 둘 다 "예외 없이 조용히 어긋나는" 부류라 앵커를 박아 둔다.
        assertTrue(html.contains(".btn-primary, .btn-secondary, .btn-success {"),
                "3종 버튼의 치수 규칙이 한 곳에 모여 있지 않다 — 다시 제각각으로 갈라진다");
        assertTrue(html.contains("border: 1px solid transparent"),
                "채움 버튼에 1px transparent border 가 없다 — .btn-secondary 만 2px 커진다");
        assertTrue(html.matches("(?s).*\\.btn-primary, \\.btn-secondary, \\.btn-success \\{[^}]*line-height: 18px.*"),
                "공통 규칙에 고정 line-height 가 없다 — 한글 라벨이 라틴 라벨보다 높아진다");
        assertTrue(html.matches("(?s).*\\.btn-primary, \\.btn-secondary, \\.btn-success \\{[^}]*font-family: inherit.*"),
                "button 은 폰트를 상속하지 않는다 — inherit 이 없으면 본문과 다른 폰트로 렌더된다");

        // 액션 바 3개는 인라인 치수가 아니라 .btn-act 하나를 공유해야 한다.
        for (String label : new String[]{"Save All", "Reload"}) {
            assertTrue(html.matches("(?s).*<button[^>]*btn-act[^>]*>" + label + "</button>.*"),
                    label + " 버튼이 .btn-act 를 쓰지 않는다");
        }
        assertTrue(html.matches("(?s).*<button[^>]*id=\"ragTestBtn\"[^>]*btn-act.*"),
                "Test Connection 버튼이 .btn-act 를 쓰지 않는다");
        assertFalse(html.contains("style=\"padding:8px 20px;font-size:13px\""),
                "인라인 치수가 남아 있다 — 규칙이 두 곳으로 갈라지면 다시 어긋난다");
    }

    @Test
    @DisplayName("숫자 입력칸은 max 유무와 무관하게 같은 폭이다 (Min Score = Top-K)")
    void numberInputsShareOneWidth() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);

        // 2026-08-30 제보: Min Score 입력칸만 두 배로 넓었다(198 vs 100px).
        // 원인은 CSS 가 아니라 브라우저 기본값이다 — number 입력의 기본 폭은 `max` 자릿수에서 나오는데
        // Min Score 만 max 가 없어 텍스트 입력 기본폭이 됐다. min-width 로는 하한만 정해져 막지 못한다.
        assertTrue(html.contains(".input-num[type=\"number\"] { width: 100px; }"),
                "숫자 입력 폭이 명시돼 있지 않다 — max 없는 필드가 다시 혼자 넓어진다");
        // 텍스트 입력에도 같은 클래스를 쓴다 — 폭 고정이 거기까지 번지면 default_database 가 잘린다
        assertTrue(html.matches("(?s).*<input type=\"text\" id=\"chromaDatabase\" class=\"input-num\".*"),
                "텍스트 입력이 .input-num 을 쓰지 않는다 — type 한정이 의미를 잃었다는 뜻이다");
    }

    @Test
    @DisplayName("연동 상태의 컬렉션·사이드카 행은 이름 줄과 속성 칩 줄로 나뉜다")
    void chromaStatusRowsSplitNameAndFacts() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);

        // 2026-08-30 제보: 두 행이 ` · ` 로 이어 붙어 한 줄에 4~5개가 몰려 있었다.
        // 값 칸은 `word-break: break-all` 이라 길어지면 토큰 한가운데서 잘리기까지 했다.
        for (String id : new String[]{"cstCollection", "cstEmbedding"}) {
            assertTrue(html.contains("id=\"" + id + "\" class=\"st-val st-multi\""),
                    id + " 이 다단(st-multi) 레이아웃이 아니다 — 다시 한 줄로 몰린다");
        }
        assertTrue(html.contains("function _stMulti(") && html.contains("function _fact("),
                "이름 줄/속성 칩 헬퍼가 없다");
        assertTrue(html.contains(".st-fact { ") && html.contains(".st-facts { "),
                "속성 칩 CSS 가 없다");
        // 칩은 불가분 단위여야 줄바꿈이 속성 경계에서만 일어난다
        assertTrue(html.matches("(?s).*\\.st-fact \\{[^}]*white-space: nowrap.*"),
                "칩에 nowrap 이 없다 — 칩 내부에서 잘려 분리한 의미가 사라진다");
        // 옛 한 줄 결합이 되살아나지 않았는지
        assertFalse(html.contains("parts.join(' · ')") || html.contains("sp.join(' · ')"),
                "옛 한 줄 결합 코드가 남아 있다");
    }

    @Test
    @DisplayName("연동 상태의 서버·컬렉션·사이드카 행은 색 점이 아니라 정상/비정상 글자로 상태를 말한다")
    void chromaStatusRowsUseTextLabelsNotDots() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);

        // 2026-09-05 요청: 초록/빨강 점은 범례가 없고 색각 이상·흑백 인쇄에서 구분되지 않는다.
        int s = html.indexOf("function _stat(");
        assertTrue(s > 0, "상태 라벨 헬퍼(_stat)가 없다");
        String stat = html.substring(s, html.indexOf("\n}", s));
        assertTrue(stat.contains("'정상'") && stat.contains("'비정상'"), "라벨이 정상/비정상이 아니다");
        assertTrue(html.contains(".st-stat.ok") && html.contains(".st-stat.err") && html.contains(".st-stat.na"),
                "상태 라벨 CSS 3종이 없다");
        assertTrue(html.matches("(?s).*\\.st-stat \\{[^}]*white-space: nowrap.*"),
                "라벨에 nowrap 이 없다 — .st-line 은 break-all 이라 '비정' / '상' 으로 잘린다");

        // 연동 상태 렌더 본문에는 점이 남아 있지 않다 (Embedding 카드의 Model 출처 표시는 별개)
        int r = html.indexOf("function renderChromaStatus(");
        String body = html.substring(r, html.indexOf("\n}\n", r));
        assertFalse(body.contains("_dot("), "연동 상태 패널에 색 점이 되살아났다");
        assertTrue(body.contains("_stat('ok')") && body.contains("_stat('err')"), "상태 라벨을 쓰지 않는다");

        // 서버 도달 여부는 apiVersion 으로 가른다 — 컬렉션만 없을 때 서버 행을 '비정상' 으로 찍지 않는다
        assertTrue(body.contains("chroma.apiVersion"), "서버 도달/컬렉션 실패를 구분하지 않는다");
        assertTrue(body.contains("chroma.configuredCollection"), "컬렉션 실패 시 무엇을 못 찾았는지 말하지 않는다");

        // 셋째 상태는 뜻이 둘 — 상류가 죽어 못 본 것(미확인)과 대상이 아닌 것(미사용)
        assertTrue(body.contains("'미확인'") && body.contains("'미사용'"),
                "회색 상태의 두 뜻(미확인/미사용)을 같은 말로 뭉갠다");
    }

    @Test
    @DisplayName("local-onnx 는 Model 을 읽기 전용으로 표시한다 (입력 칸이 아니라)")
    void localOnnxShowsModelReadOnly() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);

        assertTrue(html.contains("id=\"ragEmbModelLocalRow\""), "local-onnx 전용 Model 표시 행이 없다");
        assertTrue(html.contains("id=\"ragEmbModelLocal\""), "Model 값 표시 엘리먼트가 없다");
        // 편집 칸을 살리면 안 된다 — local-onnx 에서는 앱이 model 을 전송하지 않아 입력해도 아무 일이 없다.
        assertTrue(html.contains("document.getElementById('ragEmbModelRow').style.display  = local ? 'none' : 'flex';"),
                "local-onnx 에서 편집용 Model 입력이 감춰지지 않는다");
        assertTrue(html.contains("document.getElementById('ragEmbModelLocalRow').style.display = local ? 'flex' : 'none';"),
                "읽기 전용 Model 행이 provider 에 따라 토글되지 않는다");
        assertTrue(html.contains("function renderLocalOnnxModel()"), "Model 표시 렌더 함수가 없다");
        // 실측(사이드카 /health) 값을 우선 쓰고, 없으면 서버가 준 기본값을 쓴다.
        assertTrue(html.contains("_sidecarModel = sidecar.model"),
                "상태 패널 응답에서 실측 모델명을 수집하지 않는다");
        assertTrue(html.contains("e.localOnnxModel"), "서버가 준 기본 모델명을 쓰지 않는다");
        // ⚠ 모델명을 JS 에 다시 적으면 embedder.py 를 바꿔도 화면만 옛 이름을 계속 보여준다.
        assertFalse(html.contains("(multilingual-e5-small). "),
                "JS 에 모델명 리터럴이 남아 있다 — 단일 출처는 EmbeddingService.LOCAL_ONNX_MODEL 이다");
    }

    @Test
    @DisplayName("탭 3개가 렌더되고 설정 카드는 config 패널 안에 그대로 있다")
    void tabsRenderAndConfigPanelKeepsCards() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);

        assertTrue(html.contains("class=\"tabs\""), "탭 바가 없다");
        for (String t : new String[]{"config", "knowledge", "learning"}) {
            assertTrue(html.contains("data-tab=\"" + t + "\""), t + " 탭이 없다");
            assertTrue(html.contains("id=\"panel-" + t + "\""), t + " 패널이 없다");
        }
        assertTrue(html.contains(".tab-panel { display: none; }"), "패널 전환 CSS 가 없다");

        // 기존 설정 카드를 옮기지 않고 감싸기만 했는지 — 위치로 확인한다.
        int cfg = html.indexOf("id=\"panel-config\"");
        int kb = html.indexOf("id=\"panel-knowledge\"");
        assertTrue(cfg > 0 && kb > cfg, "패널 순서가 어긋났다");
        assertTrue(html.substring(cfg, kb).contains("class=\"rag-cols\""),
                "설정 카드가 config 패널 밖으로 나갔다 — 감싸기만 해야 한다");

        // 새 엔드포인트와 다중 파일 업로드
        assertTrue(html.contains("/api/settings/rag/index-status"));
        assertTrue(html.contains("/api/settings/rag/learning/export"));
        assertTrue(html.contains("/api/settings/rag/index/run"));
        // 2026-08-31: 입력에 .vis-hidden 이 붙어 속성 사이에 클래스가 끼었다 — 의도(다중 선택)만 검사한다.
        assertTrue(html.matches("(?s).*<input type=\"file\" id=\"impFiles\"[^>]*\\bmultiple\\b.*"), "다중 파일 선택이 없다");
    }

    @Test
    @DisplayName("가져오기는 검사(dryRun) 후에만 적용할 수 있고 중복 정책을 고르게 한다")
    void importFlowIsTwoStep() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);

        assertTrue(html.contains("id=\"impApplyBtn\"") && html.contains("disabled"),
                "적용 버튼이 처음부터 활성이면 검사를 건너뛰게 된다");
        assertTrue(html.contains("runImport(true)") && html.contains("runImport(false)"),
                "검사/적용이 같은 함수의 dryRun 분기여야 판정이 어긋나지 않는다");
        assertTrue(html.contains("name=\"impDup\""), "중복 처리 선택이 없다");
        assertTrue(html.contains("offerIndexAfterImport"), "가져오기 후 색인 확인 절차가 없다");
        // multipart 는 Common.fetchJSON 으로 보낼 수 없다(Content-Type 을 건드리면 boundary 가 깨진다)
        assertTrue(html.contains("body: fd"), "FormData 전송 경로가 없다");
    }

    @Test
    @DisplayName("USER 도 탭 이동·내보내기는 되고 변경 버튼만 잠긴다")
    void tabButtonsSurviveReadOnlyIife() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", false);

        String html = render("rag-settings", model);

        assertTrue(html.contains("btn.classList.contains('tab')"),
                "탭 버튼이 비활성화되면 USER 는 3개 중 2개 탭에 못 들어간다");
        assertTrue(html.contains("btn.classList.contains('ro-allowed')"), "GET 전용 액션 예외가 없다");
        assertTrue(html.contains("class=\"btn-secondary ro-allowed\""), "내보내기 버튼에 예외 클래스가 없다");
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다");
    }

    @Test
    @DisplayName("모달 3종의 골격 클래스가 전부 스타일을 갖는다 (함정 17 — 없으면 민짜로 뜬다)")
    void modalSkeletonClassesAreStyled() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);
        String style = html.substring(html.indexOf("<style>"), html.indexOf("</style>"));

        // 2026-08-31 제보: 지식/학습 탭의 가져오기·색인 실행·문서 보기 모달이 전부 민짜로 떴다.
        // common.css 가 주는 건 .modal-ov(오버레이) / .modal-box base / .mbtn-* "색상"뿐인데,
        // 이 페이지에는 골격 클래스 정의가 아예 없었다 — 마크업은 멀쩡하고 CSS 만 없는 부류라
        // 렌더는 성공하고 화면만 무너진다. 모달을 새로 추가할 때 그대로 재발한다.
        for (String cls : new String[]{"modal-title", "modal-body", "modal-btns"}) {
            assertTrue(html.contains("class=\"" + cls + "\"") || html.contains("class=\"" + cls + " "),
                    cls + " 를 쓰는 모달이 사라졌다 — 검사 대상이 맞는지 확인할 것");
            assertTrue(style.contains("." + cls + " {"),
                    "." + cls + " 에 스타일이 없다 — 모달이 소재 그대로 렌더된다");
        }

        // .mbtn-* 는 색상 전용이라(함정 17) 형태를 페이지가 주지 않으면 브라우저 기본 버튼이 된다.
        assertTrue(style.matches("(?s).*\\.modal-btns button \\{[^}]*font-family: inherit.*"),
                ".modal-btns button 에 형태 규칙이 없다 — .mbtn-* 만으로는 기본 버튼으로 렌더된다");
        assertTrue(style.contains(".modal-btns button:disabled"),
                "disabled 표시가 없다 — 검사 전 '가져오기' 가 눌리는 버튼처럼 보인다");

        // 인라인 max-width 는 상한일 뿐이라 width 가 없으면 박스가 내용물 폭으로 쪼그라든다.
        assertTrue(style.matches("(?s).*\\.modal-box \\{[^}]*width: 92%.*"),
                ".modal-box 에 width 가 없다 — 인라인 max-width(760/560/820px) 가 무의미해진다");
    }

    @Test
    @DisplayName("내보내기는 건수를 보여 주는 확인 모달을 거친다")
    void exportGoesThroughConfirmModal() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);

        // 두 버튼 모두 곧장 다운로드하지 않고 모달을 연다 — 지식은 선택 여부로 .md/.zip 이 갈리고
        // 학습은 선택과 무관하게 전량이 나가므로, 받은 파일을 열기 전에는 확인할 방법이 없었다.
        assertTrue(html.contains("onclick=\"openExportModal('knowledge')\""), "지식 내보내기가 모달을 거치지 않는다");
        assertTrue(html.contains("onclick=\"openExportModal('learning')\""), "학습 내보내기가 모달을 거치지 않는다");
        assertFalse(html.contains("onclick=\"exportKnowledge()\"") || html.contains("onclick=\"exportLearning()\""),
                "옛 즉시 다운로드 경로가 남아 있다 — 두 경로가 갈리면 한쪽만 고쳐진다");

        assertTrue(html.contains("id=\"expModal\"") && html.contains("id=\"expCount\"") && html.contains("id=\"expFacts\""),
                "건수/부가정보를 보여 줄 자리가 없다");
        // 건수는 서버에 다시 묻지 않고 화면의 목록에서 센다 — 표와 어긋나지 않는 것이 우선이다.
        assertTrue(html.contains("function updateExportSummary()"), "범위 변경 시 건수를 다시 계산하는 경로가 없다");
        // 다운로드 버튼은 USER 도 눌러야 한다(GET 전용) — ro-allowed 가 빠지면 읽기 전용 계정이 못 받는다.
        assertTrue(html.matches("(?s).*<button[^>]*id=\"expGoBtn\"[^>]*ro-allowed.*|(?s).*ro-allowed[^>]*id=\"expGoBtn\".*"),
                "다운로드 버튼에 ro-allowed 가 없다 — 비-ADMIN 이 내보내기를 못 한다");
        assertTrue(html.contains("return true;") && html.contains("if (!ok) return;"),
                "blobDownload 의 성공 여부를 호출부가 알 수 없다 — 실패해도 완료 토스트가 뜬다");
    }

    @Test
    @DisplayName("모달의 라디오·체크박스는 카드형이고 선택 상태가 JS 로 동기화된다")
    void modalOptionsAreCards() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);
        String style = html.substring(html.indexOf("<style>"), html.indexOf("</style>"));

        assertTrue(style.contains(".opt-card {") && style.contains(".opt-card.selected {"),
                "카드형 선택지 스타일이 없다 — 기본 라디오로 되돌아간다");
        // :has() 대신 JS 토글(.mode-opt 와 동일 정책) — 라디오는 형제까지 갱신해야 이전 선택이 풀린다.
        assertTrue(html.contains("function syncOptCards(input)"), "선택 상태 동기화 함수가 없다");
        assertTrue(html.matches("(?s).*function syncOptCards\\(input\\) \\{.*querySelectorAll\\('input\\[name=.*"),
                "같은 name 의 형제를 갱신하지 않으면 라디오 두 장이 동시에 선택돼 보인다");

        // 세 그룹(중복 처리 / 색인 대상 / 내보내기 범위)이 모두 카드다
        for (String name : new String[]{"impDup", "idxSrc", "expScope"}) {
            assertTrue(html.matches("(?s).*<label class=\"opt-card[^\"]*\"[^>]*>\\s*<input type=\"radio\" name=\"" + name + "\".*"),
                    name + " 라디오가 카드로 감싸여 있지 않다");
        }
        // 파괴적 선택지는 켠 순간 빨강 — 경고문이 펼쳐지기 전에 색이 먼저 말한다.
        assertTrue(html.contains("class=\"opt-card danger\" id=\"idxResetCard\""), "전체 재색인 체크박스가 위험 카드가 아니다");
        assertTrue(style.contains(".opt-card.danger.selected {"), "위험 카드의 선택 색이 없다");
        assertTrue(html.contains("syncOptCards(box)"), "체크박스는 name 이 없어 따로 동기화해야 한다");
    }

    @Test
    @DisplayName("색인 대상 배지는 목록이 도착하기 전엔 0 이 아니라 '–' 다")
    void indexTargetBadgesDistinguishUnloadedFromZero() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);

        // 이 모달은 지식 탭에서만 열리므로 학습 목록은 아직 없을 수 있다 — 그때 "학습 0행" 은 거짓말이다.
        assertTrue(html.contains("var _kbLoaded = false, _lnLoaded = false;"),
                "도착 여부 플래그가 없다 — 미조회/실패와 0건을 구분할 수 없다");
        assertTrue(html.contains("if (!_lnLoaded) {"), "학습 목록을 모달에서 보충 조회하지 않는다");
        assertTrue(html.matches("(?s).*function _renderIdxBadges\\(\\) \\{[^}]*_kbLoaded \\?.*"),
                "배지가 도착 여부를 보지 않는다");
        assertTrue(html.contains("return Common.fetchJSON('/api/settings/rag/learning/docs')"),
                "로더가 프라미스를 돌려주지 않으면 도착 시점에 배지를 다시 그릴 수 없다");
    }

    @Test
    @DisplayName("가져오기 파일 선택은 드롭존이고 드롭 경로도 확장자를 검사한다")
    void importFilePickerIsDropzone() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);
        String style = html.substring(html.indexOf("<style>"), html.indexOf("</style>"));

        assertTrue(html.contains("class=\"dropzone\" id=\"impDrop\" for=\"impFiles\""),
                "파일 입력이 드롭존 라벨과 연결돼 있지 않다 — 클릭해도 선택창이 안 열린다");
        // ⚠ display:none 이면 포커스를 못 받아 키보드로 파일 선택에 도달할 수 없다.
        assertTrue(html.contains("id=\"impFiles\" class=\"vis-hidden\""), "입력을 시각적 숨김이 아닌 방식으로 감췄다");
        assertTrue(style.contains(".vis-hidden {") && style.contains("clip-path: inset(50%)"),
                "시각적 숨김 규칙이 없다");
        assertTrue(style.contains("#impFiles:focus-visible + .dropzone"),
                "포커스 링을 라벨이 대신 그리지 않는다 — 입력 순서를 바꾸면 인접 형제 선택자가 깨진다");

        // accept 와 드롭 검사는 같은 출처여야 한다(드롭은 accept 를 우회한다).
        assertTrue(html.contains("var _impAccept = [];") && html.contains("input.setAttribute('accept', _impAccept.join(','))"),
                "accept 목록이 두 곳으로 갈렸다 — 드롭 검사와 선택창 필터가 어긋난다");
        assertTrue(html.contains("if (!_impExtOk(f.name)) { rejected++; continue; }"),
                "드롭 경로에 확장자 검사가 없다 — 지식 탭에 .csv 가 조용히 들어간다");

        // 드롭 기본동작을 막지 않으면 브라우저가 그 파일로 페이지를 이동시킨다.
        assertTrue(html.matches("(?s).*modal\\.addEventListener\\('drop'.*e\\.preventDefault\\(\\).*"),
                "drop 기본동작을 막지 않는다 — 선택하던 내용이 통째로 날아간다");
        // FileList 는 읽기 전용이라 개별 제외에는 DataTransfer 재구성이 필요하다.
        assertTrue(html.contains("function removeImportFile(idx)") && html.contains("function _newDataTransfer()"),
                "개별 파일 제외 경로가 없다 — 하나만 빼려면 처음부터 다시 골라야 한다");
        assertTrue(html.contains("_impMerge(_impPrev,"), "선택창 경로가 누적되지 않는다 — 나눠 고르면 앞선 선택이 사라진다");
        // 같은 이름은 last-wins 교체 — 건너뛰면 고친 파일 대신 옛 내용이 조용히 올라간다.
        assertTrue(html.contains("list[at[key]] = f;"), "같은 이름 파일의 교체 경로가 없다");
        // 파일 구성이 바뀌면 앞선 검사 결과는 그 파일들에 대한 판정이 아니다.
        assertTrue(html.matches("(?s).*function onImportFilesChosen\\(\\) \\{.*getElementById\\('impResult'\\)\\.style\\.display = 'none';.*"),
                "파일이 바뀌어도 옛 검사 결과가 남는다");
    }

    @Test
    @DisplayName("색인 진행은 스피너로 알리고, 끝나면 스피너·취소 버튼이 사라진다")
    void indexProgressSpinnerTracksActiveState() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);
        String style = html.substring(html.indexOf("<style>"), html.indexOf("</style>"));

        assertTrue(html.contains("class=\"idx-spin\" id=\"idxSpin\"") && html.contains("hidden"),
                "진행 스피너가 없다");
        assertTrue(html.contains("id=\"idxCancelBtn\""), "취소 버튼을 제어할 수단이 없다");
        // @keyframes 는 common.css 에 없다 — 페이지가 자기 것을 가져야 애니메이션이 돈다.
        assertTrue(style.contains("@keyframes idx-spin"), "회전 키프레임이 없으면 스피너가 멈춰 있다");
        assertTrue(style.contains(".idx-spin[hidden] { display: none; }"), "숨김 규칙이 없다");

        // ⚠ 폴링 종료와 스피너 표시가 서로 다른 판정을 쓰면, 폴링이 멈춘 뒤에도 스피너가 영원히 돈다.
        assertTrue(html.contains("function _idxActive(d)"), "진행 판정이 한 곳에 모여 있지 않다");
        assertTrue(html.matches("(?s).*function pollIndexRun\\(\\) \\{.*var active = _idxActive\\(d\\);.*"),
                "폴링이 공통 판정을 쓰지 않는다");
        assertTrue(html.contains("document.getElementById('idxSpin').hidden = !active;")
                && html.contains("document.getElementById('idxCancelBtn').hidden = !active;"),
                "완료 후에도 스피너가 돌거나 취소 버튼이 남는다");
    }

    @Test
    @DisplayName("인라인 스크립트에 여는 대괄호 2연속이 없다 (함정 23 — 있으면 빈 화면)")
    void noDoubleBracket() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);
        assertFalse(render("rag-settings", model).contains("[" + "["),
                "Thymeleaf 가 인라인 표현식으로 해석해 템플릿 파싱이 깨진다");
    }

    @Test
    @DisplayName("RAG OFF 면 설정 카드가 서버 렌더 시점부터 잠긴다 (Enable 카드만 예외)")
    void ragDisabledLocksCardsAtRenderTime() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);
        model.put("ragEnabled", false);

        String html = render("rag-settings", model);

        // 잠금 클래스는 패널 한 곳에 붙는다 — 초기 상태를 JS 없이 렌더할 수 있어야
        // /api/settings/rag 응답 전까지 편집 가능한 화면이 보이지 않는다.
        assertTrue(html.matches("(?s).*id=\"panel-config\"[^>]*class=\"[^\"]*rag-off[^\"]*\".*")
                        || html.matches("(?s).*class=\"[^\"]*rag-off[^\"]*\"[^>]*id=\"panel-config\".*"),
                "#panel-config 에 rag-off 클래스가 서버 렌더로 붙어야 한다");
        assertTrue(html.contains("#panel-config.rag-off .card:not(#cardRagEnable)"),
                "잠금 CSS 가 Enable 카드를 제외해야 한다 — 아니면 토글 자체를 못 켠다");
        assertTrue(html.contains("id=\"cardRagEnable\""), "Enable 카드는 잠금 제외용 id 를 가져야 한다");
        assertTrue(html.contains("id=\"ragOffHint\""), "잠긴 이유를 알리는 안내가 있어야 한다");
        // JS 는 클래스와 inert 를 함께 토글한다(마우스는 CSS, 키보드·스크린리더는 inert).
        assertTrue(html.contains("toggleAttribute('inert'"), "키보드 접근도 함께 막아야 한다");
        assertTrue(html.contains("applyRagEnabledState(el.checked)"),
                "토글 실패로 되돌린 경우에도 잠금이 토글을 따라가야 한다");
    }

    @Test
    @DisplayName("RAG ON 이면 잠금 클래스가 붙지 않는다")
    void ragEnabledRendersUnlocked() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);
        model.put("ragEnabled", true);

        String html = render("rag-settings", model);
        int panel = html.indexOf("id=\"panel-config\"");
        assertTrue(panel > 0);
        String tag = html.substring(panel, html.indexOf('>', panel));
        assertFalse(tag.contains("rag-off"), "활성 상태에서는 설정 카드가 잠기면 안 된다: " + tag);
    }

    @Test
    @DisplayName("RAG 토글 실패는 서버가 준 한국어 메시지로 표기된다 (고정 문구 '변경 실패' 금지)")
    void ragToggleSurfacesServerError() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);
        model.put("ragEnabled", true);

        String html = render("rag-settings", model);
        int fn = html.indexOf("function toggleRagEnabled");
        assertTrue(fn > 0, "토글 핸들러가 있어야 한다");
        String body = html.substring(fn, html.indexOf("function buildConnPayload", fn));

        // 원시 fetch 면 401 세션 만료가 '변경 실패'로 뭉개진다(함정 27).
        assertTrue(body.contains("Common.fetchJSON('/api/settings/rag/enabled'"),
                "세션 만료·호출량 제한 분류를 위해 Common.fetchJSON 을 써야 한다");
        assertFalse(body.contains("fetch('/api/settings/rag/enabled'"),
                "원시 fetch 로 되돌아가면 상태 코드 분류가 사라진다");
        // 서버가 준 메시지를 살리는 경로
        assertTrue(body.contains("serverErrMsg(e,"), "catch 는 서버 메시지를 꺼내 보여야 한다");
        assertTrue(html.contains("function serverErrMsg"), "메시지 추출 헬퍼가 있어야 한다");
        assertTrue(html.contains("JSON.parse(e.body)"),
                "Common.fetchJSON 은 401/429 외 상태 코드에 JSON 원문을 넣으므로 body 에서 error 를 꺼내야 한다");
        // 저장 실패(persisted=false)는 적용된 채로 경고만 — 토글을 되돌리면 서버와 어긋난다.
        assertTrue(body.contains("d.persisted === false"), "설정 파일 저장 실패를 사용자에게 알려야 한다");
        assertTrue(body.contains("el.checked = !!d.enabled"),
                "성공 경로의 토글·배지·잠금은 서버 응답값을 따라야 한다");
    }

    @Test
    @DisplayName("지식·학습 탭의 실패 경로가 전부 사람이 읽을 문구로 표기된다")
    void corpusTabsSurfaceErrors() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);
        model.put("ragEnabled", true);

        String html = render("rag-settings", model);

        // 공용 헬퍼 — 상태 코드별 문구 + JSON 이 아닌 응답 방어
        assertTrue(html.contains("function httpMsg("), "상태 코드별 한국어 문구 헬퍼가 있어야 한다");
        assertTrue(html.contains("function readJsonSafe("),
                "r.json() 은 비-JSON 응답에 SyntaxError 를 던진다 — 안전 파서가 필요하다");

        // 목록·본문·삭제: e.message 원문 대신 serverErrMsg
        assertTrue(html.contains("serverErrMsg(e, '지식 목록을 불러오지 못했습니다')"));
        assertTrue(html.contains("serverErrMsg(e, '학습 목록을 불러오지 못했습니다')"));
        assertTrue(html.contains("serverErrMsg(e, '색인 현황을 불러오지 못했습니다')"));
        assertTrue(html.contains("serverErrMsg(e, '본문을 불러오지 못했습니다')"));
        assertTrue(html.contains("serverErrMsg(e, '삭제 실패')"));

        // 내보내기: 'HTTP 404' 로 뭉개지 말고 서버 본문을 읽는다
        int bd = html.indexOf("function blobDownload");
        String blob = html.substring(bd, html.indexOf("function _expChip", bd));
        assertTrue(blob.contains("readJsonSafe(r)"), "실패 응답의 본문(사유)을 읽어야 한다");
        assertFalse(blob.contains("throw new Error('HTTP ' + r.status)"),
                "서버가 보낸 '내보낼 문서가 없습니다'·세션 만료 안내가 사라진다");

        // 가져오기·색인 실행·취소: 비-JSON 응답 방어
        assertTrue(html.contains("}).then(readJsonSafe).then(function(res) {"),
                "multipart/수동 fetch 경로는 readJsonSafe 를 거쳐야 한다");
        assertTrue(html.contains("if (!res.d.files) {"),
                "우리 계약이 아닌 응답에 '문서 0 · 신규 0' 결과표를 그리면 정상 검사로 오해된다");

        // 진행 폴링이 조용히 죽지 않는다
        assertTrue(html.contains("_idxPollFail"), "폴링 실패를 세어 사용자에게 알려야 한다");
        assertTrue(html.contains("진행 표시 중단"), "폴링이 끊기면 스피너가 굳는다 — 상태를 문구로 알려야 한다");
    }

    @Test
    @DisplayName("비-ADMIN 렌더도 파싱된다 (읽기 전용 경로)")
    void ragSettingsRendersForNonAdmin() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", false);

        String html = render("rag-settings", model);
        assertTrue(html.contains("id=\"cardChroma\""));
        assertTrue(html.contains("id=\"cardChromaStatus\""), "비-ADMIN 도 연동 상태를 읽을 수 있어야 한다");
        assertTrue(html.trim().endsWith("</html>"));
    }
}
