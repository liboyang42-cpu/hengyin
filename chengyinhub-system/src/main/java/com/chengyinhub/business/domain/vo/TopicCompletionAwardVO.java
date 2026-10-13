package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.PlayerBadge;
import java.util.ArrayList;
import java.util.List;

public class TopicCompletionAwardVO {
    private boolean firstCompletion;
    private int earnedXp;
    private java.util.Date earnedXpAt;
    private List<PlayerBadge> badges = new ArrayList<>();
    public boolean isFirstCompletion() { return firstCompletion; }
    public void setFirstCompletion(boolean firstCompletion) { this.firstCompletion = firstCompletion; }
    public int getEarnedXp() { return earnedXp; }
    public void setEarnedXp(int earnedXp) { this.earnedXp = earnedXp; }
    public java.util.Date getEarnedXpAt() { return earnedXpAt; }
    public void setEarnedXpAt(java.util.Date earnedXpAt) { this.earnedXpAt = earnedXpAt; }
    public List<PlayerBadge> getBadges() { return badges; }
    public void setBadges(List<PlayerBadge> badges) { this.badges = badges; }
}
