import request from '@/utils/request'

// 主题节点（店位）写入口。读侧不另开接口 —— 节点随 getTopic 一起返回 chapter.cmsTopicNodeList。
//
// ⚠️ 打卡码（checkin_code）在页面上只读：
//   · 新建时由后端生成（32 位十六进制、SecureRandom），前端不传；
//   · 编辑时不回传该字段 —— 换码会让店里已印好的海报全部作废。
// ⚠️ merchantMemberId = 商家的 **memberId**（不是 mms_merchant.id）。绑上它，
//   商家才能在小程序侧用 /api/merchant/chapter-node/poster-code 取到自己这家店的码。
//   审核态由后端强制置「已过审」——绑了商家又不置，节点会在玩家侧静默消失。
export function addTopicNode(data) {
  return request({
    url: '/business/topicnode',
    method: 'post',
    data: data
  })
}

export function updateTopicNode(data) {
  return request({
    url: '/business/topicnode',
    method: 'put',
    data: data
  })
}

export function delTopicNode(id) {
  return request({
    url: '/business/topicnode/' + id,
    method: 'delete'
  })
}
