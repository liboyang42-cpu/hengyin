package com.chengyinhub.business.service.ai.scope;

/** Application scenario, deliberately independent of legacy role/bizType strings. */
public enum AiScenario
{
    PLATFORM_ASSISTANT,
    CREATOR_ASSISTANT,
    MERCHANT_PRIVATE_ASSISTANT,
    STORE_PUBLIC_NPC,
    THEME_NPC,
    THEME_DIRECTOR;

    public boolean isTheme()
    {
        return this == THEME_NPC || this == THEME_DIRECTOR;
    }
}
