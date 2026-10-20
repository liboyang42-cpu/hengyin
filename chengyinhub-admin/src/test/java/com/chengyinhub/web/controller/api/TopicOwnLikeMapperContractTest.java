package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.CmsTopicLikeMapper;
import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

class TopicOwnLikeMapperContractTest {
    private Element statement(String id) throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        try(InputStream input=getClass().getResourceAsStream("/mapper/business/CmsTopicLikeMapper.xml")) {
            assertNotNull(input);Document document=factory.newDocumentBuilder().parse(input);
            NodeList nodes=document.getDocumentElement().getChildNodes();
            for(int i=0;i<nodes.getLength();i++)if(nodes.item(i) instanceof Element) {
                Element element=(Element)nodes.item(i);if(id.equals(element.getAttribute("id")))return element;
            }
        }
        throw new AssertionError("Missing statement: "+id);
    }
    private String predicate() {
        return "where member_id = #{memberId,jdbcType=BIGINT} and data_id = #{topicId,jdbcType=BIGINT} and #{memberId,jdbcType=BIGINT} > 0 and #{topicId,jdbcType=BIGINT} > 0";
    }
    @Test void stateCountUsesMandatoryExactPositivePairWithoutPaginationOrLikeType() throws Exception {
        Element element=statement("countOwnTopicLikes");
        assertEquals("select",element.getTagName());assertEquals("java.lang.Long",element.getAttribute("resultType"));
        assertEquals("select count(1) from cms_topic_like "+predicate(),element.getTextContent().replaceAll("\\s+"," ").trim());
        assertEquals(0,element.getElementsByTagName("if").getLength());
    }
    @Test void deleteIsSameFixedPairNotIdsListToggleInsertOrOptionalWhere() throws Exception {
        Element element=statement("deleteOwnTopicLikes");assertEquals("delete",element.getTagName());
        assertEquals("delete from cms_topic_like "+predicate(),element.getTextContent().replaceAll("\\s+"," ").trim());
        assertEquals(0,element.getElementsByTagName("if").getLength());
        assertFalse(element.getTextContent().contains("${"));
    }
    @Test void mapperBindingsCannotSwapOrOmitMemberAndTopic() throws Exception {
        for(String method:new String[]{"countOwnTopicLikes","deleteOwnTopicLikes"}) {
            Method m=CmsTopicLikeMapper.class.getMethod(method,Long.class,Long.class);
            Annotation[][] a=m.getParameterAnnotations();assertEquals(2,a.length);
            assertEquals("memberId",((Param)a[0][0]).value());assertEquals("topicId",((Param)a[1][0]).value());
        }
    }
}
