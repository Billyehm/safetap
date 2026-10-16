# Hosting the SafeTap backend on Railway

Takes about 5 minutes. You get a permanent public URL like
`https://safetap-backend-production.up.railway.app` that you paste into
the app's server address field. No more laptop needed.

## Steps

1. Go to **railway.app** and sign up with your GitHub account.
2. **New Project** → **Deploy from GitHub repo** → choose
   `Billyehm/safetap`.
3. Click the service → **Settings** → set **Root Directory** to
   `backend` (the Dockerfile lives there). Railway detects it
   automatically.
4. Go to the **Variables** tab and add:
   - `MOCK_GATEWAYS` = `1` (mock SMS/voice/WhatsApp; set to `0` later
     when you add real provider keys)
   - `SAFETAP_SECRET_KEY` = generate your own with
     `openssl rand -hex 32` on your laptop and paste the output here
     (never reuse a key printed anywhere public; this signs your
     login tokens)
   - `SAFETAP_BASE_URL` = `https://<your-railway-url>` (copy it from the
     service's **Settings → Networking** after the first deploy, then
     come back and set this so tracking links are correct)
5. **Deploy**. Open the public URL in your browser — you should see the
   SafeTap API (try `/api/health`, it returns `{"ok": true}`).
6. In the Android app, set the server address to your Railway URL and
   tap Test connection.

## Going live later

When you want real SMS/voice/WhatsApp instead of mocks, add these
variables in Railway and redeploy:

- `MOCK_GATEWAYS` = `0`
- `TERMII_API_KEY` = your Termii key
- `TERMII_SENDER_ID` = `SafeTap` (or your approved sender ID)
- `VOICE_API_URL`, `VOICE_API_KEY`, `WHATSAPP_API_URL`,
  `WHATSAPP_API_KEY` = your voice/WhatsApp providers

## Notes

- No credit card needed to start: the trial gives you a one-time $5
  credit (good for ~30 days), then the account moves to a free plan with
  $1 of credit a month. A small app like this idles cheap, so testing
  stays free. The Hobby plan ($5/month) is there if you ever outgrow it.
- SQLite is used by default; Railway's disk is ephemeral, so the
  database resets on redeploy. Fine for testing. For production, add a
  Railway Postgres plugin and set `DATABASE_URL` to its connection
  string.
