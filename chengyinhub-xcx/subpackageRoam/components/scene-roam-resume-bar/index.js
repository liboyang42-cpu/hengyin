// 首页「继续上次漫游」条:读本机玩家桶里的漫游恢复记录(roam_memory_v1:<playerId>:recovery),
// 用与 roam 页同一份 validRecovery 判据;有未结算的漫游才显示。不发任何后端请求。
const { validRecovery } = require('../../../utils/roam-recovery.js')
const { currentPlayerRoamMemory } = require('../../../utils/roam-player-memory.js')

// 本次小程序进程内已被用户关掉的记录(按玩家+记录指纹),重进首页不再反复弹
const dismissed = {}

function fingerprint(record) {
  return String(record.sessionId || record.clientSessionKey || '')
}

Component({
  options: { addGlobalClass: true },
  data: { visible: false },
  lifetimes: { attached() { this.refresh() } },
  pageLifetimes: { show() { this.refresh() } },
  methods: {
    refresh() {
      let visible = false
      try {
        const memory = currentPlayerRoamMemory(getApp(), wx)
        const state = memory.readRecoveryState()
        if (memory.playerId && state.ok && state.record && validRecovery(state.record)) {
          this._key = `${memory.playerId}:${fingerprint(state.record)}`
          visible = !dismissed[this._key]
        }
      } catch (_) { visible = false }
      if (visible !== this.data.visible) this.setData({ visible })
    },
    onContinue() {
      wx.switchTab({ url: '/pages/roam/index' })
    },
    onDismiss() {
      if (this._key) dismissed[this._key] = true
      this.setData({ visible: false })
    },
  },
})
