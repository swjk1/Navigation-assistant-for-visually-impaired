# Navigation Assistant for Visually Impaired

**Documentation:** [TECHNICAL.md](./TECHNICAL.md) (full technical reference) · [ARCHITECTURE.md](./ARCHITECTURE.md) (system diagram) · [DEMO.md](./DEMO.md) (build and demo)

Hackathon monorepo. The Expo client and Person 1 Perception Engine live in [`my-app/`](./my-app/).

## Person 1 (Perception)

See [`my-app/README.md`](./my-app/README.md) and step docs under [`my-app/docs/person1/`](./my-app/docs/person1/).

**Status:** Steps 1–5 complete (offline). PRD §7 final verification: `npm run test:final` in `my-app/`.

Teammates: [`my-app/docs/person1/TEAMMATE_INTEGRATION.md`](./my-app/docs/person1/TEAMMATE_INTEGRATION.md).

## Security

- Root and `my-app/` `.gitignore` block `.env`, keys, live captures, and build caches.
- Copy `my-app/.env.example` → `my-app/.env` locally. Never commit real keys.
