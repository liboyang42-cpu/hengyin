package com.chengyinhub.business.discovery;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * K2 discovery: one row of the "near you" list. Only organic facts (where it is, what it is, how new, whether it is your
 * club's) are carried. There is deliberately no field for money paid, a campaign or an operator sort, so nothing paid can
 * change the order.
 */
public final class DiscoveryItem {
    /** PLACE, DEAL (an operator opted-in offer) or EVENT. */
    public enum Kind { PLACE, DEAL, EVENT }

    final Kind kind;
    final long id;
    final String title;
    final String subtitle;
    final double lat;
    final double lng;
    final Date createdAt;
    final boolean yourClub;
    final Long merchantId;
    final Long poiId;
    final String coverImg;
    final Date startsAt;
    double distanceM;
    DiscoveryReason reason;
    double score;

    DiscoveryItem(Kind kind, long id, String title, String subtitle, double lat, double lng, Date createdAt,
                  boolean yourClub, Long merchantId, Long poiId, String coverImg, Date startsAt) {
        this.kind = kind;
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.lat = lat;
        this.lng = lng;
        this.createdAt = createdAt;
        this.yourClub = yourClub;
        this.merchantId = merchantId;
        this.poiId = poiId;
        this.coverImg = coverImg;
        this.startsAt = startsAt;
    }

    public Kind kind() { return kind; }
    public long id() { return id; }
    public DiscoveryReason reason() { return reason; }
    public double distanceM() { return distanceM; }

    Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", kind.name());
        view.put("id", id);
        view.put("title", title);
        view.put("subtitle", subtitle);
        view.put("lat", lat);
        view.put("lng", lng);
        view.put("distanceM", Math.round(distanceM));
        view.put("reason", reason.name());
        view.put("merchantId", merchantId);
        view.put("poiId", poiId);
        view.put("coverImg", coverImg);
        view.put("startsAt", startsAt);
        return view;
    }
}
