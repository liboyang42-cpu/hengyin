(function () {
  var Q = [
    ['深色范围', '默认：玩家看到的地图（漫游、游玩、附近）全部深色；商家和编辑器保持浅色，和你定的「玩家深、商家浅」一致。可以吗？'],
    ['地名显示', '默认：只显示道路名、大地标和参与我们的商铺名，其他小商铺的地图文字隐藏。可以吗？'],
    ['没有照片的地点', '默认：没有照片的地点用深色圆形和小图标，不放占位图。可以吗？'],
    ['夜景', '默认：实时天气这次做（见「实时天气」屏）。按时间切换夜景放到上线后。可以吗？'],
    ['同场路线默认', '默认：开始前「让同场队伍看到我的路线」是打开的，每个人都可以关。可以吗？']
  ];
  var html = '<view class="dwq"><view class="dwq__h">待定问题</view>' +
    Q.map(function (q, i) { return '<view class="dwq__i" data-mk="' + (i + 1) + '"><view class="dwq__n">' + (i + 1) + '</view><view><view class="dwq__t">' + q[0] + '</view><view class="dwq__d">' + q[1] + '</view></view></view>'; }).join('') + '</view>';
  var notes = '<ol class="notes">' + Q.map(function (q, i) { return '<li data-n="' + (i + 1) + '"><b>' + q[0] + '</b>：' + q[1] + '</li>'; }).join('') + '</ol>';
  document.getElementById('screens').insertAdjacentHTML('afterbegin', '<section class="scr cyp theme-light" data-id="q" data-title="待定问题" data-group="说明" data-src="">' + html + notes + '</section>');
})();
