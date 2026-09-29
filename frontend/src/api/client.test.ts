import { describe, expect, it } from 'vitest'
import { setLanguage } from '../i18n'
import { ApiError, errorMessage } from './client'

describe('errorMessage', () => {
  it('translates known backend error codes into the interface language', async () => {
    const error = new ApiError(401, 'Invalid username or password', 'invalid_credentials')
    expect(errorMessage(error)).toBe('Nome utente o password non validi.')
    await setLanguage('FR')
    expect(errorMessage(error)).toBe('Nom d’utilisateur ou mot de passe incorrect.')
  })

  it('falls back to the server message for codes it does not know', () => {
    expect(errorMessage(new ApiError(409, 'Something new happened', 'brand_new_code'))).toBe('Something new happened')
    expect(errorMessage(new ApiError(500, 'Error 500'))).toBe('Error 500')
  })

  it('handles other thrown values', () => {
    expect(errorMessage(new Error('Network down'))).toBe('Network down')
    expect(errorMessage('boom')).toBe('Errore inatteso')
  })
})
