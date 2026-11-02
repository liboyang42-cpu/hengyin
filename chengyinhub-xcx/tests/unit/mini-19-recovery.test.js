'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const storage = require('../helpers/memory-storage.js');
const writes = require('../../utils/author-write-recovery.js');
const root = path.resolve(__dirname, '../..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
// 执行原方法完整正文；平台能力由桩提供，产品逻辑不复制进测试。
function method(file, name, locals = {}) {
  const lines = read(file).split('\n');
  const re = new RegExp('^(\\s*)' + name + '(?:\\(|:\\s*function\\s*\\()');
  const start = lines.findIndex(line => re.test(line));
  assert.ok(start >= 0, name);
  const indent = re.exec(lines[start])[1];
  for (let end = start + 1; end < lines.length; end++) {
    if (!lines[end].startsWith(indent + '}')) continue;
    try { return vm.runInNewContext('({' + lines.slice(start, end + 1).join('\n') + '\n})', locals)[name]; }
    catch (error) { if (error.name !== 'SyntaxError') throw error; }
  }
  throw Error('Cannot parse complete method ' + name);
}
function host(data, extra = {}) {
  return Object.assign({ data: structuredClone(data), setData(patch, callback) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let target = this.data;
      for (const part of parts.slice(0, -1)) target = target[part] ??= {};
      target[parts.at(-1)] = value;
    }
    if (callback) callback.call(this);
  } }, extra);
}
const detail = 'subpackageMember/components/cy/scene-member-order-detail/index.js';
const play = 'pages/play/index.js';
const login = require('../../utils/session/login-reload.js');
const readers = require('../../utils/user-character.js');
test('R01 招商摘要与发布校验共用空章节条件，关招募或城市定向仍阻断', () => {
  const policy = require('../../pages/publish/utils/publish/pro-editor-policy.js');
  const validation = require('../../pages/publish/utils/publish/publish-validation-bag.js');
  const formData = { productType: 2, name: '夜行', subtitle: '一句话', description: '介绍', imgUrl: '/cover.png',
    categoryIds: '1', recruitDeadline: '2099-01-01', chapters: [{ name: '第一站', nodes: [], recruitEnabled: 1, categoryId: 1, termsMode: 'TRAFFIC', maxMerchant: 0 }], tickets: [] };
  assert.equal(policy.evaluateProfessionalDraft({ formData }).blockingIssues.length, 0);
  const bag = () => validation.buildValidationBag({ formData, selectedCategoryIds: [1], pendingMaterials: [] });
  assert.equal(bag().errors.chapter0, undefined);
  formData.chapters[0].recruitEnabled = 0; assert.ok(bag().errors.chapter0);
  formData.chapters[0].recruitEnabled = 1; formData.productType = 1; assert.ok(bag().errors.chapter0);
});
test('R02 UNKNOWN、在途及确认期间新增请求都不能清空；商家清空后保留scope', () => {
  let prompt, url, removed = 0;
  const p = host({ draftMemberId: 9, editingTopicId: 0, draftUuid: 'd', operationScope: 'MERCHANT', formData: { productType: 2 } },
    { _clearDraftEnvelope() { removed++; return true; } });
  const clear = method('pages/publish/fabu/index.js', 'clearLocalDraft', { app: { getUserID: () => 9 },
    cyToast() {}, modal: { show: value => { prompt = value; } }, wx: { redirectTo: value => { url = value.url; } } });
  p._publishAttempt = {}; clear.call(p); assert.equal(prompt, undefined);
  p._publishAttempt = null; clear.call(p); p._serverDraftAttempt = {}; prompt.success({ confirm: true }); assert.equal(removed, 0);
  p._serverDraftAttempt = null; clear.call(p); prompt.success({ confirm: true }); assert.match(url, /scope=MERCHANT/);
});
function writeHarness() {
  const requests = [], outcomes = [], durable = storage(); let owner = '9';
  const app = { getUserID: () => owner, sendRequest: request => requests.push(request) };
  const options = { app, storage: durable, bucket: 'activity:new', operation: 'ACTIVITY_PUBLISH', endpoint: '/api/activity/publish',
    body: { name: '原正文' }, validResult: value => Number.isSafeInteger(value) && value > 0,
    send: (intent, callbacks) => requests.push({ intent: structuredClone(intent), ...callbacks }),
    onSuccess: result => outcomes.push({ kind: 'success', result }), onRejected: result => outcomes.push({ kind: 'rejected', result }),
    onUnknown: message => outcomes.push({ kind: 'unknown', message }) };
  return { options, requests, outcomes, durable, app, owner: value => { owner = value; } };
}
test('R03 重开先读原回执；UNKNOWN只重放同号原正文；修改输入不能覆盖它', () => {
  const h = writeHarness(); writes.run(h.options); const first = h.requests.shift();
  first.fail(); h.options.body.name = '后续修改'; writes.run(h.options);
  const query = h.requests.shift(); assert.equal(query.url, '/api/author-write/receipt');
  query.success({ code: 200, data: { state: 'UNKNOWN', operation: first.intent.operation, requestId: first.intent.requestId } });
  const replay = h.requests.shift(); assert.deepEqual(replay.intent, first.intent);
  replay.success({ code: 200, data: 81 }); assert.equal(writes.pending(h.durable, '9', h.options.bucket), null);
});
test('R03 FOUND恢复不再写；畸形成功、500及读取失败都保留原记录', () => {
  const h = writeHarness(); writes.run(h.options); const first = h.requests.shift(); first.success({ code: 200, data: null });
  assert.ok(writes.pending(h.durable, '9', h.options.bucket)); writes.run(h.options);
  h.requests.shift().fail(); assert.equal(h.requests.length, 0); writes.run(h.options);
  h.requests.shift().success({ code: 200, data: { state: 'FOUND', operation: first.intent.operation, requestId: first.intent.requestId, response: { code: 200, data: 82 } } });
  assert.equal(h.requests.length, 0); assert.equal(h.outcomes.at(-1).kind, 'success');
});
test('R03 持久化失败不写、换号丢弃旧回包；明确回滚拒绝才释放原号', () => {
  const h = writeHarness(); h.options.storage = { ...storage(), setStorageSync() { throw Error('full'); } };
  writes.run(h.options); assert.equal(h.requests.length, 0);
  h.options.storage = h.durable; writes.run(h.options); const first = h.requests.shift(); h.owner('10');
  first.success({ code: 200, data: 91 }); assert.ok(writes.pending(h.durable, '9', h.options.bucket));
  h.owner('9'); writes.run(h.options); h.requests.shift().success({ code: 200, data: { state: 'UNKNOWN', operation: first.intent.operation, requestId: first.intent.requestId } });
  h.requests.shift().success({ code: 422, msg: '明确拒绝', writeReceipt: { operation: first.intent.operation, requestId: first.intent.requestId, state: 'REJECTED' } });
  assert.equal(writes.pending(h.durable, '9', h.options.bucket), null); assert.equal(h.outcomes.at(-1).kind, 'rejected');
});
test('R04 付A留本页再付B；A完成不能锁B，同笔done仍不可再次提交', () => {
  const requests = [], modals = [], app = { getUserID: () => 9, sendRequest: request => requests.push(request) };
  const checkout = require('../../utils/checkout/checkout-workflow.js');
  const locals = { app, ...checkout, createRegistrationPaymentVerifier: () => () => {}, cyLoading: { hide() {} }, toast() {}, modal: { show: value => modals.push(value) }, wx: {} };
  const p = host({ list: [{ id: 11 }, { id: 22 }] });
  const file = 'subpackageMember/components/cy/scene-member-order-history/index.js';
  for (const name of ['_syncPaymentAccount', '_initWorkflow', '_updateOrderPaid', 'payOrderRequest']) p[name] = method(file, name, locals);
  for (const [id, index] of [[11, 0], [22, 1]]) {
    p.payOrderRequest(id, index); requests.at(-1).success({ code: 200, data: { registrationId: id, payableAmount: 0, paymentStatus: 2, registrationStatus: 2, payParams: null } });
  }
  assert.equal(requests.length, 2); assert.equal(modals.length, 2); p.payOrderRequest(11, 0); assert.equal(requests.length, 2);
});
test('R05 已加载订单遇403清主体，普通断网保留但标注旧数据', () => {
  let request; const app = { getUserID: () => 9, sendRequest: value => { request = value; }, getRequestErrorMessage: (r, fallback) => r.msg || fallback };
  const get = method(detail, 'getData', { app, ...login, ...readers, emptyCompletion: () => ({}) });
  const p = host({ loadState: 'ready', info: { id: 11 }, invoiceRow: { show: true } }, { _orderId: 11 });
  get.call(p, 11); request.fail({ msg: '网络断开' }); assert.equal(p.data.info.id, 11); assert.match(p.data.loadErrorText, /之前/);
  get.call(p, 11); request.fail({ msg: '无权查看' }, 403); assert.equal(Object.keys(p.data.info).length, 0); assert.equal(p.data.invoiceRow.show, false);
});
test('R06 换单立即清发票并作废旧回包；R07旧同意回包不改新单', () => {
  let callback; const app = { getUserID: () => 9 };
  const p = host({ invoiceRow: { show: true, applicationId: '77' }, consentState: 'idle' }, { _orderId: '11', _attached: true, _invoiceKey: 2, _initWorkflow() {}, getData() {} });
  method(detail, '_submitMarketingConsent', { app, ...readers, marketingConsent: { newRequestId: () => 'request-1', optIn: (a, offer, key, cb) => { callback = cb; } } }).call(p, { merchantRowId: 8 });
  method(detail, '_setOrderId', { emptyCompletion: () => ({}) }).call(p, '22');
  assert.equal(p.data.invoiceRow.show, false); assert.ok(p._invoiceKey > 2); callback({ ok: true }); assert.equal(p.data.consentState, 'idle');
});
test('R08 原申诉confirmed时附件不可删', () => {
  const p = host({ auditSheet: { pending: false, confirmed: true, attachments: ['proof'] } }, { saveAuditDraft() {} });
  method('subpackageA/pages/myproject/index.js', 'removeAuditAttachment').call(p, { currentTarget: { dataset: { index: 0 } } });
  assert.deepEqual(p.data.auditSheet.attachments, ['proof']);
});
test('R09 上传B时删A，成功和失败/重试都按同一素材身份回填', () => {
  let upload, retry; const p = host({ certImages: ['A'], certUploading: [], certErrorIndexes: [] }, { validate() {} });
  const app = { chooseImage: (cb, count, options) => { upload = options; }, getUploadClient: () => ({ uploadAll: (files, options) => { retry = options; } }) };
  for (const name of ['_certSlots', '_nextCertSlot', 'addCerts', 'removeCert', 'retryCert']) p[name] = method('pages/club/apply/index.js', name, { app, toast() {} });
  p.addCerts(); upload.onUploadStart(['local-B']); p.removeCert({ detail: { index: 0 } }); upload.onUploadDone({ results: ['remote-B'], failures: [] }, ['local-B']);
  assert.deepEqual(Array.from(p.data.certImages), ['remote-B']);
  p.data.certImages = ['A', 'local-C']; p._certSlotIds = null; p.retryCert({ detail: { index: 1 } }); p.removeCert({ detail: { index: 0 } });
  retry.onDone({ ok: true, results: ['remote-C'] }); assert.deepEqual(Array.from(p.data.certImages), ['remote-C']);
});
test('R10 纠错重开保留原键与原正文；500和空成功不能释放，合法受理才完成', () => {
  const model = require('../../subpackageMember/components/cy/merchant-correction-sheet/model.js');
  const durable = storage(), requests = [], app = { getUserID: () => 9, sendRequest: value => requests.push(value) };
  const file = 'subpackageMember/components/cy/merchant-correction-sheet/index.js';
  const p = host({ description: '门店地址已经变更' }, { properties: { merchantId: '12' }, _key: 'original-key', _issueType: 'ADDRESS', _receiptEpoch: 1, loadTaxonomy() {} });
  const locals = { app, wx: durable, model, isBizOk: r => Number(r.code) === 200, NET_FAIL: '结果未知' };
  const submit = method(file, 'submit', locals); submit.call(p); const first = requests.shift(); first.success({ code: 500 });
  method(file, 'reset', locals).call(p); assert.equal(p._key, 'original-key'); assert.equal(p.data.stage, 'unknown');
  submit.call(p); const retry = requests.shift(); assert.equal(retry.data, first.data); retry.success({ code: 200, data: {} }); assert.equal(p.data.stage, 'unknown');
  submit.call(p); requests.shift().success({ code: 200, data: { id: '51', reviewStatus: 'PENDING' } }); assert.equal(p.data.stage, 'ok'); assert.equal(model.loadPending(durable, '9', '12'), null);
});
test('R11 静默403清消息与邀请，断网保留并可见标记', () => {
  const app = { getUserID: () => 9, getRequestErrorMessage: () => '拒绝' }, p = host({ list: [1], viewList: [1], invitesIn: [1], loaded: true });
  const error = method('subpackageB/pages/im/list/index.js', '_setConversationError', { app, ...login });
  error.call(p, {}, true); assert.equal(p.data.list.length, 1); assert.match(p.data.refreshError, /之前/);
  error.call(p, { code: 403 }, true, 403); assert.equal(p.data.list.length, 0); assert.equal(p.data.invitesIn.length, 0); assert.equal(p.data.loaded, false);
});
test('R12 清除超时/畸形deleted保持未知，明确零条完成，不误删后续对话', () => {
  const h = writeHarness(); h.options.bucket = 'npc-delete'; h.options.operation = 'NPC_CHAT_DELETE'; h.options.body = {};
  h.options.validResult = value => !!value && Number.isSafeInteger(value.deleted) && value.deleted >= 0;
  writes.run(h.options); const first = h.requests.shift(); first.success({ code: 200, data: {} }); assert.equal(h.outcomes.at(-1).kind, 'unknown');
  writes.run(h.options); h.requests.shift().success({ code: 200, data: { state: 'FOUND', operation: first.intent.operation, requestId: first.intent.requestId, response: { code: 200, data: { deleted: 0 } } } });
  assert.equal(h.requests.length, 0); assert.equal(h.outcomes.at(-1).kind, 'success');
});
test('R13 touchcancel丢弃已开始的录音，正常touchend仍发送', () => {
  let stop, sent = 0; const rec = { onStop: cb => { stop = cb; }, onError() {}, offStop() {}, offError() {}, start() {}, stop: () => stop({ duration: 800, tempFilePath: 'voice' }) };
  const p = host({ shopNpc: {} }, { _isShopRecordingCurrent: intent => !intent.cancelled, _sendVoice() { sent++; } });
  const locals = { wx: { getRecorderManager: () => rec } };
  for (const name of ['_beginRecord', '_cancelShopRecording', '_releaseShopRecording', 'onVoiceCancel', 'onVoiceEnd']) p[name] = method(play, name, locals);
  let intent = {}; p._shopRecordingIntent = intent; p._beginRecord(intent); p.onVoiceCancel(); assert.equal(sent, 0);
  intent = {}; p._shopRecordingIntent = intent; p._beginRecord(intent); p.onVoiceEnd(); assert.equal(sent, 1);
  assert.match(read('pages/play/index.wxml'), /bindtouchcancel="onVoiceCancel"/);
});
test('R14 搜索发送keyword，慢到的旧关键词结果与complete均不覆盖当前搜索', () => {
  const requests = [], app = { getPageSize: () => 10, getTotalPage: () => 1, sendRequest: value => requests.push(value) };
  const p = host({ list: [], page_no: 1, ids: '', searchKeyword: '' }); const file = 'pages/publish/components/collaborator-picker/index.js';
  p._getList = method(file, '_getList', { app, isRecordList: Array.isArray }); const search = method(file, 'onSearchInput');
  search.call(p, { detail: { value: 'A' } }); search.call(p, { detail: { value: 'B' } });
  assert.equal(requests[1].data.keyword, 'B'); requests[1].success({ code: 200, data: { rows: [{ id: 2, nickname: 'B' }], total: 1 } });
  requests[0].success({ code: 200, data: { rows: [{ id: 1, nickname: 'A' }], total: 1 } }); requests[0].complete();
  assert.equal(p.data.list[0].nickname, 'B'); assert.equal(p.data.loading, true); requests[1].complete(); assert.equal(p.data.loading, false);
});
test('R15 邀请下一页失败有原页重试，游标不归零', () => {
  const p = host({ hasMore: true, loading: false, loadMoreError: true }, { _pageNo: 2, load: reset => { assert.equal(reset, false); } });
  method('components/cy/scene-member-invite-history/index.js', 'loadMore').call(p); assert.equal(p._pageNo, 2);
  assert.match(read('components/cy/scene-member-invite-history/index.wxml'), /loadMoreError && groups.length && !loading[^>]*bindtap="loadMore"/);
});
test('R16 旁白关闭暂停实际_audio，R17归一化坐标可以导航', () => {
  let paused = 0, opened;
  const p = host({ audioNodeId: 'chapter', audioPlaying: true, nodes: [{ nodeId: 1, lng: 121.4, lat: 31.2 }], hero: { node: { nodeId: 1 } } }, { _audio: { pause() { paused++; } }, playChapterAudio() { throw Error('不应重播'); } });
  method(play, 'toggleStoryAudio').call(p); assert.equal(paused, 1);
  method(play, 'openHeroNav', { wx: { openLocation: value => { opened = value; } }, cyToast() {} }).call(p); assert.equal(opened.longitude, 121.4);
});
test('R18 收齐实际分页才显示统计；加载中、失败后切换不伪造零', () => {
  const requests = [], app = { getUserID: () => 9, sendRequest: value => requests.push(value) }, earningsView = require('../../utils/earnings-view.js');
  const p = host({ state: 'loading', mode: 'income' }); const file = 'subpackageA/pages/assetcenter/analysis/index.js';
  const locals = { app, earningsView, asWithdraw: rows => (rows || []).filter(row => row.eventType === 3) };
  for (const name of ['load', '_render', 'switchMode']) p[name] = method(file, name, locals);
  p.load(); p.switchMode({ currentTarget: { dataset: { mode: 'withdraw' } } }); assert.equal(p.data.state, 'loading');
  const now = new Date(), row = id => ({ id, changeType: 1, eventType: 3, changeBalance: 2, createTime: `${now.getFullYear()}/${now.getMonth() + 1}/1` });
  requests[0].success({ code: 200, data: { rows: [row(1), row(2)], total: 3 } }); assert.equal(p.data.state, 'loading'); assert.equal(requests[1].data.pageNum, 2);
  requests[1].success({ code: 200, data: { rows: [row(3)], total: 3 } }); assert.equal(p.data.current.totalText, '6.00');
  p.load(); requests.at(-1).fail(); p.switchMode({ currentTarget: { dataset: { mode: 'income' } } }); assert.equal(p.data.state, 'error');
});
test('R19 时长由实际播放器回填，未知不写12，旧kit回调不改新目标', () => {
  let canplay, update; const audio = { duration: 7.2, currentTime: 1, onPlay() {}, onPause() {}, onStop() {}, onEnded() {}, onError() {},
    onCanplay: cb => { canplay = cb; }, onTimeUpdate: cb => { update = cb; }, play() {}, pause() {} };
  const p = host({ playKit: { kit: { type: 'scan', audioUrl: '/audio.mp3' } } }, { _kit() { return this.data.playKit.kit; }, _isPlayAccountCurrent: () => true });
  method(play, '_togglePlayKitAudio', { audioSession: { register: () => audio, keepAwake() {} }, wx: { createInnerAudioContext: () => audio }, secureOwnMedia: value => value, cyToast() {} }).call(p, true);
  canplay(); assert.equal(p.data.playKit.kit.audioSeconds, 8); p.data.playKit.kit = { audioUrl: '/other.mp3' }; update(); assert.equal(p.data.playKit.kit.audioSeconds, undefined);
  assert.doesNotMatch(read('pages/play/components/playkit-scan/index.js'), /seconds: 12/);
});
