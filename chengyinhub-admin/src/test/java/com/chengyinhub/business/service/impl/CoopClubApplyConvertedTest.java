package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopClubApply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 俱乐部承接申请「已转为邀约」状态。
 *
 * 病灶(2026-08-07 修):主办方同意后**不是直接批准申请**,而是回发一张带 originApplyId 的
 * type=1 邀约。但申请状态没人回写 ⇒ 停在 0「待处理」，
 * **俱乐部侧永远看不到自己被通过了**,还以为主办方没理他,甚至会再申请一次。
 *
 * 这里只钉状态常量的语义边界;回写时机由 CoopInviteServiceImpl.insertCoopInvite 与建邀约同事务保证。
 */
class CoopClubApplyConvertedTest {

    /** 四个状态必须互不相同 —— 撞值会让「已转为邀约」被当成「已婉拒」显示。 */
    @Test
    @Timeout(5)
    void statusConstantsAreDistinct() {
        int[] all = { CoopClubApply.STATUS_PENDING, CoopClubApply.STATUS_DECLINED,
                CoopClubApply.STATUS_WITHDRAWN, CoopClubApply.STATUS_CONVERTED };
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                assertNotEquals(all[i], all[j], "申请状态常量撞值,前端会把两种情形显示成同一句话");
            }
        }
    }

    /**
     * ★与库里既有语义对齐:0待处理 1已婉拒 2已撤回。
     * 新增的「已转为邀约」只能取 3 —— 占用 0/1/2 会把存量数据的含义改掉。
     */
    @Test
    @Timeout(5)
    void convertedMustNotReuseLegacyValues() {
        assertEquals(0, CoopClubApply.STATUS_PENDING);
        assertEquals(1, CoopClubApply.STATUS_DECLINED);
        assertEquals(2, CoopClubApply.STATUS_WITHDRAWN);
        assertEquals(3, CoopClubApply.STATUS_CONVERTED,
                "占用 0/1/2 会把存量申请的含义改掉 —— 那是静默的数据语义漂移");
    }
}
