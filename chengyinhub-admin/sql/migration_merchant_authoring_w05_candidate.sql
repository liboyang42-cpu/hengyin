-- W05 merchant authoring candidate. Not applied to any database by this package.
-- owner+request rejects a second job; fingerprint conflicts are enforced in the service.

create table if not exists w05_authoring_job (
  id bigint auto_increment primary key,
  owner_id bigint not null,
  request_id varchar(64) not null,
  payload_fingerprint char(64) not null,
  merchant_id bigint not null,
  theme_id bigint null,
  draft_id varchar(64) null,
  product_id bigint null,
  sku_id bigint null,
  template_id varchar(64) null,
  fact_version varchar(64) null,
  prompt_version varchar(64) null,
  model_version varchar(64) null,
  cost_ref varchar(64) null,
  output_hash char(64) null,
  error_code varchar(64) null,
  status varchar(16) not null,
  scene varchar(32) null,
  run_id varchar(64) null,
  binding_key varchar(32) null,
  expected_version int null,
  created_at_ms bigint not null,
  updated_at_ms bigint not null,
  unique (owner_id, request_id)
);

create table if not exists w05_authoring_draft (
  id varchar(64) primary key,
  owner_id bigint not null,
  merchant_id bigint not null,
  theme_id bigint null,
  product_id bigint null,
  sku_id bigint null,
  template_id varchar(64) null,
  scene varchar(32) null,
  run_id varchar(64) null,
  binding_key varchar(32) not null,
  fact_version varchar(64) not null,
  current_version int not null,
  created_at_ms bigint not null
);

create table if not exists w05_authoring_revision (
  id bigint auto_increment primary key,
  draft_id varchar(64) not null,
  version int not null,
  body_json text not null,
  body_hash char(64) not null,
  fact_hash char(64) not null,
  content_hash char(64) not null,
  created_by bigint not null,
  created_at_ms bigint not null,
  unique (draft_id, version)
);

create table if not exists w05_authoring_approval (
  id bigint auto_increment primary key,
  draft_id varchar(64) not null,
  revision_id bigint not null,
  revision_version int not null,
  kind varchar(32) not null,
  actor_id bigint not null,
  subject_hash char(64) not null,
  audit_task_id bigint not null,
  status varchar(16) not null,
  created_at_ms bigint not null,
  unique (draft_id, revision_version, kind)
);

create table if not exists w05_authoring_release (
  id bigint auto_increment primary key,
  owner_id bigint not null,
  request_id varchar(64) not null,
  payload_fingerprint char(64) not null,
  draft_id varchar(64) not null,
  revision_id bigint not null,
  revision_version int not null,
  merchant_id bigint not null,
  theme_id bigint null,
  run_id varchar(64) null,
  binding_key varchar(32) not null,
  snapshot_json text not null,
  snapshot_hash char(64) not null,
  fact_hash char(64) not null,
  content_hash char(64) not null,
  status varchar(16) not null,
  visibility varchar(64) null,
  published_at_ms bigint null,
  withdrawn_at_ms bigint null,
  withdraw_request_id varchar(64) null,
  unique (owner_id, request_id)
);

create table if not exists w05_environment_snapshot (
  id bigint auto_increment primary key,
  release_id bigint not null,
  provider varchar(64) null,
  source varchar(64) not null,
  observed_at_ms bigint null,
  expires_at_ms bigint null,
  timezone varchar(64) null,
  accuracy varchar(32) null,
  branch varchar(32) not null,
  weather varchar(32) null,
  action varchar(16) not null,
  store_hours varchar(64) null,
  created_at_ms bigint not null
);
