package com.chengyinhub.business.service.ai;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure input-boundary tests: never opens JDBC or executes SQL. */
class MerchantAuthoringMySqlTargetTest {
    private Map<String,String> local() {
        Map<String,String> v=new HashMap<>();
        v.put("CHENGYIN_TEST_MYSQL_HOST","127.0.0.1"); v.put("CHENGYIN_TEST_MYSQL_PORT","3307");
        v.put("CHENGYIN_TEST_MYSQL_USER","synthetic"); v.put("CHENGYIN_TEST_MYSQL_PASSWORD","");
        v.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE","w12_isolated_review");
        v.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX","_unique_review"); return v;
    }
    private Map<String,String> ci() {
        Map<String,String> v=local();v.remove("CHENGYIN_TEST_MYSQL_DATABASE_BASE");
        v.put("GITHUB_ACTIONS","true");v.put("GITHUB_RUN_ID","37402578670");
        v.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX","_gh37402578670");return v;
    }
    @Test void keepsExistingExplicitReviewNamespace() {
        assertEquals("w12_isolated_review_w05_current_rows_unique_review",MerchantAuthoringMySqlTarget.from(local(),"w05_current_rows").databaseName());
    }
    @Test void acceptsOnlyExactGithubRunBoundDefaultOrExplicitBase() {
        Map<String,String> v=ci();assertEquals("chengyin_w05_current_rows_gh37402578670",MerchantAuthoringMySqlTarget.from(v,"w05_current_rows").databaseName());
        v.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE","chengyin");assertEquals("chengyin_w05_current_rows_gh37402578670",MerchantAuthoringMySqlTarget.from(v,"w05_current_rows").databaseName());
    }
    @Test void rejectsRemoteImplicitAndAlternateLoopbackHosts() {
        for(String host:new String[]{null,"localhost","::1","db.example.invalid","127.0.0.2"}) {
            Map<String,String> v=ci();v.put("CHENGYIN_TEST_MYSQL_HOST",host);assertThrows(IllegalArgumentException.class,()->MerchantAuthoringMySqlTarget.from(v,"w05_current_rows"));
        }
    }
    @Test void rejectsArbitraryOrProductionDatabaseBases() {
        for(String base:new String[]{"prod","chengyin_prod","mysql","information_schema","other",""}) {
            Map<String,String> v=ci();v.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE",base);assertThrows(IllegalArgumentException.class,()->MerchantAuthoringMySqlTarget.from(v,"w05_current_rows"));
        }
        Map<String,String> v=local();v.remove("CHENGYIN_TEST_MYSQL_DATABASE_BASE");assertThrows(IllegalArgumentException.class,()->MerchantAuthoringMySqlTarget.from(v,"w05_current_rows"));
    }
    @Test void rejectsMissingOrMismatchedGithubIdentity() {
        for(String flag:new String[]{null,"false","TRUE"}) {Map<String,String> v=ci();v.put("GITHUB_ACTIONS",flag);assertThrows(IllegalArgumentException.class,()->MerchantAuthoringMySqlTarget.from(v,"w05_current_rows"));}
        for(String run:new String[]{null,"","0","-1","37402578671","37;DROP","0001"}) {Map<String,String> v=ci();v.put("GITHUB_RUN_ID",run);assertThrows(IllegalArgumentException.class,()->MerchantAuthoringMySqlTarget.from(v,"w05_current_rows"));}
        for(String suffix:new String[]{null,"","_gh1","_local","_gh37402578670_extra"}) {Map<String,String> v=ci();v.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX",suffix);assertThrows(IllegalArgumentException.class,()->MerchantAuthoringMySqlTarget.from(v,"w05_current_rows"));}
    }
    @Test void rejectsMissingPortUserOrExplicitPasswordConfiguration() {
        for(String key:new String[]{"CHENGYIN_TEST_MYSQL_PORT","CHENGYIN_TEST_MYSQL_USER","CHENGYIN_TEST_MYSQL_PASSWORD"}) {
            Map<String,String> v=ci();v.remove(key);assertThrows(IllegalArgumentException.class,()->MerchantAuthoringMySqlTarget.from(v,"w05_current_rows"));
        }
        for(String port:new String[]{"0","65536","bad"}) {Map<String,String> v=ci();v.put("CHENGYIN_TEST_MYSQL_PORT",port);assertThrows(IllegalArgumentException.class,()->MerchantAuthoringMySqlTarget.from(v,"w05_current_rows"));}
    }
    @Test void keepsIdentifierAndLengthChecksForReviewNamespace() {
        for(String suffix:new String[]{"",";drop","_x-y","_x`",new String(new char[70]).replace('\0','x')}) {
            Map<String,String> v=local();v.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX",suffix);assertThrows(IllegalArgumentException.class,()->MerchantAuthoringMySqlTarget.from(v,"w05_current_rows"));
        }
    }
    @Test void bindsBothReviewedSuiteNamesAndRejectsEveryOtherNamespace() {
        assertEquals("chengyin_w05_race_gh37402578670",MerchantAuthoringMySqlTarget.from(ci(),"w05_race").databaseName());
        assertEquals("w12_isolated_review_w05_race_unique_review",MerchantAuthoringMySqlTarget.from(local(),"w05_race").databaseName());
        for(String suite:new String[]{null,"","w05_current_rows_other","prod","w05_race;DROP"})
            assertThrows(IllegalArgumentException.class,()->MerchantAuthoringMySqlTarget.from(ci(),suite));
    }

}
