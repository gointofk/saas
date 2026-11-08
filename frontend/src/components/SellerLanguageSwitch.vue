<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ modelValue: string; label: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: 'zh-CN' | 'en'] }>()
const language = computed({ get: () => props.modelValue, set: selectLanguage })

function selectLanguage(value: string) {
  if ((value === 'zh-CN' || value === 'en') && value !== props.modelValue) emit('update:modelValue', value)
}
</script>

<template>
  <label class="seller-language-picker">
    <svg class="seller-language-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18M12 3a17 17 0 0 1 0 18 17 17 0 0 1 0-18Z" />
    </svg>
    <select v-model="language" :aria-label="label">
      <option value="zh-CN" lang="zh-CN">中文</option>
      <option value="en" lang="en">EN</option>
    </select>
    <svg class="seller-language-chevron" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="m4 6 4 4 4-4" />
    </svg>
  </label>
</template>

<style scoped>
.seller-language-picker {
  position: relative;
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  min-height: 32px;
  padding: 0 10px;
  border: 1px solid #e5e5ea;
  border-radius: 999px;
  background: #fff;
  color: #424245;
}
.seller-language-icon { width: 17px; height: 17px; flex-shrink: 0; pointer-events: none; }
.seller-language-picker select {
  appearance: none;
  min-height: 30px;
  margin: 0;
  padding: 0 18px 0 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: 12px;
  line-height: 20px;
  cursor: pointer;
}
.seller-language-chevron { position: absolute; right: 10px; width: 12px; height: 12px; pointer-events: none; }
.seller-language-picker:hover { border-color: #06c; color: #06c; }
.seller-language-picker:focus-within { outline: 2px solid #06c; outline-offset: 2px; }
.seller-language-picker select:focus-visible { outline: none; }
</style>
