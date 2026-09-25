# Finance Tracker

Self-hosted personal finance app for a small group of users (you and your family):

- **Cash flow** – record income and expenses (date, category, amount, currency, note) and see
  monthly and yearly dashboards: totals, savings rate, breakdown by category.
- **Net worth** – bank accounts, crypto, ETFs, stocks, pension, … Record *quantity × unit price*
  at a date; the value of every position is carried forward until the next record. Dashboards
  show total net worth per month/year and its split by asset class.
- **Multi-currency** – each entry/position keeps its own currency; dashboards convert to the
  user's base currency with exchange rates you enter manually.
- **Per-user preferences** – interface language (Italian / English) and theme (system, light,
  dark) are saved to the account and follow the user on every device.
- **Personal categories** – each user starts with a set of categories named in the language chosen
  when the account is created (`APP_ADMIN_LANGUAGE` for the first admin); from then on they are
  the user's own to rename, recolour or delete.

| Layer    | Tech |
|----------|------|
| Backend  | Java 25, Spring Boot 4.1 (Web MVC, Security 7, Data JPA, Session JDBC), Flyway, PostgreSQL 18 |
| Frontend | React 19, TypeScript, Vite, TanStack Query, Recharts, Tailwind CSS 4 (UI in Italian and English) |
| Deploy   | Docker Compose: `db` (Postgres) + `backend` + `web` (Nginx serving the SPA and proxying `/api`) |

## Project layout

```
backend/
  src/main/java/me/luucka/finance/
    core/        pure Java: FX table, cash-flow & net-worth calculators, TOTP, AES-GCM, password policy
    auth/        security config, login + 2FA flow, rate limiting, session revocation
    account/     self-service: password, preferences (base currency, language, theme), 2FA, login history
    admin/       user management (no public sign-up) + bootstrap admin
    category/ cashflow/ position/ fx/ dashboard/
  src/main/resources/db/migration/   Flyway migrations
  src/test/java/…/core/              unit tests (no Spring)
  src/test/java/…/*IT.java           integration tests (Testcontainers + MockMvc)
frontend/
  src/api/       fetch client (CSRF), types, React Query hooks
  src/i18n/      it.ts (reference) + en.ts messages, useI18n()
  src/preferences/ language + theme: applied at startup, synced with the profile
  src/pages/     dashboards, entries, positions, bulk update, settings, admin
  default.conf.template   Nginx: SPA + reverse proxy + security headers
docker-compose.yml, .env.example
```

## Security model

- **Accounts**: no self-registration. The first admin is created on an empty database; admins
  create users with a one-time temporary password that must be changed at first login.
  Admins manage accounts but **cannot see other users' financial data**.
- **Passwords**: Argon2id (19 MiB, 2 iterations), min. 12 chars, common/username-containing
  passwords rejected (NIST 800-63B style). Hashes are upgraded transparently if parameters change.
- **Two-factor auth**: optional TOTP (RFC 6238) with QR enrolment; the secret is encrypted at rest
  with AES-256-GCM (`APP_ENCRYPTION_KEY`), codes cannot be replayed, 10 single-use recovery codes
  (stored as SHA-256 hashes).
- **Brute force**: generic error for every login failure (no user enumeration, constant-ish time),
  account lock after 5 failures for 15 min, per-IP limit on failed attempts, audit log of logins
  visible to the user.
- **Sessions**: server-side sessions stored in PostgreSQL (Spring Session JDBC); cookie is
  `HttpOnly`, `Secure`, `SameSite=Strict`; session id rotated on login; 2 h idle timeout.
  Password change / reset, disabling or deleting a user revokes their other sessions immediately.
- **CSRF**: synchronizer token in the session, sent by the SPA in `X-CSRF-TOKEN`.
- **Authorization**: every query is scoped by the owner id taken from the session; accessing
  another user's record returns 404. Covered by `DataIsolationIT`.
- **Headers**: strict CSP, `frame-ancestors 'none'`, `nosniff`, `no-referrer` (Nginx + Spring).
- **Containers**: DB not published; backend read-only filesystem, non-root, all capabilities
  dropped; only the web container is exposed, bound to an address you choose.

## Run locally (development)

Requirements: JDK 25, Maven 3.9+, Node 22+, Docker (for Postgres and the integration tests).

```bash
# 1. Database
docker run -d --name finance-db -p 5432:5432 \
  -e POSTGRES_DB=finance -e POSTGRES_USER=finance -e POSTGRES_PASSWORD=finance postgres:18-alpine

# 2. Backend (dev profile: insecure cookie for http, dev-only encryption key,
#    admin "admin" / "dev-password-change-me")
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 3. Frontend (proxies /api to :8080)
cd frontend
npm install
npm run dev          # http://localhost:5173
```

Optionally add the Maven wrapper once: `mvn wrapper:wrapper`.

## Tests

```bash
cd backend
mvn verify           # unit tests (surefire) + integration tests *IT (failsafe, needs Docker)
cd ../frontend
npm run typecheck && npm run build
```

Integration tests start PostgreSQL with Testcontainers and exercise the real security chain:
login/CSRF/session rotation, lockout, forced password change, session revocation, admin rules,
2FA enrolment + replay protection + recovery codes, per-user data isolation, dashboard math.

GitHub Actions (`.github/workflows/ci.yml`) runs the same checks on every pull request and on
pushes to `master`: `mvn verify` on JDK 25 (Testcontainers uses the runner's Docker) and the
frontend typecheck + build.

## Deploy with Docker Compose

```bash
cp .env.example .env
# fill DB_PASSWORD and APP_ENCRYPTION_KEY (openssl rand -base64 32), set WEB_BIND/WEB_PORT/TRUSTED_PROXY
docker compose up -d --build
docker compose logs backend | grep -A3 "temporary password"   # if APP_ADMIN_PASSWORD was empty
```

The app must be served over HTTPS (the session cookie is `Secure`) through Nginx Proxy Manager
running on another host:

- `WEB_BIND` is this server's LAN IP, `WEB_PORT` the port NPM forwards to. In NPM create a proxy
  host with scheme `http`, forward hostname `WEB_BIND`, port `WEB_PORT`, and an SSL certificate
  with "Force SSL".
- `TRUSTED_PROXY` is the IP (or CIDR) of the NPM host. The `web` container trusts
  `X-Forwarded-For`/`X-Forwarded-Proto` only from there, so login rate limiting and the login
  history see the real client IP, and a client reaching the port directly cannot forge it.
- Allow `WEB_PORT` only from the NPM host in your firewall. Ports published by Docker bypass
  `ufw`: filter them in the `DOCKER-USER` iptables chain, or at the network level.
- With rootless Docker the default port driver hides the source address, so NPM is not
  recognised: set `DOCKERD_ROOTLESS_ROOTLESSKIT_PORT_DRIVER=slirp4netns`.

### Updates with `deploy.sh`

On the server, from the repository directory:

```bash
./deploy.sh master      # switch to and deploy a branch
./deploy.sh             # update the current branch
```

The script refuses local tracked changes, fetches `origin`, fast-forwards the branch, rebuilds and
restarts the containers (`docker compose up -d --build --wait`) and waits until the backend is
ready (`FINANCE_HEALTH_TIMEOUT`, seconds, default 300).

**Back up** the Postgres volume *and* `APP_ENCRYPTION_KEY`. Without the key, 2FA secrets cannot be
decrypted (an admin can reset 2FA for affected users; no financial data is encrypted with it).

## How values are computed

- **Cash flow**: each entry is converted with the rate of its own date. Entries in a currency
  with no rate are excluded from totals and the UI warns about it.
- **Net worth at date D**: for each position, the latest record on or before D
  (`quantity × unit price`, in the position's currency), converted with the rate valid on D.
  Positions don't exist before their first record; a record with quantity 0 closes a position.
  Monthly series use month-end dates (today for the current month); yearly series use Dec 31.
- **Exchange rates**: `1 <currency> = rate <base>`, tied to the base currency they were entered
  for. The latest rate on or before the date is used; dates before the first rate use the first one.
- Bank accounts (`CASH`): quantity is the balance, unit price 1.

## API overview

| Area | Endpoints |
|------|-----------|
| Auth | `GET /api/auth/csrf`, `POST /api/auth/login`, `POST /api/auth/login/mfa`, `POST /api/auth/logout`, `GET /api/auth/me` |
| Account | `PUT /api/account/password`, `PUT /api/account/settings` (partial: `baseCurrency`, `language` `IT\|EN`, `theme` `SYSTEM\|LIGHT\|DARK`), `GET /api/account/logins`, `POST /api/account/mfa/{setup,enable,disable,recovery-codes}` |
| Categories | `GET/POST /api/categories`, `PUT/DELETE /api/categories/{id}` |
| Entries | `GET /api/cash-entries?from&to&kind&categoryId&q&page&size`, `POST`, `PUT/DELETE /{id}` |
| Positions | `GET/POST /api/positions`, `GET/PUT/DELETE /{id}`, `GET/POST /{id}/snapshots`, `PUT/DELETE /{id}/snapshots/{sid}`, `POST /api/positions/snapshots/bulk` |
| FX | `GET/POST /api/fx-rates`, `DELETE /{id}` |
| Dashboards | `GET /api/dashboard/cashflow?year`, `/cashflow/years`, `/net-worth?granularity=MONTH\|YEAR&from=yyyy-MM&to=yyyy-MM`, `/net-worth/detail?date` |
| Admin | `GET/POST /api/admin/users`, `PATCH/DELETE /{id}`, `POST /{id}/{reset-password,unlock,reset-mfa}` |

Errors are RFC 9457 problem details with a stable `code` (e.g. `invalid_credentials`,
`validation_failed` + `errors` map).

## Ideas for later

CSV import/export of entries, automatic price and FX fetching (e.g. ECB rates, CoinGecko),
recurring entries, budgets per category, transfers between own accounts.
