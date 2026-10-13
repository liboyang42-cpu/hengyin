package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.List;

public class BadgeWallV2VO {
    private List<BadgeWallItemVO> identity = new ArrayList<>();
    private List<BadgeWallItemVO> growth = new ArrayList<>();
    private List<BadgeWallItemVO> collection = new ArrayList<>();
    private List<BadgeWallItemVO> honor = new ArrayList<>();
    public List<BadgeWallItemVO> getIdentity() { return identity; }
    public void setIdentity(List<BadgeWallItemVO> v) { this.identity = v; }
    public List<BadgeWallItemVO> getGrowth() { return growth; }
    public void setGrowth(List<BadgeWallItemVO> v) { this.growth = v; }
    public List<BadgeWallItemVO> getCollection() { return collection; }
    public void setCollection(List<BadgeWallItemVO> v) { this.collection = v; }
    public List<BadgeWallItemVO> getHonor() { return honor; }
    public void setHonor(List<BadgeWallItemVO> v) { this.honor = v; }
}
