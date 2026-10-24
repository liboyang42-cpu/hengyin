'use strict';
// C:U-172:订阅消息模板 ID 前端(utils/subscribe.js TEMPLATES)与后端(application.yml subscribe.tpl*)
// 各存一份,此前只有合作三类有对账。这里把全部模板逐个对上:同一业务 key 两端 ID 必须一致,
// 后端配置的每个 tpl* 前端都得有对应 key(否则用户永远授权不到),反之亦然。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { TEMPLATES } = require('../../utils/subscribe.js');

// 前端 key → 后端 application.yml 配置项
const FRONT_TO_BACK = Object.freeze({
  signupSuccess: 'tplSignupSuccess',
  activityStart: 'tplBeforeStart',
  refund: 'tplRefund',
  coopInvited: 'tplCoopInvited',
  coopStatus: 'tplCoopStatus',
  recruit: 'tplRecruit',
  coopSettle: 'tplCoopSettle',
  clubInterest: 'tplClubInterest',
  roamRecall: 'tplRoamRecall',
});
// 前端会申请授权、但后端当前没有对应配置项(不下发)的模板。
// refundRejected:后端无 tplRefundRejected,退款被拒目前不发订阅消息 —— 已作为待拍板项记录
// (docs/delivery/defer-20261024/DEFER-COMM.md C:U-172),这里显式列出,新增一个都会让本测试变红。
const FRONT_ONLY = Object.freeze(['refundRejected']);

function backendTemplateIds() {
  const yml = fs.readFileSync(path.resolve(__dirname, '../../../chengyinhub-admin/src/main/resources/application.yml'), 'utf8');
  const start = yml.indexOf('\n  subscribe:');
  assert.notEqual(start, -1, 'application.yml 缺少 subscribe 段');
  // 段落止于下一个同级(两格缩进)键
  const rest = yml.slice(start + 1);
  const next = rest.slice(1).search(/\n  [A-Za-z]/);
  const scoped = next === -1 ? rest : rest.slice(0, next + 1);
  const ids = {};
  for (const m of scoped.matchAll(/^\s+(tpl[A-Za-z]+):\s*"([^"]*)"/gm)) ids[m[1]] = m[2];
  return ids;
}

test('后端每个 tpl* 配置项都有前端 key,且模板 ID 两端一致', () => {
  const backend = backendTemplateIds();
  assert.ok(Object.keys(backend).length >= 9, '没解析出 application.yml 的 subscribe.tpl*');
  const mapped = new Set(Object.values(FRONT_TO_BACK));
  for (const key of Object.keys(backend)) assert.ok(mapped.has(key), `后端 ${key} 没有对应的前端模板 key`);
  for (const [front, back] of Object.entries(FRONT_TO_BACK)) {
    assert.ok(Object.prototype.hasOwnProperty.call(TEMPLATES, front), `前端缺少 ${front}`);
    assert.equal(TEMPLATES[front], backend[back], `${front} ↔ ${back} 模板 ID 不一致`);
  }
});

test('前端每个模板 key 要么对到后端,要么在显式的 FRONT_ONLY 清单里', () => {
  const backend = backendTemplateIds();
  for (const key of Object.keys(TEMPLATES)) {
    if (FRONT_ONLY.includes(key)) {
      assert.ok(!Object.values(backend).includes(TEMPLATES[key]) || !TEMPLATES[key], `${key} 已在后端配置,应移出 FRONT_ONLY 并补映射`);
      continue;
    }
    assert.ok(FRONT_TO_BACK[key], `前端模板 ${key} 未与后端对账`);
  }
});
