package com.chengyinhub.business.domain;

import java.util.Date;

/** P2 已点亮迷雾格 roam_fog_tile(uk member+geohash7 幂等)。 */
public class RoamFogTile {
    private Long id;
    private Long memberId;
    private Long sessionId;
    private String geohash7;
    private Date revealedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getGeohash7() { return geohash7; }
    public void setGeohash7(String geohash7) { this.geohash7 = geohash7; }
    public Date getRevealedAt() { return revealedAt; }
    public void setRevealedAt(Date revealedAt) { this.revealedAt = revealedAt; }
}
