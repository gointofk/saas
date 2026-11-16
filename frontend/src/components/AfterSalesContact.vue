<script setup lang="ts">
import { computed } from 'vue'
import { telegramJoinUrl, whatsappConsultationUrl } from '../lib/customer-support'

const props = defineProps<{ t: (text: string) => string; whatsappNumber: string | null; telegramUrl?: string | null }>()
const whatsappHref = computed(() => whatsappConsultationUrl(props.whatsappNumber))
const telegramHref = computed(() => telegramJoinUrl(props.telegramUrl))
</script>

<template>
  <div v-if="whatsappHref || telegramHref" class="after-sales-links">
    <p v-if="whatsappHref" class="after-sales-contact">
      Whatsapp:
      <a class="after-sales-number" :href="whatsappHref" target="_blank" rel="noopener noreferrer">{{ whatsappNumber }}</a>
    </p>
    <p v-if="telegramHref" class="after-sales-contact">
      Join Us:
      <a class="after-sales-telegram-link" :href="telegramHref" target="_blank" rel="noopener noreferrer">Telegram</a>
    </p>
  </div>
</template>
