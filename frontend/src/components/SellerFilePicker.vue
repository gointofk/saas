<script setup lang="ts">
import { ref, watch } from 'vue'
import { useSellerTranslation } from '../lib/seller-locale'

const props = defineProps<{
  modelValue: File | null
  label: string
  accept: string
  required?: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [file: File | null] }>()
const t = useSellerTranslation()
const input = ref<HTMLInputElement | null>(null)

function chooseFile() {
  if (!props.disabled) input.value?.click()
}
function selectFile(event: Event) {
  if (props.disabled) return
  const file = (event.target as HTMLInputElement).files?.[0]
  if (file) emit('update:modelValue', file)
}
watch(() => props.modelValue, file => {
  if (!file && input.value) input.value.value = ''
})
</script>

<template>
  <div class="seller-file-picker">
    <input ref="input" class="seller-file-input" type="file" hidden :accept="accept" :required="required && !modelValue" :disabled="disabled" :aria-label="label" @change="selectFile">
    <button class="seller-file-button" type="button" :disabled="disabled" :aria-label="`${t('Choose file')}: ${label}`" @click="chooseFile">
      <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 13V3m-3 3 3-3 3 3M4 12v4a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1v-4" /></svg>
      {{ t('Choose file') }}
    </button>
    <span class="seller-file-name" :class="{ 'is-empty': !modelValue }" :title="modelValue?.name" aria-live="polite">{{ modelValue?.name || t('No file selected') }}</span>
  </div>
</template>

<style scoped>
.seller-file-picker { display: flex; align-items: center; gap: 12px; min-width: 0; padding: 8px; border: 1px solid #e5e5ea; border-radius: 10px; background: #fafafa; }
.seller-file-input { display: none; }
.seller-file-button { display: inline-flex; flex: 0 0 auto; align-items: center; justify-content: center; gap: 6px; min-height: 34px; padding: 6px 12px; border: 1px solid #d8d8dc; border-radius: 7px; background: #fff; color: #424245; font: inherit; font-size: 12px; font-weight: 500; cursor: pointer; }
.seller-file-button svg { width: 16px; height: 16px; fill: none; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.seller-file-button:hover:not(:disabled) { color: #06c; border-color: #06c; }
.seller-file-button:focus-visible { outline: 2px solid #06c; outline-offset: 2px; }
.seller-file-button:disabled { opacity: .5; cursor: not-allowed; }
.seller-file-name { min-width: 0; overflow: hidden; color: #424245; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.seller-file-name.is-empty { color: #86868b; }
@media (max-width: 480px) {
  .seller-file-picker { flex-wrap: wrap; gap: 8px; }
  .seller-file-name { flex-basis: 100%; white-space: normal; overflow-wrap: anywhere; }
}
</style>
