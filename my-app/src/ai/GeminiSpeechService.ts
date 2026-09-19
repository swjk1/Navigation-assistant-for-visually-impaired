import { File } from 'expo-file-system';

const PREFERRED_GEMINI_MODEL = process.env.EXPO_PUBLIC_GEMINI_MODEL?.trim() || 'gemini-3.6-flash';
const FALLBACK_GEMINI_MODEL = 'gemini-2.5-flash';

function guessAudioMimeType(uri: string): string {
  const lower = uri.toLowerCase();
  if (lower.endsWith('.wav')) return 'audio/wav';
  if (lower.endsWith('.mp3')) return 'audio/mpeg';
  if (lower.endsWith('.aac')) return 'audio/aac';
  if (lower.endsWith('.caf')) return 'audio/x-caf';
  // expo-audio high quality preset usually produces m4a
  return 'audio/mp4';
}

function extractTextFromGeminiResponse(payload: unknown): string {
  if (!payload || typeof payload !== 'object') return '';
  const candidates = (payload as { candidates?: unknown[] }).candidates;
  if (!Array.isArray(candidates) || candidates.length === 0) return '';

  const first = candidates[0] as { content?: { parts?: Array<{ text?: string }> } };
  const parts = first.content?.parts;
  if (!Array.isArray(parts)) return '';

  return parts
    .map((part) => (typeof part.text === 'string' ? part.text : ''))
    .join(' ')
    .trim();
}

export async function transcribeAudioWithGemini(audioUri: string): Promise<string> {
  const apiKey = process.env.EXPO_PUBLIC_GEMINI_API_KEY?.trim();
  if (!apiKey) {
    throw new Error('Missing EXPO_PUBLIC_GEMINI_API_KEY');
  }

  const file = new File(audioUri);
  const base64Audio = await file.base64();
  const mimeType = guessAudioMimeType(audioUri);

  const modelsToTry =
    PREFERRED_GEMINI_MODEL === FALLBACK_GEMINI_MODEL
      ? [PREFERRED_GEMINI_MODEL]
      : [PREFERRED_GEMINI_MODEL, FALLBACK_GEMINI_MODEL];

  let lastError = 'Unknown Gemini error';

  for (const model of modelsToTry) {
    const response = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          contents: [
            {
              parts: [
                {
                  text: 'Transcribe the speech in this audio. Return only the spoken transcript text.',
                },
                {
                  inline_data: {
                    mime_type: mimeType,
                    data: base64Audio,
                  },
                },
              ],
            },
          ],
        }),
      },
    );

    if (response.ok) {
      const json = (await response.json()) as unknown;
      const transcript = extractTextFromGeminiResponse(json);
      return transcript.replace(/^"+|"+$/g, '').trim();
    }

    const errorText = await response.text();
    lastError = `${response.status} ${errorText}`;

    // 404 generally means model not available to this key/version. Try fallback.
    if (response.status !== 404) break;
  }

  throw new Error(`Gemini request failed: ${lastError}`);
}
