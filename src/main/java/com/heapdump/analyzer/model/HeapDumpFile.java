package com.heapdump.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 힙 덤프 파일 정보 모델
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeapDumpFile {
    
    /**
     * 파일명
     */
    private String name;
    
    /**
     * 파일 경로
     */
    private String path;
    
    /**
     * 파일 크기 (바이트)
     */
    private long size;
    
    /**
     * 수정 시간 (밀리초)
     */
    private long lastModified;
    
    /**
     * 파일 크기를 읽기 쉬운 형식으로 변환
     * @return 형식화된 파일 크기 (예: 460 MB, 2.5 GB)
     */
    public String getFormattedSize() {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
}
