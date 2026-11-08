<script setup lang="ts">
import { useSellerTranslation } from '../lib/seller-locale'

const t = useSellerTranslation()
const props = defineProps<{ loading?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ refresh: [] }>()
function refresh() {
  if (!props.loading && !props.disabled) emit('refresh')
}
</script>

<template>
  <div class="seller-search-field">
    <slot />
    <button class="seller-search-refresh" type="button" :disabled="loading || disabled" :aria-busy="loading || undefined" :aria-label="t('刷新查询')" :title="t('刷新查询')" @mousedown.prevent @click="refresh">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M20 7v5h-5M4 17v-5h5" />
        <path d="M6.1 7a7.5 7.5 0 0 1 12.3-1L20 8M4 16l1.6 2A7.5 7.5 0 0 0 17.9 17" />
      </svg>
    </button>
  </div>
</template>

<style scoped>
.seller-search-field { display: flex; align-items: center; gap: 6px; min-width: 0; }
.seller-search-field :slotted(input) { flex: 1 1 0; width: 0; min-width: 0; }
.seller-search-refresh { display: inline-flex; align-items: center; justify-content: center; flex: 0 0 36px; width: 36px; height: 36px; padding: 0; border: 1px solid var(--color-line, #ded8cc); border-radius: 8px; background: var(--color-surface, #fff); color: var(--color-muted, #667b73); cursor: pointer; }
.seller-search-refresh svg { width: 17px; height: 17px; }
.seller-search-refresh:hover:not(:disabled) { color: var(--color-primary, #315b4b); background: var(--color-page, #f6f8f7); }
.seller-search-refresh:focus-visible { outline: 2px solid var(--color-primary, #315b4b); outline-offset: 2px; }
.seller-search-refresh:disabled { opacity: .48; cursor: not-allowed; }
</style>
