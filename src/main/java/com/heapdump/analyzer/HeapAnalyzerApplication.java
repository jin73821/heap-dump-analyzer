package com.heapdump.analyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Heap Dump Analyzer Application (MAT CLI Edition)
 */
@SpringBootApplication
@EnableScheduling
public class HeapAnalyzerApplication {

    private static final Logger logger = LoggerFactory.getLogger(HeapAnalyzerApplication.class);

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("[Shutdown] Application is shutting down (signal received)");
        }, "shutdown-logger"));

        SpringApplication.run(HeapAnalyzerApplication.class, args);
    }
}
