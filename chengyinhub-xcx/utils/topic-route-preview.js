// Presentation only: complete verified provider segments, or explicitly labelled chapter-local node previews.
// Does not calculate route distance, walking time, GPS validity, progress, completion or rewards.
function routeCoordinate(value, limit) {
  if (typeof value !== 'number' && typeof value !== 'string') return null;
  if (typeof value === 'string' && !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value.trim())) return null;
  const number = Number(value);
  return Number.isFinite(number) && Math.abs(number) <= limit ? number : null;
}
function routePoint(row) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) return null;
  const latitude = routeCoordinate(row.latitude === undefined ? row.lat : row.latitude, 90);
  const longitude = routeCoordinate(row.longitude === undefined ? row.lng : row.longitude, 180);
  return latitude === null || longitude === null ? null : { latitude, longitude };
}

function build(chaptersList) {
    const chapters = (Array.isArray(chaptersList) ? chaptersList : []).filter(function (chapter) {
      return chapter && typeof chapter === 'object' && !Array.isArray(chapter);
    });
    const valid = [], polyline = [];
    let roadSegments = 0, straightSegments = 0;
    chapters.forEach(function (chapter) {
      const nodes = (Array.isArray(chapter.nodes) ? chapter.nodes : []).slice().sort(function (a, b) {
        return (Number(a && a.sortId) || 0) - (Number(b && b.sortId) || 0);
      }).filter(function (node) { return routePoint(node) !== null; });
      nodes.forEach(function (node) { valid.push(node); });
      let points = [];
      if (chapter.routeGeometryAvailable === true && !chapter.routeGeometryErrorCode) {
        try {
          const geometry = typeof chapter.routeGeometry === 'string' ? JSON.parse(chapter.routeGeometry) : chapter.routeGeometry;
          // 不过滤坏道路点：任何断点都让整章道路降级，不能跳点后虚连成道路。
          if (Array.isArray(geometry) && geometry.length >= 2 && Array.from(geometry).every(function (row) { return routePoint(row) !== null; }))
            points = geometry.map(routePoint);
        } catch (error) { points = []; }
      }
      const road = points.length >= 2;
      if (road) roadSegments += 1;
      else points = nodes.map(routePoint);
      if (points.length >= 2) {
        if (!road) straightSegments += 1;
        polyline.push({
          points: points, color: '#1A1A1A', width: 5, borderColor: '#FFFFFF', borderWidth: 1, /* ds-ok: 原生地图不支持CSS token，沿用既有路线颜色 */
          arrowLine: road, dottedLine: !road
        });
      }
    });
    const markers = valid.map(function (node, i) {
      const point = routePoint(node);
      return {
        id: i, latitude: point.latitude, longitude: point.longitude,
        width: 24, height: 24, iconPath: '/images/icon_map4.png',
        callout: {
          content: (i + 1) + (node.name ? ' ' + node.name : ''),
          color: '#FFFFFF', bgColor: '#1A1A1A', /* ds-ok: 原生地图callout不支持CSS token，沿用既有颜色 */
          padding: 6, borderRadius: 8, fontSize: 11, display: 'BYCLICK'
        }
      };
    });
    // 包含道路以及所有节点，不能因为某章已有道路就把另一章单独的节点排除出视野。
    const include = markers.map(function (marker) { return { latitude: marker.latitude, longitude: marker.longitude }; });
    polyline.forEach(function (line) { line.points.forEach(function (point) { include.push(point); }); });
    const source = roadSegments ? (straightSegments ? '道路段参考 · 虚线为节点直线预览，章间未规划' : '道路段参考 · 按章节分段，章间未规划')
      : (straightSegments ? '节点直线预览 · 尚未规划步行路线，章间未规划' : '仅显示节点位置 · 尚未规划步行路线，章间未规划');
    return {
      routeReady: include.length > 0,
      routeIsRoad: roadSegments > 0 && straightSegments === 0,
      routeSourceText: include.length ? source : '', routeMarkers: markers, routePolyline: polyline, routeIncludePoints: include,
      mapLatitude: include.length ? include[0].latitude : 0,
      mapLongitude: include.length ? include[0].longitude : 0
    };
}

module.exports = { build, routeCoordinate, routePoint };
