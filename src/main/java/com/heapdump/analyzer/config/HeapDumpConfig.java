package com.heapdump.analyzer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Heap Dump 디렉토리 설정
 */
@Configuration
public class HeapDumpConfig {

    @Value("${heapdump.directory:/opt/heapdumps}")
    private String heapDumpDirectory;

    /**
     * 애플리케이션 시작 시 힙 덤프 디렉토리 생성
     */
    @PostConstruct
    public void init() {
        try {
            Path path = Paths.get(heapDumpDirectory);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                System.out.println("Created heap dump directory: " + heapDumpDirectory);
            } else {
                System.out.println("Heap dump directory exists: " + heapDumpDirectory);
            }
        } catch (IOException e) {
            System.err.println("Failed to create heap dump directory: " + e.getMessage());
            throw new RuntimeException("Failed to initialize heap dump directory", e);
        }
    }

    public String getHeapDumpDirectory() {
        return heapDumpDirectory;
    }
}
