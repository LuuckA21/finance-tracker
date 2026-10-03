import { describe, expect, it } from 'vitest'
import { base64urlToBuffer, bufferToBase64url, creationOptions, requestOptions } from './webauthn'

const bytes = (buffer: ArrayBuffer | BufferSource) => Array.from(new Uint8Array(buffer as ArrayBuffer))

describe('webauthn', () => {
  it('converts base64url both ways, padding and the URL-safe characters included', () => {
    expect(bufferToBase64url(new Uint8Array([251, 255, 191]).buffer)).toBe('-_-_')
    expect(bytes(base64urlToBuffer('-_-_'))).toEqual([251, 255, 191])
    for (const length of [0, 1, 2, 3, 31, 32]) {
      const data = Uint8Array.from({ length }, (_, i) => (i * 37) % 256)
      expect(bytes(base64urlToBuffer(bufferToBase64url(data.buffer)))).toEqual(Array.from(data))
    }
  })

  it('turns the binary values of the server options into buffers', () => {
    const create = creationOptions({
      rp: { id: 'finanze.example.com', name: 'Finanze' },
      user: { id: 'AQID', name: 'luca', displayName: 'luca' },
      challenge: 'BAUG',
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }],
      excludeCredentials: [{ type: 'public-key', id: 'CQo', transports: ['internal'] }],
    })
    expect(bytes(create.user.id)).toEqual([1, 2, 3])
    expect(bytes(create.challenge)).toEqual([4, 5, 6])
    expect(bytes(create.excludeCredentials![0].id)).toEqual([9, 10])
    expect(create.rp.id).toBe('finanze.example.com')

    const get = requestOptions({ challenge: 'BAUG', rpId: 'finanze.example.com', userVerification: 'required' })
    expect(bytes(get.challenge)).toEqual([4, 5, 6])
    expect(get.allowCredentials).toEqual([])
    expect(get.userVerification).toBe('required')
  })
})
