package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.domain.MmsMerchant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「只有入驻的平台商家才能报名成为节点」这道闸的入驻档位判定。
 *
 * 为什么要有这条:原来 requireMerchantNodePerm 只查「有没有商家档案」——
 * 建了档还没过审(status=0)、审核被驳回(2)、账号已禁用(accountStatus=2)的,
 * 照样能提交据点投放申请。等于平台给一个还没认过的主体在漫游地图上开了个位置,
 * 而且不报错。口径跟商家公开主页(ApiMerchantController#publicDetail)对齐。
 *
 * 这里只测「档位判定」这一段:权限位(can_open_as_node)和数据库查询要注入一堆
 * 依赖,不在本类范围 —— 那部分由控制器集成路径覆盖。
 */
class MerchantNodePermGateTest {

    /** 复刻控制器里的档位判据,并断言它和被测源码逐字一致 —— 防止两边各写一套后漂移。 */
    private String settleStatus(MmsMerchant merchant) {
        if (merchant == null) return "请先完成商家入驻";
        if (merchant.getStatus() == null || merchant.getStatus() != 1L) {
            return "商家入驻还没通过审核,通过后才能报名成为节点";
        }
        if (!Long.valueOf(1L).equals(merchant.getAccountStatus())
                || !Integer.valueOf(0).equals(merchant.getDelFlag())) {
            return "商家账号已停用,不能报名成为节点";
        }
        return null;
    }

    private MmsMerchant merchant(Long status, Long accountStatus) {
        MmsMerchant m = new MmsMerchant();
        m.setMemberId(7L);
        m.setStatus(status);
        m.setAccountStatus(accountStatus);
        m.setDelFlag(0);
        return m;
    }

    @Test
    @Timeout(5)
    void approvedAndEnabled_passes() {
        assertNull(settleStatus(merchant(1L, 1L)));
    }

    /** ★ 这条正是原来漏掉的:建了档、还没过审,却能提交据点申请。 */
    @Test
    @Timeout(5)
    void pendingAudit_rejected() {
        assertNotNull(settleStatus(merchant(0L, 1L)), "待审核商家不该能报名成为节点");
    }

    @Test
    @Timeout(5)
    void auditRejected_rejected() {
        assertNotNull(settleStatus(merchant(2L, 1L)), "审核驳回的商家不该能报名成为节点");
    }

    @Test
    @Timeout(5)
    void statusNull_rejected() {
        assertNotNull(settleStatus(merchant(null, 1L)), "状态缺失按未通过处理(fail-closed)");
    }

    @Test
    @Timeout(5)
    void disabledAccount_rejected() {
        assertNotNull(settleStatus(merchant(1L, 2L)), "已停用账号不该能报名成为节点");
    }

    @Test
    void missingOrPendingAccountStatusRejectedFailClosed() {
        assertNotNull(settleStatus(merchant(1L, null)));
        assertNotNull(settleStatus(merchant(1L, 0L)));
    }

    @Test
    void deletedMerchantRejected() {
        MmsMerchant deleted = merchant(1L, 1L);
        deleted.setDelFlag(1);
        assertNotNull(settleStatus(deleted));
    }

    @Test
    @Timeout(5)
    void noMerchantProfile_rejected() {
        assertNotNull(settleStatus(null));
    }

    /**
     * ★ 判据必须真的写在控制器里 —— 否则本类测的只是它自己复刻的那一份,
     * 是个恒真断言。源码里删掉任一条,这里就红。
     */
    @Test
    @Timeout(5)
    void gateActuallyLivesInTheController() throws Exception {
        Method m = ApiMerchantNodeController.class
                .getDeclaredMethod("requireMerchantNodePerm", MerchantAccessContext.class, String[].class);
        assertNotNull(m, "requireMerchantNodePerm 不存在了");

        String source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/com/chengyinhub/web/controller/api/ApiMerchantNodeController.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        int from = source.indexOf("private MmsMerchant requireMerchantNodePerm");
        assertTrue(from > 0, "找不到闸方法");
        String body = source.substring(from, source.indexOf("\n    }", from));
        assertTrue(body.contains("merchant.getStatus() != 1L"), "闸里少了审核通过判据");
        assertTrue(body.contains("Long.valueOf(1L).equals(merchant.getAccountStatus())"),
                "闸里少了账号生效判据");
        assertTrue(body.contains("Integer.valueOf(0).equals(merchant.getDelFlag())"),
                "闸里少了删除态判据");
    }
}
