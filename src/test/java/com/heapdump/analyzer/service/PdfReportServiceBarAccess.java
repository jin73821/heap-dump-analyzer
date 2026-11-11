package com.heapdump.analyzer.service;

import java.util.Map;

/** 다른 패키지 테스트에서 package-private {@link PdfReportService#heapBarModel} 을 쓰기 위한 통로. */
public final class PdfReportServiceBarAccess {
    private PdfReportServiceBarAccess() {}
    public static Map<String, Object> bar(long total, Long xmx) { return PdfReportService.heapBarModel(total, xmx); }
}
