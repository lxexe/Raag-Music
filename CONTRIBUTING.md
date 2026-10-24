# Contributing

Build with JDK 17 and the checked-in wrapper as described in the README. No account or API keys are required.

For bugs, provide app/Android versions, safe reproduction steps and expected/actual behavior. Public video IDs are useful for provider failures. Remove names, library backups, cookies, tokens, device identifiers and signed media URLs from logs. Discuss substantial features in an issue first; small fixes can go directly to a pull request.

## Changes and checks

- Keep changes focused and follow the surrounding Kotlin/Compose style.
- Preserve the application ID and stable storage/cache keys unless you include a migration plan.
- Keep network/storage work off the UI thread and preserve cancellation and bounded retries.
- Add regression coverage for behavior changes. Synthetic test records belong in test source sets.
- Check light, dark and AMOLED appearance when changing UI.

```sh
python3 scripts/public_source.py
python3 -m unittest discover -s scripts -p 'test_*.py'
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Instrumented tests need an emulator/device and may access live providers:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Report what you tested and any provider failures/skips. Optimized playback also needs release/preview checks because R8 and desugaring can change behavior. Enable optional benchmarks with `-PperformanceTools=true`.

## Source hygiene

Do not commit credentials, SDK paths, signing keys, APKs, personal listening data, old logs or generated screenshots. Keep documentation linked from the README. Update `public-source.json` for new top-level paths. For new dependencies, pin versions and update the runtime inventory and license notices; CI checks that the inventory matches the resolved graph. After editing legal/privacy documents run `python3 scripts/sync_legal.py`.

## Dependency update pull requests

Dependabot changes dependency versions but does not regenerate this project's inventory or review its bundled notices. A successful build can therefore still fail the **Check dependency inventory** step. On the dependency update's branch, run:

```sh
./gradlew -I scripts/runtime_inventory.init.gradle :app:writeRuntimeInventory
git diff -- LICENSES/runtime-dependencies.json
```

Review all changed modules, including transitive dependencies. Update versions, source links and any changed license requirements in `THIRD_PARTY_NOTICES.md` and `LICENSES/`, then run `python3 scripts/sync_legal.py`. If the optional Firebase build is affected, also regenerate its inventory with `-PfirebaseAnalytics=true` using your local Firebase configuration. Commit the reviewed inventories and notices to the same PR branch and rerun checks. CI provides a `dependency-inventory-review` artifact when the inventory check fails; it is a review aid, not automatic license approval.

Keep the inventory check enabled. Regenerating the inventory on `main` without the proposed dependency versions will not fix an update PR. After merging an update, refresh other open dependency PRs against `main` before validating their inventories.

Check Android SDK and Gradle requirements before accepting major updates. In particular, OkHttp 5.4.0 requires compile SDK 36 and 5.5.0 requires 37, while this project uses 35 and AGP 9.0.1. Dependabot defers all OkHttp versions 5.0.0 and above until a separately tested Android toolchain migration; remove that version exclusion when upgrading the toolchain. Review security advisories independently of this compatibility exclusion. Kotlin Compose plugin versions 2.4 and above are also deferred because the current bundled D8/R8 reports unsupported Kotlin metadata. Revisit both exclusions with a coordinated toolchain upgrade; changing the inventory cannot resolve toolchain incompatibilities.

By contributing, you license your original contribution under GPL-3.0-or-later and retain its copyright. Include compatible licenses and attribution for third-party material.
