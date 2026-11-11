// 「待定问题」屏:要业主拍板后才写代码的问题。每条写清默认做法,只需回 是 / 否。每条同时是一项审阅。
(function () {
  var Q = [
    ['B 调色盘', '六色各两档(柠檬绿 / 天蓝 / 珊瑚 / 金 / 紫 / 纸),每枚只取 2–3 色;以后新增图标也只从这里取色,不另加色。'],
    ['B 动作长度', '每枚进场 ≤ 0.9s,常驻循环 3–4s 一轮,闪光只在循环里「完成」那一下出现一次。'],
    ['通行证页', '按标准玩家 UI(实色卡 + 列表行),卡内保留一枚 3D 钥匙;只在玩家「我的」里,商家端不出现。'],
    ['参考取舍', '只借参考的结构与节奏(配色纪律、同色系阴影、主次动作),不借形象、不借原配色、不借文案。'],
  ];

  var html = '<view class="dwq"><view class="dwq__h">待定问题</view><view class="dwq__s">这几条要你拍板后才写代码。右栏每一条都能选「通过 / 要改 / 有疑问」并写批注,点「保存」后我这边能直接读到。</view>' +
    Q.map(function (q, i) { return '<view class="dwq__i" data-mk="' + (i + 1) + '"><view class="dwq__n">' + (i + 1) + '</view><view><view class="dwq__t">' + q[0] + '</view><view class="dwq__b">' + q[1] + '</view></view></view>'; }).join('') + '</view>';
  var notes = '<ol class="notes">' + Q.map(function (q, i) { return '<li data-n="' + (i + 1) + '"><b>' + q[0] + '</b>:' + q[1] + '</li>'; }).join('') + '</ol>';
  document.getElementById('screens').insertAdjacentHTML('beforeend', '<section class="scr cyp theme-light" data-id="q" data-title="待定问题" data-group="F · 待定问题" data-src="">' + html + notes + '</section>');
})();
