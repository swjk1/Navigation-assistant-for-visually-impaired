# Navigation Assistant for Visually Impaired

An Android app that walks a blind or low-vision user through an indoor space, using the phone's
camera for perception and ARCore depth for mapping, and speaking or vibrating one instruction at
a time.

**Documentation:** [ARCHITECTURE.md](./ARCHITECTURE.md) (system diagram) ·
[TECHNICAL.md](./TECHNICAL.md) (full technical reference) · [DEMO.md](./DEMO.md) (build and demo)
· [my-app/docs/](./my-app/docs/) (documentation map)

The Expo client, the native modules and the navigation core all live in [`my-app/`](./my-app/).

## Layout

| Path | What it is |
| --- | --- |
| `my-app/src/` | Expo / React Native app: screens, guidance output, the JS perception stack |
| `my-app/modules/indoor-perception/` | Native module: owns the ARCore session, runs YOLO + ML Kit OCR |
| `my-app/modules/navigation-native/` | Native module: ARCore pose and depth into the navigation engine |
| `my-app/navigation-core/` | The navigation engine. Pure Kotlin, no Android or ARCore on its classpath |
| `my-app/test/` | Offline test suite (`npm test`) |
| `my-app/scripts/` | Diagnostics that need a network, a key or a device. Never run by CI |

`my-app/navigation-core` is deliberately a separate Gradle project with no Android SDK. An
accidental `import com.google.ar.core.*` in the engine fails its build rather than going
unnoticed — see [`modules/navigation-native/android/build.gradle`](./my-app/modules/navigation-native/android/build.gradle).

## Getting started

```bash
cd my-app
npm install
cp .env.example .env     # then add a Gemini key if you want the cloud perception path
npm run android          # a dev build is required; Expo Go cannot load the native modules
```

An ARCore-capable device with Depth API support is needed for navigation. Perception alone runs
on any Android device with the dev build installed.

## Checks

```bash
cd my-app
npm run verify      # lint + type-check + offline tests. This is what CI runs.
npm test            # offline test suite on its own
npm run typecheck   # app and Node tooling, separately
npm run test:core   # the Kotlin navigation engine (needs a JDK 21 on PATH)
```

`npm test` never touches the network, a Gemini key or a device. The things that do live under
`my-app/scripts/` and are run by hand — see `package.json` for the `probe:`, `profile:`,
`report:` and `audit:` scripts.

## Security

- `.env` is ignored at both the repo root and in `my-app/`, along with keys, live captures and
  build caches. Copy `my-app/.env.example` and never commit a real key.
- `npm run audit:security` checks the guardrails that can be checked automatically.
- The Gemini key the app uses on-device (`EXPO_PUBLIC_GEMINI_API_KEY`) is compiled into the
  bundle and can be extracted from the APK. That is acceptable for development and demos; a
  release would need the Gemini call to move behind a server. See `my-app/.env.example`.
