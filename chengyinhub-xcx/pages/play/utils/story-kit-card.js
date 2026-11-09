// 故事流里两件「不该挤在正文里」的东西(2026-11-06 业主走查 V3-10/53/11、V3-15):
//   ① 要整屏的玩法(摇一摇 / 相册 / 变色就点 / 倒计时盖屏 / 抽卡 / 预言 / 秒表 / 找物):画面本身就是整块屏,
//      塞进故事流只会是「把全屏缩小了」。内嵌时流里只放一张入口卡,点开整屏;关掉回到卡,这一站的玩法不退出。
//      其余玩法照旧真内嵌(块高跟内容走、没有台面底色,见 index.wxss .chfull__kit)。
//   ② 延伸任务:本章还没做的延伸站(编号 ≥ 100)或无块流旧章节里的多站小任务,不再靠正文里的提示句,
//      收进故事流末尾一行「延伸任务」,点开半屏列出,点一行打开那一站的地点卡。
// index.js 的 UI 门禁基线按行号登记,新方法都放这里,index.js 只留单行挂钩。

const KIT_FULLSCREEN_FROM_CARD = ['album', 'ballshake', 'reaction', 'countdown', 'predict', 'random', 'stopwatch', 'hidden'];

/** 会话视图刷新后 playKit 的新状态:整屏 kit 照旧 show;内嵌里要整屏的那批 → 入口卡(已点开的保持开着) */
function playKitState(prev, kit, inline, ready) {
  const card = !!inline && !!kit && KIT_FULLSCREEN_FROM_CARD.indexOf(kit.type) >= 0;
  const keepOpen = card && !!(prev && prev.card && prev.show);
  return { show: !inline || keepOpen, kit, readyForBase: ready, card };
}

/** 本章的延伸任务:未完成、属于本章、是延伸站(编号 ≥ 100)或在无块流章节里;正在玩的那站不算 */
function storyTasks(nodes, chapterId, legacy, playingNodeId) {
  return (nodes || []).filter((n) => n && !n.done
    && (chapterId == null || String(n.chapterId) === String(chapterId))
    && (Number(n.num) >= 100 || legacy)
    && String(n.nodeId) !== String(playingNodeId));
}

const data = { storyTasks: { show: false, rows: [] } };

function methods() {
  // 写成 const 对象再返回:台账 / 处理器门禁只扫模块里 `const X = {` 的键
  const api = {
    /** 入口卡 → 整屏打开同一个玩法(同一局、同一个 kit,不另开会话) */
    expandPlayKit() {
      const pk = this.data.playKit || {};
      if (pk.card && pk.kit) this.setData({ 'playKit.show': true });
    },
    /** closePlayKit 的前置:入口卡点开的整屏只收回到卡;已经可以继续就接着走故事。返回 true = 已处理 */
    _collapseKitCard() {
      const pk = this.data.playKit || {};
      if (!pk.card || !pk.show || !this.data.chapterFull) return false;
      this.setData({ 'playKit.show': false });
      if (pk.readyForBase && this.data.advancedPlay && this.data.advancedPlay.show) this.continueStoryGame();
      return true;
    },
    /** openChapterFull 的收尾:有延伸任务就在流末尾放一行入口(一个 .chfull__para,measureChapter 按下标对齐) */
    _pushStoryTasks(items, nodes, blocks, readOnly, mood) {
      const legacy = !(blocks && blocks.length);
      const tasks = readOnly ? [] : storyTasks(nodes, (this.data.chapter || {}).id, legacy, this._playKitNodeId);
      const show = tasks.some((n) => Number(n.num) >= 100) || tasks.length > 1;
      const rows = show ? tasks.map((n) => ({ nodeId: n.nodeId, name: n.name || '' })) : [];
      if (rows.length) items.push({ kind: 'tasks', count: rows.length, mood });
      this.setData({ storyTasks: { show: false, rows } });
    },
    openStoryTasks() {
      if ((this.data.storyTasks.rows || []).length) this.setData({ 'storyTasks.show': true });
    },
    closeStoryTasks() { this.setData({ 'storyTasks.show': false }); },
    openStoryTask(e) {
      const id = e && e.currentTarget && e.currentTarget.dataset.nodeid;
      const node = (this.data.nodes || []).find((n) => String(n.nodeId) === String(id));
      this.setData({ 'storyTasks.show': false });
      if (node && !node.done) this.openSheet(node);
    },
  };
  return api;
}

module.exports = { KIT_FULLSCREEN_FROM_CARD, playKitState, storyTasks, data, methods };
