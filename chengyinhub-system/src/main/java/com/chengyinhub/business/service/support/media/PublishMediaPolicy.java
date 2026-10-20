package com.chengyinhub.business.service.support.media;

import java.util.List;
import com.chengyinhub.business.service.support.media.PublishMediaContract.AssetEvidence;
import com.chengyinhub.business.service.support.media.PublishMediaContract.PolicyDecision;
import com.chengyinhub.business.service.support.media.PublishMediaContract.PolicyValidity;
import com.chengyinhub.business.service.support.media.PublishMediaContract.Purpose;
import com.chengyinhub.business.service.support.media.PublishMediaContract.Role;
import com.chengyinhub.business.service.support.media.PublishMediaContract.VerifiedMedia;

/**
 * Explicit server-owned policy, never client configuration. No product limits are supplied here.
 * Implementations must cover count/mixing/order and probed MIME/container/codec/bytes/dimensions/
 * duration/audio, including poster constraints. Missing product decisions must return HOLD.
 * This module deliberately provides no production implementation or Spring registration.
 */
public interface PublishMediaPolicy {
    Purpose purpose();

    /** Required immutable policy version/window; missing or expired windows fail closed. */
    PolicyValidity validity();

    /** Opt-in is possible only in a separately approved server policy implementation. */
    default boolean videoEnabled() { return false; }

    PolicyDecision validateAsset(AssetEvidence evidence, Role role);

    PolicyDecision validateSelection(List<VerifiedMedia> orderedEvidence);

    interface Provider {
        PublishMediaPolicy forPurpose(Purpose purpose);
    }
}
