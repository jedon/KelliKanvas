# Shell Permission Gate + DLNA XML Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a cold-start stacked Compose permission gate for local network (required) and optional ambient sensors, plus harden DLNA XML parsing against DOCDECL/entities at the parser feature level.

**Architecture:** `PermissionCoordinator` computes permission snapshots; `ShellPermissionGate` renders stacked Grant cards; `MainActivity` swaps gate vs placeholder shell. Session-only “Not now.” DLNA `secureParser` keeps string bans and adds Android XmlPullParser secure features.

**Tech Stack:** Kotlin, Jetpack Compose (TV Material already in app), Activity Result APIs, Robolectric/unit tests, XmlPullParser.

**Spec:** `docs/superpowers/specs/2026-07-19-shell-permission-gate-design.md`

---

## File map

| File | Responsibility |
|------|----------------|
| `app/.../permission/PermissionModels.kt` | Row ids, status enum, snapshot data class |
| `app/.../permission/PermissionCoordinator.kt` | Snapshot + shouldShowGate; settings Intent factory |
| `app/.../permission/ShellPermissionGate.kt` | Compose UI |
| `app/.../MainActivity.kt` | Wire gate vs shell + activity result launchers |
| `app/src/main/AndroidManifest.xml` | Declare ACTIVITY_RECOGNITION, BODY_SENSORS |
| `app/src/test/.../PermissionCoordinatorTest.kt` | Unit/Robolectric tests |
| `source/dlna/.../DlnaXml.kt` | secureParser feature flags |
| `source/dlna/src/test/.../DlnaXmlTest.kt` | Assert DOCTYPE/entity rejection still works |

---

### Task 1: Permission models + coordinator (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/jedon/kellikanvas/permission/PermissionModels.kt`
- Create: `app/src/main/kotlin/com/jedon/kellikanvas/permission/PermissionCoordinator.kt`
- Create: `app/src/test/kotlin/com/jedon/kellikanvas/permission/PermissionCoordinatorTest.kt`

- [ ] **Step 1: Write failing tests** for snapshot statuses and `shouldShowGate` (local network denied → true; local network granted + sensors denied → false; NotApplicable local network → false).

- [ ] **Step 2: Run** `:app:testDebugUnitTest --tests "*PermissionCoordinator*"` — expect FAIL.

- [ ] **Step 3: Implement models + coordinator** using `ContextCompat.checkSelfPermission`, SDK checks for `ACCESS_LOCAL_NETWORK` (string `"android.permission.ACCESS_LOCAL_NETWORK"`), and install-time Internet as always GrantedAtInstall.

- [ ] **Step 4: Run tests** — expect PASS.

- [ ] **Step 5: Commit** `test+feat: add PermissionCoordinator snapshot`

---

### Task 2: Manifest declarations

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add** `<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />` and `BODY_SENSORS`.

- [ ] **Step 2: Commit** `chore(app): declare ambient sensor permissions`

---

### Task 3: ShellPermissionGate UI + MainActivity wiring (TDD where practical)

**Files:**
- Create: `app/src/main/kotlin/com/jedon/kellikanvas/permission/ShellPermissionGate.kt`
- Modify: `app/src/main/kotlin/com/jedon/kellikanvas/MainActivity.kt`
- Extend: `PermissionCoordinatorTest` or add `MainActivityPermissionGateTest` if Robolectric Activity feasible; otherwise Compose UI tests optional — require at least coordinator+sessionSkip unit coverage already done, plus a small test for “settings intent package”.

- [ ] **Step 1: Implement Compose stacked cards** matching layout A (Internet status, Local network Grant, Activity Grant, Body sensors Grant, Not now).

- [ ] **Step 2: Wire MainActivity** with `rememberLauncherForActivityResult`, `sessionSkip` state, rescan after results / onStart.

- [ ] **Step 3: Permanent deny** → Open app settings button via coordinator `appSettingsIntent()`.

- [ ] **Step 4: Manual smoke checklist in commit message**; run unit tests.

- [ ] **Step 5: Commit** `feat(app): show cold-start permission launch gate`

---

### Task 4: DLNA XML parser feature hardening (TDD)

**Files:**
- Modify: `source/dlna/src/main/kotlin/com/jedon/kellikanvas/source/dlna/DlnaXml.kt` (`secureParser`)
- Modify: `source/dlna/src/test/kotlin/com/jedon/kellikanvas/source/dlna/DlnaXmlTest.kt`

- [ ] **Step 1: Add/extend test** that DOCTYPE payload still throws `DlnaProtocolException` (existing) and add comment/assert that feature-disabled path rejects DOCDECL if reachable.

- [ ] **Step 2: In `secureParser`**, after creating pull parser, set:
  - `http://xmlpull.org/v1/doc/features.html#process-docdecl` = `false` (try/catch UnsupportedOperation if needed)
  - `http://xmlpull.org/v1/doc/features.html#process-namespaces` leave as today (`isNamespaceAware = true`)
  - Also attempt Android/KXML features used elsewhere in repo if available (`disallow-doctype-decl` pattern from `BackupRulesSourceTest` where applicable for XmlPullParser — only features XmlPullParser supports).

- [ ] **Step 3: Keep existing string bans** for `<!DOCTYPE` / `<!ENTITY`.

- [ ] **Step 4: Run** `:source:dlna:testDebugUnitTest --tests "*DlnaXml*"` — PASS.

- [ ] **Step 5: Commit** `fix(dlna): harden XML pull parser against DOCDECL`

---

### Task 5: Verification sweep

- [ ] **Step 1: Run** with JDK 17:
  ```
  ./gradlew :app:testDebugUnitTest --tests "*Permission*" :source:dlna:testDebugUnitTest --tests "*DlnaXml*" ktlintCheck
  ```
- [ ] **Step 2: Fix any failures.**
- [ ] **Step 3: Final commit only if fixes needed**; otherwise stop.

---

## Notes for implementers

- Worktree: `G:/Programming/KelliKanvas/.worktrees/enterprise-high-findings`
- Branch: `fix/enterprise-high-findings`
- JDK: Temurin/Android bundled 17
- Do not request `INTERNET` at runtime
- Sensors optional for gate dismissal
- Do not push unless asked
