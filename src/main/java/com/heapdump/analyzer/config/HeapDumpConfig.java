package com.heapdump.analyzer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;

/**
 * Heap Dump Analyzer 설정
 *
 * application.properties 또는 환경 변수로 재정의 가능:
 *   heapdump.directory=/opt/heapdumps
 *   mat.cli.path=/opt/mat/ParseHeapDump.sh
 */
@Configuration
public class HeapDumpConfig {

    /** 힙 덤프 파일 저장 디렉토리 */
    @Value("${heapdump.directory:/opt/heapdumps}")
    private String heapDumpDirectory;

    /** MAT CLI 스크립트 경로 */
    @Value("${mat.cli.path:/opt/mat/ParseHeapDump.sh}")
    private String matCliPath;

    @PostConstruct
    public void init() {
        // 힙 덤프 디렉토리 자동 생성
        try {
            Path path = Paths.get(heapDumpDirectory);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                System.out.println("[HeapDumpConfig] Created directory: " + heapDumpDirectory);
            }
        } catch (IOException e) {
            System.err.println("[HeapDumpConfig] Failed to create heap dump directory: " + e.getMessage());
            throw new RuntimeException("Failed to initialize heap dump directory", e);
        }

        // MAT 스크립트 존재 여부 확인 (경고만, 예외 미발생)
        File mat = new File(matCliPath);
        if (!mat.exists()) {
            System.out.println("[HeapDumpConfig] WARNING: MAT CLI not found at " + matCliPath
                    + " — analysis will fail at runtime.");
        } else {
            System.out.println("[HeapDumpConfig] MAT CLI found: " + matCliPath);
            // 실행 권한 부여
            mat.setExecutable(true);
        }
    }

    public String getHeapDumpDirectory() { return heapDumpDirectory; }
    public String getMatCliPath()        { return matCliPath; }
}
