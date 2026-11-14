package com.chengyinhub.business.externalgame;

import com.chengyinhub.business.appgaps.AppGapException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.EdECPrivateKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Mirror of the app's ExternalGameValidator; the SHA-256 vector was produced by the app's Swift construction. */
class ExternalGamePackageValidatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static Map<String, byte[]> files() {
        Map<String, byte[]> f = new TreeMap<>();
        f.put("index.html", "<!doctype html><p>hi 你好</p>".getBytes(StandardCharsets.UTF_8));
        f.put("js/app.js", "console.log(1)".getBytes(StandardCharsets.UTF_8));
        f.put("img/a.png", new byte[]{0, 1, 2, (byte) 255});
        return f;
    }

    static ObjectNode manifest(Map<String, byte[]> files) {
        ObjectNode m = JSON.createObjectNode();
        m.put("id", "city.quiz-one");
        m.put("name", "城市小测");
        m.put("version", "1.0.0");
        m.put("entry", "index.html");
        m.putArray("permissions").add("storage").add("haptics");
        m.put("contentRating", "9+");
        m.putArray("allowedDomains").add("cdn.example.com");
        m.put("sha256", ExternalGamePackageValidator.packageSha256(files));
        m.put("signature", "client-signature-is-ignored");
        m.put("author", "Ada");
        return m;
    }

    static byte[] archive(ObjectNode manifest, Map<String, byte[]> files) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.set("manifest", manifest);
        ObjectNode f = root.putObject("files");
        files.forEach((k, v) -> f.put(k, Base64.getEncoder().encodeToString(v)));
        return JSON.writeValueAsBytes(root);
    }

    private static String code(Consumer<ObjectNode> edit) {
        Map<String, byte[]> f = files();
        ObjectNode m = manifest(f);
        edit.accept(m);
        return assertThrows(AppGapException.class, () -> ExternalGamePackageValidator.validate(m, f, null)).getErrorCode();
    }

    @Test
    void packageHashMatchesTheAppsSwiftConstruction() {
        assertEquals("46e6404b3247f5e7261b4ef59c72d279113f6467b78bce8cf4243b0324c6c7d5", ExternalGamePackageValidator.packageSha256(files()));
    }

    @Test
    void validArchiveJsonPasses() throws Exception {
        ExternalGamePackageValidator.Validated v = ExternalGamePackageValidator.fromArchiveJson(archive(manifest(files()), files()), "NONE");
        assertEquals("city.quiz-one", v.manifest().id());
        assertEquals(3, v.files().size());
        assertEquals(List.of("storage", "haptics"), v.manifest().permissions());
        assertEquals("v1|city.quiz-one|城市小测|1.0.0|index.html|storage,haptics|9+|cdn.example.com|" + v.sha256(),
                new String(ExternalGamePackageValidator.canonicalSigningPayload(v.manifest()), StandardCharsets.UTF_8));
    }

    @Test
    void singleHtmlFormPasses() throws Exception {
        byte[] html = "<html>solo</html>".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> f = Map.of("index.html", html);
        ObjectNode m = manifest(f);
        assertEquals(1, ExternalGamePackageValidator.fromManifestAndEntry(JSON.writeValueAsBytes(m), html, null).files().size());
    }

    @Test
    void manifestRulesMirrorTheApp() {
        assertEquals("INVALID_MANIFEST_ID", code(m -> m.put("id", "AB")));
        assertEquals("INVALID_MANIFEST_ID", code(m -> m.put("id", "-bad")));
        assertEquals("INVALID_MANIFEST_NAME", code(m -> m.put("name", "  ")));
        assertEquals("INVALID_MANIFEST_NAME", code(m -> m.put("name", "x".repeat(81))));
        assertEquals("INVALID_MANIFEST_VERSION", code(m -> m.put("version", "1.0")));
        assertEquals("INVALID_MANIFEST_VERSION", code(m -> m.put("version", "1.0.0-beta")));
        assertEquals("INVALID_CONTENT_RATING", code(m -> m.put("contentRating", "18+")));
        assertEquals("PATH_TRAVERSAL_DETECTED", code(m -> m.put("entry", "../index.html")));
        assertEquals("DISALLOWED_FILE_EXTENSION", code(m -> m.put("entry", "js/app.js")));
        assertEquals("FORBIDDEN_PERMISSION", code(m -> m.putArray("permissions").add("camera")));
        assertEquals("FORBIDDEN_PERMISSION", code(m -> m.putArray("permissions").add("payment")));
        assertEquals("FORBIDDEN_PERMISSION", code(m -> m.putArray("permissions").add("unknown")));
        assertEquals("INVALID_ALLOWED_DOMAIN", code(m -> m.putArray("allowedDomains").add("127.0.0.1")));
        assertEquals("INVALID_ALLOWED_DOMAIN", code(m -> m.putArray("allowedDomains").add("https://x.com")));
        assertEquals("INVALID_ALLOWED_DOMAIN", code(m -> m.putArray("allowedDomains").add("printer.local")));
        assertEquals("TOO_MANY_ALLOWED_DOMAINS", code(m -> { var a = m.putArray("allowedDomains"); for (int i = 0; i < 11; i++) a.add("d" + i + ".example.com"); }));
        assertEquals("UNSIGNED_MANIFEST", code(m -> m.put("sha256", "")));
        assertEquals("HASH_MISMATCH", code(m -> m.put("sha256", "0".repeat(64))));
        assertEquals("MANIFEST_UNKNOWN_FIELD", code(m -> m.put("nativeBridge", true)));
    }

    @Test
    void monetizationMustBeNone() {
        assertEquals("MONETIZATION_NOT_ALLOWED", code(m -> m.put("monetization", "IAA")));
        assertEquals("MONETIZATION_NOT_ALLOWED", code(m -> m.put("monetizationDeclaration", "DIGITAL_SALE")));
        assertEquals("MONETIZATION_NOT_ALLOWED", code(m -> m.put("price", 6)));
        Map<String, byte[]> f = files();
        ObjectNode m = manifest(f);
        assertEquals("MONETIZATION_NOT_ALLOWED", assertThrows(AppGapException.class, () -> ExternalGamePackageValidator.validate(m, f, "MERCHANT_FUNDED")).getErrorCode());
        m.put("monetization", "NONE");
        assertNotNull(ExternalGamePackageValidator.validate(m, f, "NONE"));
    }

    @Test
    void fileRulesMirrorTheApp() {
        Map<String, byte[]> f = new TreeMap<>(files());
        f.put("js/pay.js", "new PaymentRequest([])".getBytes(StandardCharsets.UTF_8));
        assertEquals("PAYMENT_OR_IAP_DETECTED", assertThrows(AppGapException.class, () -> ExternalGamePackageValidator.validate(manifest(f), f, null)).getErrorCode());

        Map<String, byte[]> exe = new TreeMap<>(files());
        exe.put("tool.exe", new byte[]{1});
        assertEquals("DISALLOWED_FILE_EXTENSION", assertThrows(AppGapException.class, () -> ExternalGamePackageValidator.validate(manifest(exe), exe, null)).getErrorCode());

        Map<String, byte[]> hidden = new TreeMap<>(files());
        hidden.put(".env.txt", new byte[]{1});
        assertEquals("PATH_TRAVERSAL_DETECTED", assertThrows(AppGapException.class, () -> ExternalGamePackageValidator.validate(manifest(hidden), hidden, null)).getErrorCode());

        Map<String, byte[]> big = new TreeMap<>(files());
        big.put("big.txt", new byte[ExternalGamePackageValidator.MAX_FILE_BYTES + 1]);
        AppGapException e = assertThrows(AppGapException.class, () -> ExternalGamePackageValidator.validate(manifest(big), big, null));
        assertEquals("FILE_OVERSIZED", e.getErrorCode());
        assertEquals(413, e.getStatus());

        Map<String, byte[]> total = new TreeMap<>(files());
        total.put("a.txt", new byte[ExternalGamePackageValidator.MAX_FILE_BYTES]);
        total.put("b.txt", new byte[ExternalGamePackageValidator.MAX_FILE_BYTES]);
        assertEquals("PACKAGE_OVERSIZED", assertThrows(AppGapException.class, () -> ExternalGamePackageValidator.validate(manifest(total), total, null)).getErrorCode());

        Map<String, byte[]> many = new TreeMap<>();
        many.put("index.html", new byte[]{1});
        for (int i = 0; i < 200; i++) many.put("f" + i + ".txt", new byte[]{1});
        assertEquals("TOO_MANY_FILES", assertThrows(AppGapException.class, () -> ExternalGamePackageValidator.validate(manifest(many), many, null)).getErrorCode());

        Map<String, byte[]> noEntry = new TreeMap<>(files());
        noEntry.remove("index.html");
        assertEquals("MISSING_ENTRY_FILE", assertThrows(AppGapException.class, () -> ExternalGamePackageValidator.validate(manifest(noEntry), noEntry, null)).getErrorCode());
    }

    @Test
    void malformedArchivesAreRejected() throws Exception {
        assertEquals("MALFORMED_ARCHIVE", assertThrows(AppGapException.class, () -> ExternalGamePackageValidator.fromArchiveJson("{".getBytes(), null)).getErrorCode());
        assertEquals("MALFORMED_ARCHIVE", assertThrows(AppGapException.class,
                () -> ExternalGamePackageValidator.fromArchiveJson("{\"manifest\":{},\"files\":{\"index.html\":\"***\"}}".getBytes(), null)).getErrorCode());
        assertEquals("MALFORMED_ARCHIVE", assertThrows(AppGapException.class,
                () -> ExternalGamePackageValidator.fromArchiveJson("{\"manifest\":{},\"files\":{},\"files\":{}}".getBytes(), null)).getErrorCode(), "duplicate keys");
        assertEquals("PACKAGE_OVERSIZED", assertThrows(AppGapException.class,
                () -> ExternalGamePackageValidator.fromArchiveJson(new byte[ExternalGamePackageValidator.MAX_ARCHIVE_JSON_BYTES + 1], null)).getErrorCode());
    }

    @Test
    void semverOrdersNumerically() {
        assertTrue(ExternalGamePackageValidator.compareVersions("1.10.0", "1.9.9") > 0);
        assertEquals(0, ExternalGamePackageValidator.compareVersions("2.0.0", "2.0.0"));
        assertTrue(ExternalGamePackageValidator.compareVersions("0.0.9", "0.1.0") < 0);
    }

    @Test
    void signerUsesTheConfiguredSeedAndFailsClosedWithoutIt() throws Exception {
        ExternalGameSigner none = new ExternalGameSigner("", "k1");
        assertFalse(none.configured());
        assertEquals(false, none.publicKeyView().get("configured"));
        assertFalse(new ExternalGameSigner("bm90LWEta2V5", "k1").configured(), "garbage key is not a key");

        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String pkcs8 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String seed = Base64.getEncoder().encodeToString(((EdECPrivateKey) pair.getPrivate()).getBytes().orElseThrow());
        ExternalGameSigner a = new ExternalGameSigner(pkcs8, "k1");
        ExternalGameSigner b = new ExternalGameSigner(seed, "k1");
        assertTrue(a.configured());
        assertEquals(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), a.publicKeyView().get("publicKeySpki"));
        assertEquals(a.publicKeyView().get("publicKeySpki"), b.publicKeyView().get("publicKeySpki"));
        byte[] payload = "v1|x".getBytes(StandardCharsets.UTF_8);
        String sig = a.signHex(payload);
        assertEquals(128, sig.length());
        assertTrue(b.verifyHex(payload, sig));
        assertFalse(b.verifyHex("v1|y".getBytes(StandardCharsets.UTF_8), sig));
        String token = a.linkToken("dl-v1|g|1|h|9");
        assertTrue(a.verifyLinkToken("dl-v1|g|1|h|9", token));
        assertFalse(a.verifyLinkToken("dl-v1|g|1|h|10", token));
        // Another environment's key never verifies this environment's signature.
        ExternalGameSigner other = new ExternalGameSigner(Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded()), "k2");
        assertFalse(other.verifyHex(payload, sig));
        Map<String, Object> view = new LinkedHashMap<>(a.publicKeyView());
        assertFalse(view.toString().contains(pkcs8), "private key never exposed");
    }
}
