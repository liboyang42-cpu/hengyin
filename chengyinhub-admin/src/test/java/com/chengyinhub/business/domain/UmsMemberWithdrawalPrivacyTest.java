package com.chengyinhub.business.domain;

import com.chengyinhub.common.annotation.Excel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;

class UmsMemberWithdrawalPrivacyTest {

    @Test
    void toStringDoesNotContainSensitiveWithdrawalData() {
        UmsMemberWithdrawal withdrawal = new UmsMemberWithdrawal();
        withdrawal.setRealname("张三");
        withdrawal.setBankAccount("6222021234567890");
        withdrawal.setMobilephone("13800138000");

        String output = withdrawal.toString();

        assertFalse(output.contains("张三"));
        assertFalse(output.contains("6222021234567890"));
        assertFalse(output.contains("13800138000"));
    }

    @Test
    void sensitiveWithdrawalFieldsAreNotExportableByDefault() throws NoSuchFieldException {
        for (String name : new String[]{"realname", "bankName", "bankAccount", "mobilephone"}) {
            Field field = UmsMemberWithdrawal.class.getDeclaredField(name);
            assertFalse(field.isAnnotationPresent(Excel.class), name + " must not be exported by default");
        }
    }
}
