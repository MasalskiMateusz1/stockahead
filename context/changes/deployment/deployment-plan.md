# Plan: Stockahead deployment and test-server setup (Coolify)

## Context

`context/foundation/tech-stack.md` calls for self-hosting, GitHub Actions CI and auto-deploy on merge. Right now none of that exists:
- the folder is not a git repo,
- there is no Dockerfile, no CI and no production config,
- `application.properties` only sets the app name,
- tests use `postgres:latest`.

Goal: every merge to `main` deploys to your dedicated server through **Coolify** at `https://test.regavio.com`. Nothing on the server is reachable from the internet except through Cloudflare, backups go off-site every night, and a runbook lets anyone rebuild the setup.

Tick the `[ ]` boxes as you go: one per subphase, one "Phase complete" per phase.

The work is split into **phases**, and each ends in a state you can check. A phase contains **subphases of at most 15 minutes**, each marked:
- **[AI]**: I do it here (repo, or read-only server checks).
- **[Human]**: you do it (server, Cloudflare, Coolify or GitHub dashboards).

Every subphase ends with a **✅ Check**. You can stop after any subphase and nothing is left half-configured.

## Server facts (checked 2026-09-19 over SSH, read-only)

| | Value |
|---|---|
| Machine | OVH **bare-metal dedicated server**, Frankfurt, IP `145.239.3.226` |
| OS | Ubuntu 26.04.1 LTS, kernel 7.0, cgroup v2 |
| CPU / RAM | Xeon E-2236, 6 cores / 12 threads, 31 GB RAM, 1 GB swap |
| Disk | 2 × 4 TB HDD in software RAID1 (`/dev/md3`, ext4), about 3.4 TB free |
| Access | user `ubuntu` with passwordless sudo; SSH on port **7896**, keys only; `PermitRootLogin prohibit-password` |
| State | fresh: no Docker, ufw inactive, unattended-upgrades on, timezone UTC |
| Network | public interface `enp1s0f0` |

## Accepted decisions

| # | Area | Decision |
|---|---|---|
| D1 | Machine | Existing OVH dedicated server (above) |
| D2 | OS | Ubuntu 26.04 LTS (already installed). Coolify's docs list LTS releases only up to 24.04. The install script accepts 26.04, but issue #11089 (open) reports that Coolify doesn't detect Docker 29.x there, so the `localhost` server is validated right after install (4.2). |
| D3 | Deploy tool | **Coolify** (self-hosted, free), chosen over 11 other options: highest score on ease, support and price. See "Tool comparison" at the end. |
| D4 | Public access | Cloudflare Tunnel; the domain is already on Cloudflare |
| D5 | Build and deploy | GitHub Actions runs tests, builds the Dockerfile image and pushes it to GHCR. It then calls the Coolify API: `PATCH /api/v1/applications/{uuid}` sets the tag, and `POST /api/v1/deploy?uuid=…` starts the deploy. Since v4.3 a `GET` returns 405. The Coolify app has type "Docker Image". The image CI tested is exactly the one deployed. |
| D6 | Image | Multi-stage Dockerfile (Temurin 21, Spring Boot layers) |
| D7 | Database | Coolify-managed **PostgreSQL 18** resource on the internal network, never public |
| D8 | Backups | Coolify's scheduled Postgres backups to **Cloudflare R2**, plus Coolify's own instance backup |
| D9 | Secrets | App env vars live in Coolify (encrypted in its DB). GitHub holds only the Coolify API token and the Access service token. `deploy/.env.example` documents the variable names. |
| D10 | First manager | First-run setup screen |
| D11 | Admin user | Reuse `ubuntu` (sudo). There is **no separate `deploy` user**: Coolify needs Docker running as root, and CI deploys through the Coolify API instead of SSH. |
| D12 | Coolify dashboard | `coolify.regavio.com` through the tunnel **behind Cloudflare Access** (email one-time code). CI reaches the API with an Access service token. Coolify had several serious security flaws in 2026 that let a logged-in user take over the server, so the dashboard is never public. |
| D13 | Tunnel | `cloudflared` as a **host systemd service** with explicit hostnames (no wildcard). SSH keeps working even if Coolify or Docker breaks. |
| D14 | App access | No Cloudflare Access on `test.regavio.com`; only the app's own Spring Security login protects it |
| D15 | GitHub plan | **GitHub Free** (chosen 2026-09-19). Private repos on Free get no branch protection, rulesets or environment secrets (the API returns 403). So PR-only merges are a habit, not a rule (2.3), and deploy secrets are repository-level (7.1, 7.2). |

**Sub-decisions that follow from the above. Tell me if you disagree with any:**
- **S1: host firewall for root Docker.** Root Docker publishes ports past ufw, so a rule in the `DOCKER-USER` chain drops new inbound connections on `enp1s0f0`. With ufw also denying all inbound, nothing answers from the internet. The OVH Network Firewall (in the OVH panel) is an optional second layer.
- **S2: root SSH only from Docker networks.** Coolify manages its own server over SSH as `root`. Root login stays key-only and is allowed only from the Docker network ranges (`AllowUsers ubuntu root@10.0.0.0/8 root@172.16.0.0/12`). Root is refused from localhost (the tunnel) and from the internet.
- **S3: `/setup` needs a one-time `STOCKAHEAD_SETUP_TOKEN`** (a Coolify env var) and disables itself once a manager exists.
- **S4: Postgres pinned to `postgres:18`** in Coolify, the local compose file and `TestcontainersConfiguration`.
- **S5: migrations must be expand/contract,** so the previous image still works on the new schema. Before merging a risky migration, click "Backup now" in Coolify.
- **S7: the container healthcheck lives in the Dockerfile,** not in Coolify. Coolify's Health Checks tab left `Config.Healthcheck` null on the running container (2026-09-21), and an image-level `HEALTHCHECK` applies wherever the image runs.
- **S6: Coolify must be at least v4.3.23** (latest stable on 2026-09-18). The 2026 flaws were fixed across several releases; v4.2.0 is only a pre-release and covers just one CVE. Auto-update stays on (it's the default). Registration is disabled and 2FA is on for the only Coolify user.

**Out of scope:** the `/setup` screen and SSE themselves. They belong to the auth and realtime features; this plan only records the rules they must follow. Until auth ships, the deployed app shows Spring Security's default login page, which is enough to verify the deploy.

## Architecture

```
GitHub ─► Actions: verify ─► image ─► ghcr.io/<gh-user>/stockahead:sha-XXXX
                                 └─► deploy: Coolify API (Bearer token + CF Access service token)

Cloudflare edge ── tunnel ──► OVH server (ufw deny-all + DOCKER-USER drop on enp1s0f0)
  test.regavio.com ───────────────► cloudflared (host systemd) ─► :80  Coolify proxy (Traefik) ─► [stockahead app :8080]
  coolify.regavio.com  [Access] ──► cloudflared ─► :8000 dashboard/API, /terminal/ws ─► :6002
  realtime.regavio.com [Access] ──► cloudflared ─► :6001 Coolify realtime
  ssh.regavio.com      [Access] ──► cloudflared ─► :7896 sshd (ubuntu only; root only from Docker nets)
                                  Coolify network: [app] ─► [postgres:18]
                                  Coolify scheduler 02:30 ─► pg_dump ─► R2; instance backup ─► R2
```

---

## Phase 0: Repository and runbook

**Done when:** the project is in a private GitHub repo and the runbook holds the exact commands and clicks for every human step.

- [x] **Phase complete**

- [x] **0.1 [AI]: Initialise git.**
  - `git init`, add `.env` and `*.dump` to `.gitignore`, first commit.
  - ✅ Check: `git status` is clean and `git log` shows one commit.
- [x] **0.2 [Human]: Create the GitHub repo.**
  - Create a private repo `stockahead` and `git push -u origin main`. I can do this with `gh` if you are logged in.
  - ✅ Check: the commit is visible on GitHub.
- [x] **0.3 [AI]: Draft the runbook** in `context/foundation/deployment.md`.
  - Decision table, then copy-pasteable commands and click paths for every [Human] subphase (keyed by subphase number), then the restore procedure.
  - ✅ Check: every [Human] subphase in this plan has a matching runbook section.

## Phase 1: Production-ready app (local only)

**Done when:** the Docker image runs locally against Postgres 18 with the prod profile and reports healthy.

- [x] **Phase complete**

- [x] **1.1 [AI]: Config and dependencies.**
  - `pom.xml`: add `spring-boot-starter-actuator`.
  - `application.properties`: `spring.jpa.hibernate.ddl-auto=validate`, `management.endpoints.web.exposure.include=health`.
  - New `application-prod.properties`: `server.forward-headers-strategy=framework`, `server.servlet.session.cookie.secure=true`.
  - The datasource comes from env vars (`SPRING_DATASOURCE_*`).
  - `TestcontainersConfiguration`: `postgres:latest` → `postgres:18` (S4).
  - ✅ Check: `mvnw.cmd verify` passes.
- [x] **1.2 [AI]: Dockerfile and `.dockerignore`.**
  - Build stage on `eclipse-temurin:21-jdk`: `./mvnw dependency:go-offline`, then `package -DskipTests`, then `java -Djarmode=tools -jar app.jar extract --layers --launcher`.
  - Runtime stage on a pinned `eclipse-temurin:21-jre-resolute` (the bare `21-jre` tag moves between Ubuntu releases): non-root user, `-XX:MaxRAMPercentage=75`, starts with `JarLauncher`. The image already ships `curl` and `wget`, which Coolify's healthcheck needs inside the container.
  - ✅ Check:
    - `docker build -t stockahead:local .` succeeds,
    - `docker run --rm --entrypoint id stockahead:local` shows a non-root uid,
    - `docker run --rm --entrypoint curl stockahead:local --version` works.
- [x] **1.3 [AI]: Local prod-like smoke test** (`deploy/compose.local.yml` + `deploy/.env.example`).
  - `postgres:18` plus `stockahead:local` with `SPRING_PROFILES_ACTIVE=prod`, on `127.0.0.1:8080`. This file is for local checks only; Coolify doesn't use it.
  - ✅ Check: `curl localhost:8080/actuator/health/readiness` returns `{"status":"UP"}` (Boot 4 turns the probes on by default), and the log shows Flyway ran.
- [x] **1.4 [Human]: Review.**
  - Read the diff for 1.1–1.3 (about 5 minutes), then I commit and push.
  - ✅ Check: the commit is on `main` on GitHub.

## Phase 2: CI builds and publishes the image (no deploy yet)

**Done when:** a push to `main` produces a tested image in GHCR, and PRs cannot merge unless tests pass.

- [x] **Phase complete**

- [x] **2.1 [AI]: `.github/workflows/ci.yml`.**
  - Job `verify` runs on PRs and on pushes to `main`: `setup-java` with Temurin 21 and a Maven cache, then `./mvnw -B verify`.
  - Job `image` runs on pushes to `main` and on `workflow_dispatch`, after `verify`: logs in to GHCR, then builds and pushes with the `gha` cache and tag `sha-<short>`.
  - ✅ Check: `actionlint`, if available, and a YAML parse.
- [x] **2.2 [Human]: First CI run.**
  - Push, or merge my PR, and watch the Actions tab. In GHCR, confirm the `stockahead` package is private and linked to the repo.
  - ✅ Check: both jobs are green and the package shows the tag `sha-XXXXXXX`.
- [x] **2.3 [Human]: Merge discipline on `main` (D15: no branch protection on GitHub Free).**
  - Never push to `main` directly; merge only through a PR whose `verify` check is green.
  - ✅ Check: a test PR shows the `verify` check running, and it passes before you merge.

## Phase 3: Server baseline

**Done when:** the server is up to date and SSH is hardened in the way Coolify needs.

- [x] **Phase complete**

- [x] **3.1 [AI]: Server inventory.** Done 2026-09-19; see "Server facts" above.
- [x] **3.2 [Human, as ubuntu]: OS basics.**
  - OVH dedicated servers have no snapshots, so save the output of `sudo cat /etc/ssh/sshd_config.d/*` somewhere before changing anything.
  - `sudo apt update && sudo apt full-upgrade`, then reboot if a new kernel was installed.
  - `sudo timedatectl set-timezone Europe/Warsaw`.
  - ✅ Check: `apt list --upgradable` is empty, and `timedatectl` shows `Europe/Warsaw`.
- [x] **3.3 [Human, as ubuntu]: SSH hardening (S2).**
  - `/etc/ssh/sshd_config.d/10-hardening.conf`:
    - `PasswordAuthentication no`,
    - `PermitRootLogin prohibit-password`,
    - `AllowUsers ubuntu root@10.0.0.0/8 root@172.16.0.0/12`.
  - Run `sudo sshd -t && sudo systemctl reload ssh`, then test in a **second** session before you close the first one.
  - ✅ Check:
    - `ssh regavio` still works,
    - `ssh -p 7896 root@145.239.3.226` is refused,
    - `ssh -o PubkeyAuthentication=no regavio` gives "Permission denied".

## Phase 4: Docker, firewall, Coolify

**Done when:** Coolify (v4.3.23 or later) runs, only you can log in, and none of its ports answer from the internet.

- [x] **Phase complete** (2026-09-21)

- [x] **4.1 [AI]: Docker and the firewall, before Coolify.** Done 2026-09-21: Docker CE **29.8.1** (iptables backend, `DOCKER-USER` present in v4 and v6), ufw active with only `7896/tcp`.
  - Install Docker CE from the official apt repo (suite `resolute`) with `docker-compose-plugin`.
  - ufw: `default deny incoming`, `default allow outgoing`, `allow 7896/tcp` (temporary, removed in 5.5), then `enable`.
  - S1: add the `DOCKER-USER` rules to `/etc/ufw/after.rules` and `after6.rules`, dropping `NEW` connections arriving on `enp1s0f0`. Then `sudo ufw reload`.
  - ✅ Check:
    - run `sudo docker run -d --rm -p 8081:80 --name fwtest nginx`,
    - `curl localhost:8081` on the server works,
    - `Test-NetConnection 145.239.3.226 -Port 8081` from your PC **fails**,
    - `sudo docker stop fwtest`.
- [x] **4.2 [AI + Human]: Install Coolify and claim it.** Done 2026-09-21: **v4.3.23**, admin registered with 2FA confirmed, registration off, `localhost` validated (`is_reachable`/`is_usable` true) with **Docker 29.8.1 detected** — issue #11089 did not apply, so no version pin was needed.
  - `curl -fsSL https://cdn.coollabs.io/coolify/install.sh | sudo bash` — run 2026-09-21, installed **v4.3.23**, all four containers healthy, sshd config untouched. It reuses the Docker you installed. If the script refuses 26.04, stop; the runbook then has the manual install.
  - From your PC, open the dashboard only through SSH port-forwarding: `ssh -L 8000:localhost:8000 regavio`, then `http://localhost:8000`.
  - Immediately register your root admin, turn on **2FA**, and **disable registration** in Settings.
  - Then do 4.3's first step (server **User** `root` and **Port** `7896`) before judging Docker detection: the installer recorded `coolify@…:22`, and no `coolify` OS user exists, so validation fails on SSH before it ever reaches Docker.
  - With that fixed, open Servers → `localhost` and click **Validate & configure**, then check that Coolify detects Docker (issue #11089: Docker 29.x isn't detected on 26.04 — this install is 29.8.1 and *was* detected on 2026-09-21). If it isn't detected, stop. The runbook's fallback pins Docker to a version Coolify detects.
  - ✅ Check:
    - Settings shows Coolify **≥ 4.3.23** (S6),
    - the `localhost` server shows Docker as detected,
    - a private window on `/register` shows registration disabled,
    - `Test-NetConnection 145.239.3.226 -Port 8000` from your PC fails.
- [x] **4.3 [AI + Human]: Coolify server settings.** Done 2026-09-21.
  - Servers → `localhost` → General: set **User** to `root` (the installer wrote `coolify`, which is not an OS user here) and the SSH **Port** to **7896**, Save, then click **Validate**.
  - Settings: confirm auto-update is on (S6; it's the default).
  - Proxy: Servers → `localhost` → Proxy → Configuration. Add `--entrypoints.http.forwardedHeaders.trustedIPs=127.0.0.1/32,10.0.0.0/8,172.16.0.0/12` to Traefik's `command`, then restart the proxy. Without it, Traefik overwrites Cloudflare's `X-Forwarded-Proto: https` with `http`, and Spring Security redirects to `http://…/login`.
  - Save `/data/coolify/source/.env`, which holds `APP_KEY`, in your password manager. Restoring Coolify is impossible without it.
  - Set **root's login shell to bash** (`sudo chsh -s /bin/bash root`). Both `ubuntu` and `root` ship with fish on this image, and Coolify sends POSIX shell strings (`VAR=…`, `if … then`, heredocs) over SSH as root — validation survives fish because it only runs simple commands, but deploys in Phase 6 would not.
  - Narrow S2: once the journal shows Coolify's root login coming from `10.x`, remove `root@172.16.0.0/12` from `AllowUsers`, then **Validate** again. That range only covers the default `docker0` bridge, which Coolify doesn't use. Don't narrow further to the `coolify` /24: apps and databases join that network by default, so it would add no isolation and would break if the network were recreated.
  - ✅ Check:
    - the `localhost` server shows validated and usable, and the proxy (Traefik) is running,
    - `sudo journalctl -u ssh | grep 'Accepted publickey for root'` shows a `10.x` source address, which confirms the S2 `AllowUsers` ranges cover Coolify,
    - `sudo sshd -T | grep -i allowusers` lists only `ubuntu` and `root@10.0.0.0/8`, and **Validate** still passes,
    - a bash-only command survives the trip: `ssh root@host … 'V=1; if [ "$V" = "1" ]; then echo ok; fi'` prints `ok`, not a fish error.

## Phase 5: Cloudflare Tunnel, Access and lockdown

**Done when:** the dashboard, realtime and SSH work only through Cloudflare, Access protects them, and the server has no open inbound ports.

- [x] **Phase complete** (2026-09-21)

- [x] **5.1 [AI + Human]: Tunnel on the host (D13).** Done 2026-09-21: cloudflared 2026.9.1 (apt, `noble` suite), all five routes live, `coolify.regavio.com/` returns 302 to `/login`, `test.regavio.com` returns Traefik's 404.
  - In Zero Trust, create the tunnel `stockahead-test`. Install `cloudflared` from `pkg.cloudflare.com` and run `sudo cloudflared service install <token>`.
  - Public hostnames, in this order:
    1. `coolify.regavio.com` path `terminal/ws` → `http://localhost:6002`
    2. `coolify.regavio.com` → `http://localhost:8000`
    3. `realtime.regavio.com` → `http://localhost:6001`
    4. `test.regavio.com` → `http://localhost:80`
    5. `ssh.regavio.com` → `ssh://localhost:7896`
  - ✅ Check: the tunnel shows **HEALTHY**, and `https://test.regavio.com` returns Coolify's proxy 404 (no app yet), which proves the routing works.
- [x] **5.2 [Human]: Cloudflare Access (D12).** Done 2026-09-21: team domain `shiny-paper-3b05.cloudflareaccess.com`; `coolify`, `realtime` and `ssh` all 302 to the Access login, `test` stays open. The `realtime` hostname first went onto the wrong zone (the domain dropdown defaults to another zone in the account), which reads exactly like Access not applying — verify the saved row, not the form.
  - Access app **"Coolify"** covering `coolify.regavio.com` and `realtime.regavio.com`, with two policies: *Allow* for your email (one-time code), and *Service Auth* for a new service token `github-deploy` (save its ID and secret).
  - Access app **"SSH"** covering `ssh.regavio.com`: *Allow* for your email.
  - ✅ Check: `https://coolify.regavio.com` in a private window shows the Cloudflare Access login, not Coolify.
- [x] **5.3 [Human]: Point Coolify at its new domain.** Done 2026-09-21: `PUSHER_HOST=realtime.regavio.com` and `PUSHER_PORT=443` are in `/data/coolify/source/.env` (original kept as `.env.bak-2026-09-21`), install script re-run at 12:31 — still 4.3.23, containers recreated — and the instance domain is saved as `https://coolify.regavio.com`. Gotcha: `ubuntu` runs fish and `/data/coolify/source` is root-only, so the runbook snippet must be wrapped in `sudo bash -c` or `cd` fails and the rest of the block runs in the home directory.
  - In `/data/coolify/source/.env`, add `PUSHER_HOST=realtime.regavio.com` and `PUSHER_PORT=443`, then re-run the install script to apply them. The runbook has the exact command. Re-running it also upgrades Coolify to the latest version, while keeping the existing `.env` values.
  - In Coolify Settings, set the instance domain to `https://coolify.regavio.com`.
  - ✅ Check:
    - after Access plus the Coolify login, the dashboard works at `https://coolify.regavio.com`,
    - opening `https://coolify.regavio.com/realtime` in a second tab shows the test notification in the first tab,
    - the web terminal to `localhost` opens.
- [x] **5.4 [Human]: SSH through the tunnel from Windows.** Done 2026-09-21: cloudflared 2026.9.1 via winget under "Program Files (x86)/cloudflared"; `ssh stockahead` logs in as `ubuntu`. A terminal opened *before* the install still has a stale PATH, so `ProxyCommand` dies with `CreateProcessW failed error:2` — open a new one or use the full path.
  - `winget install Cloudflare.cloudflared`.
  - In `~/.ssh/config`, point the `stockahead` entry to `HostName ssh.regavio.com`, `User ubuntu`, `ProxyCommand cloudflared access ssh --hostname %h`. It currently points to a `deploy` user that won't exist.
  - ✅ Check: `ssh stockahead` logs you in after the browser login.
- [x] **5.5 [Human]: Close the last inbound port.** Done 2026-09-21: `7896/tcp` removed; ports 7896, 80, 443, 8000, 6001 and 6002 all refuse from the PC, and `ssh stockahead` still works. **Correction, found the same day:** deleting the rule outright also cuts Coolify off from its own host. Its containers SSH to `host.docker.internal` (`10.0.0.1`), which enters the host INPUT chain and hits ufw deny-all, so the web terminal and every deploy fail with `connect to host host.docker.internal port 7896: Operation timed out`. The delete must be replaced by a Docker-only pair: `ufw deny in on enp1s0f0 to any port 7896 proto tcp` (plain `deny`, since `ufw insert 1` fails with `Invalid position` on an empty rule list), then `ufw allow from 10.0.0.0/8 to any port 7896 proto tcp`. The optional OVH Edge Network Firewall layer was not enabled.
  - `sudo ufw delete allow 7896/tcp`.
  - Optional second layer: the OVH Network Firewall, with deny-all inbound and established TCP kept. It is **stateless** and IPv4-only, so first switch cloudflared from QUIC (UDP 7844) to TCP with a systemd override `Environment=TUNNEL_TRANSPORT_PROTOCOL=http2`. Then either allow UDP replies from source ports 53 (DNS) and 123 (NTP), or skip this layer. The IPv6 DROP in `after6.rules` stays the only IPv6 protection.
  - Break-glass access stays through OVH's KVM/IPMI and rescue mode.
  - ✅ Check:
    - from your PC, `Test-NetConnection 145.239.3.226` fails on ports 7896, 80, 443, 8000, 6001 and 6002,
    - `ssh stockahead` still works,
    - Coolify still shows `localhost` as validated,
    - if the OVH firewall is on, the tunnel stays **HEALTHY** for at least 10 minutes.

## Phase 6: Postgres and the app in Coolify

**Done when:** the CI-built image runs in Coolify against the managed Postgres, and `https://test.regavio.com` serves the login page.

- [x] **Phase complete** (2026-09-21) — `sha-5fd5d69` running, `health=healthy`, `https://test.regavio.com/login` 200 behind a valid edge certificate.

- [x] **6.1 [Human]: Postgres resource (D7).** Done 2026-09-21: Coolify created it as `postgres:18-alpine` with the superuser **`postgres`**, not the `stockahead` user this plan assumed; internal host `z3iff6fasg0qks5r2zp6tokk`, not publicly available, SSL off.
  - Project `stockahead`, environment `test`: add a **PostgreSQL** database with image `postgres:18`, database `stockahead`, and a generated password. Leave "Make it publicly available" **off**, and leave Coolify's SSL for the database **off**: PG18 rejects Coolify's generated certificates (issue #8601), and the database is internal only.
  - ✅ Check: the resource is running and healthy, and you've copied its **internal** connection URL.
- [x] **6.2 [Human]: GHCR pull access.** Done 2026-09-21. The first PAT was pasted into the app's `STOCKAHEAD_SETUP_TOKEN` field instead, so it was revoked and reissued the same day: read the deployed container's env back (`docker exec … env`) before calling a deploy good, because Coolify's UI happily accepts a value in the wrong box.
  - Run `sudo docker login ghcr.io` on the server with a classic PAT that has only `read:packages`. Coolify pulls through root's Docker.
  - ✅ Check: `sudo docker pull ghcr.io/<gh-user>/stockahead:sha-XXXXXXX` works.
- [x] **6.3 [Human]: App resource.** Done 2026-09-21, finally on `ghcr.io/masalskimateusz1/stockahead:sha-5fd5d69` (the first deploy, `sha-265f937`, predated the image healthcheck): `/login` returns 200 behind a valid edge certificate, `/actuator/health/readiness` returns `{"status":"UP"}` unauthenticated (Boot's `ManagementWebSecurityAutoConfiguration` permits the health endpoint while no custom chain exists), and Flyway created `flyway_schema_history` against PostgreSQL 18.6 over the internal hostname. Two defects surfaced only by inspecting the running container: `SPRING_PROFILES_ACTIVE` was typed `prd`, so `application-prod.properties` never loaded and Spring answered `Location: http://…/login` — a symptom that reads exactly like the 4.3 Traefik trusted-IP fix having failed. `docker exec <app> curl -sI -H 'X-Forwarded-Proto: https' http://localhost:8080/` isolates app from proxy in one command. And Coolify's healthcheck toggle left `Config.Healthcheck` null, so the check moved into the Dockerfile (S7). Third trap: **General and Environment Variables save separately, and Deploy uses the saved values** - a tag bump plus an env edit were typed, Deploy started a genuinely new container, and it came up on the old image with the old env, with no error anywhere. Reload the page and confirm the field survived before clicking Deploy. A longer as-built explainer lives outside the repo, next to the Cloudflare one in the user's `Documents\coolify` folder.
  - Add an application of type **Docker Image** with `ghcr.io/<gh-user>/stockahead` and tag `sha-XXXXXXX`.
    - Exposed port `8080`, domain `https://test.regavio.com`, **Redirect HTTP→HTTPS disabled** (the tunnel talks plain HTTP to the proxy).
    - No port mappings, because Coolify's rolling updates need none.
  - Env vars:
    - `SPRING_PROFILES_ACTIVE=prod`,
    - `SPRING_DATASOURCE_URL=jdbc:postgresql://<pg-internal-host>:5432/stockahead`, plus `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`,
    - `STOCKAHEAD_SETUP_TOKEN` from `openssl rand -hex 24`.
  - Healthcheck: baked into the image as a Dockerfile `HEALTHCHECK` (S7), with a start period long enough for the JVM plus Flyway (60 s). Coolify's own Health Checks toggle stays off. Then **Deploy**.
  - Leave Coolify's **gzip compression off** for this app. Traefik's compress middleware doesn't exclude `text/event-stream` and would hold back SSE events.
  - ✅ Check:
    - `https://test.regavio.com` serves the login page with a valid certificate,
    - `curl -sI -H 'Accept: text/html' https://test.regavio.com/` shows a `Location:` starting with `https://`, which proves the 4.3 trusted-headers change works. Without that `Accept` header, Spring Security answers `401` with HTTP Basic instead of redirecting,
    - the app logs show Flyway ran,
    - Coolify shows the container as healthy.

## Phase 7: Auto-deploy on merge

**Done when:** merging a PR deploys by itself, and a broken image does not leave the site down. The original wording was "a broken image never replaces a healthy one"; 7.4 proved that false for this setup, so the guarantee now comes from the deploy job verifying the site and rolling back, not from Coolify.

- [x] **Phase complete** (2026-09-21) — merging deploys by itself (7.3), and a broken image now costs about two minutes of downtime that CI recovers from unattended (7.5) instead of staying down until someone notices. Two things stay open on purpose: whether Coolify's Health Checks can gate the swap and remove the outage altogether (7.5), and the gzip middleware that 7.4 found on this app's router (6.3 says off; it must be off before SSE ships). `drill/broken-start` and its `sha-d331d11` image are kept, not deleted, because the Health Checks experiment needs them.

- [x] **7.1 [AI]: Add a `deploy` job to `ci.yml`.** Done 2026-09-21. Dispatch has two modes: `gh workflow run ci.yml --ref <branch>` with no `tag` builds only (deploy is skipped), and `-f tag=sha-XXXXXXX` skips `verify` and `image` and deploys that existing tag. The job refuses any tag that isn't `^sha-[0-9a-f]{7}$` before it reaches a URL or a JSON body, and treats every non-2xx as fatal — Access answers a blocked call with `302`, which `curl -f` would pass through as success. On a failed deploy it prints the last 120 lines of Coolify's deployment log. Checked with a YAML parse and `bash -n` over every `run` block; `actionlint` isn't installed here and Docker Desktop was down, so it wasn't run.
  - Runs after `image` on pushes to `main`. Also runs from `workflow_dispatch` with a `tag` input, for manual rollback to any earlier tag.
  - Uses `concurrency: deploy-test`. There is no `environment:` (D15), so secrets are read at repository level.
  - Sets the image tag with `PATCH /api/v1/applications/$COOLIFY_APP_UUID` and body `{"docker_registry_image_tag":"sha-…"}`.
  - Starts the deploy with **`POST /api/v1/deploy?uuid=$COOLIFY_APP_UUID`** (a `GET` returns 405 since v4.3) and reads `deployments[0].deployment_uuid` from the response.
  - Polls `GET /api/v1/deployments/{deployment_uuid}` until `status` is `finished` (success) or `failed` / `cancelled-by-user` (fail the job).
  - Every call sends `Authorization: Bearer $COOLIFY_TOKEN`, `CF-Access-Client-Id` and `CF-Access-Client-Secret`.
  - ✅ Check: the workflow parses.
- [x] **7.2 [Human]: Tokens and GitHub environment.** Done 2026-09-21, after five failed deploys, each with a different cause. In order:
  1. **Merged before the secrets existed.** The merge ran at 12:26 and the secrets landed at 12:30-12:39, so every variable arrived empty. Finish 7.2 *before* 7.3.
  2. **Cloudflare Bot Fight Mode challenged the runner.** The WAF/bot layer runs **before** Access, so the `github-deploy` service token is never evaluated: the job got HTTP 403 carrying the `Just a moment...` interstitial. GitHub-hosted runners come from Azure datacenter IPs, which is exactly what Bot Fight Mode targets, and the same request from a residential IP was untouched. On the Free plan it is zone-wide and WAF *Skip* rules do not exempt it (only Super Bot Fight Mode, Pro and up, is configurable), so it was turned off: Security → Bots → Bot Fight Mode. Access still guards `coolify`/`realtime`/`ssh`, and `test` has the app's own login (D14).
  3. **`COOLIFY_APP_UUID` held the project UUID.** A Coolify app URL carries three identically shaped 24-character UUIDs - project, environment, application - and Coolify answers a wrong one with `404 {"message":"Application not found"}`, which reads like a permissions problem. `GET /api/v1/applications` is the only reliable source; the app is `e1wcptzlkub4bjzithvmqagi`, the project `2jiwnumabokbiuqrfpugbda8`.
  4. **The token lacked `deploy`.** `PATCH` succeeded and `POST /api/v1/deploy` returned `403 {"message":"Missing required permissions: deploy"}`. Coolify tokens are immutable, so this means a new token and a new secret. Coolify uses Laravel Sanctum, so the value is `<id>|<secret>` - the digits and the pipe are part of the token.
  5. **A transient edge reset.** `curl: (35) Recv failure: Connection reset by peer` on the `PATCH`, 0.3 s in. It did not reproduce on the next run. `GET` and `PATCH` now retry three times; `POST /deploy` deliberately does not, because a lost response would queue the deployment twice.
  - In Coolify, turn on Settings → Configuration → Advanced → **API Access**. Then create an API token with **`read`** (to poll the deployment), **`write`** (to set the tag) and **`deploy`**, with an expiry date. Not `read:sensitive`, which would expose the database password and `STOCKAHEAD_SETUP_TOKEN` through the API, and not `root`. Tokens are team-wide, not per app, so the Cloudflare Access service token is the second lock.
  - Add these at **repository** level (D15):
    - secrets `COOLIFY_TOKEN`, `CF_ACCESS_CLIENT_ID` and `CF_ACCESS_CLIENT_SECRET`,
    - variables `COOLIFY_URL=https://coolify.regavio.com` and `COOLIFY_APP_UUID`.
  - ✅ Check: from your PC, `curl` to `https://coolify.regavio.com/api/v1/version` with the three headers returns the version. Without the Access headers it gets blocked.
- [x] **7.3 [Human]: First auto-deploy.** Done 2026-09-21: merging PR #8 ran `verify` → `image` → `deploy` green in 1 m 37 s (run 35609393294), deploying `sha-e613c79`. The tag is the **merge** commit's SHA, which only exists once the merge happens. The earlier attempt on PR #7 reached a running deploy only by re-running the failed job, which is why this one was repeated properly from a merge.
  - Merge a trivial PR that I prepare.
  - ✅ Check: `verify` → `image` → `deploy` are all green, and Coolify shows the new `sha-` tag running.
- [x] **7.4 [AI + Human]: Failed-deploy drill.** Done 2026-09-21, and **the drill failed the way that matters**: the protection this phase assumed does not exist.
  - `drill/broken-start` built as `sha-d331d11`. `verify` passed, as designed — tests never load the prod profile.
  - Deploying it took the site **down for 65 seconds** (16:07:26–16:08:31 local: one `502`, then `404` until the good tag was put back by hand). Coolify stopped the healthy container, started the broken one, and when that died Traefik had no backend at all.
  - **The deploy job went green in 25 s.** Coolify reported the deployment `finished` (`application_deployment_queues` row 9), because it calls a deployment finished once the container is *created*, not once it is *healthy*. Polling the Coolify API therefore proves nothing about the app.
  - Cause: the compose Coolify generates for this app has **no `healthcheck:` block**, because Coolify's own Health Checks toggle is off (S7). The image's `HEALTHCHECK` still runs — `docker ps` shows `(healthy)` on the good container — but Docker's health status is not what Coolify's deploy waits on. There is no side-by-side rollout here: one container, stopped and replaced.
  - Fixed in the deploy job, not in Coolify (7.5): after the deployment reports `finished`, CI polls `APP_HEALTH_URL` for up to 3 minutes, and if the site never returns `200` it restores the tag Coolify had stored **before** the `PATCH` and fails the job. Recovery stops depending on someone watching.
  - Also found: the Traefik labels apply `traefik.http.middlewares.gzip.compress=true` to this app's router, so **gzip is on** although 6.3 says to leave it off. Harmless today, but it will hold back SSE events (`text/event-stream` is not excluded from the compress middleware). Turn it off before the realtime feature ships.
  - **[AI]** Prepare the branch `drill/broken-start`, with a prod property that crashes startup.
  - **[Human]** Build that branch's image via `workflow_dispatch`, then run `deploy` with that tag. Afterwards, redeploy the good tag and delete the branch.
  - ✅ Check (**not met** — this is what the drill found): the deploy job fails, the old container keeps serving because the rolling update aborts on the failed healthcheck, and `https://test.regavio.com` stays up the whole time. What actually happened: green job, replaced container, 65 s of downtime.
- [x] **7.5 [AI + Human]: Re-run the drill against the guard.** Done 2026-09-21. `sha-d331d11` was dispatched again against `main` carrying the guard: the deploy job **failed** after 3 m 49 s, logged 21 consecutive `404`s from `APP_HEALTH_URL`, restored `sha-65c5da3` on its own, saw `200`, and exited 1 with a warning naming both tags. No human touched anything.
  - **The outage got longer, not shorter: 3 m 26 s** (16:19:52–16:23:18) against 65 s when a human was watching. The guard recovers unattended, but only after waiting out the health window, and that window is pure downtime when the image is broken. It cannot simply be shortened to nothing: a *healthy* rollout also serves `404` for a few seconds while Traefik has no backend, so the window has to outlast a legitimate start. Cut from 180 s to 120 s, about four times the observed healthy start.
  - What the guard is and is not: it converts "down until somebody notices" into "down for about two minutes, then restored". **It does not prevent the outage.** Only gating the swap can do that, which is the Coolify Health Checks question below, still open.
  - **[AI]** The deploy job now verifies the site and rolls back; `APP_HEALTH_URL` is a repository variable.
  - **[Human]** Merge the guard, then dispatch `sha-d331d11` again and watch `https://test.regavio.com` from a second window. Afterwards delete `drill/broken-start` and the drill image version in GHCR.
  - Worth trying separately: turn Coolify's **Health Checks** on for the app (path `/actuator/health/readiness`, port 8080, start period ≥ 60 s) and re-run the drill again. If Coolify then gates the swap, the outage shrinks to nothing instead of being recovered from; if `Config.Healthcheck` stays null as in 6.3, the CI guard is the whole answer and S7 should say so.
  - ✅ Check: the deploy job **fails**, the job log shows the rollback to the previous tag, and the site is serving again without anyone touching it. Record the outage length.

## Phase 8: Backups

**Done when:** a Postgres dump and a Coolify instance backup reach R2 every night, and you have restored one.

Pre-flight, checked 2026-09-21 over SSH (read-only): `pg_dump` in the database container is **18.6**, the same major as the server, so Coolify's scheduled dump won't hit a version mismatch; `/` has **3.4 TB free**, so 7 local dumps cost nothing. The database superuser is **`postgres`** (6.1), not `stockahead` — every `psql` / `pg_restore` in this phase uses `-U postgres`.

- [ ] **Phase complete**

- [ ] **8.1 [Human]: R2 bucket and Coolify storage.**
  - Create the R2 bucket `stockahead-backups` and an API token with *Object Read & Write* on that bucket only.
  - In Coolify, add S3 storage: endpoint `https://<account-id>.r2.cloudflarestorage.com`, region `auto`, plus the bucket and keys. If Coolify's validation fails with R2, the runbook falls back to Backblaze B2.
  - ✅ Check: Coolify's **Validate connection** passes. This alone doesn't prove uploads work (R2 issue #9792 is still open), so only the real dump in 8.2 counts.
- [ ] **8.2 [Human]: Scheduled Postgres backups.**
  - On the Postgres resource, add a scheduled backup: cron `30 2 * * *`, S3 on, keeping 7 local and 14 in S3. Then click **Backup now**.
  - ✅ Check: a dump appears in R2, and the execution log shows success.
- [ ] **8.3 [Human]: Coolify instance backup.**
  - Settings → Backup: daily to the same S3 storage. `APP_KEY` is already saved in your password manager (4.3).
  - ✅ Check: an instance-backup file appears in R2.
- [ ] **8.4 [Human]: Restore drill.**
  - Download the latest dump from R2 and `pg_restore` it into a temporary `postgres:18` container, on your PC (Docker Desktop) or on the server.
  - ✅ Check: the row count of `flyway_schema_history` matches production.

## Phase 9: Documentation and agent context

**Done when:** the repo explains the deployment to both humans and agents.

- [ ] **Phase complete**

- [ ] **9.1 [AI]: Finalise docs.**
  - Bring `context/foundation/deployment.md` to the as-built state.
  - Make targeted edits to `AGENTS.md`:
    - deploys go through Coolify (D5), and config comes only from env vars,
    - migrations must be expand/contract (S5),
    - the `/setup` token rule (S3),
    - the image must keep `curl` for the healthcheck,
    - SSE behind Cloudflare and Traefik:
      - a heartbeat at least every 30 s, because Cloudflare's timeout is 125 s (some pages still say 100 s),
      - `Content-Type: text/event-stream` (cloudflared streams only this type unbuffered) and `Cache-Control: no-cache, no-transform`,
      - gzip off for the app in Coolify,
      - a finite `SseEmitter` timeout, because cloudflared doesn't pass browser disconnects to the app. `X-Accel-Buffering` has no effect here.
  - ✅ Check: `/10x-rule-review AGENTS.md` shows no FAIL, and the length stays at or under 200 lines.

## Final end-to-end check (about 15 minutes)

1. A PR runs only `verify`; its merge deploys automatically.
2. `https://test.regavio.com` loads with a valid certificate.
3. `https://coolify.regavio.com` shows the Cloudflare Access login in a private window.
4. `Test-NetConnection 145.239.3.226` shows no open ports, and `ssh stockahead` works through the tunnel.
5. Coolify is at v4.3.23 or later, auto-update is on, registration is disabled and 2FA is on.
6. The latest Postgres dump and instance backup in R2 are less than 24 h old.
7. Later, once the realtime feature ships: an SSE connection stays open for more than 5 min, and events arrive in under 2 s (the PRD NFR).

## Tool comparison (why Coolify)

Scores are 1–5: Ease = running it day to day, Support = community and maintainer, Price = 5 means free. Stars fetched on 2026-09-19.

| Option | Ease | Support | Price | Total | Needs root Docker |
|---|:-:|:-:|:-:|:-:|:-:|
| **Coolify** ★62k | 5 | 5 | 5 | **15** | yes |
| Compose + deploy.sh (rootless) | 4 | 5 | 5 | 14 | no |
| Dokploy ★37k | 5 | 4 | 5 | 14 | yes |
| Dokku ★32k | 4 | 4 | 5 | 13 | yes |
| Portainer + compose ★38k | 4 | 4 | 5 | 13 | can use rootless |
| systemd + JAR (no containers) | 3 | 5 | 5 | 13 | no |
| Podman + Quadlet ★33k | 3 | 4 | 5 | 12 | no |
| Kamal ★15k | 4 | 3 | 5 | 12 | needs `docker` group |
| CapRover ★15k | 4 | 3 | 5 | 12 | yes |
| k3s ★34k | 1 | 5 | 5 | 11 | rootless is experimental |
| Docker Swarm | 3 | 2 | 5 | 10 | yes |
| Nomad ★17k | 2 | 2 | 4 | 8 | yes |

**What was given up:** the earlier plan had a `deploy` user without root, running rootless Docker. Coolify needs Docker running as root, so that decision was replaced by D11–D13 and S1, S2 and S6. The risk is now handled at the edges instead: Coolify is never public, only the Docker networks can log in as root, all inbound ports are closed, and Coolify is kept up to date.
