<template>
  <div class="hello">{{ msg }}</div>
</template>

<script setup>
import { ref } from 'vue'
const msg = ref('Hello World')
function greet(name) {
  const answer = 42
  return `Hi ${name} ${answer}`
}
</script>

<style scoped>
.hello { color: red; font-size: 12px; }
</style>
