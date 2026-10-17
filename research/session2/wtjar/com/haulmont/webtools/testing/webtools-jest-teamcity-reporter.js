/**
 * WebTools TeamCity reporter for Jest 24–30+.
 *
 * Emits the tree in REAL TIME so it populates live during a debug pause (WebStorm behaviour):
 *   - onTestCaseStart  (jest 28+): testStarted BEFORE the test body runs → the test shows during a pause.
 *   - onTestCaseResult (jest 26+): the result as each test finishes.
 *   - onTestFileResult: batch fallback for older jest (< 26) that lacks the per-case hooks.
 *
 * Name-based tree (kept isIdBasedTestTree=false): jest --runInBand runs tests sequentially, so only one
 * test/suite is open at a time — strict open/close nesting is always valid, no node ids needed. Writes
 * synchronously (fs.writeSync) so messages flush even while the debuggee is paused at a breakpoint.
 */

const path = require('path')
const fs = require('fs')

// Synchronous write to fd 1 so ServiceMessages flush immediately even while paused at a breakpoint.
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

tc('enteredTheMatrix')

function splitMessageAndStack(text) {
  if (!text) return { message: '', stack: '' }
  const lines = text.split(/\n|\r\n/)
  let stackStart = lines.findIndex(l => /^\s+at\s/.test(l))
  if (stackStart < 0) stackStart = Math.min(1, lines.length)
  return {
    message: lines.slice(0, stackStart).join('\n').trim(),
    stack: lines.slice(stackStart).join('\n'),
  }
}

class WebToolsJestTeamCityReporter {
  constructor(globalConfig) {
    this._globalConfig = globalConfig
    this._filePath = ''
    this._fileName = ''
    this._fileOpen = false
    this._suiteStack = []          // open describe suite names (under the file), innermost last
    this._onTestCaseResultCalled = false
  }

  onRunStart() {
    tc('testingStarted')
  }

  // Jest 27+: onTestFileStart; Jest 24-26: onTestStart (aliased below). Opens the file suite.
  onTestFileStart(test) {
    this._startFile((test && test.path) || '')
  }

  _startFile(filePath) {
    if (filePath && filePath === this._filePath && this._fileOpen) return
    this._endFile()
    this._filePath = filePath
    this._fileName = path.basename(filePath) || filePath
    this._suiteStack = []
    this._onTestCaseResultCalled = false
    if (filePath) {
      tc('testSuiteStarted', { name: this._fileName, locationHint: `jest://${filePath}` })
      this._fileOpen = true
    }
  }

  // Open/close describe suites so the stack matches `ancestors` (tests run in order, so this is valid).
  _syncStack(ancestors) {
    const stack = this._suiteStack
    let commonLen = 0
    while (commonLen < stack.length && commonLen < ancestors.length && stack[commonLen] === ancestors[commonLen]) commonLen++
    for (let i = stack.length - 1; i >= commonLen; i--) tc('testSuiteFinished', { name: stack[i] })
    stack.length = commonLen
    for (let i = commonLen; i < ancestors.length; i++) {
      const name = ancestors[i]
      const suitePath = ancestors.slice(0, i + 1).join(' > ')
      tc('testSuiteStarted', { name, locationHint: `jest://${this._filePath}::${suitePath}` })
      stack.push(name)
    }
  }

  // Jest 28+: fires before the test body runs → the test appears (running) before a breakpoint pause.
  onTestCaseStart(test, info) {
    if (!info) return
    if (test && test.path && test.path !== this._filePath) this._startFile(test.path)
    else if (!this._fileOpen) this._startFile(this._filePath || (test && test.path) || '')
    const ancestors = info.ancestorTitles || []
    this._syncStack(ancestors)
    const fullName = [...ancestors, info.title].join(' > ')
    tc('testStarted', { name: info.title, locationHint: `jest://${this._filePath}::${fullName}` })
  }

  // Jest 26+: fires when a single test finishes.
  onTestCaseResult(test, result) {
    this._onTestCaseResultCalled = true
    this._emitResult(result)
  }

  _emitResult(result) {
    const name = result.title
    if (result.status === 'failed') {
      const { message, stack } = splitMessageAndStack((result.failureMessages && result.failureMessages[0]) || '')
      tc('testFailed', { name, message: message || 'Test failed', details: stack })
    } else if (result.status === 'pending' || result.status === 'todo' || result.status === 'skipped' || result.status === 'disabled') {
      tc('testIgnored', { name })
    }
    tc('testFinished', { name, duration: String(Math.round(result.duration || 0)) })
  }

  onTestFileResult(test, testResult) {
    const filePath = testResult.testFilePath || (test && test.path) || ''
    if (filePath !== this._filePath || !this._fileOpen) this._startFile(filePath)

    // File-level execution error (syntax error, missing import) — no individual test results.
    const execError = testResult.testExecError
    if (execError && !(Array.isArray(testResult.testResults) && testResult.testResults.length > 0)) {
      const { message, stack } = splitMessageAndStack(execError.stack || execError.message || 'Execution error')
      tc('testStarted', { name: 'Error' })
      tc('testFailed', { name: 'Error', message: message || 'Execution error', details: stack })
      tc('testFinished', { name: 'Error' })
    } else if (!this._onTestCaseResultCalled) {
      // Fallback (jest < 26): the per-case hooks were never called, so emit every result now.
      for (const t of (testResult.testResults || [])) {
        const ancestors = t.ancestorTitles || []
        if (t.status === 'pending' && ancestors.length === 0 && !t.title) continue
        this._syncStack(ancestors)
        tc('testStarted', { name: t.title, locationHint: `jest://${filePath}::${[...ancestors, t.title].join(' > ')}` })
        this._emitResult(t)
      }
    }
    this._endFile()
  }

  _endFile() {
    if (!this._fileOpen) return
    this._syncStack([])
    tc('testSuiteFinished', { name: this._fileName })
    this._fileOpen = false
  }

  onRunComplete() {
    this._endFile()
    tc('testingFinished')
  }
}

// Jest 24-26 used onTestStart/onTestResult; 27+ uses onTestFileStart/onTestFileResult. Alias both.
WebToolsJestTeamCityReporter.prototype.onTestStart = WebToolsJestTeamCityReporter.prototype.onTestFileStart
WebToolsJestTeamCityReporter.prototype.onTestResult = WebToolsJestTeamCityReporter.prototype.onTestFileResult

module.exports = WebToolsJestTeamCityReporter
