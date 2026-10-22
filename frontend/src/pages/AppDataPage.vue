<template>
  <main class="page">
    <div class="page__inner">
      <div class="page__header">
        <div>
          <h1 class="page-title">Data</h1>
          <p class="page-subtitle">What {{ app.name }} stores. Ask in the chat to add a type or a field.</p>
        </div>
      </div>

      <div v-if="loadError" class="alert alert--error" role="alert">
        {{ loadError }}
        <button type="button" class="link-button" @click="load">Try again</button>
      </div>

      <div v-if="loading" class="page__status"><span class="spinner" /> Loading…</div>

      <section v-else-if="types.length === 0 && !loadError" class="panel glass">
        <h2 class="panel__title">No data yet</h2>
        <p class="muted">{{ app.name }} stores nothing yet. Once the chat adds a type, such as a Task, its records show here.</p>
      </section>

      <div v-else-if="types.length > 0" class="data">
        <nav class="types glass" aria-label="Types">
          <p class="types__label">Types</p>
          <button v-for="type in types"
                  :key="type.id!"
                  type="button"
                  :class="['types__item', { 'types__item--active': type.id === selectedId }]"
                  @click="select(type.id!)">
            <span>{{ type.name }}</span>
            <span class="types__count">{{ counts[type.id!] ?? '' }}</span>
          </button>
        </nav>

        <section class="records glass">
          <div class="records__bar">
            <h2 class="panel__title">{{ selected?.name }}</h2>
            <form class="records__search" @submit.prevent="search">
              <input v-model="searchText" class="input" type="search" :placeholder="`Search ${selected?.name ?? ''}`" aria-label="Search records" />
            </form>
          </div>
          <div v-if="recordsError" class="alert alert--error records__error" role="alert">{{ recordsError }}</div>
          <div class="table-wrap">
            <table v-if="rows.length > 0" class="table">
              <thead>
                <tr><th v-for="column in columns" :key="column">{{ column }}</th></tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in rows" :key="index">
                  <td v-for="column in columns" :key="column" class="cell" :title="cell(row[column])">{{ cell(row[column]) }}</td>
                </tr>
              </tbody>
            </table>
            <p v-else class="table__empty">{{ recordsLoading ? 'Loading…' : (appliedSearch ? 'No records match.' : 'No records yet.') }}</p>
          </div>
          <div class="records__foot">
            <span class="muted">{{ summary }}</span>
            <div class="records__pages">
              <button type="button" class="btn btn--secondary btn--small" :disabled="page === 0 || recordsLoading" @click="goTo(page - 1)">Previous</button>
              <button type="button" class="btn btn--secondary btn--small" :disabled="!hasNext || recordsLoading" @click="goTo(page + 1)">Next</button>
            </div>
          </div>
        </section>
      </div>
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Kinotic, Pageable } from '@kinotic-ai/core'
import type { EntityDefinition } from '@kinotic-ai/management-api'
import type { BuilderApp } from '@/api/appBuilder'
import { errorText } from '@/api/format'

/** The records an app stores, one type at a time. */
const props = defineProps<{
  app: BuilderApp
}>()

const MAX_TYPES = 100
const PAGE_SIZE = 20
const MAX_CELL_LENGTH = 80

type Row = Record<string, unknown>

const loading = ref(true)
const loadError = ref<string | null>(null)
const types = ref<EntityDefinition[]>([])
const counts = ref<Record<string, number>>({})
const selectedId = ref<string | null>(null)
const rows = ref<Row[]>([])
const total = ref(0)
const page = ref(0)
const searchText = ref('')
const appliedSearch = ref('')
const recordsLoading = ref(false)
const recordsError = ref<string | null>(null)

const selected = computed(() => types.value.find(type => type.id === selectedId.value))

// the union of the page's fields, with id first since every record has one
const columns = computed(() => {
  const names = new Set<string>()
  rows.value.forEach(row => Object.keys(row).forEach(name => names.add(name)))
  return [...names].sort((a, b) => (a === 'id' ? -1 : b === 'id' ? 1 : 0))
})

const hasNext = computed(() => (page.value + 1) * PAGE_SIZE < total.value)

const summary = computed(() => {
  const first = page.value * PAGE_SIZE + 1
  return total.value === 0 ? '' : `Showing ${first}–${first + rows.value.length - 1} of ${total.value}`
})

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const found = await Kinotic.entityDefinitions.findAllForApplication(props.app.applicationId, Pageable.create(0, MAX_TYPES))
    types.value = (found.content ?? []).sort((a, b) => a.name.localeCompare(b.name))
    const first = types.value[0]
    if (first?.id) {
      void countRecords()
      await select(first.id)
    }
  } catch (err) {
    loadError.value = errorText(err, 'Could not load the app\'s data types.')
  } finally {
    loading.value = false
  }
}

async function countRecords() {
  const entries = await Promise.all(types.value.map(async type =>
    [type.id!, await Kinotic.entities.count(type.id!).catch(() => -1)] as const))
  counts.value = Object.fromEntries(entries.filter(([, count]) => count >= 0))
}

async function select(id: string) {
  selectedId.value = id
  searchText.value = ''
  appliedSearch.value = ''
  await goTo(0)
}

async function search() {
  appliedSearch.value = searchText.value.trim()
  await goTo(0)
}

async function goTo(target: number) {
  const id = selectedId.value
  if (!id) return
  recordsLoading.value = true
  recordsError.value = null
  try {
    const pageable = Pageable.create(target, PAGE_SIZE)
    const found = appliedSearch.value
      ? await Kinotic.entities.searchSinglePage<Row>(id, appliedSearch.value, pageable)
      : await Kinotic.entities.findAllSinglePage<Row>(id, pageable)
    rows.value = found.content ?? []
    total.value = found.totalElements ?? rows.value.length
    page.value = target
  } catch (err) {
    rows.value = []
    total.value = 0
    recordsError.value = errorText(err, 'Could not load the records.')
  } finally {
    recordsLoading.value = false
  }
}

function cell(value: unknown): string {
  let ret: string
  if (value === null || value === undefined || value === '') {
    ret = '—'
  } else if (typeof value === 'boolean') {
    ret = value ? 'Yes' : 'No'
  } else if (typeof value === 'object') {
    ret = JSON.stringify(value)
  } else {
    ret = String(value)
  }
  return ret.length > MAX_CELL_LENGTH ? ret.slice(0, MAX_CELL_LENGTH - 1) + '…' : ret
}
</script>

<style scoped>
.cell {
  max-width: 20rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.data {
  display: grid;
  grid-template-columns: 15rem minmax(0, 1fr);
  gap: 1.25rem;
  align-items: start;
}

.types {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0.75rem 0.6rem;
}

.types__label {
  margin: 0 0 0.35rem;
  padding: 0 0.6rem;
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-muted);
}

.types__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  min-height: 2.4rem;
  padding: 0 0.6rem;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-nav);
  text-align: left;
  cursor: pointer;
}

.types__item:hover {
  background: rgba(255, 255, 255, 0.05);
}

.types__item--active,
.types__item--active:hover {
  background: var(--accent-soft);
  color: var(--accent-link);
}

.types__count {
  font-size: 0.85rem;
}

.records {
  min-width: 0;
  overflow: hidden;
}

.records__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 0.75rem;
  padding: 0.9rem 1.4rem;
  border-bottom: 1px solid var(--border);
}

.records__search {
  flex: 0 1 16rem;
}

.records__search .input {
  min-height: 2.4rem;
}

.records__error {
  margin: 1rem 1.4rem 0;
}

.records__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.75rem 1.4rem;
  border-top: 1px solid var(--border);
  font-size: 0.875rem;
}

.records__pages {
  display: flex;
  gap: 0.5rem;
}

@media (max-width: 860px) {
  .data {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
