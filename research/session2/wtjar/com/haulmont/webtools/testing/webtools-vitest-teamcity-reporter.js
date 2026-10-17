/**
 * WebTools TeamCity reporter for vitest v3+/v4+.
 * Emits ID-based ##teamcity[...] service messages so IntelliJ SMRunner builds the test tree.
 *
 * Mirrors WebStorm's vitest reporter: the whole tree is emitted at COLLECTION time
 * (onTestModuleCollected — "displaying the tree as early as possible"), so during a debug pause the
 * full structure is already visible; each test's result is then updated by nodeId as it finishes
 * (onTestCaseResult), and suites/files are closed incrementally (onTestSuiteResult / onTestModuleEnd).
 * ID-based (nodeId/parentNodeId) so write order and close order do not affect nesting.
 *
 * Writes synchronously (fs.writeSync) so messages flush even while the debuggee is paused at a
 * breakpoint. For Angular CLI, file paths are resolved back through source maps (dist/ -> src/).
 */

import path from 'path'
import fs from 'fs'

// Synchronous write to fd 1: async process.stdout.write buffers and a breakpoint pause stops the event
// loop, so messages would sit unflushed until resume. fs.writeSync bypasses the loop; fall back on error.
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

// CRITICAL: at module load time so SM runner knows the framework is alive.
tc('enteredTheMatrix')

let _testingStarted = false
function startTestingIfNeeded() {
  if (!_testingStarted) {
    _testingStarted = true
    tc('testingStarted')
  }
}

/**
 * Resolves an Angular CLI compiled spec path (dist/test-out/<uuid>/...) back to the original .spec.ts
 * source via the adjacent .map file. Returns the input unchanged for normal (Vite) projects.
 */
function resolveFilePath(compiledPath) {
  if (!compiledPath) return compiledPath
  try {
    const mapPath = compiledPath + '.map'
    if (!fs.existsSync(mapPath)) return compiledPath
    const map = JSON.parse(fs.readFileSync(mapPath, 'utf8'))
    const sources = map.sources
    if (!Array.isArray(sources) || sources.length === 0) return compiledPath
    const specSrc = sources.find(s => s.includes('.spec.') || s.includes('.test.'))
    const resolved = path.resolve(path.dirname(mapPath), specSrc || sources[0])
    if (fs.existsSync(resolved)) return resolved
  } catch (_) {}
  return compiledPath
}

export default class WebToolsTeamCityReporter {
  _consoleLogs = new Map() // vitest task.id -> { stdout, stderr }
  _nodes = new Map()       // vitest task.id -> nodeId
  _openSuites = new Set()  // nodeIds of suites not yet finished
  _seq = 0

  _nextNodeId() {
    return String(++this._seq)
  }

  onTestModuleQueued(_m) {
    startTestingIfNeeded()
  }

  // Emit the whole tree up front (before execution) so it is fully visible during a debug pause.
  onTestModuleCollected(testModule) {
    startTestingIfNeeded()
    const fileTask = testModule?.task
    if (!fileTask || this._nodes.has(fileTask.id)) return
    const filepath = resolveFilePath(fileTask.filepath || fileTask.name || '')
    const nodeId = this._openSuite(fileTask.id, '0', path.basename(filepath) || filepath, `vitest://${filepath}`)
    this._emitTree(fileTask.tasks || [], nodeId, filepath)
  }

  _emitTree(tasks, parentNodeId, filepath) {
    for (const task of tasks) {
      if (task.type === 'suite') {
        const nodeId = this._openSuite(task.id, parentNodeId, task.name, `vitest://${filepath}::${task.name}`)
        this._emitTree(task.tasks || [], nodeId, filepath)
      } else {
        const nodeId = this._nextNodeId()
        this._nodes.set(task.id, nodeId)
        tc('testStarted', { nodeId, parentNodeId, name: task.name, locationHint: `vitest://${filepath}::${task.name}` })
      }
    }
  }

  _openSuite(taskId, parentNodeId, name, locationHint) {
    const nodeId = this._nextNodeId()
    this._nodes.set(taskId, nodeId)
    this._openSuites.add(nodeId)
    tc('testSuiteStarted', { nodeId, parentNodeId, name, locationHint })
    return nodeId
  }

  _finishSuite(nodeId) {
    if (nodeId && this._openSuites.delete(nodeId)) {
      tc('testSuiteFinished', { nodeId })
    }
  }

  // Real-time as console.log/error fires inside a test; log.taskId links output to the test task.
  onUserConsoleLog(log) {
    if (!log.taskId) return
    let entry = this._consoleLogs.get(log.taskId)
    if (!entry) {
      entry = { stdout: '', stderr: '' }
      this._consoleLogs.set(log.taskId, entry)
    }
    if (log.type === 'stderr') entry.stderr += log.content
    else entry.stdout += log.content
  }

  // Update a single test's result (by nodeId) as it finishes — flushes live, even during a pause.
  onTestCaseResult(testCase) {
    const nodeId = this._nodes.get(testCase.id)
    if (!nodeId) return
    const logs = this._consoleLogs.get(testCase.id)
    if (logs) {
      if (logs.stdout) tc('testStdOut', { nodeId, name: testCase.name, out: logs.stdout })
      if (logs.stderr) tc('testStdErr', { nodeId, name: testCase.name, out: logs.stderr })
    }
    const res = typeof testCase.result === 'function' ? testCase.result() : undefined
    const state = res?.state
    if (state === 'failed') {
      const err = res.errors?.[0]
      tc('testFailed', { nodeId, name: testCase.name, message: err?.message || 'Test failed', details: err?.stack || '' })
    } else if (state && state !== 'passed') {
      tc('testIgnored', { nodeId, name: testCase.name })
    }
    const duration = typeof testCase.diagnostic === 'function' ? testCase.diagnostic()?.duration : undefined
    tc('testFinished', { nodeId, name: testCase.name, duration: String(Math.round(duration || 0)) })
  }

  onTestSuiteResult(testSuite) {
    this._finishSuite(this._nodes.get(testSuite.id))
  }

  onTestModuleEnd(testModule) {
    this._finishSuite(this._nodes.get(testModule?.task?.id))
  }

  onTestRunEnd(testModules = [], _unhandledErrors = []) {
    startTestingIfNeeded()
    // Fallback: if collection never emitted the tree (older vitest), emit it now with results.
    if (this._nodes.size === 0) {
      for (const testModule of testModules) {
        const fileTask = testModule.task
        if (!fileTask) continue
        const filepath = resolveFilePath(fileTask.filepath || fileTask.name || '')
        const nodeId = this._openSuite(fileTask.id, '0', path.basename(filepath) || filepath, `vitest://${filepath}`)
        this._emitResultsTree(fileTask.tasks || [], nodeId, filepath)
      }
    }
    // Close any suites still open (safety — normally closed by onTestSuiteResult / onTestModuleEnd).
    for (const nodeId of [...this._openSuites].reverse()) tc('testSuiteFinished', { nodeId })
    tc('testingFinished')
    this._reset()
  }

  // Fallback one-shot walk (started + result together) from the final internal task tree.
  _emitResultsTree(tasks, parentNodeId, filepath) {
    for (const task of tasks) {
      if (task.type === 'suite') {
        const nodeId = this._openSuite(task.id, parentNodeId, task.name, `vitest://${filepath}::${task.name}`)
        this._emitResultsTree(task.tasks || [], nodeId, filepath)
      } else {
        const nodeId = this._nextNodeId()
        tc('testStarted', { nodeId, parentNodeId, name: task.name, locationHint: `vitest://${filepath}::${task.name}` })
        const state = task.result?.state
        if (state === 'fail') {
          const err = task.result?.errors?.[0]
          tc('testFailed', { nodeId, name: task.name, message: err?.message || 'Test failed', details: err?.stack || '' })
        } else if (state !== 'pass') {
          tc('testIgnored', { nodeId, name: task.name })
        }
        tc('testFinished', { nodeId, name: task.name, duration: String(Math.round(task.result?.duration || 0)) })
      }
    }
  }

  _reset() {
    _testingStarted = false
    this._consoleLogs.clear()
    this._nodes.clear()
    this._openSuites.clear()
    this._seq = 0
  }
}
