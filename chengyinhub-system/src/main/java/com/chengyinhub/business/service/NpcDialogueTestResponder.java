package com.chengyinhub.business.service;

import java.util.List;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.ThemeStateKey;

/**
 * TEST-only stand-in for the NPC dialogue model when the upstream is unavailable. The only implementation lives in
 * the chengyinhub-e2e module (profile e2e-test), which production artifacts do not carry (R9 BE-16).
 */
public interface NpcDialogueTestResponder
{
    String generateJson(NpcProfile profile, String utterance, List<ThemeStateKey> allowedKeys);
}
