package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 校验 P3 随机系统新增/扩展的 MyBatis Mapper XML 与接口方法一一对应、XML 语法严格合法。
 */
class SysRandomMapperXmlTest {

    @Test
    void everyMapperMethodHasMatchingXmlStatementId() throws Exception {
        assertMapperMatchesXml(GameRandomAuditMapper.class, "mapper/business/GameRandomAuditMapper.xml");
        assertMapperMatchesXml(NodeTaskPoolMapper.class, "mapper/business/NodeTaskPoolMapper.xml");
        assertMapperMatchesXml(RewardDropRuleMapper.class, "mapper/business/RewardDropRuleMapper.xml");
    }

    private static void assertMapperMatchesXml(Class<?> mapperInterface, String xmlPath) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        try (InputStream in = SysRandomMapperXmlTest.class.getClassLoader().getResourceAsStream(xmlPath)) {
            assertNotNull(in, "Missing mapper XML: " + xmlPath);
            Document doc = builder.parse(in);
            Element root = doc.getDocumentElement();
            assertEquals("mapper", root.getTagName());
            assertEquals(mapperInterface.getName(), root.getAttribute("namespace"));

            Set<String> ids = new HashSet<>();
            for (String tag : new String[]{"select", "insert", "update", "delete"}) {
                NodeList nodes = root.getElementsByTagName(tag);
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element el = (Element) nodes.item(i);
                    ids.add(el.getAttribute("id"));
                }
            }
            for (Method m : mapperInterface.getDeclaredMethods()) {
                assertTrue(ids.contains(m.getName()),
                        "Missing statement id '" + m.getName() + "' in " + xmlPath);
            }
        }
    }
}
