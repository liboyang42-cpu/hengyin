package com.chengyinhub.business.marketplace.contract;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * App Workshop structural freeze, reusing reviewed version and license snapshots.
 * NOT a publication, install authorization, rights issuer, public projection or content scanner.
 * Upstream review must verify rights/evidence and approved abstract text; hashes prove integrity only.
 * No real POI, merchant, SKU, home, coordinates, inventory or executable code fields exist here.
 */
public final class ModulePackageManifest {
    public enum Type { GAMEPLAY, NARRATIVE, THEME_COMPONENT }
    public enum Capability { TEXT, CHOICE, LOCATION_BINDING, PRODUCT_BINDING }
    public enum VenueType { CAFE, RESTAURANT, RETAIL, PUBLIC_OUTDOOR, CULTURAL_VENUE }
    // Parser/verification resource bounds, not commercial/license limits.
    private static final int MAX_ITEMS = 128, MAX_DEPTH = 32;
    private final ModuleVersionSnapshot source;
    private final Type type;
    private final int minimumRuntime, maximumRuntime;
    private final Set<Capability> capabilities;
    private final Map<String, VenueType> venueSlots;
    private final Map<String, Asset> assets;
    private final Map<String, Dependency> dependencies;
    private final LicenseTermsSnapshot terms;
    private final String authorRightsEvidenceHash;
    private final byte[] canonical;
    private final String manifestHash;

    /** Asset names are abstract identifiers; no URLs or physical resource identifiers. */
    public static final class Asset {
        private final String contentHash, rightsEvidenceHash, attributionHash;
        public Asset(String contentHash, String rightsEvidenceHash, String attributionHash) {
            this.contentHash = ContractChecks.hash(contentHash);
            this.rightsEvidenceHash = ContractChecks.hash(rightsEvidenceHash);
            this.attributionHash = ContractChecks.hash(attributionHash);
        }
    }
    /** Exact dependency identity, content, manifest and frozen policy; no latest/range resolution. */
    public static final class Dependency {
        private final String moduleId, versionId, contentHash, manifestHash, termsHash, attributionHash;
        public Dependency(String moduleId, String versionId, String contentHash, String manifestHash,
                          String termsHash, String attributionHash) {
            this.moduleId = identity(moduleId); this.versionId = identity(versionId);
            this.contentHash = ContractChecks.hash(contentHash); this.manifestHash = ContractChecks.hash(manifestHash);
            this.termsHash = ContractChecks.hash(termsHash); this.attributionHash = ContractChecks.hash(attributionHash);
        }
        public static Dependency exact(ModulePackageManifest target, String attributionHash) {
            Objects.requireNonNull(target, "target");
            return new Dependency(target.source.getModuleId(), target.source.getVersionId(),
                    target.source.getContentHash(), target.manifestHash, target.terms.getTermsHash(), attributionHash);
        }
    }
    public ModulePackageManifest(ModuleVersionSnapshot source, Type type, int minimumRuntime, int maximumRuntime,
                                 Set<Capability> capabilities, Map<String, VenueType> venueSlots,
                                 Map<String, Asset> assets, Map<String, Dependency> dependencies,
                                 LicenseTermsSnapshot terms, String authorRightsEvidenceHash) {
        this.source = Objects.requireNonNull(source, "source");
        identity(source.getModuleId()); identity(source.getVersionId());
        this.type = Objects.requireNonNull(type, "type");
        if (minimumRuntime <= 0 || maximumRuntime < minimumRuntime) throw invalid("runtime range");
        this.minimumRuntime = minimumRuntime; this.maximumRuntime = maximumRuntime;
        this.capabilities = ContractChecks.set(capabilities, "capabilities");
        this.venueSlots = ContractChecks.map(venueSlots, "venueSlots");
        this.assets = ContractChecks.map(assets, "assets");
        this.dependencies = ContractChecks.map(dependencies, "dependencies");
        this.terms = Objects.requireNonNull(terms, "terms");
        this.authorRightsEvidenceHash = ContractChecks.hash(authorRightsEvidenceHash);
        bounded(this.venueSlots.size()); bounded(this.assets.size()); bounded(this.dependencies.size());
        Set<String> locations = new HashSet<String>();
        for (Map.Entry<String, ModuleVersionSnapshot.BindingKind> e : source.getRequiredBindings().entrySet()) {
            identifier(e.getKey());
            if (e.getValue() == ModuleVersionSnapshot.BindingKind.LOCATION) locations.add(e.getKey());
            Capability required = e.getValue() == ModuleVersionSnapshot.BindingKind.LOCATION
                    ? Capability.LOCATION_BINDING : Capability.PRODUCT_BINDING;
            if (!this.capabilities.contains(required)) throw invalid("binding capability missing");
        }
        if (!locations.equals(this.venueSlots.keySet())) throw invalid("abstract venue slots do not match reviewed bindings");
        for (String key : this.venueSlots.keySet()) identifier(key);
        for (String key : this.assets.keySet()) identifier(key);
        for (Map.Entry<String, Dependency> e : this.dependencies.entrySet()) {
            if (!identity(e.getKey()).equals(e.getValue().versionId)) throw invalid("dependency identity mismatch");
        }
        canonical = encode(); manifestHash = ContractChecks.sha256(canonical);
    }
    public String getManifestHash() { return manifestHash; }
    public byte[] canonicalBytes() { return canonical.clone(); }
    public ModuleVersionSnapshot getSource() { return source; }
    public Map<String, VenueType> getVenueSlots() { return venueSlots; }

    /** Only source-connected structural verification. Returned closure cannot authorize any writes. */
    static Map<String, String> verify(ModuleVersionSnapshot expectedSource, ModulePackageManifest root,
                                      String approvedManifestHash, Collection<ModulePackageManifest> registry,
                                      int runtimeVersion, Set<Capability> supportedCapabilities) {
        Objects.requireNonNull(root, "root"); Objects.requireNonNull(registry, "registry");
        if (!Arrays.equals(sourceBytes(expectedSource), sourceBytes(root.source))) throw invalid("source snapshot mismatch");
        if (!root.manifestHash.equals(ContractChecks.hash(approvedManifestHash))) throw invalid("manifest hash mismatch");
        bounded(registry.size());
        Map<String, ModulePackageManifest> nodes = new TreeMap<String, ModulePackageManifest>();
        nodes.put(root.source.getVersionId(), root);
        for (ModulePackageManifest m : registry) {
            Objects.requireNonNull(m, "registry item");
            if (nodes.put(m.source.getVersionId(), m) != null) throw invalid("duplicate version identity");
        }
        bounded(nodes.size());
        Set<Capability> supported = ContractChecks.set(supportedCapabilities, "supportedCapabilities");
        Map<String, String> closure = new TreeMap<String, String>();
        visit(root, nodes, new HashSet<String>(), closure, runtimeVersion, supported, 1);
        if (closure.size() != nodes.size()) throw invalid("unreachable dependency");
        // All dependency pins/compatibility are diagnosed before the unresolved rights boundary.
        // Existing terms only represent PROHIBITED redistribution; attribution is not permission.
        if (closure.size() > 1) throw invalid("dependency composition rights authority unavailable");
        return Collections.unmodifiableMap(closure);
    }
    private static void visit(ModulePackageManifest node, Map<String, ModulePackageManifest> nodes,
                              Set<String> visiting, Map<String, String> closure, int runtime,
                              Set<Capability> supported, int depth) {
        String id = node.source.getVersionId();
        if (visiting.contains(id)) throw invalid("dependency cycle");
        if (depth > MAX_DEPTH) throw invalid("dependency depth exceeded");
        if (closure.containsKey(id)) return;
        if (runtime < node.minimumRuntime || runtime > node.maximumRuntime || !supported.containsAll(node.capabilities))
            throw invalid("incompatible runtime or capabilities");
        visiting.add(id);
        for (Dependency pin : node.dependencies.values()) {
            ModulePackageManifest child = nodes.get(pin.versionId);
            if (child == null) throw invalid("missing dependency");
            if (visiting.contains(pin.versionId)) throw invalid("dependency cycle");
            if (!pin.moduleId.equals(child.source.getModuleId()) || !pin.contentHash.equals(child.source.getContentHash())
                    || !pin.manifestHash.equals(child.manifestHash) || !pin.termsHash.equals(child.terms.getTermsHash()))
                throw invalid("dependency pin mismatch");
            visit(child, nodes, visiting, closure, runtime, supported, depth + 1);
        }
        visiting.remove(id); closure.put(id, node.manifestHash);
    }
    private byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            write(out, "w18-app-package-manifest-v1"); byte[] s = sourceBytes(source); out.writeInt(s.length); out.write(s);
            write(out, type.name()); out.writeInt(minimumRuntime); out.writeInt(maximumRuntime);
            TreeSet<String> caps = new TreeSet<String>(); for (Capability c : capabilities) caps.add(c.name());
            out.writeInt(caps.size()); for (String c : caps) write(out, c);
            out.writeInt(venueSlots.size()); for (Map.Entry<String, VenueType> e : new TreeMap<String, VenueType>(venueSlots).entrySet()) {
                write(out, e.getKey()); write(out, e.getValue().name());
            }
            out.writeInt(assets.size()); for (Map.Entry<String, Asset> e : new TreeMap<String, Asset>(assets).entrySet()) {
                write(out, e.getKey()); write(out, e.getValue().contentHash); write(out, e.getValue().rightsEvidenceHash); write(out, e.getValue().attributionHash);
            }
            out.writeInt(dependencies.size()); for (Dependency d : new TreeMap<String, Dependency>(dependencies).values()) {
                write(out, d.moduleId); write(out, d.versionId); write(out, d.contentHash); write(out, d.manifestHash);
                write(out, d.termsHash); write(out, d.attributionHash);
            }
            write(out, terms.getTermsHash()); write(out, authorRightsEvidenceHash); out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static byte[] sourceBytes(ModuleVersionSnapshot s) {
        Objects.requireNonNull(s, "source");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            for (String v : Arrays.asList(s.getCreator().getKind().name(), Long.toString(s.getCreator().getEntityId()),
                    s.getModuleId(), s.getVersionId(), s.getVersion(), Integer.toString(s.getSchemaVersion()),
                    s.getSourceKind().name(), Long.toString(s.getSourceId()), s.getSourceRevision(),
                    s.getContentHash(), s.getApprovalReference())) write(out, v);
            out.writeInt(s.getRequiredBindings().size());
            for (Map.Entry<String, ModuleVersionSnapshot.BindingKind> e : new TreeMap<String, ModuleVersionSnapshot.BindingKind>(s.getRequiredBindings()).entrySet()) {
                write(out, e.getKey()); write(out, e.getValue().name());
            }
            out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    // Existing source identifiers are opaque (including UUIDs and colon-delimited IDs).
    // Do not reinterpret them as abstract venue/asset names.
    private static String identity(String value) {
        ContractChecks.text(value, "source identity");
        if (value.length() > 128) throw invalid("source identity too long");
        return value;
    }
    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw invalid("abstract identifier required");
        return value;
    }
    private static void bounded(int count) { if (count > MAX_ITEMS) throw invalid("manifest size exceeded"); }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
}
