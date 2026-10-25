package com.chengyinhub.common.config;

/**
 * Process-start restore fence, independent of request identity and provider outcome.
 * Absence and the exact value "false" retain normal dispatch. Any other value
 * closes dispatch, including malformed/empty configuration. There is no runtime
 * setter: reopening requires an explicitly normal server process.
 */
public final class RestoreDispatchGuard {
    private static final String STARTUP_MODE = System.getenv("CHENGYIN_RESTORE_MODE");

    private RestoreDispatchGuard() { }

    public static boolean isRestoreMode() {
        return STARTUP_MODE != null && !"false".equals(STARTUP_MODE);
    }

    public static void requireDispatchAllowed() {
        if (isRestoreMode()) {
            throw new Blocked();
        }
    }

    /** Not NOT_SENT: restored data cannot prove what occurred after the backup. */
    public static final class Blocked extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Blocked() {
            super("RECOVERY_DISPATCH_BLOCKED");
        }
    }
}
