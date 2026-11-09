package com.chengyinhub.business.appleiap;

import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AppleSignedDataSdkTest {
    static String input(String name) {
        String value=System.getProperty(name);
        return value == null || value.isBlank() ? System.getenv(name) : value;
    }
    static boolean hasRoot() { String value=input("APPLE_IAP_TEST_ROOT"); return value != null && !value.isBlank(); }
    static boolean hasFixtures() {
        String value=input("APPLE_IAP_TEST_FIXTURE_DIR"); return hasRoot() && value != null && !value.isBlank();
    }
    @Test
    @EnabledIf("hasRoot")
    void actualOfficialSdkRejectsMalformedAndUnsignedTransactionAndNotification() {
        var config=WorkshopAppleIapServiceTest.config();
        config.setRootCertificatePaths(List.of(input("APPLE_IAP_TEST_ROOT")));
        var sdk=new AppleSignedData(config); assertTrue(sdk.isReady(),"Use an official Apple PKI public root file");
        for(String invalid:List.of("not.a.jws", "eyJhbGciOiJub25lIn0.e30.")) {
            assertEquals("APPLE_TRANSACTION_VERIFICATION_FAILED",assertThrows(ServiceException.class,()->sdk.transaction(invalid)).getMessage());
            assertEquals("APPLE_NOTIFICATION_VERIFICATION_FAILED",assertThrows(ServiceException.class,()->sdk.notification(invalid)).getMessage());
        }
    }

    @Test
    @EnabledIf("hasFixtures")
    void actualSdkRejectsOfficialXcodeAndMockCertificatesAgainstAppleProductionRoots() throws Exception {
        var config=WorkshopAppleIapServiceTest.config();
        config.setRootCertificatePaths(List.of(input("APPLE_IAP_TEST_ROOT")));
        var sdk=new AppleSignedData(config); assertTrue(sdk.isReady());
        var fixtures=java.nio.file.Path.of(input("APPLE_IAP_TEST_FIXTURE_DIR"));
        String transaction=java.nio.file.Files.readString(fixtures.resolve("xcode-signed-transaction")).trim();
        String notification=java.nio.file.Files.readString(fixtures.resolve("testNotification")).trim();
        assertEquals("APPLE_TRANSACTION_VERIFICATION_FAILED",assertThrows(ServiceException.class,()->sdk.transaction(transaction)).getMessage());
        assertEquals("APPLE_NOTIFICATION_VERIFICATION_FAILED",assertThrows(ServiceException.class,()->sdk.notification(notification)).getMessage());
    }

    @Test void serverOnlyEnvironmentSelectsOfficialSdkClientWithoutCallingApple(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var key=temp.resolve("fixture-key.txt"); java.nio.file.Files.writeString(key,"test fixture only");
        var config=WorkshopAppleIapServiceTest.config(); config.setServerApiEnabled(true);
        config.setSigningKeyPath(key.toString()); config.setKeyId("fixture"); config.setIssuerId("fixture");
        for (var environment : java.util.List.of(com.apple.itunes.storekit.model.Environment.SANDBOX, com.apple.itunes.storekit.model.Environment.PRODUCTION)) {
            config.setEnvironment(environment.getValue()); config.setAppAppleId(99L);
            try(var clients=org.mockito.Mockito.mockConstruction(com.apple.itunes.storekit.client.AppStoreServerAPIClient.class,(client,context)->{
                assertEquals(environment,context.arguments().get(4));
                org.mockito.Mockito.when(client.getTransactionInfo("1001")).thenReturn(new com.apple.itunes.storekit.model.TransactionInfoResponse().signedTransactionInfo("verified-later"));
            })) {
                assertEquals("verified-later",new AppleSignedData(config).query("1001"));
                assertEquals(1,clients.constructed().size());
                org.mockito.Mockito.verify(clients.constructed().get(0)).getTransactionInfo("1001");
            }
        }
    }

    @Test void clientsCannotSelectLocalTestingToBypassSignatureVerification() {
        var config=WorkshopAppleIapServiceTest.config(); config.setEnvironment("LocalTesting");
        var sdk=new AppleSignedData(config); assertFalse(sdk.isReady());
        assertEquals("APPLE_IAP_UNAVAILABLE",assertThrows(ServiceException.class,()->sdk.transaction("anything")).getMessage());
    }
}
