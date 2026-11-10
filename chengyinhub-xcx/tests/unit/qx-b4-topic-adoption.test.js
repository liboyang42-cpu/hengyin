// qx-b4 U02-01/U02-02/§27.1:主题模板采用条件与「沿用地点 / 换城市」采用。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const a = require('../../pages/templatedetail/utils/topic-adoption.js');

const full = {
  sourceLocale: 'zh-CN',
  revision: { contentRevisionId: 88, revisionNo: 3 },
  locationRequirement: { cityCode: '310100', physicalPlaceRequired: true, stationWithAddressCount: 4, stationCount: 5 },
  operatingRequirementsVisibility: 'PARTNER_ONLY',
  evidence: { evidenceLevel: 'ESTIMATED' },
  runtimeSupport: { platformExecution: 'UNKNOWN' },
  license: { status: 'UNKNOWN', unavailableReason: 'SALE_SNAPSHOT_MISSING' },
};

const row = (rows, key) => rows.find(r => r.key === key);

test('采用条件只翻译服务端事实:未知不写成已验证、两端可玩或已授权', () => {
  const rows = a.deliveryRows(full);
  assert.equal(row(rows, 'place').value, '需要到现场 · 4 个地点');
  assert.equal(row(rows, 'operating').value, '仅对合作商家开放');
  assert.equal(row(rows, 'evidence').value, '预估，未实地验证');
  assert.equal(row(rows, 'revision').value, '第 3 版');
  assert.equal(row(rows, 'locale').value, '简体中文');
  // r2-create(b4-u02-01「UI需要优化一下」):没有权威事实的行(端侧待核实、许可未提供)不画
  assert.equal(row(rows, 'platform'), undefined);
  assert.equal(row(rows, 'license'), undefined);
});

test('资料缺失的行不画;contentDelivery 整段缺失不画', () => {
  assert.deepEqual(a.deliveryRows(null), []);
  assert.deepEqual(a.deliveryRows({ operatingRequirementsVisibility: 'NOT_PROVIDED' }), []);
  assert.deepEqual(a.deliveryRows({ locationRequirement: { physicalPlaceRequired: false } }).map(r => r.value), ['不需要到现场']);
});

test('/use 请求:沿用=SAME_CITY,换城=NEW_CITY+6位城市;带当前发布修订 id', () => {
  const base = { id: 7, scope: '', sourceCityCode: '310100', revisionId: a.revisionIdOf(full) };
  assert.deepEqual(a.usePayload({ ...base, intent: 'SAME_CITY' }).data,
    { id: '7', scope: '', bindingIntent: 'SAME_CITY', sourceContentRevisionId: '88' });
  // r2-create(b4-u02-02「不需要换城市」):端上不再发 NEW_CITY
  assert.deepEqual(a.usePayload({ ...base, intent: 'NEW_CITY', targetCityCode: '330100' }).data,
    { id: '7', scope: '', sourceContentRevisionId: '88' });
  // 没有修订 = 不传(旧来源),没有城市事实 = 不传意图(旧版采用)
  assert.deepEqual(a.usePayload({ id: 7, intent: '' }).data, { id: '7', scope: '' });
  assert.equal(a.revisionIdOf({ revision: null }), '');
});

test('/use 回包读对象 data.topicId,只有 level=error 计为挡发布', () => {
  const r = a.useResult({ code: 200, data: { topicId: 901, pendingRebindSlotKeys: ['loc-1', 'loc-2'],
    bindingIssues: [{ level: 'error' }, { level: 'warn' }], sourceContentRevisionId: null } });
  assert.deepEqual(r, { ok: true, topicId: '901', blocking: 1, pendingRebind: 2, sourceContentRevisionId: null });
  assert.equal(a.useResult({ code: 200, data: 901 }).ok, false); // 标量不是合同
  assert.equal(a.useResult({ code: 500, msg: '目标城市与来源相同，请使用同城复用' }).message, '目标城市与来源相同，请使用同城复用');
});

test('素材署名只显示署名 / AI 生成 / 来源待确认,不带私有许可信息', () => {
  assert.equal(a.creditText({ sourceType: 'licensed', attribution: '摄影 张三 CC BY 4.0', aiGenerated: false, sourcePendingConfirmation: false }), '摄影 张三 CC BY 4.0');
  // r2-create(b4-u02-05「不显示「AI 生成」、「来源待确认」」)
  assert.equal(a.creditText({ sourceType: 'ai-generated', attribution: null, aiGenerated: true }), '');
  assert.equal(a.creditText({ sourceType: 'unknown', sourcePendingConfirmation: true }), '');
  assert.equal(a.creditText({ sourceType: 'original', licenseNote: '私有备注', aiModel: 'x' }), '');
  assert.equal(a.creditText(null), '');
});

// r2-create(b4-u02-02/04 业主「不需要选择城市」「不需要换城市」):「应用」直接沿用原地点采用,页面上没有换城选择
test('应用模板不再弹换城选择', () => {
  const fs = require('node:fs'); const path = require('node:path');
  const dir = path.join(__dirname, '../../pages/templatedetail/topic-detail');
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8');
  const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8');
  assert.doesNotMatch(wxml, /换一个城市|adoptCities|onAdoptCity/);
  assert.doesNotMatch(js, /NEW_CITY|adoptCities/);
  assert.match(js, /this\._submitUse\(placed \? 'SAME_CITY' : ''\)/);
  assert.equal(a.adoptCities, undefined);
});
