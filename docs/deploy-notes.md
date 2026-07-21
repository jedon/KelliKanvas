# Deploy notes

## Household NAS (SMB) build secrets

Photo hosts/shares/paths are baked into source (`HouseholdNasDefaults`).
Credentials are **not** in git. Supply them at assemble time:

| Key | Where | Purpose |
| --- | --- | --- |
| `QNAP_NAS_USERNAME` | env, repo-root `.env`, or `local.properties` | SMB username → `BuildConfig.HOUSEHOLD_SMB_USERNAME` |
| `QNAP_NAS_PASSWORD` | env, repo-root `.env`, or `local.properties` | SMB password → `BuildConfig.HOUSEHOLD_SMB_PASSWORD` |
| `QNAP_NAS_HOST` | optional documentation / tooling | Expected LAN host (`192.168.68.62` / DarklingNAS) |

At runtime, passwords are stored in the Android Keystore-backed credential vault
(`kellikanvas_source_credentials`). Room `smb_connections` stores host/share/username only.

See `.env.example` for a template.

## Probe-proven SMB photo path (LAN)

- Host: `192.168.68.62` (port `445`)
- Primary share: `Kelli`
- Example roots: `Digital Photos`, `Cell Phone Photos`, `Photos for frame TV and printing`
- Also available: `Multimedia\Canvas`, `Public\Media\Pictures`
