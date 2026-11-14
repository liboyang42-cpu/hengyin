package com.chengyinhub.business.merchantgap;

import java.util.List;

/**
 * Generates a store-avatar model from one approved image. No production bean exists: without one,
 * {@code /api/merchant/npc/avatar/status} answers {@code available:false} and generate is refused.
 */
public interface MerchantAvatarProvider {
    /** Stable provider id stored on each job. */
    String id();

    /** Styles this provider accepts; the app shows them as the choice list. */
    List<String> styles();

    /** Synchronous result, or a PENDING result the provider will finish later. */
    Result generate(long merchantId, String imageUrl, String style);

    final class Result {
        public final String status;
        public final String modelUrl;
        public final String thumbUrl;
        public final String failReason;

        public Result(String status, String modelUrl, String thumbUrl, String failReason) {
            this.status = status;
            this.modelUrl = modelUrl;
            this.thumbUrl = thumbUrl;
            this.failReason = failReason;
        }
    }
}
