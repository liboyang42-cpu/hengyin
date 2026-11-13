package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTopicChapter;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-82 章节级奖励 reward_text 的 mapper 往返守卫:解析真 XML 生成 BoundSql,不连库(同 PerkLedgerMapperSqlTest)。
 *
 * <p>章节 mapper 是显式列清单 + 动态 insert/update:resultMap 配了而 select 漏列 ⇒ 恒 null;
 * insert/update 漏 if ⇒ 编辑器写了也不落库。四处少一处就是「作者设了奖励、结束卡永远没有那一行」。
 */
class ChapterRewardMapperSqlTest
{
    private static final String CHAPTER = "mapper/business/CmsTopicChapterMapper.xml";
    private static final String REOPEN = "mapper/business/TopicEditionReopenMapper.xml";
    private static final String NS_CHAPTER = "com.chengyinhub.business.mapper.CmsTopicChapterMapper.";
    private static final String NS_REOPEN = "com.chengyinhub.business.mapper.TopicEditionReopenMapper.";

    private Configuration parse(String resource) throws Exception
    {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias(CmsTopicChapter.class.getSimpleName(), CmsTopicChapter.class);
        try (InputStream in = Resources.getResourceAsStream(resource))
        {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        return cfg;
    }

    private String sql(Configuration cfg, String statementId, Object param)
    {
        return cfg.getMappedStatement(statementId).getBoundSql(param).getSql().replaceAll("\\s+", " ").toLowerCase();
    }

    private static CmsTopicChapter chapter(String reward)
    {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(1L);
        c.setTopicId(2L);
        c.setRewardText(reward);
        return c;
    }

    @Test
    void resultMapMapsRewardTextColumn() throws Exception
    {
        Configuration cfg = parse(CHAPTER);
        boolean mapped = false;
        for (ResultMapping m : cfg.getResultMap(NS_CHAPTER + "CmsTopicChapterResult").getResultMappings())
        {
            if ("rewardText".equals(m.getProperty()))
            {
                assertEquals("reward_text", m.getColumn());
                mapped = true;
            }
        }
        assertTrue(mapped, "resultMap 缺 rewardText");
    }

    @Test
    void everyChapterReadSelectsRewardText() throws Exception
    {
        Configuration cfg = parse(CHAPTER);
        for (String stmt : new String[] {"selectCmsTopicChapterList", "selectCmsTopicChapterById",
                "selectByTopicForUpdate", "selectContentByIdForUpdate", "selectRequiredByTopic"})
        {
            Object param = "selectCmsTopicChapterList".equals(stmt) ? chapter(null) : 1L;
            String s = sql(cfg, NS_CHAPTER + stmt, param);
            assertTrue(s.contains("reward_text"), stmt + " 的显式列清单缺 reward_text ⇒ 玩家端恒空。实际: " + s);
        }
    }

    @Test
    void insertAndUpdateWriteRewardTextOnlyWhenSent() throws Exception
    {
        Configuration cfg = parse(CHAPTER);
        String insert = sql(cfg, NS_CHAPTER + "insertCmsTopicChapter", chapter("线索卡 ×1"));
        assertTrue(insert.contains("reward_text"), insert);
        String update = sql(cfg, NS_CHAPTER + "updateCmsTopicChapter", chapter("线索卡 ×1"));
        assertTrue(update.contains("reward_text = ?"), update);
        // 空串 = 清空(要写);null = 没送(动态 update 跳过,不清掉既有值)
        assertTrue(sql(cfg, NS_CHAPTER + "updateCmsTopicChapter", chapter("")).contains("reward_text = ?"));
        assertFalse(sql(cfg, NS_CHAPTER + "updateCmsTopicChapter", chapter(null)).contains("reward_text"));
        assertFalse(sql(cfg, NS_CHAPTER + "insertCmsTopicChapter", chapter(null)).contains("reward_text"));
    }

    @Test
    void editionReopenCarriesRewardTextToTheNewEdition() throws Exception
    {
        Configuration cfg = parse(REOPEN);
        assertTrue(sql(cfg, NS_REOPEN + "selectSourceChapters", Map.of("sourceTopicId", 1L)).contains("reward_text"));
        assertTrue(sql(cfg, NS_REOPEN + "insertReopenedChapter", chapter("线索卡 ×1")).contains("reward_text"));
    }
}
