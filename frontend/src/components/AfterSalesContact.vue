<script setup lang="ts">
import { whatsappConsultationUrl } from '../lib/customer-support'

defineProps<{ t: (text: string) => string; whatsappNumber: string | null }>()
</script>

<template>
  <p v-if="whatsappConsultationUrl(whatsappNumber)" class="after-sales-contact">
    {{ t('After-sales service:') }} WhatsApp
    <a :href="whatsappConsultationUrl(whatsappNumber)" target="_blank" rel="noopener noreferrer">{{ whatsappNumber }}</a>
  </p>
</template>
