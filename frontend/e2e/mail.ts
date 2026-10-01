import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { expect } from '@playwright/test'

/** Where the SMTP sink started by the Playwright config writes the messages it receives. */
const MAIL_DIR = process.env.E2E_MAIL_DIR ?? path.join(os.tmpdir(), 'finance-e2e-mail')

export interface Mail {
  subject: string
  /** Plain-text version */
  text: string
  /** HTML version */
  html: string
}

/** The messages received for `to`, oldest first, waiting until there are at least `count`. */
export async function mailsTo(to: string, count = 1): Promise<Mail[]> {
  const prefix = `${to.toLowerCase()}-`
  let files: string[] = []
  await expect.poll(() => {
    files = fs.existsSync(MAIL_DIR) ? fs.readdirSync(MAIL_DIR).filter((f) => f.startsWith(prefix)).toSorted(byTime) : []
    return files.length
  }, { message: `mail to ${to}` }).toBeGreaterThanOrEqual(count)
  return files.map((f) => parse(fs.readFileSync(path.join(MAIL_DIR, f), 'latin1')))
}

/** Time and sequence number at the end of a file name: <recipient>-<time>-<n>.eml */
const stamp = (file: string) => file.replace(/\.eml$/, '').split('-').slice(-2).map(Number)

function byTime(a: string, b: string) {
  const [ta, na] = stamp(a)
  const [tb, nb] = stamp(b)
  return ta - tb || na - nb
}

/** Subject and decoded plain-text and HTML versions of a message (single part or multipart). */
function parse(raw: string): Mail {
  const { headers } = split(raw)
  const subject = decodeWords(header(headers, 'Subject'))
  return { subject, text: find(raw, 'text/plain') ?? '', html: find(raw, 'text/html') ?? '' }
}

function split(raw: string) {
  const at = raw.indexOf('\r\n\r\n')
  return { headers: raw.slice(0, at).replace(/\r\n[ \t]+/g, ' '), body: raw.slice(at + 4) }
}

function header(headers: string, name: string) {
  return new RegExp(`^${name}:\\s*(.*)$`, 'im').exec(headers)?.[1] ?? ''
}

/** The decoded body of the first part of `type`, looking inside multipart containers. */
function find(raw: string, type: string): string | undefined {
  const { headers, body } = split(raw)
  const contentType = header(headers, 'Content-Type').toLowerCase()
  if (contentType.startsWith('multipart/')) {
    const boundary = /boundary="?([^";]+)"?/i.exec(header(headers, 'Content-Type'))?.[1]
    if (!boundary) return undefined
    for (const part of body.split(`--${boundary}`).slice(1)) {
      if (part.startsWith('--')) break
      const found = find(part.replace(/^\r\n/, ''), type)
      if (found !== undefined) return found
    }
    return undefined
  }
  if (!contentType.startsWith(type) && !(type === 'text/plain' && contentType === '')) return undefined
  const encoding = header(headers, 'Content-Transfer-Encoding').toLowerCase()
  const bytes = encoding === 'base64' ? Buffer.from(body.replace(/\s+/g, ''), 'base64')
    : encoding === 'quoted-printable' ? quotedPrintable(body)
      : Buffer.from(body, 'latin1')
  return bytes.toString('utf8').replace(/\r\n/g, '\n')
}

function quotedPrintable(body: string): Buffer {
  const soft = body.replace(/=\r\n/g, '')
  const out: number[] = []
  for (let i = 0; i < soft.length; i++) {
    if (soft[i] === '=' && /^[0-9A-F]{2}$/i.test(soft.slice(i + 1, i + 3))) {
      out.push(parseInt(soft.slice(i + 1, i + 3), 16))
      i += 2
    } else {
      out.push(soft.charCodeAt(i))
    }
  }
  return Buffer.from(out)
}

/** RFC 2047 encoded words (=?UTF-8?Q?...?= / =?UTF-8?B?...?=) in a header. */
function decodeWords(value: string): string {
  return value.replace(/=\?utf-8\?([bq])\?([^?]*)\?=\s*/gi, (_, kind: string, text: string) =>
    kind.toLowerCase() === 'b'
      ? Buffer.from(text, 'base64').toString('utf8')
      : quotedPrintable(text.replace(/_/g, ' ')).toString('utf8'))
}
