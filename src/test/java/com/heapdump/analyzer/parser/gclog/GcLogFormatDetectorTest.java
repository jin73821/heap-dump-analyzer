package com.heapdump.analyzer.parser.gclog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GcLogFormatDetectorTest {

    @Test
    void unifiedIsRecognizedByDecorationGroupsAndGcTag() {
        assertEquals(GcLogFormat.UNIFIED, GcLogFormatDetector.sniff(List.of(
                "[2026-09-14T00:52:33.163+0900][0.003s][info][gc,init] CardTable entry size: 512",
                "[2026-09-14T00:52:33.163+0900][0.003s][info][gc     ] Using G1")));
        assertEquals(GcLogFormat.UNIFIED, GcLogFormatDetector.sniff(List.of("[0.325s][info][gc] GC(0) Pause Young (Allocation Failure) 32M->4M(123M) 3.100ms")));
    }

    @Test
    void jdk8IsRecognizedWithAndWithoutDateStampsAndByHeader() {
        assertEquals(GcLogFormat.JDK8, GcLogFormatDetector.sniff(List.of(
                "2026-09-14T00:52:33.485+0900: 0.325: [GC (Allocation Failure) [PSYoungGen: 33280K->5100K(38400K)] 33280K->5108K(125952K), 0.0067 secs]")));
        assertEquals(GcLogFormat.JDK8, GcLogFormatDetector.sniff(List.of("0.325: [GC (Allocation Failure) [PSYoungGen: 1K->1K(1K)] 1K->1K(1K), 0.0067 secs]")));
        assertEquals(GcLogFormat.JDK8, GcLogFormatDetector.sniff(List.of("6.840: [Full GC (Ergonomics) [PSYoungGen: 1K->0K(1K)] 1K->1K(1K), 0.1 secs]")));
        assertEquals(GcLogFormat.JDK8, GcLogFormatDetector.sniff(List.of(
                "Java HotSpot(TM) 64-Bit Server VM (25.202-b08) for linux-amd64 JRE (1.8.0_202-b08), built on Dec 15 2018",
                "Memory: 4k page, physical 16000000k(8000000k free), swap 0k(0k free)")));
    }

    @Test
    void applicationLogsAndEmptyInputAreUnknown() {
        assertEquals(GcLogFormat.UNKNOWN, GcLogFormatDetector.sniff(List.of()));
        assertEquals(GcLogFormat.UNKNOWN, GcLogFormatDetector.sniff(List.of(
                "2026-09-14 00:52:33.163  INFO 1234 --- [           main] c.h.a.HeapAnalyzerApplication : Starting",
                "[main] INFO org.example - hello",
                "")));
        assertEquals(GcLogFormat.UNKNOWN, GcLogFormatDetector.sniff(List.of("[2026-09-14T00:52:33.163+0900][0.003s][info][os] not a gc tag")));
    }
}
