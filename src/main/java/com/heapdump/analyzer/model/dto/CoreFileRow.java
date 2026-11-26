package com.heapdump.analyzer.model.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 코어 파일 화면(/core-dump) 표의 한 행 — 서버 dumpfiles/ 의 파일과 분석 이력(DB)을 파일명으로 합친 것.
 * 조립은 {@code CoreFileListBuilder}. 이 화면 전용이라 다른 곳에서 쓰지 않는다.
 *
 * <p>{@code fileType} 은 {@code coredump} | {@code exec}(코어에 연결되지 않은 실행 파일 — '실행 파일 표시' 를 켜야 보인다).
 * {@code fileMissing} 은 이력은 있는데 원본이 디스크에 없는 행(외부에서 지워진 경우)이다.
 */
public class CoreFileRow {

    /** 백트레이스 요약({@code crashSummary}) 한 줄을 쪼갠 것. 주소·위치는 없을 수 있다. */
    public static class Frame {
        private final String num;
        private final String addr;
        private final String fn;
        private final String loc;

        public Frame(String num, String addr, String fn, String loc) {
            this.num = num; this.addr = addr; this.fn = fn; this.loc = loc;
        }
        public String getNum()  { return num; }
        public String getAddr() { return addr; }
        public String getFn()   { return fn; }
        public String getLoc()  { return loc; }
    }

    private String filename;
    private String fileType = "coredump";
    private String status = "NOT_ANALYZED";
    private boolean fileMissing;

    private long sizeBytes = -1;
    private String formattedSize;

    private LocalDateTime uploadedAt;
    private String uploadedLabel;      // 오늘 14:32 / 어제 22:47 / 11-24 11:20 / 2025-11-24
    private String uploadedBy;
    private LocalDateTime analyzedAt;
    private String formattedAnalysisTime;

    private String crashSignal;
    private Integer signalNumber;
    private String signalMeaning;      // 한국어 풀이 — 모르는 시그널이면 null
    private String signalDescription;  // gdb 원문(예: Segmentation fault)

    private String crashFunction;
    private String crashLocation;
    private List<Frame> frames = new ArrayList<>();

    private String repeatKey;
    private int repeatCount;
    private List<String> repeatOthers = new ArrayList<>();

    private String execName;
    private String execFormattedSize;

    private String errorMessage;

    public boolean isExec()        { return "exec".equals(fileType); }
    public boolean isSuccess()     { return "SUCCESS".equals(status); }
    public boolean isAnalyzing()   { return "ANALYZING".equals(status); }
    public boolean isError()       { return "ERROR".equals(status); }
    public boolean isNotAnalyzed() { return !isExec() && !isSuccess() && !isAnalyzing() && !isError(); }
    public boolean isRepeated()    { return repeatCount > 1; }

    /** 시그널 글자색 — fatal(메모리·명령 오류) / warn(스스로 중단·산술) / neutral. */
    public String getSignalTone() {
        if (crashSignal == null) return "neutral";
        switch (crashSignal) {
            case "SIGSEGV": case "SIGBUS": case "SIGILL": return "fatal";
            case "SIGABRT": case "SIGFPE": return "warn";
            default: return "neutral";
        }
    }

    /** 표에서 파일명을 가운데 말줄임할 때 끝에 남기는 글자 수 — 이보다 {@value #NAME_SPLIT_MIN}자 이상 긴 이름만 나눈다. */
    static final int NAME_TAIL = 12;
    static final int NAME_SPLIT_MIN = 24;

    /**
     * 파일명 앞부분 — 폭이 모자라면 이 부분만 말줄임된다. 코어 이름은 끝의 pid·시각이 서로를 가르는데
     * ({@code core.tmaxsvr.20261125-031455.18822}) 끝을 자르면 같은 프로그램의 코어가 전부 같은 글자로 보인다.
     */
    public String getNameHead() {
        if (filename == null) return null;
        return filename.substring(0, nameCut());
    }

    /** 파일명 끝부분(항상 보인다). 짧은 이름이면 null — 나누지 않는다. */
    public String getNameTail() {
        if (filename == null) return null;
        int cut = nameCut();
        return cut < filename.length() ? filename.substring(cut) : null;
    }

    private int nameCut() {
        int len = filename.length();
        if (len <= NAME_SPLIT_MIN) return len;
        int cut = len - NAME_TAIL;
        if (Character.isLowSurrogate(filename.charAt(cut))) cut--;   // 보조 평면 문자를 반으로 가르지 않는다
        return cut;
    }

    /** 표 칸에 쓰는 소스 위치 — 디렉터리를 뗀 {@code 파일명:줄}. 전체 경로는 툴팁과 펼친 백트레이스에 있다. */
    public String getCrashLocationShort() { return shortLocation(crashLocation); }

    /** {@code /build/src/svc/registry.cpp:188} → {@code registry.cpp:188}. 빌드 서버 경로가 길어 정작 파일명:줄이 잘렸다. */
    public static String shortLocation(String location) {
        if (location == null) return null;
        int slash = Math.max(location.lastIndexOf('/'), location.lastIndexOf('\\'));
        return (slash >= 0 && slash < location.length() - 1) ? location.substring(slash + 1) : location;
    }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isFileMissing() { return fileMissing; }
    public void setFileMissing(boolean fileMissing) { this.fileMissing = fileMissing; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getFormattedSize() { return formattedSize; }
    public void setFormattedSize(String formattedSize) { this.formattedSize = formattedSize; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
    public String getUploadedLabel() { return uploadedLabel; }
    public void setUploadedLabel(String uploadedLabel) { this.uploadedLabel = uploadedLabel; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
    public LocalDateTime getAnalyzedAt() { return analyzedAt; }
    public void setAnalyzedAt(LocalDateTime analyzedAt) { this.analyzedAt = analyzedAt; }
    public String getFormattedAnalysisTime() { return formattedAnalysisTime; }
    public void setFormattedAnalysisTime(String formattedAnalysisTime) { this.formattedAnalysisTime = formattedAnalysisTime; }
    public String getCrashSignal() { return crashSignal; }
    public void setCrashSignal(String crashSignal) { this.crashSignal = crashSignal; }
    public Integer getSignalNumber() { return signalNumber; }
    public void setSignalNumber(Integer signalNumber) { this.signalNumber = signalNumber; }
    public String getSignalMeaning() { return signalMeaning; }
    public void setSignalMeaning(String signalMeaning) { this.signalMeaning = signalMeaning; }
    public String getSignalDescription() { return signalDescription; }
    public void setSignalDescription(String signalDescription) { this.signalDescription = signalDescription; }
    public String getCrashFunction() { return crashFunction; }
    public void setCrashFunction(String crashFunction) { this.crashFunction = crashFunction; }
    public String getCrashLocation() { return crashLocation; }
    public void setCrashLocation(String crashLocation) { this.crashLocation = crashLocation; }
    public List<Frame> getFrames() { return frames; }
    public void setFrames(List<Frame> frames) { this.frames = frames; }
    public String getRepeatKey() { return repeatKey; }
    public void setRepeatKey(String repeatKey) { this.repeatKey = repeatKey; }
    public int getRepeatCount() { return repeatCount; }
    public void setRepeatCount(int repeatCount) { this.repeatCount = repeatCount; }
    public List<String> getRepeatOthers() { return repeatOthers; }
    public void setRepeatOthers(List<String> repeatOthers) { this.repeatOthers = repeatOthers; }
    public String getExecName() { return execName; }
    public void setExecName(String execName) { this.execName = execName; }
    public String getExecFormattedSize() { return execFormattedSize; }
    public void setExecFormattedSize(String execFormattedSize) { this.execFormattedSize = execFormattedSize; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
