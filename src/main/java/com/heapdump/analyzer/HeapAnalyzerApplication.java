package com.heapdump.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Heap Dump Analyzer Application
 * 
 * Java heap dump 파일을 분석하는 웹 애플리케이션
 */
@SpringBootApplication
public class HeapAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(HeapAnalyzerApplication.class, args);
    }

}
