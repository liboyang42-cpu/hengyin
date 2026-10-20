package com.chengyinhub.business.marketplace.contract;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModulePackageManifestTest {
    static final String HASH = sha("evidence"), OTHER = sha("other");
    static final Set<ModulePackageManifest.Capability> CAPS = EnumSet.allOf(ModulePackageManifest.Capability.class);
    static String sha(String value) { return ContractChecks.sha256(value.getBytes(StandardCharsets.UTF_8)); }
    static LicenseTermsSnapshot terms() {
        return new LicenseTermsSnapshot("terms-v1", new byte[]{1}, ContractChecks.sha256(new byte[]{1}), "reviewed-terms",
                LicenseTermsSnapshot.Permission.PROHIBITED, LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION,
                LicenseTermsSnapshot.Permission.PROHIBITED, LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION,
                LicenseTermsSnapshot.Redistribution.PROHIBITED, Collections.singleton("CN"),
                LicenseTermsSnapshot.Limit.atMost(1), LicenseTermsSnapshot.Limit.atMost(1), LicenseTermsSnapshot.Limit.atMost(1));
    }
    static ModuleVersionSnapshot source(String id) { return source(id, "approval-v1"); }
    static ModuleVersionSnapshot source(String id, String approval) {
        byte[] bytes = ("reviewed-" + id).getBytes(StandardCharsets.UTF_8);
        return new ModuleVersionSnapshot(new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL, 8), "module-"+id, id, "1.0.0", 1,
                ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE, 42, "revision-1", bytes, ContractChecks.sha256(bytes),
                approval, Collections.singletonMap("site", ModuleVersionSnapshot.BindingKind.LOCATION));
    }
    static ModulePackageManifest manifest(String id) { return manifest(source(id), Collections.<String, ModulePackageManifest.Dependency>emptyMap()); }
    static ModulePackageManifest manifest(ModuleVersionSnapshot source, Map<String, ModulePackageManifest.Dependency> deps) {
        return new ModulePackageManifest(source, ModulePackageManifest.Type.GAMEPLAY, 1, 3, CAPS,
                Collections.singletonMap("site", ModulePackageManifest.VenueType.CAFE),
                Collections.singletonMap("art", new ModulePackageManifest.Asset(HASH, HASH, HASH)), deps, terms(), HASH);
    }
    static Map<String,String> verify(ModulePackageManifest root, ModulePackageManifest... deps) {
        return root.getSource().verifyPackageManifest(root, root.getManifestHash(), Arrays.asList(deps), 2, CAPS);
    }
    static void reason(String reason, org.junit.jupiter.api.function.Executable action) {
        assertEquals(reason, assertThrows(IllegalArgumentException.class, action).getMessage());
    }
    @Test void leafUsesExistingSourceAndReturnsImmutableNonExecutableClosure() {
        ModulePackageManifest m = manifest("v1"); Map<String,String> closure = verify(m);
        assertEquals(Collections.singletonMap("v1", m.getManifestHash()), closure);
        assertThrows(UnsupportedOperationException.class, () -> closure.put("v2", HASH));
    }
    @Test void approvalTamperAndMutableLatestSourceRejected() {
        ModulePackageManifest m = manifest("v1");
        reason("source snapshot mismatch", () -> source("v1", "approval-v2").verifyPackageManifest(m, m.getManifestHash(), Collections.emptyList(), 2, CAPS));
        reason("source snapshot mismatch", () -> source("v2").verifyPackageManifest(m, m.getManifestHash(), Collections.emptyList(), 2, CAPS));
        reason("manifest hash mismatch", () -> m.getSource().verifyPackageManifest(m, OTHER, Collections.emptyList(), 2, CAPS));
    }
    @Test void freezesDefensiveCopiesAndCanonicalBytes() {
        Map<String,ModulePackageManifest.VenueType> slots = new HashMap<>(); slots.put("site", ModulePackageManifest.VenueType.CAFE);
        ModulePackageManifest m = new ModulePackageManifest(source("v1"), ModulePackageManifest.Type.GAMEPLAY, 1, 3, CAPS, slots,
                Collections.emptyMap(), Collections.emptyMap(), terms(), HASH);
        String hash = m.getManifestHash(); slots.clear(); byte[] bytes = m.canonicalBytes(); bytes[0] ^= 1;
        assertEquals(hash, m.getManifestHash()); assertEquals(hash, ContractChecks.sha256(m.canonicalBytes()));
        assertThrows(UnsupportedOperationException.class, () -> m.getVenueSlots().clear());
    }
    @Test void canonicalOrderingAndEveryMetadataChangeBound() {
        Map<String,ModulePackageManifest.Asset> a = new LinkedHashMap<>(), b = new LinkedHashMap<>();
        ModulePackageManifest.Asset asset = new ModulePackageManifest.Asset(HASH, HASH, HASH);
        a.put("a", asset); a.put("b", asset); b.put("b", asset); b.put("a", asset);
        ModulePackageManifest left = new ModulePackageManifest(source("v1"), ModulePackageManifest.Type.GAMEPLAY, 1, 3, CAPS,
                Collections.singletonMap("site", ModulePackageManifest.VenueType.CAFE), a, Collections.emptyMap(), terms(), HASH);
        ModulePackageManifest right = new ModulePackageManifest(source("v1"), ModulePackageManifest.Type.GAMEPLAY, 1, 3, CAPS,
                Collections.singletonMap("site", ModulePackageManifest.VenueType.CAFE), b, Collections.emptyMap(), terms(), HASH);
        assertEquals(left.getManifestHash(), right.getManifestHash());
        b.put("a", new ModulePackageManifest.Asset(HASH, OTHER, HASH));
        ModulePackageManifest changed = new ModulePackageManifest(source("v1"), ModulePackageManifest.Type.GAMEPLAY, 1, 3, CAPS,
                Collections.singletonMap("site", ModulePackageManifest.VenueType.CAFE), b, Collections.emptyMap(), terms(), HASH);
        assertNotEquals(left.getManifestHash(), changed.getManifestHash());
    }
    @Test void rejectsUnsupportedRuntimeAndCapabilities() {
        ModulePackageManifest m = manifest("v1");
        reason("incompatible runtime or capabilities", () -> m.getSource().verifyPackageManifest(m,m.getManifestHash(),Collections.emptyList(),4,CAPS));
        reason("incompatible runtime or capabilities", () -> m.getSource().verifyPackageManifest(m,m.getManifestHash(),Collections.emptyList(),2,Collections.emptySet()));
    }
    @Test void requiresExactAbstractSlotAndBindingCapability() {
        reason("abstract venue slots do not match reviewed bindings", () -> new ModulePackageManifest(source("v1"), ModulePackageManifest.Type.GAMEPLAY,
                1, 2, CAPS, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), terms(), HASH));
        reason("binding capability missing", () -> new ModulePackageManifest(source("v1"), ModulePackageManifest.Type.GAMEPLAY,
                1, 2, Collections.singleton(ModulePackageManifest.Capability.TEXT), Collections.singletonMap("site",ModulePackageManifest.VenueType.CAFE),
                Collections.emptyMap(),Collections.emptyMap(),terms(),HASH));
    }
    @Test void rejectsPhysicalAddressAssetUrlAndMissingRightsAttribution() {
        reason("abstract identifier required", () -> new ModulePackageManifest(source("v1"), ModulePackageManifest.Type.GAMEPLAY, 1, 2, CAPS,
                Collections.singletonMap("site",ModulePackageManifest.VenueType.CAFE),
                Collections.singletonMap("https://private-address/31.2,121.5",new ModulePackageManifest.Asset(HASH,HASH,HASH)),Collections.emptyMap(),terms(),HASH));
        assertThrows(IllegalArgumentException.class, () -> new ModulePackageManifest.Asset(HASH,null,HASH));
        assertThrows(IllegalArgumentException.class, () -> new ModulePackageManifest.Asset(HASH,HASH,null));
        assertThrows(IllegalArgumentException.class, () -> ModulePackageManifest.Dependency.exact(manifest("v2"),null));
    }
    @Test void rejectsDuplicateAndUnreachableRegistryEntries() {
        ModulePackageManifest m = manifest("v1");
        reason("duplicate version identity", () -> verify(m,m));
        reason("duplicate version identity", () -> verify(m,manifest("v2"),manifest("v2")));
        reason("unreachable dependency", () -> verify(m,manifest("v2")));
    }
    @Test void rejectsMissingDependencyAndIdentityMismatch() {
        ModulePackageManifest child = manifest("v2");
        ModulePackageManifest root = manifest(source("v1"),Collections.singletonMap("v2",ModulePackageManifest.Dependency.exact(child,HASH)));
        reason("missing dependency", () -> verify(root));
        reason("dependency identity mismatch", () -> manifest(source("v1"),Collections.singletonMap("v3",ModulePackageManifest.Dependency.exact(child,HASH))));
    }
    @Test void refusesRemixEvenWithLocalAdaptationSameAuthorAndAttribution() {
        ModulePackageManifest child = manifest("v2");
        ModulePackageManifest root = manifest(source("v1"),Collections.singletonMap("v2",ModulePackageManifest.Dependency.exact(child,HASH)));
        reason("dependency composition rights authority unavailable", () -> verify(root,child));
    }
    @Test void rejectsContentManifestAndTermsPinMismatch() {
        ModulePackageManifest child = manifest("v2");
        for (int field=0;field<3;field++) {
            ModulePackageManifest.Dependency pin = new ModulePackageManifest.Dependency(child.getSource().getModuleId(),"v2",
                    field==0?OTHER:child.getSource().getContentHash(),field==1?OTHER:child.getManifestHash(),field==2?OTHER:terms().getTermsHash(),HASH);
            ModulePackageManifest root = manifest(source("v1"),Collections.singletonMap("v2",pin));
            reason("dependency pin mismatch", () -> verify(root,child));
        }
    }
    @Test void rejectsSelfCycleWithoutTrustingFabricatedPin() {
        ModulePackageManifest root = manifest(source("v1"),Collections.singletonMap("v1",
                new ModulePackageManifest.Dependency("module-v1","v1",HASH,HASH,HASH,HASH)));
        reason("dependency cycle", () -> verify(root));
    }
    @Test void closureDepthIsBoundedBeforeAnyCompositionRightsDecision() {
        List<ModulePackageManifest> nodes = new ArrayList<>(); ModulePackageManifest child=manifest("v33"); nodes.add(child);
        for(int i=32;i>=1;i--) { child=manifest(source("v"+i),Collections.singletonMap(child.getSource().getVersionId(),ModulePackageManifest.Dependency.exact(child,HASH)));nodes.add(child); }
        ModulePackageManifest root=nodes.remove(nodes.size()-1);
        reason("dependency depth exceeded", () -> verify(root,nodes.toArray(new ModulePackageManifest[0])));
    }
    @Test void registryAndAssetsAreBounded() {
        List<ModulePackageManifest> nodes=new ArrayList<>();for(int i=0;i<129;i++)nodes.add(manifest("node"+i));
        reason("manifest size exceeded", () -> verify(manifest("root"),nodes.toArray(new ModulePackageManifest[0])));
        Map<String,ModulePackageManifest.Asset> assets=new HashMap<>();for(int i=0;i<129;i++)assets.put("asset"+i,new ModulePackageManifest.Asset(HASH,HASH,HASH));
        reason("manifest size exceeded", () -> new ModulePackageManifest(source("root"),ModulePackageManifest.Type.GAMEPLAY,1,2,CAPS,
                Collections.singletonMap("site",ModulePackageManifest.VenueType.CAFE),assets,Collections.emptyMap(),terms(),HASH));
    }
    @Test void preservesExistingOpaqueSourceIdsAndExactDependencyKeys() {
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        assertTrue(verify(manifest(uuid)).containsKey(uuid));
        ModulePackageManifest child = manifest("version:2");
        assertTrue(verify(child).containsKey("version:2"));
        ModulePackageManifest root = manifest(source("root:1"), Collections.singletonMap("version:2", ModulePackageManifest.Dependency.exact(child,HASH)));
        reason("dependency composition rights authority unavailable", () -> verify(root,child));
        reason("dependency identity mismatch", () -> manifest(source("root:1"),Collections.singletonMap("version:3",ModulePackageManifest.Dependency.exact(child,HASH))));
    }
    @Test void legacyVersionGetterShapeUnchanged() {
        Set<String> getters=new TreeSet<>();for(java.lang.reflect.Method method:ModuleVersionSnapshot.class.getDeclaredMethods())if(method.getName().startsWith("get"))getters.add(method.getName());
        assertEquals(new TreeSet<>(Arrays.asList("getCreator","getModuleId","getVersionId","getVersion","getSchemaVersion","getSourceKind","getSourceId","getSourceRevision","getContentHash","getApprovalReference","getRequiredBindings")),getters);
    }
}
