package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ViewCreativeSquare;
import com.chengyinhub.business.mapper.NodeMedalClaimMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.service.IViewCreativeSquareService;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 节点名次奖牌抢占:并发裁判在 PRIMARY KEY (node_id, medal_rank),应用层只负责在阶梯上退让。
 *
 * <p>撞 1062 分两种,靠回读点查区分:回读有名次 = 自己早已持牌(幂等返回原名次);
 * 回读无 = 该档被别人占走,试下一档。非重复键异常必须穿透,不许伪装成「无牌」。
 */
class NodeMedalClaimTest {

    private static final Long NODE = 401L;
    private static final Long MEMBER = 88L;
    private static final Long ACTIVITY = 500L;
    private static final Long TOPIC = 300L;
    private static final Long PROGRESS = 9001L;

    private NodeMedalClaimMapper mapper;
    private ApiPlayProgressController controller;

    @BeforeEach
    void setUp() {
        mapper = mock(NodeMedalClaimMapper.class);
        controller = new ApiPlayProgressController();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMedalClaimMapper", mapper);
    }

    private Integer claim() {
        return PlayNodeAccessTestFixtures.<Integer>invokeMethod(controller, "claimNodeMedal", NODE, MEMBER, ACTIVITY, TOPIC, PROGRESS);
    }

    /** 正控:金牌被别人占走 ⇒ 退到银牌并真的插进去。 */
    @Test
    void goldTakenByAnother_fallsBackToSilver() {
        when(mapper.insertClaim(eq(NODE), eq(1), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("PRIMARY"));
        when(mapper.selectRankByNodeAndMember(NODE, MEMBER)).thenReturn(null);
        when(mapper.insertClaim(eq(NODE), eq(2), any(), any(), any(), any())).thenReturn(1);

        assertEquals(2, claim());
        verify(mapper).insertClaim(NODE, 2, MEMBER, ACTIVITY, TOPIC, PROGRESS);
        verify(mapper, never()).insertClaim(eq(NODE), eq(3), any(), any(), any(), any());
    }

    /** 幂等:同一人同一节点重复完成(换局重玩/重放)⇒ 返回原名次,绝不再抢第二枚。 */
    @Test
    void alreadyHoldsMedal_returnsSameRankAndStopsClaiming() {
        when(mapper.insertClaim(eq(NODE), eq(1), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_node_member"));
        when(mapper.selectRankByNodeAndMember(NODE, MEMBER)).thenReturn(1);

        assertEquals(1, claim());
        verify(mapper, never()).insertClaim(eq(NODE), eq(2), any(), any(), any(), any());
        verify(mapper, never()).insertClaim(eq(NODE), eq(3), any(), any(), any(), any());
    }

    /** 满档:三枚都被占 ⇒ 无牌(第四位起),且恰好只试三次。 */
    @Test
    void podiumFull_returnsNullAfterThreeAttempts() {
        when(mapper.insertClaim(eq(NODE), anyInt(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("PRIMARY"));
        when(mapper.selectRankByNodeAndMember(NODE, MEMBER)).thenReturn(null);

        assertNull(claim());
        verify(mapper, times(3)).insertClaim(eq(NODE), anyInt(), any(), any(), any(), any());
    }

    /** 负控:非重复键的写失败必须穿透到调用方整体回滚,不能被吞成「没抢到牌」。 */
    @Test
    void nonDuplicateFailure_propagates() {
        when(mapper.insertClaim(eq(NODE), eq(1), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("column truncated"));

        assertThrows(DataIntegrityViolationException.class, this::claim);
        verify(mapper, never()).selectRankByNodeAndMember(any(), any());
    }

    // ==================================================================================
    // 下面三组补的是「上面四条 mock 用例照样绿、功能却是坏的」那片盲区。
    //
    // 为什么非补不可:生产 player_node_progress 只有 1 行(全平台 1 个人完成过 1 个节点),
    // 这功能【没有生产数据可验】,线上也几乎不会自己冒出来。测试是唯一的探测器,
    // 所以每条断言都必须能被一个【已知错误的输入】弄红 —— 各用例的负控写在自己的注释里。
    //
    // A 组:真的走 claimNodeMedal,但底下换成一张「只认迁移那两把键」的内存表(FakeClaimTable),
    //       canned mock 编不出的涌现性质(唯一金牌 / 一人一牌 / 名次无洞)在这里才看得见。
    // B 组:A 组的键不是我编的 —— 从迁移文件正文(剥掉注释)里读出来核一遍。
    // C 组:feed 聚合口径,用真 MyBatis 渲染 SQL 断言,不连库。
    // ==================================================================================

    private static final Long OTHER = 99L;

    /**
     * 内存版 node_medal_claim,只认迁移里声明的两把键:
     * <ul>
     *   <li>PRIMARY KEY (node_id, medal_rank) —— 抢占闸本体,同一 node 的同一档位只能有一行;</li>
     *   <li>UNIQUE KEY uk_node_member (node_id, member_id) —— 一人一牌的兜底,回读失明时唯一的防线。</li>
     * </ul>
     * 三个开关就是负控用的「已知错误输入」:关掉任一把键、或让回读失明,依赖它的用例必须变红。
     */
    private static final class FakeClaimTable {
        /** {node_id, medal_rank, member_id};直接当行存,行数/名次分布都从这里读。 */
        private final List<long[]> rows = new ArrayList<long[]>();

        boolean enforcePk = true;
        boolean enforceMemberUnique = true;
        /** 回读失明:模拟实现类注释点名的 REPEATABLE_READ 坑(看不见刚提交的冲突行)。 */
        boolean blindReadback = false;

        int insert(long node, int rank, long member) {
            if (enforcePk && rankHolder(node, rank) != null) {
                throw new DuplicateKeyException("PRIMARY");
            }
            if (enforceMemberUnique && rankOf(node, member) != null) {
                throw new DuplicateKeyException("uk_node_member");
            }
            rows.add(new long[]{node, rank, member});
            return 1;
        }

        Long rankHolder(long node, int rank) {
            for (long[] r : rows) {
                if (r[0] == node && r[1] == rank) return Long.valueOf(r[2]);
            }
            return null;
        }

        Integer rankOf(long node, long member) {
            for (long[] r : rows) {
                if (r[0] == node && r[2] == member) return Integer.valueOf((int) r[1]);
            }
            return null;
        }

        /** 该 node 上已发出的名次,升序。名次无洞 = 这里必须是 1..k 的连续前缀。 */
        List<Integer> ranksOf(long node) {
            List<Integer> out = new ArrayList<Integer>();
            for (long[] r : rows) {
                if (r[0] == node) out.add(Integer.valueOf((int) r[1]));
            }
            Collections.sort(out);
            return out;
        }

        int rowCount(long node, long member) {
            int n = 0;
            for (long[] r : rows) {
                if (r[0] == node && r[2] == member) n++;
            }
            return n;
        }
    }

    /** 把假表接到 mapper mock 上;返回它自己,方便中途翻开关做负控。 */
    private FakeClaimTable wire(final FakeClaimTable table) {
        when(mapper.insertClaim(any(), anyInt(), any(), any(), any(), any())).thenAnswer(inv -> {
            Long node = inv.getArgument(0);
            Integer rank = inv.getArgument(1);
            Long member = inv.getArgument(2);
            return Integer.valueOf(table.insert(node.longValue(), rank.intValue(), member.longValue()));
        });
        when(mapper.selectRankByNodeAndMember(any(), any())).thenAnswer(inv -> {
            if (table.blindReadback) return null;
            Long node = inv.getArgument(0);
            Long member = inv.getArgument(1);
            return table.rankOf(node.longValue(), member.longValue());
        });
        return table;
    }

    private Integer claim(Long memberId, Long activityId) {
        return PlayNodeAccessTestFixtures.<Integer>invokeMethod(controller, "claimNodeMedal", NODE, memberId, activityId, TOPIC, PROGRESS);
    }

    // ---------------- A 组:走真阶梯,看涌现性质 ----------------

    /**
     * 【要覆盖 1】抢占唯一性:同一 node 的 rank=1 全平台只能有一个人拿到,第二个人退到银牌。
     *
     * <p>负控(2026-09-02 实测):{@code enforcePk = false}(=迁移里把 PK 换成自增 id,抢占闸不存在)
     * ⇒ OTHER 也插进 rank=1,第二条断言红(expected 2 but was 1)。
     */
    @Test
    void onlyOneGoldPerNode() {
        FakeClaimTable table = wire(new FakeClaimTable());

        assertEquals(Integer.valueOf(1), claim(MEMBER, 500L));
        assertEquals(Integer.valueOf(2), claim(OTHER, 500L));   // 金牌被占 ⇒ 退银,不是「无牌」
        assertEquals(MEMBER, table.rankHolder(NODE, 1));
        assertEquals(Arrays.asList(1, 2), table.ranksOf(NODE));
    }

    /**
     * 【要覆盖 2】一人一牌:同一人对同一 node 重复完成(换会话 / 自玩 activity_id=0)不能拿第二枚。
     *
     * <p>正常路径靠回读点查幂等返回原名次;这条专打【回读失明】那一半 ——
     * 实现类注释说 REPEATABLE_READ 会把「我其实持牌」误报成「无牌」,那时唯一的防线是
     * uk_node_member。player_node_progress 的唯一键含 activity_id,同一人换一局对同一 node
     * 可以再次「首次完成」,没有这道键他会先金后银把领奖台包圆。
     *
     * <p>负控(2026-09-02 实测):只把 {@code enforceMemberUnique = false}(=迁移里删掉 uk_node_member,
     * PK 保留)⇒ 第二次抢占拿到银牌,assertNull 红(expected null but was 2),
     * 且【只有本用例】红 —— 证明这条断言钉的正是 uk_node_member 那一把键,没有和 PK 混在一起。
     */
    @Test
    void oneMedalPerMember_evenWhenReadbackIsBlind() {
        FakeClaimTable table = wire(new FakeClaimTable());
        assertEquals(Integer.valueOf(1), claim(MEMBER, 500L));

        table.blindReadback = true;
        assertNull(claim(MEMBER, 501L));                        // 换一局重玩,拿不到第二枚
        assertEquals(1, table.rowCount(NODE, MEMBER));
        assertEquals(Arrays.asList(1), table.ranksOf(NODE));
    }

    /**
     * 【要覆盖 3】名次无洞:发出的名次必须是 1..k 的连续前缀,不能出现只有 1 和 3。
     *
     * <p>返回值序列本身就是探测器:阶梯若从 2 起步会得到 [2,3,null],若跨步 +2 会得到 [1,3,null],
     * 两种都红。第四个人起无牌,也不许把无牌写成 0 或 4。
     *
     * <p>负控(2026-09-02 实测):{@code enforcePk = false} ⇒ 四个人全拿金,
     * 返回从 [1,2,3] 变成 [1,1,1],第一条断言红。
     */
    @Test
    void ranksAreAGaplessPrefix() {
        FakeClaimTable table = wire(new FakeClaimTable());

        assertEquals(Arrays.asList(1, 2, 3),
                Arrays.asList(claim(101L, 500L), claim(102L, 500L), claim(103L, 500L)));
        assertNull(claim(104L, 500L));                          // 第四位起无牌
        assertEquals(Arrays.asList(1, 2, 3), table.ranksOf(NODE));
    }

    /**
     * 【要覆盖 4】抢占失败不拖垮打卡:三档满是最常见的失败态,claimNodeMedal 必须把 1062
     * 全部吞掉并返回 null,不许抛给外层 —— 外层是打卡的 @Transactional,异常出去 = 节点完成被整体回滚。
     *
     * <p>⚠️ 覆盖边界:非重复键异常(如迁移没落库 ⇒ Table doesn't exist)仍然穿透
     * (见 {@link #nonDuplicateFailure_propagates()}),那条路径上打卡【会】整体回滚。
     * 要让它也不影响打卡,得在调用点 ApiPlayProgressController:2151 外面包 try/catch,
     * 那是另一个 agent 的文件,本轮没测。
     *
     * <p>负控(2026-09-02 实测,两条各证一半):{@code enforcePk = false} 让第四个人也插得进去
     * ⇒ assertNull 红(expected null but was 1);把假表抛的 {@code DuplicateKeyException} 换成
     * {@code DataIntegrityViolationException}(=驱动没把 1062 翻译成 DuplicateKeyException,
     * mapper 没走 Spring 异常翻译时的真实故障态)⇒ 异常穿过阶梯逃逸,本用例 ERROR。
     */
    @Test
    void contentionNeverBreaksCheckin() {
        wire(new FakeClaimTable());
        claim(101L, 500L);
        claim(102L, 500L);
        claim(103L, 500L);                                      // 三档满

        assertNull(assertDoesNotThrow(() -> claim(MEMBER, 500L)));
    }

    // ---------------- B 组:A 组那两把键,得跟迁移正文对得上 ----------------

    /**
     * 【要覆盖 1 + 2 的另一半】A 组的假表按这两把键行事,前提是迁移真的建了它们。
     *
     * <p>必须先剥注释再断言:迁移头部的说明文字里原样写着 "PRIMARY KEY (node_id, medal_rank)"
     * 和 "uk_node_member (node_id, member_id)" —— 直接 contains 会命中注释,
     * 于是「DDL 里把键删了」照样绿,那是比没断言更危险的假绿。
     *
     * <p>负控(2026-09-02 实测):把迁移复制一份到 /tmp、删掉 DDL 里的 UNIQUE KEY 行,让本用例读那一份
     * ⇒ 第二条断言红;只删注释里的那句 ⇒ 仍然绿(证明锚点钉在 DDL 上,不是钉在注释上)。
     */
    @Test
    void migrationDeclaresBothKeys() throws Exception {
        String ddl = sqlWithoutComments(migrationSql());

        assertTrue(ddl.contains("primary key (node_id, medal_rank)"),
                "抢占闸不见了:PRIMARY KEY 必须是 (node_id, medal_rank)。实际 DDL=" + ddl);
        assertTrue(ddl.contains("unique key uk_node_member (node_id, member_id)"),
                "一人一牌的兜底不见了:uk_node_member (node_id, member_id)。实际 DDL=" + ddl);
    }

    /**
     * 【要覆盖 3 的另一半】名次无洞在回填侧的三道防线,少一道就会出洞:
     * mrn=1 去重(规则是「头三个人」不是「头三行」)、@claim_rows=0 空表守卫、以及无洞回读断言本身。
     *
     * <p>剥注释在这条上不是洁癖:文件末尾的负控说明里【原样写着】"WHERE f.mrn = 1",
     * 不剥就会命中注释 —— 回填里真把去重删了也照样绿。
     *
     * <p>负控(2026-09-02 实测):在 /tmp 的副本里删掉回填正文的 "WHERE f.mrn = 1"(保留注释里那句)
     * ⇒ 第一条红,证明锚点钉在会执行的 SQL 上;删掉 "AND claimed.node_id IS NULL" ⇒ 第二条红;
     * 删掉两句 HAVING/NOT EXISTS 回读 ⇒ 第三、第四条各自红。
     */
    @Test
    void migrationBackfillCannotPunchHoles() throws Exception {
        String ddl = sqlWithoutComments(migrationSql());

        assertTrue(ddl.contains("where f.mrn = 1"),
                "回填去重没了:同一人会被同时排成 rank1+rank2,第二条被 uk_node_member 挡下 ⇒ rank2 出洞");
        assertTrue(ddl.contains("and claimed.node_id is null"),
                "重跑守卫没了:落库后重跑会按另一套定序给已有牌的 node 补插 ⇒ 出洞");
        assertTrue(ddl.contains("not exists (select 1 from node_medal_claim c where c.node_id = p.node_id)"),
                "「该插却没插」的回读没了:回填整条没跑也会被判通过(空表上无洞断言恒真)= fail-open");
        assertTrue(ddl.contains("having max(medal_rank) <> count(*)"),
                "无洞回读断言没了:出洞也没人报");
    }

    /** 迁移正文,剥掉行注释后小写、去反引号、压空白 —— 断言只钉在真正会执行的 SQL 上。 */
    private static String sqlWithoutComments(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            int cut = line.indexOf("--");
            sb.append(cut >= 0 ? line.substring(0, cut) : line).append(' ');
        }
        return sb.toString().toLowerCase().replace("`", "").replaceAll("\\s+", " ");
    }

    private static String migrationSql() throws Exception {
        String name = "sql/migration_20260902_node_medal_claim.sql";
        File file = new File(name);
        if (!file.isFile()) file = new File("chengyinhub-admin/" + name);
        assertTrue(file.isFile(),
                "找不到迁移文件(它必须留在 chengyinhub-admin/sql/ 第一层,见 CI migration_layout 门禁);cwd="
                        + new File(".").getAbsolutePath());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    // ---------------- C 组:feed 聚合口径(真 MyBatis 渲染,不连库) ----------------

    /**
     * 【要覆盖 5】一条漫游帖只统计这一次漫游里拿到的牌。
     *
     * <p>会话帖(dataType=1)按 (member_id, activity_id) 取数:where 与 group by 两处都必须带
     * activity_id。任一处退化成只按 member_id,这个人在别的会话拿的牌就会串到这张卡上,
     * 而金银铜都是小数字、串了看起来仍然「像对的」—— 没有断言就永远发现不了。
     *
     * <p>空 pairs 必须渲染成 where 1 = 0:fail-closed,绝不能退化成全表聚合。
     *
     * <p>负控(2026-09-02 实测,三条各打一处):把 mapper XML 复制到 /tmp、只改副本、让本用例解析那一份 ——
     * group by 删掉 activity_id ⇒ 第二条红;where 元组删掉 activity_id ⇒ 第一条红;
     * &lt;otherwise&gt; 兜底删空(空 pairs 退化成全表聚合)⇒ 最后那条 where1=0 断言红。
     */
    @Test
    void feedMedalCounts_scopedToOneSession() throws Exception {
        MappedStatement statement = squareStatement("selectMedalCountsByMemberActivities");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("pairs", Arrays.asList(pair("activityId", 500L), pair("activityId", 501L)));

        BoundSql bound = statement.getBoundSql(params);
        String flat = bound.getSql().replaceAll("\\s+", "");
        assertTrue(flat.contains("(member_id,activity_id)in((?,?),(?,?))"), bound.getSql());
        assertTrue(flat.contains("groupbymember_id,activity_id"), bound.getSql());
        assertEquals(4, bound.getParameterMappings().size(), bound.getSql());

        BoundSql empty = statement.getBoundSql(emptyPairsAsProductionSendsThem());
        assertTrue(empty.getSql().replaceAll("\\s+", "").contains("where1=0"), empty.getSql());
        assertEquals(0, empty.getParameterMappings().size());
    }

    /**
     * 【要覆盖 5 的另一半】自玩帖走主题口径,除了 topic_id 还必须钉死 activity_id = 0。
     *
     * <p>node_medal_claim 给活动局夺的牌同样写了 topic_id,所以只按 (member_id, topic_id) 聚合时,
     * 一张免费自玩成绩帖会把作者在【收费活动会话】里夺的牌一起挂上来 —— 卡上显示的就不再是
     * 「这一次漫游拿到的牌」。金银铜都是小数字,串了看起来仍然「像对的」,没有断言永远发现不了。
     *
     * <p>负控(2026-09-02 实测):在 /tmp 的 XML 副本里删掉 "where activity_id = 0"
     * (otherwise 一并改回 where 1 = 0)⇒ 第一条断言红。
     * 另有一次非人造的实证:本断言最早写成 where1=0 版本,主题口径加上 activity_id = 0 那一刻
     * otherwise 从 "where 1 = 0" 变成 "and 1 = 0",本用例当场红 —— 口径一动就报。
     */
    @Test
    void feedMedalCounts_scopedToOneTopic() throws Exception {
        MappedStatement statement = squareStatement("selectMedalCountsByMemberTopics");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("pairs", Arrays.asList(pair("topicId", 300L), pair("topicId", 301L)));

        BoundSql bound = statement.getBoundSql(params);
        String flat = bound.getSql().replaceAll("\\s+", "");
        assertTrue(flat.contains("whereactivity_id=0"),
                "自玩帖没钉住会话边界,活动局夺的牌会串到自玩成绩帖上:" + bound.getSql());
        assertTrue(flat.contains("and(member_id,topic_id)in((?,?),(?,?))"), bound.getSql());
        assertTrue(flat.contains("groupbymember_id,topic_id"), bound.getSql());
        assertEquals(4, bound.getParameterMappings().size(), bound.getSql());

        BoundSql empty = statement.getBoundSql(emptyPairsAsProductionSendsThem());
        String emptyFlat = empty.getSql().replaceAll("\\s+", "");
        assertTrue(emptyFlat.contains("whereactivity_id=0and1=0"), empty.getSql());
        assertEquals(0, empty.getParameterMappings().size());
    }

    /**
     * 空入参必须按【生产真的传进来的形状】喂:activityPairs / topicPairs 是可能为空的 ArrayList
     * (整页只有自玩帖时 activityPairs 必空),不是「键不存在」。
     *
     * <p>两者落在 &lt;when test="pairs != null and pairs.size() &gt; 0"&gt; 的不同一半上:
     * 缺键 ⇒ OGNL 求值 null ⇒ 挡在 {@code pairs != null};空 ArrayList ⇒ 挡在 {@code size() > 0}。
     * 喂缺键就等于这条 fail-closed 断言没打在生产走的那条路上。
     * ViewCreativeSquareServiceImpl:142/147 是直接透传,中间没有空集守卫,所以空 List 真的会到 MyBatis。
     *
     * <p>「生产传的确实是这个形状」由 {@link #emptyPairsFromProductionStayFailClosed()} 从真 controller 捞出来对。
     */
    private static Map<String, Object> emptyPairsAsProductionSendsThem() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("pairs", new ArrayList<Map<String, Object>>());
        return params;
    }

    private static Map<String, Object> pair(String scopeKey, Long scopeValue) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("memberId", MEMBER);
        row.put(scopeKey, scopeValue);
        return row;
    }

    /** 只解析 XML 取 MappedStatement —— 不建 DataSource、不连库,渲染 SQL 用不着连接。 */
    private static MappedStatement squareStatement(String id) throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("ViewCreativeSquare", ViewCreativeSquare.class);
        String resource = "mapper/business/ViewCreativeSquareMapper.xml";
        InputStream in = Resources.getResourceAsStream(resource);
        try {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        } finally {
            in.close();
        }
        return cfg.getMappedStatement(
                "com.chengyinhub.business.mapper.ViewCreativeSquareMapper." + id);
    }

    // ==================================================================================
    // D 组:阶梯上两类写失败,方向【相反】,必须各走各的门。
    // E 组:牌数从 SQL 别名一路走到卡片上,中间三处隐式约定(名次→颜色、列别名→读取 key、
    //       空 pairs 的形状)全都没人守 —— 串了看起来仍然「像对的」。
    // ==================================================================================

    /**
     * 【网 1】死锁(1213)必须原样抛出,不许被吞成「没抢到牌」。
     *
     * <p>为什么零覆盖:{@link DeadlockLoserDataAccessException} 【继承自】
     * {@code TransientDataAccessException},所以把 {@code catch (DeadlockLoserDataAccessException)}
     * 整块删掉,代码照样编译、上面十几条用例照样全绿 —— 这条断言是唯一钉住它的东西。
     *
     * <p>失败模式:死锁掉进 transient 分支被咽下 ⇒ 但 1213 时 MySQL 回滚的是【整个事务】,
     * 打卡那条 INSERT 已经没了,而后面的 topicRouteCompletionCoordinator.advance /
     * topicCompletionAwardService.grantOnce 会落在一个新的隐式事务里提交 ⇒ 玩家看到「完成了」,
     * 库里没有那条打卡 = 静默丢打卡,零告警。
     *
     * <p>负控(2026-09-02 实测):删掉 ApiPlayProgressController 里
     * {@code catch (DeadlockLoserDataAccessException deadlock) { throw deadlock; }} 整块
     * ⇒ 本用例红(Expected org.springframework.dao.DeadlockLoserDataAccessException to be thrown,
     * but nothing was thrown.),
     * 且【只有本用例】红,下面那条 transient 用例仍绿 —— 证明两个方向是分开钉的,没混在一起。
     */
    @Test
    void deadlockMustPropagate_neverSwallowedAsNoMedal() {
        when(mapper.insertClaim(eq(NODE), eq(1), any(), any(), any(), any()))
                .thenThrow(new DeadlockLoserDataAccessException("Deadlock found when trying to get lock",
                        new SQLException("Deadlock found when trying to get lock; try restarting transaction",
                                "40001", 1213)));

        assertThrows(DeadlockLoserDataAccessException.class, this::claim);
        // 事务已经整个死了,退一档重试只是在一个已回滚的事务里瞎写:一次都不许再试
        verify(mapper, never()).insertClaim(eq(NODE), eq(2), any(), any(), any(), any());
        verify(mapper, never()).selectRankByNodeAndMember(any(), any());
    }

    /**
     * 【网 2】锁等待超时(1205)必须【被吞掉】。方向和上一条相反,同样零覆盖:
     * 已有的 {@link #nonDuplicateFailure_propagates()} 只守住「不许把 catch 放宽到
     * DataAccessException / Exception」,没人守「不许把它收窄到只剩死锁」。
     *
     * <p>失败模式:1205 被抛出去 ⇒ 外层打卡的 @Transactional(rollbackFor) 整单回滚。
     * 但 innodb_rollback_on_timeout 默认 OFF,1205 只回滚这一条 INSERT,外层事务本来还好好的 ——
     * 夺牌是锦上添花,没有「把玩家的打卡一起回滚」的权力。
     *
     * <p>负控(2026-09-02 实测):把 transient 分支的 {@code return null;} 改成
     * {@code throw transientFailure;} ⇒ 本用例红(Unexpected exception thrown:
     * CannotAcquireLockException),且【只有本用例】红,上面那条死锁用例仍绿。
     */
    @Test
    void lockWaitTimeoutMustBeSwallowed_soCheckinSurvives() {
        when(mapper.insertClaim(eq(NODE), eq(1), any(), any(), any(), any()))
                .thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded",
                        new SQLException("Lock wait timeout exceeded; try restarting transaction",
                                "HY000", 1205)));

        assertNull(assertDoesNotThrow(() -> claim()), "1205 应当收场成「没抢到牌」");
        // 锁都等超时了,立刻换一档只会再等一次 innodb_lock_wait_timeout:短路返回,不重试
        verify(mapper, never()).insertClaim(eq(NODE), eq(2), any(), any(), any(), any());
    }

    // ---------------- E 组:读取侧,SQL 别名 ↔ controller 的 key ↔ 卡片上的金银铜 ----------------

    /** 真 ApiCreativeSquareController + mock service:只跑 enrichSportFeedBatch 这一段读取链,不连库。 */
    private static final class FeedFixture {
        final IViewCreativeSquareService viewService = mock(IViewCreativeSquareService.class);
        final ApiCreativeSquareController controller = new ApiCreativeSquareController();

        FeedFixture() {
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "viewCreativeSquareService", viewService);
            // topicRouteRuntimeService 留 null:controller 自己有 null 分支(@Autowired(required = false))
        }

        ViewCreativeSquare enrich(ViewCreativeSquare card) throws Exception {
            Method m = ApiCreativeSquareController.class.getDeclaredMethod("enrichSportFeedBatch", List.class);
            m.setAccessible(true);
            m.invoke(controller, new ArrayList<ViewCreativeSquare>(Collections.singletonList(card)));
            return card;
        }
    }

    /** 一条运动帖:dataType=1 会话帖(dataId=活动)/ dataType=2 自玩帖(dataId=主题)。 */
    private static ViewCreativeSquare sportPost(long dataType) {
        ViewCreativeSquare card = new ViewCreativeSquare();
        card.setId(7L);
        card.setMemberId(MEMBER);
        card.setDataType(Long.valueOf(dataType));
        card.setDataId(dataType == 1L ? ACTIVITY : TOPIC);
        return card;
    }

    /** summary 只放 enrichSportFeedBatch 真正读的那几个键,其余留 null 走既有 null 分支。 */
    private static Map<String, Object> summaryFor(long dataType) {
        Map<String, Object> summary = new HashMap<String, Object>();
        if (dataType == 1L) {
            summary.put("activityId", ACTIVITY);
            summary.put("topicId", TOPIC);
        } else {
            summary.put("topicId", TOPIC);
        }
        return summary;
    }

    /**
     * 【网 3 + 网 4】名次→颜色、列别名→读取 key,两层隐式约定一次钉死(会话口径)。
     *
     * <p>做法:金银铜三个数字【互不相同】(7/5/3),而且行的 key 不是我写死的字符串,是
     * 从会真的执行的 SQL 里抽出来的别名 —— 于是这条用例把两端绑在了一起:
     * <ul>
     *   <li>网 3:SQL 里 {@code sum(case when medal_rank = 1 ...) as gold} 若和 silver 串位,
     *       喂进去的 7 就会落到 silver 上,断言红。金银铜都是小数字,串了看起来仍然「像对的」,
     *       没有这条断言永远发现不了;</li>
     *   <li>网 4:别名一旦从 {@code as memberId} 改成 {@code member_id},行的 key 跟着变成下划线名,
     *       而 ApiCreativeSquareController:437 的 rowValue 只试 exact/lower/upper 三种写法,
     *       取不到 ⇒ pairKey 为 null ⇒ 整行被丢弃 ⇒ 卡上静默 0 枚,断言红。</li>
     * </ul>
     *
     * <p>负控(2026-09-02 实测,把 mapper XML 复制到 /tmp、只改副本、让本用例解析那一份):
     * ① 把 rank=1 的别名 gold 与 rank=2 的 silver 对调 ⇒ 两条 feed 用例都红,报「rank=1 没落到金牌上」
     *   (JUnit 遇第一条断言即停,所以只看得到金牌那条);
     * ② 把 {@code member_id as memberId} 改成 {@code member_id as member_id} ⇒ 两条都红,
     *   {@code expected: <7> but was: <0>} —— 整行被 rowValue 丢掉,卡上静默 0 枚,正是要防的那个样子。
     */
    @Test
    void activityFeed_medalRankColorsAndAliasesReachTheCard() throws Exception {
        assertMedalsReachCard(1L);
    }

    /** 【网 3 + 网 4】自玩口径同一套契约(别名 topicId / 三色 sum),另一条 SQL 得单独钉。 */
    @Test
    void topicFeed_medalRankColorsAndAliasesReachTheCard() throws Exception {
        assertMedalsReachCard(2L);
    }

    private void assertMedalsReachCard(long dataType) throws Exception {
        boolean byActivity = dataType == 1L;
        String statementId = byActivity
                ? "selectMedalCountsByMemberActivities" : "selectMedalCountsByMemberTopics";
        String scopeColumn = byActivity ? "activity_id" : "topic_id";
        Long scopeId = byActivity ? ACTIVITY : TOPIC;

        // 1) 契约的一端:从【会真的执行的 SQL】里抽名次→颜色别名 + 两个主键列别名。一个字面量都不写死。
        String sql = renderedMedalSql(statementId, byActivity ? "activityId" : "topicId", scopeId);
        Map<Integer, String> rankAlias = rankAliases(sql);
        assertEquals(3, rankAlias.size(),
                "三档名次必须各有一个 sum(case when medal_rank = N ...) 列(抽不到就是断言恒真):" + sql);

        // 2) 按 SQL 自己的别名造一行,三个数字互不相同 —— 串位一定会被下面逮到。
        Map<String, Object> row = new HashMap<String, Object>();
        row.put(aliasOf(sql, "member_id"), MEMBER);
        row.put(aliasOf(sql, scopeColumn), scopeId);
        row.put(rankAlias.get(1), Integer.valueOf(7));
        row.put(rankAlias.get(2), Integer.valueOf(5));
        row.put(rankAlias.get(3), Integer.valueOf(3));
        assertEquals(5, row.size(), "别名重复或没抽到,这行根本没成形(断言会变恒真):" + sql);

        // 3) 契约的另一端:真 controller 按它自己写死的 key 去读这一行。
        FeedFixture f = new FeedFixture();
        List<Map<String, Object>> medalRows = Collections.singletonList(row);
        if (byActivity) {
            when(f.viewService.selectSportActivitySummaries(anyList()))
                    .thenReturn(Collections.singletonList(summaryFor(1L)));
            when(f.viewService.selectMedalCountsByMemberActivities(anyList())).thenReturn(medalRows);
        } else {
            when(f.viewService.selectSportTopicSummaries(anyList()))
                    .thenReturn(Collections.singletonList(summaryFor(2L)));
            when(f.viewService.selectMedalCountsByMemberTopics(anyList())).thenReturn(medalRows);
        }

        ViewCreativeSquare card = f.enrich(sportPost(dataType));
        assertEquals(Integer.valueOf(7), card.getMedalGold(), "rank=1 没落到金牌上:" + sql);
        assertEquals(Integer.valueOf(5), card.getMedalSilver(), "rank=2 没落到银牌上:" + sql);
        assertEquals(Integer.valueOf(3), card.getMedalBronze(), "rank=3 没落到铜牌上:" + sql);
    }

    /**
     * 【网 5】fail-closed 必须对【生产真的传进来的那个对象】成立,不是对我手捏的形状成立。
     *
     * <p>这条不重造入参:跑一遍真 enrichSportFeedBatch,把 controller 实际递给「另一口径」那条
     * 批量查询的 List 原样捞出来(整页只有自玩帖 ⇒ activityPairs 必为空 ArrayList;反之 topicPairs),
     * 再把【那个对象本身】喂给真 MyBatis。
     *
     * <p>失败模式:空 pairs 落不到 &lt;otherwise&gt; ⇒ 退化成 {@code from node_medal_claim group by ...}
     * 全表聚合 ⇒ 随便谁在别处夺的牌都会挂到这张卡上。ViewCreativeSquareServiceImpl:142/147 是直接透传,
     * 中间没有空集守卫,所以这个空 List 真的会走到 MyBatis。
     *
     * <p>负控(2026-09-02 实测,改 /tmp 上的 XML 副本):删掉
     * {@code <otherwise> where 1 = 0 </otherwise>} ⇒ 会话口径那条红(SQL 里再没有 1 = 0);
     * 删掉主题口径的 {@code <otherwise> and 1 = 0 </otherwise>} ⇒ 主题那条红。
     * 另一半(「生产传的确实是可能为空的 ArrayList」)由本用例前两条断言钉住:
     * 哪天改成传 null,assertNotNull 立刻红,提醒去看 &lt;when&gt; 走的是哪一半。
     *
     * <p>为什么非换成生产形状不可 —— 有一条负控只有生产形状逮得到(2026-09-02 实测):
     * 把 {@code <when test="pairs != null and pairs.size() > 0">} 砍成 {@code <when test="pairs != null">}
     * (空 List 也进 when ⇒ 渲染出 {@code in ()} 的坏 SQL)。
     * 喂空 ArrayList ⇒ 本用例 + 两条 feed 用例共 3 条红;
     * 把入参换回上一轮的「键不存在」形状再跑同一个变异 ⇒ 那两条 feed 用例【转绿】,只剩本用例红。
     * 旧形状对这个变异是瞎的 —— 它挡在 {@code pairs != null} 就返回了,压根没走到生产走的那半。
     */
    @Test
    void emptyPairsFromProductionStayFailClosed() throws Exception {
        assertFailClosed(pairsProductionSendsFor(2L), "selectMedalCountsByMemberActivities");
        assertFailClosed(pairsProductionSendsFor(1L), "selectMedalCountsByMemberTopics");
    }

    /** 跑一遍真 enrich,把 controller 递给【另一口径】那条批量查询的入参原样捞出来。 */
    private static List<Map<String, Object>> pairsProductionSendsFor(long dataType) throws Exception {
        boolean byActivity = dataType == 1L;
        FeedFixture f = new FeedFixture();
        if (byActivity) {
            when(f.viewService.selectSportActivitySummaries(anyList()))
                    .thenReturn(Collections.singletonList(summaryFor(1L)));
        } else {
            when(f.viewService.selectSportTopicSummaries(anyList()))
                    .thenReturn(Collections.singletonList(summaryFor(2L)));
        }
        f.enrich(sportPost(dataType));

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        if (byActivity) {
            verify(f.viewService).selectMedalCountsByMemberTopics(captor.capture());
        } else {
            verify(f.viewService).selectMedalCountsByMemberActivities(captor.capture());
        }
        return captor.getValue();
    }

    private static void assertFailClosed(List<Map<String, Object>> productionPairs, String statementId)
            throws Exception {
        assertNotNull(productionPairs,
                "生产传的是 new ArrayList<>() 不是 null;形状一变,<when> 走的就是另一半,这条网得重设:" + statementId);
        assertTrue(productionPairs.isEmpty(), "这一页不该有该口径的 pair:" + productionPairs);

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("pairs", productionPairs);   // 原样喂真 MyBatis,不重造形状
        BoundSql bound = squareStatement(statementId).getBoundSql(params);
        assertTrue(bound.getSql().replaceAll("\\s+", "").contains("1=0"),
                "空 pairs 退化成全表聚合,别人的牌会挂到这张卡上:" + bound.getSql());
        assertEquals(0, bound.getParameterMappings().size(), bound.getSql());
    }

    /** 渲染一条奖牌聚合 SQL(带一对 pair,select 列表才是完整的)。 */
    private static String renderedMedalSql(String statementId, String scopeKey, Long scopeValue)
            throws Exception {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("pairs", Collections.singletonList(pair(scopeKey, scopeValue)));
        return squareStatement(statementId).getBoundSql(params).getSql();
    }

    /** 名次 → 颜色别名,从 SQL 正文抽:sum(case when medal_rank = N then 1 else 0 end) as X。 */
    private static Map<Integer, String> rankAliases(String sql) {
        Map<Integer, String> aliases = new LinkedHashMap<Integer, String>();
        Matcher m = Pattern.compile(
                "medal_rank\\s*=\\s*(\\d+)\\s+then\\s+1\\s+else\\s+0\\s+end\\s*\\)\\s*as\\s+(\\w+)",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        while (m.find()) aliases.put(Integer.valueOf(m.group(1)), m.group(2));
        return aliases;
    }

    /** 列 → 别名(select 列表里的 `x as y`)。抽不到就让用例红,绝不静默回退成写死的名字。 */
    private static String aliasOf(String sql, String column) {
        Matcher m = Pattern.compile("(?<![\\w.])" + column + "\\s+as\\s+(\\w+)",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        assertTrue(m.find(), "SQL 里找不到 " + column + " 的别名,读取侧按 key 取值一定落空:" + sql);
        return m.group(1);
    }

}
