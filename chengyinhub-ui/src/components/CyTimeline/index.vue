<template>
  <section class="cy-timeline">
    <h3 v-if="title" class="cy-timeline__heading">{{ title }}</h3>
    <el-empty v-if="!visibleItems.length" :description="emptyText" :image-size="76" />
    <ol v-else class="cy-timeline__list">
      <li v-for="item in visibleItems" :key="item.id" class="cy-timeline__item">
        <span class="cy-timeline__rail" aria-hidden="true" />
        <span class="cy-timeline__dot" :class="toneClass(item.tone)" aria-hidden="true" />
        <div class="cy-timeline__content">
          <div class="cy-timeline__title-row">
            <strong>{{ item.title }}</strong>
            <span v-if="item.occurredAt" class="cy-timeline__time">{{ item.occurredAt }}</span>
          </div>
          <p v-if="item.summary" class="cy-timeline__summary">{{ item.summary }}</p>
          <p v-if="item.actor" class="cy-timeline__actor">{{ item.actor }}</p>
          <ul v-if="item.evidence && item.evidence.length" class="cy-timeline__evidence" aria-label="关联证据">
            <li v-for="(evidence, index) in item.evidence" :key="`${evidence}-${index}`">{{ evidence }}</li>
          </ul>
        </div>
      </li>
    </ol>
  </section>
</template>

<script>
const TONES = ['neutral', 'brand', 'success', 'warning', 'danger', 'info']

export default {
  name: 'CyTimeline',
  props: {
    title: { type: String, default: '处理轨迹' },
    items: { type: Array, default: () => [] },
    emptyText: { type: String, default: '暂无处理记录' }
  },
  computed: {
    visibleItems() {
      return this.items.filter(item => item && item.id && item.title)
    }
  },
  methods: {
    toneClass(tone) {
      return `cy-timeline__dot--${TONES.includes(tone) ? tone : 'neutral'}`
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-timeline { margin-top: var(--cy-space-3-5); }
.cy-timeline__heading { margin: 0 0 var(--cy-space-2-5); color: var(--cy-color-text-primary); font-size: 15px; }
.cy-timeline__list { margin: 0; padding: 0; list-style: none; }
.cy-timeline__item { position: relative; display: grid; grid-template-columns: 18px minmax(0, 1fr); gap: var(--cy-space-1-5); padding-bottom: var(--cy-space-4-5); }
.cy-timeline__item:last-child { padding-bottom: 0; }
.cy-timeline__rail { position: absolute; top: 12px; bottom: 0; left: var(--cy-legacy-5); width: 1px; background: var(--cy-color-border-strong); }
.cy-timeline__item:last-child .cy-timeline__rail { display: none; }
.cy-timeline__dot { position: relative; z-index: 1; width: 11px; height: 11px; margin-top: 4px; border: 3px solid var(--cy-color-bg-surface); border-radius: 50%; background: var(--cy-color-text-tertiary); box-shadow: 0 0 0 1px var(--cy-color-border-strong); }
.cy-timeline__dot--brand { background: var(--cy-color-brand); box-shadow: 0 0 0 1px var(--cy-color-brand-soft); }
.cy-timeline__dot--success { background: var(--cy-color-status-success); box-shadow: 0 0 0 1px var(--cy-color-status-success-soft); }
.cy-timeline__dot--warning { background: var(--cy-color-status-warning); box-shadow: 0 0 0 1px var(--cy-color-status-warning-soft); }
.cy-timeline__dot--danger { background: var(--cy-color-status-danger); box-shadow: 0 0 0 1px var(--cy-color-status-danger-soft); }
.cy-timeline__dot--info { background: var(--cy-color-status-info); box-shadow: 0 0 0 1px var(--cy-color-status-info-soft); }
.cy-timeline__title-row { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.cy-timeline__title-row strong { color: var(--cy-color-text-primary); font-size: 14px; line-height: 1.5; }
.cy-timeline__time, .cy-timeline__actor { color: var(--cy-color-text-tertiary); font-size: 12px; line-height: 1.6; }
.cy-timeline__time { flex: none; white-space: nowrap; }
.cy-timeline__summary { margin: var(--cy-legacy-5) 0 0; color: var(--cy-color-text-secondary); font-size: 13px; line-height: 1.65; white-space: pre-wrap; word-break: break-word; }
.cy-timeline__actor { margin: var(--cy-legacy-5) 0 0; }
.cy-timeline__evidence { display: flex; flex-wrap: wrap; gap: var(--cy-space-0-5); margin: 9px 0 0; padding: 0; list-style: none; }
.cy-timeline__evidence li { padding: var(--cy-legacy-3) 8px; border-radius: 999px; background: var(--cy-color-brand-soft); color: var(--cy-color-brand-strong); font-size: 12px; }

@media (max-width: 560px) {
  .cy-timeline__title-row { display: block; }
  .cy-timeline__time { display: block; margin-top: var(--cy-legacy-3); }
}
</style>
