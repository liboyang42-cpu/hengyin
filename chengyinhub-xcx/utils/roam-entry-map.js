'use strict';

const H = require('./roam-hangout.js');
const { toTimestamp, chinaParts } = require('./datetime.js');
const { readTags, presentationForPoi } = require('./roam-poi-presentation.js');
const { readFontScale, mapFontSize } = require('./font-scale.js');

const FALLBACK_TEAM_ICON = '/images/d_profile.png';
const TEAM_MARKER_TAG = 4;
const POST_MARKER_TAG = 5;
const PLACE_MARKER_TAG = 6;
const PLACE_ICON = '/images/roam-pushpin.png';
// V3-35:城市漫游目录不再是单独清单,目录地点(导入工具写了种子标签的已上架平台点位)直接画在入口地图上。
const SEED_PREFIX = 'roam:seed:v1:';

function validPoint(row) {
  return row && H.numOrNull(row.latitude) != null && H.numOrNull(row.longitude) != null;
}
function coverOf(row, getImgUrl) {
  const raw = row && (row.picUrl || row.coverUrl || row.imgUrl);
  return raw && getImgUrl ? getImgUrl(raw) : (raw || '');
}
// j4 F6:场次行只认本场次的队伍(队伍要本场票);主题行才按主题汇总各场次的队伍。
function teamsFor(row, teams) {
  if (row && row.kind === 'activity') return (teams || []).filter((team) => String(team.activityId) === String(row.id));
  const topicId = row && (row.topicId || (row.kind === 'topic' ? row.id : null));
  return (teams || []).filter((team) => topicId && String(team.topicId) === String(topicId));
}

// V1-P32(业主「地图上放的活动 组队要都能看到」):活动 / 主题与招募中的队伍在地图上常显一张深色小卡(标题 + 一行事实),
// 不再只是 14px 的点。卡片文字只用服务端回来的字段;没有的那一行不写。
const CARD_BG = '#1C1C1EF2'; /* ds-ok V3-18 深色浮层同一灰 */
const CARD_TEXT = '#FFFFFF'; /* ds-ok 深色卡白字 */
function cardCallout(lines) {
  return {
    content: lines.filter(Boolean).join('\n'), color: CARD_TEXT, bgColor: CARD_BG,
    fontSize: mapFontSize(12, readFontScale()), borderRadius: 12, padding: 8, display: 'ALWAYS', textAlign: 'left',
  };
}
function teamLine(team) {
  const joined = Number(team.joinedCount); const max = Number(team.maxMembers);
  return Number.isFinite(joined) && Number.isFinite(max) && max > 0 ? joined + '/' + max + ' 人 · 招募中' : '招募中';
}
function buildTeamMarkers(teams) {
  return (teams || []).filter((team) => validPoint(team) && Number(team.teamId) > 0).map((team) => ({
    id: Number(team.teamId) * 10 + TEAM_MARKER_TAG,
    latitude: Number(team.latitude),
    longitude: Number(team.longitude),
    width: 22,
    height: 22,
    anchor: { x: 0.5, y: 0.5 },
    zIndex: 12,
    iconPath: FALLBACK_TEAM_ICON,
    callout: cardCallout([String(team.title || '玩家队伍'), teamLine(team)]),
  }));
}
function withActivityCards(markers, cards) {
  return markers.map((marker) => {
    const hit = H.parseMarkerId(marker.id);
    const card = hit && (cards || []).find((row) => row.kind === hit.kind && String(row.id) === String(hit.id));
    if (!card) return marker;
    return Object.assign({}, marker, {
      width: Math.max(marker.width, 20), height: Math.max(marker.height, 20),
      callout: marker.callout || cardCallout([card.title, card.meta || card.tag]),
    });
  });
}

function imageList(row) {
  const source = row && (row.postImages || row.imageUrls || row.images);
  return Array.isArray(source) ? source.filter(Boolean) : [];
}

function buildPostMarkers(rows, getImgUrl) {
  return (rows || []).filter((row) => validPoint(row) && imageList(row).length).map((row) => ({
    id: Number(row.id) * 10 + POST_MARKER_TAG,
    latitude: Number(row.latitude),
    longitude: Number(row.longitude),
    width: 22,
    height: 22,
    anchor: { x: 0.5, y: 0.5 },
    zIndex: 13,
    iconPath: coverOf({ picUrl: imageList(row)[0] }, getImgUrl),
  }));
}

function isCatalogPlace(row) {
  return !!row && Number(row.id) > 0 && Number(row.type) === 1 && validLatLng(row)
    && readTags(row.tags).some((tag) => typeof tag === 'string' && tag.indexOf(SEED_PREFIX) === 0);
}
function validLatLng(row) {
  return H.numOrNull(row.lat) != null && H.numOrNull(row.lng) != null;
}
function placeView(row) {
  const p = presentationForPoi(row);
  return { id: Number(row.id), name: String(row.name || ''), address: String(row.address || ''), catLabel: p.catLabel,
    tags: typeof row.tags === 'string' ? row.tags : JSON.stringify(readTags(row.tags)), lat: Number(row.lat), lng: Number(row.lng) };
}
function buildPlaceMarkers(places) {
  return (places || []).filter(isCatalogPlace).map((row) => ({
    id: Number(row.id) * 10 + PLACE_MARKER_TAG,
    latitude: Number(row.lat),
    longitude: Number(row.lng),
    width: 20,
    height: 24,
    anchor: { x: 0.5, y: 1 },
    zIndex: 4,
    iconPath: PLACE_ICON,
  }));
}

// 第十二轮 k7(j4 N5):同一主题的几场卡片靠场次开始时刻分开(真数据,不是说明行)。只给场次,不给主题。
function sessionWhen(row) {
  if (!row || row.kind !== 'activity') return '';
  const d = row.startDate ? chinaParts(row.startDate) : null;
  if (!d) return '';
  const pad = (n) => (n < 10 ? '0' : '') + n;
  return d.month + '月' + d.day + '日 ' + pad(d.hours) + ':' + pad(d.minutes);
}

function activityView(row, teams, getImgUrl) {
  // 与 ActivityLifecycle.timeBucket 一致：结束时刻已过且尚未开始条件不成立。
  const now = Date.now();
  const ended = !!row && row.kind === 'activity' && now > toTimestamp(row.endDate)
    && !(now < toTimestamp(row.startDate));
  const linkedTeams = teamsFor(row, teams);
  const participantFaces = linkedTeams.reduce((all, team) => all.concat(Array.isArray(team.memberAvatars) ? team.memberAvatars : []), []).filter(Boolean).slice(0, 3);
  const participantCount = Number(row && row.participantCount) || linkedTeams.reduce((sum, team) => sum + (Number(team.joinedCount) || 0), 0);
  const dynamicImages = imageList(row).slice(0, 3).map((url) => getImgUrl ? getImgUrl(url) : url);
  const registered = !!(row && (row.registered === true || row.viewerRegistered === true || row.viewerHasTicket === true))
    || linkedTeams.some((team) => team && team.viewerHasTicket === true);
  const free = row && Number(row.productType) === 2;
  const distance = H.fmtKm(row && row.distance);
  return {
    key: (row && row.kind) + '-' + (row && row.id),
    id: row && row.id,
    kind: row && row.kind,
    topicId: row && (row.topicId || (row.kind === 'topic' ? row.id : null)),
    title: String((row && row.name) || '未命名活动'),
    cover: coverOf(row, getImgUrl),
    tag: free ? '自由探索' : '城市定向',
    meta: [ended && '已结束', sessionWhen(row), distance && ('距你 ' + distance), !ended && linkedTeams.length && (linkedTeams.length + ' 支队伍招募')].filter(Boolean).join(' · '),
    address: String((row && row.addressName) || ''),
    participantFaces,
    participantCount,
    dynamicImages,
    registered,
    ended,
    teams: (ended ? [] : linkedTeams).slice(0, 2).map((team) => ({
      teamId: team.teamId,
      title: team.title || '玩家队伍',
      members: (Number(team.joinedCount) || 0) + '/' + (Number(team.maxMembers) || 0) + ' 人',
      canApply: team.viewerHasTicket === true,
      faces: (Array.isArray(team.memberAvatars) ? team.memberAvatars : []).filter(Boolean).slice(0, 3),
    })),
  };
}

function build(items, teams, layers, getImgUrl, places) {
  const visible = layers || { activities: true, teams: true, posts: true };
  const rows = (items || []).filter((row) => validPoint(row) && H.isMapItem(row));
  const teamRows = (teams || []).filter((row) => validPoint(row) && Number(row.teamId) > 0);
  const cards = rows.map((row) => activityView(row, teamRows, getImgUrl));
  const markers = [];
  if (visible.activities !== false) markers.push.apply(markers, withActivityCards(H.buildHangoutMarkers(rows, {}, {}), cards));
  if (visible.teams !== false) markers.push.apply(markers, buildTeamMarkers(teamRows));
  if (visible.posts !== false) markers.push.apply(markers, buildPostMarkers(rows, getImgUrl));
  markers.push.apply(markers, buildPlaceMarkers(places));
  return {
    markers,
    cards,
    searchPlaces: cards.map((card) => {
      const row = rows.find((item) => String(item.id) === String(card.id) && item.kind === card.kind);
      return { id: card.kind + '-' + card.id, name: card.title, address: card.address, kind: Number(row.productType) === 2 ? 'free' : 'city', lat: Number(row.latitude), lng: Number(row.longitude) };
    }),
  };
}

function parseMarker(markerId) {
  const n = Number(markerId);
  const tag = n % 10;
  const id = Math.floor(n / 10);
  if (!(id > 0)) return null;
  if (tag === TEAM_MARKER_TAG) return { kind: 'team', id };
  if (tag === POST_MARKER_TAG) return { kind: 'post', id };
  if (tag === PLACE_MARKER_TAG) return { kind: 'place', id };
  return H.parseMarkerId(markerId);
}

function hasLayerError(errors) { return !!(errors && (errors.activities || errors.teams)); }

module.exports = { build, activityView, sessionWhen, parseMarker, hasLayerError, isCatalogPlace, placeView, buildPlaceMarkers };
