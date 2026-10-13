package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.util.Arrays;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;

/** requestId 与积分奖励必须由数据库业务键提供最终幂等。 */
class RegistrationBusinessKeyContractTest {

    @Test
    void rawRequestIdCreationMethodsAreHiddenBehindOneTransactionCollaborator() {
        long rawMethods = Arrays.stream(ICmsRegistrationService.class.getMethods())
                .map(Method::getName)
                .filter(name -> name.endsWith("WithRequestId"))
                .count();

        assertEquals(0, rawMethods);
        assertEquals(1, IRegistrationCreationTransaction.class.getDeclaredMethods().length);
        assertEquals("create", IRegistrationCreationTransaction.class.getDeclaredMethods()[0].getName());
    }

    @Test
    void orderBoundaryExposesOnlyValidatedCreateOrReplayAndSingleCreationTransaction() throws Exception {
        assertEquals(1, IRegistrationOrderService.class.getDeclaredMethods().length);
        assertNotNull(IRegistrationOrderService.class.getMethod("createOrReplay", Long.class,
                com.chengyinhub.business.domain.dto.RegistrationRequest.class));
        assertNotNull(IRegistrationCreationTransaction.class.getMethod("create", Long.class,
                com.chengyinhub.business.domain.dto.RegistrationRequest.class, String.class, String.class));
        assertNotNull(IRegistrationOrderCreateGate.class.getMethod("validate", Long.class,
                com.chengyinhub.business.domain.dto.RegistrationRequest.class));
    }

    @Test
    void rewardLedgerMapperRequiresStableBusinessKeyArguments() throws Exception {
        Method insert = UmsMemberPointsDetailMapper.class.getMethod(
                "insertUmsMemberPointsDetailWithBusinessKey",
                com.chengyinhub.business.domain.UmsMemberPointsDetail.class);
        assertEquals(int.class, insert.getReturnType());
        assertNotNull(UmsMemberPointsDetailMapper.class.getMethod("selectByRewardBusinessKey",
                Long.class, String.class));
    }
}
