#!/usr/bin/env python3
"""Human-selected focus links and policy questions; neither an allowlist nor a SQL parser."""
from pathlib import Path
import hashlib
import json
import subprocess

OUT=Path(__file__).resolve().parent
ROOT=OUT.parents[3]
SOURCE='0c0d61266b2ae54d77c5ad9cf8b3c8fdb86d4b16'
XML='chengyinhub-system/src/main/resources/mapper/business/'
JAVA='chengyinhub-system/src/main/java/com/chengyinhub/business/'
ADMIN='chengyinhub-admin/src/main/java/com/chengyinhub/web/'


def rows(name):return [json.loads(line) for line in (OUT/name).read_text().splitlines()]

def source(path):return subprocess.check_output(['git','show',SOURCE+':'+path],cwd=ROOT)

def main():
    statements={r['key']:r for r in rows('mapper-writes.jsonl')}
    writes=rows('java-write-calls.jsonl')
    reach={r['statement_key']:r for r in rows('statement-entry-candidates.jsonl')}
    groups={
        'withdrawal': {'tables':['member_cash_withdraw','member_cash_withdraw_fund_fact','ums_member_withdrawal','ums_member_balance_detail'],
                       'extra_statements':['UmsMemberMapper.xml#deductWithdrawableBalance','UmsMemberMapper.xml#addBalance','UmsMemberMapper.xml#deductBalance']},
        'registration':{'tables':['cms_registration','cms_registration_merchant','registration_entitlement','oms_ticket'], 'extra_statements':[]},
        'payment_intent_callback':{'tables':['wx_payment_callback_inbox','wx_payment_callback_outbox'],
                       'extra_statements':['CmsRegistrationMapper.xml#claimPaymentProcessing','CmsRegistrationMapper.xml#releasePaymentProcessing','CmsRegistrationMapper.xml#claimPaymentSuccess','CmsRegistrationMapper.xml#claimPaymentFailure']},
        'topic_cash':{'tables':['coop_settlement','coop_settlement_adjustment','coop_settlement_detail','settlement_claim','coop_settlement_cash_close','coop_settlement_cash_event','coop_settlement_cash_contribution'], 'extra_statements':[]},
        'game_receipt_progress':{'tables':['game_command_receipt','game_session','game_story_state','player_node_progress','photo_check_intent','topic_route_session'],'extra_statements':[]},
        'notification_intent':{'tables':['im_notification_intent','im_message'],'extra_statements':[]},
    }
    focus={}
    for name,spec in groups.items():
        keys=[k for k,r in statements.items() if r['literal_write_target'] in spec['tables'] or set(r['table_hints']) & set(spec['tables']) or k in {XML+x for x in spec['extra_statements']}]
        focus[name]={'tables':spec['tables'],'statements':[{ 'key':k,'sha256':statements[k]['sha256'],'namespace':statements[k]['namespace'],
                     'direct_java_calls':[w['id'] for w in writes if k in w['statement_keys']],
                     'entry_method_candidates':reach[k]['entry_method_candidates']} for k in keys],
                     'status':'OBSERVED_SOURCE_NOT_AUTHORIZATION; table groups do not assign business ownership'}
    (OUT/'focus-index.json').write_text(json.dumps({'source_sha':SOURCE,'groups':focus},ensure_ascii=False,indent=2)+'\n')
    # These selected fields were manually read, NOT extracted by a second SQL parser.
    selected=[
        ('UmsMemberMapper.xml#addBalance','ums_member',['balance']),
        ('UmsMemberMapper.xml#deductBalance','ums_member',['balance']),
        ('UmsMemberMapper.xml#deductWithdrawableBalance','ums_member',['balance']),
        ('CmsRegistrationMapper.xml#insertCmsRegistration','cms_registration',['member_id','ticket_id','order_num','point_used','points_returned','payment_status','registration_status','registration_no','parent_order_no']),
        ('TopicEditionFundsMapper.xml#classifyUnknownExplorePurchases','cms_registration',['purchase_kind','update_by','update_time']),
        ('AccountDeregistrationMapper.xml#anonymizeRegistrations','cms_registration',['real_name','phone','phone_hash','email','address','address_id','update_time']),
        ('AccountDeregistrationMapper.xml#anonymizeCashWithdraw','member_cash_withdraw',['openid']),
        ('CoopSettlementDetailMapper.xml#refreshOriginAggregate','coop_settlement',['amount','verified_heads']),
        ('GameSessionRuntimeMapper.xml#completeReceipt','game_command_receipt',['game_session_id','outcome','reason_code','revision','result_json','update_time']),
        ('GameSessionRuntimeMapper.xml#updateAppliedReceiptResult','game_command_receipt',['result_json','update_time']),
        ('ImNotificationIntentMapper.xml#insert','im_notification_intent',['event_key','recipient','msg_type','content','extra_json','request_hash','status','create_time']),
        ('ImNotificationIntentMapper.xml#delivered','im_notification_intent',['status','message_id','delivered_at']),
        ('MemberCashWithdrawMapper.xml#insertFundFact','member_cash_withdraw_fund_fact',['withdrawal_id','member_id','amount','out_batch_no','out_detail_no','kind','submission_intent_id','balance_before','balance_after','create_time']),
        ('MemberCashWithdrawMapper.xml#bindDebitEvidence','member_cash_withdraw',['balance_deduct_id']),
    ]
    observed=[]
    for tail,table,columns in selected:
        key=XML+tail;r=statements[key];raw=source(key.split('#')[0]).decode()
        assert all(c in raw for c in columns)
        observed.append({'statement_key':key,'statement_sha256':r['sha256'],'table':table,'selected_written_columns':columns,
                         'status':'MANUALLY_READ_SELECTED_FIELDS_NOT_COMPLETE_WRITE_SET_OR_APPROVAL',
                         'direct_call_ids':[w['id'] for w in writes if key in w['statement_keys']]})
    (OUT/'manual-field-observations.json').write_text(json.dumps({'source_sha':SOURCE,'observations':observed},ensure_ascii=False,indent=2)+'\n')
    # Evidence needles link exact methods/scopes; source existence does not prove runtime activation.
    evidence={
        ADMIN+'controller/api/ApiWalletController.java':['@RequestMapping','cashWithdrawService.withdraw(memberId, amount)','if (!fastWithdrawEnabled)','getAppUserId()'],
        ADMIN+'controller/api/ApiClubSettlementController.java':['cashWithdrawService.withdraw(memberId, settled)','!memberId.equals(club.getMemberId())'],
        ADMIN+'controller/business/CashWithdrawAdminController.java':["business:cashwithdraw:manage","business:cashwithdraw:freeze"],
        ADMIN+'controller/business/CmsRegistrationController.java':['cmsRegistrationService.insertCmsRegistration(cmsRegistration)',"business:registration:add",'editableRegistrationFields'],
        ADMIN+'controller/api/ApiRegistrationController.java':['getAppUserId()','registrationService.cancelRegistration','wechatPayService.handlePayCallback'],
        ADMIN+'controller/business/TopicEditionFundsController.java':['fundsService.repairUnknownExplorePurchases(topicId)',"business:topic:edit",'scopeAuthorizer.canEditTopic(topicId)'],
        JAVA+'service/impl/CmsRegistrationServiceImpl.java':['cmsRegistrationMapper.insertCmsRegistration(registration)','cmsRegistrationMapper.insertCmsRegistration(r)','systemNotificationIntents.enqueue'],
        JAVA+'service/support/WithdrawalBalanceAdmissionService.java':['insertFundFact','deductWithdrawableBalance'],
        JAVA+'service/impl/WxPaymentCallbackMutationService.java':['mapper.markDone','mutation.apply(row)','@Transactional'],
        JAVA+'service/impl/CmsTopicServiceImpl.java':['coopSettlementMapper.markPaidIfPending','umsMemberMapper.addBalance'],
        JAVA+'service/impl/CoopSettlementClaimServiceImpl.java':['detailMapper.refreshOriginAggregate'],
        JAVA+'service/ai/PhotoCheckIntentService.java':['mapper.insertPrepared','mapper.markApplied','Propagation.MANDATORY'],
        JAVA+'service/impl/ImServiceImpl.java':['notificationIntentMapper.insert','notificationIntentMapper.delivered','enqueueSystemNotification'],
        ADMIN+'controller/api/ApiClubLeadController.java':['commandReceiptMapper.insertReceiptClaim','commandReceiptMapper.completeReceipt','memberId.equals(resolveLeaderMemberId(activity))'],
        ADMIN+'controller/api/ApiPlayProgressController.java':['playCommandReceiptMapper.insertReceiptClaim','playCommandReceiptMapper.completeReceipt','basicIntentScope(memberId'],
        JAVA+'service/impl/GameSessionRuntimeServiceImpl.java':['runtimeMapper.insertReceiptClaim','runtimeMapper.completeReceipt','runtimeMapper.updateAppliedReceiptResult'],
        JAVA+'task/WithdrawTransferQueryTask.java':['cashWithdrawService.syncPendingWithdrawals'],
        JAVA+'task/ImNotificationDeliveryTask.java':['imService.deliverSystemNotification'],
        ADMIN+'task/CoopSettlementPayoutTask.java':['cmsTopicService.payoutDueCoopSettlements'],
        'chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/SecurityConfig.java':['.anyRequest().authenticated()','permitAllUrl.getUrls()'],
        'chengyinhub-quartz/src/main/java/com/chengyinhub/quartz/util/JobInvokeUtil.java':['Class.forName(beanName)','method.invoke(bean'],
        'chengyinhub-quartz/src/main/java/com/chengyinhub/quartz/util/MySqlSessionLock.java':['SELECT DATABASE()','SELECT GET_LOCK(?, ?)','SELECT IS_USED_LOCK(?) = CONNECTION_ID()','SELECT RELEASE_LOCK(?)'],
        'chengyinhub-generator/src/main/java/com/chengyinhub/generator/controller/GenController.java':["@ss.hasRole('admin')",'sqlStatement instanceof MySqlCreateTableStatement','genTableService.createTable'],
        'chengyinhub-generator/src/main/resources/mapper/generator/GenTableMapper.xml':['<update id="createTable">','${sql}'],
        'chengyinhub-admin/sql/migration_fund_withdraw_query_job.sql':['withdrawTransferQueryTask.run()'],
        'chengyinhub-admin/sql/migration_20261023_g_wp08_delivery_jobs_paused.sql':['imNotificationDeliveryTask.deliverPending()'],
        'chengyinhub-admin/sql/migration_20261013_monitor_remaining_jobs.sql':['coopSettlementPayoutTask.run()'],
    }
    anchors=[]
    for path,needles in evidence.items():
        data=source(path);lines=data.decode().splitlines();hits={n:[i for i,l in enumerate(lines,1) if n in l] for n in needles}
        assert all(hits.values()),(path,hits)
        anchors.append({'path':path,'sha256':hashlib.sha256(data).hexdigest(),'anchors':hits,'status':'SOURCE_EXISTENCE_VERIFIED_NOT_RUNTIME'})
    (OUT/'manual-chain-anchors.json').write_text(json.dumps({'source_sha':SOURCE,'files':anchors},ensure_ascii=False,indent=2)+'\n')


if __name__=='__main__':main()
