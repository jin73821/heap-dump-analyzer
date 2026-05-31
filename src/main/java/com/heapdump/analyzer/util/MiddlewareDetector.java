package com.heapdump.analyzer.util;

import com.heapdump.analyzer.model.HistogramEntry;
import com.heapdump.analyzer.model.ThreadInfo;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 힙 덤프에서 미들웨어(WAS) 벤더와 버전을 추정한다.
 *
 * <p>판정 근거는 <b>패키지 prefix</b> 와 <b>System Properties</b> 다.
 *
 * <p><b>신호 우선순위:</b>
 * <ol>
 *   <li><b>System Properties (권위적)</b> — {@code catalina.home}(Tomcat)·{@code weblogic.Name}
 *       (WebLogic)·{@code jeus.home}(JEUS) 등 벤더 고유 프로퍼티가 있으면 해당 벤더로 확정하고,
 *       {@code weblogic.version}/{@code jeus.version} 같은 버전 프로퍼티가 있으면 버전을 그대로 채택.</li>
 *   <li><b>스레드 스택/객체타입/컨텍스트 로더 (주 신호)</b> — WAS 요청 스레드는 거의 반드시
 *       프레임워크 프레임(예: {@code at org.apache.catalina.core...})을 거치므로 식별력이 높다.</li>
 *   <li><b>히스토그램 (보조)</b> — MAT 리포트는 retained 상위 ~25개만 담아 WAS 클래스가 잘 안 잡힘.</li>
 * </ol>
 *
 * <p>여러 벤더가 동시에 잡히면 <b>매칭 점수 최다 1개</b>를 대표로 반환한다(System Properties 마커는
 * 큰 가중치). 오탐 방지를 위해 {@link #MIN_MATCH} 미만은 무시한다.
 *
 * <p><b>버전 한계:</b> System Properties 가 없으면(구 분석분/추출 실패) 클래스·패키지 이름에는
 * 버전이 없어 best-effort 이름 매칭만 시도하며 대부분 {@code null} 이다.
 */
public final class MiddlewareDetector {

    private MiddlewareDetector() {}

    /** 대표 벤더로 인정하기 위한 최소 매칭 점수 (오탐 방지). */
    private static final int MIN_MATCH = 3;

    /** System Properties 마커 1건이 주는 가중치 (권위적이라 다른 신호를 압도). */
    private static final int SYSPROP_WEIGHT = 100;

    /**
     * 알려진 미들웨어 벤더.
     * @param prefixes        클래스/스택 prefix
     * @param sysPropMarkers  벤더 확정용 sysprop 키 (존재 시 권위적)
     * @param versionPropKeys 직접 버전 sysprop 키
     * @param productToken    경로/명령행 sysprop 값에서 버전을 anchor 할 제품 토큰 (예: "tomcat" → "apache-tomcat-9.0.87")
     */
    public enum Vendor {
        WEBLOGIC ("Oracle WebLogic", "WAS",
                new String[]{"weblogic."},
                new String[]{"weblogic.Name", "weblogic.home", "weblogic.RootDirectory"},
                new String[]{"weblogic.version"}, "wlserver"),
        JEUS     ("TmaxSoft JEUS", "WAS",
                new String[]{"jeus."},
                new String[]{"jeus.home", "jeus.server.name", "jeus.baseport"},
                new String[]{"jeus.version"}, "jeus"),
        TOMCAT   ("Apache Tomcat", "WAS",
                new String[]{"org.apache.catalina.", "org.apache.coyote.", "org.apache.tomcat."},
                new String[]{"catalina.home", "catalina.base"},
                new String[]{"catalina.version", "tomcat.version"}, "tomcat"),
        WEBSPHERE("IBM WebSphere", "WAS",
                new String[]{"com.ibm.ws.", "com.ibm.websphere."},
                new String[]{"was.install.root", "server.root", "ws.ext.dirs"},
                new String[]{"com.ibm.websphere.productVersion"}, "websphere"),
        JETTY    ("Eclipse Jetty", "WAS",
                new String[]{"org.eclipse.jetty."},
                new String[]{"jetty.home", "jetty.base"},
                new String[]{"jetty.version"}, "jetty"),
        WILDFLY  ("JBoss/WildFly", "WAS",
                new String[]{"org.jboss.as.", "org.wildfly.", "org.jboss.web.", "org.jboss.servlet."},
                new String[]{"jboss.home.dir", "jboss.server.name", "jboss.server.base.dir"},
                new String[]{"jboss.home.version"}, "wildfly"),
        UNDERTOW ("Undertow", "WAS",
                new String[]{"io.undertow."},
                new String[]{},
                new String[]{}, null),
        RESIN    ("Caucho Resin", "WAS",
                new String[]{"com.caucho."},
                new String[]{"resin.home"},
                new String[]{"resin.version"}, "resin");

        private final String displayName;
        private final String category;
        private final String[] prefixes;
        private final String[] sysPropMarkers;
        private final String[] versionPropKeys;
        private final String productToken;
        Vendor(String displayName, String category, String[] prefixes,
               String[] sysPropMarkers, String[] versionPropKeys, String productToken) {
            this.displayName = displayName; this.category = category; this.prefixes = prefixes;
            this.sysPropMarkers = sysPropMarkers; this.versionPropKeys = versionPropKeys;
            this.productToken = productToken;
        }
        public String displayName() { return displayName; }
        public String category()    { return category; }
    }

    /** 감지 결과. {@link #vendor} 가 null 이면 미식별. */
    public static final class Result {
        public final Vendor vendor;
        /** 대표 벤더의 누적 매칭 점수. 미식별 시 0. */
        public final int matchCount;
        /** 버전 (sysprop 권위적 → 이름 best-effort 순). 미상이면 null. */
        public final String version;

        Result(Vendor vendor, int matchCount, String version) {
            this.vendor = vendor; this.matchCount = matchCount; this.version = version;
        }
        public boolean detected()   { return vendor != null; }
        public String displayName() { return vendor != null ? vendor.displayName() : null; }
        public String category()    { return vendor != null ? vendor.category() : null; }

        static final Result NONE = new Result(null, 0, null);
    }

    /** 하위호환 오버로드 (System Properties 없이). */
    public static Result detect(List<HistogramEntry> entries, List<ThreadInfo> threads) {
        return detect(entries, threads, null);
    }

    /**
     * 히스토그램 + 스레드 정보 + System Properties 에서 대표 미들웨어를 추정한다.
     * @param entries 히스토그램 엔트리 (보조 신호). null 허용.
     * @param threads 스레드 정보 (주 신호). null 허용.
     * @param sysProps System Properties (권위적 신호: 벤더 확정 + 버전). null 허용.
     */
    public static Result detect(List<HistogramEntry> entries, List<ThreadInfo> threads,
                                Map<String, String> sysProps) {
        int[] counts = new int[Vendor.values().length];

        // 1) 주 신호 — 스레드 스택/객체타입/컨텍스트 로더
        if (threads != null) {
            for (ThreadInfo t : threads) {
                if (t == null) continue;
                addPrefixOccurrences(counts, t.getStackTrace());
                addStartsWith(counts, t.getObjectType());
                addStartsWith(counts, t.getContextClassLoader());
            }
        }

        // 2) 보조 신호 — 히스토그램 클래스명 (top-N 한정)
        if (entries != null) {
            for (HistogramEntry e : entries) {
                if (e != null) addStartsWith(counts, e.getClassName());
            }
        }

        // 3) 권위적 신호 — System Properties 마커 (큰 가중치)
        if (sysProps != null && !sysProps.isEmpty()) {
            for (Vendor v : Vendor.values()) {
                for (String marker : v.sysPropMarkers) {
                    if (hasProp(sysProps, marker)) { counts[v.ordinal()] += SYSPROP_WEIGHT; }
                }
            }
        }

        Vendor best = null;
        int bestCount = 0;
        for (Vendor v : Vendor.values()) {
            int c = counts[v.ordinal()];
            if (c > bestCount) { bestCount = c; best = v; }
        }
        if (best == null || bestCount < MIN_MATCH) return Result.NONE;

        return new Result(best, bestCount, resolveVersion(best, sysProps, entries, threads));
    }

    private static boolean hasProp(Map<String, String> props, String key) {
        String v = props.get(key);
        return v != null && !v.isBlank();
    }

    /** 문자열 시작이 어느 벤더 prefix 와 일치하면 카운트(클래스명/객체타입/로더용). */
    private static void addStartsWith(int[] counts, String s) {
        if (s == null || s.isEmpty()) return;
        s = s.trim();
        for (Vendor v : Vendor.values()) {
            for (String p : v.prefixes) {
                if (s.startsWith(p)) { counts[v.ordinal()]++; return; }
            }
        }
    }

    /** 텍스트(스택 트레이스) 안에 등장하는 prefix 출현 횟수를 누적(부분 문자열). */
    private static void addPrefixOccurrences(int[] counts, String text) {
        if (text == null || text.isEmpty()) return;
        for (Vendor v : Vendor.values()) {
            int c = 0;
            for (String p : v.prefixes) c += countOccurrences(text, p);
            counts[v.ordinal()] += c;
        }
    }

    private static int countOccurrences(String text, String sub) {
        int n = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) { n++; idx += sub.length(); }
        return n;
    }

    // ── 버전 해석: 직접 sysprop → 경로/명령행 토큰 → 이름 best-effort ──────
    private static String resolveVersion(Vendor vendor, Map<String, String> sysProps,
                                         List<HistogramEntry> entries, List<ThreadInfo> threads) {
        if (sysProps != null && !sysProps.isEmpty()) {
            // 1) 직접 버전 프로퍼티 (권위적)
            for (String key : vendor.versionPropKeys) {
                String v = sysProps.get(key);
                if (v != null && !v.isBlank()) return v.trim();
            }
            // 2) 경로/명령행 sysprop 값에서 제품 토큰 anchor 로 버전 추출 (예: apache-tomcat-9.0.87)
            String v = versionFromSysPropValues(vendor, sysProps);
            if (v != null) return v;
        }
        return detectVersionFromNames(vendor, entries, threads);
    }

    /** 제품 토큰 뒤(최대 12자 이내)의 버전 숫자를 sysprop 값들에서 찾는다. */
    private static String versionFromSysPropValues(Vendor vendor, Map<String, String> sysProps) {
        if (vendor.productToken == null) return null;
        Pattern p = Pattern.compile(
                Pattern.quote(vendor.productToken) + "[^0-9]{0,12}(\\d{1,2}(?:\\.\\d{1,3}){1,3})",
                Pattern.CASE_INSENSITIVE);
        for (String val : sysProps.values()) {
            if (val == null || val.isEmpty()) continue;
            Matcher m = p.matcher(val);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    // 이름에 버전 토큰이 박힌 드문 경우만 best-effort (메이저 WAS 는 대개 null).
    private static final Pattern VERSION_TOKEN =
            Pattern.compile("[._]v?(\\d{1,2}(?:[._]\\d{1,3}){1,3})(?:[._]|$|[A-Za-z])");

    private static String detectVersionFromNames(Vendor vendor, List<HistogramEntry> entries,
                                                  List<ThreadInfo> threads) {
        if (entries != null) {
            for (HistogramEntry e : entries) {
                if (e == null) continue;
                String v = versionFromName(vendor, e.getClassName());
                if (v != null) return v;
            }
        }
        if (threads != null) {
            for (ThreadInfo t : threads) {
                if (t == null) continue;
                String v = versionFromName(vendor, t.getObjectType());
                if (v != null) return v;
            }
        }
        return null;
    }

    private static String versionFromName(Vendor vendor, String name) {
        if (name == null) return null;
        boolean ofVendor = false;
        for (String p : vendor.prefixes) { if (name.startsWith(p)) { ofVendor = true; break; } }
        if (!ofVendor) return null;
        Matcher m = VERSION_TOKEN.matcher(name);
        return m.find() ? m.group(1).replace('_', '.') : null;
    }
}
