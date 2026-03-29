package com.heapdump.analyzer.util;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 힙덤프 파일명 검증 유틸리티.
 * Controller 입구에서 호출하여 path traversal 등 악의적 입력을 조기 차단합니다.
 */
public final class FilenameValidator {

    private FilenameValidator() {}

    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".hprof", ".bin", ".dump",
            ".hprof.gz", ".bin.gz", ".dump.gz"
    ));

    /**
     * 파일명을 검증하고 안전한 파일명(경로 구성요소 없음)을 반환합니다.
     *
     * @param filename 사용자 입력 파일명
     * @return 검증된 안전한 파일명
     * @throws IllegalArgumentException 유효하지 않은 파일명인 경우
     */
    public static String validate(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("Filename is required");
        }

        // 경로 구성요소 제거 — 파일명만 추출
        String safe = Paths.get(filename).getFileName().toString();

        // null byte 차단
        if (safe.contains("\0")) {
            throw new IllegalArgumentException("Invalid filename: contains null byte");
        }

        // 경로 탐색 시도 차단
        if (safe.contains("..") || safe.contains("/") || safe.contains("\\")) {
            throw new IllegalArgumentException("Invalid filename: path traversal detected");
        }

        // 빈 파일명 차단
        if (safe.trim().isEmpty() || safe.equals(".")) {
            throw new IllegalArgumentException("Invalid filename");
        }

        // 허용 확장자 확인
        if (!hasAllowedExtension(safe)) {
            throw new IllegalArgumentException("Unsupported file type. Allowed: .hprof, .bin, .dump");
        }

        return safe;
    }

    private static boolean hasAllowedExtension(String name) {
        String lower = name.toLowerCase();
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }
}
