# `:baselineprofile`

This module generates AnkiDroid's **baseline profile** - a list of classes
and methods ART pre-compiles at install time so cold start is faster.

It has two classes:

- **`BaselineProfileGenerator`** captures a cold-start trace and writes
  `baseline-prof.txt` into `:AnkiDroid`.
- **`StartupBenchmark`** measures cold-start time with vs without the
  profile so you can see the impact.

For results to be valid for comparisons and real-world impact, they must
be run on real hardware devices ideally the same device over time and
representative of typical user hardware. So this is currently not
intended to be used in CI unless CI is connected to real devices (e.g.
Firebase Test Lab or similar).

## Requirements

- Physical Android device, API 28+ (emulators aren't trustworthy)
- USB debugging on, device plugged in

## Regenerate the profile

The Baseline Profile plugin generates an isolated `playNonMinifiedRelease`
target. Its application ID is
`io.github.davelewis360.ankidroid.m3expressive.baselineprofile`, its launcher
name is `AnkiDroid M3 Expressive Baseline Profile`, and it uses debug signing.
It therefore installs alongside production, debug, and the custom `.benchmark`
target without requiring production release credentials. The instrumentation
reads the tested APK ID dynamically, so no application ID argument is needed.

```sh
./gradlew :AnkiDroid:generateBaselineProfile
```

Takes a few minutes. The plugin installs the isolated non-minified target, runs
the cold-start journey (launch app -> dismiss intro screen if present -> wait
for DeckPicker), and writes the result to `baseline-prof.txt`.

## When to regenerate

When the startup path changes meaningfully — new init work in
`AnkiDroidApp.onCreate`, a refactor to `IntentHandler` or `DeckPicker`,
a new dependency wired into `Application`. Otherwise, leave it alone;
the profile is device- and OS-agnostic, one commit covers everyone.

## Config notes

See comments in [`build.gradle.kts`](build.gradle.kts) for the rationale
behind `minSdk`, `missingDimensionStrategy`, the `benchmark` buildType,
and `self-instrumenting`. The KDoc on each class explains the journey
and the measurement modes.
