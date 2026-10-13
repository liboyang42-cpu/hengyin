package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 2026-09-16 提现闸:后台通用会员编辑不得写余额。超管(能看 PII,不走 stripPii)提交 balance=5000,
 * 以前原样落库 = 5000 元当场可提现、没有流水、不经结算退款截止闸。
 */
class UmsMemberControllerBalanceWriteTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void superAdminEditCannotWriteBalance() {
        IUmsMemberService service = mock(IUmsMemberService.class);
        UmsMemberController controller = controller(service);
        UmsMember body = new UmsMember();
        body.setId(9L);
        body.setStatus(1L);
        body.setBalance(new BigDecimal("5000.00"));

        controller.edit(body);

        ArgumentCaptor<UmsMember> written = ArgumentCaptor.forClass(UmsMember.class);
        verify(service).updateUmsMember(written.capture());
        assertNull(written.getValue().getBalance(), "通用编辑写入的余额没有流水,不能落库");
    }

    @Test
    void addCannotSeedBalance() {
        IUmsMemberService service = mock(IUmsMemberService.class);
        UmsMember body = new UmsMember();
        body.setBalance(new BigDecimal("5000.00"));

        controller(service).add(body);

        ArgumentCaptor<UmsMember> written = ArgumentCaptor.forClass(UmsMember.class);
        verify(service).insertUmsMember(written.capture());
        assertNull(written.getValue().getBalance());
    }

    private static UmsMemberController controller(IUmsMemberService service) {
        SysUser user = new SysUser();
        user.setUserId(1L);
        LoginUser loginUser = new LoginUser(user, Collections.singleton(Constants.ALL_PERMISSION));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
        UmsMemberController controller = new UmsMemberController();
        ReflectionTestUtils.setField(controller, "umsMemberService", service);
        return controller;
    }
}
