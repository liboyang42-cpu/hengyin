package com.chengyinhub.business.service.ai.npc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MerchantNpcPublicFactsDraftTest {
    String facts=MerchantNpcKnowledgeDraftMybatisTest.FACTS;
    @Test void canonicalizesWhitespaceAndPreservesPublicFacts() {
        MerchantNpcPublicFactsDraft a=new MerchantNpcPublicFactsDraft(facts);
        MerchantNpcPublicFactsDraft b=new MerchantNpcPublicFactsDraft("  "+facts+"  ");
        assertEquals(a.hash,b.hash); assertEquals(a.json,b.json); assertTrue(a.json.contains("1800"));
    }
    @Test void rejectsPrivateKeysAndUnknownRootFields() {
        for(String key:new String[]{"bankAccount","ownerPhone","privateOperations","salesRevenue","profileKnowledge","taskHook","approved"})
            assertThrows(IllegalArgumentException.class,()->new MerchantNpcPublicFactsDraft(facts.replace("\"schemaVersion\":1","\"schemaVersion\":1,\""+key+"\":\"secret\"")));
    }
    @Test void rejectsInjectedNestedFieldsDuplicateKeysTrailingJson() {
        assertThrows(IllegalArgumentException.class,()->new MerchantNpcPublicFactsDraft(facts.replace("\"name\":\"Tea\"","\"name\":\"Tea\",\"account\":123")));
        assertThrows(IllegalArgumentException.class,()->new MerchantNpcPublicFactsDraft(facts.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1")));
        assertThrows(IllegalArgumentException.class,()->new MerchantNpcPublicFactsDraft(facts+" {}"));
    }
    @Test void rejectsInvalidPricesVersionsHoursAndDates() {
        for(String bad:new String[]{facts.replace("1800","-1"),facts.replace("1800","1.5"),facts.replace("CNY","bad"),
            facts.replace("\"schemaVersion\":1","\"schemaVersion\":4294967297"),facts.replace("\"day\":1","\"day\":4294967297"),
            facts.replace("18:00","08:00"),facts.replace("Asia/Shanghai","Invalid/Zone"),facts.replace("2026-10-08T00:00:00Z","2026-09-01T00:00:00Z")})
            assertThrows(IllegalArgumentException.class,()->new MerchantNpcPublicFactsDraft(bad));
    }
    @Test void rejectsOversizedAndMissingCategories() {
        assertThrows(IllegalArgumentException.class,()->new MerchantNpcPublicFactsDraft(new String(new char[16001]).replace('\0','x')));
        assertThrows(IllegalArgumentException.class,()->new MerchantNpcPublicFactsDraft("{}"));
    }
    @Test void rejectsUnpairedSurrogatesButPreservesEmoji() {
        for(char c:new char[]{(char)0xd800,(char)0xd801,(char)0xdc00}) {
            String malformed=facts.replace("Tea",String.valueOf(c));
            assertThrows(IllegalArgumentException.class,()->new MerchantNpcPublicFactsDraft(malformed));
        }
        String emoji=new String(Character.toChars(0x1f375));
        assertTrue(new MerchantNpcPublicFactsDraft(facts.replace("Tea",emoji)).json.contains(emoji));
    }
    @Test void futureAndExpiredPromotionsRemainUnapprovedDrafts() {
        assertNotNull(new MerchantNpcPublicFactsDraft(facts.replace("2026-10-01","2020-10-01").replace("2026-10-08","2020-10-08")));
        assertNotNull(new MerchantNpcPublicFactsDraft(facts.replace("2026-10-01","2030-10-01").replace("2026-10-08","2030-10-08")));
    }
}
