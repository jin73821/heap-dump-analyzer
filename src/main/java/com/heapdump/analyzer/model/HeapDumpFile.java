package com.heapdump.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 힙 덤프 파일 정보 모델
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeapDumpFile {

    private String name;
    private String path;
    private long   size;
    private long   lastModified;

    /** 파일 크기 포맷 */
    public String getFormattedSize() {
        if (size < 1024)                return size + " B";
        if (size < 1024 * 1024)         return String.format("%.2f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format("%.2f MB", size / (1024.0 * 1024));
        return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
    }

    /** 수정 날짜 포맷 (MM-dd HH:mm) — Thymeleaf에서 new java.util.Date() 호출 회피용 */
    public String getFormattedDate() {
        return new SimpleDateFormat("MM-dd HH:mm").format(new Date(lastModified));
    }

    /** 파일 확장자 대문자 — Thymeleaf substringAfterLast 미지원 대응 */
    public String getExtension() {
        if (name == null) return "DUMP";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) return "DUMP";
        return name.substring(dot + 1).toUpperCase();
    }
}
