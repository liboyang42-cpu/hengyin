package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * player_object_card 的三张表对齐(建表 SQL ↔ Mapper XML ↔ 发布侧列宽)。
 *
 * <p>「同 member + 同 origin 重复铸卡只覆盖、不新增行」这条契约,真身在 MySQL 的
 * {@code on duplicate key} 上 —— 它由谁触发完全取决于建表时那条 UNIQUE KEY 的列顺序。
 * 单测没有 DB,所以这里不演行为,改钉**形状**:XML 里的 upsert 与 DDL 的唯一键必须是同一组列,
 * 少一列或换一列都让覆盖语义静默失效(变成每次插一行,或撞在错误的键上)。
 *
 * <p>另外两条同样静默:列宽要与发布侧的 64 字上限一致(不一致时 MySQL 只发 warning 就截断),
 * 以及每条读与改都必须带 member_id(不带就是任何人都能读别人的藏品)。
 */
class PlayerObjectCardSchemaContractTest {

    private static final String MIGRATION = "sql/migration_player_object_card_20260922.sql";
    private static final String MAPPER_XML =
            "../chengyinhub-system/src/main/resources/mapper/business/PlayerObjectCardMapper.xml";
    private static final String VALIDATOR_SOURCE =
            "../chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/AdvancedGameConfigValidator.java";

    @Test
    void 唯一键是归属加来源_upsert打的也是同一条键() throws IOException {
        String ddl = read(MIGRATION);
        String xml = read(MAPPER_XML);

        Set<String> uniqueColumns = firstParenColumns(ddl, "unique key uk_member_card_origin");
        assertEquals(new LinkedHashSet<>(java.util.Arrays.asList("member_id", "origin_type", "origin_id")),
                uniqueColumns, "覆盖语义钉在这一组列上:" + uniqueColumns);

        assertTrue(xml.toLowerCase().contains("on duplicate key update"),
                "铸卡必须是一条 upsert —— 先查再插的写法在并发重拍下会多出一行");
        Matcher insert = Pattern.compile("insert into player_object_card \\(([^)]*)\\)", Pattern.CASE_INSENSITIVE)
                .matcher(xml);
        assertTrue(insert.find(), "找不到 upsert 的列表:" + xml);
        Set<String> insertColumns = splitColumns(insert.group(1));
        assertTrue(insertColumns.containsAll(uniqueColumns),
                "唯一键的列必须都在插入列表里,否则 duplicate key 永不命中:" + insertColumns);
    }

    @Test
    void 回看索引按归属加时间() throws IOException {
        Set<String> index = firstParenColumns(read(MIGRATION), "key idx_card_member_time");
        assertEquals(new LinkedHashSet<>(java.util.Arrays.asList("member_id", "create_time")), index);
    }

    /** 施工文档 §5(第四版):3D 生成字段 —— 铸卡一律落 NONE / 0 帧,生成字段全部可空。 */
    @Test
    void 生成字段有默认且可空() throws IOException {
        String ddl = read(MIGRATION);

        assertTrue(ddl.contains("source_url varchar(512) not null"), ddl);
        assertTrue(ddl.contains("gen_status varchar(16) not null default 'none'"), ddl);
        assertTrue(ddl.contains("frame_count tinyint not null default 0"), ddl);
        for (String nullable : new String[] { "gen_provider varchar(16) null", "gen_task_id varchar(128) null",
                "gen_error varchar(64) null", "gen_queued_at datetime null", "model_url varchar(512) null",
                "frames_prefix varchar(512) null" }) {
            assertTrue(ddl.contains(nullable), "缺列或类型不对:" + nullable);
        }
    }

    /**
     * 抠图贴纸与 AI 分类(第四版重新加回,§2.1 / §2.4)。两列都可空且空是常态:
     * 抠不出、认不出都照常用原照片铸卡,所以库不能用 NOT NULL 把 fail-open 变成铸卡失败。
     * 512 与 source_url 同宽 —— 窄了会在 MySQL 静默截断成一张打不开的图。
     */
    @Test
    void 抠图与分类列可空且宽度够用() throws IOException {
        String ddl = read(MIGRATION);

        assertTrue(ddl.contains("cutout_url varchar(512) null"), "缺列或类型不对:cutout_url varchar(512) null");
        assertTrue(ddl.contains("category varchar(16) null"), "缺列或类型不对:category varchar(16) null");
        // 物品在原照片里的位置,四个比例值「x,y,w,h」各带四位小数:最长 27 字
        assertTrue(ddl.contains("cutout_box varchar(32) null"), "缺列或类型不对:cutout_box varchar(32) null");
    }

    /** 同一个服务商任务只能落到一张卡上:回调或轮询串单时由库兜住,而不是静默写进别人的卡。 */
    @Test
    void 服务商任务号唯一() throws IOException {
        assertEquals(new LinkedHashSet<>(java.util.Arrays.asList("gen_provider", "gen_task_id")),
                firstParenColumns(read(MIGRATION), "unique key uk_card_gen_task"));
    }

    /** 生成任务按状态取队列、额度按排队时间计数,两件事走同一条索引。 */
    @Test
    void 生成队列索引按状态加排队时间() throws IOException {
        assertEquals(new LinkedHashSet<>(java.util.Arrays.asList("gen_status", "gen_queued_at")),
                firstParenColumns(read(MIGRATION), "key idx_card_gen"));
    }

    /** 回包要出帧清单,查询就得把生成状态与帧数据带出来。 */
    @Test
    void 查询带出生成状态与帧数据() throws IOException {
        String xml = read(MAPPER_XML);
        Matcher select = Pattern.compile("<sql id=\"selectplayerobjectcardvo\">(.*?)</sql>", Pattern.DOTALL)
                .matcher(xml);
        assertTrue(select.find(), "找不到公共查询片段:" + xml);
        for (String column : new String[] { "gen_status", "frames_prefix", "frame_count" }) {
            assertTrue(select.group(1).contains(column), "查询没带 " + column);
        }
    }

    /**
     * 卡面三档(§2.1)与藏品册的分类筛选(§3.3)都读这两列:公共查询与 resultMap 少一处,
     * 实体属性就静默为 null —— 铸进库的抠图在回包里凭空消失,单测不钉这一步没人会发现。
     */
    @Test
    void 查询与映射都带出抠图和分类() throws IOException {
        String xml = read(MAPPER_XML);

        Matcher select = Pattern.compile("<sql id=\"selectplayerobjectcardvo\">(.*?)</sql>", Pattern.DOTALL)
                .matcher(xml);
        assertTrue(select.find(), "找不到公共查询片段:" + xml);
        for (String column : new String[] { "cutout_url", "cutout_box", "category" }) {
            assertTrue(select.group(1).contains(column), "查询没带 " + column);
        }

        Matcher resultMap = Pattern.compile("<resultmap[^>]*>(.*?)</resultmap>", Pattern.DOTALL).matcher(xml);
        assertTrue(resultMap.find(), "找不到 resultMap:" + xml);
        assertTrue(resultMap.group(1).contains("column=\"cutout_url\""), "resultMap 没映射 cutout_url");
        assertTrue(resultMap.group(1).contains("column=\"category\""), "resultMap 没映射 category");
        assertTrue(resultMap.group(1).contains("column=\"cutout_box\""), "resultMap 没映射 cutout_box");
    }

    /**
     * 同一节点重拍会覆盖这一行,照片换了,旧 3D 就不再是这张照片的 —— 覆盖时生成状态、帧与服务商
     * 任务号必须一并清掉。漏一列,玩家就会看到新照片配着旧物件在转。
     */
    @Test
    void 重拍覆盖时生成结果一并重置() throws IOException {
        String xml = read(MAPPER_XML);
        int at = xml.indexOf("on duplicate key update");
        assertTrue(at > 0, xml);
        String updates = xml.substring(at, xml.indexOf("</insert>", at));
        for (String reset : new String[] { "gen_status = values(gen_status)", "frames_prefix = values(frames_prefix)",
                "frame_count = values(frame_count)", "model_url = null", "gen_task_id = null", "gen_provider = null",
                "gen_error = null", "gen_queued_at = null" }) {
            assertTrue(updates.contains(reset), "重拍覆盖漏了:" + reset);
        }
    }

    /**
     * 同一节点重拍换了照片,抠图与分类是上一件东西的结果 —— 不跟着覆盖,卡面就会是新照片
     * 配着旧物件的贴纸。两者都要在 upsert 的插入列表里(否则 values() 无源)并在覆盖列表里带过新值。
     */
    @Test
    void 重拍覆盖时抠图与分类跟着新照片更新() throws IOException {
        String xml = read(MAPPER_XML);

        Matcher insert = Pattern.compile("insert into player_object_card \\(([^)]*)\\)", Pattern.CASE_INSENSITIVE)
                .matcher(xml);
        assertTrue(insert.find(), "找不到 upsert 的列表:" + xml);
        Set<String> insertColumns = splitColumns(insert.group(1));
        assertTrue(insertColumns.containsAll(java.util.Arrays.asList("cutout_url", "category")),
                "抠图与分类没进插入列表:" + insertColumns);

        int at = xml.indexOf("on duplicate key update");
        assertTrue(at > 0, xml);
        String updates = xml.substring(at, xml.indexOf("</insert>", at));
        for (String overwrite : new String[] { "cutout_url = values(cutout_url)", "cutout_box = values(cutout_box)",
                "category = values(category)" }) {
            assertTrue(updates.contains(overwrite), "重拍覆盖漏了:" + overwrite);
        }
    }

    /** 发布侧限 64 字,库里也得是 64:窄了白截,宽了等于没限。 */
    @Test
    void 卡名列宽与发布侧上限一致() throws IOException {
        String ddl = read(MIGRATION);
        Matcher validator = Pattern.compile("MAX_CARD_TITLE = (\\d+)", Pattern.CASE_INSENSITIVE)
                .matcher(read(VALIDATOR_SOURCE));
        assertTrue(validator.find(), "发布侧的卡名上限常量不见了:" + VALIDATOR_SOURCE);
        int publishLimit = Integer.parseInt(validator.group(1));

        Matcher column = Pattern.compile("title varchar\\((\\d+)\\)", Pattern.CASE_INSENSITIVE).matcher(ddl.toLowerCase());
        assertTrue(column.find(), "库里没有 title 列:" + ddl);
        assertEquals(publishLimit, Integer.parseInt(column.group(1)));
    }

    /** 读与改一律带 owner:少一条 WHERE 就是跨用户。 */
    @Test
    void 每条读写都带归属条件() throws IOException {
        String xml = read(MAPPER_XML);

        int statements = 0;
        Matcher matcher = Pattern.compile("<(select|update|insert)[^>]*id=\"([^\"]+)\"[^>]*>(.*?)</\\1>",
                Pattern.DOTALL).matcher(xml);
        while (matcher.find()) {
            statements++;
            String body = matcher.group(3).toLowerCase();
            assertTrue(body.contains("member_id"),
                    matcher.group(2) + " 没带 member_id:跨用户读写就藏在这种语句里");
        }
        assertEquals(5, statements, "语句数与 mapper 接口对不上,新增方法忘了钉归属条件:");
    }

    @Test
    void 建表是幂等的且不动别人的表() throws IOException {
        String ddl = read(MIGRATION).toLowerCase();

        assertTrue(ddl.contains("create table if not exists player_object_card"), ddl);
        assertEquals(1, count(ddl, "create table"), "一个迁移只建一张表");
        assertTrue(ddl.contains("engine=innodb") || ddl.contains("engine = innodb"), ddl);
        assertTrue(ddl.contains("utf8mb4"), ddl);
    }

    // ==================== 脚手架 ====================

    private static Set<String> firstParenColumns(String source, String clause) {
        String lowered = source.toLowerCase();
        int at = lowered.indexOf(clause);
        assertTrue(at >= 0, "找不到 " + clause + ":");
        int open = lowered.indexOf('(', at);
        int close = lowered.indexOf(')', open);
        assertTrue(open > 0 && close > open, clause + " 后面没有列清单");
        return splitColumns(source.substring(open + 1, close));
    }

    private static Set<String> splitColumns(String raw) {
        Set<String> columns = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                columns.add(trimmed);
            }
        }
        return columns;
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return n;
            }
            n++;
            from = at + needle.length();
        }
    }

    /** surefire 的 cwd 是模块目录;从仓库根跑时退一层。与仓内其它读源码的用例同一口径。 */
    /**
     * 藏品册列表(§3.3):分类筛选与计数必须同一个条件,否则「共 N 件」和筛出来的对不上;
     * 两条都得带 member_id,不带就是谁都能翻别人的册子。地点 = 主题名 · 节点名,来自玩法节点。
     */
    @Test
    void 列表与计数按同一个分类条件筛_都带归属_列表带出地点() throws IOException {
        String xml = read(MAPPER_XML);
        String list = block(xml, "<select id=\"selectbymember\"");
        String count = block(xml, "<select id=\"countbymember\"");

        for (String sql : new String[] { list, count }) {
            assertTrue(sql.contains("c.member_id = #{memberid}"), "少了归属条件:" + sql);
            assertTrue(sql.contains("<if test=\"category != null\">and c.category = #{category}</if>"),
                    "分类条件不一致:" + sql);
        }
        assertTrue(list.contains("left join cms_topic_node n"), "地点要从玩法节点来:" + list);
        assertTrue(list.contains("left join cms_topic t on t.id = n.topic_id"), list);
        assertTrue(list.contains("as place"), list);
        assertTrue(xml.contains("<result property=\"place\" column=\"place\" />"), "resultMap 没映射 place");
    }

    private static String block(String xml, String start) {
        int from = xml.indexOf(start);
        assertTrue(from >= 0, "找不到 " + start);
        return xml.substring(from, xml.indexOf("</select>", from)).replaceAll("\\s+", " ");
    }

    private static String read(String relativeToModule) throws IOException {
        Path path = Paths.get(relativeToModule);
        if (!Files.exists(path)) {
            path = Paths.get("chengyinhub-admin").resolve(relativeToModule);
        }
        assertTrue(Files.exists(path), "文件不在:" + path.toAbsolutePath());
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertNotNull(text);
        // 列对齐的空格与反引号是排版,不是契约:压成单空格后按语义比对。
        return text.toLowerCase().replace("`", "").replaceAll("[ \\t]+", " ");
    }
}
