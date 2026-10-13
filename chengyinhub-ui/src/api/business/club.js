import request from '@/utils/request'

// 查询俱乐部列表
export function listClub(query) {
  return request({
    url: '/business/club/list',
    method: 'get',
    params: query
  })
}

// 查询俱乐部详细
export function getClub(id) {
  return request({
    url: '/business/club/' + id,
    method: 'get'
  })
}

// 查询俱乐部运营工作区基础档案（俱乐部申请 + 主理人能力档案）
export function getClubWorkspace(id) {
  return request({
    url: '/business/club/' + id + '/workspace',
    method: 'get'
  })
}

// 查询俱乐部成员与动态治理视图
export function getClubGovernance(id) {
  return request({
    url: '/business/club/' + id + '/governance',
    method: 'get'
  })
}

// 查询俱乐部已承接主题的带队履约与团核销证据
export function getClubFulfillment(id) {
  return request({
    url: '/business/club/' + id + '/fulfillment',
    method: 'get'
  })
}

// 查询俱乐部平台治理工单；仅平台审核权限可见
export function listClubGovernanceCases(query) {
  return request({
    url: '/business/club-governance/cases',
    method: 'get',
    params: query
  })
}

// 裁决俱乐部举报/申诉工单；裁决本身不等于成员处罚
export function reviewClubGovernanceCase(data) {
  return request({
    url: '/business/club-governance/cases/review',
    method: 'post',
    data
  })
}

// 平台永久封禁俱乐部成员；与举报工单裁决保持独立动作
export function permanentBanClubMember(data) {
  return request({
    url: '/business/club-governance/permanent-ban',
    method: 'post',
    data
  })
}

// 设置俱乐部成员角色（0成员 / 1管理员）
export function updateClubMemberRole(clubId, memberId, role) {
  return request({
    url: '/business/club/' + clubId + '/members/' + memberId + '/role',
    method: 'put',
    data: { role }
  })
}

// 从俱乐部移除普通成员
export function removeClubMember(clubId, memberId) {
  return request({
    url: '/business/club/' + clubId + '/members/' + memberId,
    method: 'delete'
  })
}

// 将俱乐部动态提交统一审核台，不在业务页直接下架
export function submitClubPostForReview(clubId, postId) {
  return request({
    url: '/business/club/' + clubId + '/posts/' + postId + '/review',
    method: 'post'
  })
}

// 修改俱乐部
export function updateClub(data) {
  return request({
    url: '/business/club',
    method: 'put',
    data: data
  })
}

// 审核俱乐部（status 1通过 2驳回）
export function auditClub(data) {
  return request({
    url: '/business/club/audit',
    method: 'put',
    data: data
  })
}

// 主理人定级（D15:写 club_leader level/tags + 审计;入参 memberId 或 id）
export function leaderAudit(data) {
  return request({
    url: '/business/club/leader-audit',
    method: 'put',
    data: data
  })
}

// 删除俱乐部
export function delClub(ids) {
  return request({
    url: '/business/club/' + ids,
    method: 'delete'
  })
}
