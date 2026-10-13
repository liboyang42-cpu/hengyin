-- NF-47 商家认领与 NF-02 批量审核命令；仅写迁移文件，本任务不执行生产 SQL。
create table merchant_claim (
  id bigint not null auto_increment comment '认领申请ID',
  merchant_id bigint not null comment 'mms_merchant.id',
  claimant_member_id bigint not null comment '申请人 ums_member.id',
  contact_name varchar(64) not null,
  contact_phone varchar(32) not null,
  claim_statement varchar(1000) null,
  submit_request_id varchar(64) not null comment '申请人提供的稳定幂等键',
  submit_fingerprint char(64) not null,
  status varchar(16) not null comment 'PENDING/APPROVED/REJECTED',
  active_claim_key tinyint generated always as (
    case when status in ('PENDING', 'APPROVED') then 1 else null end
  ) stored comment '活跃态恒为非NULL，终态允许留存历史',
  audit_actor_id bigint null,
  audit_reason varchar(500) null,
  audit_request_id varchar(96) null,
  audit_fingerprint char(64) null,
  version int not null default 0,
  submitted_at datetime not null,
  audited_at datetime null,
  create_time datetime not null,
  update_time datetime not null,
  primary key (id),
  unique key uk_claimant_submit_request (claimant_member_id, submit_request_id),
  unique key uk_merchant_active_claim (merchant_id, active_claim_key),
  key idx_claimant_status (claimant_member_id, status, create_time),
  constraint chk_merchant_claim_status check (status in ('PENDING', 'APPROVED', 'REJECTED'))
) engine=InnoDB default charset=utf8mb4 comment='商家认领申请';

create table merchant_claim_proof (
  id bigint not null auto_increment,
  claim_id bigint not null,
  proof_type varchar(32) not null,
  asset_id varchar(255) not null comment '已有对象存储资源标识，不存客户端任意URL',
  create_time datetime not null,
  primary key (id),
  unique key uk_claim_proof_asset (claim_id, asset_id),
  key idx_claim_proof_claim (claim_id),
  constraint fk_claim_proof_claim foreign key (claim_id) references merchant_claim(id)
) engine=InnoDB default charset=utf8mb4 comment='商家认领证明材料';

create table merchant_claim_batch_command (
  id bigint not null auto_increment,
  actor_id bigint not null,
  request_id varchar(64) not null comment '审核人提供的稳定批次幂等键',
  request_fingerprint char(64) not null,
  action varchar(16) not null,
  create_time datetime not null,
  primary key (id),
  unique key uk_claim_batch_actor_request (actor_id, request_id),
  constraint chk_claim_batch_action check (action in ('APPROVE', 'REJECT'))
) engine=InnoDB default charset=utf8mb4 comment='商家认领批量审核命令';

create table merchant_claim_batch_result (
  id bigint not null auto_increment,
  batch_command_id bigint not null,
  claim_id bigint not null,
  success tinyint(1) not null,
  result_code varchar(64) not null,
  result_message varchar(500) not null,
  current_status varchar(16) null,
  create_time datetime not null,
  primary key (id),
  unique key uk_claim_batch_item (batch_command_id, claim_id),
  key idx_claim_batch_result_claim (claim_id),
  constraint fk_claim_batch_result_command foreign key (batch_command_id) references merchant_claim_batch_command(id)
) engine=InnoDB default charset=utf8mb4 comment='商家认领批量审核逐项结果';
