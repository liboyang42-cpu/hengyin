// 「待定问题」屏:要业主拍板后才写代码的问题。每条写清默认做法,只需回 是 / 否。每条同时是一项审阅。
(function () {
  var Q = [
    ['图标风格', '十枚先只定一种:A 3D 玻璃 / B 精致插画 / C 波点。默认建议 B 进主包做基础,A 只用于通行证、勋章这类「奖励时刻」,C 只用于章节数字与章节印。'],
    ['3D 落地方式', '3D 图标与钥匙开场一期用预渲染序列帧(WebP),放分包;threejs-miniprogram 实时渲染放二期,等低端机实测后再定。'],
    ['钥匙开场文案', '眉题「通行证」、标题「赢取你的钥匙」、正文一行;这段文案要不要跟你的通行证规则对齐(比如年卡 / 月卡、¥0 开玩)。'],
    ['钥匙开场出现时机', '只在第一次进通行证页 / 购买成功时播一次完整版(约 10s,可跳过),之后进页只播 4.6s 之后的待机态。'],
    ['玻璃范围', '玻璃只用在两处:平台的通行证页、星尘外观的主题内卡片与弹窗;纸本 / 末世 / 夜城不用玻璃。'],
    ['章节开场点阵', '章节数字与章节印都换成圆点点阵(替换现有像素块),四种外观共用一套运动,只换颜色。'],
    ['减少动态效果', '系统开启减弱动态时:所有图标静帧、钥匙开场直接停在终态、玻璃保留(不算动态)。'],
  ];
  var html = '<view class="dwq"><view class="dwq__h">待定问题</view><view class="dwq__s">这几条要你拍板后才写代码。右栏每一条都能选「通过 / 要改 / 有疑问」并写批注,点「保存」后我这边能直接读到。</view>' +
    Q.map(function (q, i) { return '<view class="dwq__i" data-mk="' + (i + 1) + '"><view class="dwq__n">' + (i + 1) + '</view><view><view class="dwq__t">' + q[0] + '</view><view class="dwq__b">' + q[1] + '</view></view></view>'; }).join('') + '</view>';
  var notes = '<ol class="notes">' + Q.map(function (q, i) { return '<li data-n="' + (i + 1) + '"><b>' + q[0] + '</b>:' + q[1] + '</li>'; }).join('') + '</ol>';
  document.getElementById('screens').insertAdjacentHTML('beforeend', '<section class="scr cyp theme-light" data-id="q" data-title="待定问题" data-group="F · 待定问题" data-src="">' + html + notes + '</section>');
})();
