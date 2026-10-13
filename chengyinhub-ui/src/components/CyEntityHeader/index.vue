<template>
  <header class="cy-entity-header">
    <div class="cy-entity-header__top">
      <div class="cy-entity-header__identity">
        <p v-if="eyebrow" class="cy-entity-header__eyebrow">{{ eyebrow }}</p>
        <h2>{{ title }}</h2>
      </div>
      <div v-if="status.label || $slots.actions" class="cy-entity-header__controls">
        <cy-status-tag v-if="status.label" :label="status.label" :tone="status.tone" />
        <div v-if="$slots.actions" class="cy-entity-header__actions">
          <slot name="actions" />
        </div>
      </div>
    </div>
    <dl v-if="visibleFacts.length" class="cy-entity-header__facts">
      <div v-for="fact in visibleFacts" :key="fact.label" class="cy-entity-header__fact">
        <dt>{{ fact.label }}</dt>
        <dd>{{ fact.value }}</dd>
      </div>
    </dl>
  </header>
</template>

<script>
import CyStatusTag from '@/components/CyStatusTag'

export default {
  name: 'CyEntityHeader',
  components: { CyStatusTag },
  props: {
    eyebrow: { type: String, default: '' },
    title: { type: String, required: true },
    status: { type: Object, default: () => ({}) },
    facts: { type: Array, default: () => [] }
  },
  computed: {
    visibleFacts() {
      return this.facts.slice(0, 4)
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-entity-header { padding: 8px 0 20px; border-bottom: 1px solid var(--cy-color-border-subtle); }
.cy-entity-header__top { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.cy-entity-header__identity { min-width: 0; }
.cy-entity-header__eyebrow { margin: 0 0 var(--cy-legacy-5); color: var(--cy-color-brand); font-size: 12px; font-weight: 700; }
h2 { margin: 0; color: var(--cy-color-text-primary); font-size: 20px; line-height: 1.35; }
.cy-entity-header__controls { display: flex; flex: none; align-items: center; justify-content: flex-end; gap: var(--cy-space-1-5); }
.cy-entity-header__actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 4px; }
.cy-entity-header__facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: var(--cy-space-1-5); margin: var(--cy-space-3-5) 0 0; }
.cy-entity-header__fact { min-width: 0; padding: var(--cy-space-2-5); border-radius: var(--cy-radius-control); background: var(--cy-color-bg-subtle); }
dt { color: var(--cy-color-text-tertiary); font-size: 11px; }
dd { margin: var(--cy-space-0-5) 0 0; overflow: hidden; color: var(--cy-color-text-primary); font-size: 15px; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }

@media (max-width: 560px) {
  .cy-entity-header__top { flex-direction: column; }
  .cy-entity-header__controls { width: 100%; justify-content: space-between; }
}
</style>
