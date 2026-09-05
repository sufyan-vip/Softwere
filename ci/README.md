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

## What it does

```
setup JDK 17 → setup Android SDK → gradle wrapper → lintDebug
  → testDebugUnitTest → assembleDebug → assembleRelease → verify APKs → upload artifacts
```

Verification asserts each APK is non-empty and contains `AndroidManifest.xml` and
`classes.dex`, then dumps badging with `aapt2`.

Artifacts produced: `sufyan-harness-debug-apk`, `sufyan-harness-release-apk`, `reports`.

Download from the **Actions** tab → latest **Build APK** run → **Artifacts**.
