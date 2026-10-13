const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const workspace = fs.readFileSync(
  path.resolve(__dirname, '../../src/views/business/topic/workspace.vue'),
  'utf8'
);

test('主题工作台显式呈现内容主体，并以服务端能力合同展示连续运营状态', () => {
  assert.match(workspace, /内容主体/);
  assert.match(workspace, /官方活动/,
    '尚未建立正式关系时必须明确显示官方活动未关联');
  assert.match(workspace, /publisher\(\)\s*\{\s*return this\.workspace\.publisher \|\| \{\}\s*\}/);
  assert.match(workspace, /officialActivityRelation\(\)\s*\{\s*return this\.workspace\.officialActivityRelation \|\| \{\}\s*\}/);
  assert.match(workspace, /UNLINKED/);
  assert.match(workspace, /不会被冒充为官方活动/);
  assert.match(workspace, /operation\(\)\s*\{\s*return this\.workspace\.operation \|\| \{\}\s*\}/,
    '玩法、生命周期和公开池应来自工作台白名单投影');
  assert.match(workspace, /governance\(\)\s*\{\s*return this\.workspace\.governance \|\| \{\}\s*\}/,
    '审核与用户端可见性应来自治理投影');
  assert.match(workspace, /merchantSupply\(\)\s*\{\s*return this\.workspace\.merchantSupply \|\| \{\}\s*\}/,
    '商家候选摘要应来自供给投影');
  assert.match(workspace, /clubFulfillment\(\)\s*\{\s*return this\.workspace\.clubFulfillment \|\| \{\}\s*\}/,
    '俱乐部申请、邀约、合同与履约应来自连续状态投影');
  assert.match(workspace, /capabilities\(\)\s*\{\s*return this\.workspace\.capabilities \|\| \{\}\s*\}/,
    '未验证轴必须由后端能力门禁显式控制');
  assert.match(workspace, /pendingReviewCount/,
    '候选摘要必须区分待审核，不能只输出笼统报名总数');
  assert.match(workspace, /awardedCount/,
    '候选摘要必须区分已中标，不能把报名总数误作有效供给');
  assert.match(workspace, /publisherLabel\(\)/);
  assert.match(workspace, /历史待归属/);
  assert.match(workspace, /axisCapability\(axis\)/,
    '能力门禁必须读取后端状态合同，而不是由字段空值推断');
  assert.match(workspace, /CAPABILITY_CONTRACT_MISSING/,
    '缺少能力合同的旧响应必须 fail-closed');
  assert.match(workspace, /runBlockerLabel/);
  assert.match(workspace, /REQUIRED_CHAPTER_SUPPLY_MISSING/);
  assert.match(workspace, /1:\s*'线性城市定向'/,
    'productType=1 是现网经典定向，不能误标为自由探索');
  assert.match(workspace, /2:\s*'主题内自由探索'/,
    'productType=2 才是自由探索');
  assert.doesNotMatch(workspace, /0:\s*'线性城市定向'/,
    '0 不是当前产品类型的经典定向枚举值');
  assert.doesNotMatch(workspace, /this\.topic\.productType/,
    '工作台首屏不得继续从裸 topic 读取玩法真源');
  assert.match(workspace, /isAxisAvailable\('clubFulfillment'\)/);
  assert.match(workspace, /isAxisAvailable\('merchantSupply'\)/);
  assert.match(workspace, /isAxisAvailable\('cooperation'\)/);
  assert.match(workspace, /isAxisAvailable\('settlement'\)/);
  assert.match(workspace, /axisCapabilityDescription\('cooperation'\)/);
  assert.match(workspace, /axisCapabilityDescription\('settlement'\)/);
  assert.doesNotMatch(workspace, /cooperationConnected|settlementConnected/,
    'boolean 门禁不能继续掩盖未接通、无权限和读取失败的差异');
  assert.match(workspace, /settlementStatusOverviewLabel\(\)/,
    '审核摘要的结算状态也必须经过同一能力门禁');
  assert.match(workspace, /<article><span>结算状态<\/span><strong>\{\{ settlementStatusOverviewLabel \}\}<\/strong>/,
    '缺结算回读时，审核摘要必须使用受门禁保护的状态标签');
  assert.match(workspace, /pendingApplicationCount/);
  assert.match(workspace, /pendingInviteCount/);
  assert.match(workspace, /signedCooperationCount/);
  assert.match(workspace, /activeOfferCount/);
  assert.match(workspace, /missingRequiredChapterCount/);
  assert.match(workspace, /openComplaintCount/,
    '进行中异常当前以未结客诉这一可验证口径呈现');
});
