'use strict';

/* 第七轮 C 第 2 项:游玩页右上角转发。
 * 不给 imageUrl 时微信截当前屏当封面,游玩页上就是玩家实时位置和谜面(漫游页同一问题见 roam-menu-share.js)。
 * 落点改成公开的主题详情:游玩页本身是登录后的会话页,接收人点开只会看到「本局尚未开始」。
 * 自玩会话没有 activityId,旧实现发出 activityId= 空串;这里只认主题 id。
 * 封面:主题封面(公开素材),没有就用仓内不含位置的固定插画,绝不留空让微信截屏。 */
const { buildTopicShare } = require('./topic-share.js');
const { ROAM_SHARE_COVER } = require('./roam-menu-share.js');

const FALLBACK_TITLE = '来城瘾，走一段城市探索';

function validTopicId(value) {
  const id = String(value == null ? '' : value).trim();
  return /^[1-9]\d*$/.test(id) ? id : '';
}

function playMenuShare(data) {
  const d = data || {};
  const topicId = validTopicId(d.topicId);
  if (!topicId) return { title: FALLBACK_TITLE, path: '/pages/index/index', imageUrl: ROAM_SHARE_COVER };
  return buildTopicShare({ topicId, name: d.topicName, cover: d.topicCover || ROAM_SHARE_COVER, fallbackTitle: FALLBACK_TITLE });
}

module.exports = { playMenuShare, FALLBACK_TITLE };
