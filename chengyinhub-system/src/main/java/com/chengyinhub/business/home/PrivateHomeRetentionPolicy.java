package com.chengyinhub.business.home;

import com.chengyinhub.common.exception.ServiceException;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Explicit operational reference-expiry policy, not a legal retention or row-deletion policy. */
@Component
@ConfigurationProperties(prefix="app.private-home.retention")
public class PrivateHomeRetentionPolicy {
    private boolean enabled;
    private String version;
    private Long expiredReferenceGraceMillis;
    public synchronized boolean isEnabled() { return enabled; }
    public synchronized void setEnabled(boolean enabled) { this.enabled = enabled; }
    public synchronized String getVersion() { return version; }
    public synchronized void setVersion(String version) { this.version = version; }
    public synchronized Long getExpiredReferenceGraceMillis() { return expiredReferenceGraceMillis; }
    public synchronized void setExpiredReferenceGraceMillis(Long value) { expiredReferenceGraceMillis = value; }
    public synchronized Snapshot requireEnabled() {
        if (!enabled) throw new ServiceException("HOME_RETENTION_DISABLED");
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                || expiredReferenceGraceMillis == null || expiredReferenceGraceMillis < 0)
            throw new ServiceException("HOME_RETENTION_POLICY_REQUIRED");
        return new Snapshot(version, expiredReferenceGraceMillis);
    }
    public void revalidate(Snapshot snapshot) {
        Snapshot current = requireEnabled();
        if (!Objects.equals(snapshot.version, current.version) || snapshot.graceMillis != current.graceMillis)
            throw new ServiceException("HOME_RETENTION_POLICY_CHANGED");
    }
    public static final class Snapshot extends PrivateHomeContracts.Redacted {
        public final String version;
        public final long graceMillis;
        private Snapshot(String version, long graceMillis) { this.version = version; this.graceMillis = graceMillis; }
        public long cutoff(long now) {
            if (now < 0 || graceMillis > now) throw new ServiceException("HOME_RETENTION_CLOCK_INVALID");
            return now - graceMillis;
        }
    }
}
