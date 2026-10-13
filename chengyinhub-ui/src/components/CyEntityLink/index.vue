<template>
  <button type="button" class="cy-entity-link" :aria-label="ariaLabel" @click="open">
    <span class="cy-entity-link__primary">
      <span class="cy-entity-link__name">{{ name }}</span>
      <span class="cy-entity-link__id">#{{ entityId }}</span>
      <i class="el-icon-arrow-right" aria-hidden="true" />
    </span>
    <span class="cy-entity-link__meta">
      <span>{{ displayEntityType }}</span><span v-if="meta"> · {{ meta }}</span>
    </span>
  </button>
</template>

<script>
export default {
  name: 'CyEntityLink',
  props: {
    name: { type: String, required: true },
    entityType: { type: String, required: true },
    typeLabel: { type: String, default: '' },
    entityId: { type: [String, Number], required: true },
    meta: { type: String, default: '' }
  },
  computed: {
    displayEntityType() {
      return this.typeLabel || this.entityType
    },
    ariaLabel() {
      return `${this.displayEntityType} ${this.name} #${this.entityId}${this.meta ? `，${this.meta}` : ''}`
    }
  },
  methods: {
    open() {
      this.$emit('open', { type: this.entityType, id: this.entityId })
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-entity-link {
  display: block;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.cy-entity-link__primary { display: flex; align-items: center; min-width: 0; gap: 4px; color: var(--cy-color-text-primary); font-weight: 700; }
.cy-entity-link__name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cy-entity-link__id { flex: none; color: var(--cy-color-text-tertiary); font-weight: 600; }
.cy-entity-link__primary i { flex: none; color: var(--cy-color-brand); font-size: 13px; opacity: 0; transform: translateX(-3px); transition: opacity 160ms ease, transform 160ms ease; }
.cy-entity-link__meta { display: block; margin-top: 4px; overflow: hidden; color: var(--cy-color-text-tertiary); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.cy-entity-link:hover .cy-entity-link__name { color: var(--cy-color-brand); }
.cy-entity-link:hover .cy-entity-link__primary i { opacity: 1; transform: translateX(0); }
.cy-entity-link:focus-visible { outline: 3px solid var(--cy-color-focus); outline-offset: 3px; border-radius: var(--cy-radius-control); }
</style>
