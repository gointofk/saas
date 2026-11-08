<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

defineProps<{
  label: string
  contentClass?: string | string[]
}>()

const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLElement | null>(null)
let restoreFocus: HTMLElement | null = null

const focusableSelector = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

onMounted(() => {
  restoreFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  requestAnimationFrame(() => {
    const firstControl = dialog.value?.querySelector<HTMLElement>(focusableSelector)
    ;(firstControl || dialog.value)?.focus()
  })
})

onBeforeUnmount(() => {
  if (restoreFocus?.isConnected) restoreFocus.focus()
})

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
    return
  }

  if (event.key !== 'Tab') return
  const controls = [...(dialog.value?.querySelectorAll<HTMLElement>(focusableSelector) || [])]
  if (!controls.length) {
    event.preventDefault()
    dialog.value?.focus()
    return
  }

  const first = controls[0]
  const last = controls[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
</script>

<template>
  <div class="modal-overlay" role="presentation" @click.self="emit('close')">
    <section
      ref="dialog"
      class="modal-box"
      :class="contentClass"
      role="dialog"
      aria-modal="true"
      :aria-label="label"
      tabindex="-1"
      @keydown="handleKeydown"
    >
      <slot />
    </section>
  </div>
</template>
