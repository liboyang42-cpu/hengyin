package com.chengyinhub.business.service.notify;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * C09 独占：设备 / 环境 / 静默推送注册生命周期。夹具 token 不是生产 APNs/FCM 凭据。
 *
 * <p>注册 → 更新 → 撤权后停止再投。静默登记不是 APPLIED 展示证明。
 */
public final class DevicePushRegistrationLifecycle {

    public static final String ENV_SANDBOX = "SANDBOX";
    public static final String ENV_PRODUCTION_LABEL = "PRODUCTION_LABEL";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_DISABLED = "DISABLED";

    private final RegistrationStore store;

    public DevicePushRegistrationLifecycle(RegistrationStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public Registration register(String deviceId, String environment, boolean silent, String fixtureToken) {
        String id = requireDeviceId(deviceId);
        String env = requireEnvironment(environment);
        requireFixtureToken(fixtureToken);
        Registration existing = store.find(id);
        if (existing != null) {
            if (STATUS_REVOKED.equals(existing.status)) {
                throw new IllegalStateException("已撤权设备不能原地重注册 deviceId=" + id);
            }
            if (!env.equals(existing.environment) || existing.silent != silent
                    || !fixtureToken.equals(existing.fixtureToken)) {
                Registration updated = existing.withToken(env, silent, fixtureToken, STATUS_ACTIVE);
                store.replaceSameIdentity(updated);
                return updated;
            }
            return existing;
        }
        Registration created = new Registration(id, env, silent, fixtureToken, STATUS_ACTIVE);
        store.insert(created);
        return created;
    }

    public Registration disable(String deviceId) {
        Registration original = require(deviceId);
        if (STATUS_REVOKED.equals(original.status)) {
            return original;
        }
        Registration next = original.withStatus(STATUS_DISABLED);
        store.replaceSameIdentity(next);
        return next;
    }

    public Registration revoke(String deviceId) {
        Registration original = require(deviceId);
        Registration next = original.withStatus(STATUS_REVOKED);
        store.replaceSameIdentity(next);
        return next;
    }

    /**
     * 投递许可。撤权 / 停用后不得再投。APPLIED 展示投影不能重新授权。
     */
    public SendDecision maySend(String deviceId, String appliedProjection) {
        Registration original = require(deviceId);
        boolean projectionClaimsApplied = ImmutableReceiptProducer.PROJECTION_APPLIED.equals(appliedProjection);
        if (STATUS_REVOKED.equals(original.status) || STATUS_DISABLED.equals(original.status)) {
            return new SendDecision(false, original, "REVOKED_OR_DISABLED", projectionClaimsApplied);
        }
        return new SendDecision(true, original, "ACTIVE", projectionClaimsApplied);
    }

    public boolean treatsAppliedProjectionAsAuthorization(String appliedProjection) {
        return false;
    }

    public boolean treatsAppliedProjectionAsOriginalProof(String appliedProjection) {
        return false;
    }

    /**
     * Missing deviceId stays Java {@code null} / UNKNOWN and does not occupy the ledger.
     * Never-seen ids return UNKNOWN without insert, so a later register is still the unique original.
     */
    public Registration recoverOriginal(String originalRequestId, String appliedProjection) {
        return recoverUnknown(originalRequestId, appliedProjection);
    }

    public Registration recoverUnknown(String originalRequestId, String appliedProjection) {
        String id = parseDeviceId(originalRequestId);
        if (id == null) {
            return unknownRegistration(null);
        }
        Registration original = store.find(id);
        if (original != null) {
            return original;
        }
        return unknownRegistration(id);
    }

    private Registration require(String deviceId) {
        String id = requireDeviceId(deviceId);
        Registration original = store.find(id);
        if (original == null) {
            throw new IllegalStateException("原设备注册不存在 deviceId=" + id);
        }
        return original;
    }

    static String parseDeviceId(String deviceId) {
        if (isBlank(deviceId)) {
            return null;
        }
        String id = deviceId.trim();
        if (id.length() > 128) {
            return null;
        }
        return id;
    }

    static String requireDeviceId(String deviceId) {
        if (isBlank(deviceId)) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        String id = deviceId.trim();
        if (id.length() > 128) {
            throw new IllegalArgumentException("deviceId 过长");
        }
        return id;
    }

    static Registration unknownRegistration(String deviceId) {
        return new Registration(deviceId, null, false, null, "UNKNOWN");
    }

    static String requireEnvironment(String environment) {
        if (!ENV_SANDBOX.equals(environment) && !ENV_PRODUCTION_LABEL.equals(environment)) {
            throw new IllegalArgumentException("环境只允许夹具 SANDBOX / PRODUCTION_LABEL");
        }
        return environment;
    }

    static String requireFixtureToken(String fixtureToken) {
        if (isBlank(fixtureToken) || !fixtureToken.startsWith("fixture-")) {
            throw new IllegalArgumentException("只接受 fixture- 前缀的隔离 token，禁止生产凭据");
        }
        return fixtureToken;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class Registration {
        public final String deviceId;
        public final String environment;
        public final boolean silent;
        public final String fixtureToken;
        public final String status;

        Registration(String deviceId, String environment, boolean silent, String fixtureToken, String status) {
            this.deviceId = deviceId;
            this.environment = environment;
            this.silent = silent;
            this.fixtureToken = fixtureToken;
            this.status = status;
        }

        Registration withToken(String environment, boolean silent, String fixtureToken, String status) {
            return new Registration(deviceId, environment, silent, fixtureToken, status);
        }

        Registration withStatus(String status) {
            return new Registration(deviceId, environment, silent, fixtureToken, status);
        }

        public Map<String, Object> asRequeryableView() {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("deviceId", deviceId);
            view.put("environment", environment);
            view.put("silent", Boolean.valueOf(silent));
            view.put("status", status);
            view.put("fixtureTokenPrefix", "fixture-");
            return Collections.unmodifiableMap(view);
        }
    }

    public static final class SendDecision {
        public final boolean allowed;
        public final Registration registration;
        public final String reason;
        public final boolean currentProjectionClaimsApplied;

        SendDecision(boolean allowed, Registration registration, String reason,
                     boolean currentProjectionClaimsApplied) {
            this.allowed = allowed;
            this.registration = registration;
            this.reason = reason;
            this.currentProjectionClaimsApplied = currentProjectionClaimsApplied;
        }
    }

    public interface RegistrationStore {
        Registration find(String deviceId);

        void insert(Registration registration);

        void replaceSameIdentity(Registration registration);
    }
}
