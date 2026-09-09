package com.heapdump.analyzer.util;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 CSV 읽기/쓰기. 순수 static, Spring 비의존.
 *
 * <p><b>왜 직접 구현하나</b> — 저장소에 CSV 리더가 없고 commons-csv 의존성도 없다. 쓰기는
 * {@code ServerController.csvCell} 하나가 전부였다(이제 {@link #cell(Object)} 로 옮기고 그쪽이 위임한다).
 *
 * <p><b>왜 이 파서가 필요한가</b> — 레거시 지식 CSV(`rag-knowledge-20260430.csv`)는 {@code tags} 에
 * <b>인용 없는 콤마</b>가 들어가 84행 전부 컬럼이 밀렸다(정상 8컬럼 행 0건). 그래서 이 파서는
 * <b>행 길이를 교정하지 않고 그대로 돌려준다</b> — 호출부가 "9개를 기대했는데 11개" 라고 보고해야
 * 사용자가 원인을 안다. 조용히 맞춰주면 같은 사고가 반복된다.
 *
 * <p>쓰기는 파이썬 {@code csv.QUOTE_MINIMAL} 과 규칙이 같고 줄 끝이 <b>CRLF</b>, BOM 없음이라
 * 기존 코퍼스 파일과 <b>바이트 동일</b>하다({@code CsvCodecGoldenTest} 가 고정).
 */
public final class CsvCodec {

    private CsvCodec() {}

    /** 파일 선두 BOM. Excel 로 저장한 CSV 를 되가져올 때 첫 컬럼명이 오염되는 것을 막는다. */
    private static final char BOM = '\uFEFF';

    /**
     * RFC 4180 파싱. 인용 필드 안의 콤마·개행·{@code ""} 이스케이프를 처리하고 LF/CRLF 를 모두 받는다.
     *
     * <p>⚠ 행마다 컬럼 수가 다를 수 있다 — <b>보정하지 않는다</b>(위 클래스 주석 참조).
     *
     * @return 행 목록. 헤더도 첫 행으로 포함된다. 빈 입력이면 빈 목록.
     */
    public static List<String[]> parse(String text) {
        List<String[]> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) return rows;
        if (text.charAt(0) == BOM) text = text.substring(1);

        List<String> field = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean rowStarted = false;   // 개행만 있는 꼬리가 빈 행을 만들지 않도록

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    // "" 는 리터럴 따옴표, 홀로 있는 " 는 인용 종료
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQuotes = false;
                } else {
                    cur.append(c);   // 인용 안에서는 콤마·CR·LF 가 전부 리터럴
                }
                continue;
            }
            switch (c) {
                case '"':
                    // 필드 시작 위치의 따옴표만 인용 시작. 중간의 " 는 리터럴로 둔다(관대한 파싱).
                    if (cur.length() == 0) inQuotes = true; else cur.append('"');
                    rowStarted = true;
                    break;
                case ',':
                    field.add(cur.toString());
                    cur.setLength(0);
                    rowStarted = true;
                    break;
                case '\r':
                    // CRLF 의 CR 은 버린다. 홀로 있는 CR 도 줄바꿈으로 본다.
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    field.add(cur.toString());
                    cur.setLength(0);
                    rows.add(field.toArray(new String[0]));
                    field.clear();
                    rowStarted = false;
                    break;
                case '\n':
                    field.add(cur.toString());
                    cur.setLength(0);
                    rows.add(field.toArray(new String[0]));
                    field.clear();
                    rowStarted = false;
                    break;
                default:
                    cur.append(c);
                    rowStarted = true;
            }
        }
        // 마지막 행에 개행이 없으면 여기서 마감. 개행으로 끝났으면 빈 행을 만들지 않는다.
        if (rowStarted || cur.length() > 0 || !field.isEmpty()) {
            field.add(cur.toString());
            rows.add(field.toArray(new String[0]));
        }
        return rows;
    }

    /**
     * CSV 직렬화. <b>CRLF 종료, BOM 없음</b> — 기존 코퍼스 파일과 바이트 호환을 유지하기 위해서다.
     *
     * <p>⚠ BOM 을 붙이지 말 것: 색인기 {@code sources.from_csv()} 가 {@code encoding="utf-8"}
     * (utf-8-sig 아님)로 열기 때문에 BOM 이 있으면 첫 키가 {@code "\uFEFFid"} 가 되어 KeyError 로 죽는다.
     */
    public static String write(String[] header, List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        if (header != null) appendRow(sb, header);
        if (rows != null) for (String[] r : rows) appendRow(sb, r);
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, String[] cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells[i]));
        }
        sb.append("\r\n");
    }

    /**
     * 셀 이스케이프 (RFC 4180 최소 인용). {@code ServerController.csvCell} 에서 옮겨 왔다 —
     * 두 곳에 같은 규칙이 흩어지면 언젠가 갈라진다.
     */
    public static String cell(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuote) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
