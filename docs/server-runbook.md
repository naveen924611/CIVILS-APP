# Server runbook (Oracle Cloud Always Free)

Everything here is done by the owner. Each step is explained first; nothing is destructive.

## 1. Create the VM
1. Sign up at https://www.oracle.com/cloud/free/ - choose home region **Hyderabad or Mumbai** (cannot be changed later).
2. Compute > Instances > Create. Image **Ubuntu 22.04**. Shape **VM.Standard.A1.Flex** (ARM, 2 OCPU, 12 GB RAM). If it says "out of capacity", retry later, or use the AMD micro shape.
3. Let Oracle generate an SSH key pair and **download the private key** (keep it safe).
4. In the VM's network security list, allow inbound TCP **80** and **443** (22 is already open).

## 2. DuckDNS name
1. https://www.duckdns.org - sign in, create a subdomain, set its IP to the VM's public IP.
2. Note the subdomain name and token (put them in `.env` as `DUCKDNS_SUBDOMAIN`, `DUCKDNS_TOKEN`).

## 3. Harden the VM (SSH in first: `ssh -i key.pem ubuntu@<ip>`)
```bash
sudo apt update && sudo apt -y upgrade
sudo apt -y install unattended-upgrades fail2ban ufw
sudo dpkg-reconfigure -plow unattended-upgrades       # choose Yes
sudo ufw allow 22 && sudo ufw allow 80 && sudo ufw allow 443 && sudo ufw --force enable
sudo iptables -I INPUT -p tcp -m multiport --dports 80,443 -j ACCEPT   # Oracle images block ports by default
sudo netfilter-persistent save 2>/dev/null || true
```
SSH already uses keys only on Oracle images. Do not enable password login.

## 4. Install Docker
```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER && newgrp docker
```

## 5. Deploy
```bash
git clone <your private repo url> civils-companion && cd civils-companion
cp .env.example .env && nano .env          # fill JWT_SECRET, OWNER_PASSWORD_HASH, DUCKDNS_SUBDOMAIN
mkdir -p secrets                            # put firebase-service-account.json here later
docker compose up -d --build
curl https://<subdomain>.duckdns.org/health   # should print {"status":"ok"}
```
Update later with: `git pull && docker compose up -d --build`.

## 6. Firebase (push)
1. https://console.firebase.google.com - new project (free Spark plan).
2. Add an **Android app** with package name `com.naveen.civilscompanion` (must match the app). Download `google-services.json` and put it in `android/app/` (never commit it).
3. Project settings > Service accounts > Generate new private key. Upload that file to the server as `secrets/firebase-service-account.json` (never commit it).
4. Test: `docker compose exec api python -m app.push.send_test`

## 7. GitHub secrets (for the APK build)
Repository > Settings > Secrets and variables > Actions > New secret:
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, `GOOGLE_SERVICES_JSON_BASE64`, `API_BASE_URL` (e.g. `https://<subdomain>.duckdns.org`).
