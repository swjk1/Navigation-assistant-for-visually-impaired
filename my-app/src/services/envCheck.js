/**
 * Bootstrap guard: refuse to run with a missing or placeholder Gemini key.
 * Call early from any entry that will hit the Gemini API.
 */

const PLACEHOLDER = 'your_gemini_api_key_here';

/**
 * @returns {string} Validated API key
 * @throws {Error} If key is missing or still the template placeholder
 */
export function requireGeminiApiKey() {
  const key =
    process.env.EXPO_PUBLIC_GEMINI_API_KEY ||
    process.env.GEMINI_API_KEY ||
    '';

  if (!key || key.trim() === '' || key === PLACEHOLDER) {
    throw new Error(
      'Missing or invalid GEMINI_API_KEY. Copy .env.example to .env and set a real key. Never commit .env.'
    );
  }

  return key;
}

/**
 * Soft check for UI bootstrap — returns false instead of throwing.
 * @returns {boolean}
 */
export function hasValidGeminiApiKey() {
  try {
    requireGeminiApiKey();
    return true;
  } catch {
    return false;
  }
}
