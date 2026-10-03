/**
 * Passkeys in the browser. The server sends the options as JSON (binary values in base64url);
 * the browser API wants ArrayBuffers, and the answer goes back as JSON again, in the shape of
 * PublicKeyCredential.toJSON() (converted by hand, which also works where toJSON is missing).
 */

export function base64urlToBuffer(value: string): ArrayBuffer {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(value.length / 4) * 4, '=')
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes.buffer
}

export function bufferToBase64url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

interface CredentialDescriptorJSON {
  type: 'public-key'
  id: string
  transports?: AuthenticatorTransport[]
}

export interface CreationOptionsJSON {
  rp: { id: string; name: string }
  user: { id: string; name: string; displayName: string }
  challenge: string
  pubKeyCredParams: { type: 'public-key'; alg: number }[]
  timeout?: number
  excludeCredentials?: CredentialDescriptorJSON[]
  authenticatorSelection?: AuthenticatorSelectionCriteria
  attestation?: AttestationConveyancePreference
}

export interface RequestOptionsJSON {
  challenge: string
  rpId: string
  timeout?: number
  userVerification?: UserVerificationRequirement
  allowCredentials?: CredentialDescriptorJSON[]
}

const descriptor = (d: CredentialDescriptorJSON): PublicKeyCredentialDescriptor =>
  ({ type: d.type, id: base64urlToBuffer(d.id), transports: d.transports })

export function creationOptions(json: CreationOptionsJSON): PublicKeyCredentialCreationOptions {
  return {
    ...json,
    user: { ...json.user, id: base64urlToBuffer(json.user.id) },
    challenge: base64urlToBuffer(json.challenge),
    excludeCredentials: (json.excludeCredentials ?? []).map(descriptor),
  }
}

export function requestOptions(json: RequestOptionsJSON): PublicKeyCredentialRequestOptions {
  return {
    ...json,
    challenge: base64urlToBuffer(json.challenge),
    allowCredentials: (json.allowCredentials ?? []).map(descriptor),
  }
}

/** Whether this browser can use passkeys at all. */
export function passkeysSupported(): boolean {
  return typeof window !== 'undefined' && typeof window.PublicKeyCredential === 'function' && !!navigator.credentials
}

/** The user closed the browser's dialog or let it time out: nothing to report as an error. */
export function isCancelled(error: unknown): boolean {
  return error instanceof DOMException && (error.name === 'NotAllowedError' || error.name === 'AbortError')
}

/** Creates a passkey on this device; returns the answer to send to the server. */
export async function createPasskey(options: CreationOptionsJSON): Promise<unknown> {
  const credential = await navigator.credentials.create({ publicKey: creationOptions(options) })
  if (!(credential instanceof PublicKeyCredential)) throw new DOMException('No passkey created', 'NotAllowedError')
  const response = credential.response as AuthenticatorAttestationResponse
  return {
    id: credential.id,
    rawId: bufferToBase64url(credential.rawId),
    type: credential.type,
    response: {
      clientDataJSON: bufferToBase64url(response.clientDataJSON),
      attestationObject: bufferToBase64url(response.attestationObject),
      transports: response.getTransports?.() ?? [],
    },
    clientExtensionResults: credential.getClientExtensionResults(),
    authenticatorAttachment: credential.authenticatorAttachment ?? undefined,
  }
}

/** Asks this device for one of its passkeys for the site; returns the answer to send to the server. */
export async function getPasskey(options: RequestOptionsJSON): Promise<unknown> {
  const credential = await navigator.credentials.get({ publicKey: requestOptions(options) })
  if (!(credential instanceof PublicKeyCredential)) throw new DOMException('No passkey chosen', 'NotAllowedError')
  const response = credential.response as AuthenticatorAssertionResponse
  return {
    id: credential.id,
    rawId: bufferToBase64url(credential.rawId),
    type: credential.type,
    response: {
      clientDataJSON: bufferToBase64url(response.clientDataJSON),
      authenticatorData: bufferToBase64url(response.authenticatorData),
      signature: bufferToBase64url(response.signature),
      userHandle: response.userHandle ? bufferToBase64url(response.userHandle) : null,
    },
    clientExtensionResults: credential.getClientExtensionResults(),
    authenticatorAttachment: credential.authenticatorAttachment ?? undefined,
  }
}
