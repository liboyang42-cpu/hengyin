# Synthetic pre-hardening Excel fixtures

These two files were generated locally using Apache POI 4.1.2 before dependency
changes, at candidate commit dcf2e18dfb5daf228f4359def85ea45eeb15ef01 on 2026-10-02.
They contain only synthetic Chinese merchant labels, a leading-zero phone-like
string, numeric coordinates/member ID, an empty physical row gap, and a formula
with cached value 2. The current tests must consume these exact files instead of
regenerating them using the new library and claiming backward compatibility.

SHA256:
- legacy-poi412.xls: 43a674f8a982e2559a8478b280c7ec1fb2f5cfe7872fac6df57d5fe0758811f0
- legacy-poi412.xlsx: f151dd411845bdcee5a09ea929cabea27776d2b3d1a80de714424fbdd13db1bb

RetainedDependencyGoldenCompatibilityTest ran 5/5 on the unchanged dependencies
before the hardening batch. Resource-boundary fixtures are small in-memory
transformations of the frozen XLSX, not live or third-party documents.
