// 2026-11-01 业主定稿(审阅页 65gFrW…):定向广播弹层选人与按钮话术。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const sheet = require('../../pages/topic/utils/broadcast-sheet.js');
const read = (p) => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8');

const R = [
  { memberId: 1, name: '阿乔', arrived: false, reach: 'OK' },
  { memberId: 2, name: '周周', arrived: true, reach: 'OK' },
  { memberId: 3, name: '老陈', arrived: false, reach: 'NO_CONSENT' },
  { memberId: 4, name: '小夏', arrived: false, reach: 'TODAY_SENT' },
  { memberId: 5, name: '无状态', arrived: false },
];

test('能不能收到只听服务端:不接收 / 今天已收到 / 没给 reach 都勾不上', () => {
  const rows = sheet.rowsFrom(R);
  assert.deepEqual(rows.map((r) => [r.statusText, r.off, r.picked]), [
    ['未到店', false, true], ['已核销', false, true], ['不接收', true, false], ['今天已收到', true, false], ['不接收', true, false],
  ]);
  assert.deepEqual(sheet.pickedIds(sheet.toggleRow(rows, '3')), ['1', '2'], '点灰掉的人不起作用');
  assert.deepEqual(sheet.pickedIds(sheet.toggleRow(rows, '1')), ['2']);
});

test('从客户名单多选进来只勾那几个;不接收的人即使带进来也不勾', () => {
  assert.deepEqual(sheet.pickedIds(sheet.rowsFrom(R, ['2', '3'])), ['2']);
});

test('范围:一点选上对应的人,不接收的始终不选', () => {
  const rows = sheet.rowsFrom(R);
  assert.deepEqual(sheet.pickedIds(sheet.pickScope(rows, 'pending')), ['1']);
  assert.deepEqual(sheet.pickedIds(sheet.pickScope(rows, 'arrived')), ['2']);
  assert.deepEqual(sheet.pickedIds(sheet.pickScope(rows, 'all')), ['1', '2']);
});

test('按钮话术与能否发送', () => {
  const rows = sheet.rowsFrom(R);
  const base = { state: 'ready', rows, remaining: 3, content: '集合' };
  assert.equal(sheet.derive(base).sendText, '发送给 2 人');
  assert.equal(sheet.derive(base).canSend, true);
  assert.equal(sheet.derive(base).skipped, 3);
  assert.equal(sheet.derive(Object.assign({}, base, { content: '  ' })).canSend, false, '只有空格不算写了内容');
  assert.equal(sheet.derive(Object.assign({}, base, { rows: rows.map((r) => Object.assign({}, r, { picked: false })) })).sendText, '先选收件人');
  const full = sheet.derive(Object.assign({}, base, { remaining: 0 }));
  assert.equal(full.sendText, '今天已发满 3 条');
  assert.equal(full.canSend, false);
  const sending = sheet.derive(Object.assign({}, base, { sending: true }));
  assert.equal(sending.sendText, '发送中…');
  assert.equal(sending.canSend, false);
  assert.equal(sheet.derive({ state: 'none', rows: [], remaining: 3 }).sendText, '没有可发送的人');
  for (const state of ['loading', 'error']) {
    assert.equal(sheet.derive({ state, rows: [], remaining: 0 }).sendText, '发送');
    assert.equal(sheet.derive({ state, rows: [], remaining: 0 }).canSend, false);
  }
});

test('页面:蓝色提示块与「可以收到」都没了,内容 50 字,定向广播悬浮在底部', () => {
  const page = read('pages/topic/merchantinfo/merchantinfo.wxml');
  assert.doesNotMatch(page, /人数没读到|可以收到/);
  const host = read('pages/topic/components/project-host/index.wxml');
  assert.doesNotMatch(host, /cy-inline-error[^>]*openBroadcast/);
  assert.doesNotMatch(host, /class="bcast-row"[^>]*data-act="openBroadcast"/);
  assert.match(host, /class="bx-ta" maxlength="50"/);
  assert.match(read('pages/club/components/club-director-broadcast-sheet/index.wxml'), /maxlength="50"/);
  assert.match(read('pages/topic/merchantinfo/merchantinfo.js'), /key: 'openBroadcast', label: '定向广播'/);
  assert.match(read('pages/topic/components/project-drawer/index.wxss'), /\.dw--customer \.dw-cta \{\s*position: absolute;/);
});
