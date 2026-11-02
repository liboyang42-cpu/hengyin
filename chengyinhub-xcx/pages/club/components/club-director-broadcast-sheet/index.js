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
    audience: { type: Array, value: [] }, // 2026-11-01 顶部横滑头像:这次会发给谁 [{id,name,initial,sub}]
    countText: { type: String, value: '' },
    confirmText: { type: String, value: '确认发送' },
    confirmDisabled: { type: Boolean, value: false },
  },
  methods: {
    onClose() { this.triggerEvent('close') },
    onScopeChange(e) { this.triggerEvent('selectscope', e.detail) },
    onTargetTap(e) { this.triggerEvent('selecttarget', { id: e.currentTarget.dataset.id }) },
    onContentInput(e) {
      this.triggerEvent('input', { value: String((e && e.detail && e.detail.value) || '') })
    },
    onConfirmTap() {
      if (this.data.confirmDisabled) return
      this.triggerEvent('confirm')
    },
  },
})
