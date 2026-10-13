import request from '@/utils/request'

export function listClubDirectReceivables(topicId) {
  return request({ url: '/business/club-direct-merchant/list', method: 'get', params: { topicId } })
}

export function recordClubDirectPayment(id, data) {
  return request({ url: `/business/club-direct-merchant/${id}/record`, method: 'put', data })
}

// ③ 计酬档应付月结批次(自然月，次月结上月）
// ⚠️ 这里所有「打款/开票」都是**记录事实**，不是转账动作 —— 转账在对公网银，
//    平台的打款能力收口在系统1（PR#78），本模块只记账。

// 某自然月的全部批次
export function listPayableBatch(periodYm) {
  return request({
    url: '/business/payablebatch/list',
    method: 'get',
    params: { periodYm: periodYm }
  })
}

// 生成月结批次。⚠️ 只能结已过完的月份 —— 月中生成会让之后落进该月的台账行无处可去。
export function generatePayableBatch(periodYm) {
  return request({
    url: '/business/payablebatch/generate',
    method: 'post',
    data: { periodYm: periodYm }
  })
}

// 对账确认：PENDING → CONFIRMED
export function confirmPayableBatch(id) {
  return request({
    url: '/business/payablebatch/' + id + '/confirm',
    method: 'put'
  })
}

// 记录打款事实：CONFIRMED → PAID。⚠️ 带 CONFIRMED 前置 = 先对账再打款
export function markPayableBatchPaid(id, payVoucherNo) {
  return request({
    url: '/business/payablebatch/' + id + '/paid',
    method: 'put',
    data: { payVoucherNo: payVoucherNo }
  })
}

// 记录已开蓝票：NONE → ISSUED。发票轴与打款轴独立
export function markPayableBatchInvoice(id, invoiceNo) {
  return request({
    url: '/business/payablebatch/' + id + '/invoice',
    method: 'put',
    data: { invoiceNo: invoiceNo }
  })
}

// 记录已红冲：ISSUED → RED。已开票批次里的核销被撤销时，税务上必须有红字发票对应
export function markPayableBatchInvoiceRed(id, redInvoiceNo) {
  return request({
    url: '/business/payablebatch/' + id + '/invoice-red',
    method: 'put',
    data: { redInvoiceNo: redInvoiceNo }
  })
}
