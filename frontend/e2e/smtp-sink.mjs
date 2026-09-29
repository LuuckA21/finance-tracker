// A minimal SMTP server for the end-to-end tests: accepts every message and writes it to
// E2E_MAIL_DIR as <recipient>-<time>-<n>.eml, where the tests read it (see mail.ts).
// Plain SMTP on 127.0.0.1 only; no authentication, no TLS: never use it outside the tests.
import fs from 'node:fs'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'

const dir = process.env.E2E_MAIL_DIR ?? path.join(os.tmpdir(), 'finance-e2e-mail')
const port = Number(process.env.E2E_SMTP_PORT ?? 2525)
fs.mkdirSync(dir, { recursive: true })
let count = 0

function save(recipients, message) {
  for (const to of recipients) {
    // The address becomes a file name: keep it to harmless characters so it cannot leave the directory
    const name = to.toLowerCase().replace(/[^a-z0-9@._+-]/g, '_').replace(/^\.+/, '_')
    fs.writeFileSync(path.join(dir, `${name}-${Date.now()}-${count++}.eml`), message)
  }
}

net.createServer((socket) => {
  socket.setEncoding('latin1')
  let buffer = ''
  let data = null
  let recipients = []
  const reply = (line) => socket.write(`${line}\r\n`)
  reply('220 finance-e2e SMTP sink')
  socket.on('data', (chunk) => {
    buffer += chunk
    let end
    while ((end = buffer.indexOf('\r\n')) >= 0) {
      const line = buffer.slice(0, end)
      buffer = buffer.slice(end + 2)
      if (data !== null) {
        if (line === '.') {
          save(recipients, data.join('\r\n'))
          data = null
          recipients = []
          reply('250 OK')
        } else {
          data.push(line.startsWith('..') ? line.slice(1) : line)
        }
        continue
      }
      const command = line.slice(0, 4).toUpperCase()
      if (command === 'RCPT') {
        recipients.push(line.replace(/^RCPT TO:\s*<?([^>\s]*)>?.*$/i, '$1'))
        reply('250 OK')
      } else if (command === 'DATA') {
        data = []
        reply('354 End data with <CR><LF>.<CR><LF>')
      } else if (command === 'QUIT') {
        reply('221 Bye')
        socket.end()
      } else {
        // EHLO/HELO, MAIL, RSET, NOOP
        reply('250 OK')
      }
    }
  })
  socket.on('error', () => socket.destroy())
}).listen(port, '127.0.0.1')
