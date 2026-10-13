#!/usr/bin/env python3
"""Inspect the actual Boot JAR and retain raw scanner findings; never suppress matches."""
import argparse
import collections
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

if not __debug__:
    raise SystemExit("Run without Python optimization; verification assertions are required")

parser = argparse.ArgumentParser()
parser.add_argument("jar", type=Path)
parser.add_argument("--scan", type=Path, help="Optional unmodified Grype JSON from this JAR's extracted libraries")
parser.add_argument("--scanned-libs", type=Path, help="Exact extracted library directory scanned by Grype")
args = parser.parse_args()
expected = {
    "poi": "5.5.1", "poi-ooxml": "5.5.1", "poi-ooxml-lite": "5.5.1",
    "xmlbeans": "5.3.0", "commons-compress": "1.28.0", "commons-io": "2.21.0",
    "jettison": "1.5.7", "credentials-java": "0.3.11", "okhttp": "4.12.0",
    "okio": "3.6.0", "okio-jvm": "3.6.0", "velocity-engine-core": "2.4",
    "dysmsapi20170525": "3.0.0", "tea": "1.2.7", "tea-openapi": "0.3.4",
    "aliyun-sdk-oss": "3.10.2", "wechatpay-java": "0.2.15",
    "wechatpay-java-core": "0.2.15", "jjwt": "0.9.1", "fastjson2": "2.0.57",
}
with zipfile.ZipFile(args.jar) as jar:
    libraries = [n for n in jar.namelist() if n.startswith("BOOT-INF/lib/") and n.endswith(".jar")]
    assert len(libraries) == len(set(libraries)), "Duplicate packaged library paths refused"
    assert len(libraries) == len({Path(n).name for n in libraries}), "Duplicate library filenames refused"
    entries = {Path(n).name: n for n in libraries}
    for artifact, version in expected.items():
        hits = sorted(n for n in entries if re.fullmatch(re.escape(artifact) + r"-\d.*\.jar", n))
        assert hits == [f"{artifact}-{version}.jar"], (artifact, hits)
    assert not any(n.startswith(("ini4j-", "poi-ooxml-schemas-")) for n in entries)
    velocity_name = "velocity-engine-core-2.4.jar"
    velocity_bytes = jar.read(entries[velocity_name])
    velocity_hash = hashlib.sha256(velocity_bytes).hexdigest()
    assert velocity_hash == "1bf78c2ade46f209bf93ebe72ed2af5b989ca7a1de0a015fc1b92a62f56b6549"
    with zipfile.ZipFile(io.BytesIO(velocity_bytes)) as velocity:
        io_classes = sorted(n for n in velocity.namelist() if n.endswith(".class") and "commons/io/" in n)
        assert io_classes == [], "Velocity 2.4 must not reintroduce shaded Commons IO"
        assert not any("XmlStreamReader" in n for n in velocity.namelist())
    common_name = "kotlin-stdlib-common-1.9.10.jar"
    with zipfile.ZipFile(io.BytesIO(jar.read(entries[common_name]))) as common:
        assert not any(n.endswith(".class") for n in common.namelist()), "Common artifact must remain JVM-class-free metadata"
    for artifact in ("kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8"):
        assert f"{artifact}-2.3.21.jar" in entries
    result = {
        "jarSha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(),
        "packagedLibraries": len(entries), "versions": expected,
        "velocitySha256": velocity_hash, "velocityCommonsIoClasses": io_classes,
        "velocityXmlStreamReaderPresent": False,
        "kotlinCommonJvmClasses": 0,
        "notSecurityClearance": True,
    }
    if args.scan:
        scan = json.loads(args.scan.read_text())
        assert not scan.get("ignoredMatches"), "Keep raw findings; no scanner suppressions are accepted here"
        assert args.scanned_libs, "--scan requires --scanned-libs for byte-level provenance"
        assert scan["source"]["type"] == "directory"
        assert Path(scan["source"]["target"]).resolve() == args.scanned_libs.resolve()
        scanned = {p.name: p for p in args.scanned_libs.iterdir() if p.is_file()}
        assert set(scanned) == set(entries), "Scanned inventory must exactly match BOOT-INF/lib"
        for name, path in scanned.items():
            assert hashlib.sha256(path.read_bytes()).digest() == hashlib.sha256(jar.read(entries[name])).digest(), name
        # Record, but do not alter or suppress, scanner artifact locations.
        for match in scan["matches"]:
            for location in match["artifact"].get("locations", []):
                name = Path(location["path"]).name
                assert name in entries, ("Finding is not from this packaged library inventory", name)
        result["scanSha256"] = hashlib.sha256(args.scan.read_bytes()).hexdigest()
        result["scannerVersion"] = scan["descriptor"]["version"]
        result["rawMatches"] = len(scan["matches"])
        result["rawSeverity"] = dict(collections.Counter(m["vulnerability"]["severity"] for m in scan["matches"]))
        result["ignoredMatches"] = len(scan.get("ignoredMatches", []))
        result["databaseBuilt"] = scan["descriptor"]["db"]["status"]["built"]
        result["findings"] = [{"id": m["vulnerability"]["id"], "artifact": m["artifact"]["name"],
                               "version": m["artifact"]["version"], "locations": m["artifact"]["locations"]}
                              for m in scan["matches"]]
print(json.dumps(result, indent=2))
