package com.chengyinhub.business.util;

import com.chengyinhub.business.security.PiiCryptoService;

/**
 * j2 F-08:主理人要从一串人里选一个(成员名单、角色、定向广播、排行榜、转让主理人)时给他看的名字。
 *
 * <p>口径同 C-05(2026-11-02 用户,报名参与者 / 客户列表 / 核销详情):报名时填写的姓名优先;
 * 没有就用昵称;默认昵称「城瘾用户」分不出人,改用「成员#id」。只给主理人一侧(能看报名名册的岗位)用,
 * 玩家互看的名单不经过这里。报名姓名是密文列,只在服务端解,不下发密文。
 */
public final class OrganiserPersonName {

    public static final String DEFAULT_NICKNAME = "城瘾用户";

    private OrganiserPersonName() {
    }

    /** signupName 已是明文(或空);nickname 是 ums_member.nickname。 */
    public static String pick(String signupName, String nickname, Long memberId) {
        if (signupName != null && !signupName.trim().isEmpty()) return signupName.trim();
        String nick = nickname == null ? "" : nickname.trim();
        if (!nick.isEmpty() && !DEFAULT_NICKNAME.equals(nick)) return nick;
        return memberId == null ? "参与人" : "成员#" + memberId;
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
