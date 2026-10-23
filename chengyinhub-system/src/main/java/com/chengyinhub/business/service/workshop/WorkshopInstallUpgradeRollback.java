package com.chengyinhub.business.service.workshop;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C10 exclusive install / upgrade / rollback machine.
 *
 * <p>Existing iOS {@code WorkshopPaidInstall*} is a private-draft grant
 * ({@code W18_PRIVATE_COMPONENT_ONLY}); this adapter is a separate metadata
 * machine and is unwired from Runtime / public install registry.
 *
 * <p>Qmod runtime originals are not on this remote. A run against missing
 * originals is {@code WAITING_SOURCE}, not success. Rollback without a retained
 * prior version fails instead of inventing one.
 */
public final class WorkshopInstallUpgradeRollback {

    public enum Phase {
        IDLE,
        INSTALLED,
        UPGRADING,
        ROLLED_BACK,
        WAITING_SOURCE,
        UNKNOWN
    }

    public record Snapshot(
            Phase phase,
            String originalRequestId,
            String installedVersion,
            String retainedPriorVersion,
            boolean secondEffect,
            String reason) {

        public Snapshot {
            phase = phase == null ? Phase.UNKNOWN : phase;
            reason = reason == null ? "" : reason;
        }
    }

    private final WorkshopOriginalRequestBinder binder;
    private Phase phase = Phase.IDLE;
    private String installedVersion;
    private String retainedPriorVersion;
    private String lastRequestId;

    public WorkshopInstallUpgradeRollback() {
        this(new WorkshopOriginalRequestBinder());
    }

    public WorkshopInstallUpgradeRollback(WorkshopOriginalRequestBinder binder) {
        this.binder = binder == null ? new WorkshopOriginalRequestBinder() : binder;
    }

    public Snapshot snapshot() {
        return new Snapshot(phase, lastRequestId, installedVersion, retainedPriorVersion, false, phase.name());
    }

    public Snapshot install(String originalRequestId, String version) {
        return apply(originalRequestId, "install", () -> liveInstall(originalRequestId, version));
    }

    public Snapshot upgrade(String originalRequestId, String nextVersion) {
        return apply(originalRequestId, "upgrade", () -> liveUpgrade(originalRequestId, nextVersion));
    }

    public Snapshot rollback(String originalRequestId) {
        return apply(originalRequestId, "rollback", () -> liveRollback(originalRequestId));
    }

    public Snapshot recover(String originalRequestId) {
        return toSnapshot(binder.recover(originalRequestId));
    }

    private Snapshot apply(String originalRequestId, String operation, java.util.function.Supplier<Map<String, Object>> body) {
        Map<String, Object> result = binder.oneShot(originalRequestId, operation, body);
        Snapshot snap = toSnapshot(result);
        if (!Boolean.TRUE.equals(result.get("replay"))) {
            lastRequestId = snap.originalRequestId();
            if (snap.phase() == Phase.INSTALLED || snap.phase() == Phase.UPGRADING
                    || snap.phase() == Phase.ROLLED_BACK || snap.phase() == Phase.WAITING_SOURCE
                    || snap.phase() == Phase.UNKNOWN) {
                phase = snap.phase();
                if (result.get("installedVersion") instanceof String) {
                    installedVersion = (String) result.get("installedVersion");
                }
                if (result.containsKey("retainedPriorVersion")) {
                    Object prior = result.get("retainedPriorVersion");
                    retainedPriorVersion = prior instanceof String ? (String) prior : null;
                }
            }
        }
        return snap;
    }

    private Map<String, Object> liveInstall(String originalRequestId, String version) {
        if (originalRequestId == null || originalRequestId.isBlank()) {
            return WorkshopOriginalRequestBinder.unknown(null, "install", "originalRequestId missing");
        }
        if (version == null || version.isBlank()) {
            return rejected(originalRequestId, "version missing");
        }
        if (phase != Phase.IDLE && phase != Phase.ROLLED_BACK) {
            return rejected(originalRequestId, "install only from idle or rolled_back");
        }
        if (!WorkshopModuleImportGate.QMOD_ORIGINAL_PRESENT) {
            return waiting(originalRequestId, version, null, "qmod.original");
        }
        Map<String, Object> ok = base(originalRequestId, Phase.INSTALLED, version, null, "installed");
        ok.put("status", WorkshopOriginalRequestBinder.APPLIED);
        return ok;
    }

    private Map<String, Object> liveUpgrade(String originalRequestId, String nextVersion) {
        if (originalRequestId == null || originalRequestId.isBlank()) {
            return WorkshopOriginalRequestBinder.unknown(null, "upgrade", "originalRequestId missing");
        }
        if (phase != Phase.INSTALLED || installedVersion == null) {
            return rejected(originalRequestId, "upgrade requires installed version");
        }
        if (nextVersion == null || nextVersion.isBlank() || nextVersion.equals(installedVersion)) {
            return rejected(originalRequestId, "next version missing or unchanged");
        }
        if (!WorkshopModuleImportGate.QMOD_ORIGINAL_PRESENT) {
            return waiting(originalRequestId, installedVersion, installedVersion, "qmod.original");
        }
        Map<String, Object> ok = base(originalRequestId, Phase.UPGRADING, nextVersion, installedVersion, "upgrading");
        ok.put("status", WorkshopOriginalRequestBinder.APPLIED);
        return ok;
    }

    private Map<String, Object> liveRollback(String originalRequestId) {
        if (originalRequestId == null || originalRequestId.isBlank()) {
            return WorkshopOriginalRequestBinder.unknown(null, "rollback", "originalRequestId missing");
        }
        String prior = retainedPriorVersion;
        if (phase == Phase.UPGRADING) {
            prior = retainedPriorVersion;
        }
        if (prior == null || prior.isBlank()) {
            return rejected(originalRequestId, "rollback without retained prior");
        }
        Map<String, Object> ok = base(originalRequestId, Phase.ROLLED_BACK, prior, null, "rolled back to retained prior");
        ok.put("status", WorkshopOriginalRequestBinder.APPLIED);
        ok.put("inventedPrior", Boolean.FALSE);
        return ok;
    }

    private static Map<String, Object> waiting(String requestId, String installed, String prior, String capability) {
        Map<String, Object> out = base(requestId, Phase.WAITING_SOURCE, installed, prior,
                WorkshopOriginalRequestBinder.WAITING_SOURCE + ":" + capability);
        out.put("status", WorkshopOriginalRequestBinder.WAITING_SOURCE);
        return out;
    }

    private static Map<String, Object> rejected(String requestId, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("originalRequestId", requestId);
        out.put("status", WorkshopOriginalRequestBinder.REJECTED);
        out.put("phase", Phase.UNKNOWN.name());
        out.put("reason", reason);
        out.put("secondEffect", Boolean.FALSE);
        out.put("inventedPrior", Boolean.FALSE);
        return out;
    }

    private static Map<String, Object> base(String requestId, Phase phase, String installed, String prior, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("originalRequestId", requestId);
        out.put("phase", phase.name());
        out.put("installedVersion", installed);
        out.put("retainedPriorVersion", prior);
        out.put("reason", reason);
        out.put("secondEffect", Boolean.FALSE);
        out.put("wrotePublicRegistry", Boolean.FALSE);
        return out;
    }

    private Snapshot toSnapshot(Map<String, Object> result) {
        Phase next = Phase.UNKNOWN;
        Object phaseName = result.get("phase");
        if (phaseName instanceof String) {
            try {
                next = Phase.valueOf((String) phaseName);
            } catch (IllegalArgumentException ignored) {
                next = Phase.UNKNOWN;
            }
        } else if (WorkshopOriginalRequestBinder.WAITING_SOURCE.equals(String.valueOf(result.get("status")))) {
            next = Phase.WAITING_SOURCE;
        } else if (WorkshopOriginalRequestBinder.UNKNOWN.equals(String.valueOf(result.get("status")))) {
            next = Phase.UNKNOWN;
        } else if (WorkshopOriginalRequestBinder.REPLAY.equals(String.valueOf(result.get("status")))) {
            next = phase;
        }
        Object requestId = result.get("originalRequestId");
        Object installed = result.get("installedVersion");
        Object prior = result.get("retainedPriorVersion");
        boolean second = Boolean.TRUE.equals(result.get("replay"));
        return new Snapshot(
                next,
                requestId instanceof String ? (String) requestId : null,
                installed instanceof String ? (String) installed : installedVersion,
                prior instanceof String ? (String) prior : retainedPriorVersion,
                second,
                result.get("reason") == null ? String.valueOf(result.get("status")) : String.valueOf(result.get("reason")));
    }
}
