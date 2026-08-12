package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    /** 메모 덮어쓰기 직전 스냅샷 기록 — 의존은 단방향(MemoHistoryService 는 UserService 를 모른다). */
    private final MemoHistoryService memoHistory;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       MemoHistoryService memoHistory) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.memoHistory = memoHistory;
    }

    @PostConstruct
    public void initDefaultAdmin() {
        if (!userRepository.existsByUsername("admin")) {
            String defaultPassword = System.getenv("HEAP_ADMIN_DEFAULT_PASSWORD");
            if (defaultPassword == null || defaultPassword.isEmpty()) {
                defaultPassword = UUID.randomUUID().toString();
                logger.warn("[UserService] HEAP_ADMIN_DEFAULT_PASSWORD 환경변수가 설정되지 않았습니다.");
                logger.warn("[UserService] 자동 생성된 기본 관리자 비밀번호: {}", defaultPassword);
                logger.warn("[UserService] 이 비밀번호를 기록한 후, 로그인하여 즉시 변경하세요.");
            }
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode(defaultPassword));
            admin.setDisplayName("관리자");
            admin.setRole(User.Role.ADMIN);
            admin.setEnabled(true);
            userRepository.save(admin);
            logger.info("[UserService] 기본 관리자 계정 생성: admin");
        }
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("비밀번호는 최소 8자 이상이어야 합니다.");
        }
        if (!password.matches(".*[A-Za-z].*")) {
            throw new IllegalArgumentException("비밀번호는 영문자를 포함해야 합니다.");
        }
        if (!password.matches(".*[0-9].*")) {
            throw new IllegalArgumentException("비밀번호는 숫자를 포함해야 합니다.");
        }
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")) {
            throw new IllegalArgumentException("비밀번호는 특수문자를 포함해야 합니다.");
        }
    }

    public static void validateUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("사용자명을 입력하세요.");
        }
        if (username.length() < 3 || username.length() > 50) {
            throw new IllegalArgumentException("사용자명은 3~50자 사이여야 합니다.");
        }
        if (!username.matches("^[A-Za-z0-9_.-]+$")) {
            throw new IllegalArgumentException("사용자명은 영문/숫자/_/-/. 만 사용할 수 있습니다.");
        }
    }

    public User createUser(String username, String password, String displayName, User.Role role) {
        validateUsername(username);
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("이미 존재하는 사용자명입니다: " + username);
        }
        validatePassword(password);
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    /** 이미 BCrypt 인코딩된 비밀번호로 사용자 생성. 계정 신청 승인 흐름에서 사용. */
    public User createUserWithEncodedPassword(String username, String encodedPassword,
                                              String displayName, User.Role role) {
        validateUsername(username);
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("이미 존재하는 사용자명입니다: " + username);
        }
        if (encodedPassword == null || encodedPassword.isEmpty()) {
            throw new IllegalArgumentException("비밀번호가 비어 있습니다.");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(encodedPassword);
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    public User updateUser(Long id, String displayName, User.Role role, Boolean enabled) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + id));
        if (displayName != null) user.setDisplayName(displayName);
        if (role != null) user.setRole(role);
        // ADMIN 역할 계정은 비활성화 불가
        User.Role effectiveRole = (role != null) ? role : user.getRole();
        if (enabled != null) {
            if (!enabled && effectiveRole == User.Role.ADMIN) {
                throw new IllegalArgumentException("관리자 계정은 비활성화할 수 없습니다.");
            }
            user.setEnabled(enabled);
        }
        return userRepository.save(user);
    }

    public void resetPassword(Long id, String newPassword) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + id));
        validatePassword(newPassword);
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(LocalDateTime.now());   // 관리자 초기화 → 만료 카운트 리셋
        userRepository.save(user);
    }

    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + id));
        if ("admin".equals(user.getUsername())) {
            throw new IllegalArgumentException("기본 관리자 계정은 삭제할 수 없습니다.");
        }
        userRepository.deleteById(id);
    }

    // ── 2차인증 (OTP) 잠금 관리 ────────────────────────────────────

    /** OTP 반복 실패로 잠긴 계정 해제 (실패 카운트 리셋 포함) */
    public User unlockUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + id));
        user.setAccountLocked(false);
        user.setLockedAt(null);
        user.setOtpFailCount(0);
        return userRepository.save(user);
    }

    /** OTP seed 초기화 — 다음 로그인 시 Seed 등록 페이지로 재진입 */
    public User resetOtp(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + id));
        user.setOtpSecret(null);
        user.setOtpEnrolledAt(null);
        user.setOtpLastUsedStep(null);
        user.setOtpFailCount(0);
        return userRepository.save(user);
    }

    // ── 자기서비스 (My Account 페이지) ─────────────────────────────

    /** 본인 비밀번호 변경: 현재 PW 검증 + 복잡도 검증 + 동일 PW 차단. */
    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        if (currentPassword == null || currentPassword.isEmpty()) {
            throw new IllegalArgumentException("현재 비밀번호를 입력하세요.");
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("현재 비밀번호가 일치하지 않습니다.");
        }
        validatePassword(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new IllegalArgumentException("새 비밀번호는 현재 비밀번호와 달라야 합니다.");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(LocalDateTime.now());   // 만료 카운트 리셋 (강제 변경 포함)
        userRepository.save(user);
        logger.info("[UserService] 비밀번호 변경 (self): {}", username);
    }

    /**
     * 본인 OTP 초기화 (자기서비스): 현재 비밀번호 검증 후 OTP 등록 정보 삭제.
     * 다음 로그인 시 Seed 등록 화면으로 재진입. 세션 탈취 시 무단 재등록을 막기 위해 현재 PW 확인.
     */
    public void resetOwnOtp(String username, String currentPassword) {
        if (currentPassword == null || currentPassword.isEmpty()) {
            throw new IllegalArgumentException("현재 비밀번호를 입력하세요.");
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("현재 비밀번호가 일치하지 않습니다.");
        }
        if (user.getOtpSecret() == null || user.getOtpSecret().isEmpty()) {
            throw new IllegalArgumentException("등록된 OTP가 없습니다.");
        }
        user.setOtpSecret(null);
        user.setOtpEnrolledAt(null);
        user.setOtpLastUsedStep(null);
        user.setOtpFailCount(0);
        userRepository.save(user);
        logger.info("[UserService] OTP 초기화 (self): {}", username);
    }

    private static final int MEMO_MAX_BYTES = 10 * 1024 * 1024; // 10 MB (UTF-8 byte 기준)

    public Optional<User> getOwnMemo(String username) {
        return userRepository.findByUsername(username);
    }

    public LocalDateTime saveMemo(String username, String text) {
        return saveMemoInternal(username, text, MemoHistoryService.REASON_SAVE);
    }

    /**
     * 메모 저장 공통 경로. <b>덮어쓰기 직전 내용을 이력에 남긴 뒤</b> 새 값을 기록한다.
     * 이력 기록이 실패해도 저장 자체는 진행한다 — 이력은 안전망이지 저장의 전제가 아니다.
     */
    private LocalDateTime saveMemoInternal(String username, String text, String reason) {
        String memo = text == null ? "" : text;
        if (memo.getBytes(StandardCharsets.UTF_8).length > MEMO_MAX_BYTES) {
            throw new IllegalArgumentException("메모는 최대 10MB까지 저장할 수 있습니다.");
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        String previous = user.getMemo();
        if (!memo.equals(previous == null ? "" : previous)) {
            recordHistorySafely(username, previous, reason);
        }

        user.setMemo(memo.isEmpty() ? null : memo);
        LocalDateTime now = memo.isEmpty() ? null : LocalDateTime.now();
        user.setMemoUpdatedAt(now);
        userRepository.save(user);
        return now;
    }

    public void clearMemo(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        recordHistorySafely(username, user.getMemo(), MemoHistoryService.REASON_CLEAR);
        user.setMemo(null);
        user.setMemoUpdatedAt(null);
        userRepository.save(user);
    }

    /**
     * 이력 스냅샷을 특정 시점 내용으로 되돌린다.
     * 되돌리기 직전의 현재 내용도 이력에 남으므로 <b>복원 자체를 다시 취소</b>할 수 있다.
     *
     * @return 복원된 본문과 저장 시각
     */
    public RestoredMemo restoreMemo(String username, Long historyId) {
        String text = memoHistory.require(username, historyId).getMemo();
        String value = text == null ? "" : text;
        LocalDateTime updatedAt = saveMemoInternal(username, value, MemoHistoryService.REASON_RESTORE);
        logger.info("[UserService] 메모 이력 복원 — user={}, historyId={}, bytes={}",
                username, historyId, value.getBytes(StandardCharsets.UTF_8).length);
        return new RestoredMemo(value, updatedAt);
    }

    /** 복원 결과 — 컨트롤러가 본문을 그대로 화면에 반영할 수 있도록 함께 돌려준다. */
    public record RestoredMemo(String memo, LocalDateTime memoUpdatedAt) { }

    private void recordHistorySafely(String username, String previous, String reason) {
        try {
            memoHistory.record(username, previous, reason);
        } catch (Exception e) {
            // 이력 실패로 사용자의 저장을 막지 않는다 (안전망 < 본 기능)
            logger.error("[UserService] 메모 이력 기록 실패 — user={}, reason={}, err={}: {}",
                    username, reason, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** 메모장 폰트 설정 — 계정별 영속화(브라우저 localStorage 가 아닌 DB). 화이트리스트 검증. */
    private static final java.util.Set<String> ALLOWED_MEMO_FONTS =
            java.util.Set.of("d2coding", "jb+nanum", "nanum", "system");

    public void saveMemoFont(String username, String font) {
        String f = (font == null || font.isBlank()) ? "d2coding" : font.trim();
        if (!ALLOWED_MEMO_FONTS.contains(f)) {
            throw new IllegalArgumentException("지원하지 않는 폰트입니다: " + f);
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.setMemoFont(f);
        userRepository.save(user);
    }

    /**
     * 메모장 자동 저장 토글 — 계정별 영속화. 본문/새창 어느 쪽에서 바꿔도 같은 값을 공유한다.
     * null(미설정) 은 기본 ON 으로 해석하므로 저장은 항상 명시적 true/false 로 기록.
     */
    public void saveMemoAutosave(String username, boolean autosave) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.setMemoAutosave(autosave);
        userRepository.save(user);
    }

    /** memo_autosave 의 null(미설정) → 기본 ON 해석을 한 곳에서만 수행. */
    public static boolean isMemoAutosaveOn(User user) {
        return user == null || user.getMemoAutosave() == null || user.getMemoAutosave();
    }

    /**
     * My Account 레이아웃 — 계정별 영속화(브라우저 localStorage 가 아닌 DB, memoFont 와 동일 정책).
     * 화이트리스트 밖의 값은 저장하지 않는다 — 그대로 두면 CSS 클래스로 흘러간다.
     */
    private static final java.util.Set<String> ALLOWED_ACCOUNT_LAYOUTS = java.util.Set.of("stack", "split");

    /**
     * null/미지원 값 → 기본 'stack' 해석을 한 곳에서만 수행.
     * ⚠ {@code Set.of(...)} 는 {@code contains(null)} 에 NPE 를 던진다 — 컬럼이 아직 null 인
     * 기존 계정 전원이 /account 진입에서 500 을 맞는다. null 검사를 먼저 할 것.
     */
    public static String accountLayoutOf(User user) {
        String v = (user == null) ? null : user.getAccountLayout();
        return (v != null && ALLOWED_ACCOUNT_LAYOUTS.contains(v)) ? v : "stack";
    }

    public void saveAccountLayout(String username, String layout) {
        String v = (layout == null) ? "" : layout.trim();   // null 은 빈 문자열로 — Set.of 는 contains(null) 에 NPE
        if (!ALLOWED_ACCOUNT_LAYOUTS.contains(v)) {
            throw new IllegalArgumentException("지원하지 않는 레이아웃입니다: " + v);
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.setAccountLayout(v);
        userRepository.save(user);
    }
}
