package com.chengyinhub.business.locationtrust;

/** Which location-bearing write is being scored. Stored as text in {@code location_trust_fix.kind}. */
public enum LocationTrustKind {
    /** /api/roam/checkin: lifetime first-visit XP for a point. */
    CHECKIN(true),
    /** /api/roam/poi/discover: POI found, credited at finish. */
    POI_DISCOVER(true),
    /** /api/roam/shop/visit: shop counted for the session and the shop-streak medal. */
    SHOP_VISIT(true),
    /** /api/roam/reveal: fog tiles. */
    REVEAL(false),
    /** /api/roam/presence: heartbeat for opt-in nearby visibility; never rewarded. */
    PRESENCE(false),
    /** Journey GPS arrive at the start station (300 m start fence still applies). */
    JOURNEY_START(false),
    /** Journey GPS arrive after the start: location optional (owner 2026-11-13), scored when present. */
    JOURNEY_NODE(false),
    /** CITY claim photo evidence. */
    CLAIM_EVIDENCE(true),
    /** R4 guest-walk: the track of one walk made before sign-in, uploaded once after sign-in (fog cells only). */
    GUEST_WALK(false),
    /** G2 treasure hunt: a hidden item found at one exact place. */
    TREASURE_FIND(true),
    /** G2 walk-test gate: the creator stands at a node of their own journey. Never rewarded. */
    WALK_TEST_ONSITE(false);

    private final boolean pointReward;

    LocationTrustKind(boolean pointReward) {
        this.pointReward = pointReward;
    }

    /** A reward tied to standing at one exact point: the tighter check-in accuracy limit applies. */
    public boolean pointReward() {
        return pointReward;
    }
}
