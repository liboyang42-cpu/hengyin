const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { createGameSessionClient } = require('../../utils/game-session-client.js');
const { createClubGameDirectorAdapter, genericOpsActions, genericOpsFields, genericOpsPayload } = require('../../pages/club/utils/club-game-director-adapter.js');

function club() {
  return { status: 'ready', data: { sessionId: 31, activityId: 71, perspective: 'CLUB', status: 'RUNNING', revision: 9, authorityEpoch: 2,
    availableActions: ['FINISH'], club: { playMode: 'ROUTE', readiness: {}, teams: [], stations: [] } } };
}
function control(actions) {
  return { run: { operationStatus: 'RUNNING', authorityMode: 'ONLINE', authorityEpoch: 2, eventSeq: 3, bindings: {}, openedActs: {}, exitedTeams: {} },
    revision: 9, teams: [{ teamId: 81, player: { hp: 2, gameplay: { actIndex: 0 } }, ownerRevision: 4, exited: false }], stations: [], availableActions: actions };
}
const guideControl = { guideTasks: [{ questKey: 'quest.1', nodeId: 11, basic: { mode: 'TYPE', options: [] } }] };

test('shared/control matches the actual serializer without an invented sessionId or runtimeScope', async () => {
  const calls = [];
  const client = createGameSessionClient({ sendRequest(options) { calls.push(options); options.success({ code: 200, data: control(['ACT_UNLOCK']) }); } });
  const result = await client.loadSharedControl(71);
  assert.equal(result.status, 'ready');
  assert.deepEqual(calls[0].data, { activityId: 71 });
  assert.equal(calls[0].url, '/api/game/session/shared/control');
  assert.equal(calls[0].method, 'GET');
});

test('ROUTE staff panel requires a successful TEAM control view; STORY never asks for it', async () => {
  let reads = 0;
  const raw = club();
  const adapter = createClubGameDirectorAdapter({ loadProjection: async () => raw,
    loadSharedControl: async () => { reads += 1; return { status: 'ready', data: control(['RUN_PAUSE']) }; } });
  assert.equal((await adapter.load(71)).genericControl.run.authorityEpoch, 2);
  raw.data.club.playMode = 'STORY';
  assert.equal((await adapter.load(71)).genericControl, null);
  assert.equal(reads, 1);
});

test('absent actions fail closed and unlisted arrival attest is never synthesized', () => {
  assert.deepEqual(genericOpsActions(control(undefined)), []);
  assert.deepEqual(genericOpsActions(control([])), []);
  assert.deepEqual(genericOpsActions(control(['ACT_UNLOCK', 'UNKNOWN', 'MANUAL_SIGN'])).map(row => row.id), ['ACT_UNLOCK']);
  assert.equal(genericOpsActions(control(['STAFF_MANUAL_SIGN'])).some(row => row.id === 'ARRIVAL_ATTEST'), false);
});

const cases = [
  ['ACT_UNLOCK', { actKey: 'act.2', reason: '开放第二幕' }, { actKey: 'act.2', reason: '开放第二幕' }],
  ['ARRIVAL_CONFIRM', { teamId: '81', questKey: 'quest.1', nonce: 'n'.repeat(43) }, { teamId: 81, questKey: 'quest.1', nonce: 'n'.repeat(43) }],
  ['ARRIVAL_REVOKE', { teamId: '81', operationKind: 'FACILITY', operationKey: 'tool.1', reason: '核验失效' }, { teamId: 81, operationKind: 'FACILITY', operationKey: 'tool.1', reason: '核验失效' }],
  ['QUEST_GUIDE', { teamId: '81', questKey: 'quest.1', reason: '按现场规则指导', guide_basic: '队伍口令' }, { teamId: 81, questKey: 'quest.1', reason: '按现场规则指导', answers: { answer: '队伍口令' } }],
  ['LOCATION_REBIND', { questKey: 'quest.1', nodeId: '91', reason: '转移到已批准备用站' }, { questKey: 'quest.1', nodeId: 91, reason: '转移到已批准备用站' }],
  ['STAFF_TEAM_EXIT', { teamId: '81', reason: '队伍申请退出' }, { teamId: 81, reason: '队伍申请退出' }],
  ...['RUN_PAUSE', 'RUN_RESUME', 'RUN_INTERRUPT', 'RUN_CANCEL', 'AUTHORITY_RESUME'].map(action => [action, { reason: '现场操作说明' }, { reason: '现场操作说明' }]),
  ['MANUAL_BEGIN', { highWaterHash: 'a'.repeat(64), configHash: 'b'.repeat(64), unknownRequests: '[]', reason: '改用人工账' },
    { highWaterHash: 'a'.repeat(64), configHash: 'b'.repeat(64), unknownRequests: [], reason: '改用人工账' }],
  ['MANUAL_IMPORT', { signatureId: '62' }, { signatureId: '62' }],
  ['MANUAL_REVIEW', { reviewKind: 'HIGH_WATER', reason: '已核对高水位' }, { acceptCurrentHighWater: true, reason: '已核对高水位' }],
  ['MANUAL_REVIEW', { reviewKind: 'SIGNATURE', signatureId: '62', reason: '驳回该签名' }, { signatureId: '62', reason: '驳回该签名' }],
  ['MANUAL_REVIEW', { reviewKind: 'REQUEST', memberId: '99', requestId: 'game_original', acceptLateConflict: true, reason: '明确核对迟到冲突' },
    { memberId: 99, requestId: 'game_original', acceptLateConflict: true, reason: '明确核对迟到冲突' }],
  ['STAFF_MANUAL_SIGN', { sequence: '1', occurredAt: '1791577800000', signedAction: 'ARRIVAL_ATTEST', teamId: '81', questKey: 'quest.1', reason: '纸账现场到达' },
    { sequence: 1, occurredAt: 1791577800000, command: { action: 'ARRIVAL_ATTEST', payload: { teamId: 81, questKey: 'quest.1', reason: '纸账现场到达' } } }],
];
for (const [action, draft, expected] of cases) test(`${action} exact whitelisted body ${draft.reviewKind || ''}`, () => {
  assert.deepEqual(genericOpsPayload(action, Object.assign({ ownerId: 1000, balance: 999 }, draft), guideControl), expected);
});

const options = [{ id: 'a', label: '甲' }, { id: 'b', label: '乙' }];
const guideCases = [
  ['basic text', { basic: { mode: 'TYPE', options: [] } }, { guide_basic: '口令' }, { answer: '口令' }],
  ['basic pick', { basic: { mode: 'PICK', options: [{ id: 'A', label: '甲' }, { id: 'B', label: '乙' }] } }, { guide_basic_0: true, guide_basic_1: true }, { answer: 'AB' }],
  ['qa text', { qa: { mode: 'TYPE', title: '口令', options: [] } }, { guide_qa: '口令' }, { qa: { input: '口令' } }],
  ['qa pick', { qa: { mode: 'PICK', title: '选择口令', options } }, { guide_qa: 'b' }, { qa: { optionId: 'b' } }],
  ['qa multi', { qa: { mode: 'PICK', multi: true, title: '选择口令', options } }, { guide_qa_0: true, guide_qa_1: true }, { qa: { optionIds: ['a', 'b'] } }],
  ['sort', { sort: { prompt: '排列', items: options } }, { guide_sort_0: 'b', guide_sort_1: 'a' }, { sort: { order: ['b', 'a'] } }],
  ['match', { match: { prompt: '连线', left: options, right: [{ id: 'x', label: '一' }, { id: 'y', label: '二' }] } }, { guide_match_0: 'y', guide_match_1: 'x' }, { match: { pairs: [['a', 'y'], ['b', 'x']] } }],
  ['classify', { classify: { prompt: '分类', items: options, bins: [{ id: 'x', label: '一' }, { id: 'y', label: '二' }] } }, { guide_classify_0: 'x', guide_classify_1: 'x' }, { classify: { placement: { a: 'x', b: 'x' } } }],
];
for (const [kind, questions, answersDraft, answers] of guideCases) for (const nested of [false, true]) test(`guided ${kind}, manual=${nested}, exact server answer payload`, () => {
  const snapshot = { guideTasks: [Object.assign({ questKey: 'q1', nodeId: 11 }, questions)] };
  const draft = Object.assign({ teamId: '81', questKey: 'q1', reason: '记录队伍实际作答', passed: true, answerKey: 'forbidden', answers: { passed: true } }, answersDraft);
  const expected = { teamId: 81, questKey: 'q1', reason: draft.reason, answers };
  const action = nested ? 'STAFF_MANUAL_SIGN' : 'QUEST_GUIDE';
  if (nested) Object.assign(draft, { signedAction: 'QUEST_GUIDE', sequence: 1, occurredAt: 1791577800000 });
  assert.deepEqual(genericOpsPayload(action, draft, snapshot), nested
    ? { sequence: 1, occurredAt: 1791577800000, command: { action: 'QUEST_GUIDE', payload: expected } } : expected);
  assert.ok(genericOpsFields(action, draft, snapshot).some(field => field.key.startsWith('guide_')));
  for (const key of Object.keys(answersDraft)) {
    const incomplete = Object.assign({}, draft); delete incomplete[key];
    if (questions.basic && questions.basic.mode === 'PICK' || questions.qa && questions.qa.multi) {
      for (const answerKey of Object.keys(answersDraft)) delete incomplete[answerKey];
    }
    assert.throws(() => genericOpsPayload(action, incomplete, snapshot), /完整录入/);
  }
  assert.throws(() => genericOpsPayload(action, draft, {}), /读取本局题型/);
});

test('duplicate sort/match choices and unknown option ids never reach a write', () => {
  for (const kind of ['sort', 'match', 'classify', 'qa pick']) {
    const fixture = guideCases.find(row => row[0] === kind);
    const snapshot = { guideTasks: [Object.assign({ questKey: 'q1', nodeId: 11 }, fixture[1])] };
    const draft = Object.assign({ teamId: 81, questKey: 'q1', reason: '实际核验' }, fixture[2]);
    const keys = Object.keys(fixture[2]);
    draft[keys[0]] = 'unknown';
    assert.throws(() => genericOpsPayload('QUEST_GUIDE', draft, snapshot), /完整录入/);
    if (kind === 'sort' || kind === 'match') {
      draft[keys[0]] = draft[keys[1]];
      assert.throws(() => genericOpsPayload('QUEST_GUIDE', draft, snapshot), /重复选择/);
    }
  }
});

test('every enabled objective in one group is collected rather than just the first question', () => {
  const questions = {}, draft = { teamId: 81, questKey: 'q1', reason: '实际核验' }, expected = {};
  for (const kind of ['qa pick', 'sort', 'match', 'classify']) {
    const fixture = guideCases.find(row => row[0] === kind);
    Object.assign(questions, fixture[1]); Object.assign(draft, fixture[2]); Object.assign(expected, fixture[3]);
  }
  assert.deepEqual(genericOpsPayload('QUEST_GUIDE', draft, { guideTasks: [Object.assign({ questKey: 'q1', nodeId: 11 }, questions)] }).answers, expected);
});

test('required reason, exclusive target and controlled signer are validated before a write', () => {
  assert.throws(() => genericOpsPayload('RUN_CANCEL', { reason: '' }), /原因/);
  assert.throws(() => genericOpsPayload('RUN_PAUSE', { reason: '长'.repeat(201) }), /原因/);
  assert.throws(() => genericOpsPayload('ARRIVAL_CONFIRM', { teamId: '81', questKey: 'quest.1', operationKind: 'FACILITY', operationKey: 'tool.1', nonce: 'abc' }), /任务或操作/);
  assert.throws(() => genericOpsPayload('MANUAL_BEGIN', { highWaterHash: 'a'.repeat(64), configHash: 'b'.repeat(64), unknownRequests: '[{"memberId":99,"requestId":"x","requestHash":"invalid"}]', reason: '接人工账' }), /待核对请求/);
  assert.throws(() => genericOpsPayload('STAFF_MANUAL_SIGN', { signedAction: 'ITEM_USE', sequence: 1, occurredAt: 1 }), /核验/);
});

test('authority conflict is preserved and unknown / replay receipt uses the original member-scoped request', async () => {
  const calls = [];
  const client = createGameSessionClient({ sendRequest(options) { calls.push(options); } });
  const adapter = createClubGameDirectorAdapter(client);
  const loaded = adapter.load(71);
  calls[0].success({ code: 200, data: club().data });
  await new Promise(resolve => setImmediate(resolve));
  calls[1].success({ code: 200, data: control(['RUN_PAUSE']) });
  await loaded;
  const command = { activityId: 71, expectedRevision: 9, authorityEpoch: 2, requestId: 'game_original', action: 'RUN_PAUSE', payload: { reason: '暂停核对' } };
  const conflicted = adapter.submit(command);
  calls[2].success({ code: 500, msg: '权威已切换', data: { reasonCode: 'GAME_AUTHORITY_CONFLICT' } });
  assert.equal((await conflicted).raw.reasonCode, 'GAME_AUTHORITY_CONFLICT');
  const pending = adapter.submit(command);
  calls[3].fail();
  assert.equal((await pending).state, 'unknown-write');
  assert.equal(calls[3].data.authorityEpoch, 2);
  assert.equal(calls[3].data.ownerId, undefined);
  const receipt = adapter.readReceipt(71, command.requestId, command);
  assert.deepEqual(calls[4].data, { activityId: 71, requestId: 'game_original' });
  calls[4].success({ code: 200, data: { receiptId: 63, activityId: 71, requestId: 'game_original', action: 'RUN_PAUSE', outcome: 'APPLIED', revision: 10 } });
  assert.equal((await receipt).state, 'confirmed');
});

function pageHarness(adapter, suppliedStorage, realLoad) {
  const modals = [], toasts = [], storage = suppliedStorage || {}, exported = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../pages/club/topic-detail/director.js'), 'utf8'), {
    module: exported, Date, Promise, JSON, setTimeout, clearTimeout,
    getApp: () => ({ getUserID: () => 99 }),
    require(file) {
      if (file.endsWith('club-game-director-adapter.js')) return require('../../pages/club/utils/club-game-director-adapter.js');
      if (file.endsWith('datetime.js')) return require('../../utils/datetime.js');
      if (file.endsWith('modal.js')) return { show: options => modals.push(options) };
      if (file.endsWith('toast.js')) return Object.assign(message => toasts.push(message), { success(message) { toasts.push(message); } });
      throw new Error(file);
    },
    wx: { getStorageSync: key => storage[key], setStorageSync: (key, value) => { storage[key] = value; }, removeStorageSync: key => { delete storage[key]; } },
  });
  const page = Object.assign({}, exported.exports.DIRECTOR_METHODS, { data: JSON.parse(JSON.stringify(exported.exports.DIRECTOR_DATA)),
    _activityId: 71, _directorAdapter: adapter, setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback(); } });
  page.applyProjection({ revision: 9, authorityEpoch: 2, status: 'RUNNING', genericControl: control(['RUN_CANCEL', 'ARRIVAL_CONFIRM', 'STAFF_MANUAL_SIGN', 'MANUAL_BEGIN']) });
  if (!realLoad) page.loadProjection = () => { page.loads = (page.loads || 0) + 1; };
  return { page, modals, storage, toasts };
}
const tick = () => new Promise(resolve => setImmediate(resolve));

test('irreversible command requires confirmation; epoch change between modal and submit blocks the write', () => {
  let writes = 0;
  const { page, modals } = pageHarness({ submit: async () => { writes += 1; } });
  page.openGenericOps({ currentTarget: { dataset: { action: 'RUN_CANCEL' } } });
  page.inputGenericOps({ currentTarget: { dataset: { key: 'reason' } }, detail: { value: '停止现场活动' } });
  assert.equal(page.confirmGenericOps(), true);
  assert.equal(writes, 0);
  assert.match(modals[0].content, /不可撤销/);
  page.data.authorityEpoch = 3;
  modals[0].success({ confirm: true });
  assert.equal(writes, 0);
  assert.match(page.data.genericOpsError, /已变化/);
});

for (const replayed of [false, true]) test(`unknown/replayed=${replayed} automatically reads the original receipt without mutating run state`, async () => {
  const commands = [], reads = [];
  const { page, modals, storage } = pageHarness({
    submit: async command => { commands.push(command); return replayed
      ? { state: 'confirmed', raw: { receipt: { replayed: true } } } : { state: 'unknown-write' }; },
    readReceipt: async (activityId, requestId, command) => { reads.push({ activityId, requestId, command }); return { state: 'pending' }; },
  });
  page.openGenericOps({ currentTarget: { dataset: { action: 'RUN_CANCEL' } } });
  page.data.genericOpsDraft.reason = '停止现场活动';
  page.confirmGenericOps(); modals[0].success({ confirm: true }); await tick();
  assert.equal(commands.length, 1); assert.equal(reads.length, 1);
  assert.equal(commands[0].authorityEpoch, 2); assert.equal(commands[0].expectedRevision, 9);
  assert.equal(reads[0].requestId, commands[0].requestId);
  assert.equal(page._genericControl.run.operationStatus, 'RUNNING');
  assert.equal(page.data.writeLocked, true);
  assert.doesNotMatch(JSON.stringify(storage), /停止现场活动|nonce|payload/);
  page.retryUnknownWrite(); await tick();
  assert.equal(commands.length, 1); assert.equal(reads.length, 2);
});

test('confirmed generic writes refresh authority and do not optimistically alter the run', async () => {
  const { page, modals } = pageHarness({ submit: async () => ({ state: 'confirmed' }) });
  page.openGenericOps({ currentTarget: { dataset: { action: 'RUN_CANCEL' } } });
  page.data.genericOpsDraft.reason = '停止现场活动'; page.confirmGenericOps(); modals[0].success({ confirm: true }); await tick();
  assert.equal(page.data.writeLocked, false); assert.equal(page.loads, 1);
  assert.equal(page._genericControl.run.operationStatus, 'RUNNING');
});

test('manual form reads server fingerprints/sequence, omits proofs and preserves exact signed action body', async () => {
  const snapshot = { revision: 9, authorityEpoch: 2, highWaterHash: 'a'.repeat(64), configHash: 'b'.repeat(64),
    manual: { nextSequence: 4, signatures: { '88': { sequence: 3, used: false, command: { action: 'ARRIVAL_ATTEST', payload: { nonce: 'private-proof' } } } }, unknownRequests: [] } };
  const { page } = pageHarness({ loadSharedExport: async () => snapshot });
  page.openGenericOps({ currentTarget: { dataset: { action: 'MANUAL_BEGIN' } } }); await tick();
  assert.equal(page.data.genericOpsDraft.highWaterHash, snapshot.highWaterHash);
  page.openGenericOps({ currentTarget: { dataset: { action: 'STAFF_MANUAL_SIGN' } } }); await tick();
  assert.equal(page.data.genericOpsDraft.sequence, 4);
  assert.doesNotMatch(JSON.stringify(page.data.genericOpsLedgerRows), /private-proof|nonce/);
  const payload = genericOpsPayload('STAFF_MANUAL_SIGN', { sequence: 4, occurredAt: '2026-10-09 14:30:00', signedAction: 'ARRIVAL_ATTEST', teamId: 81, questKey: 'quest.1', reason: '现场核验' });
  assert.equal(payload.occurredAt, Date.UTC(2026, 9, 9, 6, 30));
  assert.equal(payload.command.action, 'ARRIVAL_ATTEST');
  assert.equal(payload.reason, undefined);
});

test('unlisted and legacy operations cannot open the generic form; STORY chapter unlock remains independent', () => {
  const { page } = pageHarness({});
  page.openGenericOps({ currentTarget: { dataset: { action: 'ARRIVAL_ATTEST' } } });
  assert.equal(page.data.genericOpsSheet, false);
  page.applyProjection({ status: 'RUNNING', revision: 9, availableActions: ['UNLOCK_CHAPTER'], chapterOptions: [{ chapterId: 5, title: '第二章' }] });
  assert.equal(page.data.genericOpsKnown, false); assert.equal(page.data.canUnlockChapter, true);
  page.openGenericOps({ currentTarget: { dataset: { action: 'RUN_CANCEL' } } });
  assert.equal(page.data.genericOpsSheet, false);
});

test('staff control rejects malformed epochs, export refuses another activity, and owner scope is required', async () => {
  let body = control(['ACT_UNLOCK']); body.run.authorityEpoch = 0;
  const client = createGameSessionClient({ sendRequest(options) { options.success({ code: 200, data: body }); } });
  assert.equal((await client.loadSharedControl(71)).status, 'business-error');
  body = { sessionId: 31, activityId: 72, revision: 9, authorityEpoch: 2, configHash: 'a'.repeat(64), highWaterHash: 'b'.repeat(64) };
  assert.equal((await client.loadSharedExport(71)).status, 'business-error');
  await assert.rejects(createClubGameDirectorAdapter(client).loadSharedExport(71), /所属俱乐部/);
});

for (const action of ['ARRIVAL_CONFIRM', 'QUEST_GUIDE']) test(`staff paper ${action} serializes only a controlled nested command plus optional original request`, () => {
  const draft = { signedAction: action, sequence: 4, occurredAt: '2026-10-09 14:30:00', teamId: 81, questKey: 'quest.1',
    nonce: 'n'.repeat(43), reason: '本人现场核验', originalRequestId: 'game_original', originalExpectedRevision: 8, guide_basic: '口令' };
  const result = genericOpsPayload('STAFF_MANUAL_SIGN', draft, guideControl);
  assert.deepEqual(result.command, { action, payload: action === 'ARRIVAL_CONFIRM'
    ? { teamId: 81, questKey: 'quest.1', nonce: 'n'.repeat(43) } : { teamId: 81, questKey: 'quest.1', reason: '本人现场核验', answers: { answer: '口令' } } });
  assert.equal(result.originalRequestId, 'game_original'); assert.equal(result.originalExpectedRevision, 8);
  assert.throws(() => genericOpsPayload('STAFF_MANUAL_SIGN', Object.assign({}, draft, { originalRequestId: '' }), guideControl), /一起填写/);
});

for (const nested of [false, true]) test(`missing guided answers disable confirmation and never write, manual=${nested}`, () => {
  const { page, modals } = pageHarness({ loadSharedExport: async () => ({ revision: 9, authorityEpoch: 2 }) });
  const action = nested ? 'STAFF_MANUAL_SIGN' : 'QUEST_GUIDE';
  page._genericControl = Object.assign(control([action]), guideControl);
  page.openGenericOps({ currentTarget: { dataset: { action } } });
  const input = (key, value) => page.inputGenericOps({ currentTarget: { dataset: { key } }, detail: { value } });
  if (nested) { input('signedAction', 2); input('sequence', '1'); input('occurredAt', '1791577800000'); }
  input('questKey', 0); input('teamId', '81'); input('reason', '实际核验');
  assert.equal(page.data.genericOpsReady, false);
  assert.equal(page.confirmGenericOps(), false);
  assert.equal(modals.length, 0);
  input('guide_basic', '队伍口令');
  assert.equal(page.data.genericOpsReady, true);
  assert.equal(page.confirmGenericOps(), true);
  assert.equal(modals.length, 1);
  input('questKey', 0);
  assert.equal(page.data.genericOpsReady, false);
  assert.equal(page.data.genericOpsDraft.guide_basic, undefined);
  assert.match(fs.readFileSync(path.join(__dirname, '../../pages/club/topic-detail/index.wxml'), 'utf8'), /aria-disabled="\{\{writeLocked \|\| !genericOpsReady\}\}"/);
});

test('manual begin uses the server anchor instead of editable hashes/time and preserves original request fingerprints', () => {
  const unknown = [{ memberId: 99, requestId: 'game_original', requestHash: 'c'.repeat(64) }];
  const snapshot = control(['MANUAL_BEGIN']);
  snapshot.offlineAnchor = { highWaterHash: 'a'.repeat(64), configHash: 'b'.repeat(64), epoch: 2, at: Date.UTC(2026, 9, 9, 6, 30) };
  const draft = { highWaterHash: 'd'.repeat(64), configHash: 'e'.repeat(64), unknownRequests: JSON.stringify(unknown), offlineStartedAt: '1999-01-01 00:00:00', reason: '现场改用纸账' };
  const result = genericOpsPayload('MANUAL_BEGIN', draft, snapshot);
  assert.deepEqual(result.unknownRequests, unknown);
  assert.equal(result.offlineStartedAt, Date.UTC(2026, 9, 9, 6, 30));
  assert.equal(result.highWaterHash, snapshot.offlineAnchor.highWaterHash);
  assert.equal(result.configHash, snapshot.offlineAnchor.configHash);
  assert.ok(genericOpsFields('MANUAL_BEGIN', draft, snapshot).every(field => !['highWaterHash', 'configHash', 'offlineStartedAt'].includes(field.key)));
  snapshot.offlineAnchor.epoch = 1;
  assert.throws(() => genericOpsPayload('MANUAL_BEGIN', draft, snapshot), /失效/);
  delete snapshot.offlineAnchor;
  assert.throws(() => genericOpsPayload('MANUAL_BEGIN', draft, snapshot), /先开始离线记录/);
  assert.equal(result.memberId, undefined);
});

test('offline start is listed only with the explicit server availability flag', () => {
  for (const available of [undefined, false, 'true']) {
    assert.equal(genericOpsActions(Object.assign(control([]), { offlineAnchorAvailable: available })).some(row => row.id === 'OFFLINE_ANCHOR'), false);
  }
  assert.equal(genericOpsActions(Object.assign(control([]), { offlineAnchorAvailable: true }))[0].title, '开始离线记录');
});

const offlineAnchor = { epoch: 2, at: 1791577800000, highWaterHash: 'a'.repeat(64), configHash: 'b'.repeat(64), revision: 9, eventSeq: 3 };
test('offline start uses the exact JSON author-scoped endpoint body without client-made hashes or command receipts', async () => {
  const requests = [];
  const client = createGameSessionClient({ sendRequest(options) { requests.push(options); options.success({ code: 200,
    data: { activityId: 71, sessionId: 31, authorityEpoch: 2, highWaterHash: offlineAnchor.highWaterHash, configHash: offlineAnchor.configHash, anchor: offlineAnchor } }); } });
  assert.equal((await client.anchorSharedRun(71)).status, 'ready');
  assert.equal(requests[0].url, '/api/game/session/shared/export/anchor');
  assert.equal(requests[0].method, 'POST');
  assert.deepEqual(requests[0].data, { activityId: 71 });
  assert.equal(requests[0].header['content-type'], 'application/json');
  assert.equal(requests[0].retry, 0);
  assert.equal(requests[0].autoErrorToast, false);
  await assert.rejects(createClubGameDirectorAdapter(client).anchorSharedRun(71), /所属俱乐部/);
});

for (const callback of ['fail', 'successStatusAbnormal']) test('offline start '+callback+' becomes unknown without any automatic POST retry', async () => {
  let writes = 0;
  const client = createGameSessionClient({ sendRequest(options) { writes++; options[callback]({}); } });
  assert.equal((await client.anchorSharedRun(71)).status, 'unknown-write');
  assert.equal(writes, 1);
});

test('offline start rejects malformed or mismatched server anchors as unknown', async () => {
  for (const change of [{ epoch: 1 }, { at: 0 }, { configHash: 'c'.repeat(64) }, { highWaterHash: 'c'.repeat(64) }]) {
    const client = createGameSessionClient({ sendRequest(options) { options.success({ code: 200, data: {
      activityId: 71, sessionId: 31, authorityEpoch: 2, highWaterHash: offlineAnchor.highWaterHash,
      configHash: offlineAnchor.configHash, anchor: Object.assign({}, offlineAnchor, change)
    } }); } });
    assert.equal((await client.anchorSharedRun(71)).status, 'unknown-write');
  }
});

test('offline malformed responses and untyped server errors remain unknown; typed game rejection is definite', async () => {
  for (const body of [undefined, null, {}, { code: 500, msg: '游戏服务暂时不可用' }, { code: 500, data: { reasonCode: 'OTHER_ERROR' } }]) {
    const client = createGameSessionClient({ sendRequest(options) { options.success(body); } });
    assert.equal((await client.anchorSharedRun(71)).status, 'unknown-write');
  }
  const client = createGameSessionClient({ sendRequest(options) { options.success({ code: 500, msg: '当前不可开始离线记录', data: { reasonCode: 'GAME_MANUAL_UNRESOLVED' } }); } });
  const response = await client.anchorSharedRun(71);
  assert.equal(response.status, 'business-error');
  assert.equal(response.reasonCode, 'GAME_MANUAL_UNRESOLVED');
});

test('offline start confirms first, unknown reads back only, and a current server anchor unlocks writes', async () => {
  let writes = 0, reads = 0;
  const current = Object.assign(control([]), { offlineAnchorAvailable: true });
  const { page, modals } = pageHarness({ anchorSharedRun: async () => { writes++; return { status: 'unknown-write' }; },
    load: async () => { reads++; return { revision: 9, authorityEpoch: 2, status: 'RUNNING', genericControl: current }; } });
  page._genericControl = current;
  page.openGenericOps({ currentTarget: { dataset: { action: 'OFFLINE_ANCHOR' } } });
  assert.equal(writes, 0); assert.equal(modals[0].title, '开始离线记录');
  modals[0].success({ confirm: true }); await tick();
  assert.equal(writes, 1); assert.equal(reads, 1); assert.equal(page.data.writeLocked, true);
  assert.equal(page.data.canRetryUnknownWrite, false);
  current.offlineAnchor = offlineAnchor;
  assert.equal(await page.retryUnknownWrite(), true);
  assert.equal(writes, 1); assert.equal(reads, 2); assert.equal(page.data.writeLocked, false);
  assert.equal(page._genericControl.offlineAnchor, offlineAnchor);
});

test('offline start cancellation, stale availability and epoch never write', () => {
  let writes = 0;
  const { page, modals } = pageHarness({ anchorSharedRun: async () => { writes++; } });
  page.startOfflineRecord(); assert.equal(modals.length, 0);
  page._genericControl.offlineAnchorAvailable = true;
  page.startOfflineRecord(); modals[0].success({ confirm: false }); assert.equal(writes, 0);
  page.startOfflineRecord(); page.data.authorityEpoch = 3; modals[1].success({ confirm: true }); assert.equal(writes, 0);
});

test('offline known rejection is visible and unlocks only after a definite rejection', async () => {
  const { page, modals, toasts, storage } = pageHarness({ anchorSharedRun: async () => ({ status: 'business-error', message: '当前不可开始离线记录' }) });
  page._genericControl.offlineAnchorAvailable = true;
  page.startOfflineRecord(); modals[0].success({ confirm: true }); await tick();
  assert.deepEqual(toasts, ['当前不可开始离线记录']);
  assert.equal(page.data.writeLocked, false);
  assert.equal(page._offlineAnchorPending, null);
  assert.equal(page.loads, 1);
  assert.equal(Object.keys(storage).some(key => key.endsWith(':offline-start')), false);
});

test('offline unknown survives re-entry in actor scoped storage and only reads control', async () => {
  let writes = 0, reads = 0;
  const first = pageHarness({ anchorSharedRun: async () => { writes++; return { status: 'unknown-write' }; }, load: async () => { throw new Error('offline'); } });
  first.page._genericControl.offlineAnchorAvailable = true;
  first.page.startOfflineRecord(); first.modals[0].success({ confirm: true }); await tick();
  assert.equal(first.page.data.writeLocked, true);
  const key = Object.keys(first.storage).find(value => value.endsWith(':offline-start'));
  assert.match(key, /\.m99\.a71:/);
  assert.deepEqual(Object.keys(first.storage[key]), ['epoch']);
  const second = pageHarness({ load: async () => { reads++; return { revision: 9, authorityEpoch: 2, status: 'RUNNING',
    genericControl: Object.assign(control([]), { offlineAnchor }) }; } }, first.storage, true);
  second.page.initDirector(71); await tick(); await tick();
  assert.equal(writes, 1); assert.equal(reads, 2);
  assert.equal(second.page.data.writeLocked, false);
  assert.equal(second.page._offlineAnchorPending, null);
  assert.equal(second.storage[key], undefined);
});

test('staff selector highlight and accessible label use the actual controlled draft value', () => {
  const signing = genericOpsFields('STAFF_MANUAL_SIGN', { signedAction: 'ARRIVAL_ATTEST' }).find(field => field.key === 'signedAction');
  assert.equal(signing.optionIndex, 1);
  assert.equal(signing.optionLabels[signing.optionIndex], signing.valueLabel);
  const review = genericOpsFields('MANUAL_REVIEW', { reviewKind: 'REQUEST' }).find(field => field.key === 'reviewKind');
  assert.equal(review.optionIndex, 2);
  assert.equal(review.optionLabels[review.optionIndex], review.valueLabel);
  assert.match(fs.readFileSync(path.join(__dirname, '../../pages/club/topic-detail/index.wxml'), 'utf8'), /value="\{\{item\.optionIndex\}\}" disabled="\{\{writeLocked\}\}"/);
});

test('simulator fixture traverses the real adapter serializers and blocks all writes', async () => {
  const application = {}, exported = { exports: {} };
  const page = { route: 'pages/club/topic-detail/index', data: {},
    setData(patch) { Object.assign(this.data, patch); }, applyProjection(projection) { this.projection = projection; this.data.genericOpsActions = genericOpsActions(projection.genericControl); },
    _getAdapter() { return createClubGameDirectorAdapter(createGameSessionClient({ sendRequest: options => application.sendRequest(options) })); } };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../fixtures/generic-director-ops-simulator.js'), 'utf8'), {
    module: exported, getCurrentPages: () => [page], getApp: () => application, wx: { getWindowInfo: () => ({ windowWidth: 390 }) },
  });
  const online = await exported.exports.installDirectorFixture();
  assert.equal(online.width, 390);
  assert.equal(page.projection.genericControl.run.authorityMode, 'ONLINE');
  assert.ok(page.projection.genericControl.availableActions.includes('ACT_UNLOCK'));
  await exported.exports.installDirectorFixture(true);
  assert.equal(page.projection.genericControl.run.authorityMode, 'MANUAL');
  assert.ok(page.projection.genericControl.availableActions.includes('STAFF_MANUAL_SIGN'));
  let blocked = false;
  application.sendRequest({ method: 'POST', url: '/api/game/session/command', fail() { blocked = true; }, success() { assert.fail('fixture must never write'); } });
  assert.equal(blocked, true);
  page.route = 'pages/play/index';
  assert.throws(() => exported.exports.installDirectorFixture(), /Only the director page/);
});
