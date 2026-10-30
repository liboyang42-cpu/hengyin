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
        ThemeLook.normalize("{\"style\":\"SURVIVAL\",\"params\":{\"climate\":\"BUNKER\",\"volatility\":\"VIOLENT\",\"edge\":\"OFF\"}}");
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
    }
}
