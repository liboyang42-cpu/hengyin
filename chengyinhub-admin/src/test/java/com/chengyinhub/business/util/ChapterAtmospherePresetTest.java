package com.chengyinhub.business.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChapterAtmospherePresetTest
{
    @Test
    void nullAndUnknownReadValuesFallBackToDefault()
    {
        assertEquals("DEFAULT", ChapterAtmospherePreset.normalizeForRead(null));
        assertEquals("DEFAULT", ChapterAtmospherePreset.normalizeForRead("future-color"));
    }

    @Test
    void knownValuesAreCanonicalized()
    {
        // 2026-09-06 换成黑/蓝/红/黄/白五档纯色
        assertEquals("BLUE", ChapterAtmospherePreset.requireValid("blue"));
        assertEquals("WHITE", ChapterAtmospherePreset.requireValid(" WHITE "));
    }

    /**
     * ★ 换取值域时最容易出事的不是新值，是**老数据**：库里存着 NIGHT/ARCHIVE/MOSS/NEON。
     * 若 requireValid 对它们抛异常，一条老章节**一保存就编辑不了**；若静默落回默认，
     * 作者选过的颜色会整片消失且零报错。所以别名必须逐条钉住。
     */
    @Test
    void legacyPresetsAreAliasedNotRejected()
    {
        assertEquals("BLUE", ChapterAtmospherePreset.requireValid("NIGHT"));
        assertEquals("YELLOW", ChapterAtmospherePreset.requireValid("archive"));
        assertEquals("RED", ChapterAtmospherePreset.requireValid("NEON"));
        // 苔野在新色板里没有对应色，落回默认黑是有意的
        assertEquals("DEFAULT", ChapterAtmospherePreset.requireValid("MOSS"));
    }

    @Test
    void writesRejectArbitraryColorsAndUnknownPresets()
    {
        assertThrows(IllegalArgumentException.class,
                () -> ChapterAtmospherePreset.requireValid("#ff00aa"));
        assertThrows(IllegalArgumentException.class,
                () -> ChapterAtmospherePreset.requireValid("SUNSET"));
    }

    @Test
    void playerProjectionHardGatesAtmosphereToLinearOrienting() throws Exception
    {
        String source = new String(Files.readAllBytes(Paths.get("src", "main", "java", "com", "chengyinhub",
                "web", "service", "play", "PlayNodeQueryApplication.java")), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        org.junit.jupiter.api.Assertions.assertTrue(source.contains(
                "read.playMode == PlayMode.LINEAR_ORIENTEERING ? ChapterAtmospherePreset.normalizeForRead"));
    }
}
