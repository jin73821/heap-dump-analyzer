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

    // ── 주소→모듈 귀속 (심볼 없는 ?? 프레임을 소유 라이브러리에 매핑) ──
    private String module;         // 소유 objfile basename (예: libclntsh.so.19.1)
    private String moduleOffset;   // 모듈 내 오프셋 hex 문자열 (예: 0x28fb14f)
    private String moduleVendor;   // 벤더 라벨 (예: Oracle Client / Tmax / glibc), 미상이면 null
    private Boolean moduleHasSymbols; // 소유 모듈의 심볼 로드 여부(Syms Read). 미상이면 null
}
