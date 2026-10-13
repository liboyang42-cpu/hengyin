/**
 * Dev Server Helper — 启动/连接管理后台前端开发服务器
 *
 * 本项目使用 @vue/cli-service serve (webpack-dev-server)
 * 提供: start / stop / waitUntilReady
 */

const { spawn } = require('node:child_process')
const path = require('node:path')
const http = require('node:http')

const UI_ROOT = path.join(__dirname, '../../..')

class DevServer {
  constructor(port = 8080) {
    this.port = port
    this.url = `http://localhost:${port}`
    this._process = null
  }

  /**
   * 启动 dev server (vue-cli-service serve)
   * @returns {Promise<string>} server URL
   */
  start() {
    return new Promise((resolve, reject) => {
      if (this._process) {
        return resolve(this.url)
      }

      this._process = spawn('npx', ['vue-cli-service', 'serve', '--port', String(this.port)], {
        cwd: UI_ROOT,
        stdio: ['ignore', 'pipe', 'pipe'],
        env: { ...process.env, BROWSER: 'none' },
      })

      let resolved = false
      const timeout = setTimeout(() => {
        if (!resolved) {
          resolved = true
          reject(new Error('Dev server start timeout (60s)'))
        }
      }, 60000)

      this._process.stdout.on('data', (data) => {
        const text = data.toString()
        if (!resolved && (text.includes('App running at') || text.includes('Compiled successfully'))) {
          resolved = true
          clearTimeout(timeout)
          resolve(this.url)
        }
      })

      this._process.stderr.on('data', (data) => {
        const text = data.toString()
        if (!resolved && text.includes('Compiled with warnings')) {
          resolved = true
          clearTimeout(timeout)
          resolve(this.url)
        }
      })

      this._process.on('error', (err) => {
        if (!resolved) {
          resolved = true
          clearTimeout(timeout)
          reject(err)
        }
      })

      this._process.on('exit', (code) => {
        if (!resolved) {
          resolved = true
          clearTimeout(timeout)
          reject(new Error(`Dev server exited with code ${code}`))
        }
      })
    })
  }

  /**
   * 轮询直到服务器返回 200
   */
  async waitUntilReady(timeoutMs = 30000) {
    const start = Date.now()
    while (Date.now() - start < timeoutMs) {
      try {
        await this._ping()
        return true
      } catch {
        await new Promise(r => setTimeout(r, 1000))
      }
    }
    throw new Error(`Dev server not ready after ${timeoutMs}ms`)
  }

  _ping() {
    return new Promise((resolve, reject) => {
      const req = http.get(this.url, (res) => {
        res.resume()
        resolve(res.statusCode)
      })
      req.on('error', reject)
      req.setTimeout(5000, () => {
        req.destroy()
        reject(new Error('timeout'))
      })
    })
  }

  /**
   * 停止 dev server
   */
  stop() {
    if (this._process) {
      this._process.kill('SIGTERM')
      this._process = null
    }
  }
}

module.exports = { DevServer }
