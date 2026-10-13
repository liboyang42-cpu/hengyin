package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.NpcChatLog;
import com.chengyinhub.business.domain.vo.ai.NpcChatDataExportVO;
import com.chengyinhub.business.mapper.NpcChatLogMapper;
import com.chengyinhub.business.service.impl.NpcChatDataService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * NPC 聊天数据导出/删除的入口行为测试(CI 常跑,mock mapper):
 * <ul>
 *   <li>身份只来自登录态:mapper 收到的 userId 必须 == getAppUserId() 返回值,别无其他来源。
 *       端点签名无任何用户 ID 入参,越权面结构性不存在;这里锁死的是传参链路本身
 *       (若有人把 getAppUserId() 换成常量或请求参数,这里转红)。</li>
 *   <li>数据权利不挂 chat 开关:npcFeatureFlags 故意不注入,端点若触碰 chatOn() 必 NPE 转红。</li>
 *   <li>用户主动删除只走 deleteNpcChatLogsByUserId(按归属),绝不触发 deleteNpcChatLogsBefore(到期批量清理)。</li>
 * </ul>
 * SQL 真语义(A 的登录态碰不到 B 的行)由 {@code NpcChatUserDataMyBatisIT} 连真库坐实。
 */
class ApiAiNpcChatDataTest {

    private static final long LOGIN_USER_ID = 42L;

    private NpcChatLogMapper mapper;
    private ApiAiNpcController controller;

    @BeforeEach
    void setUp() {
        mapper = mock(NpcChatLogMapper.class);
        NpcChatDataService svc = new NpcChatDataService();
        ReflectionTestUtils.setField(svc, "npcChatLogMapper", mapper);
        ReflectionTestUtils.setField(svc, "npcChatRetentionDays", 90);
        controller = spy(new ApiAiNpcController());
        ReflectionTestUtils.setField(controller, "npcChatDataService", svc);
        doReturn(LOGIN_USER_ID).when(controller).getAppUserId();
        // 故意不注入 npcFeatureFlags:数据权利端点一旦挂上开关闸,这里立即 NPE 转红。
    }

    @Test
    void export_onlyLoginUserIdReachesMapper_andChatGateUntouched() {
        NpcChatLog row = new NpcChatLog();
        row.setUserId(LOGIN_USER_ID);
        row.setUserMsg("我的消息");
        when(mapper.selectNpcChatLogsByUserId(LOGIN_USER_ID)).thenReturn(Collections.singletonList(row));

        AjaxResult result = controller.exportChatData();

        NpcChatDataExportVO vo = (NpcChatDataExportVO) result.get("data");
        assertEquals(1, vo.getRecords().size(), "导出必须是登录用户自己的记录");
        assertEquals("我的消息", vo.getRecords().get(0).getUserMsg());
        assertEquals(Integer.valueOf(90), vo.getRetentionDays(), "必须向用户回显真实配置的留存天数");
        // 锁死传参链路:mapper 只被以登录态 userId 查过这一次,没有任何其他查询/删除
        verify(mapper).selectNpcChatLogsByUserId(LOGIN_USER_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void delete_onlyLoginUserIdReachesMapper_neverTouchesRetentionBulkDelete() {
        when(mapper.deleteNpcChatLogsByUserId(LOGIN_USER_ID)).thenReturn(3);

        AjaxResult result = controller.deleteChatData();

        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertEquals(3, data.get("deleted"), "响应必须回报真实删除行数");
        verify(mapper).deleteNpcChatLogsByUserId(LOGIN_USER_ID);
        // 用户主动删除绝不允许走全表到期清理语义
        verify(mapper, never()).deleteNpcChatLogsBefore(any(), anyInt());
        verifyNoMoreInteractions(mapper);
    }
}
