package com.chengyinhub.business.marketplace.paid.materialization;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.service.support.TemplateAuditSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual pinned domain/snapshot + pure conversion tests. No persistence or licensing proof. */
class PaidTemplateMaterializationFieldsTest {
    private static byte[] bytes(String body){return body.getBytes(StandardCharsets.UTF_8);}
    private static String sparse(){return "{\"schema\":\"w18-member-text-v1\",\"title\":\"Original title\",\"bindings\":{},\"validationMethod\":1}";}
    private static String full(){return "{\"schema\":\"w18-member-text-v1\",\"title\":\" Original title \",\"description\":\"Short text\",\"ruleInstructions\":\" exact rules \",\"merchantGuide\":\"staff-only exact answer\",\"questionName\":\"Exact question?\",\"questionAnswer\":\" Exact answer \",\"hint1\":\" First \",\"hint2\":\" Second \",\"answerReveal\":\" Original reveal \",\"bindings\":{},\"validationMethod\":1}";}
    @Test void allNineFieldsAndOriginalBytesArePreservedWithoutTrimOrDefaults(){
        byte[] original=bytes(full());PaidTemplateMaterializationFields mapping=PaidTemplateMaterializationFields.decode(original);CmsMemberTemplate row=mapping.newPrivateDraft(7,new Date(1234));row.setId(999L);mapping.requireExactReadback(row,7);
        assertEquals(" Original title ",row.getTitle());assertEquals(" exact rules ",row.getRuleInstructions());assertEquals("staff-only exact answer",row.getMerchantGuide());assertEquals(" Exact answer ",row.getQuestionAnswer());assertEquals(" First ",row.getHint1());assertEquals(" Second ",row.getHint2());assertEquals(" Original reveal ",row.getAnswerReveal());assertArrayEquals(original,mapping.originalComponent());
        assertTrue(TemplateAuditSnapshot.personal(row).contains("staff-only exact answer"));assertEquals(Arrays.asList("players","duration","category"),mapping.missingProfessionalInputs());
    }
    @Test void createsOnlyUnsavedPrivateDraftAndNoTemplateIdOrPublication(){
        CmsMemberTemplate row=PaidTemplateMaterializationFields.decode(bytes(full())).newPrivateDraft(7,new Date());assertNull(row.getId());assertEquals(7L,row.getMemberId());assertEquals(0,row.getDraftStatus());assertEquals(0L,row.getStatus());assertEquals(0,row.getCheckStatus());assertEquals(0L,row.getPublishStatus());assertEquals(0,row.getIsSync());assertEquals(0L,row.getOriginalTemplateId());assertEquals(0,row.getXpValue());assertEquals(0,row.getMerchantNodeEnabled());assertNull(row.getCouponId());assertNull(row.getMedalImg());assertNull(row.getCategoryId());assertNull(row.getPlayers());assertNull(row.getDuration());
    }
    @Test void sparseSourceRemainsIncompleteRatherThanInventingQuestionOrPublicationFields(){
        PaidTemplateMaterializationFields mapping=PaidTemplateMaterializationFields.decode(bytes(sparse()));CmsMemberTemplate row=mapping.newPrivateDraft(7,new Date());row.setId(1L);mapping.requireExactReadback(row,7);assertNull(row.getDescription());assertNull(row.getQuestionName());assertNull(row.getQuestionAnswer());assertEquals(1,row.getValidationMethod());assertTrue(mapping.missingProfessionalInputs().containsAll(Arrays.asList("description","questionName","questionAnswer","players","duration","category")));
    }
    @Test void absentMethodOrDifferentMethodCannotBeInferredFromQuestionText(){
        for(String body:Arrays.asList(full().replace(",\"validationMethod\":1",""),full().replace("\"validationMethod\":1","\"validationMethod\":0"),full().replace("\"validationMethod\":1","\"validationMethod\":2"),full().replace("\"validationMethod\":1","\"validationMethod\":1.0")))assertThrows(IllegalArgumentException.class,()->PaidTemplateMaterializationFields.decode(bytes(body)));
    }
    @Test void unsupportedMediaRewardsRoutesAndUnknownFieldsAreExplicitlyRejected(){
        for(String field:Arrays.asList("rewardRulesJson","questionImg","storyJson","routeGraphJson","templateId","ownerId","futureUnknownField")){
            String body=("{\""+field+"\":\"unmapped\","+sparse().substring(1));IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->PaidTemplateMaterializationFields.decode(bytes(body)));assertTrue(failure.getMessage().contains(field));
        }
    }
    @Test void duplicateFieldsBindingsAndNestedOrNullTextFailClosed(){
        for(String body:Arrays.asList(sparse().replace("\"title\":","\"title\":\"duplicate\",\"title\":"),sparse().replace("\"bindings\":{}","\"bindings\":{\"slot\":\"NPC\"}"),sparse().replace("\"Original title\"","{}"),sparse().replace("\"Original title\"","null"),sparse()+"{}"))assertThrows(IllegalArgumentException.class,()->PaidTemplateMaterializationFields.decode(bytes(body)));
    }
    @Test void descriptionUsesUtf16NotGraphemesAndNeverTruncates(){
        String thirty=new String(new char[15]).replace("\0","😀");String thirtyTwo=new String(new char[16]).replace("\0","😀");
        String okay=sparse().replace("\"title\":","\"description\":\""+thirty+"\",\"title\":");assertEquals(30,PaidTemplateMaterializationFields.decode(bytes(okay)).textFields().get("description").length());
        String tooLong=sparse().replace("\"title\":","\"description\":\""+thirtyTwo+"\",\"title\":");IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->PaidTemplateMaterializationFields.decode(bytes(tooLong)));assertTrue(error.getMessage().contains("description:exceeds-30-utf16"));
    }
    @Test void rawSourceReturnedDefensivelyAndFieldMapCannotBeMutated(){
        byte[] input=bytes(sparse());PaidTemplateMaterializationFields map=PaidTemplateMaterializationFields.decode(input);byte first=map.originalComponent()[0];input[0]=0;byte[] out=map.originalComponent();out[0]=0;assertEquals(first,map.originalComponent()[0]);assertThrows(UnsupportedOperationException.class,()->map.textFields().put("title","changed"));
    }
    @Test void invalidUtf8SurrogateAndDeepTreeCannotEnterDomainRow(){
        assertThrows(IllegalArgumentException.class,()->PaidTemplateMaterializationFields.decode(new byte[]{(byte)0xc0,(byte)0xaf}));
        assertThrows(IllegalArgumentException.class,()->PaidTemplateMaterializationFields.decode(bytes(sparse().replace("Original title","\\uD800"))));
        String nesting=new String(new char[8000]).replace('\0','[')+"0"+new String(new char[8000]).replace('\0',']');assertThrows(IllegalArgumentException.class,()->PaidTemplateMaterializationFields.decode(bytes(sparse().replace("\"Original title\"",nesting))));
    }
    @Test void badOwnerOrMissingDatabaseIdentityAndStateDoNotPassReadback(){
        PaidTemplateMaterializationFields map=PaidTemplateMaterializationFields.decode(bytes(full()));assertThrows(IllegalArgumentException.class,()->map.newPrivateDraft(0,new Date()));CmsMemberTemplate row=map.newPrivateDraft(7,new Date());assertThrows(IllegalArgumentException.class,()->map.requireExactReadback(row,7));row.setId(1L);assertThrows(IllegalArgumentException.class,()->map.requireExactReadback(row,8));row.setPublishStatus(1L);assertThrows(IllegalArgumentException.class,()->map.requireExactReadback(row,7));
    }
    @Test void truncatedMerchantGuideAndTrimmedQuestionFailRealDomainReadbackValidation(){
        PaidTemplateMaterializationFields map=PaidTemplateMaterializationFields.decode(bytes(full()));CmsMemberTemplate row=map.newPrivateDraft(7,new Date());row.setId(1L);row.setMerchantGuide("staff-only");assertThrows(IllegalArgumentException.class,()->map.requireExactReadback(row,7));CmsMemberTemplate changed=map.newPrivateDraft(7,new Date());changed.setId(1L);changed.setQuestionAnswer("Exact answer");assertThrows(IllegalArgumentException.class,()->map.requireExactReadback(changed,7));
    }
    @Test void unexpectedReadbackCapabilitiesAreNeverTreatedAsSuccessfulMapping(){
        PaidTemplateMaterializationFields map=PaidTemplateMaterializationFields.decode(bytes(full()));CmsMemberTemplate row=map.newPrivateDraft(7,new Date());row.setId(1L);row.setCouponId(99L);assertThrows(IllegalArgumentException.class,()->map.requireExactReadback(row,7));
    }
}
