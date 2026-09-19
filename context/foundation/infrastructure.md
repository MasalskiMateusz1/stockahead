---
project: stockahead
researched_at: 2026-09-19
recommended_platform: Coolify (self-hosted) on the existing OVH dedicated server, behind Cloudflare Tunnel
runner_up: Render
context_type: mvp
tech_stack:
  language: Java 21
  framework: Spring Boot 4.1.1 (Thymeleaf, Spring Security, JPA, Flyway)
  runtime: JVM (Eclipse Temurin 21) in a Docker image, PostgreSQL 18
---

## Recommendation

**Deploy on Coolify, self-hosted on the existing OVH dedicated server (Frankfurt), reachable only through Cloudflare Tunnel.**

The developer asked to minimise cost, is most familiar with running their own server, needs one region (a single plant in Poland), and wants Postgres next to the app. Coolify on a server that is already paid for costs nothing extra and runs Postgres 18 on the same host. It also has an official CLI, a built-in MCP server and `llms.txt` docs. It scored **14** in the weighted comparison, second to Render (**16**). Render passes all five agent-friendly criteria, but it costs about $13–31/month and keeps backups only on Render. After seeing the Render cross-check, the developer chose Coolify on OVH, and this file records that choice. The price of the choice is the "managed" criterion, which Coolify fails: the developer owns OS patching, the firewall, Docker, Coolify updates and monitoring. The risk register below is where that cost is tracked.

The step-by-step setup lives in `context/changes/deployment/deployment-plan.md` (decisions D1–D14, S1–S6). The claims in that plan were checked on 2026-09-19. The corrections are listed under **Verified corrections to the deployment plan** and must be applied before running it.

## Platform Comparison

Scoring: Pass = 2, Partial = 1, Fail = 0. CLI-first, Managed and Deploy API count double; Docs counts once; MCP counts half. Interview adjustments:
- Cost (minimise): an already-owned server gets +3; about $8–31/month gets +1; about $47+/month gets −2.
- Co-location (preferred): Postgres on the same platform gets +1; a third-party database gets −1.
- Familiarity (own server): breaks ties only.

Hard filters: the platform must run a JVM, and it must hold persistent connections (SSE for the PRD's under-2 s refresh). Statuses were checked on 2026-09-19.

| Platform | CLI-first | Managed/Serverless | Agent-readable docs | Stable deploy API | MCP / Integration | Base | Adj. | **Total** | Est. cost / month |
|---|---|---|---|---|---|---|---|---|---|
| **Coolify on OVH** | Pass | Fail | Pass | Partial | Pass | 9 | +4 (+1 tie-break) | **14** | ~$0 extra (R2 backups within free tier) |
| **Render** (Frankfurt) | Pass | Pass | Pass | Pass | Pass | 15 | +1 | **16** | ~$13 (512 MB) to ~$31 (2 GB) |
| **Railway** (Amsterdam) | Partial | Partial | Pass | Pass | Partial | 10.5 | +2 | **12.5** | ~$8–15 (Hobby) |
| Fly.io (fra) | Pass | Partial | Pass | Pass | Partial | 12.5 | −2 | 10.5 | ~$47–50 with Managed Postgres |
| Cloudflare Containers | Pass | Partial | Pass | Partial | Pass | 11 | −1 | 10 (passes filters only conditionally) | ~$17–19 incl. PlanetScale PS-5 |
| Vercel | Pass | Pass | Pass | Pass | Pass (MCP beta) | — | — | **Dropped** | — |
| Netlify | Pass | Pass | Pass | Pass | Pass | — | — | **Dropped** | — |

Per-platform notes:

- **Coolify on OVH.**
  - *CLI:* the official `coolify-cli` v1.8.0 (2026-08-19) covers deploy, app logs, deployment logs and `app rollback run`, and the REST API covers the rest.
  - *Managed: Fail.* Bare metal (Ubuntu 26.04, HDD RAID1, no snapshots) leaves the OS, firewall, Docker, cloudflared, Coolify upgrades and monitoring to the developer. Coolify provides TLS, routing, healthchecks and rolling updates only for the app layer.
  - *Docs:* `coolify.io/docs/llms.txt` and `llms-full.txt` exist, and the MDX source is on GitHub.
  - *Deploy API: Partial.* The deploy endpoint changed from `GET` to `POST` in v4.3. Stable v4 has only existed since 2026-04-27.
  - *MCP:* an official MCP server is built in at `https://<instance>/mcp`. It is enabled per instance and per team, and no beta label was found.
- **Render.** The only candidate that passes all five criteria. The CLI has a non-interactive mode with JSON output and `deploys create --wait`, but rollback goes through the REST API or dashboard only. Postgres 13–18 is offered with 18 as default; point-in-time recovery keeps 3 days on Hobby and 7 on Pro. The MCP server has been GA since 2025-08-21 and cannot delete resources. Minuses: account-wide API keys, the 512 MB Starter plan is tight for Spring Boot, and a single HTTP request is capped at 100 minutes.
- **Railway.** Rollback is dashboard-only and pulled images are kept for only 72 h on Hobby. Postgres is an unmanaged template whose backups are "still under development". SSE connections are capped at 15 minutes and closed after 5 minutes without data. The only EU region is Amsterdam. The remote MCP server is "ready for public testing" (preview, since 2026-04-17). Private GHCR pulls need the Pro plan.
- **Fly.io.** The Warsaw region was removed in the 2025 region consolidation. Managed Postgres runs only in ams, fra and lhr, starts at $38/month, and its docs name only PG 16. Unmanaged `fly postgres` is officially unsupported. `fly mcp` is experimental. `--image` pulls only from public registries.
- **Cloudflare Containers.** GA since 2026-04-13. Workers and Pages can't run a JVM. Traffic has to pass through a Worker plus a Durable Object shim, and containers sleep after 10 minutes by default. There is no Cloudflare-native Postgres; PlanetScale is billed through Cloudflare but isn't co-located. With one instance, every deploy is downtime, and the docs don't say how to roll back a container image.
- **Vercel: dropped.** The JVM runs only through Container Images, which have a beta badge in the docs as of 2026-07-07. There is no always-on process: requests are capped at 300–800 s, instances scale to zero after 5 minutes, and WebSockets are in public beta for Node, Bun and Python only.
- **Netlify: dropped.** There is no Java or container runtime, and synchronous functions have a hard 60 s limit.

### Shortlisted Platforms

#### 1. Coolify on the existing OVH server (Chosen)

It costs nothing extra on hardware that is already paid for and far oversized for this app (6 cores, 31 GB RAM). Postgres 18 runs on the same Docker network. It matches the developer's existing skills and the `deployment_target: self-host` / `ci_default_flow: auto-deploy-on-merge` in `tech-stack.md`. For agent access it has more than expected: an official CLI, an API with rolling updates that keep the old container when the healthcheck fails, a built-in MCP server and markdown docs. The main gap is operations: security, patching and monitoring of a public server are the developer's job.

#### 2. Render (Top score, runner-up)

It scored highest because it passes all five criteria and needs about an hour of setup instead of the plan's roughly 30 manual steps. That matters with the PRD's 3-week after-hours budget. It lost to Coolify on the developer's own weighting: it costs about $13–31/month against about $0, backups stay only on Render, and API keys can't be scoped. **This is the fallback if the self-hosted setup overruns its time budget or the server becomes unavailable.**

#### 3. Railway

It is the cheapest managed option, but the per-connection SSE cap, the dashboard-only rollback and the experimental Postgres backups make it weaker than Render for this app.

## Anti-Bias Cross-Check: Coolify on OVH

A cross-check was also run on Render, which led the scoring. It found:
- the 512 MB container gets killed on a large CSV import, and upgrading doubles the cost;
- API keys are account-wide;
- backups stay only on Render (3-day PITR on Hobby);
- rolling back by tag breaks when the tag moves or the GHCR PAT expires;
- pricing changed twice in 2026.

After reading these, the developer chose Coolify. The findings below are the cross-check re-run on the chosen platform.

### Devil's Advocate — Weaknesses

1. **Coolify is the biggest attack surface, and it runs as root.** It had 11 CVEs disclosed in January 2026, five of them root RCE rated CVSS 10, and about 60 more advisories in June–July 2026, including websocket and terminal RCEs and a cross-team IDOR. Coolify reaches the host as `root` over SSH, so a Coolify compromise gives full control of the server, the database and the backup keys. Keeping the dashboard behind Cloudflare Access reduces the exposure, but security still depends on how fast Coolify patches.
2. **One bare-metal machine is a single point of failure, with no snapshots.** If an HDD in the RAID1 dies, a motherboard fails or OVH does maintenance, the app is down until someone rebuilds by hand. Nightly dumps mean up to 24 h of stock movements can be lost, and for a warehouse app whose guardrails are about stock correctness, that data is the product.
3. **Setup competes with product work.** The plan has about 30 human subphases, roughly 6–8 hours, before the first feature ships, inside a 3-week after-hours MVP. After that, the ongoing work (OS upgrades, Docker, cloudflared, Coolify) never shows up in the backlog.
4. **The platform stack is at the edge of what Coolify supports.** Its docs list Ubuntu up to 24.04. On 26.04 with Docker 29 there is an open issue (#11089, "Coolify doesn't detect Docker 29.x"). Auto-update is on by default, so an upgrade can break things overnight: v4.3 turned the deploy call from `GET` into `POST` and returns 405 for the old form.
5. **Agent access splits into two worlds with different boundaries.** The app layer goes through the Coolify API, CLI or MCP, but those tokens are team-wide, and CI needs `write` + `deploy`. The host layer (ufw, sshd, cloudflared, Docker) is SSH plus passwordless sudo, with nothing structured or read-only in between.

### Pre-Mortem — How This Could Fail

The team kept the OVH server because it was already paid for. Setup ate the first weekend and a half. Coolify didn't detect Docker 29 on Ubuntu 26.04, so they pinned an older Docker and wrote "revisit later" in the runbook; nobody did. The app shipped and ran quietly for three months. Then Coolify auto-updated overnight to a release that changed an API contract, and Monday's merge failed at the deploy step. The fix took five minutes, but nobody noticed for two days, because CI failures went to a personal inbox nobody checked. In month five one RAID1 disk failed. `mdadm` had no mail configured, so the degraded array went unnoticed until the second disk started throwing errors three weeks later. The restore from R2 worked, but the newest dump was twelve days old: the R2 token had expired, and the Coolify notification channel had never been set up. The plant recounted the warehouse by hand. The wrong assumption was never "self-hosting is cheap". It was that "set up once" meant "watched forever".

### Unknown Unknowns

- **Auto-update can break CI without any code change.** The v4.3 switch from `GET /api/v1/deploy` to `POST` is the precedent. Have CI check `GET /api/v1/version` before deploying, and review Coolify release notes weekly or turn auto-update off and patch deliberately.
- **Spring sees plain HTTP behind tunnel → Traefik.** Coolify's Traefik doesn't trust forwarded headers, so the app receives `X-Forwarded-Proto: http`. Without Traefik `forwardedHeaders.trustedIPs`, or Coolify's Full-TLS tunnel setup, Spring Security redirects to `http://…/login` even with `server.forward-headers-strategy=framework`.
- **Nothing watches the host by default.** That covers RAID degradation, SMART errors, a disk filling with Docker images and build cache, failed backup jobs, and expiring R2/GHCR/Cloudflare tokens. Coolify notifications and `mdadm` mail both have to be configured explicitly.
- **Agent tooling has to get through Cloudflare Access.** `coolify-cli` and MCP clients must reach `coolify.<domain>` through Access. Whether they can send `CF-Access-Client-Id`/`-Secret` headers was not verified. The fallback is to point them at `http://localhost:8000` through `ssh -L 8000:localhost:8000 stockahead`.
- **Every deploy drops SSE streams and in-memory sessions.** This happens even with rolling updates, because the old container stops. Technicians mid-picking get logged out unless the app uses Spring Session JDBC, and the SSE client must reload full state when it reconnects instead of replaying events. cloudflared also doesn't pass client disconnects to the origin, so each `SseEmitter` needs a finite timeout.

## Verified corrections to the deployment plan

`context/changes/deployment/deployment-plan.md` was fact-checked on 2026-09-19. Apply these before executing it.

| # | Severity | Plan item | Correction |
|---|---|---|---|
| 1 | Blocker | D5, 7.1 | The deploy call is **`POST /api/v1/deploy?uuid=…`** (a `GET` returns 405 since v4.3). The tag is updated with `PATCH /api/v1/applications/{uuid}` `{"docker_registry_image_tag": "sha-…"}`. Poll `GET /api/v1/deployments/{deployment_uuid}` until `finished` / `failed` / `cancelled-by-user`. |
| 2 | High | S6, 4.2, final check | Require **Coolify ≥ v4.3.23** (latest stable, 2026-09-18), not v4.2.0, which is a pre-release. v4 left beta on 2026-04-27. Auto-update is on by default (Settings → Updates). |
| 3 | High | D2, 4.1–4.3 | Open issue #11089: Coolify doesn't detect Docker 29.x on 26.04. Validate the `localhost` server right after install; the fallback is to pin Docker to a version Coolify detects. Docker's `resolute` apt suite exists (docker-ce up to 29.8.1). |
| 4 | High | 1.1, 6.3 | Set Traefik `forwardedHeaders.trustedIPs` (loopback plus Docker ranges) in the Coolify proxy config, or use the Full-TLS tunnel guide. Add a check to 6.3: after login, the `Location` header uses `https://`. |
| 5 | Medium | 7.2 | Coolify API tokens are team-wide. CI needs `write` + `deploy` with an expiry date. Turn on API access under Settings → Configuration → Advanced. |
| 6 | Medium | 5.1, 5.5 | The OVH Edge Network Firewall is stateless and IPv4-only, so set cloudflared to `protocol: http2`, or allow UDP replies from port 7844, plus DNS (53) and NTP (123). The IPv6 DROP in `after6.rules` is the only IPv6 protection. |
| 7 | Medium | 9.1 | SSE rules to apply: Cloudflare's timeout is 125 s (some pages still say 100 s); keep the 30 s heartbeat. Send `Content-Type: text/event-stream` and `Cache-Control: no-cache, no-transform`. Turn off Coolify/Traefik gzip for the app, because Traefik's compress middleware doesn't exclude `text/event-stream`. Give every `SseEmitter` a finite timeout. `X-Accel-Buffering` has no effect here. |
| 8 | Medium | 8.1–8.3 | A green "Validate connection" doesn't prove R2 uploads work (#9792 open). Only a real dump in R2 counts, and 8.2 already requires one. The instance backup doesn't contain `APP_KEY` (already handled in 4.3). |
| 9 | Low | 1.2, 6.3 | Boot 4 turns liveness/readiness probes on by default, so point the Coolify healthcheck at `/actuator/health/readiness` with a start period long enough for the JVM plus Flyway. Pin the base image (`eclipse-temurin:21-jre` now means Ubuntu 26.04), e.g. `21-jre-resolute`. Both `curl` and `wget` are in the image. |
| 10 | Low | 6.1 | Coolify has supported Postgres 18 (mounted at `/var/lib/postgresql`) since beta.463. Don't turn on Coolify-generated SSL for PG18 (#8601). |
| 11 | Low | 3.3 (S2) | Before relying on `AllowUsers … root@10.0.0.0/8 root@172.16.0.0/12`, confirm Coolify's real source IP with `sudo journalctl -u ssh`. Keep the first session open. |
| 12 | Low | 5.3 | Re-running `install.sh` to apply `PUSHER_*` also upgrades Coolify to the latest version. |

**Confirmed as written:**
- **Coolify:** the tunnel routes, their order and the `PUSHER_HOST` / `PUSHER_PORT=443` recipe; an `https://` app domain with Redirect HTTP→HTTPS disabled (the official all-resources guide); rolling updates for Docker Image apps, which keep the old container when the healthcheck fails.
- **Docker:** DOCKER-USER rules through `ufw` `after.rules` (iptables is still Docker 29's default; do **not** turn on the experimental nftables backend, because it has no DOCKER-USER chain).
- **Build and registry:** the `java -Djarmode=tools … extract --layers --launcher` form with `org.springframework.boot.loader.launch.JarLauncher`; a classic PAT with `read:packages` for GHCR (fine-grained PATs aren't supported).
- **Cloudflare:** `CF-Access-Client-Id` / `CF-Access-Client-Secret` service tokens; `cloudflared access ssh --hostname %h`; `winget install Cloudflare.cloudflared`; Zero Trust free up to 50 seats.

## Operational Story

- **Preview deploys**: none in the MVP. Coolify PR previews turn off rolling updates and would need a wildcard tunnel hostname, and the plan deliberately uses explicit hostnames. PRs run only the `verify` job (`./mvnw -B verify` against Testcontainers Postgres 18). Every merge to `main` deploys to `https://test.<domain>`, which acts as the test environment.
- **Secrets**:
  - App env vars (`SPRING_DATASOURCE_*`, `STOCKAHEAD_SETUP_TOKEN`) are stored in Coolify, encrypted with `APP_KEY`.
  - GitHub environment `test` holds `COOLIFY_TOKEN` (`write` + `deploy`, with an expiry), `CF_ACCESS_CLIENT_ID` and `CF_ACCESS_CLIENT_SECRET`.
  - On the server, root's Docker config holds a classic GHCR PAT (`read:packages`), and Coolify's S3 storage holds the R2 token (one bucket, read/write).
  - `APP_KEY` is kept offline in a password manager.
  - To rotate a secret: regenerate it in Coolify or Cloudflare, update the GitHub secret, then re-run the workflow.
- **Rollback**: run the `deploy` job by `workflow_dispatch` with an earlier `sha-XXXXXXX` tag. It PATCHes the tag, POSTs the deploy and polls until done. Alternatively run `coolify app rollback run` or use Coolify → Deployments → Rollback. It takes about 1–3 minutes because the image is already on the host. Flyway migrations do **not** roll back: migrations must be expand/contract (S5), and you click "Backup now" before merging a risky one.
- **Approval**:
  - *Human only:* merging to `main` (branch protection), Coolify upgrades if auto-update is turned off, rotating `APP_KEY` or the Cloudflare/Coolify tokens, restoring or dropping the database, any `ufw` / `sshd` / cloudflared / OVH firewall change, and deleting Coolify resources.
  - *Agent, unattended:* reading logs, read-only SSH checks, preparing PRs, and running a deploy of an **existing** tag to `test` through the CI workflow.
- **Logs** (read-only):
  - CI: `gh run view <run-id> --log-failed`.
  - App: `coolify app logs <app-uuid>` and `coolify app deployments logs <app-uuid> --follow` (coolify-cli ≥ 1.8.0, `read`-scoped token), or the built-in MCP at `https://coolify.<domain>/mcp` with a `read` token.
  - Host: `ssh stockahead 'sudo journalctl -u cloudflared -n 200'`, `ssh stockahead 'sudo docker ps && sudo docker logs --tail 200 <container>'`.

## Risk Register

| Risk | Source | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| A Coolify vulnerability gives an attacker root on the host (and the DB and backup keys) | Devil's advocate | M | H | Dashboard only behind Cloudflare Access; Coolify ≥ v4.3.23 with auto-update on; registration off; 2FA; watch github.com/coollabsio/coolify/security/advisories monthly |
| Coolify auto-update changes API or behaviour and breaks CI deploys | Unknown unknowns | M | M | CI calls `GET /api/v1/version` and fails loudly on an unexpected major/minor; turn on GitHub email or Slack notifications for failed workflows |
| Deploy step uses `GET /api/v1/deploy` and gets 405 | Research finding | H (as currently planned) | M | Use `POST` (correction 1) |
| Coolify doesn't detect Docker 29 on Ubuntu 26.04 (#11089) | Research finding | M | M | Validate the `localhost` server right after install; pin a detected Docker version; note it in the runbook with a revisit date |
| RAID1 disk failure goes unnoticed until the second disk fails | Pre-mortem | L | H | Set up `mdadm` monitoring mail and `smartd`; Coolify notifications (email) for failed deployments and backups |
| Backups silently stop (expired R2 token, R2 upload bug #9792) | Pre-mortem | M | H | Coolify backup-failure notifications; monthly restore drill (plan 8.4); fallback to Backblaze B2; token expiry dates in the runbook |
| Up to 24 h of stock movements lost on server loss (RPO = nightly dump) | Devil's advocate | L | H | Accept for MVP; manual "Backup now" before risky migrations; revisit to a 6-hourly schedule after launch |
| Spring Security redirects to `http://` behind tunnel → Traefik | Unknown unknowns | H | M | Traefik `forwardedHeaders.trustedIPs` or Full-TLS tunnel (correction 4); check `Location` header in 6.3 |
| Every deploy logs users out and drops SSE streams | Unknown unknowns | H | M | Spring Session JDBC (a Flyway migration for the session tables); SSE client reloads full state when it reconnects; finite `SseEmitter` timeout |
| SSE events are held back or connections cut (Cloudflare 125 s, Traefik gzip, cloudflared buffering) | Research finding | M | M | 30 s heartbeat, `text/event-stream`, `no-cache, no-transform`, gzip off for the app (correction 7); final check: a stream stays open for more than 5 min |
| Stateless OVH firewall breaks the tunnel's QUIC or DNS/NTP replies | Research finding | M | H | cloudflared `protocol: http2`, or UDP accept rules, before turning the OVH firewall on (correction 6) |
| Team-wide Coolify CI token (`write` + `deploy`) leaks from GitHub | Devil's advocate | L | H | Put it in a GitHub **environment** secret with an expiry; the Cloudflare Access service token is required as well; rotate on any suspicion |
| Agent CLI/MCP can't pass Cloudflare Access headers | Unknown unknowns | M | L | Use `ssh -L 8000:localhost:8000 stockahead` and point coolify-cli at `http://localhost:8000` |
| Self-hosted setup eats the 3-week after-hours budget | Devil's advocate | M | M | Time-box phases 0–7 to one weekend; if it overruns, move to Render (runner-up) with the same Dockerfile and image |
| No snapshots on OVH bare metal; a bad host change needs a rebuild | Devil's advocate | L | M | Save `/etc/ssh/sshd_config.d/*` and `/data/coolify/source/.env` before changes; OVH KVM/IPMI and rescue mode for break-glass access; runbook restore procedure |

## Getting Started

1. **Fix the plan first.** Apply the 12 corrections above to `context/changes/deployment/deployment-plan.md`: the `POST` deploy call, Coolify ≥ v4.3.23, Traefik trusted forwarded headers, token scopes, SSE rules, and cloudflared `protocol: http2`.
2. **Make the app ready for production, locally (plan phase 1).** Add `spring-boot-starter-actuator`. Use the Boot 4.1 layered Dockerfile on a pinned `eclipse-temurin:21-jre-resolute`:
   - build: `java -Djarmode=tools -jar app.jar extract --layers --launcher`;
   - run: `org.springframework.boot.loader.launch.JarLauncher`.
   Check: `docker build -t stockahead:local .`, then `curl localhost:8080/actuator/health/readiness` against `postgres:18`.
3. **Install Docker, then Coolify, on the server.** Install Docker CE from `download.docker.com/linux/ubuntu` suite `resolute` and apply the DOCKER-USER rules before Coolify. Then run `curl -fsSL https://cdn.coollabs.io/coolify/install.sh | sudo bash`, and **validate the `localhost` server right away** (#11089) before doing anything else.
4. **Wire CI deploys to the current Coolify API.** In the `deploy` job, every call sends `Authorization: Bearer $COOLIFY_TOKEN`, `CF-Access-Client-Id` and `CF-Access-Client-Secret`:
   - `curl -X PATCH "$COOLIFY_URL/api/v1/applications/$COOLIFY_APP_UUID" -d '{"docker_registry_image_tag":"sha-…"}'`
   - `curl -X POST "$COOLIFY_URL/api/v1/deploy?uuid=$COOLIFY_APP_UUID"`
   - then poll `GET /api/v1/deployments/{deployment_uuid}` until the status is `finished` or `failed`.
5. **Give the agent read-only eyes.** Install `coolify-cli` ≥ 1.8.0 locally with a `read`-only token, check that it gets through Cloudflare Access (fallback: SSH port-forward), and set up Coolify email notifications for failed deploys and backups.

## Out of Scope

The following were not evaluated in this research:
- Docker image configuration
- CI/CD pipeline setup
- Production-scale architecture (multi-region, HA, DR)
