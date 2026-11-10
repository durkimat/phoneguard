# Cloudflare onboarding spike

Tests the shape of "parent signs in, we deploy their family hub into their own Cloudflare account".

- `src/worker.js`: smallest hub: a Worker plus a SQLite-backed Durable Object. **Tested locally** with `npx wrangler dev --local` (state persists across requests).
- `deploy.mjs`: deploys it with REST only (no wrangler, no GitHub). **Not yet run against a real account**: needs your own Cloudflare account and token.

To run it (the owner, with their own account; nothing here creates accounts):
1. Open this pre-filled token page (Cloudflare fills in the permission; you press Continue, then Create):
   https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=%5B%7B%22key%22%3A%22workers_scripts%22%2C%22type%22%3A%22edit%22%7D%5D&accountId=*&zoneId=all&name=phoneguard-spike
2. Copy the token and your Account ID (dashboard, right-hand side of Workers & Pages).
3. `CF_TOKEN=... CF_ACCOUNT=... node deploy.mjs` then `curl -X POST -d hello https://phoneguard-spike.<your-subdomain>.workers.dev`
4. Delete the Worker and the token afterwards.

What this answers: does upload-with-Durable-Object-and-migration work over plain REST with a minimal token, and does the pre-filled link need anything else.
Still to test separately: Cloudflare OAuth (private client) as a replacement for steps 1-2, and sign-up of a brand-new user inside that flow.
