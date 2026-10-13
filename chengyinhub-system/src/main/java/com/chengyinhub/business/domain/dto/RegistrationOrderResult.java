package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.CmsRegistration;

import java.util.Collections;
import java.util.List;

/** requestId 建单或重放的领域结果。 */
public class RegistrationOrderResult {
    private final List<CmsRegistration> registrations;
    private final boolean replayed;

    public RegistrationOrderResult(List<CmsRegistration> registrations, boolean replayed) {
        this.registrations = registrations == null ? Collections.<CmsRegistration>emptyList() : registrations;
        this.replayed = replayed;
    }

    public List<CmsRegistration> getRegistrations() {
        return registrations;
    }

    public CmsRegistration getPrimary() {
        return registrations.isEmpty() ? null : registrations.get(0);
    }

    public boolean isReplayed() {
        return replayed;
    }
}
