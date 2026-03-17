package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.HeapDumpFile;
import com.heapdump.analyzer.model.MemoryObject;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 힙 덤프 분석 서비스
 */
@Service
public class HeapDumpAnalyzerService {

    private final HeapDumpConfig config;
    private final Random random = new Random();

    public HeapDumpAnalyzerService(HeapDumpConfig config) {
        this.config = config;
    }

    /**
     * 파일 업로드
     */
    public String uploadFile(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isEmpty()) {
            throw new IllegalArgumentException("Invalid filename");
        }

        // 파일명 검증 (경로 탐색 공격 방지)
        filename = new File(filename).getName();

        // 파일 확장자 검증
        if (!isValidHeapDumpFile(filename)) {
            throw new IllegalArgumentException("Invalid file format. Only .hprof, .bin, .dump files are allowed");
        }

        Path targetPath = Paths.get(config.getHeapDumpDirectory(), filename);
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        return filename;
    }

    /**
     * 파일 목록 조회
     */
    public List<HeapDumpFile> listFiles() {
        File directory = new File(config.getHeapDumpDirectory());
        File[] files = directory.listFiles((dir, name) -> isValidHeapDumpFile(name));

        if (files == null) {
            return Collections.emptyList();
        }

        return Arrays.stream(files)
                .map(file -> new HeapDumpFile(
                        file.getName(),
                        file.getAbsolutePath(),
                        file.length(),
                        file.lastModified()
                ))
                .sorted((f1, f2) -> Long.compare(f2.getLastModified(), f1.getLastModified()))
                .collect(Collectors.toList());
    }

    /**
     * 파일 다운로드
     */
    public File getFile(String filename) throws IOException {
        // 파일명 검증
        filename = new File(filename).getName();
        File file = new File(config.getHeapDumpDirectory(), filename);

        if (!file.exists() || !file.isFile()) {
            throw new FileNotFoundException("File not found: " + filename);
        }

        return file;
    }

    /**
     * 파일 삭제
     */
    public void deleteFile(String filename) throws IOException {
        // 파일명 검증
        filename = new File(filename).getName();
        File file = new File(config.getHeapDumpDirectory(), filename);

        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + filename);
        }

        if (!file.delete()) {
            throw new IOException("Failed to delete file: " + filename);
        }
    }

    /**
     * 힙 덤프 파일 분석
     */
    public HeapAnalysisResult analyzeHeapDump(String filename) {
        long startTime = System.currentTimeMillis();

        try {
            // 파일명 검증
            filename = new File(filename).getName();
            File file = new File(config.getHeapDumpDirectory(), filename);

            if (!file.exists() || !file.isFile()) {
                return createErrorResult(filename, "File not found: " + filename);
            }

            // 파일 정보 수집
            long fileSize = file.length();
            long lastModified = file.lastModified();
            String format = getFileExtension(filename).toUpperCase();

            // 실제 힙 덤프 분석 수행 (시뮬레이션)
            HeapAnalysisResult result = performAnalysis(filename, fileSize, lastModified, format);
            
            result.setAnalysisTime(System.currentTimeMillis() - startTime);
            result.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.SUCCESS);

            return result;

        } catch (Exception e) {
            HeapAnalysisResult result = createErrorResult(filename, e.getMessage());
            result.setAnalysisTime(System.currentTimeMillis() - startTime);
            return result;
        }
    }

    /**
     * 실제 힙 덤프 분석 수행 (시뮬레이션)
     * 실제 구현에서는 Eclipse MAT API 또는 JDK jhat를 사용할 수 있습니다.
     */
    private HeapAnalysisResult performAnalysis(String filename, long fileSize, long lastModified, String format) {
        HeapAnalysisResult result = new HeapAnalysisResult();
        
        result.setFilename(filename);
        result.setFileSize(fileSize);
        result.setLastModified(lastModified);
        result.setFormat(format);

        // 파일 크기 기반으로 힙 크기 추정 (실제로는 파일 파싱 필요)
        long estimatedHeapSize = (long) (fileSize * 0.8);
        long usedHeapSize = (long) (estimatedHeapSize * (0.6 + random.nextDouble() * 0.3)); // 60-90% 사용
        long freeHeapSize = estimatedHeapSize - usedHeapSize;
        double heapUsagePercent = (usedHeapSize * 100.0) / estimatedHeapSize;

        result.setTotalHeapSize(estimatedHeapSize);
        result.setUsedHeapSize(usedHeapSize);
        result.setFreeHeapSize(freeHeapSize);
        result.setHeapUsagePercent(heapUsagePercent);

        // 샘플 메모리 객체 생성
        List<MemoryObject> memoryObjects = generateSampleMemoryObjects(estimatedHeapSize);
        result.setTopMemoryObjects(memoryObjects);

        // 통계 정보
        result.setTotalClasses(memoryObjects.size());
        result.setTotalObjects(memoryObjects.stream().mapToLong(MemoryObject::getObjectCount).sum());

        return result;
    }

    /**
     * 샘플 메모리 객체 생성 (실제 환경에서는 파일 파싱 결과 사용)
     */
    private List<MemoryObject> generateSampleMemoryObjects(long totalHeapSize) {
        List<MemoryObject> objects = new ArrayList<>();
        
        // 대표적인 Java 클래스들
        String[] classNames = {
            "char[]",
            "java.lang.String",
            "byte[]",
            "java.util.HashMap$Node",
            "java.lang.Object[]",
            "int[]",
            "java.util.ArrayList",
            "java.util.HashMap",
            "java.lang.Long",
            "java.util.concurrent.ConcurrentHashMap$Node",
            "java.util.LinkedHashMap$Entry",
            "java.lang.Integer",
            "java.util.TreeMap$Entry",
            "java.util.HashSet",
            "java.lang.reflect.Method",
            "java.lang.Class",
            "java.util.WeakHashMap$Entry",
            "java.lang.ref.SoftReference",
            "java.util.LinkedList$Node",
            "java.lang.ThreadLocal$ThreadLocalMap$Entry"
        };

        long remainingSize = (long) (totalHeapSize * 0.8); // 상위 20개가 80% 차지
        
        for (int i = 0; i < 20 && i < classNames.length; i++) {
            double sizeRatio = (20 - i) / 210.0; // 1/21 + 2/21 + ... + 20/21 = 1
            long objectSize = (long) (remainingSize * sizeRatio);
            long objectCount = 1000 + random.nextInt(100000);
            double percentOfHeap = (objectSize * 100.0) / totalHeapSize;

            objects.add(new MemoryObject(
                classNames[i],
                objectCount,
                objectSize,
                percentOfHeap
            ));
        }

        return objects;
    }

    /**
     * 에러 결과 생성
     */
    private HeapAnalysisResult createErrorResult(String filename, String errorMessage) {
        HeapAnalysisResult result = new HeapAnalysisResult();
        result.setFilename(filename);
        result.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.ERROR);
        result.setErrorMessage(errorMessage);
        return result;
    }

    /**
     * 유효한 힙 덤프 파일 확인
     */
    private boolean isValidHeapDumpFile(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return lower.endsWith(".hprof") || lower.endsWith(".bin") || lower.endsWith(".dump");
    }

    /**
     * 파일 확장자 추출
     */
    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1);
        }
        return "";
    }
}
