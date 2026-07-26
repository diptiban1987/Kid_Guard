# Free Platform Comparison for KidGuard Deployment

This document compares all free hosting platforms for the KidGuard cloud server (`cloud-server/`) and recommends the best fit based on the project's architecture.

---

## Key Project Requirements

The KidGuard app architecture has specific needs that affect platform choice:

| Requirement | Reason |
|---|---|
| **Always-on server** | Child device reports every 5 minutes; parent dashboard polls every 30 seconds. If the server spins down, reports are missed and commands are delayed. |
| **Persistent storage** | Uploaded APK files and media must survive server restarts and redeploys. |
| **HTTPS** | Android apps require HTTPS for network security (see `network_security_config.xml`). |
| **Database** | SQLite (development default) or PostgreSQL (production). |
| **WebSocket (optional)** | Flask-SocketIO is used for real-time updates, but the app falls back to 30-second polling when unavailable (`app.py:14-19`). |
| **File upload support** | APK OTA updates require up to 50 MB file uploads (`config.py:17`). |

---

## Platform Comparison

| Platform | Always-On? | Storage | Database | WebSocket | Difficulty | Verdict |
|---|---|---|---|---|---|---|
| **PythonAnywhere (Free)** | Yes (no spin-down) | 512 MB | SQLite only | No | Easy | Best fit for this project |
| **Render (Free)** | No (spins down after 15 min) | Ephemeral | PostgreSQL (90 days only) | Optional | Easy | Not suitable — missed reports and delayed commands |
| **Oracle Cloud (Always Free)** | Yes (real virtual machine) | 200 GB | Anything you install | Yes | Hard | Best long-term, but requires server administration |
| **Koyeb (Free)** | Yes | Small | External only | Yes | Medium | Decent, but very small free tier |
| **Fly.io (Free)** | Partial | 3 GB | SQLite or Postgres | Yes | Medium | Requires credit card |
| **Google Cloud Run (Free)** | No (serverless, spins to zero) | Ephemeral | Cloud SQL (paid) | No | Hard | Not suitable for polling architecture |
| **Vercel (Free)** | No (serverless) | Ephemeral | External only | No | Hard | Not suitable for Flask long-running |
| **Heroku (Free)** | N/A | N/A | N/A | N/A | N/A | No longer offers a free tier |

---

## Detailed Analysis

### PythonAnywhere (Free) — Recommended

**Best overall choice for this project.**

#### Why it fits

1. **Always-on** — PythonAnywhere free does not spin down. The child device reporting every 5 minutes will always reach the server, and parent commands will be delivered within seconds.
2. **SQLite is fine** — the app already uses SQLite by default (`config.py:10-13`). For a single family (1 to 2 children), SQLite handles the load easily.
3. **Persistent storage** — uploaded APK files and media persist on the filesystem (unlike Render free where they are lost on every deploy).
4. **HTTPS included** — PythonAnywhere provides free SSL on `yourusername.pythonanywhere.com`.
5. **Already documented** — `PYTHONANYWHERE_DEPLOY.md` has all 9 steps ready to follow.
6. **No credit card required** — sign up and deploy immediately.

#### Limitations

| Limit | Value | Impact on this project |
|---|---|---|
| Storage | 512 MB | Enough for the app + a few APK versions + media |
| CPU | 1 core (shared) | Fine for 1 to 2 devices |
| Concurrent requests | 1 (processed sequentially) | Reports are quick; no issue at this scale |
| Background tasks | Not supported | Not needed — the app uses polling, not background workers |
| WebSocket | Not available | The app falls back to 30-second polling automatically |
| Database | SQLite only | Good enough for a family deployment |
| Bandwidth | Unlimited (but throttled) | Fine for low traffic |

#### How to deploy

Follow the guide at `PYTHONANYWHERE_DEPLOY.md`.

---

### Oracle Cloud (Always Free) — Best for Long-Term

**Best choice if you want no limitations and are comfortable with server administration.**

#### Why it fits

- **Genuinely free forever** — not a trial. Oracle Cloud provides an Always Free tier that does not expire.
- **Full virtual machine** — ARM-based VM with 4 cores and 24 GB RAM.
- **200 GB storage** — more than enough for the app, database, and all uploaded files.
- **Run anything** — you can run Docker Compose (PostgreSQL, Flask, Nginx) exactly as defined in `cloud-server/docker-compose.yml`.
- **WebSocket support** — full SocketIO support since you control the server.
- **Custom domain** — point your domain to the VM and use Let's Encrypt for free SSL.

#### What you need to do yourself

| Task | Details |
|---|---|
| Install Docker | `apt install docker.io docker-compose` |
| Configure firewall | Open ports 80, 443, 5000 in Oracle Cloud security list + VM iptables |
| Clone the repo | `git clone` the project to the VM |
| Set environment variables | Create `.env` with `SECRET_KEY`, `JWT_SECRET_KEY`, `DATABASE_URL`, `CLOUD_SERVER_URL` |
| Run Docker Compose | `cd cloud-server && docker-compose up -d` |
| Set up SSL | Use Certbot/Let's Encrypt with Nginx |
| Configure Nginx | Update `nginx.conf` with your domain |

#### Limitations

| Limit | Value |
|---|---|
| Sign-up | Requires a credit card for verification (not charged) |
| Region | Some regions have limited Always Free capacity |
| Setup difficulty | Medium to hard — you manage everything |
| Support | Community only on free tier |

#### When to choose this

- You want WebSocket support (real-time updates without polling).
- You want PostgreSQL instead of SQLite.
- You expect more than 2 child devices.
- You want a custom domain.
- You are comfortable with Linux server administration.

---

### Render (Free) — Not Recommended

**Not suitable for this project due to spin-down behavior.**

#### Why it does not fit

| Issue | Impact |
|---|---|
| Spins down after 15 min of inactivity | When the parent sends a "lock device" command, the server may be asleep. The child will not receive the command for up to 30 seconds (cold start time). |
| Ephemeral filesystem on free tier | Uploaded APK files and media are deleted on every redeploy. The OTA update system breaks. |
| PostgreSQL free for 90 days only | After 90 days, the database is deleted. You would lose all data. |
| No persistent disk on free tier | The persistent disk feature requires a paid plan ($7/month). |

#### When Render would be acceptable

- You upgrade to the **Starter plan** ($7/month) for always-on and persistent disk.
- You are only testing temporarily (within the 90-day PostgreSQL window).

#### If you still want to use Render

Follow the guide at `RENDER_DEPLOY.md`, but be aware of the limitations above.

---

### Other Platforms — Not Recommended

#### Koyeb (Free)

| Pros | Cons |
|---|---|
| Always-on | Very small free tier (512 MB RAM, 0.1 vCPU) |
| Docker support | External database only (no managed Postgres on free) |
| Decent performance | May change free tier terms at any time |

#### Fly.io (Free)

| Pros | Cons |
|---|---|
| 3 GB persistent volume | Requires credit card to sign up |
| PostgreSQL support | More complex deployment than PythonAnywhere |
| Good performance | Free tier has limited monthly compute hours |

#### Google Cloud Run (Free)

| Pros | Cons |
|---|---|
| Generous free tier (2 million requests/month) | Serverless — spins to zero between requests |
| Auto-scales | Request timeout (max 60 minutes, but cold starts are slow) |
| Pay-per-use | Not designed for long-running Flask apps with polling |
| | No persistent filesystem |
| | Cloud SQL requires payment |

#### Vercel (Free)

| Pros | Cons |
|---|---|
| Extremely fast | Designed for Next.js / static sites, not Flask |
| Global CDN | No persistent filesystem |
| | No WebSocket support |
| | Request timeout (10 seconds on free tier) |
| | Not suitable for long-running Flask apps |

#### Heroku

- **No longer offers a free tier** as of November 2022.
- Minimum cost is $5/month (Eco dynos).

---

## Decision Matrix

| Your situation | Recommended platform | Guide |
|---|---|---|
| Quick, easy, works today, no cost | PythonAnywhere Free | `PYTHONANYWHERE_DEPLOY.md` |
| Full power, no limits, willing to configure | Oracle Cloud Always Free | See "Oracle Cloud" section above |
| Testing temporarily with Docker | Render Free (Starter for production) | `RENDER_DEPLOY.md` |
| Local development only | Your own machine | `python app.py` in `cloud-server/` |

---

## Final Recommendation

### Use PythonAnywhere Free

This is the best free platform for the KidGuard project because:

1. **It is always-on** — the single most important requirement for a monitoring app. The server must be ready to receive reports and deliver commands at any moment.
2. **Persistent storage** — APK files and media uploads survive restarts.
3. **SQLite works** — the app already defaults to SQLite, which is fine for a family-scale deployment.
4. **Free HTTPS** — included on the `pythonanywhere.com` subdomain.
5. **No credit card** — sign up and deploy immediately.
6. **Already documented** — `PYTHONANYWHERE_DEPLOY.md` has every step.

The only trade-off is no WebSocket support on the free tier, but the app already handles this by falling back to 30-second polling (`app.py:14-19`). For 1 to 2 child devices, polling every 30 seconds is perfectly adequate.

### When to switch to Oracle Cloud

Switch to Oracle Cloud Always Free if you need any of the following:
- Real-time WebSocket updates (no polling delay)
- PostgreSQL database (for more than 5 devices)
- Custom domain
- More than 512 MB storage
- Full control over the server environment

---

## Quick Comparison Table

| Feature | PythonAnywhere Free | Oracle Cloud Always Free | Render Free |
|---|---|---|---|
| **Always-on** | Yes | Yes | No (15 min spin-down) |
| **Cost** | Free | Free | Free |
| **Credit card needed** | No | Yes (verification only) | No |
| **Storage** | 512 MB | 200 GB | Ephemeral |
| **Database** | SQLite | Any (PostgreSQL, etc.) | PostgreSQL (90 days only) |
| **WebSocket** | No | Yes | Optional |
| **HTTPS** | Yes | Via Let's Encrypt | Yes |
| **Custom domain** | No (paid only) | Yes | Yes |
| **Docker support** | No | Yes | Yes |
| **Setup difficulty** | Easy | Hard | Easy |
| **Persistent uploads** | Yes | Yes | No |
| **Suitable for this project** | **Yes** | **Yes** | **No** |
