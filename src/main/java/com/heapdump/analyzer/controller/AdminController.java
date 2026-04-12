package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class AdminController {

    private final UserService userService;

    public AdminController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/admin/users")
    public String usersPage(Model model) {
        List<User> users = userService.findAll();
        model.addAttribute("users", users);
        return "admin/users";
    }

    @PostMapping("/api/admin/users")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String username = body.get("username");
            String password = body.get("password");
            String displayName = body.get("displayName");
            String roleStr = body.getOrDefault("role", "USER");
            User.Role role = User.Role.valueOf(roleStr);

            User user = userService.createUser(username, password, displayName, role);
            result.put("success", true);
            result.put("userId", user.getId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PutMapping("/api/admin/users/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable Long id,
                                                          @RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String displayName = body.get("displayName");
            User.Role role = body.containsKey("role") ? User.Role.valueOf(body.get("role")) : null;
            Boolean enabled = body.containsKey("enabled") ? Boolean.valueOf(body.get("enabled")) : null;

            userService.updateUser(id, displayName, role, enabled);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping("/api/admin/users/{id}/reset-password")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable Long id,
                                                             @RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String newPassword = body.get("password");
            userService.resetPassword(id, newPassword);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @DeleteMapping("/api/admin/users/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            userService.deleteUser(id);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}
