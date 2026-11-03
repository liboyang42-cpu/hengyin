(function () {
  var Q = [
    ['深色范围', '默认：玩家看到的地图（漫游、游玩、附近）全部深色；商家和编辑器保持浅色，和你定的「玩家深、商家浅」一致。可以吗？'],
    ['地名显示', '默认：只显示道路名和大地标，小商铺的地图文字隐藏，让照片图钉成为主角。可以吗？'],
    ['没有照片的地点', '默认：没有照片的地点用深色圆形和小图标，不放占位图。可以吗？'],
    ['昼夜和天气', '默认：这次只做深色和云层。按时间切换夜景、下雨效果放到上线后。可以吗？'],
    ['3D 插画街区', '默认：南京路这类手绘 3D 街区放到上线后，先做一个地方看效果。可以吗？']
  ];
  var html = '<view class="dwq"><view class="dwq__h">待定问题</view>' +
    Q.map(function (q, i) { return '<view class="dwq__i" data-mk="' + (i + 1) + '"><view class="dwq__n">' + (i + 1) + '</view><view><view class="dwq__t">' + q[0] + '</view><view class="dwq__d">' + q[1] + '</view></view></view>'; }).join('') + '</view>';
  var notes = '<ol class="notes">' + Q.map(function (q, i) { return '<li data-n="' + (i + 1) + '"><b>' + q[0] + '</b>：' + q[1] + '</li>'; }).join('') + '</ol>';
  document.getElementById('screens').insertAdjacentHTML('afterbegin', '<section class="scr cyp theme-light" data-id="q" data-title="待定问题" data-group="说明" data-src="">' + html + notes + '</section>');
})();
