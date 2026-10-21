'use strict'

/* BL-03 漫游位置同步的离线提示。
 *
 * 只认两类事实,不猜:
 *   · 位置上报(/api/roam/reveal、/api/roam/presence)连续 FAIL_THRESHOLD 次网络失败(req 的 netFail 哨兵);
 *   · 系统网络状态回调 isConnected=false。
 * 任一上报成功(code=200)或系统报网络恢复且没有未消的连续失败 ⇒ 提示消失。
 * HTTP 异常(httpFail)与后端业务错误不是「网络断开」,不计数也不清零 —— 不能把服务端问题说成断网。
 * 只管提示,不改计时、不改重试、不丢任何本地格子(格子本来就先落本机)。
 */

const FAIL_THRESHOLD = 2
const OFFLINE_TEXT = '网络断开，位置暂不同步'

function createRoamSyncStatus() {
  let fails = 0
  let netDown = false

  function offline() {
    return netDown || fails >= FAIL_THRESHOLD
  }

  return {
    /** 记一次上报结果,返回当前是否离线。 */
    report(res) {
      if (res && res.netFail === true) fails += 1
      else if (res && (res.code == 200 || res.code == '200')) { fails = 0; netDown = false }
      return offline()
    },
    /** 系统网络回调。恢复时连续失败计数也清零:下一次上报再失败会重新累计。 */
    setConnected(isConnected) {
      netDown = isConnected === false
      if (!netDown) fails = 0
      return offline()
    },
    reset() { fails = 0; netDown = false; return false },
    offline,
  }
}

module.exports = { createRoamSyncStatus, FAIL_THRESHOLD, OFFLINE_TEXT }
