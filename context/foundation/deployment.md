# Stockahead deployment runbook

How the test server is built and run. Every **[Human]** step from `context/changes/deployment/deployment-plan.md` has a section here with copy-pasteable commands and click paths, under the same number.

**Status:** draft, 2026-09-19 (plan phase 0). Phase 9.1 brings it to the as-built state. Commands marked *(from memory: verify against the linked docs before running)* were not checked against live docs while this was drafted.

## Placeholders

| Placeholder | Value |
|---|---|
| `<domain>` | your Cloudflare zone, e.g. `example.pl` (not yet chosen) |
| `<gh-user>` | `MasalskiMateusz1`. **GHCR image names must be lowercase:** `ghcr.io/masalskimateusz1/stockahead` |
| `<your-email>` | the email you log in with at Cloudflare Access and in Coolify notifications |
| `<pg-uuid>` | the internal hostname of the Coolify Postgres resource (from 6.1) |
| `<app-uuid>` | the Coolify application UUID (from 6.3; it's the last segment of the app's URL in Coolify) |
| `sha-XXXXXXX` | an image tag produced by CI (`sha-` + 7-char commit SHA) |

SSH aliases in `~/.ssh/config` on your PC:
- `regavio`: `ubuntu@145.239.3.226:7896`, direct. Used until 5.5 closes the port.
- `stockahead`: the same server through the Cloudflare Tunnel. Set up in 5.4.

Shells: server commands run in bash on the server. Commands marked **PC (PowerShell)** or **PC (Git Bash)** run on your Windows machine.

## Decisions

| # | Area | Decision |
|---|---|---|
| D1 | Machine | OVH bare-metal dedicated server, Frankfurt, `145.239.3.226` (Xeon E-2236, 31 GB RAM, 2 × 4 TB HDD RAID1) |
| D2 | OS | Ubuntu 26.04 LTS. Coolify officially lists up to 24.04, and #11089 (Docker 29.x not detected) is open, so `localhost` is validated right after install |
| D3 | Deploy tool | Coolify (self-hosted, free) |
| D4 | Public access | Cloudflare Tunnel only; the domain is on Cloudflare |
| D5 | Build and deploy | GitHub Actions: `verify` → image to GHCR → Coolify API (`PATCH` tag, `POST /api/v1/deploy`, poll). Coolify app type "Docker Image" |
| D6 | Image | Multi-stage Dockerfile, Temurin 21, Spring Boot layers, pinned `21-jre-resolute` runtime |
| D7 | Database | Coolify-managed `postgres:18`, internal network only |
| D8 | Backups | Nightly Postgres dump plus Coolify instance backup to Cloudflare R2 |
| D9 | Secrets | App env vars in Coolify; GitHub holds only the Coolify API token and the Access service token; names in `deploy/.env.example` |
| D10 | First manager | First-run `/setup` screen |
| D11 | Admin user | `ubuntu` (sudo); no `deploy` user |
| D12 | Coolify dashboard | `coolify.<domain>` behind Cloudflare Access (email one-time code); CI uses an Access service token |
| D13 | Tunnel | `cloudflared` as a host systemd service, explicit hostnames |
| D14 | App access | `test.<domain>` has no Access; Spring Security login only |
| S1 | Host firewall | ufw deny-all inbound, plus a `DOCKER-USER` drop of `NEW` connections on `enp1s0f0` |
| S2 | Root SSH | Key-only, and only from Docker networks (`10.0.0.0/8`, `172.16.0.0/12`) |
| S3 | `/setup` | Needs `STOCKAHEAD_SETUP_TOKEN`; disables itself once a manager exists |
| S4 | Postgres version | `postgres:18` everywhere (Coolify, local compose, Testcontainers) |
| S5 | Migrations | Expand/contract only; click "Backup now" before a risky one |
| S6 | Coolify version | ≥ v4.3.23, auto-update on, registration off, 2FA on |

## Ports and hostnames

| Hostname | Tunnel target | Cloudflare Access |
|---|---|---|
| `coolify.<domain>` path `terminal/ws` | `http://localhost:6002` | yes (app "Coolify") |
| `coolify.<domain>` | `http://localhost:8000` | yes (app "Coolify") |
| `realtime.<domain>` | `http://localhost:6001` | yes (app "Coolify") |
| `test.<domain>` | `http://localhost:80` (Traefik) → app `:8080` | no |
| `ssh.<domain>` | `ssh://localhost:7896` | yes (app "SSH") |

After 5.5, nothing answers on the public IP.

## Credentials inventory

Fill this in as you go. The values themselves go in your password manager, never in this file.

| Credential | Created in | Stored in | Scope | Expires |
|---|---|---|---|---|
| Coolify `APP_KEY` | 4.2 (install) | password manager | decrypts everything in Coolify's DB | never; losing it makes restores impossible |
| Coolify root admin + 2FA recovery codes | 4.2 | password manager | full Coolify | — |
| Tunnel token | 5.1 | systemd unit on the server | runs the tunnel | never (rotate by recreating the tunnel) |
| Access service token `github-deploy` | 5.2 | GitHub secrets | Coolify app in Access | _fill in_ (choose 1 year) |
| GHCR classic PAT | 6.2 | `/root/.docker/config.json` | `read:packages` | _fill in_ |
| `STOCKAHEAD_SETUP_TOKEN` | 6.3 | Coolify env var | one-time `/setup` | irrelevant once a manager exists |
| Coolify API token | 7.2 | GitHub secrets | `write` + `deploy`, team-wide | _fill in_ |
| R2 API token | 8.1 | Coolify S3 storage | Object R/W on `stockahead-backups` | _fill in_ |

Put a calendar reminder two weeks before each expiry. An expired R2 token means backups stop silently (see the risk register in `infrastructure.md`).

---

## Human steps

### 0.2 Create the GitHub repo

Done 2026-09-19: `gh repo create stockahead --private --source=. --remote=origin --push` created https://github.com/MasalskiMateusz1/stockahead.

**Your account is on GitHub Free.** For private repos, that blocks two later steps: branch protection (2.3) and environment secrets (7.2). The GitHub API answers `403 Upgrade to GitHub Pro or make this repository public`. Decide before phase 2:

| Option | Effect |
|---|---|
| **A. GitHub Pro** (about $4/month) | Plan runs as written |
| **B. Stay on Free** | 2.3 becomes a habit, not a rule: always merge through a PR. In 7.2, secrets and variables go at **repository** level instead of the `test` environment, and 7.1 drops `environment: test` (it keeps `concurrency`). Every workflow on any branch can then read the Coolify token, so it matters more that the Access service token acts as the second lock |
| C. Make the repo public | Not recommended: it publishes the full server design |

### 1.4 Review the phase 1 diff

PC (Git Bash):
```bash
cd /p/10xdevs
git status
git diff            # unstaged changes
git diff --stat
```
When you're happy, tell me to commit and push. ✅ `gh browse` opens the repo; the commit is on `main`.

### 2.2 First CI run

PC (Git Bash):
```bash
gh run list --limit 5
gh run watch                       # pick the run on main
gh run view --log-failed           # if something failed
```
GHCR: open https://github.com/MasalskiMateusz1?tab=packages → `stockahead`.
- Visibility must be **Private**. If not: Package settings → Danger Zone → Change visibility.
- The sidebar must show **Repository: stockahead**. If not: Package settings → Manage Actions access → Add repository → `stockahead` (Role: Write). Also, the image needs the `org.opencontainers.image.source` label, which `docker/metadata-action` adds.

✅ Both jobs are green and the package lists `sha-XXXXXXX`.

### 2.3 Branch protection on `main`

**Only with option A (Pro)** from 0.2. PC (Git Bash), creating a ruleset:
```bash
gh api -X POST repos/MasalskiMateusz1/stockahead/rulesets --input - <<'EOF'
{
  "name": "main",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "pull_request", "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false } },
    { "type": "required_status_checks", "parameters": {
        "strict_required_status_checks_policy": false,
        "required_status_checks": [ { "context": "verify" } ] } }
  ]
}
EOF
```
`required_approving_review_count: 0` because you can't approve your own PR. The click path is Settings → Rules → Rulesets → New branch ruleset, with the same options.

✅ Open a test PR: `verify` shows **Required**.

With **option B (Free)**, skip this step and never push to `main` directly.

### 3.2 OS basics

First, save the current SSH config **to your PC**, since the server has no snapshots. PC (Git Bash):
```bash
ssh regavio 'sudo sh -c "cat /etc/ssh/sshd_config; for f in /etc/ssh/sshd_config.d/*; do echo \"## \$f\"; cat \"\$f\"; done"' > ~/stockahead-sshd-backup-$(date +%F).txt
```
On the server (`ssh regavio`):
```bash
sudo apt update && sudo apt full-upgrade -y
[ -f /var/run/reboot-required ] && cat /var/run/reboot-required.pkgs && sudo reboot
# after the reboot, log in again
sudo timedatectl set-timezone Europe/Warsaw
```
✅ `apt list --upgradable` is empty, and `timedatectl` shows `Time zone: Europe/Warsaw`.

### 3.3 SSH hardening (S2)

Keep your **first session open** until the checks pass. On the server:
```bash
sudo tee /etc/ssh/sshd_config.d/10-hardening.conf >/dev/null <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
AllowUsers ubuntu root@10.0.0.0/8 root@172.16.0.0/12
EOF
sudo sshd -t && sudo systemctl reload ssh
sudo sshd -T | grep -Ei '^(passwordauthentication|kbdinteractiveauthentication|permitrootlogin|allowusers)'
```
sshd keeps the **first** value it reads, and files in `sshd_config.d/` are read in name order. The `10-` prefix makes this file win over e.g. `50-cloud-init.conf`.

Break-glass prep for 5.5: the OVH KVM console needs a **password**, and `ubuntu` has none. Set one now and store it in your password manager. SSH still refuses passwords, so the password works only on the console:
```bash
sudo passwd ubuntu
```
✅ From a **second** terminal on your PC (Git Bash):
```bash
ssh regavio 'echo ok'                                          # ok
ssh -p 7896 root@145.239.3.226                                 # refused
ssh -o PubkeyAuthentication=no -o PreferredAuthentications=password regavio   # Permission denied (publickey)
```

### 4.1 Docker and the firewall, before Coolify

**Docker CE** (official apt repo, suite `resolute`). On the server:
```bash
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
docker --version
```
Don't turn on Docker's experimental nftables backend: it has no `DOCKER-USER` chain.

**ufw** (the SSH rule goes in **before** `enable`):
```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 7896/tcp comment 'ssh, temporary until 5.5'
sudo ufw --force enable
sudo ufw status verbose
```
**DOCKER-USER (S1).** This appends a separate `*filter` block to both files, and ufw loads it on every reload:
```bash
for f in /etc/ufw/after.rules /etc/ufw/after6.rules; do
sudo tee -a "$f" >/dev/null <<'EOF'

# BEGIN stockahead DOCKER-USER (S1)
*filter
:DOCKER-USER - [0:0]
-A DOCKER-USER -i enp1s0f0 -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
-A DOCKER-USER -i enp1s0f0 -j DROP
-A DOCKER-USER -j RETURN
COMMIT
# END stockahead DOCKER-USER (S1)
EOF
done
sudo ufw reload
sudo iptables -S DOCKER-USER
sudo ip6tables -S DOCKER-USER
```
Containers can still reach the internet (their replies arrive as `ESTABLISHED`), and anything on the host (cloudflared → `localhost`) never passes `enp1s0f0`.

✅ Check:
```bash
sudo docker run -d --rm -p 8081:80 --name fwtest nginx
curl -sI localhost:8081 | head -1                # HTTP/1.1 200 OK
```
PC (PowerShell): `Test-NetConnection 145.239.3.226 -Port 8081` shows `TcpTestSucceeded : False`. Then, on the server: `sudo docker stop fwtest`.

### 4.2 Install Coolify and claim it

On the server:
```bash
curl -fsSL https://cdn.coollabs.io/coolify/install.sh | sudo bash
```
It reuses the Docker from 4.1 and writes `/etc/docker/daemon.json` (log rotation and the `10.0.0.0/8` address pool that S2 relies on). If it refuses Ubuntu 26.04, use **Fallback: manual install** below.

After install, check that sshd still has the hardened values (the installer may touch root login):
```bash
sudo sshd -T | grep -Ei '^(permitrootlogin|allowusers)'
sudo ls /etc/ssh/sshd_config.d/
```
Open the dashboard **only** through a port-forward. PC (Git Bash): `ssh -N -L 8000:localhost:8000 regavio`, then browse to http://localhost:8000.

1. Register the root admin **immediately**; the first account to register owns the instance.
2. Profile → Two-factor authentication → enable, and save the recovery codes to your password manager.
3. Settings → Advanced → turn **Registration allowed** off.
4. Servers → `localhost` → **Validate & configure**, and check that Docker is detected (#11089). If it isn't, stop and use **Fallback: pin Docker**.

✅ Check:
- Settings (or the footer) shows **≥ v4.3.23**,
- `localhost` shows Docker detected,
- a private window on http://localhost:8000/register says registration is disabled,
- PC (PowerShell): `Test-NetConnection 145.239.3.226 -Port 8000` → `False`.

#### Fallback: pin Docker (if #11089 hits)

```bash
apt-cache madison docker-ce | head -20
```
Pick the newest version the issue thread reports as detected (28.x at the time of writing). Then:
```bash
V='<version string from madison, e.g. 5:28.x.y-1~ubuntu.26.04~resolute>'
sudo apt-get install -y --allow-downgrades docker-ce="$V" docker-ce-cli="$V"
sudo apt-mark hold docker-ce docker-ce-cli
sudo systemctl restart docker
```
If `resolute` has no 28.x build, point `docker.sources` at `Suites: noble` for this install only, then pin as above. Record the pin and a revisit date in this runbook.

#### Fallback: manual install (if the script refuses 26.04)

*(From memory: verify against https://coolify.io/docs/get-started/installation#manual-installation before running.)* As root (`sudo -i`):
```bash
mkdir -p /data/coolify/{source,ssh,applications,databases,backups,services,proxy,webhooks-during-maintenance}
mkdir -p /data/coolify/ssh/{keys,mux} /data/coolify/proxy/dynamic
ssh-keygen -f /data/coolify/ssh/keys/id.root@host.docker.internal -t ed25519 -N '' -C root@coolify
cat /data/coolify/ssh/keys/id.root@host.docker.internal.pub >> /root/.ssh/authorized_keys
chmod 600 /root/.ssh/authorized_keys
cd /data/coolify/source
curl -fsSL https://cdn.coollabs.io/coolify/docker-compose.yml -o docker-compose.yml
curl -fsSL https://cdn.coollabs.io/coolify/docker-compose.prod.yml -o docker-compose.prod.yml
curl -fsSL https://cdn.coollabs.io/coolify/.env.production -o .env
curl -fsSL https://cdn.coollabs.io/coolify/upgrade.sh -o upgrade.sh
sed -i "s|^APP_ID=.*|APP_ID=$(openssl rand -hex 16)|" .env
sed -i "s|^APP_KEY=.*|APP_KEY=base64:$(openssl rand -base64 32)|" .env
sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -hex 32)|" .env
sed -i "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=$(openssl rand -hex 32)|" .env
sed -i "s|^PUSHER_APP_ID=.*|PUSHER_APP_ID=$(openssl rand -hex 32)|" .env
sed -i "s|^PUSHER_APP_KEY=.*|PUSHER_APP_KEY=$(openssl rand -hex 32)|" .env
sed -i "s|^PUSHER_APP_SECRET=.*|PUSHER_APP_SECRET=$(openssl rand -hex 32)|" .env
chown -R 9999:root /data/coolify && chmod -R 700 /data/coolify
cat > /etc/docker/daemon.json <<'EOF'
{ "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "3" },
  "default-address-pools": [ { "base": "10.0.0.0/8", "size": 24 } ] }
EOF
systemctl restart docker
docker network create --attachable coolify
docker compose --env-file .env -f docker-compose.yml -f docker-compose.prod.yml up -d --pull always --remove-orphans --force-recreate
```

### 4.3 Coolify server settings

In the dashboard (still through `ssh -N -L 8000:localhost:8000 regavio`):
1. Servers → `localhost` → General: **Port** `7896` → Save → **Validate**.
2. Settings → Updates: auto-update **on**.
3. Settings → set the instance timezone to `Europe/Warsaw`; backup cron times (8.2) use it.
4. Servers → `localhost` → Proxy → Configuration. In Traefik's `command:` list, add
   ```yaml
       - '--entrypoints.http.forwardedHeaders.trustedIPs=127.0.0.1/32,10.0.0.0/8,172.16.0.0/12'
   ```
   Save, then **Restart proxy**.

Copy `APP_KEY` into your password manager without writing it to disk on your PC. PC (Git Bash):
```bash
ssh regavio 'sudo grep -E "^APP_(ID|KEY)=" /data/coolify/source/.env'
```
Save the whole `.env` too (the DB and Redis passwords help with a restore):
```bash
ssh regavio 'sudo cat /data/coolify/source/.env'
```
✅ Check:
- `localhost` shows validated and usable, and Proxy shows running,
- on the server: `sudo journalctl -u ssh --since today | grep 'Accepted publickey for root'` shows a `10.x.x.x` source (S2 covers Coolify).

### 5.1 Tunnel on the host (D13)

Cloudflare dashboard → Zero Trust → Networks → Tunnels → **Create a tunnel** → Cloudflared → name `stockahead-test` → copy the token from the install command it shows.

On the server:
```bash
sudo mkdir -p --mode=0755 /usr/share/keyrings
curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg | sudo tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
echo 'deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main' \
  | sudo tee /etc/apt/sources.list.d/cloudflared.list
sudo apt-get update && sudo apt-get install -y cloudflared
read -rsp 'Tunnel token: ' TUNNEL_TOKEN; echo      # keeps the token out of shell history
sudo cloudflared service install "$TUNNEL_TOKEN"; unset TUNNEL_TOKEN
systemctl status cloudflared --no-pager
```
Tunnel → **Public hostnames** (also called "Published application routes"). Add them **in this order**, because the first match wins:

| # | Subdomain | Domain | Path | Service |
|---|---|---|---|---|
| 1 | `coolify` | `<domain>` | `terminal/ws` | `HTTP` `localhost:6002` |
| 2 | `coolify` | `<domain>` | | `HTTP` `localhost:8000` |
| 3 | `realtime` | `<domain>` | | `HTTP` `localhost:6001` |
| 4 | `test` | `<domain>` | | `HTTP` `localhost:80` |
| 5 | `ssh` | `<domain>` | | `SSH` `localhost:7896` |

If a hostname already has a DNS record, delete it first. Cloudflare creates the tunnel CNAMEs itself.

**Do 5.2 right after this.** Until then, the Coolify login page is reachable from the internet (registration is off and 2FA is on, but don't leave it that way).

✅ The tunnel shows **HEALTHY**, and PC (Git Bash) `curl -sI https://test.<domain>` returns `404` (Traefik, no app yet).

### 5.2 Cloudflare Access (D12)

Zero Trust → Settings → Authentication → Login methods: make sure **One-time PIN** is enabled.

Zero Trust → Access → Service credentials → Service Tokens → **Create**: name `github-deploy`, duration 1 year. Save the **Client ID** and **Client Secret** now; the secret is shown once. Write the expiry into the credentials inventory.

Access → Applications → **Add an application** → Self-hosted:
- **"Coolify"**: hostnames `coolify.<domain>` and `realtime.<domain>`, session 24 h. Policies:
  1. `owner`, action **Allow**, Include → Emails → `<your-email>`.
  2. `ci`, action **Service Auth**, Include → Service Token → `github-deploy`.
- **"SSH"**: hostname `ssh.<domain>`. Policy `owner`, action **Allow**, Include → Emails → `<your-email>`.

✅ A private window on `https://coolify.<domain>` shows the Cloudflare Access login, not Coolify. So does `https://realtime.<domain>`.

### 5.3 Point Coolify at its new domain

On the server (keep the `.env` backup readable by root only):
```bash
cd /data/coolify/source
sudo cp -p .env ".env.bak-$(date +%F)"
for kv in 'PUSHER_HOST=realtime.<domain>' 'PUSHER_PORT=443'; do
  k=${kv%%=*}
  if sudo grep -q "^$k=" .env; then sudo sed -i "s|^$k=.*|$kv|" .env; else echo "$kv" | sudo tee -a .env >/dev/null; fi
done
sudo grep -E '^PUSHER_(HOST|PORT)=' .env
curl -fsSL https://cdn.coollabs.io/coolify/install.sh | sudo bash    # applies .env and upgrades Coolify to latest
```
Then, in the dashboard (now at `https://coolify.<domain>`, or through the port-forward): Settings → General → **Instance's domain** `https://coolify.<domain>` → Save.

✅ Check:
- Access login, then Coolify login: the dashboard works at `https://coolify.<domain>`,
- open `https://coolify.<domain>/realtime` in a second tab, and the first tab shows the test notification,
- Servers → `localhost` → Terminal opens.

If realtime fails but the dashboard works, open `https://realtime.<domain>` once in the same browser so Access sets its cookie for that hostname, then retry. Also check the browser console for a websocket blocked by a redirect.

### 5.4 SSH through the tunnel from Windows

PC (PowerShell):
```powershell
winget install --id Cloudflare.cloudflared
(Get-Command cloudflared).Source
```
Replace the `stockahead` entry in `~/.ssh/config` (it points to a `deploy` user that won't exist). Keep the same `IdentityFile` line as `regavio`:
```
Host stockahead
    HostName ssh.<domain>
    User ubuntu
    ProxyCommand cloudflared access ssh --hostname %h
```
If ssh can't find `cloudflared`, use the full path printed above, e.g. `ProxyCommand "C:\Program Files (x86)\cloudflared\cloudflared.exe" access ssh --hostname %h`.

✅ `ssh stockahead` opens a browser for the Access login, then logs you in.

### 5.5 Close the last inbound port

**Only after 5.4 passes**, and with a `ssh stockahead` session open:
```bash
sudo ufw delete allow 7896/tcp
sudo ufw status verbose        # no ALLOW rules
```
✅ Check:
- PC (PowerShell):
  ```powershell
  7896,80,443,8000,6001,6002 | % { "{0}: {1}" -f $_, (Test-NetConnection 145.239.3.226 -Port $_ -WarningAction SilentlyContinue).TcpTestSucceeded }
  ```
  all `False`,
- a **new** `ssh stockahead` works,
- Coolify still shows `localhost` validated. It connects over the Docker network, not the public IP.

**Optional: OVH Edge Network Firewall.** It is stateless and IPv4-only. First move cloudflared off QUIC (UDP 7844):
```bash
sudo mkdir -p /etc/systemd/system/cloudflared.service.d
printf '[Service]\nEnvironment=TUNNEL_TRANSPORT_PROTOCOL=http2\n' | sudo tee /etc/systemd/system/cloudflared.service.d/override.conf
sudo systemctl daemon-reload && sudo systemctl restart cloudflared
sudo journalctl -u cloudflared -n 50 --no-pager | grep -i 'protocol=http2'
```
Then go to OVH Control Panel → Bare Metal Cloud → Network → IP → `145.239.3.226` → `…` → **Edge Network Firewall** → create the rules and enable the firewall:

| Priority | Action | Protocol | Option |
|---|---|---|---|
| 0 | Accept | TCP | established |
| 1 | Accept | UDP | source port 53 (DNS replies) |
| 2 | Accept | UDP | source port 123 (NTP replies) |
| 3 | Accept | ICMP | |
| 19 | Deny | IPv4 | |

✅ The tunnel stays **HEALTHY** for at least 10 minutes, and `sudo apt update` still works.

**Break-glass** if you lock yourself out: OVH Control Panel → the server → **IPMI / KVM** (console login as `ubuntu` with the password from 3.3), or Netboot → **Rescue** mode and reboot. In rescue mode, mount `/dev/md3` and fix `/etc/ssh/sshd_config.d/` or `/etc/ufw/`.

### 6.1 Postgres resource (D7)

Coolify → Projects → **+ Add** → `stockahead`. In the project, add environment `test` and switch to it.
**+ New** → Databases → **PostgreSQL**:
- Image `postgres:18`, name `stockahead-db`, initial database `stockahead`, user `stockahead`, password: keep the generated one.
- **Make it publicly available: off.** **SSL: off** (PG18 rejects Coolify's generated certificates, #8601; the DB is internal only).
- **Start**.

✅ Running and healthy. Copy the **internal** URL (`postgres://stockahead:…@<pg-uuid>:5432/stockahead`); 6.3 needs its host and password.

### 6.2 GHCR pull access

GitHub → Settings → Developer settings → Personal access tokens → **Tokens (classic)** → Generate new token: note `coolify-ghcr-pull`, expiry 1 year, scope **`read:packages` only**. Fine-grained tokens don't work with GHCR.

On the server (the token is read from stdin, so it stays out of history):
```bash
sudo docker login ghcr.io -u MasalskiMateusz1 --password-stdin
# paste the token, press Enter, then Ctrl-D
```
✅ `sudo docker pull ghcr.io/masalskimateusz1/stockahead:sha-XXXXXXX` works.

The token is stored base64-encoded (not encrypted) in `/root/.docker/config.json`. It only has `read:packages`, so that's acceptable.

### 6.3 App resource

Generate the setup token. PC (Git Bash): `openssl rand -hex 24`.

Coolify → `stockahead` / `test` → **+ New** → **Docker Image** → `ghcr.io/masalskimateusz1/stockahead:sha-XXXXXXX`.

Configuration → General:
- **Domains:** `https://test.<domain>`
- **Ports Exposes:** `8080`. **Ports Mappings:** empty.
- Advanced: **Force HTTPS / redirect HTTP→HTTPS: off** (the tunnel talks HTTP to Traefik). **Gzip compression: off** (Traefik would buffer SSE).

Environment Variables (runtime, not build-time):
```
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:postgresql://<pg-uuid>:5432/stockahead
SPRING_DATASOURCE_USERNAME=stockahead
SPRING_DATASOURCE_PASSWORD=<from 6.1>
STOCKAHEAD_SETUP_TOKEN=<from openssl above>
```
Healthcheck: **enabled**, GET, scheme `http`, host `localhost`, port `8080`, path `/actuator/health/readiness`, expected code `200`, interval 5 s, timeout 5 s, retries 10, **start period 60 s**.

**Deploy.**

Once the auth feature adds its own `SecurityFilterChain`, it must `permitAll()` on `/actuator/health/**`, or this healthcheck gets 401 and every deploy fails.

✅ Check, PC (Git Bash):
```bash
curl -sI https://test.<domain>/ | grep -i '^location'     # Location: https://test.<domain>/login
```
- the browser shows the login page with a valid certificate,
- Coolify → app → Logs shows Flyway ran (`Successfully applied` or `Schema … is up to date`),
- Coolify shows the container as **healthy**.

### 7.2 Tokens and the GitHub environment

Coolify → Settings → Advanced → **API Access: on**.
Coolify → Keys & Tokens → API tokens → create `github-deploy-test` with permissions **`write`** and **`deploy`** only, and an expiry. Copy it now.
Get `<app-uuid>` from the app's URL in Coolify.

PC (Git Bash). `gh secret set` prompts for the value, so it stays out of history.

**Option A (Pro), environment `test`:**
```bash
gh api -X PUT repos/MasalskiMateusz1/stockahead/environments/test
gh secret set COOLIFY_TOKEN           --env test
gh secret set CF_ACCESS_CLIENT_ID     --env test
gh secret set CF_ACCESS_CLIENT_SECRET --env test
gh variable set COOLIFY_URL      --env test --body 'https://coolify.<domain>'
gh variable set COOLIFY_APP_UUID --env test --body '<app-uuid>'
```
**Option B (Free), repository level:** the same commands without `--env test`.

✅ Check, PC (Git Bash):
```bash
read -rsp 'Coolify token: ' T; echo; read -rp 'CF id: ' I; read -rsp 'CF secret: ' S; echo
curl -s https://coolify.<domain>/api/v1/version -H "Authorization: Bearer $T" -H "CF-Access-Client-Id: $I" -H "CF-Access-Client-Secret: $S"   # prints the version
curl -s -o /dev/null -w '%{http_code}\n' https://coolify.<domain>/api/v1/version -H "Authorization: Bearer $T"                               # 302 or 403 (Access)
unset T I S
```

### 7.3 First auto-deploy

PC (Git Bash), for the trivial PR I prepare:
```bash
gh pr view <n> --web
gh pr merge <n> --squash --delete-branch
gh run watch
```
✅ `verify` → `image` → `deploy` are all green, and Coolify → app → Deployments shows the new `sha-` tag running.

### 7.4 Failed-deploy drill (human part)

Watch uptime during the drill. PC (PowerShell), in its own window:
```powershell
while ($true) { try { $c = (Invoke-WebRequest https://test.<domain>/actuator/health/readiness -UseBasicParsing -TimeoutSec 5).StatusCode; "$(Get-Date -f T) $c" } catch { "$(Get-Date -f T) DOWN" }; Start-Sleep 2 }
```
PC (Git Bash):
```bash
GOOD=$(gh run list --branch main --workflow ci.yml --status success --limit 1 --json headSha --jq '"sha-" + (.[0].headSha[0:7])')
echo "good tag: $GOOD"
gh workflow run ci.yml --ref drill/broken-start      # builds sha-<drill>
gh run watch
git fetch origin && BAD=sha-$(git rev-parse --short=7 origin/drill/broken-start)
gh workflow run ci.yml --ref main -f tag="$BAD"      # deploy job must FAIL
gh run watch
gh workflow run ci.yml --ref main -f tag="$GOOD"     # redeploy the good tag
gh run watch
git push origin --delete drill/broken-start
```
✅ The `$BAD` deploy job fails, the watcher never prints `DOWN`, and Coolify keeps the old container. Optionally, delete the drill image version in GHCR → package → Package versions.

### 8.1 R2 bucket and Coolify storage

Cloudflare dashboard → R2 → **Create bucket** `stockahead-backups`, location hint **Eastern Europe (EEUR)**.
R2 → **Manage API tokens** → Create **Account API token** (or User): permission **Object Read & Write**, apply to **specific bucket** `stockahead-backups` only, TTL 1 year. Copy the Access Key ID, the Secret Access Key and the S3 endpoint (`https://<account-id>.r2.cloudflarestorage.com`). Write the expiry into the credentials inventory.

Coolify → **Storages** → **+ Add** (S3):
- name `r2`, endpoint `https://<account-id>.r2.cloudflarestorage.com`, region `auto`, bucket `stockahead-backups`, plus the keys → **Validate connection**.

✅ Validation passes. That doesn't prove uploads work (#9792); 8.2 does.

**Fallback: Backblaze B2** if R2 validation or uploads fail. Create a private bucket `stockahead-backups`, then App Keys → Add a key restricted to that bucket (Read and Write). In Coolify, use endpoint `https://s3.<region>.backblazeb2.com` (e.g. `eu-central-003`) and region `<region>`.

### 8.2 Scheduled Postgres backups

Coolify → `stockahead-db` → **Backups** → **+ Add**:
- frequency `30 2 * * *`, **Save to S3: on**, storage `r2`,
- retention: 7 locally, 14 in S3.

Save, then **Backup now**.

✅ The execution log shows success, and a new dump appears in R2 → `stockahead-backups`.

Set up notifications now too (pre-mortem risk): Coolify → Notifications → Email (or Discord/Telegram), enable **Backup failure** and **Deployment failure**, and send a test.

### 8.3 Coolify instance backup

Coolify → Settings → **Backup**: enabled, frequency `0 3 * * *`, **S3: on**, storage `r2`. Save, then **Backup now**.

✅ An instance-backup file appears in R2. Remember that it can't be restored without `APP_KEY` (saved in 4.3).

### 8.4 Restore drill

Download the newest Postgres dump from R2 (dashboard → bucket → object → Download) to `~/Downloads`. PC (Git Bash) with Docker Desktop:
```bash
DUMP=~/Downloads/<dump file name from R2>
docker run -d --name restore-drill -e POSTGRES_PASSWORD=drill postgres:18
until docker exec restore-drill pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
docker cp "$DUMP" restore-drill:/tmp/db.dump
docker exec restore-drill createdb -U postgres stockahead
docker exec restore-drill pg_restore -U postgres -d stockahead --no-owner --no-acl /tmp/db.dump
docker exec restore-drill psql -U postgres -d stockahead -tAc 'select count(*) from flyway_schema_history'
```
Production count: Coolify → `stockahead-db` → **Terminal**:
```bash
psql -U stockahead -d stockahead -tAc 'select count(*) from flyway_schema_history'
```
✅ The two counts match. Clean up, because the dump holds production data:
```bash
docker rm -f restore-drill
rm "$DUMP"
```

---

## Restore procedures

### R1. Bad deploy (app only, schema compatible)

Roll back to an earlier image. PC (Git Bash):
```bash
gh workflow run ci.yml --ref main -f tag=sha-XXXXXXX
gh run watch
```
Or use Coolify → app → Deployments → pick an earlier deployment → **Rollback**. The image is already on the host, so it takes 1–3 minutes. Migrations don't roll back; that's why they must be expand/contract (S5).

### R2. Bad migration or corrupted data (restore the database)

1. Coolify → app → **Stop** (so nothing writes during the restore).
2. Pick the dump: the latest one, or the one taken with "Backup now" before the risky merge. Coolify → `stockahead-db` → Backups lists the local copies; R2 holds 14 days.
3. Restore into the running Postgres. For a dump that's in R2 only, first copy it to the server: `scp <dump> stockahead:/tmp/`. On the server:
   ```bash
   PG=$(sudo docker ps -q --filter "name=<pg-uuid>")
   sudo docker cp /tmp/<dump> "$PG":/tmp/restore.dump
   sudo docker exec "$PG" pg_restore -U stockahead -d stockahead --clean --if-exists --no-owner /tmp/restore.dump
   sudo docker exec "$PG" rm /tmp/restore.dump && sudo rm /tmp/<dump>
   ```
4. Deploy the image tag that matches the restored schema (R1), then **Start** the app.
5. ✅ The login page loads, and `flyway_schema_history` has the expected row count.

### R3. Server lost or reinstalled (full rebuild)

Budget: about half a day. If that's not available, switch to the runner-up (Render, see `infrastructure.md`) with the same image and the latest dump.

1. OVH: reinstall Ubuntu 26.04 with your SSH key, user `ubuntu`, SSH port `7896`.
2. Re-run **3.2, 3.3, 4.1**.
3. Install Coolify (**4.2**) and register a throwaway admin. The restore replaces it.
4. Restore Coolify from the instance backup. *(From memory: verify against https://coolify.io/docs/knowledge-base/how-to/backup-restore-coolify before running.)* Download the newest instance backup from R2 to the server, then:
   ```bash
   sudo docker cp <instance-backup>.dmp coolify-db:/tmp/coolify.dmp
   sudo docker exec coolify-db pg_restore --verbose --clean --no-acl --no-owner -U coolify -d coolify /tmp/coolify.dmp
   # add the OLD key so Coolify can decrypt the restored data:
   echo 'APP_PREVIOUS_KEYS=<old APP_KEY from password manager>' | sudo tee -a /data/coolify/source/.env
   curl -fsSL https://cdn.coollabs.io/coolify/install.sh | sudo bash
   ```
5. The restored DB has the old Coolify SSH key for `localhost`. Coolify → Keys & Tokens → Private keys → the `localhost` key → copy its **public key** into `/root/.ssh/authorized_keys`. Then Servers → `localhost` → **Validate**.
6. Re-run **4.3** (proxy trusted IPs, port 7896). For **5.1**, only install `cloudflared` and run `service install` with the **existing** tunnel's token (Zero Trust → Tunnels → `stockahead-test` → Configure). The public hostnames and Access apps live in Cloudflare and survive. Apply **5.3**'s `.env` edits again, and keep ufw closed (**5.5**).
7. Re-run **6.2** (GHCR login).
8. The Postgres volume is empty: start `stockahead-db`, then restore the latest dump as in **R2**.
9. Redeploy the last good tag (R1). Run the checks from 6.3 and 8.2.

### R4. Lost Coolify admin access (2FA device gone)

Use the recovery codes from your password manager. Without them: `ssh stockahead`, then follow Coolify's docs for resetting the root user's password and 2FA from the server (the `php artisan` commands inside the `coolify` container).

---

## Not covered yet

These come from the risk register in `infrastructure.md` and are not part of the current plan:
- `mdadm` monitoring mail and `smartd` for the RAID1 disks,
- a disk-usage alert for Docker images and build cache,
- a CI check of `GET /api/v1/version` before deploying.
