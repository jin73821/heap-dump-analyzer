package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
}
