# CI — Activating the APK build

`android-workflow.yml` is the complete GitHub Actions pipeline that builds, tests and
validates the APK. It lives here rather than in `.github/workflows/` because the GitHub App
used to push this branch does not hold the `workflows` permission, so it cannot create
workflow files:

```
! [remote rejected] refusing to allow a GitHub App to create or update
  workflow `.github/workflows/android.yml` without `workflows` permission
```

## Activate it (one command)

```bash
mkdir -p .github/workflows
git mv ci/android-workflow.yml .github/workflows/android.yml
git commit -m "Enable APK build workflow"
git push
```

Or, entirely in the browser: **Add file → Create new file**, name it
`.github/workflows/android.yml`, and paste the contents of `android-workflow.yml`.

Re-running either command overwrites the activated copy, so use it again whenever this file
changes — the copy under `.github/workflows/` is otherwise frozen at the moment it was created.
(The `git mv` form also removes this file; later edits then go straight to
`.github/workflows/android.yml`, which has to be done from an account that holds the
`workflows` permission.)

## What it does

```
setup JDK 17 → setup Android SDK → gradle wrapper
  → ./gradlew --continue lintDebug testDebugUnitTest assembleDebug assembleRelease
  → verify APKs → upload artifacts → (on failure) annotate build errors
```

Lint, unit tests and both assemblies run in a single Gradle invocation with `--continue`, so
one run reports every compile error in the repo instead of stopping at the first failing task.

Verification asserts each APK is non-empty and contains `AndroidManifest.xml` and
`classes.dex`, then dumps badging with `aapt2`.

If the job fails, the last step parses the Gradle output and publishes the compiler errors as
check **annotations** (also printed to the log), so the root cause is visible on the run page
without downloading the full log.

Artifacts produced: `sufyan-harness-debug-apk`, `sufyan-harness-release-apk`, `reports`.

Download from the **Actions** tab → latest **Build APK** run → **Artifacts**.
