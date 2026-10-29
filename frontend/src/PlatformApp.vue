<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import MerchantManagement from './components/MerchantManagement.vue'
import { api, clearToken, getToken, request, setToken } from './lib/api'
const authenticated=ref(false), busy=ref(false), account=ref(''), password=ref(''), error=ref('')
function message(e:unknown) { return e instanceof Error ? e.message : '请求失败' }
async function login() {
  if(busy.value) return
  busy.value=true; error.value=''
  try { const result=await request<{accessToken:string}>('/platform/auth/login',{method:'POST',body:JSON.stringify({account:account.value,password:password.value})},'platform',{token:null,clearOnUnauthorized:false}); setToken('platform',result.accessToken); password.value=''; authenticated.value=true }
  catch(e) { error.value=message(e) } finally { busy.value=false }
}
async function logout() {
  busy.value=true; error.value=''
  try { await request('/platform/auth/logout',{method:'POST'},'platform'); clearToken('platform'); authenticated.value=false }
  catch(e) { error.value=message(e) } finally { busy.value=false }
}
function authCleared(event:Event) {
  if((event as CustomEvent<string>).detail==='platform') { authenticated.value=false; password.value='' }
}
function storageChanged(event:StorageEvent) { if(event.key==='dailyactivity.platform.token' || event.key===null) location.reload() }
onUnmounted(()=>{ window.removeEventListener('authentication-cleared',authCleared); window.removeEventListener('storage',storageChanged) })
onMounted(async()=>{
  window.addEventListener('authentication-cleared',authCleared)
  window.addEventListener('storage',storageChanged)
  if(!getToken('platform')) return
  busy.value=true
  try { await api.me('platform'); authenticated.value=true } catch(e) { error.value=message(e) } finally { busy.value=false }
})
</script>
<template>
  <main class="platform-shell demo-seller-shell">
    <header class="platform-header"><h1>平台管理</h1><button v-if="authenticated" class="btn" :disabled="busy" @click="logout">退出登录</button></header>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    <MerchantManagement v-if="authenticated" />
    <form v-else class="card platform-login" @submit.prevent="login">
      <h2>平台管理员登录</h2>
      <label class="form-group">账号<input v-model="account" autocomplete="username" required maxlength="64" :disabled="busy"></label>
      <label class="form-group">密码<input v-model="password" type="password" autocomplete="current-password" required :disabled="busy"></label>
      <button class="btn" :disabled="busy">{{ busy ? '请稍候…' : '登录' }}</button>
    </form>
  </main>
</template>
<style scoped>
.platform-shell{min-height:100vh;padding:24px;background:#f5f6f8}.platform-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:24px}.platform-login{max-width:420px;margin:8vh auto;padding:28px}.platform-login label{display:flex;flex-direction:column;gap:8px;margin:20px 0}@media(max-width:640px){.platform-shell{padding:12px}}
</style>
