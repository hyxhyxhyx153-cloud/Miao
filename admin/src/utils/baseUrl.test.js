import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeBaseUrl } from './baseUrl.js'

test('Base URL only trims surrounding whitespace', () => {
  assert.equal(
    normalizeBaseUrl('  https://gateway.example.com/  '),
    'https://gateway.example.com/'
  )
  assert.equal(
    normalizeBaseUrl('https://relay.example/openai/compatible/'),
    'https://relay.example/openai/compatible/'
  )
})

test('Base URL normalizer does not invent an API suffix', () => {
  const baseUrl = normalizeBaseUrl('https://gateway.example.com/')

  assert.equal(baseUrl, 'https://gateway.example.com/')
  assert.equal(baseUrl.includes('/v1'), false)
})
