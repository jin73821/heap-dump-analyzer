package com.heapdump.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Heap Dump Analyzer Application (MAT CLI Edition)
 */
@SpringBootApplication
public class HeapAnalyzerApplication {
    public static void main(String[] args) {
        SpringApplication.run(HeapAnalyzerApplication.class, args);
    }
}
