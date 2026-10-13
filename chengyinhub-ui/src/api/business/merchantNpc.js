import request from '@/utils/request'

// 门店 AI 形象(npc_profile scope_type=2「门店分身」,CmsMerchantNpcController)。
// 后端不分页,list 直接回裸数组。

export function listMerchantNpc(params) {
  return request({
    url: '/business/merchantNpc/list',
    method: 'get',
    params: params
  })
}

export function getMerchantNpc(id) {
  return request({
    url: '/business/merchantNpc/' + id,
    method: 'get'
  })
}

// 新增时 scope_type 恒为 2、审核态恒为 1、persona 落空串,均由后端把守,前端不送。
export function addMerchantNpc(data) {
  return request({
    url: '/business/merchantNpc',
    method: 'post',
    data: data
  })
}

// 编辑只送展示字段;换商家 = 重新建一条,scope_id 不可迁移。
export function updateMerchantNpc(data) {
  return request({
    url: '/business/merchantNpc',
    method: 'put',
    data: data
  })
}

export function delMerchantNpc(id) {
  return request({
    url: '/business/merchantNpc/' + id,
    method: 'delete'
  })
}

// 审核商家自助提交的形象。
// ★★ 没有这条,商家提交的形象永远停在待审、永远不上线,而且零报错零告警 ——
//   玩家侧表现是「所有店铺都没有形象」,跟「运营还没配」长得一模一样。
// ★ 驳回必须带 auditReason:不说为什么,商家只会把同一份原样再交一遍。
export function auditMerchantNpc(data) {
  return request({
    url: '/business/merchantNpc/audit',
    method: 'post',
    data: data
  })
}
