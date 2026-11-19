// 账户收益正文由 scene-asset-earnings 承载；本页负责三级 scene 与「提现」客服入口。
const toast = require('../../../../utils/toast.js');
const app = getApp()
const merchantTheme = require('../../../../utils/merchant-theme.js')
const roleGuard = require('../../../../utils/roleGuard.js')
const { getScene } = require('../../../../utils/scene-registry.js')
// 2026-09-15 起「提现」不再走银行卡表单:平台不打款,一律弹平台客服微信线下处理。
const withdrawCs = require('../../../../utils/withdraw-cs.js')

function identityKey() {
  const gd = app.globalData || {}
  const member = typeof app.getUserID === 'function' ? app.getUserID() : gd.user_id
  const role = typeof app.getUserRole === 'function' ? app.getUserRole() : gd.role
  const type = typeof app.getUserType === 'function' ? app.getUserType() : gd.user_type
  return [member == null ? '' : member, role || '', type == null ? '' : type].join('|')
}

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    sceneStack: [],
    sceneCurrent: null,
    merchantView: false,
  },

  onLoad(options) {
    this._hidden = false
    this._unloaded = false
    // 2026-10-30 走查:商家从项目主页、我的「我的收益」等入口进来没带 theme 参数,整页落成玩家深色;按身份兜底
    if ((options && options.theme === 'merchant') || roleGuard.isMerchantView()) this.setData({ merchantView: true })
    if (!options || options.scene !== 'merchant-profit') return
    const params = options.topicId ? { topicId: options.topicId } : {}
    this.openScene({ detail: { id: 'merchant-profit', params } })
  },

  onShow() { this._hidden = false; if (this.data.merchantView) merchantTheme.merchantPageShow(); else merchantTheme.merchantPageRestore() },
  onHide() { this._hidden = true; this._withdrawEpoch = (this._withdrawEpoch || 0) + 1; merchantTheme.merchantPageRestore() },
  onUnload() { this._unloaded = true; this._withdrawEpoch = (this._withdrawEpoch || 0) + 1; merchantTheme.merchantPageRestore() },

  blockSceneTouch() {},

  openScene(event) {
    const detail = (event && event.detail) || {}
    if (!detail.id) return
    // G-9:提现记录 / 邀请记录从「更多」菜单打开时,先把菜单收起来(回来时不再叠在页面上)
    const ae = typeof this.selectComponent === 'function' ? this.selectComponent('#assetEarnings') : null
    if (ae && ae.data && ae.data.moreShow) ae.closeMore()
    if (detail.id === 'member-withdraw') {
      this.openWithdrawSheet()
      return
    }
    const scene = getScene(detail.id, detail.params || {})
    this.setData({ sceneStack: [scene], sceneCurrent: scene })
  },

  closeScene() {
    this.setData({ sceneStack: [], sceneCurrent: null })
  },

  // 2026-09-15 收款模型定稿 §3:平台不打款。「提现」弹平台客服微信;
  // PG-033:原银行卡提现表单与 /api/withdrawal/create 提交死代码已删除。
  openWithdrawSheet() {
    if (this._hidden || this._unloaded) return
    const identity = identityKey()
    const epoch = (this._withdrawEpoch || 0) + 1
    this._withdrawEpoch = epoch
    roleGuard.load(() => {
      if (this._hidden || this._unloaded || epoch !== this._withdrawEpoch || identity !== identityKey()) return
      if (!roleGuard.can('withdrawable')) {
        toast('暂无提现权限')
        return
      }
      withdrawCs.showWithdrawCsPopup()
    })
  },
})
