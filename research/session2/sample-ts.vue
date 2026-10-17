<template>
  <div class="hello">{{ msg }}</div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
const msg = ref<string>('Hello World')
function greet(name: string): string {
  const answer = 42
  return `Hi ${name} ${answer}`
}
</script>

<style scoped>
.hello { color: red; font-size: 12px; }
</style>
