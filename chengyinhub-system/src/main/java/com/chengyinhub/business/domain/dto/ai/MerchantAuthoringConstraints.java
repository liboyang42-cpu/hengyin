package com.chengyinhub.business.domain.dto.ai;

import com.chengyinhub.common.exception.ServiceException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Human-confirmed store constraints. They are not pricing, inventory or reward permissions. */
public class MerchantAuthoringConstraints {
    private Integer durationMinutes;
    private Integer participantLimit;
    private String openingHours;
    private Boolean staffRequired;
    private Boolean filmingAllowed;
    private String noisePolicy;
    private String accessibleAlternative;
    private String offlineAlternative;
    private String failureExit;

    public Map<String, Object> validatedSnapshot() {
        if (durationMinutes == null || durationMinutes <= 0 || participantLimit == null || participantLimit <= 0
                || staffRequired == null || filmingAllowed == null) throw invalid();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("durationMinutes", durationMinutes); value.put("participantLimit", participantLimit);
        value.put("openingHours", required(openingHours)); value.put("staffRequired", staffRequired);
        value.put("filmingAllowed", filmingAllowed); value.put("noisePolicy", required(noisePolicy));
        value.put("accessibleAlternative", required(accessibleAlternative)); value.put("offlineAlternative", required(offlineAlternative));
        value.put("failureExit", required(failureExit)); value.put("rewardAuthority", "UNCONFIGURED");
        return Collections.unmodifiableMap(value);
    }
    private static String required(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 1000) throw invalid();
        return value.trim();
    }
    private static ServiceException invalid() { return new ServiceException("请完整确认店内游戏的时长、人数、营业与替代规则", 400); }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer value) { durationMinutes = value; }
    public Integer getParticipantLimit() { return participantLimit; }
    public void setParticipantLimit(Integer value) { participantLimit = value; }
    public String getOpeningHours() { return openingHours; }
    public void setOpeningHours(String value) { openingHours = value; }
    public Boolean getStaffRequired() { return staffRequired; }
    public void setStaffRequired(Boolean value) { staffRequired = value; }
    public Boolean getFilmingAllowed() { return filmingAllowed; }
    public void setFilmingAllowed(Boolean value) { filmingAllowed = value; }
    public String getNoisePolicy() { return noisePolicy; }
    public void setNoisePolicy(String value) { noisePolicy = value; }
    public String getAccessibleAlternative() { return accessibleAlternative; }
    public void setAccessibleAlternative(String value) { accessibleAlternative = value; }
    public String getOfflineAlternative() { return offlineAlternative; }
    public void setOfflineAlternative(String value) { offlineAlternative = value; }
    public String getFailureExit() { return failureExit; }
    public void setFailureExit(String value) { failureExit = value; }
}
