'use strict';

/* U-050:广场帖的转发卡。
 *
 * 只有「已发布 + 公开」的帖子才生成指向帖子详情的卡片:标题取正文摘要,封面取首图。
 * 私密 / 仅关注者 / 社区可见 / 未发布的帖子,对方点开也看不到——这时退回发现页帖文 tab 的
 * 通用卡片,卡片上不带正文与图片(卡片一旦发出就固化在对方会话里,不能先露出再靠详情页拦)。
 * 旧接口的帖子没有 audience 字段,旧广场只有公开帖,按公开处理。
 */

const GENERIC_TITLE = '城瘾Hub';
// 2026-10-29:「动态」二级页已删,通用卡落到发现页的全站帖文 tab。
const GENERIC_PATH = '/pages/talent/list/index?tab=feed';
const TITLE_MAX = 28;

function isPubliclyShareable(post) {
  if (!post || post.id == null || post.id === '') return false;
  if (post.audience != null && String(post.audience).toUpperCase() !== 'PUBLIC') return false;
  if (post.status != null && Number(post.status) !== 1) return false;
  return true;
}

function shareTitle(post) {
  const text = String(post.contents || post.content || '').replace(/\s+/g, ' ').trim();
  if (!text) return GENERIC_TITLE;
  return text.length > TITLE_MAX ? text.slice(0, TITLE_MAX) + '…' : text;
}

/** 首图:详情页已拆好的 picList,或帖子行的 pics("a;b" 分号分隔);只认 http(s) 绝对地址 */
function firstPic(post) {
  const list = Array.isArray(post.picList) && post.picList.length
    ? post.picList : String(post.pics == null ? '' : post.pics).split(';');
  const pic = String(list.find(function (item) { return item; }) || '').trim();
  return /^https?:\/\//.test(pic) ? pic : '';
}

function buildSquareShare(post) {
  if (!isPubliclyShareable(post)) return { title: GENERIC_TITLE, path: GENERIC_PATH };
  const card = { title: shareTitle(post), path: '/pages/square/detail/index?id=' + encodeURIComponent(post.id) };
  const image = firstPic(post);
  if (image) card.imageUrl = image;
  return card;
}

module.exports = { buildSquareShare, isPubliclyShareable };
