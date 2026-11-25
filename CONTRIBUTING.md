# Contributing to Smart Campus Door Unit

Thank you for helping. This is the Android app of the Smart Campus door terminals (Famoco, Android 6 and later).
[`AGENTS.md`](AGENTS.md) is the full guideline; this page is the short version.

## Before you start

- Open or pick an issue first for anything larger than a typo.
- The terminal speaks the protocol of the [central server](https://github.com/Epitech-Hub-Marseille/Smart-Campus-Central-Server) to the letter.
  A protocol change starts there and needs a matching change here.
- Some behaviour is a safety decision (what the door does during an outage or an emergency). Do not pick a default
  silently: raise it in the issue.

## Set up

Needs JDK 17 and the Android SDK (platform 35, build tools 35).

```bash
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/Android/Sdk
./gradlew :core:test            # unit tests, JVM, fast
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

`core/` is a Kotlin library with no Android dependency (handshake, encrypted channel, protocol, door state, controller).
`app/` is the Android app (Keystore identity, Compose screens, kiosk mode, audio). The end-to-end test against a real
server only runs when `SC_E2E_URL` is set, see the README.

## Check your work

1. `./gradlew :core:test` and `./gradlew :app:assembleDebug` pass before you open the PR.
2. Logic goes in `core` with a JUnit test. Crypto and handshake code must test tampering and replay. State machines are
   tested with a fake clock and fake players.
3. For anything seen or heard, run it on an emulator against a dev server and say what you could not check (sound, real
   hardware, brightness). Test landscape **and** portrait for any screen change.

## Conventions

- **Android 6 (API 23) is the minimum.** Do not use newer APIs in `app` or `core` without a fallback (for example use `B64`, not `java.util.Base64`).
  `java.time` is fine. Test on an API 23 emulator when you touch crypto, storage, audio or kiosk mode.
- Kotlin official style, explicit types on public API, `val` over `var`, no `!!` outside tests unless the invariant is documented.
- Coroutines: `suspend` for I/O, nothing blocking on the main thread, never swallow `CancellationException`.
- UI is Compose with tokens in `ui/Theme.kt`. The door screen is read from a distance: large type, high contrast, no
  decorative motion. The emergency screen shows only the door code, the emblem, the title and the procedure.
- The private key never leaves the Keystore and is never logged. The terminal trusts the pinned server key, not TLS alone.

Allowed commit scopes: `core`, `app`, `crypto`, `protocol`, `audio`, `state`, `net`, `ui`, `build`, `docs`, `deps`.

## Commits and pull requests

- Branch from `main`: `<type>/<short-kebab-description>`, for example `feat/live-announcement`. Never push to `main` directly.
- [Conventional Commits](https://www.conventionalcommits.org/) for every commit and PR title: `<type>(<scope>): <subject>`. Types: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`. Subject in the imperative, lowercase start, no trailing period, 72 characters at most.
- One logical change per commit. Do not mix a refactor with a behaviour change.
- Explain *why* in the body when it is not obvious. Link issues with `Closes #12` or `Refs #7`.
- A PR says what changed and why, how you verified it, and what you could not verify (sound, real hardware, a browser). Keep it small enough to review.
- `main` is protected: a PR needs one approving review.

## Security

Never commit secrets: `.env` files, private keys, certificates, tokens. Only `.env.example` is tracked. Never log or return passwords, hashes, session tokens or key material. To report a vulnerability, do not open a public issue: use the repository's **Security** tab (private vulnerability reporting) or contact a maintainer privately.

## Licence of your contribution

This project is released under the [PolyForm Noncommercial License 1.0.0](LICENSE). By opening a pull request you agree that your contribution is licensed under the same terms.

## Conduct

Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md).

