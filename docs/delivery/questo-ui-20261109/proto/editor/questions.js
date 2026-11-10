// 「待定问题」屏:要业主拍板才能写代码的问题。每条同时是一项审阅。
(function () {
  var Q = [
    ['拖动起手', '长按 350 毫秒抬起(块右侧的六点按下 120 毫秒就抬起)。默认这样,可以吗?'],
    ['拆段位置', '拖到段落中间时只在句号 / 问号 / 感叹号 / 分号 / 换行之后拆,不在句子中间拆。默认这样,可以吗?'],
    ['撤销范围', '只撤销最近一步(移动 / 插入 / 删除 / 拆段),5 秒后撤销条消失但仍可连续撤销到上一步。要不要做成多步撤销?默认:最近 20 步。'],
    ['分支去向', '分支去向只有:继续本章 / 跳到第 N 章 / 结束本章。要不要支持跳到本章某一段?默认:不支持。'],
    ['玩法弹窗', '玩法开始弹窗按模板里的「嵌入 / 整屏」显示打开方式;嵌入式玩法开始后直接在故事流里展开。默认这样,可以吗?']
  ];
  var html = '<view class="dwq"><view class="dwq__h">待定问题</view><view class="dwq__s">这几条要你拍板后才写代码。右栏每一条都能选「通过 / 要改 / 有疑问」并写批注。</view>' +
    Q.map(function (q, i) { return '<view class="dwq__i" data-mk="' + (i + 1) + '"><view class="dwq__n">' + (i + 1) + '</view><view><view class="dwq__t">' + q[0] + '</view><view class="dwq__b">' + q[1] + '</view></view></view>'; }).join('') + '</view>';
  var notes = '<ol class="notes">' + Q.map(function (q, i) { return '<li data-n="' + (i + 1) + '"><b>' + q[0] + '</b>:' + q[1] + '</li>'; }).join('') + '</ol>';
  document.getElementById('screens').insertAdjacentHTML('beforeend', '<section class="scr cyp theme-light" data-id="q" data-title="待定问题" data-group="说明" data-src="">' + html + notes + '</section>');
})();
