package com.chengyinhub.business.domain.vo;

/**
 * 协作邀约里「对方是谁」的展示出口(§3.8 联系方式口径)。
 *
 * <p>两档信息不同闸:
 * <ul>
 *   <li><b>名称</b>(俱乐部名 / 商家店名)是可公开信息,恒下发 —— 没有它,合作区只能显示
 *       「俱乐部 · 合作中」,商家看不出到底是谁在跟他合作;</li>
 *   <li><b>负责人 + 电话</b>是受闸信息,只在邀约 {@code status=1}(已接受)时给,
 *       由装配方调 {@link #stripContact()} 落闸。</li>
 * </ul>
 *
 * <p>★ 不要拿 {@link PublicMerchantSummaryVO} 改造来干这件事:那是给玩家看主题详情的脱敏出口,
 * <b>故意</b>不带联系方式(见 PublicDetailVoBoundaryTest 断言它没有 getPhone),
 * 放开它等于对全体玩家放开商家电话。这里是另建的、作用域只到「合作双方」的出口。
 */
public class CoopPartnerVO {

    /** 对方名称:俱乐部名 / 商家店名(无档案时退回昵称) */
    private String name;

    /** 负责人(仅俱乐部有);受闸 */
    private String leaderName;

    /** 联系电话;受闸 */
    private String phone;

    public static CoopPartnerVO of(String name, String leaderName, String phone) {
        CoopPartnerVO out = new CoopPartnerVO();
        out.name = name;
        out.leaderName = leaderName;
        out.phone = phone;
        return out;
    }

    /** 落闸:未接受的邀约只给名称,不给联系方式。 */
    public void stripContact() {
        this.leaderName = null;
        this.phone = null;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLeaderName() { return leaderName; }
    public void setLeaderName(String leaderName) { this.leaderName = leaderName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
}
