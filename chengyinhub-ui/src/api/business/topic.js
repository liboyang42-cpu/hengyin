import request from '@/utils/request'

// 查询主题列表
export function listTopic(query) {
  return request({
    url: '/business/topic/list',
    method: 'get',
    params: query
  })
}

// 查询主题详细
export function getTopic(id) {
  return request({
    url: '/business/topic/' + id,
    method: 'get'
  })
}

// 查询主题运营工作台只读快照
export function getTopicWorkspace(id) {
  return request({
    url: '/business/topic/' + id + '/workspace',
    method: 'get'
  })
}

// 查询主题 P0 统一待办安全快照；只返回可展示的领域摘要与动作可用性。
export function getTopicOperations(id) {
  return request({
    url: '/business/topic/' + id + '/operations',
    method: 'get'
  })
}

// 新增主题
export function addTopic(data) {
  return request({
    url: '/business/topic',
    method: 'post',
    data: data
  })
}

// 修改主题
export function updateTopic(data) {
  return request({
    url: '/business/topic',
    method: 'put',
    data: data
  })
}

// 主题广场只开放发现治理；该命令不能写入发布者的内容、路线、图片说明或票价。
export function updatePlazaRecommendation(id, data) {
  return request({
    url: '/business/topic/' + id + '/plaza-recommendation',
    method: 'put',
    data: data
  })
}

// 高风险：CONTENT 槽决定票款归属。仅历史待归属处理队列中、且获独立权限的运营人员可调用。
export function reassignTopicContentSlot(id, data) {
  return request({
    url: '/business/topic/' + id + '/content-slot',
    method: 'put',
    data: data
  })
}

export function declareTopicOrganizerActor(id) {
  return request({
    url: '/business/topic/' + id + '/organizer-actor',
    method: 'put'
  })
}

export function getTopicEditionFunds(id) {
  return request({ url: `/business/topic-edition-funds/${id}/current`, method: 'get' })
}

export function getTopicEditionFinanceOverview(id) {
  return request({ url: `/business/topic-edition-funds/${id}/overview`, method: 'get' })
}

export function saveTopicEditionCost(id, data) {
  return request({ url: `/business/topic-edition-funds/${id}/cost`, method: 'put', data })
}

// 旧的 PUT /business/topic-edition-funds/{id}/hours 已随后端 mutable 写链整条删除（点下去必 404）。
// 计划工时只在开售冻结前可写；它是 F 的金额来源，由平台登记，不是自报。
// 2026-08-25：confirmTopicEditionHours（确认实际工时）随「俱乐部自报工时」整条退役而删除 ——
// 后端 rejectRetiredHoursWrite() 直接拒，留着就是一个点了必然报错的入口。
export function planTopicEditionHours(id, data) {
  return request({ url: `/business/club-compensation/${id}/hours/plan`, method: 'put', data })
}

// ── A4 俱乐部对公收款人 ────────────────────────────────────────────────────
// 开售是硬闸：没有一版 is_current=1 且 VERIFIED 的九组齐全对公资料，探店日期次开不了售。
// 这两条路（写资料 / 财务审核）此前在后台零入口，等于主链路在控制台上断开。
export function getClubPayee(clubId) {
  return request({ url: `/business/club-payee/${clubId}`, method: 'get' })
}

export function saveClubPayee(clubId, data) {
  return request({ url: `/business/club-payee/${clubId}`, method: 'put', data })
}

export function verifyClubPayee(clubId, profileId, data) {
  return request({ url: `/business/club-payee/${clubId}/verify/${profileId}`, method: 'post', data })
}

// 受控完整解密：单独权限 business:clubpayee:payout，只在付款登记环节用。
export function getClubPayeePayoutDetail(clubId) {
  return request({ url: `/business/club-payee/${clubId}/payout-detail`, method: 'get' })
}

// ── A2 额外工时（EXTRA）──────────────────────────────────────────────────
// 2026-08-25 整条退役：实际工时线下无法核实，而 EXTRA 是 ¥40／人小时 ——
// 自报即计酬等于给了一条「凭空写小时数换钱」的通道。预批与更正两个 API 一并删除。

// ── A1 质量奖励一次确认 / 过错排除 ─────────────────────────────────────────
export function confirmClubQuality(topicId, data) {
  return request({ url: `/business/club-compensation/${topicId}/quality/confirm`, method: 'post', data })
}

export function excludeClubQualityByFault(topicId, data) {
  return request({ url: `/business/club-compensation/${topicId}/quality/exclude`, method: 'post', data })
}

// ── F3 FINAL / 红冲 / 重计提 / 批次 ────────────────────────────────────────
// ★只读操作面：FINAL 头 + 应付原始行 + 批次(付款轴/发票轴) + 三维质量决定 + EXTRA 审批链 + 四类工时。
// 没有它，红冲/更正/批次四个动作都要求操作者先连生产库把 id 捞出来。
export function getTopicEditionLedger(topicId) {
  return request({ url: `/business/edition-final/${topicId}`, method: 'get' })
}

export function finalizeTopicEdition(topicId) {
  return request({ url: `/business/edition-final/${topicId}`, method: 'post' })
}

export function reverseClubPayable(topicId, payableId, data) {
  return request({ url: `/business/edition-final/${topicId}/payable/${payableId}/reverse`, method: 'post', data })
}

// 红冲之后的 append-only 重计提：没有它，红冲只能把钱抵成 0，正确金额进不了台账。
export function reaccrueClubPayable(topicId, data) {
  return request({ url: `/business/edition-final/${topicId}/reaccrue`, method: 'post', data })
}

// 一期一批次一张发票：路径带 topicId，与 FINAL/红冲过同一道对象级 scope。
export function createClubPayableBatch(topicId, data) {
  return request({ url: `/business/edition-final/${topicId}/batch`, method: 'post', data })
}

export function confirmClubPayableBatch(topicId, batchId) {
  return request({ url: `/business/edition-final/${topicId}/batch/${batchId}/confirm`, method: 'post' })
}

export function markClubPayableBatchPaid(topicId, batchId, data) {
  return request({ url: `/business/edition-final/${topicId}/batch/${batchId}/paid`, method: 'post', data })
}

// 发票轴与付款轴互不引用：「已打款未开票」是合法状态。
export function issueClubPayableInvoice(topicId, batchId, data) {
  return request({ url: `/business/edition-final/${topicId}/batch/${batchId}/invoice`, method: 'post', data })
}

export function redClubPayableInvoice(topicId, batchId, data) {
  return request({ url: `/business/edition-final/${topicId}/batch/${batchId}/invoice/red`, method: 'post', data })
}

export function recomputeTopicEditionFunds(id, idempotencyKey) {
  return request({
    url: `/business/topic-edition-funds/${id}/recompute`, method: 'post', data: { idempotencyKey }
  })
}

export function repairTopicPurchaseKind(id) {
  return request({ url: `/business/topic-edition-funds/${id}/repair-purchase-kind`, method: 'post' })
}

// 删除主题
export function delTopic(id) {
  return request({
    url: '/business/topic/' + id,
    method: 'delete'
  })
}

 // 复制主题的API调用（需要后端实现）
export function copyTopicRequest(id) {
  return request({
    url: '/business/topic/copy/' + id,
    method: 'post'
  });
}

// 主题详情已随 getTopic 返回章节列表；单章读写复用既有 /business/topicchapter 资源与权限点。
export function getTopicChapter(id) {
  return request({
    url: '/business/topicchapter/' + id,
    method: 'get'
  })
}

export function addTopicChapter(data) {
  return request({
    url: '/business/topicchapter',
    method: 'post',
    data
  })
}

export function updateTopicChapter(data) {
  return request({
    url: '/business/topicchapter',
    method: 'put',
    data
  })
}

export function delTopicChapter(id) {
  return request({
    url: '/business/topicchapter/' + id,
    method: 'delete'
  })
}

// [D21] 配置章节供给条款 —— 「③ 的一章怎么让商家参与」的唯一入口。
// ⚠️ 四个字段是一套形状，必须一起提交。尤其**计酬档必须配人头费上限**：留空的话
//    enroll 的 assertFeeWithinChapterCap 对 cap==null 是 fail-closed ⇒ 商家入驻恒被拒
//    ⇒ 等于配了一个谁也进不来的章，而且零告警。
// ⚠️ 计酬档只对 ③-自营 开放。后台发的主题天然是自营（insertCmsTopic 会声明 PLATFORM 内容槽），
//    所以正常不会被拒；被拒说明这主题是小程序发布器建的（内容槽=MEMBER），
//    那要先在「历史待归属」处理队列确认内容主体，再配置计酬档。
// ⚠️ **改档位**时该章有生效商家供给会被拒（会让存量供给的条款与章节口径打架 = 超发）；
//    同档位下调上限/门槛不受此限——那正是给已在营的章补配上限的场景。
export function configureChapterSupplyTerms(chapterId, terms) {
  return request({
    url: '/business/topicchapter/' + chapterId + '/supply-terms',
    method: 'put',
    data: terms
  });
}

export function getChapterServiceCapacity(chapterId) {
  return request({
    url: '/business/topicchapter/' + chapterId + '/service-capacity',
    method: 'get'
  })
}

export function configureChapterServiceCapacity(chapterId, capacity) {
  return request({
    url: '/business/topicchapter/' + chapterId + '/service-capacity',
    method: 'put',
    data: capacity
  })
}

export function getTopicLeadFulfillment(id) {
  return request({
    url: '/business/topic/' + id + '/lead-fulfillment',
    method: 'get'
  })
}
