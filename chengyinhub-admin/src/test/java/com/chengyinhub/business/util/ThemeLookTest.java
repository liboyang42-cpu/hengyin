package com.chengyinhub.business.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThemeLookTest
{
    @Test
    void absentKeepsOldValueAndBlankResetsToDefault()
    {
        assertNull(ThemeLook.normalize(null));
        assertEquals("", ThemeLook.normalize("  "));
    }

    @Test
    void canonicalizesCaseAndFillsTransitionAndFont()
    {
        assertEquals("{\"style\":\"CYBER\",\"params\":{\"neon\":\"CYAN_PINK\",\"current\":\"HIGH\"},\"transition\":\"AUTO\",\"storyFont\":\"HAND\"}",
                ThemeLook.normalize("{\"style\":\"cyber\",\"params\":{\"current\":\"high\",\"neon\":\"cyan_pink\"}}"));
        assertEquals("{\"style\":\"PAPER\",\"params\":{},\"transition\":\"WARP\",\"storyFont\":\"KAI\"}",
                ThemeLook.normalize("{\"style\":\"PAPER\",\"transition\":\"warp\",\"storyFont\":\"kai\"}"));
    }

    @Test
    void everyStyleAcceptsItsOwnParams()
    {
        ThemeLook.normalize("{\"style\":\"STARDUST\",\"params\":{\"density\":\"DENSE\",\"drift\":\"FAST\",\"tone\":\"COOL\"}}");
        ThemeLook.normalize("{\"style\":\"PAPER\",\"params\":{\"paper\":\"XUAN\",\"grain\":\"HEAVY\",\"stamp\":\"SEAL\"}}");
        ThemeLook.normalize("{\"style\":\"SURVIVAL\",\"params\":{\"climate\":\"SAND\",\"volatility\":\"VIOLENT\",\"edge\":\"OFF\"}}");
        ThemeLook.normalize("{\"style\":\"CYBER\",\"params\":{\"neon\":\"RED_CYAN\",\"current\":\"LOW\",\"glitch\":\"OFF\"}}");
    }

    @Test
    void rejectsAnythingOutsideTheCatalog()
    {
        assertThrows(IllegalArgumentException.class, () -> ThemeLook.normalize("{\"style\":\"SUN\"}"));
        assertThrows(IllegalArgumentException.class, () -> ThemeLook.normalize("{\"style\":\"CYBER\",\"params\":{\"climate\":\"STORM\"}}"));
        assertThrows(IllegalArgumentException.class, () -> ThemeLook.normalize("{\"style\":\"CYBER\",\"params\":{\"neon\":\"#FF00FF\"}}"));
        assertThrows(IllegalArgumentException.class, () -> ThemeLook.normalize("{\"style\":\"CYBER\",\"transition\":\"EXPLODE\"}"));
        assertThrows(IllegalArgumentException.class, () -> ThemeLook.normalize("{\"style\":\"CYBER\",\"storyFont\":\"COMIC\"}"));
        assertThrows(IllegalArgumentException.class, () -> ThemeLook.normalize("{\"style\":\"CYBER\",\"color\":\"red\"}"));
        assertThrows(IllegalArgumentException.class, () -> ThemeLook.normalize("{\"style\":1}"));
        assertThrows(IllegalArgumentException.class, () -> ThemeLook.normalize("[\"CYBER\"]"));
        assertThrows(IllegalArgumentException.class, () -> ThemeLook.normalize("not json"));
        assertThrows(IllegalArgumentException.class, () -> ThemeLook.normalize("{\"style\":\"CYBER\",\"palette\":\"GREEN\"}"));
    }

    @Test
    void themePaletteIsOptionalAndBoldItalicFontIsAccepted()
    {
        assertEquals("{\"style\":\"PAPER\",\"params\":{},\"transition\":\"AUTO\",\"storyFont\":\"BOLD\",\"palette\":\"YELLOW\"}",
                ThemeLook.normalize("{\"style\":\"paper\",\"storyFont\":\"bold\",\"palette\":\"yellow\"}"));
        // 不传 / 空串 = 各章沿用自己的章节配色,输出里不出现 palette
        assertEquals("{\"style\":\"PAPER\",\"params\":{},\"transition\":\"AUTO\",\"storyFont\":\"HAND\"}",
                ThemeLook.normalize("{\"style\":\"PAPER\",\"palette\":\"\"}"));
    }

    @Test
    void survivalWeatherAndChapterTimes()
    {
        assertEquals("{\"style\":\"SURVIVAL\",\"params\":{\"climate\":\"SAND\"},\"transition\":\"AUTO\",\"storyFont\":\"HAND\",\"times\":[\"DAWN\",\"\",\"DUSK\"]}",
                ThemeLook.normalize("{\"style\":\"SURVIVAL\",\"params\":{\"climate\":\"sand\"},\"times\":[\"dawn\",\"\",\"dusk\",\"\"]}"));
        assertThrows(IllegalArgumentException.class, () -> ThemeLook.normalize("{\"style\":\"SURVIVAL\",\"params\":{\"climate\":\"WINTER\"}}"));
        assertThrows(IllegalArgumentException.class, () -> ThemeLook.normalize("{\"style\":\"SURVIVAL\",\"times\":[\"NOON\",\"MIDNIGHT\"]}"));
        assertThrows(IllegalArgumentException.class, () -> ThemeLook.normalize("{\"style\":\"PAPER\",\"times\":[\"NOON\"]}"));
    }
}
