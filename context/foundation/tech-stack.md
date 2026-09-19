---
starter_id: spring
package_manager: maven
project_name: stockahead
hints:
  language_family: java
  team_size: solo
  deployment_target: self-host
  ci_provider: github-actions
  ci_default_flow: auto-deploy-on-merge
  bootstrapper_confidence: verified
  path_taken: standard
  quality_override: false
  self_check_answers: null
  has_auth: true
  has_payments: false
  has_realtime: true
  has_ai: false
  has_background_jobs: false
---

## Why this stack

A solo developer with about four years of Java and Spring Boot experience is building a small warehouse and production-reservation web app in three weeks of after-hours work, so staying in a familiar, agent-friendly stack matters more than out-of-the-box features. Spring Boot is the recommended default for a Java web app, clears all four agent-friendly gates, and has verified bootstrapper support. The author can also judge when an agent drifts from Spring practice. The default scaffold lacks auth, realtime and persistence, and the user chose to add them manually: Spring Security for email and password login with manager and technician roles, JPA with PostgreSQL and migrations for the transactional reservation guardrails (stock never below zero, no double reservation), and server-sent events or WebSocket so reservations and the shopping list refresh within two seconds. Payments, AI and background jobs are out of scope per the PRD. Deployment is self-hosted, and CI runs on GitHub Actions with auto-deploy on merge, which needs SSH or a self-hosted runner to reach the server.
