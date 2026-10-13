package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IImService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附近的局群聊契约:
 *  1. 接口三方法存在;
 *  2. 群访问校验 hasActiveGroupAccess 有 hangout_ 分支 —— 没有的话局群会被当成「俱乐部已删」拒发消息;
 *  3. closeHangoutGroup 与 closeClubGroup 同样先拿会话行锁。
 * 负控:删掉 hasActiveGroupAccess 里的 hangoutIdOfBizKey 分支 → 第二条红。
 */
class ImHangoutGroupContractTest {

    private static String impl() throws Exception {
        Path p = Paths.get("../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ImServiceImpl.java");
        if (!Files.exists(p)) p = Paths.get("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ImServiceImpl.java");
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String method(String src, String signature) {
        int i = src.indexOf(signature);
        assertTrue(i >= 0, "找不到 " + signature);
        int next = src.indexOf("\n    @Override", i + 1);
        int nextPrivate = src.indexOf("\n    private ", i + 1);
        int end = Math.min(next < 0 ? src.length() : next, nextPrivate < 0 ? src.length() : nextPrivate);
        return src.substring(i, end);
    }

    @Test
    void 接口三方法齐全() throws Exception {
        assertNotNull(IImService.class.getMethod("getOrCreateHangoutGroup", Long.class, Long.class, Long.class));
        assertNotNull(IImService.class.getMethod("leaveHangoutGroup", Long.class, Long.class));
        assertNotNull(IImService.class.getMethod("closeHangoutGroup", Long.class));
    }

    @Test
    void 群访问校验必须认得局群() throws Exception {
        String body = method(impl(), "private boolean hasActiveGroupAccess(");
        assertTrue(body.contains("hangoutIdOfBizKey"), "hasActiveGroupAccess 缺 hangout_ 分支,局群会被按俱乐部口径拒掉");
        assertTrue(body.contains("selectStatusById"), "非锁路径每条消息都走一次,必须只取状态,不带成员计数子查询");
        assertTrue(body.contains("RoamHangout.STATUS_OPEN"), "局关了就不能再发");
    }

    @Test
    void 关群先拿行锁() throws Exception {
        String body = method(impl(), "public void closeHangoutGroup(");
        assertTrue(body.contains("selectImConversationByIdForUpdate"), "与 closeClubGroup 同:关闭前拿会话行锁");
        assertTrue(body.contains("markAllDeleted"));
    }

    @Test
    void 会话列表给局群起名() throws Exception {
        String body = method(impl(), "private Map<String, Object> counterpartyOf(");
        assertTrue(body.contains("hangoutIdOfBizKey"), "会话列表要把 hangout_ 群显示成局名而不是「群聊」");
    }
}
