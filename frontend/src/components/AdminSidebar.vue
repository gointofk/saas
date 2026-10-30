<script setup lang="ts">
import { useSellerTranslation } from '../lib/seller-locale'

const t = useSellerTranslation()
type NavigationItem = { key: string; label: string }

defineProps<{
  items: NavigationItem[]
  activePage: string
  openOrders: number
  merchantName: string
  merchantLogoUrl: string
}>()

const emit = defineEmits<{ navigate: [page: string] }>()
</script>

<template>
  <aside class="sidebar">
    <button class="side-brand brand-button" type="button" @click="emit('navigate', 'orders')">
      <img v-if="merchantLogoUrl" class="side-logo" :src="merchantLogoUrl" alt="">
      <span><span class="side-brand-name">{{ merchantName || 'YTPEPS' }}</span><span class="side-brand-sub">{{ t('商家管理后台') }}</span></span>
    </button>
    <nav class="side-menu" :aria-label="t('商家导航')">
      <button
        v-for="item in items"
        :key="item.key"
        class="side-menu-item"
        :class="{ active: activePage === item.key }"
        :aria-current="activePage === item.key ? 'page' : undefined"
        type="button"
        @click="emit('navigate', item.key)"
      >
        <svg class="side-menu-icon" width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <template v-if="item.key === 'order-audit'"><path d="M7 3.75h7l4 4v12.5H7z" /><path d="M14 3.75v4h4M9.5 12h5M9.5 15.5h2" /><path d="m14.5 16 1.5 1.5 3-3" /></template>
          <template v-else-if="item.key === 'orders'"><path d="M7 3.75h7l4 4v12.5H7z" /><path d="M14 3.75v4h4M10 12h5M10 15.5h5" /></template>
          <template v-else-if="item.key === 'commissions'"><circle cx="12" cy="12" r="8.25" /><path d="M15.5 8.5 8.5 15.5M9 8.5h.01M15 15.5h.01" /></template>
          <template v-else-if="item.key === 'billing'"><path d="M6.5 3.75h8l3 3v13.5h-11z" /><path d="M14.5 3.75v3h3M9.5 11h5M9.5 14h5M9.5 17h3" /></template>
          <template v-else-if="item.key === 'products'"><path d="m12 3.75 8 4.5v7.5l-8 4.5-8-4.5v-7.5z" /><path d="m4.5 8.5 7.5 4.25 7.5-4.25M12 12.75v7" /></template>
          <template v-else-if="item.key === 'coas'"><path d="M6.5 3.75h8l3 3v13.5h-11z" /><path d="M14.5 3.75v3h3M9.5 11h5M9.5 14h2M9.5 17l1.5 1.5 3-3" /></template>
          <template v-else-if="item.key === 'users'"><circle cx="9" cy="8" r="3" /><path d="M3.75 19c.45-3.1 2.2-4.75 5.25-4.75s4.8 1.65 5.25 4.75M16 5.5a3 3 0 0 1 0 5.75M17 14.5c1.9.65 2.95 2.15 3.25 4.5" /></template>
          <template v-else-if="item.key === 'categories'"><path d="M4 5.5h7l8.25 8.25-5.5 5.5L5.5 10.5z" /><circle cx="8.25" cy="8.25" r="1" /></template>
          <template v-else-if="item.key === 'payments'"><rect x="3.75" y="5.5" width="16.5" height="13" rx="2" /><path d="M4 9.5h16M7.5 14.5h4" /></template>
          <template v-else-if="item.key === 'permissions'"><path d="M12 3.5 19 6v5.25c0 4.25-2.45 7.3-7 9.25-4.55-1.95-7-5-7-9.25V6z" /><path d="m9 12 2 2 4-4" /></template>
          <template v-else-if="item.key === 'merchants'"><path d="M5 10.5v9.75h14V10.5M3.75 7.5l2-3.75h12.5l2 3.75v1a2.75 2.75 0 0 1-5.5 0 2.75 2.75 0 0 1-5.5 0 2.75 2.75 0 0 1-5.5 0z" /><path d="M9.5 20.25v-6h5v6M3.75 7.5h16.5" /></template>
        </svg>
        {{ t(item.label) }}
        <span v-if="item.key === 'orders' && openOrders" class="nav-badge">{{ openOrders }}</span>
      </button>
    </nav>
    <div class="side-foot"><a href="/">{{ t('返回商城前台') }}</a></div>
  </aside>
</template>
