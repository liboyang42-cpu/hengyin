// cy-station-silhouette:F07 到站前不显示的站点照片的占位(中性剪影 + 站序号)。
// 服务端对还没到的后续站下发 imgUrl:null + imgHidden:true;所有原本显示站图的位置在 imgHidden 时放这一枚,
// 不留空白,也不显示站名以外的信息。只做展示,不判断可见性 —— 可见性由服务端决定。
Component({
  properties: {
    num: { type: null, value: '' },   // 站序号;空则只画剪影
    size: { type: String, value: 'lg' },   // lg = 大封面,sm = 缩略图
  },
})
