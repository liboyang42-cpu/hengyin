<template>
  <article class="cy-metric-card" :class="{ 'cy-metric-card--disconnected': !connected }">
    <div class="cy-metric-card__topline">
      <span>{{ label }}</span>
      <cy-status-tag :label="connectionLabel || (connected ? '已接通' : '尚未接通')" :tone="connectionTone || (connected ? 'success' : 'neutral')" />
    </div>
    <strong v-if="connected">{{ value }}</strong>
    <p>{{ description }}</p>
  </article>
</template>

<script>
import CyStatusTag from '@/components/CyStatusTag'

export default {
  name: 'CyMetricCard',
  components: { CyStatusTag },
  props: {
    label: { type: String, required: true },
    value: { type: [String, Number], default: '—' },
    description: { type: String, default: '' },
    connected: { type: Boolean, default: false },
    connectionLabel: { type: String, default: '' },
    connectionTone: { type: String, default: '' }
  }
}
</script>

<style lang="scss" scoped>
.cy-metric-card {
  padding: var(--cy-space-3-5);
  border: 1px solid var(--cy-color-border-subtle);
  border-radius: var(--cy-radius-card);
  background: var(--cy-color-bg-surface);
}
.cy-metric-card__topline { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: var(--cy-color-text-primary); font-size: 13px; font-weight: 700; }
strong { display: block; margin-top: var(--cy-space-3-5); color: var(--cy-color-text-primary); font-size: 28px; font-variant-numeric: tabular-nums; }
p { margin: var(--cy-space-1-5) 0 0; color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.6; }
.cy-metric-card--disconnected { background: #FAFAFC; }
</style>
