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

        int method = service.indexOf("String handleWechatPayCallback");
        int claim = service.indexOf("claimPaymentSuccess", method);
        int settlement = service.indexOf("settlementEntryService.recordOnPayment", method);
        int reward = service.indexOf("umsMemberMapper.addPoints", method);

        assertTrue(method > 0, "支付回调必须保留在公开 Service interface");
        assertTrue(service.contains("wxPaymentCallbackInboxService.process(notifyId"), "真实回调必须领取inbox处理权");
        String mutation = source("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/WxPaymentCallbackMutationService.java");
        assertTrue(mutation.contains("@Transactional") && mutation.contains("mutation.apply(row)")
                && mutation.contains("mapper.markDone("), "业务变化与inbox DONE必须同事务");
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
