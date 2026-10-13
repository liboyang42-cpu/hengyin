package com.chengyinhub.business.service;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.GrowthMission;
import com.chengyinhub.business.domain.GrowthMissionProgress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F0395:成长任务/经验记录必须带完成来源,客户端自助完成不能伪装成已核验。 */
class GrowthMissionEvidenceSourceTest {

    @Test
    void storeVerifyMissionIsVerifiedOthersAreSelfReported() {
        GrowthMission store = new GrowthMission();
        store.setTriggerEvent("store_verify");
        GrowthMission click = new GrowthMission();
        click.setTriggerEvent("daily_checkin");
        GrowthMission blank = new GrowthMission();
        assertEquals("verified_store", store.getEvidenceSource());
        assertEquals("self_reported", click.getEvidenceSource());
        assertEquals("self_reported", blank.getEvidenceSource());
        assertTrue(JSON.toJSONString(click).contains("\"evidenceSource\":\"self_reported\""), "来源要随接口下发");
    }

    @Test
    void progressIsVerifiedOnlyWhenBoundToRegistrationFact() {
        GrowthMissionProgress verified = new GrowthMissionProgress();
        verified.setBizType("registration");
        verified.setBizId(88L);
        GrowthMissionProgress selfReported = new GrowthMissionProgress();
        selfReported.setBizType("");
        selfReported.setBizId(0L);
        GrowthMissionProgress forged = new GrowthMissionProgress();
        forged.setBizType("registration");
        forged.setBizId(0L);
        assertEquals("verified_store", verified.getEvidenceSource());
        assertEquals("self_reported", selfReported.getEvidenceSource());
        assertEquals("self_reported", forged.getEvidenceSource());
    }
}
