/**
 * WebTools TeamCity reporter for Playwright (@playwright/test v1.10+).
 * Emits ID-based ##teamcity[...] service messages so IntelliJ SMRunner builds the test tree.
 *
 * The whole tree is emitted up front in onBegin (Playwright hands the fully-resolved suite of tests
 * that will run), so during a debug pause the structure is already visible; each test's result is
 * then updated by nodeId as it finishes (onTestEnd). ID-based (nodeId/parentNodeId) so write order
 * does not affect nesting.
 *
 * CommonJS + written to a temp .cjs file so it loads regardless of the project's package "type";
 * fs.writeSync(1, …) so messages flush even while the debuggee is paused at a breakpoint.
 */

const fs = require('fs')
const path = require('path')

const _write = s => {
  try {
    fs.writeSync(1, s)
  } catch {
    process.stdout.write(s)
  }
}

const esc = str => String(str ?? '')
  .replace(/\|/g, '||')
  .replace(/'/g, "|'")
  .replace(/\[/g, '|[')
  .replace(/\]/g, '|]')
  .replace(/\n/g, '|n')
  .replace(/\r/g, '|r')

const tc = (name, attrs = {}) => {
  const parts = Object.entries(attrs)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${k}='${esc(v)}'`)
    .join(' ')
  _write(`\n##teamcity[${name}${parts ? ' ' + parts : ''}]\n`)
}

const streamToString = chunks =>
  (chunks || []).map(c => (Buffer.isBuffer(c) ? c.toString('utf8') : String(c))).join('')

class WebToolsPlaywrightReporter {
  constructor() {
    this._seq = 0
    this._nodes = new Map()   // Suite/TestCase object -> nodeId
    this._openSuites = []      // suite nodeIds in open order
  }

  _next() {
    return String(++this._seq)
  }

  onBegin(_config, suite) {
    tc('enteredTheMatrix')
    tc('testingStarted')
    this._emit(suite, '0')
  }

  // Recursively emit the suite tree. The synthetic 'root' is always collapsed. A 'project' level is
  // kept as a top-level group when it is named ('chromium'/'firefox'/'webkit') so the tree groups by
  // browser like WebStorm; the implicit unnamed project (no `projects` in the config) is collapsed so
  // it doesn't render as an empty node. Collapsed levels attach their children to the same parent.
  _emit(suite, parentNodeId) {
    const collapse = suite.type === 'root' || (suite.type === 'project' && !suite.title)
    let nodeId = parentNodeId
    if (!collapse) {
      const filepath = (suite.location && suite.location.file) || ''
      const name = suite.title || path.basename(filepath) || 'suite'
      nodeId = this._next()
      this._nodes.set(suite, nodeId)
      this._openSuites.push(nodeId)
      // Project groups have no file location and aren't navigable → emit a locationHint only when a
      // file is known (file/describe suites); tc() drops undefined attributes.
      const hint = filepath ? `playwright://${filepath}${suite.title ? '::' + suite.title : ''}` : undefined
      tc('testSuiteStarted', { nodeId, parentNodeId, name, locationHint: hint })
    }
    for (const child of suite.suites || []) this._emit(child, nodeId)
    for (const test of suite.tests || []) {
      const id = this._next()
      this._nodes.set(test, id)
      const file = (test.location && test.location.file) || ''
      tc('testStarted', { nodeId: id, parentNodeId: nodeId, name: test.title, locationHint: `playwright://${file}::${test.title}` })
    }
  }

  onTestEnd(test, result) {
    const nodeId = this._nodes.get(test)
    if (!nodeId) return

    const out = streamToString(result.stdout)
    const err = streamToString(result.stderr)
    if (out) tc('testStdOut', { nodeId, name: test.title, out })
    if (err) tc('testStdErr', { nodeId, name: test.title, out: err })

    const status = result.status
    if (status === 'failed' || status === 'timedOut' || status === 'interrupted') {
      const e = result.error || (result.errors && result.errors[0]) || {}
      tc('testFailed', { nodeId, name: test.title, message: e.message || `Test ${status}`, details: e.stack || '' })
    } else if (status === 'skipped') {
      tc('testIgnored', { nodeId, name: test.title })
    }
    tc('testFinished', { nodeId, name: test.title, duration: String(Math.round(result.duration || 0)) })
  }

  onEnd(_result) {
    for (const nodeId of [...this._openSuites].reverse()) tc('testSuiteFinished', { nodeId })
    tc('testingFinished')
  }

  printsToStdio() {
    return true
  }
}

module.exports = WebToolsPlaywrightReporter
