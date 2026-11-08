# Registration Bot Protection — Plan Brief

> Full plan: `context/changes/registration-bot-protection/plan.md`

## What & Why

`/register` is the only public write endpoint. Any script can currently fill the operator's pending list with fake companies. This slice (roadmap S-10) settles FR-023's "human-verification check": automated mass sign-ups are refused before a company row exists, while a person registers exactly as today (prd-v2 NFR).

## Starting Point

`RegistrationController` validates the company name and then the credentials, and creates a PENDING company and its manager in one transaction. CSRF is on, but nothing distinguishes a bot from a human. In prod the app is reachable only through Cloudflare Tunnel, which sets `CF-Connecting-IP`.

## Desired End State

A submission that fills a hidden honeypot field, has no form-issued timestamp in its session, or comes back faster than 3 s after the form was shown creates nothing. It gets the neutral message "Nie udało się wysłać formularza. Spróbuj ponownie." with name and email kept. After 5 successful registrations from one IP within a sliding hour, further attempts get HTTP 429 and "Zbyt wiele rejestracji z tego adresu. Spróbuj ponownie później." Humans see no new field, delay or widget.

## Key Decisions Made

| Decision         | Choice                                                                                                          | Why (1 sentence)                                                                                  |
| ---------------- | --------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| Mechanism        | Self-hosted honeypot + minimum fill time (3 s, `app.registration.min-fill-time`)                                | User chose no external vendor and no third-party script.                                          |
| Timer storage    | `Instant` in the HTTP session, stamped on every form render                                                     | CSRF already needs the session, so no signing key or secret is required.                          |
| Vendor outage    | Not applicable                                                                                                  | No external dependency.                                                                           |
| On/off flag      | None, always on                                                                                                 | Protection can never be silently disabled in prod; tests seed the session attribute instead.      |
| Bot rejection UX | Neutral retry error, fields kept, INFO log                                                                      | A falsely flagged human recovers in one click, and the message doesn't reveal which check failed. |
| Rate limit       | In-memory, per IP, 5 *successful* registrations per sliding hour (`app.registration.max-per-ip`, `rate-window`) | Caps scripts that beat the honeypot, without new infrastructure; typos don't use quota.           |
| IP source        | `CF-Connecting-IP`, falling back to `remoteAddr`                                                                | Prod is reachable only via Cloudflare; the leftmost `X-Forwarded-For` is spoofable.               |
| Over-limit UX    | HTTP 429, form re-rendered with an explicit message                                                             | Being explicit helps a legitimate user on a shared NAT.                                           |

## Scope

**In scope:** honeypot field, session fill-time check, in-memory per-IP limiter with reserve/release, two messages, unit and integration tests.

**Out of scope:** Turnstile or any CAPTCHA, Cloudflare WAF rules, persisted limiter state, an enable flag, email verification, changes to `/login`/`/setup`/technician form, fake-success responses.

## Architecture / Approach

POST `/register` runs its checks cheapest first, all before any database write: honeypot → timer (`RegistrationBotCheck`) → existing field validation → `RegistrationRateLimiter.tryReserve(ClientAddress.of(request))` → transaction. The slot is released whenever the transaction doesn't commit (duplicate email, unexpected error), so concurrent requests can't exceed the cap and failures don't use quota.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Honeypot and minimum fill time | Naive and fast bots rejected with a neutral error; existing tests send a pre-stamped session | Browser autofill filling the honeypot, so it needs an unusual field name and `autocomplete="off"` |
| 2. Per-IP registration rate limit | 6th successful registration per IP per hour answers 429 | Reserve/release leaking a slot on an unexpected exception |

**Prerequisites:** S-09 company-registration (done).
**Estimated effort:** ~1–2 sessions across 2 phases.

## Open Risks & Assumptions

- `CF-Connecting-IP` can be trusted only while no route bypasses Cloudflare Tunnel. If the app is ever exposed directly, the header becomes spoofable.
- Rate-limit counts reset on restart and are per instance during a rolling deploy (accepted).
- Self-hosted checks stop naive bots only. A targeted script that waits 3 s is held back by the IP cap and, after that, by operator rejection (S-11).

## Success Criteria (Summary)

- No company row is created for a honeypot, missing-timestamp, too-fast or over-limit submission (integration tests against Postgres).
- A human registers on `/register` with no visible change.
- `./mvnw verify` is green, with the existing registration tests intact.
