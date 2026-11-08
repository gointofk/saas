<script setup lang="ts">
import { useSellerTranslation } from '../lib/seller-locale'
import { MAX_ORDER_ATTACHMENT_BYTES, ORDER_IMAGE_ACCEPT, ORDER_IMAGE_FORMATS, ORDER_IMAGE_MIME_TYPES } from '../lib/order-file-rules'
import { computed, onBeforeUnmount, ref, watch } from 'vue'

const t = useSellerTranslation()

const props = withDefaults(defineProps<{
  modelValue: File[]
  label: string
  disabled?: boolean
  invalid?: boolean
  compact?: boolean
  allowPdf?: boolean
  allowCamera?: boolean
  optional?: boolean
  maxFiles?: number
}>(), { disabled: false, invalid: false, compact: false, allowPdf: false, allowCamera: false, optional: false, maxFiles: 10 })
const emit = defineEmits<{ 'update:modelValue': [files: File[]] }>()
const input = ref<HTMLInputElement | null>(null)
const cameraInput = ref<HTMLInputElement | null>(null)
const error = ref('')
const dragging = ref(false)
const previews = ref(new Map<File, string>())
const formats = computed(() => props.allowPdf ? `${ORDER_IMAGE_FORMATS} / PDF` : ORDER_IMAGE_FORMATS)
const accept = computed(() => props.allowPdf ? `${ORDER_IMAGE_ACCEPT},application/pdf` : ORDER_IMAGE_ACCEPT)
const unit = computed(() => props.allowPdf ? t('个') : t('张'))
const kind = computed(() => props.allowPdf ? t('面单') : t('图片'))

watch(() => props.modelValue, files => {
  const next = new Map<File, string>()
  for (const file of files) next.set(file, previews.value.get(file) || URL.createObjectURL(file))
  for (const [file, url] of previews.value) if (!next.has(file)) URL.revokeObjectURL(url)
  previews.value = next
}, { immediate: true })
onBeforeUnmount(() => { for (const url of previews.value.values()) URL.revokeObjectURL(url) })

function append(files: File[]) {
  if (props.disabled) return
  error.value = ''
  const allowedTypes = new Set<string>([...ORDER_IMAGE_MIME_TYPES, ...(props.allowPdf ? ['application/pdf'] : [])])
  const invalid = files.find(file => !allowedTypes.has(file.type) || file.size === 0 || file.size > MAX_ORDER_ATTACHMENT_BYTES)
  if (invalid) {
    error.value = `${invalid.name} 无法添加，请选择不超过 15 MiB 的 ${formats.value} 文件。`
    return
  }
  const next = [...props.modelValue]
  for (const file of files) {
    if (!next.some(existing => existing.name === file.name && existing.size === file.size && existing.lastModified === file.lastModified)) next.push(file)
  }
  if (next.length > props.maxFiles) { error.value = `每个包裹最多添加 ${props.maxFiles} 个文件，请减少选择。`; return }
  emit('update:modelValue', next)
}
function select(event: Event) {
  const control = event.target as HTMLInputElement
  append(Array.from(control.files || []))
  control.value = ''
}
function drop(event: DragEvent) {
  dragging.value = false
  append(Array.from(event.dataTransfer?.files || []))
}
function remove(index: number) {
  error.value = ''
  emit('update:modelValue', props.modelValue.filter((_, fileIndex) => fileIndex !== index))
}
</script>

<template>
  <section :class="['parcel-photo-picker', { compact }]" :aria-label="label">
    <div class="photo-picker-heading"><strong>{{ label }} <span>{{ optional ? t('选填') : maxFiles === 1 ? t('必传 1 张图片') : t('至少 1 个文件') }}</span></strong><small>{{ modelValue.length }} / {{ maxFiles }} {{ unit }}</small></div>
    <div
      :class="['photo-drop-area', { 'is-dragging': dragging && !disabled, 'is-invalid': invalid && !modelValue.length }]"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="drop"
    >
      <input ref="input" class="sr-only" type="file" :accept="accept" :multiple="maxFiles > 1" :disabled="disabled" :aria-label="t('选择{0}', [label])" @change="select">
      <input v-if="allowCamera" ref="cameraInput" class="sr-only" type="file" accept="image/*" capture="environment" :disabled="disabled" :aria-label="t('拍照上传{0}', [label])" @change="select">
      <div class="photo-picker-actions">
        <button class="photo-add-button" type="button" :disabled="disabled || modelValue.length >= maxFiles" @click="input?.click()">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 16V4m-4 4 4-4 4 4M4 15v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4" /></svg>
          {{ maxFiles === 1 ? t('选择图片') : modelValue.length ? t('继续添加{0}', [kind]) : t('选择{0}', [kind]) }}
        </button>
        <button v-if="allowCamera" class="photo-add-button" type="button" :disabled="disabled || modelValue.length >= maxFiles" @click="cameraInput?.click()">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h3l1.5-2h7L17 7h3v12H4zM12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z" /></svg> {{ t('拍照上传') }} </button>
      </div>
      <span>{{ maxFiles === 1 ? t('{0} · 不超过 15 MiB', [formats]) : t('可拖入多个文件 · {0} · 每个不超过 15 MiB', [formats]) }}</span>
    </div>
    <p v-if="error || (invalid && !modelValue.length)" class="photo-picker-error" role="alert">{{ t(error) || (optional ? t('请检查所选文件。') : t('请为本包添加至少 1 个文件。')) }}</p>
    <ul v-if="modelValue.length" class="photo-preview-list">
      <li v-for="(file, index) in modelValue" :key="`${file.name}-${file.size}-${file.lastModified}`">
        <a v-if="file.type === 'application/pdf'" class="photo-pdf-preview" :href="previews.get(file)" target="_blank" rel="noopener noreferrer" :aria-label="t('预览面单 {0}', [file.name])"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6" /></svg><strong>PDF</strong><small>{{ t('点击预览') }}</small></a>
        <img v-else :src="previews.get(file)" :alt="`${label} ${index + 1}`">
        <span :title="file.name">{{ file.name }}</span>
        <button type="button" :disabled="disabled" :aria-label="t('移除{0} {1}', [kind, file.name])" @click="remove(index)">×</button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.parcel-photo-picker { min-width: 0; }
.photo-picker-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; font-size: 13px; }
.photo-picker-heading strong { font-weight: 550; }
.photo-picker-heading strong span, .photo-picker-heading small { color: var(--color-muted); font-size: 12px; font-weight: 400; }
.photo-drop-area { display: flex; min-height: 108px; flex-direction: column; align-items: center; justify-content: center; gap: 10px; padding: 18px 12px; border: 1px dashed var(--color-line-strong); border-radius: 9px; background: var(--color-page); }
.photo-drop-area.is-dragging { border-color: var(--color-primary); background: var(--color-info-soft); }
.photo-drop-area.is-invalid { border-color: var(--color-danger); }
.photo-drop-area > span { color: var(--color-muted); font-size: 11px; text-align: center; }
.photo-picker-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: center; gap: 8px; }
.photo-add-button { display: inline-flex; align-items: center; gap: 8px; padding: 7px 12px; border: 1px solid var(--color-line-strong); border-radius: 6px; background: var(--color-surface); color: var(--color-primary); font-size: 12px; }
.photo-add-button svg { width: 17px; height: 17px; fill: none; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.photo-picker-error { margin: 8px 0; color: var(--color-danger); font-size: 12px; overflow-wrap: anywhere; }
.photo-preview-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(86px, 1fr)); gap: 10px; margin: 14px 0 0; padding: 0; list-style: none; }
.photo-preview-list li { position: relative; min-width: 0; }
.photo-preview-list img { display: block; width: 100%; height: 82px; border: 1px solid var(--color-line); border-radius: 7px; object-fit: cover; }
.photo-pdf-preview { display: flex; height: 82px; flex-direction: column; align-items: center; justify-content: center; gap: 4px; border: 1px solid var(--color-line); border-radius: 7px; background: var(--color-page); color: var(--color-primary); text-decoration: none; }
.photo-pdf-preview svg { width: 22px; height: 22px; fill: none; stroke: currentColor; stroke-width: 1.4; stroke-linejoin: round; }
.photo-pdf-preview strong { font-size: 11px; }
.photo-pdf-preview small { color: var(--color-muted); font-size: 10px; }
.photo-preview-list span { display: block; margin-top: 5px; overflow: hidden; color: var(--color-muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.photo-preview-list button { position: absolute; top: -5px; right: -5px; display: grid; width: 24px; height: 24px; place-items: center; padding: 0; border: 1px solid var(--color-line); border-radius: 50%; background: var(--color-surface); color: var(--color-ink); font-size: 16px; }
.compact .photo-drop-area { min-height: 66px; flex-direction: row; justify-content: flex-start; gap: 16px; padding: 12px; }
.compact .photo-drop-area > span { text-align: left; line-height: 1.5; }
.compact .photo-picker-actions { flex: 0 0 auto; }
.compact .photo-add-button { flex: 0 0 auto; }
.compact .photo-preview-list { grid-template-columns: repeat(auto-fill, 86px); }
@media (max-width: 480px) { .compact .photo-drop-area { gap: 10px; } .compact .photo-picker-actions { gap: 6px; } .compact .photo-add-button { gap: 5px; padding: 7px 8px; } }
</style>
