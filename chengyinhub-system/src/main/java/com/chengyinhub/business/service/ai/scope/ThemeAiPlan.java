package com.chengyinhub.business.service.ai.scope;

import java.time.Instant;
import java.util.Objects;

/** Planning value only; there is intentionally no provider invocation or execution adapter. */
public final class ThemeAiPlan
{
    public enum Mode { OFF, OPTIONAL }
    private final Mode mode;
    private final AiScope scope;
    private final String approvedFallbackReference;

    private ThemeAiPlan(Mode mode, AiScope scope, String approvedFallbackReference)
    {
        this.mode = mode;
        this.scope = scope;
        this.approvedFallbackReference = AiContractSupport.text(approvedFallbackReference, "approvedFallbackReference");
    }

    /** AI-off content needs neither an NPC/binding nor any model-specific configuration. */
    public static ThemeAiPlan off(String approvedFallbackReference)
    {
        return new ThemeAiPlan(Mode.OFF, null, approvedFallbackReference);
    }

    /** The server still must validate binding/knowledge availability before any future call. */
    public static ThemeAiPlan optional(AiScope scope, String approvedFallbackReference, Instant now)
    {
        Objects.requireNonNull(scope, "scope").requireActiveAt(now);
        if (!scope.getScenario().isTheme()) throw new IllegalArgumentException("theme scope required");
        return new ThemeAiPlan(Mode.OPTIONAL, scope, approvedFallbackReference);
    }

    public Mode getMode() { return mode; }
    public AiScope getScope() { return scope; }
    public String getApprovedFallbackReference() { return approvedFallbackReference; }
    public boolean isModelCallAllowed() { return mode == Mode.OPTIONAL; }
    public boolean isModelRequiredForProgress() { return false; }
}
