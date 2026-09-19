/**
 * Bootstrap guard for Gemini credentials.
 *
 * Prefer GEMINI_API_KEY (not embedded in Expo web/public bundles).
 * EXPO_PUBLIC_GEMINI_API_KEY is accepted only as a last-resort fallback and
 * is unsafe for production (it ships inside the client bundle).
 */

const PLACEHOLDER = 'your_gemini_api_key_here';

/**
 * @returns {string} Validated API key
 * @throws {Error} If key is missing or still the template placeholder
 */
export function requireGeminiApiKey() {
  const privateKey = (process.env.GEMINI_API_KEY || '').trim();
  const publicKey = (process.env.EXPO_PUBLIC_GEMINI_API_KEY || '').trim();

  const key =
    privateKey && privateKey !== PLACEHOLDER
      ? privateKey
      : publicKey && publicKey !== PLACEHOLDER
        ? publicKey
        : '';

  if (!key) {
    throw new Error(
      'Missing or invalid GEMINI_API_KEY. Set GEMINI_API_KEY in .env (preferred). Avoid EXPO_PUBLIC_ for production — it is bundled into the client.'
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

/**
 * True if only the public Expo key is available (insecure for production).
 */
export function isUsingPublicBundledGeminiKey() {
  const privateKey = (process.env.GEMINI_API_KEY || '').trim();
  const publicKey = (process.env.EXPO_PUBLIC_GEMINI_API_KEY || '').trim();
  const privateOk = privateKey && privateKey !== PLACEHOLDER;
  const publicOk = publicKey && publicKey !== PLACEHOLDER;
  return !privateOk && publicOk;
}
