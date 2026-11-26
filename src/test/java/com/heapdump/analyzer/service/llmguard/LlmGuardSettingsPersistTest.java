package com.heapdump.analyzer.service.llmguard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 토글 3종의 settings.json 왕복과 훅 정합성 (함정 40 — 동기화 지점이 빠지면 예외 없이 값만 사라진다).
 */
class LlmGuardSettingsPersistTest {

    @Test
    @DisplayName("collectSettings → applyFromSettings 왕복 후 값이 같다(모든 조합)")
    void roundTrip() {
        for (int mask = 0; mask < 8; mask++) {
            boolean a = (mask & 1) != 0, b = (mask & 2) != 0, c = (mask & 4) != 0;
            LlmGuardService src = new LlmGuardService(null, null, null);
            src.setSettings(a, b, c);
            Map<String, Object> saved = new LinkedHashMap<>();
            src.collectSettings(saved);

            LlmGuardService dst = new LlmGuardService(null, null, null);
            dst.setSettings(!a, !b, !c);
            dst.applyFromSettings(saved);
            assertEquals(a, dst.isInputCheckEnabled());
            assertEquals(b, dst.isOutputMaskEnabled());
            assertEquals(c, dst.isOutputInstructionEnabled());
        }
    }

    @Test
    @DisplayName("settings.json 훅과 application.properties 훅의 항목 수가 같고, 모든 volatile 필드가 저장된다")
    void hooksAreConsistent() {
        LlmGuardService s = new LlmGuardService(null, null, null);
        Map<String, Object> settings = new LinkedHashMap<>();
        Map<String, String> props = new LinkedHashMap<>();
        s.collectSettings(settings);
        s.collectApplicationProperties(props);
        assertEquals(settings.size(), props.size(), "두 훅의 항목 수가 다르다");

        int volatiles = 0;
        for (Field f : LlmGuardService.class.getDeclaredFields()) {
            if (Modifier.isVolatile(f.getModifiers())) {
                volatiles++;
                String key = "llmGuard" + Character.toUpperCase(f.getName().charAt(0)) + f.getName().substring(1);
                assertTrue(settings.containsKey(key), "settings.json 에 빠진 필드: " + f.getName());
            }
        }
        assertEquals(volatiles, settings.size());
        assertTrue(props.keySet().stream().allMatch(k -> k.startsWith("llm.guard.")));
    }

    @Test
    @DisplayName("저장값이 없으면 기존 값 유지(키 누락이 OFF 로 해석되지 않는다)")
    void missingKeysKeepCurrent() {
        LlmGuardService s = new LlmGuardService(null, null, null);
        s.setSettings(true, true, true);
        s.applyFromSettings(new LinkedHashMap<>());
        assertTrue(s.isInputCheckEnabled() && s.isOutputMaskEnabled() && s.isOutputInstructionEnabled());
    }
}
