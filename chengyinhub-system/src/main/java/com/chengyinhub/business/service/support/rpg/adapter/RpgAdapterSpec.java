package com.chengyinhub.business.service.support.rpg.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * C02 作者侧 sidecar:环境失效 / 装备与状态期限 / party / 资源 / 成长 / 团队对抗。
 *
 * <p>不能写进当前远端 {@code JourneyRpg.normalize}(只认 enabled/keys,多余字段会拒)。
 * 运行入口由 dot 从已发布快照接线;本对象只在 adapter 包内解析。{@code legacyFrozen=true}
 * 或缺省空对象 = 老局 / 未配置,全部 adapter 零行为。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RpgAdapterSpec {
    public static final int SCHEMA_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private int schemaVersion = SCHEMA_VERSION;
    /** 冻结旧局:adapter 一律 no-op,检定 breakdown 字段集合与 F2 一致。 */
    private boolean legacyFrozen;
    private EnvironmentExpiry environmentExpiry;
    private List<EquipmentDuration> equipmentDurations = new ArrayList<EquipmentDuration>();
    private List<StatusDuration> statusDurations = new ArrayList<StatusDuration>();
    private Party party;
    private Resources resources;
    private Growth growth;
    private TeamOpposition teamOpposition;

    public static RpgAdapterSpec inactive() {
        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.legacyFrozen = true;
        return spec;
    }

    public static RpgAdapterSpec parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return inactive();
        try {
            RpgAdapterSpec spec = JSON.readValue(raw, RpgAdapterSpec.class);
            return spec == null ? inactive() : spec;
        } catch (Exception bad) {
            throw new IllegalArgumentException("C02 adapter spec 无法解析");
        }
    }

    /** 老局 / 未启用任何 C02 块:不得改 state、不得给 breakdown 加键。 */
    public boolean active() {
        return !legacyFrozen && schemaVersion == SCHEMA_VERSION && (environmentExpiry != null
                || !equipmentDurations.isEmpty() || !statusDurations.isEmpty()
                || party != null || resources != null || growth != null || teamOpposition != null);
    }

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public boolean isLegacyFrozen() { return legacyFrozen; }
    public void setLegacyFrozen(boolean legacyFrozen) { this.legacyFrozen = legacyFrozen; }
    public EnvironmentExpiry getEnvironmentExpiry() { return environmentExpiry; }
    public void setEnvironmentExpiry(EnvironmentExpiry environmentExpiry) { this.environmentExpiry = environmentExpiry; }
    public List<EquipmentDuration> getEquipmentDurations() {
        return equipmentDurations == null ? Collections.<EquipmentDuration>emptyList() : equipmentDurations;
    }
    public void setEquipmentDurations(List<EquipmentDuration> equipmentDurations) {
        this.equipmentDurations = equipmentDurations == null ? new ArrayList<EquipmentDuration>() : equipmentDurations;
    }
    public List<StatusDuration> getStatusDurations() {
        return statusDurations == null ? Collections.<StatusDuration>emptyList() : statusDurations;
    }
    public void setStatusDurations(List<StatusDuration> statusDurations) {
        this.statusDurations = statusDurations == null ? new ArrayList<StatusDuration>() : statusDurations;
    }
    public Party getParty() { return party; }
    public void setParty(Party party) { this.party = party; }
    public Resources getResources() { return resources; }
    public void setResources(Resources resources) { this.resources = resources; }
    public Growth getGrowth() { return growth; }
    public void setGrowth(Growth growth) { this.growth = growth; }
    public TeamOpposition getTeamOpposition() { return teamOpposition; }
    public void setTeamOpposition(TeamOpposition teamOpposition) { this.teamOpposition = teamOpposition; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EnvironmentExpiry {
        private List<String> keys = new ArrayList<String>();
        /** 服务器时钟(UTC epoch millis);0 = 只刷新不失效。 */
        private long expiresAtEpochMillis;
        public List<String> getKeys() { return keys == null ? Collections.<String>emptyList() : keys; }
        public void setKeys(List<String> keys) { this.keys = keys == null ? new ArrayList<String>() : keys; }
        public long getExpiresAtEpochMillis() { return expiresAtEpochMillis; }
        public void setExpiresAtEpochMillis(long expiresAtEpochMillis) { this.expiresAtEpochMillis = expiresAtEpochMillis; }
        public boolean hasDeadline() { return expiresAtEpochMillis > 0L; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EquipmentDuration {
        private String itemId;
        private String slot;
        private long expiresAtEpochMillis;
        public String getItemId() { return itemId; }
        public void setItemId(String itemId) { this.itemId = itemId; }
        public String getSlot() { return slot; }
        public void setSlot(String slot) { this.slot = slot; }
        public long getExpiresAtEpochMillis() { return expiresAtEpochMillis; }
        public void setExpiresAtEpochMillis(long expiresAtEpochMillis) { this.expiresAtEpochMillis = expiresAtEpochMillis; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatusDuration {
        private String condition;
        private long expiresAtEpochMillis;
        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }
        public long getExpiresAtEpochMillis() { return expiresAtEpochMillis; }
        public void setExpiresAtEpochMillis(long expiresAtEpochMillis) { this.expiresAtEpochMillis = expiresAtEpochMillis; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Party {
        private boolean requireRole;
        public boolean isRequireRole() { return requireRole; }
        public void setRequireRole(boolean requireRole) { this.requireRole = requireRole; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Resources {
        private JsonNode successEffects;
        private JsonNode failEffects;
        public JsonNode getSuccessEffects() { return successEffects; }
        public void setSuccessEffects(JsonNode successEffects) { this.successEffects = successEffects; }
        public JsonNode getFailEffects() { return failEffects; }
        public void setFailEffects(JsonNode failEffects) { this.failEffects = failEffects; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Growth {
        private String skillPointKey;
        private List<String> featConditions = new ArrayList<String>();
        private boolean resetOnRecovery;
        private List<String> resetKeys = new ArrayList<String>();
        public String getSkillPointKey() { return skillPointKey; }
        public void setSkillPointKey(String skillPointKey) { this.skillPointKey = skillPointKey; }
        public List<String> getFeatConditions() {
            return featConditions == null ? Collections.<String>emptyList() : featConditions;
        }
        public void setFeatConditions(List<String> featConditions) {
            this.featConditions = featConditions == null ? new ArrayList<String>() : featConditions;
        }
        public boolean isResetOnRecovery() { return resetOnRecovery; }
        public void setResetOnRecovery(boolean resetOnRecovery) { this.resetOnRecovery = resetOnRecovery; }
        public List<String> getResetKeys() {
            return resetKeys == null ? Collections.<String>emptyList() : resetKeys;
        }
        public void setResetKeys(List<String> resetKeys) {
            this.resetKeys = resetKeys == null ? new ArrayList<String>() : resetKeys;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TeamOpposition {
        /** WHITE / RED / PASSIVE;空 = 不投影检定车道。 */
        private String lane;
        private String opposeRoleCode;
        /** 作者已在 disadvantageIf 里引用的状态名;adapter 只按对抗角色挂上,不改 DC。 */
        private String opposedCondition;
        public String getLane() { return lane; }
        public void setLane(String lane) { this.lane = lane; }
        public String getOpposeRoleCode() { return opposeRoleCode; }
        public void setOpposeRoleCode(String opposeRoleCode) { this.opposeRoleCode = opposeRoleCode; }
        public String getOpposedCondition() { return opposedCondition; }
        public void setOpposedCondition(String opposedCondition) { this.opposedCondition = opposedCondition; }
    }
}
