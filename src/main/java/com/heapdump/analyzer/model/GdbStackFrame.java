package com.heapdump.analyzer.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
public class GdbStackFrame {
    private int frameNumber;
    private String address;
    private String function;
    private String args;
    private String location;    // "file.c:42" 형태
    private String library;     // from 절 공유 라이브러리 경로
    private List<String> locals; // bt full 지역변수
    private String quality;     // "RESOLVED" | "UNSYMBOLIZED" | "GARBAGE" — 프레임 신뢰도 분류
}
