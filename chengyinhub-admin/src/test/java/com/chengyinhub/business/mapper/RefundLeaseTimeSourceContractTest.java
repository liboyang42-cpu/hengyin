package com.chengyinhub.business.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 退款租约的时间来源契约:凡是**会被拿去跟数据库 now() 比较**的时间列,它的值也必须由
 * 数据库自己生成,绝不能从 JVM 侧算好 Date 再传进来。
 *
 * 为什么需要这道结构门禁,而不是行为测试:
 * 两种写法只有在「JVM 时区 ≠ MySQL 服务器时区」时才表现不同,而测试环境(以及生产)通常
 * 两侧一致 —— 一致时它们完全等价,任何行为测试都测不出差别。2026-08-01 的事故正是这样
 * 躺过来的:self-hosted runner 那台机器时区变了,派发租约的 30 分钟窗口瞬间失效,
 * 两个并发 worker 各调了一次微信退款。等到能靠行为测出来时,钱已经出去了。
 * 所以这里锁的是**写法**:让「跨时钟比较」这种形状根本无法回到代码里。
 *
 * 负控:把 NOW(3) + INTERVAL #{leaseMinutes} MINUTE 改回 #{leaseExpiresAt},本测试立刻红。
 */
class RefundLeaseTimeSourceContractTest {

    /** 这些列会在 WHERE 里跟 now() 比,故其赋值必须来自 DB 侧表达式。 */
    private static final List<String> TIME_COLUMNS_COMPARED_TO_NOW =
            List.of("payout_lease_expires_at");

    private static final Pattern JVM_PARAM = Pattern.compile("^#\\{[^}]+\\}$");

    @Test
    void 与now比较的时间列不得由JVM侧传入() throws IOException {
        Path xml = locateMapperXml();
        String content = Files.readString(xml, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        for (String column : TIME_COLUMNS_COMPARED_TO_NOW) {
            // 只看赋值(set 子句)形态:`列 = <表达式>,` 或 `列 = <表达式>\n`
            Matcher m = Pattern.compile(column + "\\s*=\\s*([^,\\n]+)").matcher(content);
            while (m.find()) {
                String rhs = m.group(1).trim();
                if (JVM_PARAM.matcher(rhs).matches()) {
                    violations.add(column + " = " + rhs);
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("以下时间列由 JVM 侧传入,却会在 SQL 里跟 now() 比较 —— 一旦 JVM 与 MySQL 时区"
                    + "不一致,租约窗口即刻失效(重复打款/合法处理人被锁死)。"
                    + "改为 NOW(3) ± INTERVAL #{分钟数} MINUTE,只传时长、不传时刻:"
                    + String.join("; ", violations));
        }
    }

    @Test
    void 门禁本身指向真实文件且列名确实存在() throws IOException {
        String content = Files.readString(locateMapperXml(), StandardCharsets.UTF_8);
        // 防止「文件路径写错 ⇒ 扫了个空 ⇒ 恒绿」这种最典型的假绿。
        for (String column : TIME_COLUMNS_COMPARED_TO_NOW) {
            assertTrue(content.contains(column),
                    "列 " + column + " 在 mapper 里找不到,门禁扫错了对象(恒绿即无效)");
            assertTrue(content.contains(column + " &lt; now()")
                            || content.contains(column + " >= now()"),
                    "列 " + column + " 已不再与 now() 比较?请重新确认本门禁是否还适用");
        }
    }

    private Path locateMapperXml() {
        // 从 admin 模块的工作目录回到仓库根,再进 system 模块。
        Path[] candidates = {
            Paths.get("../chengyinhub-system/src/main/resources/mapper/business/RefundApplicationMapper.xml"),
            Paths.get("chengyinhub-system/src/main/resources/mapper/business/RefundApplicationMapper.xml"),
        };
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException("找不到 RefundApplicationMapper.xml —— 门禁无法执行,不能静默通过");
    }
}
