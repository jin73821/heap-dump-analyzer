package com.heapdump.analyzer.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
public class GdbThreadInfo {
    private int id;
    private String targetId;     // "Thread 0x7f1234 (LWP 12345)"
    private String name;         // 스레드명 (예: "myapp")
    private boolean current;     // '*' 마커 여부 (크래시 스레드)
    private String currentFrame; // thread 목록 줄에서 파싱한 현재 프레임 요약
    private List<GdbStackFrame> backtrace;
}
