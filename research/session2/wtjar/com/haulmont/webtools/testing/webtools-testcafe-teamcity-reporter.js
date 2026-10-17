/**
 * WebTools TeamCity reporter for TestCafe.
 * Emits name-based ##teamcity[...] service messages so IntelliJ's SMRunner builds the test tree.
 *
 * TestCafe has no upfront "here is the whole tree" callback, so the tree is emitted as it runs:
 * each fixture opens a suite (closing the previous one), and each test is a leaf under it. TestCafe
 * reporter plugins export a factory returning an object whose methods run with `this` bound to
 * TestCafe's ReporterPluginHost (providing `this.formatError` etc.).
 *
 * Written to stdout via fs.writeSync(1, …) rather than `this.write` so the raw service messages reach
 * IntelliJ unformatted. Resolved as the npm module `testcafe-reporter-webtools` via NODE_PATH (see
 * TestcafeReporter.kt) and selected with `-r webtools`.
 */

const fs = require('fs')

const write = str => {
  try {
    fs.writeSync(1, str)
  } catch {
    process.stdout.write(str)
  }
}

const esc = str => String(str == null ? '' : str)
  .replace(/\|/g, '||')
  .replace(/'/g, "|'")
  .replace(/\[/g, '|[')
  .replace(/\]/g, '|]')
  .replace(/\n/g, '|n')
  .replace(/\r/g, '|r')

const tc = (name, attrs) => {
  const parts = Object.keys(attrs || {})
    .filter(key => attrs[key] !== undefined && attrs[key] !== null && attrs[key] !== '')
    .map(key => `${key}='${esc(attrs[key])}'`)
    .join(' ')
  write(`\n##teamcity[${name}${parts ? ' ' + parts : ''}]\n`)
}

module.exports = function () {
  return {
    // Closure state: the currently-open fixture suite and the file its tests live in.
    _fixtureOpen: false,
    _currentFixture: '',
    _currentFile: '',

    reportTaskStart(startTime, userAgents, testCount) {
      this._fixtureOpen = false
      this._currentFixture = ''
      this._currentFile = ''
      tc('enteredTheMatrix', {})
      tc('testCount', { count: String(testCount || 0) })
      tc('testingStarted', {})
    },

    reportFixtureStart(name, path, meta) {
      if (this._fixtureOpen) tc('testSuiteFinished', { name: this._currentFixture })
      this._currentFixture = name || ''
      this._currentFile = path || ''
      this._fixtureOpen = true
      const hint = path ? `testcafe://${path}::${name || ''}` : undefined
      tc('testSuiteStarted', { name: this._currentFixture, locationHint: hint })
    },

    reportTestStart(name, meta, testStartInfo) {
      const hint = this._currentFile ? `testcafe://${this._currentFile}::${name}` : undefined
      tc('testStarted', { name, locationHint: hint, captureStandardOutput: 'false' })
    },

    reportTestDone(name, testRunInfo, meta) {
      const errs = (testRunInfo && testRunInfo.errs) || []
      if (testRunInfo && testRunInfo.skipped) {
        tc('testIgnored', { name })
      } else if (errs.length) {
        let details = ''
        try {
          details = errs.map(err => this.formatError ? this.formatError(err) : (err.errMsg || String(err))).join('\n\n')
        } catch {
          details = errs.map(err => err.errMsg || String(err)).join('\n\n')
        }
        const message = (errs[0] && (errs[0].errMsg || errs[0].message)) || 'Test failed'
        tc('testFailed', { name, message, details })
      }
      const duration = testRunInfo && testRunInfo.durationMs != null ? String(Math.round(testRunInfo.durationMs)) : undefined
      tc('testFinished', { name, duration })
    },

    reportTaskDone(endTime, passed, warnings, result) {
      if (this._fixtureOpen) {
        tc('testSuiteFinished', { name: this._currentFixture })
        this._fixtureOpen = false
      }
      tc('testingFinished', {})
    },
  }
}
