/* 自由探索的参与商家卡(2026-10-22 业主参考的人物卡):照片铺满整张卡,
   名字和一句介绍压在图上,底部一层渐变 + 模糊托住文字;右下一颗深色「去看看」。
   数据来自主题详情里各站点的 registrationMerchantList,页面去重后传进来;没有就整块不出。 */
Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    items: { type: Array, value: [] },
    owned: { type: Boolean, value: false },
  },
  methods: {
    onOpen(e) {
      this.triggerEvent('open', { kind: 'merchant', id: e.currentTarget.dataset.id });
    },
  },
});
