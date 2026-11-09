package com.chengyinhub.business.util;

import com.chengyinhub.business.security.PiiCryptoService;

/**
 * j2 F-08:主理人要从一串人里选一个(成员名单、角色、定向广播、排行榜、转让主理人)时给他看的名字。
 *
 * <p>口径同 C-05(2026-11-02 用户,报名参与者 / 客户列表 / 核销详情):报名时填写的姓名优先;
 * 没有就用昵称;默认昵称「城瘾用户」分不出人时带上加入 / 报名时刻(第十二轮,不再给编号)。只给主理人一侧(能看报名名册的岗位)用,
 * 玩家互看的名单不经过这里。报名姓名是密文列,只在服务端解,不下发密文。
 */
public final class OrganiserPersonName {

    public static final String DEFAULT_NICKNAME = "城瘾用户";

    private OrganiserPersonName() {
    }

    /** signupName 已是明文(或空);nickname 是 ums_member.nickname。 */
    public static String pick(String signupName, String nickname, Long memberId) {
        return pick(signupName, nickname, memberId, null, null);
    }

    /**
     * 第十二轮 k3(p3 N4):不再给主理人看「成员#id」这种库内编号。报名姓名 → 昵称;
     * 默认昵称「城瘾用户」分不出人时,带上这个人在本俱乐部里的时刻(加入 / 申请 / 报名到分钟),
     * 主理人能对上「刚才那位」,又不暴露任何编号。没有时刻可用时只给「城瘾用户」。
     */
    public static String pick(String signupName, String nickname, Long memberId, java.util.Date since, String verb) {
        if (signupName != null && !signupName.trim().isEmpty()) return signupName.trim();
        String nick = nickname == null ? "" : nickname.trim();
        if (!nick.isEmpty() && !DEFAULT_NICKNAME.equals(nick)) return nick;
        if (since == null) return DEFAULT_NICKNAME;
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("M月d日 HH:mm");
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT+8"));
        return DEFAULT_NICKNAME + " · " + fmt.format(since) + (verb == null || verb.isEmpty() ? "" : " " + verb);
    }

    /** 业主 2026-11-05:俱乐部主理人在所有地方都用昵称,不公开真实姓名。 */
    public static String ownerName(String nickname) {
        String nick = nickname == null ? "" : nickname.trim();
        return nick.isEmpty() ? DEFAULT_NICKNAME : nick;
    }

    /** 报名姓名密文 → 明文;解不开或为空给 null(上层回落昵称)。 */
    public static String decryptSignupName(Object stored) {
        if (!(stored instanceof String) || ((String) stored).trim().isEmpty()) return null;
        String value = (String) stored;
        try {
            PiiCryptoService pii = PiiCryptoService.getInstance();
            String plain = pii == null ? (value.startsWith("v1:") ? null : value) : pii.decrypt(value);
            return plain == null || plain.trim().isEmpty() ? null : plain.trim();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
