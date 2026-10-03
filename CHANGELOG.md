# Changelog

Every release of Finanze, newest first. Versions follow [Semantic Versioning](https://semver.org/):
the major number changes when an update needs something done by hand (see its notes), the minor
number for new features, the patch number for fixes. The section of a version is the text of its
GitHub release.

## [Unreleased]

## [1.1.0] - 2026-10-03

### Added
- Passkeys: sign in with the device's lock (face, fingerprint or PIN), without username, password
  or 2FA code. Add, rename and remove them in Settings › Account; adding one asks for the password
  (and the 2FA code when 2FA is on). Needs `APP_PUBLIC_URL` set to the address you open the app at.

## [1.0.0] - 2026-10-01

The first release: personal finances and net worth for a household, self-hosted with Docker.

### Money in and out
- Income, expenses and transfers between your own accounts, with filters, search and tags.
- Recurring entries (rent, salary, subscriptions) created automatically on their schedule.
- Two-level categories: macro categories with their details (Casa › Affitto), in the language of
  each user to start with.
- Category rules ("the description contains *migros* → Groceries › Supermarket") and suggestions
  from past entries while writing an entry or importing.
- CSV import (all at once or reviewed row by row, duplicates detected, missing categories created
  on the spot) and CSV export.

### Analysis
- Income & expenses by month and by year, split into categories and their details, by tag, and a
  categories × tags table.
- A category month by month against the year before.
- Annual report of a closed year against the one before, ready to print.
- Forecast of a coming year, month by month, in saved scenarios.

### Budgets and goals
- Budgets per expense category, monthly, quarterly or yearly, with an end-of-month projection that
  starts from the usual pace.
- Savings goals: a balance to reach on some positions, or a yearly amount to put into them.
- Email notifications: budgets close to or over their limit, goals reached, and a summary of the
  previous month.

### Net worth
- Positions (accounts, ETFs, pension, crypto…) valued from the records you enter, with the net
  worth per month and year and its split by asset class.
- Any currency, converted to your base currency with the ECB reference rates (downloaded every
  day) or with rates of your own.

### Accounts and security
- Users created by an administrator, Argon2id passwords, optional two-step verification with
  recovery codes, account lock and per-IP limits against password guessing, login history.
- Every user sees only their own data; administrators manage accounts, not finances.
- Interface in Italian, English, German and French, light and dark theme, usable on a phone.

### Running it
- Docker Compose with PostgreSQL, the Spring Boot backend and Nginx behind your reverse proxy.
- `deploy.sh` to install a branch or a release, with a database backup before every update.
- Nightly backups with a copy in the cloud (Restic), and a restore script.
