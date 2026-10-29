<script setup lang="ts">
defineProps<{
  notice: string
  kind: 'success' | 'error'
  hint: string
  t: (text: string) => string
}>()

const emit = defineEmits<{ dismiss: [] }>()
</script>

<template>
  <div class="customer-notice-layer">
    <section
      :class="['customer-notice', `is-${kind}`]"
      :role="kind === 'error' ? 'alert' : 'status'"
      :aria-live="kind === 'error' ? 'assertive' : 'polite'"
      aria-atomic="true"
    >
      <span class="customer-notice-mark" aria-hidden="true">{{ kind === 'success' ? '✓' : '!' }}</span>
      <div class="customer-notice-copy">
        <strong>{{ t(kind === 'success' ? 'Operation successful' : 'Operation failed') }}</strong>
        <p>{{ notice }}</p>
        <small v-if="hint">{{ hint }}</small>
      </div>
      <button class="customer-notice-close" type="button" :aria-label="t('Dismiss notification')" @click="emit('dismiss')">×</button>
    </section>
  </div>
</template>
