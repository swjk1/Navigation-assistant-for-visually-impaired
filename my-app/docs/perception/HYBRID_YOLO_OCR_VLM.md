# Hybrid Perception: YOLO26n + ML Kit OCR + gated Gemini Flash

## Policy (updated)

- **YOLO + ML Kit every frame** (on-device)
- **Gemini only when needed** to reduce 503 demand:
  - ML Kit empty / low confidence / OCR error
  - YOLO empty / error
  - Caution keywords in OCR
  - Or explicit `forceVlm` / `vlmOnTop: true`

Live Gemini also uses **retries + backoff** and **model fallbacks**, with a **minimum gap** between calls.

## Who owns what

| Layer | Role |
|---|---|
| YOLO26n | Object boxes |
| ML Kit | OCR source of truth |
| Gemini Flash | Scene/hazard when on-device stack is unsure |

## Enable live

1. Key in `my-app/.env`
2. `PERCEPTION_MODE=live`
3. Android build for YOLO+OCR: `npx expo run:android`
