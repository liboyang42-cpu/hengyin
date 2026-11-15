'use strict'
// 去票夹的落点:带 topicId 就落在这条路线的票上,不是第一张票(signup 页 onLoad 读 topicId)。
const WALLET = '/subpackageMember/signup/index'

function ticketWalletUrl(topicId) {
  const id = String(topicId == null ? '' : topicId).trim()
  return /^[1-9]\d{0,18}$/.test(id) ? WALLET + '?topicId=' + id : WALLET
}

module.exports = { ticketWalletUrl }
