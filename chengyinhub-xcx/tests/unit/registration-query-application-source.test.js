'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../..');
const controllerPath = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java';
const applicationPath = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/service/registration/RegistrationQueryApplication.java';
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
function method(source, name) {
  const found = new RegExp('^    (?:public|private|static)[^\\n]*\\b' + name + '\\(', 'm').exec(source);
  assert.ok(found, name); const end = source.indexOf('\n    }', found.index);
  assert.ok(end > found.index); return source.slice(found.index, end + 6);
}
function actors(controller) {
  const bindings = {
    getRegistrationDetail: ['memberId', 'id'], exploreCompletion: ['memberId', 'id'],
    getJoinRegistrationDetail: ['memberId', 'member_id'], countByDate: ['principalMemberId', 'owner_type, owner_id'],
    myRegistrationList: ['member_id', 'owner_type, status, is_online'], myJoined: ['memberId', ''],
  };
  for (const [name, [actor, rest]] of Object.entries(bindings)) {
    const body = method(controller, name);
    assert.match(body, new RegExp(actor + ' = getAppUserId\\(\\)'));
    assert.ok(body.includes('registrationQueryApplication.' + name + '(' + actor + (rest ? ', ' + rest : '') + ')'), name);
    assert.doesNotMatch(body, /selectCms|selectRefund|merchantAccessService|countByParticipateDate|isTopicOwner/);
  }
}
function ownedDetail(application) {
  const body = method(application, 'getRegistrationDetail');
  let previous = -1;
  for (const step of ['memberId <= 0', 'registrationService.selectCmsRegistrationById(registrationId)',
    'OwnershipUtils.notOwner(registration.getMemberId(), memberId)', 'topicService.selectCmsTopicById(',
    'TemplateSecrets.strip(memberTemplate)', 'attachOrganizer(registration)', 'attachManualRefundInfo(registration)',
    'latestRegistrationRefund(registration.getId(), memberId)', 'RegistrationDetailDisplay.enrich(registration, topicChapterService)']) {
    const at = body.indexOf(step); assert.ok(at > previous, step); previous = at;
  }
}
function trendScope(application) {
  const body = method(application, 'countByDate');
  for (const fact of ['principalMemberId == null || principalMemberId <= 0', 'MerchantPermission.VERIFY_RECORD_READ',
    'awarded.setMemberId(access.getOwnerMemberId())', 'awarded.setAuditStatus(1L)', 'awarded.setDelFlag(0)',
    '!access.getOwnerMemberId().equals(activity.getMemberId())', 'new AjaxResult(HttpStatus.FORBIDDEN, "无权查看该项目趋势")',
    'catch (ServiceException e)', 'new AjaxResult(e.getCode(), e.getMessage())']) assert.ok(body.includes(fact), fact);
  assert.equal([...body.matchAll(/merchantAccessService\.require\(\s*principalMemberId,\s*MerchantPermission\.VERIFY_RECORD_READ\)/g)].length, 2, 'both topic and activity use the actual current actor permission');
  assert.ok(body.indexOf('merchantAccessService.require(') < body.indexOf('registrationService.countByParticipateDate(ownerType, ownerId)'));
}
// These are source/ownership controls only; Java/MySQL and HTTP verification remain separate.
test('all six real HTTP query adapters pass their actual actor into the application', () => actors(read(controllerPath)));
test('owned detail preserves the ownership gate before PII/template/refund projection', () => ownedDetail(read(applicationPath)));
test('trend retains current operator permission, awarded owner scope and original forbidden mapping', () => trendScope(read(applicationPath)));
test('only the HTTP controller wraps the original list in PageInfo/TableDataInfo without enabling pagination', () => {
  const controller = method(read(controllerPath), 'myRegistrationList'), application = method(read(applicationPath), 'myRegistrationList');
  assert.match(controller, /result\.put\(AjaxResult.DATA_TAG, getDataTable\(\(List<\?>\) result.get\(AjaxResult.DATA_TAG\)\)\)/);
  assert.match(application, /return AjaxResult.success\(list\)/);
  assert.doesNotMatch(read(applicationPath).replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, ''), /getDataTable\(|PageHelper|startPage\(|@Transactional|@Async|CompletableFuture|ApiRegistrationController|getAppUserId/);
  assert.doesNotMatch(controller, /\bstartPage\(\)/);
});
test('list and joined queries retain different filtering and the original refund reader', () => {
  const application = read(applicationPath);
  const list = method(application, 'myRegistrationList').replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, '');
  assert.doesNotMatch(list, /filterEndedOverWeek\(/);
  assert.match(method(application, 'myJoined'), /all = filterEndedOverWeek\(all, new Date\(\)\)/);
  assert.match(method(application, 'latestRegistrationRefunds'), /query.setMemberId\(memberId\)/);
  assert.match(method(application, 'attachOrganizer'), /registration.setOrganizerName\(organizer.getNickname\(\)\)/);
});
test('application leaf calls remain the exact existing read ports without QR/init/refund execution', () => {
  const application = read(applicationPath);
  const permitted = new Set(['selectCmsRegistrationById', 'selectCmsRegistrationList', 'countByParticipateDate',
    'selectCmsTopicById', 'selectCmsActivityById', 'selectOmsTicketById', 'selectOmsTicketList',
    'selectCmsRegistrationScanList', 'selectCmsRegistrationMerchantList', 'selectCmsMemberTemplateById',
    'selectMmsMerchantByMemberId', 'selectSysCategoryListByIds', 'listEntitlements', 'selectUmsMemberById',
    'selectRefundList', 'attachManualRefundInfo', 'read', 'isTopicOwner', 'require', 'selectEffectiveOrdersByTopicId']);
  const calls = [...application.matchAll(/\b\w+(?:Service|Mapper)\.(\w+)\(/g)].map(match => match[1]);
  assert.ok(calls.length >= 20); for (const call of calls) assert.ok(permitted.has(call), call);
});
test('negative controls reject changed HTTP actor and deleted owned-detail guard', () => {
  const controller = read(controllerPath), app = read(applicationPath);
  const changedActor = controller.replace('getRegistrationDetail(memberId, id)', 'getRegistrationDetail(Convert.toLong(id), id)');
  assert.notEqual(changedActor, controller); assert.throws(() => actors(changedActor), assert.AssertionError);
  const noGuard = app.replace('OwnershipUtils.notOwner(registration.getMemberId(), memberId)', 'false');
  assert.notEqual(noGuard, app); assert.throws(() => ownedDetail(noGuard), assert.AssertionError);
});
test('negative control rejects deletion of the current merchant permission even when trend SQL remains', () => {
  const application = read(applicationPath), noPermission = application.replace('MerchantPermission.VERIFY_RECORD_READ', 'MerchantPermission.VERIFY');
  assert.notEqual(noPermission, application); assert.throws(() => trendScope(noPermission), assert.AssertionError);
});
