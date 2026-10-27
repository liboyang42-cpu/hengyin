const { withNativeBackGuard, onBackGuardLeave } = require('../../../utils/native-back-guard.js');

/* cy-back-guard · 给手写浮层用的返回守卫(G-2,2026-10-27)
 * 共享弹层(cy-sheet / cy-scene-sheet / cy-modal …)已自带守卫;roam / play 的 psheet 这类手写浮层
 * 在宿主 wxml 里加一行 <cy-back-guard show="{{浮层开着}}" bind:back="关最上层" /> 即可接入,
 * 与共享弹层同一张登记表,叠层时只有最上层吃返回键。 */
Component(withNativeBackGuard({
  properties: {
    // G-2:页面已有自己的 page-container 覆盖这个弹层时传 false 让位(一页只能有一个容器)
    backGuard: { type: Boolean, value: true },
    show: { type: Boolean, value: false },
  },
  methods: {
    _onBackGuardLeave() { onBackGuardLeave(this); },
  },
}, (vm) => vm.triggerEvent('back')));
