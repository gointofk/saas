<script setup lang="ts">
import { computed } from 'vue'
import { whatsappConsultationUrl } from '../lib/customer-support'

const props = defineProps<{ whatsappNumber: string | null; label: string; aboveCart?: boolean }>()
const href = computed(() => whatsappConsultationUrl(props.whatsappNumber))
</script>

<template>
  <a v-if="href" class="whatsapp-float" :class="{ 'is-above-cart': aboveCart }" :href="href" target="_blank" rel="noopener noreferrer" :aria-label="label" :title="label">
    <svg class="whatsapp-float-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d="M20 11.7a8 8 0 0 1-11.9 7l-4.1 1.1 1.1-4A8 8 0 1 1 20 11.7Z" />
      <path d="M9 8.3c.2-.4.4-.4.7-.4h.5c.2 0 .4.1.5.4l.6 1.4c.1.3.1.5-.1.7l-.5.6c-.2.2-.2.4-.1.6.5.8 1.1 1.4 1.9 1.8.2.1.4.1.6-.1l.7-.8c.2-.2.4-.3.7-.2l1.4.7c.3.1.4.3.4.5 0 .6-.3 1.2-.8 1.5-.5.4-1.1.5-1.8.3-1.1-.3-2.2-1-3.2-2-1-.9-1.7-2-2-3.1-.2-.7-.1-1.3.2-1.8.2-.4.6-.7 1-.9Z" />
    </svg>
  </a>
</template>

<style scoped>
.whatsapp-float {
  position: fixed;
  right: 25px;
  bottom: 23px;
  z-index: 30;
  display: grid;
  width: 52px;
  height: 52px;
  box-sizing: border-box;
  padding: 0;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, .22);
  border-radius: 50%;
  background: var(--color-primary, #1f5148);
  color: var(--color-surface, #fff);
  box-shadow: 0 9px 24px rgba(18, 59, 54, .24);
  text-decoration: none;
  transition: background-color .2s ease, transform .2s ease;
}

.whatsapp-float.is-above-cart { bottom: 88px; }
.whatsapp-float:hover { background: var(--color-primary-strong, #173d31); transform: translateY(-2px); }
.whatsapp-float:focus-visible { outline: 3px solid var(--color-accent-soft, #f3e3d9); outline-offset: 3px; }

.whatsapp-float-icon {
  display: block;
  width: 27px;
  height: 27px;
  overflow: visible;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}

@media (max-width: 760px) {
  .whatsapp-float { right: 16px; }
}

@media (prefers-reduced-motion: reduce) {
  .whatsapp-float { transition: none; }
  .whatsapp-float:hover { transform: none; }
}
</style>
