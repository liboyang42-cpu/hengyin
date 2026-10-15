// 漫游「跟随官方路线」HUD:只渲染 utils/roam-follow-route.js hudModel 的产物,不持有状态。
// 「退出跟随」只抛事件,由漫游页清叠加;不结束漫游、不改轨迹与结算。
Component({
  options: { styleIsolation: 'apply-shared' },
  properties: {
    hud: { type: Object, value: null },
  },
  methods: {
    onExit() { this.triggerEvent('exit') },
  },
})
