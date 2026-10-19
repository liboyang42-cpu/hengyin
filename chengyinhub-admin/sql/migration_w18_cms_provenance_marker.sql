-- W18 provenance sentinel only. Sidecar schema and authoritative writer belong to lane 2.
-- No historical backfill: NULL does not certify original ownership.
-- Apply once through the normal reviewed migration process before marker-aware reads.
ALTER TABLE cms_member_template
    ADD COLUMN w18_provenance_kind VARCHAR(32) NULL;
