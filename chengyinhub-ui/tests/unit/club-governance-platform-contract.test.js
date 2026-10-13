const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadClub(overrides = {}) {
  const source = read('src/views/business/club/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s*\{[\s\S]*?\}\s*from\s*'@\/api\/business\/club'\s*;?\s*$/m,
      'const { auditClub, delClub, getClub, getClubFulfillment, getClubGovernance, getClubWorkspace, leaderAudit, listClub, listClubGovernanceCases, permanentBanClubMember, removeClubMember, reviewClubGovernanceCase, submitClubPostForReview, updateClub, updateClubMemberRole } = dependencies;')
    .replace(/^import\s*\{ listTopic \}\s*from\s*'@\/api\/business\/topic'\s*;?\s*$/m, 'const { listTopic } = dependencies;')
    .replace(/^import\s*\{ checkPermi \}\s*from\s*'@\/utils\/permission'\s*;?\s*$/m, 'const { checkPermi } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  const dependencies = Object.assign({
    auditClub: () => Promise.resolve({}),
    delClub: () => Promise.resolve({}),
    getClub: () => Promise.resolve({ data: {} }),
    getClubFulfillment: () => Promise.resolve({ data: {} }),
    getClubGovernance: () => Promise.resolve({ data: {} }),
    getClubWorkspace: () => Promise.resolve({ data: {} }),
    leaderAudit: () => Promise.resolve({}),
    listClub: () => Promise.resolve({ rows: [], total: 0 }),
    listClubGovernanceCases: () => Promise.resolve({ data: [] }),
    listTopic: () => Promise.resolve({ rows: [], total: 0 }),
    permanentBanClubMember: () => Promise.resolve({}),
    removeClubMember: () => Promise.resolve({}),
    reviewClubGovernanceCase: () => Promise.resolve({}),
    submitClubPostForReview: () => Promise.resolve({}),
    updateClub: () => Promise.resolve({}),
    updateClubMemberRole: () => Promise.resolve({}),
    checkPermi: () => false
  }, overrides)
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies)
}

test('俱乐部 API 暴露平台工单读取、裁决和永久封禁三个独立动作', () => {
  const api = read('src/api/business/club.js')

  assert.match(api, /export function listClubGovernanceCases\(query\)[\s\S]*?url:\s*['"]\/business\/club-governance\/cases['"][\s\S]*?method:\s*['"]get['"]/)
  assert.match(api, /export function reviewClubGovernanceCase\(data\)[\s\S]*?url:\s*['"]\/business\/club-governance\/cases\/review['"][\s\S]*?method:\s*['"]post['"]/)
  assert.match(api, /export function permanentBanClubMember\(data\)[\s\S]*?url:\s*['"]\/business\/club-governance\/permanent-ban['"][\s\S]*?method:\s*['"]post['"]/)
})

test('平台工单位于俱乐部工作区并按审计权限隔离', () => {
  const view = read('src/views/business/club/index.vue')

  assert.match(view, /<el-tab-pane\s+v-if="checkAuditListPermi"\s+label="平台工单"\s+name="platform-cases">/)
  assert.match(view, /listClubGovernanceCases\(\{\s*clubId\s*\}\)/)
  assert.match(view, /checkAuditReviewPermi\(\)\s*\{\s*return checkPermi\(\[['"]business:audit:review['"]\]\)\s*\}/)
  assert.match(view, /v-if="checkAuditReviewPermi && row\.status === 'PENDING'"/)
})

test('工单裁决不冒充成员处罚，永久封禁保持独立确认动作', () => {
  const view = read('src/views/business/club/index.vue')

  assert.match(view, /工单已批准；成员处罚需单独执行/)
  assert.match(view, /startGovernanceAction\('ban', row\)/)
  assert.match(view, /permanentBanClubMember\(\{[\s\S]*?clubId:\s*row\.clubId,[\s\S]*?targetMemberId:\s*row\.targetId,[\s\S]*?requestId:/)
  assert.match(view, /const action = kind === 'ban'\s*\? permanentBanClubMember\([\s\S]*?: reviewClubGovernanceCase\(/)
  assert.doesNotMatch(view, /else if \(kind === 'approve'\)[^\n]*永久封禁/)
})

test('平台工单读取按当前俱乐部过滤，批准动作携带版本和独立幂等键', async () => {
  let query
  let reviewPayload
  const messages = []
  const component = loadClub({
    listClubGovernanceCases: value => {
      query = value
      return Promise.resolve({ data: [{ id: 9, status: 'PENDING' }] })
    },
    reviewClubGovernanceCase: value => {
      reviewPayload = value
      return Promise.resolve({})
    }
  })
  const vm = Object.assign(component.data(), {
    checkAuditListPermi: true,
    checkAuditReviewPermi: true,
    governanceActionKind: 'approve',
    governanceActionTarget: { id: 9, clubId: 31, version: 4, status: 'PENDING' },
    governanceActionRequestId: 'club-gov-approve-9-stable',
    $modal: { msgSuccess: message => messages.push(message) }
  })
  vm.loadPlatformCases = clubId => component.methods.loadPlatformCases.call(vm, clubId)

  await vm.loadPlatformCases(31)
  await component.methods.submitGovernanceAction.call(vm, '证据完整，批准工单')

  assert.deepEqual(query, { clubId: 31 })
  assert.deepEqual(reviewPayload, {
    caseId: 9,
    version: 4,
    approved: true,
    reason: '证据完整，批准工单',
    requestId: 'club-gov-approve-9-stable'
  })
  assert.equal(messages.at(-1), '工单已批准；成员处罚需单独执行')
})

test('永久封禁只向独立端点传俱乐部和成员对象', async () => {
  let banPayload
  const component = loadClub({
    permanentBanClubMember: value => {
      banPayload = value
      return Promise.resolve({})
    }
  })
  const vm = Object.assign(component.data(), {
    checkAuditListPermi: true,
    checkAuditReviewPermi: true,
    governanceActionKind: 'ban',
    governanceActionTarget: { id: 11, clubId: 31, targetId: 82, status: 'APPROVED', caseType: 'REPORT', targetType: 'MEMBER' },
    governanceActionRequestId: 'club-gov-ban-11-stable',
    $modal: { msgSuccess: () => {} },
    loadPlatformCases: () => Promise.resolve()
  })

  await component.methods.submitGovernanceAction.call(vm, '多次恶意骚扰，执行平台永久封禁')

  assert.deepEqual(banPayload, {
    clubId: 31,
    targetMemberId: 82,
    reason: '多次恶意骚扰，执行平台永久封禁',
    requestId: 'club-gov-ban-11-stable'
  })
})
