package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;

/**
 * 附近正在漫游的人(原型 f-other-a / f-other-b 那张半屏的数据源)。
 *
 * ⚠️ 这个 VO 是发给**陌生人**的,字段范围就是 2026-09-10 拍板的那几样:
 *    昵称、头像、地盘 %、探店数、在走时长、TA 这一趟点亮的店。
 *    **不许**往里加手机号、真实姓名、memberId 以外的身份线索;memberId 本身留着是因为
 *    前端要拿它做 marker 的去重键与「看 TA 的漫游」的入参,它不显示在屏上。
 *    坐标是已经截过位的(约 100m 网格),不是原始定位点 —— 截位在 ApiRoamController.presence。
 */
public class NearbyRunnerVO {
    private Long memberId;
    private String nickname;
    private String avatar;
    private BigDecimal lat;
    private BigDecimal lng;
    /** 客户端口径的探索度 0-99。 */
    private Integer explorePct;
    /** 这一趟去过几家店(count(distinct source_type, source_id))。 */
    private Integer shops;
    /** 已经走了多少秒(now - started_at)。前端自己格式化成 38:20。 */
    private Long elapsedSec;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }
    public BigDecimal getLng() { return lng; }
    public void setLng(BigDecimal lng) { this.lng = lng; }
    public Integer getExplorePct() { return explorePct; }
    public void setExplorePct(Integer explorePct) { this.explorePct = explorePct; }
    public Integer getShops() { return shops; }
    public void setShops(Integer shops) { this.shops = shops; }
    public Long getElapsedSec() { return elapsedSec; }
    public void setElapsedSec(Long elapsedSec) { this.elapsedSec = elapsedSec; }
}
