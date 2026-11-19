'use strict'
/* 图片加载淡入(motion-1118 v2):配 style/img-fade.wxss。
 *
 * 页面:Page({ data: { imgOn: {} }, onImgFadeLoad: imgFade.onImgFadeLoad, … })
 * 组件:Component({ data: { imgOn: {} }, methods: { onImgFadeLoad: imgFade.onImgFadeLoad, … } })
 * 模板:<image class="cy-img-fade {{imgOn[key] ? 'cy-img-fade--on' : ''}}" data-fade-key="{{key}}" bindload="onImgFadeLoad" />
 *
 * 只在 bindload 时标记;binderror 不标记,占位色留着。key 用 id / 序号这类不含「.」「[」的值,
 * 这里再规整一遍,保证 setData 路径合法 —— 模板里用同一个 key 查 imgOn,所以调用方给的 key 本身就要干净。 */

function fadeKey(e) {
  const ds = e && e.currentTarget && e.currentTarget.dataset
  const raw = ds ? ds.fadeKey : undefined
  if (raw === undefined || raw === null || raw === '') return ''
  const key = String(raw)
  return /[.[\]]/.test(key) ? '' : key
}

function onImgFadeLoad(e) {
  const key = fadeKey(e)
  if (!key || !this || typeof this.setData !== 'function') return
  const on = this.data && this.data.imgOn
  if (on && on[key]) return
  this.setData({ ['imgOn.' + key]: true })
}

module.exports = { fadeKey, onImgFadeLoad }
