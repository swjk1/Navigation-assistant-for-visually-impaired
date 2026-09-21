/**
 * Gemini credential lookup, and an honest account of what it can and cannot protect.
 *
 * READ THIS BEFORE TRUSTING THE KEY HANDLING HERE.
 *
 * Expo inlines only variables prefixed `EXPO_PUBLIC_` into the client bundle. A plain
 * `GEMINI_API_KEY` is therefore visible to Node - the offline tests and the diagnostic scripts
 * under `scripts/` - and is ALWAYS undefined once the code is running on a phone.
 *
 * The practical consequence: on-device, the only key that can possibly be found is
 * `EXPO_PUBLIC_GEMINI_API_KEY`, and that key ships inside the APK where anyone can extract it.
 * Preferring the private name below does not change that; it only means the Node-side tooling
 * can use a key that never reaches a bundle.
 *
 * This file used to claim the preference order made the setup safe for production. It does not.
 * Shipping the cloud perception path to real users needs the Gemini call to move behind a
 * server that holds the key, with the app calling that server instead. Until then the cloud
 * path is a development and demo capability - which is also why `src/index.js` is not on the
 * shipping navigation path.
 *
 * @see src/index.js for the scope of the JavaScript perception stack.
 */

const PLACEHOLDER = 'your_gemini_api_key_here';

/**
 * @param {string | undefined} value
 * @returns {string} The trimmed key, or '' if absent or still the template placeholder.
 */
function usable(value) {
  const key = (value || '').trim();
  return key && key !== PLACEHOLDER ? key : '';
}

/** The key Node-side tooling uses. Never present in a client bundle. */
function privateKey() {
  return usable(process.env.GEMINI_API_KEY);
}

/** The key the app finds on-device. Present in the bundle, therefore extractable. */
function bundledKey() {
  return usable(process.env.EXPO_PUBLIC_GEMINI_API_KEY);
}

/**
 * @returns {string} A usable API key.
 * @throws {Error} If neither name is set to anything but the placeholder.
 */
export function requireGeminiApiKey() {
  const key = privateKey() || bundledKey();

  if (!key) {
    throw new Error(
      'Missing Gemini API key. Set GEMINI_API_KEY in .env for tests and scripts, or ' +
        'EXPO_PUBLIC_GEMINI_API_KEY for on-device runs. Note that the EXPO_PUBLIC_ key is ' +
        'compiled into the app bundle and can be extracted from the APK.'
    );
  }

  return key;
}

/**
 * Soft check for UI bootstrap - returns false instead of throwing.
 *
 * @returns {boolean}
 */
export function hasValidGeminiApiKey() {
  return Boolean(privateKey() || bundledKey());
}

/**
 * True when the only key available is the one compiled into the client bundle.
 *
 * On a device this is true whenever a key is configured at all, because the private name cannot
 * reach the bundle. Callers use it to surface that the cloud path is demo-grade.
 *
 * @returns {boolean}
 */
export function isUsingPublicBundledGeminiKey() {
  return !privateKey() && Boolean(bundledKey());
}
