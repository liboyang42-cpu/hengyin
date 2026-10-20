package com.chengyinhub.business.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Jupiter discovery, offline. This certifies collection only, never MySQL execution. */
class ModuleCityMySqlDiscoveryTest {
    @Test void jupiterCollectsCityBoardDraftAssertions() { discover("city.draft.CityBoardDraftMySqlIT",16); }
    @Test void jupiterCollectsOwnerHomeAssertions() { discover("home.PrivateHomeMySqlIT",31); }
    @Test void jupiterCollectsRetentionAssertions() { discover("home.PrivateHomeRetentionMySqlIT",28); }
    @Test void receiptScratchSchemaNameFitsMysqlWithFullUuid() {
        Map<String,String> environment=new HashMap<>();
        environment.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE","cy_module_city");
        String suffix="_"+UUID.randomUUID().toString().replace("-","");
        environment.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX",suffix);
        String name=com.chengyinhub.business.service.MysqlIntegrationTestConfig.from(environment,"installreceipt").databaseName();
        assertEquals(62,name.length());assertTrue(name.endsWith(suffix));
        assertEquals("cy_module_city_installreceipt"+suffix,name);
    }
    @Test void jupiterCollectsInstalledDraftIndexAndSnapshotAssertions() { discover("marketplace.editor.InstalledDraftReceiptIndexMySqlIT",10); }
    @Test void jupiterCollectsInstalledDraftReceiptAssertions() { discover("marketplace.editor.InstalledDraftReceiptMySqlIT",42); }
    @Test void jupiterCollectsPersistedFreeInstallationAssertions() { discover("marketplace.free.FreeClaimInstallationMySqlIT",43); }
    @Test void jupiterCollectsFreeInstallScopeSchemaAssertions() { discover("marketplace.free.FreeInstallScopeSchemaMySqlIT",7); }
    @Test void jupiterCollectsEveryFrozenPreviewLockAssertion() { discover("service.support.release.ContentCapturePreviewMySqlIT",18); }
    @Test void jupiterCollectsFreeClaimSchemaDriftAssertions() { discover("marketplace.free.FreeModuleSchemaMySqlIT",13); }
    @Test void jupiterCollectsEveryFreeClaimAssertion() { discover("marketplace.free.FreeModuleClaimMySqlIT",21); }
    @Test void jupiterCollectsEveryInheritedReservationAssertion() { discover("marketplace.reservation.ModuleReservationMySqlIT",35); }
    @Test void jupiterCollectsEveryInheritedInstallationAssertion() { discover("marketplace.reservation.ModuleTextInstallationMySqlIT",38); }
    @Test void jupiterCollectsEveryInheritedCityAssertion() { discover("city.CityRuntimeMySqlIT",31); }
    @Test void jupiterCollectsFullLegacyServiceRaces() { discover("service.CityLegacyPointsMySqlIT",4); }
    @Test void frozenPreviewSchemaNameFitsWithUnshortenedUuid() {
        Map<String,String> environment=new HashMap<>();
        environment.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE","cy_module_city");
        environment.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX","_"+UUID.randomUUID().toString().replace("-",""));
        String name=com.chengyinhub.business.service.MysqlIntegrationTestConfig.from(environment,"capture_preview").databaseName();
        assertEquals(63,name.length());assertTrue(name.matches("cy_module_city_capture_preview_[a-f0-9]{32}"));
    }
    private static void discover(String suffix, int expected) {
        final DiscoverySelector selector=DiscoverySelectors.selectClass("com.chengyinhub.business."+suffix);
        EngineDiscoveryRequest request=new EngineDiscoveryRequest() {
            public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> type) {
                return type.isInstance(selector)?Collections.singletonList(type.cast(selector)):Collections.<T>emptyList();
            }
            public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> type) {return Collections.emptyList();}
            public ConfigurationParameters getConfigurationParameters() {return new ConfigurationParameters() {
                public Optional<String> get(String key){return Optional.empty();}
                public Optional<Boolean> getBoolean(String key){return Optional.empty();}
                public int size(){return 0;}
            };}
        };
        TestDescriptor root=new JupiterTestEngine().discover(request,UniqueId.forEngine("junit-jupiter"));
        assertEquals(expected,root.getDescendants().stream().filter(TestDescriptor::isTest).count());
        assertTrue(root.getDescendants().stream().filter(TestDescriptor::isTest)
                .allMatch(test->test.getUniqueId().toString().contains(suffix)), "Every assertion must execute in the MySQL subclass identity");
        System.out.println("MYSQL_SUITE_DISCOVERY class=com.chengyinhub.business."+suffix+" tests="+expected+" execution=false");
    }
}
