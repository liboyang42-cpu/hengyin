const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const topicPage = fs.readFileSync(
  path.resolve(__dirname, '../../src/views/business/topic/index.vue'),
  'utf8'
);

test('主题旧可见性轴在业务页只读，不保留第二写入口', () => {
  assert.doesNotMatch(topicPage, /@click="handleUserStatus\(/,
    'userStatus 不得从主题业务页直写');
  assert.doesNotMatch(topicPage, /@click="handleMerchantStatus\(/,
    'merchantStatus 不得从主题业务页直写');
  assert.doesNotMatch(topicPage, /submitUserStatusForm|submitMerchantForm/,
    '旧状态提交方法应删除，避免后续按钮复活旁路');
  assert.match(topicPage, /prop="userStatus"[\s\S]*?userGovernanceLabel\(scope\.row\.userStatus\)/,
    '用户端旧轴仍需只读回显，且未知值不得留白');
  assert.match(topicPage, /prop="merchantStatus"[\s\S]*?merchantPoolLabel\(scope\.row\)/,
    '商家公开池旧轴仍需只读回显，且未知值不得留白');
});
