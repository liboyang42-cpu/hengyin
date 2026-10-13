const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// 统一审核台的 TEAM(玩家队伍举报)适配契约:审核员要能在列表筛出队伍、在详情读懂快照
// (组队方式/队伍状态是人话不是裸数字),并且处置按钮走的仍是通用的 Integer 状态入口。
// 处置语义:拒绝=举报成立→队伍改回仅邀请并撤下地图,不解散不踢人;通过=不成立→保持公开。
const viewPath = path.resolve(__dirname, '../../src/views/business/audit/index.vue');

function loadComponent() {
  const src = fs.readFileSync(viewPath, 'utf8');
  const script = src.match(/<script>([\s\S]*?)<\/script>/)[1];
  const body = script
    .replace(/^\s*import[^\n]*\n/gm, '')
    .replace(/components:\s*\{[^}]+\},/, 'components: {},')
    .replace(/export\s+default\s*/, 'return ');
  return new Function(body)();
}

test('业务类型可选「玩家队伍」,否则审核员筛不出 TEAM 举报', () => {
  const { bizTypeOptions } = loadComponent().data();
  const team = bizTypeOptions.find(option => option.value === 'TEAM');
  assert.ok(team, 'bizTypeOptions 必须含 TEAM');
  assert.equal(team.label, '玩家队伍');
});

test('TEAM 快照按队伍字段渲染,组队方式/队伍状态显示人话,不糊成一段 JSON', () => {
  const component = loadComponent();
  const snapshot = JSON.stringify({
    bizType: 'TEAM',
    bizId: 31,
    reason: '有人发广告',
    content: {
      available: true, evidenceStatus: 'CAPTURED', title: '外滩夜行搭子', leaderMemberId: 7,
      ownerType: 2, ownerId: 10, joinMode: 2, status: 0, joinedCount: 2, maxMembers: 4
    }
  });
  const items = component.computed.auditEvidenceItems.call({
    detailTask: { bizType: 'TEAM', snapshotJson: snapshot },
    detailRecords: [],
    hangoutEvidence: component.methods.hangoutEvidence,
    teamEvidence: component.methods.teamEvidence,
    flatSnapshotLines: component.methods.flatSnapshotLines
  });

  assert.equal(items.length, 1);
  const content = items[0].content;
  assert.match(content, /队伍名称：外滩夜行搭子/);
  assert.match(content, /队长编号：7/);
  assert.match(content, /场次编号：10/);
  assert.match(content, /组队方式：公开申请/);
  assert.match(content, /队伍状态：招募中/);
  assert.match(content, /已加入人数：2/);
  assert.match(content, /队伍人数上限：4/);
  assert.doesNotMatch(content, /"joinMode"/, '不得直接糊原始 JSON');
});

test('详情写明 TEAM 处置语义:成立=改回仅邀请不解散,不成立=保持公开', () => {
  const src = fs.readFileSync(viewPath, 'utf8');
  assert.match(src, /detailTask\.bizType === 'TEAM'/);
  assert.match(src, /不解散/);
  assert.match(src, /仅邀请/);
});

test('查看同对象审核历史按当前业务类型与对象 id 过滤(不再只认 HANGOUT)', () => {
  const component = loadComponent();
  const ctx = {
    queryParams: { pageNum: 3, pageSize: 10, bizType: null, bizId: null, status: 1 },
    detailTask: { bizType: 'TEAM', bizId: 31 },
    detailOpen: true,
    listed: 0,
    getList() { this.listed += 1; }
  };
  component.methods.investigateSameTarget.call(ctx);

  assert.equal(ctx.queryParams.bizType, 'TEAM');
  assert.equal(ctx.queryParams.bizId, 31);
  assert.equal(ctx.queryParams.status, null);
  assert.equal(ctx.queryParams.pageNum, 1);
  assert.equal(ctx.detailOpen, false);
  assert.equal(ctx.listed, 1);
});
