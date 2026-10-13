package com.chengyinhub.business.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;

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
}
