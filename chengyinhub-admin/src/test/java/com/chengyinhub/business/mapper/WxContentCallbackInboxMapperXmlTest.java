package com.chengyinhub.business.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import com.chengyinhub.business.service.IWxContentSecurityService;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class WxContentCallbackInboxMapperXmlTest
{
    @Test
    void mapperXmlParsesAndContainsOwnerFencedTerminalStatements() throws Exception
    {
        Configuration configuration = new Configuration();
        String resource = "mapper/business/WxContentCallbackInboxMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource))
        {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String namespace = WxContentCallbackInboxMapper.class.getName() + ".";
        assertTrue(configuration.hasStatement(namespace + "selectOwnedForUpdate"));
        assertTrue(configuration.hasStatement(namespace + "markDone"));
        assertTrue(configuration.hasStatement(namespace + "markManualReview"));
        assertTrue(configuration.hasStatement(namespace + "release"));
        assertTrue(configuration.hasStatement(namespace + "lockBusinessTarget"));
    }

    /**
     * #334 F5:节点 NPC(16)已在送检却无锁目标分支 → 回调抛 "callback target missing",违规头像永不下架。
     * 门店分身(18)同理。两条都只能锁到各自 scope 的 npc_profile 行;已有类型号(13/15/17)语义不变。
     */
    @Test
    void lockBusinessTargetCoversNpcTypesWithoutRenumberingExistingOnes() throws Exception
    {
        assertEquals("select id from npc_profile where id = ? and scope_type = 4 for update",
                lockSql(IWxContentSecurityService.BIZ_TYPE_NODE_NPC));
        assertEquals("select id from npc_profile where id = ? and scope_type = 2 for update",
                lockSql(IWxContentSecurityService.BIZ_TYPE_MERCHANT_NPC));
        assertEquals("select id from community_post where id = ? for update",
                lockSql(IWxContentSecurityService.BIZ_TYPE_COMMUNITY_POST));
        assertEquals("select id from cms_member_template where id = ? for update",
                lockSql(IWxContentSecurityService.BIZ_TYPE_TEMPLATE));
        assertEquals("select id from roam_hangout where id = ? for update",
                lockSql(IWxContentSecurityService.BIZ_TYPE_HANGOUT));
        assertEquals(17, IWxContentSecurityService.BIZ_TYPE_COMMUNITY_POST, "社区帖文的既有类型号不得重排");
        assertEquals(18, IWxContentSecurityService.BIZ_TYPE_MERCHANT_NPC);
    }

    private static String lockSql(int bizType) throws Exception
    {
        Configuration configuration = new Configuration();
        String resource = "mapper/business/WxContentCallbackInboxMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource))
        {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("bizType", bizType);
        params.put("bizId", 77L);
        return configuration.getMappedStatement(WxContentCallbackInboxMapper.class.getName() + ".lockBusinessTarget")
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").trim();
    }
}
