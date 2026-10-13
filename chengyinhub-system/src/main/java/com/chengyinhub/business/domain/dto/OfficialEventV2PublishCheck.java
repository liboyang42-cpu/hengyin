package com.chengyinhub.business.domain.dto;

import java.util.ArrayList;
import java.util.List;

/** 发布检查结果：阻塞项必须清零，风险项可在 OPTIONAL_PARTNERS 场景展示但不伪装成通过。 */
public class OfficialEventV2PublishCheck {
    private final List<String> blockers = new ArrayList<>();
    private final List<String> risks = new ArrayList<>();

    public List<String> getBlockers() { return blockers; }
    public List<String> getRisks() { return risks; }
    public boolean isPass() { return blockers.isEmpty(); }
    public void block(String message) { blockers.add(message); }
    public void risk(String message) { risks.add(message); }
}
