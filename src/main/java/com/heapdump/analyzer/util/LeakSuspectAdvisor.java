package com.heapdump.analyzer.util;

import com.heapdump.analyzer.model.LeakSuspect;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leak Suspect 텍스트를 구조적으로 분석하여 카테고리, 설명, 조언, 심각도를 생성한다.
 *
 * 분석 흐름:
 * 1. parseContext() — MAT 텍스트에서 클래스명, 인스턴스 수, 메모리 크기/비율, 축적 대상 등 추출
 * 2. 알려진 라이브러리 매칭 — 패키지 prefix 기반으로 특화된 동적 설명 생성
 * 3. 패턴 기반 fallback — 키워드 매칭으로 일반 카테고리 분류
 */
public final class LeakSuspectAdvisor {

    private LeakSuspectAdvisor() {}

    // ─── 텍스트 파싱용 정규식 ──────────────────────────────────────────────

    private static final Pattern INSTANCE_CLASS_PATTERN = Pattern.compile(
            "(\\d[\\d,]*|One|one)\\s+instances?\\s+of\\s+([\\w.$\\[\\]<>]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern LOADER_PATTERN = Pattern.compile(
            "loaded by\\s+(.*?)(?:\\s*@\\s*0x[\\da-fA-F]+)?\\s+occup", Pattern.CASE_INSENSITIVE);

    private static final Pattern MEMORY_PATTERN = Pattern.compile(
            "occup(?:y|ies)\\s+([\\d,]+)\\s+\\(([\\d.]+)%\\)\\s+bytes", Pattern.CASE_INSENSITIVE);

    private static final Pattern ACCUMULATOR_PATTERN = Pattern.compile(
            "accumulated in.*?instance of\\s+([\\w.$]+).*?occup(?:y|ies)\\s+([\\d,]+)\\s+\\(([\\d.]+)%\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern REFERENCED_FROM_PATTERN = Pattern.compile(
            "referenced from.*?instance of\\s+([\\w.$\\[\\]]+)", Pattern.CASE_INSENSITIVE);

    // ─── 알려진 라이브러리 규칙 ────────────────────────────────────────────

    private static final KnownLibrary[] KNOWN_LIBRARIES = {
        // APM / Monitoring
        new KnownLibrary("com.newrelic.", "New Relic APM", "APM Agent 메모리 점유",
            (ctx) -> "New Relic APM 에이전트의 " + ctx.simpleClassName + "가 힙의 " + ctx.percentage + "% ("
                + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + (ctx.accumulatorClass != null
                    ? "메모리의 대부분이 " + ctx.accumulatorSimple + "에 축적되어 있으며, 이는 바이트코드 계측(instrumentation) 과정에서 변환된 클래스 정보를 캐싱하는 구조입니다. "
                    : "바이트코드 계측(instrumentation) 에이전트가 변환된 클래스 메타데이터를 메모리에 보관하고 있습니다. ")
                + "배포된 애플리케이션의 클래스 수가 많을수록 점유량이 증가합니다.",
            (ctx) -> "<ol>"
                + "<li>New Relic 에이전트 설정(newrelic.yml)에서 불필요한 계측 대상을 제외하세요 (class_transformer.excludes).</li>"
                + "<li>에이전트 버전을 최신으로 업그레이드하여 메모리 최적화 패치를 적용하세요.</li>"
                + "<li>힙 크기(-Xmx) 대비 에이전트 점유율이 " + (ctx.percentage >= 30 ? "과도합니다. 힙을 늘리거나 계측 범위를 축소하세요." : "높은 편입니다. 계측 설정 검토를 권장합니다.") + "</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.dynatrace.", "Dynatrace", "APM Agent 메모리 점유",
            (ctx) -> "Dynatrace APM 에이전트의 " + ctx.simpleClassName + "가 힙의 " + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "바이트코드 계측 에이전트가 모니터링 대상 클래스의 메타데이터를 캐싱하는 과정에서 메모리를 사용합니다.",
            (ctx) -> "<ol>"
                + "<li>Dynatrace 에이전트 설정에서 불필요한 계측 대상(센서 규칙)을 제외하세요.</li>"
                + "<li>에이전트 버전을 최신으로 업그레이드하여 메모리 최적화를 적용하세요.</li>"
                + "<li>힙 크기 대비 에이전트 점유율을 모니터링하고 필요 시 -Xmx를 조정하세요.</li>"
                + "</ol>"
        ),

        // WAS (Web Application Server)
        new KnownLibrary("jeus.", "JEUS WAS", "WAS 요청/세션 누적",
            (ctx) -> "JEUS 서블릿 엔진의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + (ctx.simpleClassName.toLowerCase().contains("inputstream") || ctx.simpleClassName.toLowerCase().contains("outputstream")
                    ? "HTTP 요청/응답 스트림이 처리 완료 후 해제되지 않고 누적되고 있으며, 요청 처리 중 예외 발생이나 비동기 처리 미완료가 주요 원인입니다."
                    : "WAS 내부 객체가 정리되지 않고 누적되고 있으며, 요청 처리 생명주기 관리를 점검해야 합니다."),
            (ctx) -> "<ol>"
                + "<li>요청 처리 코드에서 InputStream/OutputStream을 명시적으로 close하거나 try-with-resources를 사용하세요.</li>"
                + "<li>JEUS 서버의 요청 타임아웃 설정을 검토하고, 장시간 대기 요청을 강제 종료하도록 설정하세요.</li>"
                + "<li>서블릿 필터에서 요청 본문(body)을 캐싱하는 로직이 있는지 확인하고, 불필요한 캐싱을 제거하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("org.apache.catalina.", "Apache Tomcat", "WAS 내부 객체 누적",
            (ctx) -> "Apache Tomcat의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "Tomcat 내부의 요청 처리, 세션 관리, 또는 커넥터 관련 객체가 정리되지 않고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Tomcat의 maxKeepAliveRequests, connectionTimeout 설정을 검토하세요.</li>"
                + "<li>세션 관련이라면 session-timeout 및 세션에 저장하는 데이터 크기를 줄이세요.</li>"
                + "<li>Connector의 maxThreads, acceptCount 설정이 트래픽에 적합한지 확인하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("org.apache.tomcat.", "Apache Tomcat", "WAS 내부 객체 누적",
            (ctx) -> "Apache Tomcat의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Tomcat 설정(server.xml)에서 커넥터 및 스레드 풀 설정을 검토하세요.</li>"
                + "<li>웹 애플리케이션의 리소스 해제(close/destroy) 로직을 점검하세요.</li>"
                + "<li>Tomcat 버전 업그레이드 또는 알려진 메모리 관련 이슈를 확인하세요.</li>"
                + "</ol>"
        ),

        // ORM / Persistence
        new KnownLibrary("org.hibernate.", "Hibernate ORM", "ORM 캐시/세션 누적",
            (ctx) -> "Hibernate ORM의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "영속성 컨텍스트(1차 캐시), 쿼리 캐시, 또는 엔티티 메타데이터가 과도하게 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>장기 실행 트랜잭션에서 EntityManager/Session을 주기적으로 flush + clear 하세요.</li>"
                + "<li>2차 캐시(Ehcache, Caffeine 등)의 최대 크기와 TTL 설정을 검토하세요.</li>"
                + "<li>N+1 쿼리 문제를 해결하여 불필요한 엔티티 로딩을 줄이세요.</li>"
                + "</ol>"
        ),

        // Spring Framework
        new KnownLibrary("org.springframework.", "Spring Framework", "Spring 내부 객체 누적",
            (ctx) -> "Spring Framework의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "Bean 컨텍스트, AOP 프록시, 이벤트 리스너, 또는 요청 스코프 객체가 과도하게 생성되었을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>ApplicationContext 내 Bean 등록 수를 점검하고, 불필요한 컴포넌트 스캔 범위를 줄이세요.</li>"
                + "<li>@EventListener 또는 ApplicationListener 등록 후 해제되지 않는 리스너가 없는지 확인하세요.</li>"
                + "<li>요청 스코프(@RequestScope) Bean이 적절히 정리되는지 점검하세요.</li>"
                + "</ol>"
        ),

        // Logging
        new KnownLibrary("org.apache.logging.log4j", "Log4j", "로깅 프레임워크 메모리 점유",
            (ctx) -> "Log4j 로깅 프레임워크의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "로그 이벤트 버퍼, 설정 객체, 또는 Appender 내부 큐가 과도하게 쌓여 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>AsyncAppender 사용 시 버퍼 크기(bufferSize)를 적절히 제한하세요.</li>"
                + "<li>로그 레벨을 WARN 이상으로 올려 불필요한 로그 이벤트 생성을 줄이세요.</li>"
                + "<li>Log4j 설정에서 GC-free logging 모드 사용을 검토하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("ch.qos.logback", "Logback", "로깅 프레임워크 메모리 점유",
            (ctx) -> "Logback 로깅 프레임워크의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>AsyncAppender의 queueSize, discardingThreshold 설정을 검토하세요.</li>"
                + "<li>로그 레벨을 조정하여 불필요한 DEBUG/TRACE 로그를 줄이세요.</li>"
                + "<li>로그 메시지에 대용량 객체를 포함하지 않도록 주의하세요.</li>"
                + "</ol>"
        ),

        // Network / IO
        new KnownLibrary("io.netty.", "Netty", "네트워크 버퍼 누수",
            (ctx) -> "Netty 프레임워크의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "ByteBuf 또는 Channel 관련 객체가 해제(release)되지 않고 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>ByteBuf 사용 후 반드시 release()를 호출하거나 ReferenceCountUtil.release()를 사용하세요.</li>"
                + "<li>-Dio.netty.leakDetection.level=PARANOID 옵션으로 누수 위치를 정확히 추적하세요.</li>"
                + "<li>ChannelHandler에서 메시지를 소비하지 않고 전달하지 않는 경우가 없는지 점검하세요.</li>"
                + "</ol>"
        ),

        // Connection Pool
        new KnownLibrary("com.zaxxer.hikari", "HikariCP", "커넥션 풀 누수",
            (ctx) -> "HikariCP 커넥션 풀의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "DB 커넥션이 반환되지 않거나, 커넥션 풀 자체가 과도한 메모리를 사용하고 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>leakDetectionThreshold 설정(기본값 0)을 활성화하여 커넥션 누수를 감지하세요 (권장: 30000ms).</li>"
                + "<li>maximumPoolSize를 적절히 설정하고, 사용 후 Connection을 반드시 close하세요.</li>"
                + "<li>maxLifetime과 idleTimeout 설정을 검토하여 오래된 커넥션을 정리하세요.</li>"
                + "</ol>"
        ),

        // Serialization
        new KnownLibrary("com.fasterxml.jackson.", "Jackson", "직렬화 메타데이터 누적",
            (ctx) -> "Jackson 직렬화 라이브러리의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "ObjectMapper의 내부 캐시(SerializerCache, DeserializerCache)가 과도하게 증가했을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>ObjectMapper를 매번 새로 생성하지 말고, 싱글턴으로 재사용하세요.</li>"
                + "<li>다형성 직렬화(@JsonTypeInfo) 사용 시 타입 수가 과도하지 않은지 확인하세요.</li>"
                + "<li>Jackson 버전을 최신으로 업그레이드하여 캐시 관련 최적화를 적용하세요.</li>"
                + "</ol>"
        ),

        // Elasticsearch
        new KnownLibrary("org.elasticsearch.", "Elasticsearch", "검색 엔진 버퍼 누적",
            (ctx) -> "Elasticsearch 클라이언트의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>검색 응답의 scroll/pit 컨텍스트를 사용 후 반드시 해제(clearScroll)하세요.</li>"
                + "<li>Bulk 요청 크기와 클라이언트 버퍼 설정을 검토하세요.</li>"
                + "<li>RestHighLevelClient 사용 시 close()가 적절히 호출되는지 확인하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("co.elastic.", "Elastic", "검색 엔진 버퍼 누적",
            (ctx) -> "Elastic 클라이언트의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>검색 응답의 scroll/pit 컨텍스트를 사용 후 반드시 해제하세요.</li>"
                + "<li>Bulk 요청 크기를 적절히 제한하세요.</li>"
                + "<li>클라이언트 인스턴스를 재사용하고, 사용 후 close()를 호출하세요.</li>"
                + "</ol>"
        ),

        // JDK 내부 클래스
        new KnownLibrary("java.util.zip.", "JDK ZIP", "ZIP 파일 리소스 누수",
            (ctx) -> "JDK의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + (ctx.referencedFromClass != null
                    ? ctx.referencedFromClass + "에 의해 참조되고 있으며, "
                    : "")
                + "ZipFile이 사용 후 닫히지 않아 native 메모리와 함께 힙 메모리도 누적되고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>ZipFile, JarFile 사용 후 반드시 close()를 호출하거나 try-with-resources를 사용하세요.</li>"
                + "<li>ClassLoader가 JAR 파일을 반복 로딩하는 경우가 없는지 확인하세요.</li>"
                + "<li>URLClassLoader 사용 시 close()를 통해 열려있는 JAR 파일을 해제하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("java.lang.reflect.", "JDK Reflection", "리플렉션 메타데이터 누적",
            (ctx) -> "JDK Reflection API의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "프레임워크의 리플렉션 캐시, 동적 프록시, 또는 어노테이션 처리 메타데이터가 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>동적 프록시(java.lang.reflect.Proxy)가 과도하게 생성되지 않는지 확인하세요.</li>"
                + "<li>프레임워크(Spring, Hibernate 등)의 리플렉션 캐시 설정을 검토하세요.</li>"
                + "<li>불필요한 런타임 어노테이션 스캐닝 범위를 줄이세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("java.lang.Class", "JDK Class 메타데이터", "클래스 메타데이터 누적",
            (ctx) -> "java.lang.Class 인스턴스 " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "동적으로 생성된 클래스(프록시, CGLib, Javassist 등)가 과도하게 많거나, ClassLoader가 정리되지 않아 클래스 메타데이터가 누적되었을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>동적 프록시/CGLib 클래스 생성 수를 모니터링하고, 캐싱을 활용하세요.</li>"
                + "<li>-XX:MaxMetaspaceSize를 설정하여 무한 증가를 방지하세요.</li>"
                + "<li>ClassLoader 누수가 동반되지 않았는지 GC Root 경로를 확인하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("java.nio.", "JDK NIO Buffer", "NIO 버퍼 누수",
            (ctx) -> "JDK NIO의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "Direct/Heap ByteBuffer가 해제되지 않고 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>DirectByteBuffer 사용 시 명시적으로 Cleaner를 통해 해제하거나, 풀링(pooling)을 사용하세요.</li>"
                + "<li>NIO Channel을 사용 후 반드시 close()를 호출하세요.</li>"
                + "<li>-XX:MaxDirectMemorySize를 설정하여 Direct Buffer의 무한 증가를 방지하세요.</li>"
                + "</ol>"
        ),

        // ─── APM / 모니터링 (추가) ──────────────────────────────────────
        new KnownLibrary("datadog.trace.", "Datadog APM", "APM Agent 메모리 점유",
            (ctx) -> "Datadog APM 에이전트의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "트레이싱 에이전트가 계측 메타데이터와 스팬(span) 데이터를 캐싱하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>dd-java-agent 설정에서 불필요한 계측 대상을 제외하세요 (dd.trace.classes.exclude).</li>"
                + "<li>에이전트 버전을 최신으로 업그레이드하여 메모리 최적화를 적용하세요.</li>"
                + "<li>트레이스 샘플링 비율(dd.trace.sample.rate)을 조정하여 수집량을 줄이세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.datadog.", "Datadog APM", "APM Agent 메모리 점유",
            (ctx) -> "Datadog 에이전트의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>dd-java-agent 설정에서 불필요한 계측 대상을 제외하세요.</li>"
                + "<li>에이전트 버전을 최신으로 업그레이드하세요.</li>"
                + "<li>힙 크기 대비 에이전트 점유율을 모니터링하고 필요 시 -Xmx를 조정하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.navercorp.pinpoint.", "Pinpoint APM", "APM Agent 메모리 점유",
            (ctx) -> "Naver Pinpoint APM 에이전트의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "바이트코드 계측 에이전트가 추적 메타데이터를 메모리에 보관하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>pinpoint.config에서 profiler.include/exclude 설정으로 계측 범위를 줄이세요.</li>"
                + "<li>Pinpoint Agent 버전을 최신으로 업그레이드하세요.</li>"
                + "<li>샘플링 비율(profiler.sampling.rate)을 조정하여 수집 부하를 줄이세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("io.micrometer.", "Micrometer Metrics", "메트릭 수집 메모리 점유",
            (ctx) -> "Micrometer 메트릭 라이브러리의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "메트릭 레지스트리에 등록된 메트릭/태그 조합이 과도하게 많을 수 있습니다 (카디널리티 폭발).",
            (ctx) -> "<ol>"
                + "<li>태그(tag) 값의 카디널리티가 높은 메트릭(예: userId, requestId)을 제거하세요.</li>"
                + "<li>MeterFilter를 사용하여 불필요한 메트릭을 필터링하세요.</li>"
                + "<li>메트릭 레지스트리의 등록된 meter 수를 모니터링하세요.</li>"
                + "</ol>"
        ),

        // ─── WAS / 웹서버 (추가) ────────────────────────────────────────
        new KnownLibrary("weblogic.", "Oracle WebLogic", "WAS 내부 객체 누적",
            (ctx) -> "Oracle WebLogic의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "WebLogic 내부의 요청 처리, JNDI, 또는 EJB 관련 객체가 정리되지 않고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>WebLogic 서버의 Work Manager, 스레드 풀, 커넥션 풀 설정을 검토하세요.</li>"
                + "<li>JNDI 리소스 바인딩/언바인딩이 적절한지 확인하세요.</li>"
                + "<li>WebLogic 패치/버전 업그레이드로 알려진 메모리 누수 이슈를 해결하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.ibm.ws.", "IBM WebSphere", "WAS 내부 객체 누적",
            (ctx) -> "IBM WebSphere의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>WebSphere 관리 콘솔에서 커넥션 풀, 스레드 풀, 세션 관리 설정을 검토하세요.</li>"
                + "<li>애플리케이션 재배포 시 ClassLoader 누수가 발생하지 않는지 확인하세요.</li>"
                + "<li>IBM Support에서 해당 버전의 알려진 메모리 누수 APAR를 확인하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.ibm.websphere.", "IBM WebSphere", "WAS 내부 객체 누적",
            (ctx) -> "IBM WebSphere의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>WebSphere 관리 콘솔에서 리소스 설정을 검토하세요.</li>"
                + "<li>애플리케이션 재배포 시 이전 ClassLoader 정리를 확인하세요.</li>"
                + "<li>해당 WebSphere 버전의 Fix Pack 적용 여부를 확인하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("org.jboss.", "JBoss/WildFly", "WAS 내부 객체 누적",
            (ctx) -> "JBoss/WildFly의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>standalone.xml/domain.xml에서 서브시스템 설정(datasources, infinispan 등)을 검토하세요.</li>"
                + "<li>배포된 애플리케이션의 리소스 정리(close/undeploy) 로직을 점검하세요.</li>"
                + "<li>WildFly/JBoss EAP 버전 업그레이드 또는 패치를 적용하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("org.wildfly.", "WildFly", "WAS 내부 객체 누적",
            (ctx) -> "WildFly의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>WildFly 서브시스템 설정에서 커넥션 풀, 캐시, 스레드 풀 크기를 검토하세요.</li>"
                + "<li>배포된 애플리케이션의 라이프사이클 관리를 점검하세요.</li>"
                + "<li>WildFly 최신 버전으로 업그레이드를 검토하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("io.undertow.", "Undertow", "웹서버 버퍼 누적",
            (ctx) -> "Undertow 웹서버의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "I/O 버퍼 풀 또는 요청 처리 객체가 정리되지 않고 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Undertow의 buffer-size, buffers-per-region, io-threads 설정을 검토하세요.</li>"
                + "<li>요청 처리 핸들러에서 exchange.endExchange()가 적절히 호출되는지 확인하세요.</li>"
                + "<li>WebSocket 또는 SSE 연결이 타임아웃 없이 유지되지 않는지 점검하세요.</li>"
                + "</ol>"
        ),

        // ─── 한국형 웹 프레임워크 ────────────────────────────────────────
        new KnownLibrary("websquare.", "WebSquare (인스웨이브)", "UI 프레임워크 세션/요청 누적",
            (ctx) -> "WebSquare UI 프레임워크의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "WebSquare 요청 디스패처, 세션 데이터, 또는 XML/JSON 처리 객체가 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>WebSquare 세션에 저장하는 데이터 크기와 세션 타임아웃 설정을 검토하세요.</li>"
                + "<li>대용량 그리드 데이터 조회 시 페이징 처리를 적용하여 메모리 사용을 줄이세요.</li>"
                + "<li>WebSquare 엔진 버전 업그레이드 및 관련 메모리 패치를 확인하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.inswave.websquare.", "WebSquare (인스웨이브)", "UI 프레임워크 세션/요청 누적",
            (ctx) -> "WebSquare UI 프레임워크의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>WebSquare 세션 데이터와 타임아웃 설정을 검토하세요.</li>"
                + "<li>대용량 데이터 조회 시 페이징/스트리밍 처리를 적용하세요.</li>"
                + "<li>WebSquare 엔진 버전 업그레이드를 검토하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.inswave.", "Inswave 프레임워크", "UI 프레임워크 객체 누적",
            (ctx) -> "Inswave 프레임워크의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "프레임워크 내부 객체가 정리되지 않고 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>프레임워크 세션 및 요청 처리 설정을 검토하세요.</li>"
                + "<li>대용량 데이터 전송 시 스트리밍 방식을 사용하세요.</li>"
                + "<li>Inswave 제품 최신 패치를 적용하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.proworks.", "ProWorks", "웹 프레임워크 객체 누적",
            (ctx) -> "ProWorks 프레임워크의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "프레임워크 내부 요청 처리 또는 세션 관련 객체가 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>ProWorks 프레임워크의 세션 관리 및 타임아웃 설정을 검토하세요.</li>"
                + "<li>요청 처리 과정에서 대용량 데이터 캐싱이 발생하지 않는지 확인하세요.</li>"
                + "<li>ProWorks 최신 버전 패치를 적용하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.wmatrix.", "WMatrix", "웹 프레임워크 객체 누적",
            (ctx) -> "WMatrix 프레임워크의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "프레임워크 내부 객체가 정리되지 않고 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>WMatrix 프레임워크의 세션 및 캐시 설정을 검토하세요.</li>"
                + "<li>요청/응답 처리 시 메모리 해제가 적절히 이루어지는지 확인하세요.</li>"
                + "<li>WMatrix 최신 버전 패치를 적용하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("wmatrix.", "WMatrix", "웹 프레임워크 객체 누적",
            (ctx) -> "WMatrix 프레임워크의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>WMatrix 프레임워크의 세션 및 캐시 설정을 검토하세요.</li>"
                + "<li>요청/응답 처리 시 메모리 해제를 확인하세요.</li>"
                + "<li>WMatrix 최신 패치를 적용하세요.</li>"
                + "</ol>"
        ),

        // ─── 웹 프레임워크 (추가) ────────────────────────────────────────
        new KnownLibrary("org.glassfish.jersey.", "Jersey JAX-RS", "REST 프레임워크 메모리 점유",
            (ctx) -> "Jersey JAX-RS 프레임워크의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "REST 리소스 메타데이터, HK2 서비스 로케이터, 또는 요청 스코프 객체가 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Jersey ResourceConfig에 등록된 리소스/프로바이더 수를 최소화하세요.</li>"
                + "<li>HK2 ServiceLocator의 shutdown()이 적절히 호출되는지 확인하세요.</li>"
                + "<li>Jersey/HK2 버전을 최신으로 업그레이드하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("io.vertx.", "Vert.x", "리액티브 프레임워크 버퍼 누적",
            (ctx) -> "Vert.x 프레임워크의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "이벤트 버스 메시지, HTTP 요청 버퍼, 또는 Verticle 내부 상태가 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>EventBus 메시지 소비자가 적절히 등록 해제되는지 확인하세요.</li>"
                + "<li>HTTP 클라이언트/서버의 연결 풀 설정과 타임아웃을 검토하세요.</li>"
                + "<li>Buffer 객체를 사용 후 적절히 해제하세요.</li>"
                + "</ol>"
        ),

        // ─── ORM / 데이터 접근 (추가) ────────────────────────────────────
        new KnownLibrary("org.apache.ibatis.", "MyBatis", "ORM 세션/캐시 누적",
            (ctx) -> "MyBatis ORM의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "SqlSession 캐시, Configuration 메타데이터, 또는 매퍼 프록시가 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>SqlSession을 사용 후 반드시 close()하세요 (try-with-resources 권장).</li>"
                + "<li>1차 캐시(localCacheScope)를 STATEMENT로 설정하여 세션 단위 캐시를 비활성화하세요.</li>"
                + "<li>2차 캐시 사용 시 flushInterval과 size 설정을 검토하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("org.mybatis.", "MyBatis", "ORM 세션/캐시 누적",
            (ctx) -> "MyBatis의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>SqlSession을 사용 후 반드시 close()하세요.</li>"
                + "<li>캐시 설정(localCacheScope, flushInterval)을 검토하세요.</li>"
                + "<li>MyBatis 버전을 최신으로 업그레이드하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("org.eclipse.persistence.", "EclipseLink JPA", "ORM 캐시/세션 누적",
            (ctx) -> "EclipseLink JPA의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "영속성 컨텍스트, 공유 캐시, 또는 쿼리 메타데이터가 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>EntityManager를 트랜잭션 단위로 생성/닫기를 보장하세요.</li>"
                + "<li>공유 캐시(L2 cache) 크기와 무효화 정책을 검토하세요.</li>"
                + "<li>eclipselink.cache.size.default 설정을 적절히 제한하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("org.jooq.", "jOOQ", "쿼리 메타데이터 누적",
            (ctx) -> "jOOQ의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>DSLContext를 매번 새로 생성하지 말고 재사용하세요.</li>"
                + "<li>대량 쿼리 결과를 fetchLazy()로 스트리밍 처리하세요.</li>"
                + "<li>jOOQ 버전을 최신으로 업그레이드하세요.</li>"
                + "</ol>"
        ),

        // ─── DB 드라이버 / 커넥션 풀 (추가) ──────────────────────────────
        new KnownLibrary("com.mysql.", "MySQL JDBC", "DB 드라이버 리소스 누수",
            (ctx) -> "MySQL JDBC 드라이버의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "DB 커넥션, Statement, 또는 ResultSet이 사용 후 닫히지 않아 누적되고 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Connection, Statement, ResultSet을 try-with-resources로 반드시 닫으세요.</li>"
                + "<li>커넥션 풀(HikariCP 등)의 leakDetectionThreshold를 활성화하세요.</li>"
                + "<li>MySQL Connector/J 버전을 최신으로 업그레이드하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("oracle.jdbc.", "Oracle JDBC", "DB 드라이버 리소스 누수",
            (ctx) -> "Oracle JDBC 드라이버의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "DB 커넥션 또는 커서(Statement/ResultSet)가 닫히지 않아 누적되고 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Connection, Statement, ResultSet을 try-with-resources로 반드시 닫으세요.</li>"
                + "<li>Oracle JDBC의 Implicit Statement Cache 크기(oracle.jdbc.implicitStatementCacheSize)를 검토하세요.</li>"
                + "<li>OCI 드라이버 대신 Thin 드라이버 사용을 검토하세요 (메모리 효율).</li>"
                + "</ol>"
        ),
        new KnownLibrary("org.postgresql.", "PostgreSQL JDBC", "DB 드라이버 리소스 누수",
            (ctx) -> "PostgreSQL JDBC 드라이버의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Connection, Statement, ResultSet을 try-with-resources로 반드시 닫으세요.</li>"
                + "<li>prepareThreshold 설정을 검토하여 서버 측 Prepared Statement 캐시를 관리하세요.</li>"
                + "<li>PgJDBC 버전을 최신으로 업그레이드하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.tmax.tibero.", "Tibero JDBC", "DB 드라이버 리소스 누수",
            (ctx) -> "Tibero JDBC 드라이버의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "DB 커넥션 또는 Statement/ResultSet이 사용 후 닫히지 않아 누적되고 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Connection, Statement, ResultSet을 try-with-resources로 반드시 닫으세요.</li>"
                + "<li>커넥션 풀 설정에서 leakDetectionThreshold를 활성화하여 누수를 감지하세요.</li>"
                + "<li>tbJDBC 드라이버를 최신 버전으로 업그레이드하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.tmax.", "TmaxSoft", "미들웨어 객체 누적",
            (ctx) -> "TmaxSoft 제품의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Tmax 미들웨어(JEUS, Tibero, ProObject 등)의 리소스 설정을 검토하세요.</li>"
                + "<li>커넥션/세션 관련 객체의 정리(close) 로직을 점검하세요.</li>"
                + "<li>제품 최신 패치를 적용하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("org.apache.commons.dbcp", "Apache DBCP", "커넥션 풀 누수",
            (ctx) -> "Apache DBCP 커넥션 풀의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "DB 커넥션이 반환되지 않거나 풀 설정이 과도할 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>removeAbandonedOnBorrow=true, removeAbandonedTimeout 설정으로 미반환 커넥션을 자동 회수하세요.</li>"
                + "<li>maxTotal, maxIdle 설정을 적절히 제한하세요.</li>"
                + "<li>가능하다면 HikariCP로 마이그레이션을 검토하세요 (더 나은 성능/메모리 효율).</li>"
                + "</ol>"
        ),
        new KnownLibrary("org.apache.tomcat.dbcp.", "Tomcat DBCP", "커넥션 풀 누수",
            (ctx) -> "Tomcat DBCP 커넥션 풀의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>removeAbandoned=true, removeAbandonedTimeout 설정을 활성화하세요.</li>"
                + "<li>maxActive, maxIdle, minIdle 설정을 검토하세요.</li>"
                + "<li>Connection 사용 후 반드시 close()를 호출하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.mchange.v2.c3p0.", "C3P0", "커넥션 풀 누수",
            (ctx) -> "C3P0 커넥션 풀의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>unreturnedConnectionTimeout을 설정하여 미반환 커넥션을 자동 회수하세요.</li>"
                + "<li>maxPoolSize, minPoolSize, maxIdleTime 설정을 검토하세요.</li>"
                + "<li>가능하다면 HikariCP로 마이그레이션을 검토하세요.</li>"
                + "</ol>"
        ),

        // ─── 캐시 (추가) ────────────────────────────────────────────────
        new KnownLibrary("net.sf.ehcache.", "Ehcache", "캐시 메모리 점유",
            (ctx) -> "Ehcache의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "캐시 엔트리가 만료되지 않거나 최대 크기가 과도하게 설정되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>각 캐시의 maxEntriesLocalHeap, timeToLiveSeconds, timeToIdleSeconds 설정을 검토하세요.</li>"
                + "<li>캐시 히트율을 모니터링하고, 사용률이 낮은 캐시는 제거하세요.</li>"
                + "<li>Off-heap 또는 디스크 기반 캐시 전환을 검토하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("org.ehcache.", "Ehcache 3", "캐시 메모리 점유",
            (ctx) -> "Ehcache 3의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>ResourcePools에서 heap(), offheap() 크기를 적절히 설정하세요.</li>"
                + "<li>Expiry 정책(TTL/TTI)을 반드시 설정하세요.</li>"
                + "<li>캐시 통계를 활성화하여 히트율과 크기를 모니터링하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.github.benmanes.caffeine.", "Caffeine Cache", "캐시 메모리 점유",
            (ctx) -> "Caffeine Cache의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "캐시 최대 크기(maximumSize) 또는 만료 정책이 설정되지 않았을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Caffeine.newBuilder()에서 maximumSize() 또는 maximumWeight()를 반드시 설정하세요.</li>"
                + "<li>expireAfterWrite() 또는 expireAfterAccess()로 만료 정책을 설정하세요.</li>"
                + "<li>캐시 통계(recordStats())를 활성화하여 히트율을 모니터링하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.hazelcast.", "Hazelcast", "분산 캐시 메모리 점유",
            (ctx) -> "Hazelcast의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "분산 맵/캐시의 데이터가 과도하게 로컬 파티션에 저장되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>IMap/ICache의 max-size, eviction-policy, time-to-live-seconds 설정을 검토하세요.</li>"
                + "<li>Near Cache 설정에서 max-size와 TTL을 제한하세요.</li>"
                + "<li>백업 카운트(backup-count)를 줄여 메모리 사용을 줄이세요.</li>"
                + "</ol>"
        ),

        // ─── 메시징 / 이벤트 (추가) ──────────────────────────────────────
        new KnownLibrary("org.apache.kafka.", "Apache Kafka", "메시징 버퍼 누적",
            (ctx) -> "Apache Kafka 클라이언트의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "Producer 버퍼, Consumer 폴링 레코드, 또는 내부 메타데이터가 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Producer의 buffer.memory, batch.size 설정을 검토하세요.</li>"
                + "<li>Consumer의 max.poll.records, fetch.max.bytes 설정을 적절히 제한하세요.</li>"
                + "<li>Consumer close() 또는 Producer close()가 적절히 호출되는지 확인하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.rabbitmq.", "RabbitMQ", "메시징 버퍼 누적",
            (ctx) -> "RabbitMQ 클라이언트의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "미처리 메시지가 큐 소비자에게 과도하게 전달(prefetch)되고 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Consumer의 prefetchCount를 적절히 설정하여 메모리에 쌓이는 메시지를 제한하세요.</li>"
                + "<li>Connection, Channel 사용 후 반드시 close()를 호출하세요.</li>"
                + "<li>메시지 처리 실패 시 NACK/reject 처리가 적절한지 확인하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.lmax.disruptor.", "LMAX Disruptor", "이벤트 링 버퍼 점유",
            (ctx) -> "LMAX Disruptor의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "RingBuffer 크기가 과도하거나 이벤트 객체가 대형일 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>RingBuffer 크기(bufferSize)를 필요한 만큼만 설정하세요 (2의 거듭제곱).</li>"
                + "<li>이벤트 객체에 대용량 데이터를 직접 저장하지 말고 참조만 유지하세요.</li>"
                + "<li>이벤트 처리 후 대용량 필드를 null로 초기화하는 EventHandler를 추가하세요.</li>"
                + "</ol>"
        ),

        // ─── Redis / NoSQL 클라이언트 (추가) ─────────────────────────────
        new KnownLibrary("redis.clients.jedis.", "Jedis (Redis)", "Redis 클라이언트 리소스 누수",
            (ctx) -> "Jedis Redis 클라이언트의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "Redis 커넥션이 풀에 반환되지 않거나, 대용량 응답 데이터가 메모리에 체류하고 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>JedisPool에서 가져온 Jedis 인스턴스를 finally 블록에서 반드시 close()하세요.</li>"
                + "<li>JedisPool의 maxTotal, maxIdle 설정을 검토하세요.</li>"
                + "<li>대용량 key/value 사용을 지양하고, 데이터 구조를 최적화하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("io.lettuce.", "Lettuce (Redis)", "Redis 클라이언트 버퍼 누적",
            (ctx) -> "Lettuce Redis 클라이언트의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "비동기 명령 버퍼 또는 Netty 기반 I/O 버퍼가 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>StatefulConnection의 명령 큐 크기가 과도하지 않은지 확인하세요.</li>"
                + "<li>ClientOptions.disconnectedBehavior 설정을 검토하세요.</li>"
                + "<li>사용하지 않는 연결을 shutdown()으로 정리하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.mongodb.", "MongoDB", "MongoDB 클라이언트 버퍼 누적",
            (ctx) -> "MongoDB 클라이언트의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>MongoClient를 싱글턴으로 재사용하고, 매번 새로 생성하지 마세요.</li>"
                + "<li>MongoCursor 사용 후 반드시 close()를 호출하세요.</li>"
                + "<li>커넥션 풀 설정(maxPoolSize, maxConnectionIdleTime)을 검토하세요.</li>"
                + "</ol>"
        ),

        // ─── HTTP 클라이언트 (추가) ──────────────────────────────────────
        new KnownLibrary("org.apache.http.", "Apache HttpClient", "HTTP 클라이언트 커넥션 누수",
            (ctx) -> "Apache HttpClient의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "HTTP 커넥션이 닫히지 않거나 커넥션 풀이 과도하게 설정되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>CloseableHttpResponse를 try-with-resources로 반드시 닫으세요.</li>"
                + "<li>PoolingHttpClientConnectionManager의 maxTotal, defaultMaxPerRoute를 검토하세요.</li>"
                + "<li>IdleConnectionMonitorThread를 사용하여 유휴 커넥션을 주기적으로 정리하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("org.apache.hc.", "Apache HttpClient 5", "HTTP 클라이언트 커넥션 누수",
            (ctx) -> "Apache HttpClient 5의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>CloseableHttpResponse를 try-with-resources로 반드시 닫으세요.</li>"
                + "<li>PoolingHttpClientConnectionManager의 커넥션 수 설정을 검토하세요.</li>"
                + "<li>HttpClient를 싱글턴으로 재사용하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("okhttp3.", "OkHttp", "HTTP 클라이언트 커넥션 누수",
            (ctx) -> "OkHttp 클라이언트의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Response.body()를 사용 후 반드시 close()하세요.</li>"
                + "<li>OkHttpClient를 싱글턴으로 재사용하고, 매번 새로 생성하지 마세요.</li>"
                + "<li>ConnectionPool 설정(maxIdleConnections, keepAliveDuration)을 검토하세요.</li>"
                + "</ol>"
        ),

        // ─── DI / 직렬화 (추가) ──────────────────────────────────────────
        new KnownLibrary("com.google.inject.", "Google Guice", "DI 컨테이너 메타데이터 누적",
            (ctx) -> "Google Guice DI 컨테이너의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "Injector 내부의 바인딩 메타데이터, JIT 바인딩, 또는 AOP 인터셉터가 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Injector를 여러 개 생성하지 말고, 하나의 루트 Injector를 재사용하세요.</li>"
                + "<li>Just-In-Time(JIT) 바인딩을 최소화하고, 명시적 바인딩을 사용하세요.</li>"
                + "<li>child Injector를 사용 후 적절히 정리하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.google.common.", "Google Guava", "유틸리티 캐시/컬렉션 누적",
            (ctx) -> "Google Guava의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "Guava Cache, Multimap, 또는 EventBus 관련 객체가 누적되어 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>CacheBuilder에서 maximumSize()와 expireAfterWrite()를 반드시 설정하세요.</li>"
                + "<li>EventBus에 등록된 구독자가 적절히 unregister()되는지 확인하세요.</li>"
                + "<li>Guava Cache 대신 Caffeine Cache로 마이그레이션을 검토하세요 (더 나은 메모리 효율).</li>"
                + "</ol>"
        ),
        new KnownLibrary("com.google.gson.", "Gson", "직렬화 메타데이터 누적",
            (ctx) -> "Gson 직렬화 라이브러리의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Gson 인스턴스를 싱글턴으로 재사용하고, 매번 new Gson()을 호출하지 마세요.</li>"
                + "<li>TypeAdapter의 과도한 등록이 없는지 확인하세요.</li>"
                + "<li>대용량 JSON 처리 시 JsonReader 스트리밍 API를 사용하세요.</li>"
                + "</ol>"
        ),

        // ─── 리액티브 / gRPC (추가) ──────────────────────────────────────
        new KnownLibrary("reactor.", "Project Reactor", "리액티브 스트림 버퍼 누적",
            (ctx) -> "Project Reactor의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "Flux/Mono 스트림의 버퍼링, 또는 구독 취소 없이 무한 스트림이 실행되고 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>Flux.buffer(), collectList() 사용 시 최대 크기를 제한하세요.</li>"
                + "<li>구독(subscribe) 후 Disposable을 통해 적절히 취소(dispose)하세요.</li>"
                + "<li>onBackpressureBuffer()에 maxSize를 설정하여 무한 버퍼링을 방지하세요.</li>"
                + "</ol>"
        ),
        new KnownLibrary("io.grpc.", "gRPC", "gRPC 채널/스트림 누수",
            (ctx) -> "gRPC의 " + ctx.simpleClassName + " " + formatInstanceCount(ctx.instanceCount) + "가 힙의 "
                + ctx.percentage + "% (" + formatBytes(ctx.bytes) + ")를 점유하고 있습니다. "
                + "ManagedChannel이 닫히지 않거나, 스트리밍 호출이 완료되지 않고 있을 수 있습니다.",
            (ctx) -> "<ol>"
                + "<li>ManagedChannel을 사용 후 반드시 shutdown() → awaitTermination()을 호출하세요.</li>"
                + "<li>스트리밍 RPC에서 onCompleted()/cancel()이 적절히 호출되는지 확인하세요.</li>"
                + "<li>maxInboundMessageSize 설정으로 대용량 메시지로 인한 메모리 폭증을 방지하세요.</li>"
                + "</ol>"
        ),
    };

    // ─── 기존 키워드 기반 fallback 규칙 ──────────────────────────────────

    private static final LeakRule[] FALLBACK_RULES = {
        new LeakRule("ClassLoader 누수",
            Pattern.compile("classloader|webappclassloader|urlclassloader|permgen|metaspace", Pattern.CASE_INSENSITIVE),
            "ClassLoader가 가비지 컬렉션되지 않아 로드된 클래스의 메타데이터와 static 필드가 메모리에 계속 남아있습니다. "
                + "주로 웹 애플리케이션 재배포(redeploy) 시 이전 ClassLoader가 해제되지 않을 때 발생합니다.",
            "<ol><li>웹 애플리케이션 재배포 시 이전 ClassLoader에 대한 참조가 남아있지 않은지 확인하세요.</li>"
                + "<li>ThreadLocal, JDBC 드라이버, 로깅 프레임워크 등이 ClassLoader 참조를 유지하는지 점검하세요.</li>"
                + "<li>Metaspace 크기 설정(-XX:MaxMetaspaceSize)을 검토하고, 재배포보다 재시작을 권장합니다.</li></ol>"
        ),
        new LeakRule("Connection/Stream 누수",
            Pattern.compile("connection|socket|inputstream|outputstream|\\bstatement\\b|resultset|datasource", Pattern.CASE_INSENSITIVE),
            "데이터베이스 연결, 네트워크 소켓, 또는 I/O 스트림이 사용 후 제대로 닫히지 않아 리소스가 누적되고 있습니다.",
            "<ol><li>try-with-resources 구문을 사용하여 Connection, Statement, ResultSet 등을 자동으로 닫으세요.</li>"
                + "<li>커넥션 풀(HikariCP 등) 설정에서 maxLifetime, leakDetectionThreshold 값을 검토하세요.</li>"
                + "<li>InputStream/OutputStream 사용 코드에서 finally 블록으로 해제를 보장하세요.</li></ol>"
        ),
        new LeakRule("Thread 누수",
            Pattern.compile("\\bthread\\b|threadpoolexecutor|pool.*thread|scheduledexecutor", Pattern.CASE_INSENSITIVE),
            "스레드가 과도하게 생성되거나 완료 후 종료되지 않아 메모리를 점유하고 있습니다. "
                + "각 스레드는 기본 1MB의 스택 메모리를 사용하므로 다수의 미종료 스레드는 심각한 메모리 부족을 유발합니다.",
            "<ol><li>ExecutorService 사용 시 shutdown()/shutdownNow()가 적절히 호출되는지 확인하세요.</li>"
                + "<li>스레드 풀 크기를 제한하고(newFixedThreadPool), newCachedThreadPool 사용을 지양하세요.</li>"
                + "<li>Timer, ScheduledExecutorService 등 주기적 작업의 취소(cancel) 및 풀 종료를 보장하세요.</li></ol>"
        ),
        new LeakRule("컬렉션 증가",
            Pattern.compile("hashmap|arraylist|concurrenthashmap|hashset|linkedlist|hashtable|\\bentries\\b", Pattern.CASE_INSENSITIVE),
            "Map, List 등 컬렉션 객체에 데이터가 지속적으로 추가되면서 제거되지 않아 메모리가 계속 증가하고 있습니다.",
            "<ol><li>컬렉션에 최대 크기 제한을 설정하거나 LRU 정책을 적용하세요.</li>"
                + "<li>더 이상 필요 없는 엔트리를 명시적으로 제거하는 정리 로직을 추가하세요.</li>"
                + "<li>약한 참조(WeakHashMap, WeakReference)를 사용하여 GC가 자동으로 회수할 수 있게 하세요.</li></ol>"
        ),
        new LeakRule("캐시/세션 누수",
            Pattern.compile("\\bcache\\b|\\bsession\\b|httpsession|ehcache|caffeine|guava.*cache", Pattern.CASE_INSENSITIVE),
            "캐시 또는 HTTP 세션 객체가 만료·제거되지 않고 계속 누적되어 메모리를 점유하고 있습니다.",
            "<ol><li>캐시에 TTL(Time-To-Live)과 최대 엔트리 수를 반드시 설정하세요.</li>"
                + "<li>HTTP 세션 타임아웃을 적절히 설정하고, 불필요한 세션 데이터 저장을 줄이세요.</li>"
                + "<li>캐시 히트율을 모니터링하고, 사용률이 낮은 캐시는 제거하거나 크기를 줄이세요.</li></ol>"
        ),
        new LeakRule("byte[]/char[] 누적",
            Pattern.compile("byte\\s*\\[|char\\s*\\[|string.*occupies|stringbuilder|byte\\s*array", Pattern.CASE_INSENSITIVE),
            "대량의 byte[] 또는 char[] 배열이 메모리를 점유하고 있습니다. "
                + "대용량 파일 읽기, 문자열 연결, 또는 버퍼 미해제 등이 원인일 수 있습니다.",
            "<ol><li>대용량 데이터를 한 번에 메모리에 로드하지 말고 스트리밍 방식으로 처리하세요.</li>"
                + "<li>StringBuilder/StringBuffer의 과도한 사용 여부를 점검하세요.</li>"
                + "<li>바이트 버퍼(ByteBuffer) 사용 시 Direct Buffer 누수가 없는지 확인하세요.</li></ol>"
        ),
        new LeakRule("Listener/Observer 누수",
            Pattern.compile("listener|observer|\\bcallback\\b|eventlistener", Pattern.CASE_INSENSITIVE),
            "이벤트 리스너 또는 콜백이 등록된 후 해제되지 않아 관련 객체들이 GC되지 못하고 있습니다.",
            "<ol><li>리스너 등록(addListener) 후 반드시 적절한 시점에 removeListener를 호출하세요.</li>"
                + "<li>WeakReference 기반 리스너 패턴을 사용하여 GC가 자동 해제할 수 있게 하세요.</li>"
                + "<li>컴포넌트 생명주기(destroy, close)에서 모든 리스너 해제 로직을 점검하세요.</li></ol>"
        ),
        new LeakRule("Finalizer 문제",
            Pattern.compile("finalizer|\\bfinalize\\b|pending.*finalization", Pattern.CASE_INSENSITIVE),
            "Finalizer 큐에 대량의 객체가 대기 중입니다. finalize() 메서드가 있는 객체는 GC 시 즉시 회수되지 않고 별도 스레드에서 처리되어야 합니다.",
            "<ol><li>finalize() 메서드 사용을 제거하고 AutoCloseable + try-with-resources 패턴으로 전환하세요.</li>"
                + "<li>Java 9+에서는 java.lang.ref.Cleaner 또는 PhantomReference를 사용하세요.</li>"
                + "<li>Finalizer 큐 크기를 모니터링하여 병목 여부를 확인하세요.</li></ol>"
        ),
        // fallback
        new LeakRule("메모리 누수 의심",
            Pattern.compile(".*", Pattern.DOTALL),
            "메모리 분석 도구가 비정상적으로 많은 메모리를 점유하는 객체를 감지했습니다. "
                + "해당 객체가 더 이상 필요하지 않음에도 GC Root에서 참조 체인이 유지되어 회수되지 못하고 있을 수 있습니다.",
            "<ol><li>해당 객체의 GC Root 경로(Shortest Paths to GC Roots)를 분석하여 어떤 참조가 유지되는지 확인하세요.</li>"
                + "<li>Dominator Tree를 확인하여 메모리를 가장 많이 점유하는 객체의 소유 관계를 파악하세요.</li>"
                + "<li>힙 덤프를 시간 간격을 두고 여러 번 수집하여 지속적으로 증가하는 객체가 있는지 비교 분석하세요.</li></ol>"
        )
    };

    // ─── 메인 분석 메서드 ────────────────────────────────────────────────

    /**
     * Suspect의 전체 텍스트를 구조적으로 분석하여 category, explanation, advice, severity를 설정한다.
     */
    public static void analyze(LeakSuspect suspect, String fullText) {
        if (suspect == null || fullText == null || fullText.isEmpty()) return;

        SuspectContext ctx = parseContext(fullText);

        // 1. 알려진 라이브러리 매칭 (className 기반)
        if (ctx.className != null) {
            for (KnownLibrary lib : KNOWN_LIBRARIES) {
                if (matchesLibrary(ctx, lib)) {
                    suspect.setCategory(lib.category);
                    suspect.setExplanation(lib.explanationFn.apply(ctx));
                    suspect.setAdvice(lib.adviceFn.apply(ctx));
                    suspect.setSeverity(ctx.severity);
                    return;
                }
            }
        }

        // 2. fallback: 키워드 기반 규칙 (동적 정보 보강)
        for (LeakRule rule : FALLBACK_RULES) {
            if (rule.pattern.matcher(fullText).find()) {
                suspect.setCategory(rule.category);
                suspect.setExplanation(enrichExplanation(rule.explanation, ctx));
                suspect.setAdvice(rule.advice);
                suspect.setSeverity(ctx.severity);
                return;
            }
        }
    }

    // ─── 텍스트 파싱 ────────────────────────────────────────────────────

    private static SuspectContext parseContext(String text) {
        SuspectContext ctx = new SuspectContext();

        // 인스턴스 수 + 클래스명
        Matcher m = INSTANCE_CLASS_PATTERN.matcher(text);
        if (m.find()) {
            String countStr = m.group(1).replace(",", "");
            ctx.instanceCount = countStr.equalsIgnoreCase("one") ? 1 : parseIntSafe(countStr);
            ctx.className = m.group(2);
            ctx.simpleClassName = simpleName(ctx.className);
        }

        // ClassLoader
        m = LOADER_PATTERN.matcher(text);
        if (m.find()) {
            ctx.classLoader = m.group(1).trim();
        }

        // 메모리
        m = MEMORY_PATTERN.matcher(text);
        if (m.find()) {
            ctx.bytes = parseLongSafe(m.group(1).replace(",", ""));
            ctx.percentage = parseDoubleSafe(m.group(2));
        }

        // 축적 대상
        m = ACCUMULATOR_PATTERN.matcher(text);
        if (m.find()) {
            ctx.accumulatorClass = m.group(1);
            ctx.accumulatorSimple = simpleName(m.group(1));
            ctx.accumulatorBytes = parseLongSafe(m.group(2).replace(",", ""));
            ctx.accumulatorPercentage = parseDoubleSafe(m.group(3));
        }

        // 참조 출처
        m = REFERENCED_FROM_PATTERN.matcher(text);
        if (m.find()) {
            ctx.referencedFromClass = simpleName(m.group(1));
        }

        // 심각도
        ctx.severity = severityFrom(ctx.percentage);

        return ctx;
    }

    // ─── 라이브러리 매칭 ─────────────────────────────────────────────────

    private static boolean matchesLibrary(SuspectContext ctx, KnownLibrary lib) {
        // className으로 먼저 매칭
        if (ctx.className != null && ctx.className.startsWith(lib.prefix)) return true;
        // accumulatorClass로도 매칭
        if (ctx.accumulatorClass != null && ctx.accumulatorClass.startsWith(lib.prefix)) return true;
        // classLoader로도 매칭 (예: com.newrelic.bootstrap.BootstrapAgent$JVMAgentClassLoader)
        if (ctx.classLoader != null && ctx.classLoader.startsWith(lib.prefix)) return true;
        return false;
    }

    // ─── fallback 설명 보강 ──────────────────────────────────────────────

    private static String enrichExplanation(String baseExplanation, SuspectContext ctx) {
        if (ctx.className == null && ctx.percentage <= 0) return baseExplanation;

        StringBuilder sb = new StringBuilder();

        // 동적 컨텍스트 요약 추가
        if (ctx.className != null) {
            sb.append(ctx.simpleClassName);
            if (ctx.instanceCount > 0) {
                sb.append(" ").append(formatInstanceCount(ctx.instanceCount));
            }
            if (ctx.percentage > 0) {
                sb.append("가 힙의 ").append(ctx.percentage).append("% (").append(formatBytes(ctx.bytes)).append(")를 점유하고 있습니다. ");
            } else {
                sb.append("가 메모리를 점유하고 있습니다. ");
            }
        }

        if (ctx.accumulatorClass != null) {
            sb.append("메모리는 주로 ").append(ctx.accumulatorSimple).append("에 축적되어 있습니다. ");
        }

        sb.append(baseExplanation);
        return sb.toString();
    }

    // ─── 유틸리티 메서드 ─────────────────────────────────────────────────

    static String formatBytes(long bytes) {
        if (bytes <= 0) return "0B";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("약 %.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("약 %.1fMB", bytes / (1024.0 * 1024));
        return String.format("약 %.1fGB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String severityFrom(double percentage) {
        if (percentage >= 50) return "critical";
        if (percentage >= 25) return "high";
        if (percentage >= 10) return "medium";
        if (percentage > 0) return "low";
        return "medium"; // 비율을 추출하지 못한 경우 기본값
    }

    private static String simpleName(String fqcn) {
        if (fqcn == null) return null;
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    private static String formatInstanceCount(int count) {
        if (count <= 0) return "";
        if (count == 1) return "1개 인스턴스";
        return String.format("%,d개 인스턴스", count);
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private static double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    // ─── 내부 데이터 클래스 ──────────────────────────────────────────────

    private static class SuspectContext {
        int instanceCount;
        String className;
        String simpleClassName;
        String classLoader;
        long bytes;
        double percentage;
        String accumulatorClass;
        String accumulatorSimple;
        long accumulatorBytes;
        double accumulatorPercentage;
        String referencedFromClass;
        String severity;
    }

    @FunctionalInterface
    private interface ContextFunction {
        String apply(SuspectContext ctx);
    }

    private static class KnownLibrary {
        final String prefix;
        final String libraryName;
        final String category;
        final ContextFunction explanationFn;
        final ContextFunction adviceFn;

        KnownLibrary(String prefix, String libraryName, String category,
                     ContextFunction explanationFn, ContextFunction adviceFn) {
            this.prefix = prefix;
            this.libraryName = libraryName;
            this.category = category;
            this.explanationFn = explanationFn;
            this.adviceFn = adviceFn;
        }
    }

    private static class LeakRule {
        final String category;
        final Pattern pattern;
        final String explanation;
        final String advice;

        LeakRule(String category, Pattern pattern, String explanation, String advice) {
            this.category = category;
            this.pattern = pattern;
            this.explanation = explanation;
            this.advice = advice;
        }
    }
}
