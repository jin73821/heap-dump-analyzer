package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.service.ComparisonHistoryService;
import com.heapdump.analyzer.service.ComparisonHistoryService.BulkDeleteResult;
import com.heapdump.analyzer.service.ComparisonHistoryService.ComparisonHistoryItem;
import com.heapdump.analyzer.service.ComparisonHistoryService.DeleteResult;
import com.heapdump.analyzer.util.AuthUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class ComparisonHistoryController {

    private static final Logger logger = LoggerFactory.getLogger(ComparisonHistoryController.class);

    private final ComparisonHistoryService service;

    public ComparisonHistoryController(ComparisonHistoryService service) {
        this.service = service;
    }

    @GetMapping("/comparison-history")
    public String page(Model model, Authentication authentication) {
        boolean isAdmin = AuthUtil.isAdmin(authentication);
        String username = authentication == null ? "" : authentication.getName();
        List<ComparisonHistoryItem> items = service.list(isAdmin);
        model.addAttribute("comparisonHistory", items);
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("currentUsername", username);
        model.addAttribute("totalCount", items.size());
        long deletedCount = items.stream()
                .filter(it -> it.isBaseDeleted() || it.isTargetDeleted())
                .count();
        model.addAttribute("deletedCount", deletedCount);
        return "comparison-history";
    }

    @GetMapping("/api/comparison-history")
    @ResponseBody
    public List<ComparisonHistoryItem> listJson(Authentication authentication) {
        return service.list(AuthUtil.isAdmin(authentication));
    }

    @DeleteMapping("/api/comparison-history/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteOne(@PathVariable Long id,
                                                         Authentication authentication) {
        String username = authentication == null ? "" : authentication.getName();
        boolean isAdmin = AuthUtil.isAdmin(authentication);
        DeleteResult r = service.deleteOne(id, username, isAdmin);
        Map<String, Object> resp = new HashMap<>();
        switch (r) {
            case DELETED:
                resp.put("success", true);
                return ResponseEntity.ok(resp);
            case NOT_FOUND:
                resp.put("success", false);
                resp.put("error", "비교 이력을 찾을 수 없습니다.");
                return ResponseEntity.status(404).body(resp);
            case FORBIDDEN:
            default:
                resp.put("success", false);
                resp.put("error", "본인이 실행한 비교 이력만 삭제할 수 있습니다.");
                return ResponseEntity.status(403).body(resp);
        }
    }

    @PostMapping("/api/comparison-history/bulk-delete")
    @ResponseBody
    public Map<String, Object> bulkDelete(@RequestBody Map<String, Object> body,
                                          Authentication authentication) {
        Object raw = body == null ? null : body.get("ids");
        List<Long> ids = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof Number) {
                    ids.add(((Number) o).longValue());
                } else if (o != null) {
                    try { ids.add(Long.parseLong(o.toString())); }
                    catch (NumberFormatException ignore) { /* skip */ }
                }
            }
        }
        String username = authentication == null ? "" : authentication.getName();
        boolean isAdmin = AuthUtil.isAdmin(authentication);
        BulkDeleteResult br = service.bulkDelete(ids, username, isAdmin);
        logger.info("[CompareHistory] bulk-delete by={} admin={} requested={} deleted={} skipped={}",
                username, isAdmin, ids.size(), br.deleted, br.skipped);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", br.deleted);
        resp.put("deleted", br.deleted);
        resp.put("skipped", br.skipped);
        resp.put("requested", ids.size());
        return resp;
    }
}
