package com.chengyinhub.business.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 提现转账渠道判定(R1)。
 *
 * 微信按 (appid, openid) 认人,配错会被判「不匹配」而拒付 ——
 * 表现是 App 用户提现一直失败,而错误信息指向 openid,极易误判成用户身份问题。
 */
class TransferChannelResolveTest {

    private static final String WX_APPID = "wx_mini";
    private static final String APP_APPID = "wx_open_app";

    @Test
    void pureMiniProgramUserGoesMiniChannel() {
        assertArrayEquals(new String[] {"o_mini", WX_APPID},
                CashWithdrawServiceImpl.resolveTransferChannel("o_mini", null, WX_APPID, APP_APPID));
    }

    @Test
    void pureAppUserGoesAppChannel() {
        assertArrayEquals(new String[] {"o_app", APP_APPID},
                CashWithdrawServiceImpl.resolveTransferChannel(null, "o_app", WX_APPID, APP_APPID));
    }

    @Test
    void dualChannelUserPrefersMiniProgram() {
        // 真双端用户:两个应用的两个不同 id。小程序渠道存量已验证可用，优先它。
        assertArrayEquals(new String[] {"o_mini", WX_APPID},
                CashWithdrawServiceImpl.resolveTransferChannel("o_mini", "o_app", WX_APPID, APP_APPID));
    }

    /**
     * ★ 本文件最重要的一条:存量 App 用户两列存着**同一个** App openid
     * (R1 修复前被误写进 wx_open_id,登录迁移又补进 app_open_id)。
     * 此时若按「wx 列优先」就会拿小程序 appid 配 App openid ⇒ 继续拒付。
     */
    @Test
    void legacyAppUserWithSameValueInBothColumnsGoesAppChannel() {
        assertArrayEquals(new String[] {"o_app", APP_APPID},
                CashWithdrawServiceImpl.resolveTransferChannel("o_app", "o_app", WX_APPID, APP_APPID));
    }

    @Test
    void emptyStringIsTreatedAsAbsent() {
        assertArrayEquals(new String[] {"o_app", APP_APPID},
                CashWithdrawServiceImpl.resolveTransferChannel("", "o_app", WX_APPID, APP_APPID));
        assertNull(CashWithdrawServiceImpl.resolveTransferChannel("", "", WX_APPID, APP_APPID));
        assertNull(CashWithdrawServiceImpl.resolveTransferChannel(null, null, WX_APPID, APP_APPID));
    }

    /**
     * 移动应用 appid 未配置时仍返回 App 渠道(appid 为 null),
     * 由 transferToBalance 明确拒绝并点名 appid —— 不在这里悄悄回落小程序 appid。
     */
    @Test
    void missingAppAppidStillReturnsAppChannelRatherThanFallingBack() {
        assertArrayEquals(new String[] {"o_app", null},
                CashWithdrawServiceImpl.resolveTransferChannel(null, "o_app", WX_APPID, null));
    }
}
