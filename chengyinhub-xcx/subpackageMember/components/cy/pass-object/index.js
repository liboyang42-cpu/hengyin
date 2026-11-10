/* r3-pass:金色城市通票(我们自己的物件:票根 + 骑缝孔 + 城瘾印章),由碎片拼起来。
   state: scattered(碎片散开)→ assembled(拼好、微倾)→ open(印章亮起 = 解锁)。
   只做展示,不读任何数据;票面字由调用方给(城市名 / 类型,来自服务端)。
   全部用 transition(transform / opacity),不新增 keyframes;减少动态效果时直接给终态。 */
Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    state: { type: String, value: 'assembled' }, // scattered | assembled | open
    face: { type: String, value: '' },
    reducedMotion: { type: Boolean, value: false }
  }
})
