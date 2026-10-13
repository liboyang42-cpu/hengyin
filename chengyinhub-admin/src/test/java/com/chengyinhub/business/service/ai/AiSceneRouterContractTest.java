package com.chengyinhub.business.service.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * AiSceneRouter 路由表契约:AiCreatorServiceImpl 实际使用的每个 bizType 都必须注册到 ROUTES。
 *
 * 为什么要这个测试(真实事故):
 * chapter_overview 自 2026-06-28 建成起就没进 ROUTES,resolve() 的 getOrDefault 把它兜底成 "mock",
 * 而 mockLlmClient 这个 bean 名真实存在 → AiGatewayService 的 bean 名查找直接命中并返回,
 * 连它自己那句 log.warn("unknown provider") 都够不着。结果:生产环境 /api/ai/chapter/overview
 * 返回 MockLlmClient 写死的假文案,HTTP 200 + 真 traceId + 像模像样的中文,零告警,三周无人发现。
 *
 * 这里刻意**从源码里抠 bizType**而不是硬编码一份清单 —— 硬编码只能证明「今天这几个是对的」,
 * 挡不住下一个人加了第 8 个 bizType 却忘了注册路由(那正是本次事故的成因)。
 *
 * @author chengyinhub
 */
public class AiSceneRouterContractTest
{
    /** AiCreatorServiceImpl 源码位置(跨模块反向读;找不到时本测试必须失败,见下方自检)。 */
    private static final String CREATOR_SRC =
        "../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AiCreatorServiceImpl.java";

    /** 匹配 gateway.generate(memberId, role, "xxx", ...) 里的第三个实参 */
    private static final Pattern BIZ_TYPE =
        Pattern.compile("gateway\\.generate\\s*\\([^,]+,[^,]+,\\s*\"([a-z_]+)\"");

    private Set<String> bizTypesUsedByCreator() throws IOException
    {
        Path p = Paths.get(CREATOR_SRC);
        // ★ 自检:读不到源码就必须炸,不许安静地跑出「0 个 bizType 全都合格」的假绿。
        assertTrue(Files.exists(p),
            "读不到 AiCreatorServiceImpl 源码:" + p.toAbsolutePath()
            + " —— 路径不存在 = 无法判定,不是「没问题」。本测试跨模块反向读源码,请确认工作目录与模块布局。");

        String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        Set<String> types = new LinkedHashSet<>();
        Matcher m = BIZ_TYPE.matcher(src);
        while (m.find())
        {
            types.add(m.group(1));
        }
        // ★ 自检第二层:正则失效(比如有人重构了 gateway.generate 的签名)会让这里为空 →
        // 循环零次 → 测试恒绿。那同样是假绿,宁可红着让人来修正则。
        assertFalse(types.isEmpty(),
            "从 AiCreatorServiceImpl 里一个 bizType 都没抠出来 —— 几乎必然是本测试的正则跟不上源码了"
            + "(gateway.generate 签名变了?),而不是真的没有。正则失效时本测试对任何漏注册都恒绿,故主动失败。");
        return types;
    }

    @Test
    public void everyCreatorBizTypeMustBeRoutedToRealModelNotMock() throws IOException
    {
        AiSceneRouter router = new AiSceneRouter();
        for (String bizType : bizTypesUsedByCreator())
        {
            String provider = router.resolve(bizType);
            assertNotEquals("mock", provider,
                "bizType=" + bizType + " 没注册进 AiSceneRouter.ROUTES,被兜底成 mock。"
                + "后果:该场景在生产返回 MockLlmClient 的写死假文案(HTTP 200 + 真 traceId + 零告警),"
                + "用户会把假 AI 内容当真发布。修法:在 AiSceneRouter 的静态块里补 m.put(\"" + bizType + "\", \"deepseek\")。");
        }
    }

    @Test
    public void unknownBizTypeStillFallsBackToMock()
    {
        // 兜底行为本身要保留(未知场景不该抛异常炸掉调用方),这里钉住它,
        // 免得上一条测试被人「修」成让 resolve 直接抛错。
        AiSceneRouter router = new AiSceneRouter();
        assertNotEquals(null, router.resolve("definitely_not_a_registered_biz_type"));
    }
}
