package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 支付成功回调的事务与唯一处理权合同。 */
class RegistrationPaymentAtomicityContractTest {

    private static String source(String relative) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName() != null && "chengyinhub-admin".equals(root.getFileName().toString())) {
            root = root.getParent();
        }
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    @Test
    void paymentCallbackMustClaimOnceInsideTransactionBeforeEffects() throws Exception {
        String service = source("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java");
        String mapper = source("chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CmsRegistrationMapper.java");
        String xml = source("chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMapper.xml");

        String mutation = source("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/WxPaymentCallbackMutationService.java");
        int method = service.indexOf("String handleWechatPayCallback");
        int register = service.indexOf("wxPaymentCallbackInboxService.register(", method);
        int fence = service.indexOf("wxPaymentCallbackInboxService.process(", method);
        int apply = service.indexOf("applyWechatPayCallback(registration, originalFacts)", method);
        int mutationDefinition = service.indexOf("IWxPaymentCallbackInboxService.Mutation mutation =", method);
        int verifiedFence = service.indexOf("wxPaymentCallbackInboxService.processVerified(", method);
        int machine = service.indexOf("private String applyWechatPayCallback");
        int claim = service.indexOf("claimPaymentSuccess", machine);
        int settlement = service.indexOf("settlementEntryService.recordOnPayment", machine);
        int reward = service.indexOf("umsMemberMapper.addPoints", machine);

        assertTrue(method > 0, "支付回调必须保留在公开 Service interface");
        // 2026-10-22 W3:回调整链改由 inbox fence 的事务承载(业务推进与 inbox DONE 同一事务提交)。
        assertTrue(register > method && mutationDefinition > register && apply > mutationDefinition
                        && verifiedFence > apply && fence > verifiedFence,
                "支付回调必须先登记 inbox,再经 process fence 领取处理权后才推进业务");
        assertTrue(machine > 0, "支付状态机必须只在 fence/调用方事务内执行");
        assertTrue(mutation.contains("@Transactional\n    public boolean apply("),
                "fence 的业务 mutation 必须在事务内");
        assertTrue(mapper.contains("int claimPaymentSuccess("), "Mapper 必须暴露条件置位 CAS");
        assertTrue(xml.contains("<update id=\"claimPaymentSuccess\">"), "MyBatis XML 必须实现支付 CAS");
        assertTrue(xml.contains("payment_status in (0, 1)"), "CAS 只能从待支付/支付中过渡");
        assertTrue(claim > method && settlement > claim && reward > claim,
                "结算和积分副作用必须发生在取得唯一处理权之后");
        assertTrue(service.contains("boolean paymentTransitioned = cmsRegistrationMapper.claimPaymentSuccess"),
                "支付成功必须显式记录本次是否取得状态迁移权");
        assertTrue(service.contains("if (paymentTransitioned) {\n            registerPaymentAfterCommit"),
                "重复回调不得再次注册报名成功通知和二维码副作用");
        assertFalse(service.contains("System.out.println(resultMap)"), "禁止记录原始微信回调");
        assertFalse(service.contains("System.out.println(registration)"), "禁止记录包含 PII 的报名对象");
    }
}
