package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.service.impl.MerchantNpcFactsBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 门店 NPC 事实包。这是「玩家问『想吃点辣的』→ 答本店真有的菜」能不能成立的那一层。
 *
 * <p>四类断言,每类对应一个真会发生的坏结果:
 * <ul>
 *   <li><b>白名单</b> —— phone / address 不得进 prompt(进模型 = 送出平台)</li>
 *   <li><b>不编占位</b> —— 空字段整行不出现,不能写「介绍:暂无」(模型会复述「暂无」)</li>
 *   <li><b>边界指令恒在</b> —— 没有它模型会拿通用餐饮知识编菜名,玩家照着去点单</li>
 *   <li><b>零事实要说明</b> —— 一条事实都没有时必须明说,否则模型当成「没限制」自由发挥</li>
 * </ul>
 */
class MerchantNpcFactsBuilderTest {

    private MerchantNpcFactsBuilder builder;
    private IMmsMerchantService merchantService;

    @BeforeEach
    void setUp() {
        builder = new MerchantNpcFactsBuilder();
        merchantService = mock(IMmsMerchantService.class);
        ReflectionTestUtils.setField(builder, "mmsMerchantService", merchantService);
    }

    private NpcProfile profile(String persona, String knowledge) {
        NpcProfile p = new NpcProfile();
        p.setPersona(persona);
        p.setKnowledge(knowledge);
        return p;
    }

    private MmsMerchant merchant() {
        MmsMerchant m = new MmsMerchant();
        m.setName("老周咖啡");
        m.setDescription("巷子里的手冲店");
        m.setTags("安静,适合拍照");
        m.setStoryTitle("从一台磨豆机开始");
        m.setPhone("13800000000");
        m.setAddress("上海市静安区某路 1 号");
        return m;
    }

    @Test
    void includesWhitelistedShopFacts() {
        when(merchantService.selectMmsMerchantById(anyLong())).thenReturn(merchant());

        String prompt = builder.build(profile("你是老周,说话慢", "招牌是椰香拿铁"), 7L);

        assertThat(prompt)
                .doesNotContain("你是老周,说话慢")
                .contains("老周咖啡")
                .contains("巷子里的手冲店")
                .contains("安静,适合拍照")
                .contains("从一台磨豆机开始")
                .contains("招牌是椰香拿铁");
    }

    /**
     * ★ 负控本体:把 phone/address 加进 appendIfPresent 那串调用里,这条必须红。
     * 口径与 merchant_insight 一致(那边断言 prompt 输入体 grep 不到 nickname/phone/member_id)。
     */
    @Test
    void neverLeaksContactOrLocation() {
        when(merchantService.selectMmsMerchantById(anyLong())).thenReturn(merchant());

        String prompt = builder.build(profile("你是老周", "招牌是椰香拿铁"), 7L);

        assertThat(prompt)
                .doesNotContain("13800000000")
                .doesNotContain("静安区");
    }

    /** ★ 空字段整行不出现 —— 不能写成「介绍:」这种半截,模型会当成一个空事实去复述。 */
    @Test
    void omitsBlankFieldsInsteadOfWritingPlaceholders() {
        MmsMerchant m = new MmsMerchant();
        m.setName("只有名字的店");
        m.setDescription("   ");   // 空白,不是 null
        when(merchantService.selectMmsMerchantById(anyLong())).thenReturn(m);

        String prompt = builder.build(profile("你是店主", null), 7L);

        assertThat(prompt).contains("只有名字的店");
        assertThat(prompt).doesNotContain("介绍:");
        assertThat(prompt).doesNotContain("店铺知识:");
    }

    /** ★ 边界指令是这个功能能不能对外开的前提。删掉 BOUNDARY 拼接这条必须红。 */
    @Test
    void alwaysCarriesBoundaryInstruction() {
        when(merchantService.selectMmsMerchantById(anyLong())).thenReturn(merchant());

        assertThat(builder.build(profile("你是老周", "招牌"), 7L))
                .contains("【回答边界】")
                .contains("事实里没有的菜品、价格、活动,一律不要说");
    }

    /** 商家档案查不到也要带边界指令 —— 降级路径同样不能让模型裸奔。 */
    @Test
    void keepsBoundaryWhenMerchantLookupFails() {
        when(merchantService.selectMmsMerchantById(anyLong()))
                .thenThrow(new RuntimeException("db down"));

        String prompt = builder.build(profile("你是老周", null), 7L);

        assertThat(prompt).contains("【回答边界】");
        assertThat(prompt).contains("店主还没有填写任何店铺信息");
    }

    /** 一条事实都没有时要明说。不写这句,模型会把空事实包当成「没限制」。 */
    @Test
    void statesExplicitlyWhenNoFactsAtAll() {
        when(merchantService.selectMmsMerchantById(anyLong())).thenReturn(null);

        assertThat(builder.build(profile("你是店主", "  "), 7L))
                .contains("店主还没有填写任何店铺信息");
    }

    /** profile 为 null ⇒ 返回 null,调用方据此拒绝对话,而不是拿空 prompt 去调模型。 */
    @Test
    void returnsNullWithoutProfile() {
        assertThat(builder.build(null, 7L)).isNull();
    }
}
