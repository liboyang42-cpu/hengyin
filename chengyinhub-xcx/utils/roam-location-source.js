'use strict'

// 漫游实时定位源(C:U-091):把「bgTracker 申请(后台优先、降级前台)+ wx.onLocationChange 订阅 + 退订释放」
// 收成一个可注入对象。页面只认 start/stop,单测可注入假 tracker/wx 回放一串定位点驱动 _realTick。
function createRoamLocationSource(deps) {
  const tracker = deps && deps.tracker
  const wxApi = deps && deps.wx
  const owner = (deps && deps.owner) || 'roam'
  let listener = null
  return {
    /** onLocation(loc) 每个点回调一次;success/fail 对应 tracker 申请结果。重复 start 先退掉旧订阅。 */
    start(onLocation, cb) {
      this.stop({ keepTracker: true })
      tracker.acquire(owner, {
        success: () => {
          listener = (loc) => onLocation(loc)
          wxApi.onLocationChange(listener)
          if (cb && cb.success) cb.success()
        },
        fail: (err) => { if (cb && cb.fail) cb.fail(err) },
      })
    },
    stop(opts) {
      if (listener) {
        try { wxApi.offLocationChange(listener) } catch (e) { /* 页面已卸载时 off 可能抛,订阅本就失效 */ }
        listener = null
      }
      if (!(opts && opts.keepTracker)) tracker.release(owner)
    },
    get listening() { return !!listener },
  }
}

module.exports = { createRoamLocationSource }
