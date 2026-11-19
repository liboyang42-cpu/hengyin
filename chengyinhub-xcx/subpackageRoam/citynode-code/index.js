// C2 玩家据点核销码:到店打卡完成后出码,商家扫码后端校验发券(防伪造GPS白领券)。
// PG-091:出码逻辑只在 components/cy/scene-qr-citynode,本页是透传 poiId/name 的薄壳。
// PG-005:进页先判登录,游客由 cy-access-gate 盖住,不发出码请求。
const app = getApp();
const { safeDecode } = require('../../utils/safe-decode.js');

Page({
  data: {
    poiId: '',
    name: '节点',
    guest: false,
    // missing 与出码失败分家:缺参不可能靠重试变出参数,给它重试就是假按钮。
    state: 'ready', // ready | missing
  },

  onLoad(options) {
    const o = options || {};
    const poiId = o.poiId ? String(o.poiId) : '';
    this.setData({
      poiId,
      name: o.name ? safeDecode(o.name) : '节点',
      state: poiId ? 'ready' : 'missing',
      guest: !(typeof app.getUserID === 'function' && app.getUserID()),
    });
  },

  // 缺参态的唯一真出口
  onMissingBack() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.switchTab({ url: '/pages/roam/index' });
  },

  // 出码卡✕ / 游客态返回:退出出码页
  onClose() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.reLaunch({ url: '/pages/roam/index' });
  },
});
