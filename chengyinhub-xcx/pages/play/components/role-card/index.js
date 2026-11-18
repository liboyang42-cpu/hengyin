// cy-role-card · F05 组队角色卡(站点卡 encounter 区域)。
// 数据只来自 GET /api/play/encounter 的 roleView;组队只给本人那一份,SOLO 合并全部视图(见 utils/role-card-view.js)。
const { roleCardView } = require('../../utils/role-card-view.js');

Component({
  properties: {
    view: { type: Object, value: null },
  },
  data: { card: null },
  observers: {
    view(value) { this.setData({ card: roleCardView(value) }); },
  },
});
