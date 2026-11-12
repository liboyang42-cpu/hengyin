const { readReducedMotion } = require('../../../../utils/motion-preference.js')
/**
 * cy-club-director-broadcast-sheet · D7 定向广播(弹窗)。
 * Figma s7SEFaoJ3GQUIxJhdqcFUb 288:496。T1 底部弹窗,复用 cy-scene-sheet variant="half"。
 * scope 用 chip-group 切换(全部/队伍/角色);scope!=ALL 时展示 targets 行列表供选择;
 * previewRows 是收编行列表格式的单行数组(如 [{id:'preview',title:'发送前预览',value:'5 人·1 队'}]),
 * 组件本身不做人数聚合——那是宿主页面 broadcastRecipientCount 之类的既有逻辑。
 */
Component({
  properties: {
    show: { type: Boolean, value: false },
    title: { type: String, value: '定向广播' },
    scopeOptions: { type: Array, value: [] }, // [{id,label}]
    scope: { type: String, value: 'ALL' },
    targets: { type: Array, value: [] },
    content: { type: String, value: '' },
    audience: { type: Array, value: [] }, // 2026-11-01 顶部横滑头像,点了单个加减 [{id,name,initial,sub,picked}]
    countText: { type: String, value: '' },
    confirmText: { type: String, value: '确认发送' },
    confirmDisabled: { type: Boolean, value: false },
    sendPhase: { type: String, value: '' }, // '' | sending | sent:宿主在服务端确认后给 sent,本组件只管逐个打勾与按钮变色
  },
  data: { ticking: false, sentMap: {}, sentDone: false },
  observers: { sendPhase(p) { this._phase(p) } },
  lifetimes: { detached() { this._clear() } },
  methods: {
    _clear() { (this._ts || []).forEach(clearTimeout); this._ts = [] },
    _phase(p) {
      this._clear()
      if (p !== 'sent') { this.setData({ ticking: false, sentMap: {}, sentDone: false }); return }
      const reduced = readReducedMotion()
      const ids = (this.data.audience || []).filter((x) => x.picked).map((x) => x.id)
      const map = {}
      const step = reduced ? 0 : 160 // 三位同行人依次打勾,间隔 160ms(超过 8 位的并入最后一拍)
      this.setData({ ticking: true, sentMap: {}, sentDone: false })
      ids.forEach((id, i) => {
        const at = step * (Math.min(i, 7) + 1)
        this._ts.push(setTimeout(() => { map[id] = true; this.setData({ sentMap: Object.assign({}, map) }) }, at))
      })
      const end = step * Math.min(ids.length, 8)
      this._ts.push(setTimeout(() => this.setData({ sentDone: true }), end))
      this._ts.push(setTimeout(() => this.triggerEvent('sentdone', { reduced }), end + (reduced ? 0 : 700))) // 700ms 后宿主收起半屏
    },
    onClose() { this.triggerEvent('close') },
    onScopeChange(e) { this.triggerEvent('selectscope', e.detail) },
    onPersonTap(e) { this.triggerEvent('toggleperson', { id: e.currentTarget.dataset.id }) },
    onTargetTap(e) { this.triggerEvent('selecttarget', { id: e.currentTarget.dataset.id }) },
    onContentInput(e) {
      this.triggerEvent('input', { value: String((e && e.detail && e.detail.value) || '') })
    },
    onConfirmTap() {
      if (this.data.confirmDisabled || this.data.sendPhase) return
      this.triggerEvent('confirm')
    },
  },
})
