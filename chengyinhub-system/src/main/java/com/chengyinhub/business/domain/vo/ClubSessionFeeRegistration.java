package com.chengyinhub.business.domain.vo;

/** 第十二轮 k5:俱乐部场次商家费的计费报名(已付、未退、已参加)。 */
public class ClubSessionFeeRegistration {
    private Long registrationId;
    private Long orderNum;
    /** cms_registration_seat 已核销座位数;0 = 没有逐座事实,按整单核销计。 */
    private Integer verifiedSeats;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public Long getOrderNum() { return orderNum; }
    public void setOrderNum(Long value) { orderNum = value; }
    public Integer getVerifiedSeats() { return verifiedSeats; }
    public void setVerifiedSeats(Integer value) { verifiedSeats = value; }

    /** 参加人数:有逐座核销事实按座位数,否则整单(order_num,缺省 1)。 */
    public int heads() {
        if (verifiedSeats != null && verifiedSeats > 0) {
            return verifiedSeats;
        }
        return orderNum == null || orderNum <= 0 ? 1 : orderNum.intValue();
    }
}
