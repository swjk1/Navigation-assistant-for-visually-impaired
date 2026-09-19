# Navigation Assistant for Visually Impaired

Hackathon monorepo. The Expo client and Person 1 Perception Engine live in [`my-app/`](./my-app/).

## Person 1 (Perception)

See [`my-app/README.md`](./my-app/README.md) and step docs under [`my-app/docs/person1/`](./my-app/docs/person1/).

**Current checkpoint:** Step 1 complete (secure setup + camera harness). Live hardware snapshot pending device capture.

## Security

- Root and `my-app/` `.gitignore` block `.env`, keys, live captures, and build caches.
- Copy `my-app/.env.example` → `my-app/.env` locally. Never commit real keys.
