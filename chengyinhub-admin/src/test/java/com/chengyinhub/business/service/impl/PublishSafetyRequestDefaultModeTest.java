package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AUDIT-INT-10(#376):PublishSafetyRequest.reviewMode 的初值必须与 javadoc/门禁的安全默认一致 ——
 * 未设置 = PRE_REVIEW(最严一档)。此前字段初值是 POST_REVIEW,忘了传模式的新调用方会静默 fail-open。
 * 现有调用方全部显式传模式,行为不变;下面的源码契约钉住「每个 new PublishSafetyRequest() 都显式 setReviewMode」。
 */
class PublishSafetyRequestDefaultModeTest {

    @Test
    void unsetReviewModeIsPreReview() {
        assertEquals(PublishSafetyRequest.ReviewMode.PRE_REVIEW.name(), new PublishSafetyRequest().getReviewMode());
    }

    @Test
    void gateHoldsAReviewRequiredRequestThatNeverSetAMode() {
        IWxContentSecurityService wxSec = mock(IWxContentSecurityService.class);
        ContentSafetyGateImpl gate = new ContentSafetyGateImpl();
        ReflectionTestUtils.setField(gate, "wxContentSecurityService", wxSec);
        ReflectionTestUtils.setField(gate, "auditTaskService", mock(IAuditTaskService.class));
        when(wxSec.isEnabled()).thenReturn(true);
        when(wxSec.checkText(anyString(), anyString())).thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);
        PublishSafetyRequest r = new PublishSafetyRequest();
        r.setContentType("SQUARE");
        r.setContentId(1L);
        r.setMemberId(100L);
        r.setOpenid("openid-x");
        r.setText("疑似风险内容");

        SafetyDecision d = gate.decide(r);

        assertTrue(d.isHold(), "未设置模式必须按先审后发 HOLD,不能打标放行");
    }

    @Test
    void everyProductionCallerSetsReviewModeExplicitly() throws IOException {
        Path root = Paths.get("..").toAbsolutePath().normalize();
        List<String> missing = new ArrayList<>();
        int callers = 0;
        for (String module : new String[] {"chengyinhub-system", "chengyinhub-admin"}) {
            Path src = root.resolve(module).resolve("src/main/java");
            try (Stream<Path> files = Files.walk(src)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (!lines.get(i).contains("new PublishSafetyRequest()")) continue;
                        callers++;
                        boolean set = false;
                        for (int j = i; j < Math.min(lines.size(), i + 25) && !set; j++) {
                            set = lines.get(j).contains(".setReviewMode(");
                        }
                        if (!set) missing.add(root.relativize(file) + ":" + (i + 1));
                    }
                }
            }
        }
        assertTrue(callers >= 9, "门禁调用方数量异常:" + callers);
        assertTrue(missing.isEmpty(), "以下门禁调用方没有显式 setReviewMode:" + missing);
    }
}
