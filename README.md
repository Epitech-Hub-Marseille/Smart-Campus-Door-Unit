# Smart Campus Door Unit

Android app for the door terminals of Smart Campus (Famoco, Android 6 and later). A terminal is linked to a
room and shows its name, its status (free, occupied, closed, works, or a custom one), announcements with their
priority, and a clock. It is managed from the console of
[SmartCampus-CentralServer](https://github.com/liam-colle/SmartCampus-CentralServer).

```
core/   Kotlin library, no Android dependency: handshake, encrypted channel, protocol, door state,
        controller, enrollment, signed asset download. Unit tested and tested against a real server.
app/    Android app: Keystore identity, Compose screens, kiosk mode, audio playback.
```

## Build

Needs JDK 17 and the Android SDK (platform 35, build tools 35).

```bash
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/Android/Sdk
./gradlew :core:test              # unit tests
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
```

## First boot

1. The installer tells the terminal which server to trust, in one of three ways:
   - **Scan the QR code** from the console (Devices, Provision a device). It carries the address and the key, so the
     key never crosses the network on the way to the terminal. Needs a camera.
   - **Fetch the key**: enter the address, tap "Fetch the key from the server", compare the fingerprint with the one
     in the console and tap "Trust this server". This is trust on first use, and the fingerprint is computed on the
     terminal from the key, not taken from the server's answer.
   - **Paste the key** (base64 SPKI, `secrets/server-key.pub`).

   Whichever way it came, the terminal pins the key from then on: it only trusts a server that proves it owns that
   key, and TLS alone is not trusted as proof of identity.
2. The terminal creates its own P-256 key in the Android Keystore (the private key can sign but never be read)
   and sends a connection request with the public key. The screen shows the key **fingerprint**.
3. An admin or operator compares the fingerprint in the console (Devices, connection requests) and approves.
4. The terminal connects, receives its full desired state and shows the room. From then on it boots straight
   into its last known state, network or not.

A revoked terminal wipes its key and state and asks again from step 1.

## What it does

- **Room screen**: layouts standard, compact and split (split stacks the panels on a portrait screen), dark or light theme, accent colour, and the elements the
  console chose to show (room name, building, floor, capacity, clock, announcements).
- **Status**: label, colour and icon come with each status, so a status created after installation just works.
- **Announcements**: info, important, urgent, each with its own style. They hide by themselves at their expiry
  time, using the server clock offset, so a wrong terminal clock does not keep one on screen.
- **Offline mode**: the last state is written to disk after every change; the screen shows it with an
  "offline" badge while the link retries with a 1 to 60 s jittered backoff.
- **Night sleep**: inside the configured window the screen goes black and the backlight to its minimum.
  The terminal stays connected, so an announcement can still wake it.
- **InterCom**: `audio.play` carries an asset id, SHA-256 and size. The terminal downloads the file with a request
  signed by its key, checks the hash, caches it and plays it at the volume set for this device. During quiet hours
  it acknowledges the broadcast without playing it.
- **Delivery**: every command is acknowledged once; the ids of the last 200 are remembered on disk, so a replay
  after a reconnect or a restart never runs twice.

## Decisions

- **Native Kotlin and Compose** instead of the React Native app in the first design: a kiosk needs a small runtime
  and direct access to the Android Keystore. The protocol is the same, so nothing changes on the server.
- **`core` has no Android dependency**, so the cryptography, protocol and controller run in plain JUnit tests and in
  an end to end test, without an emulator.
- **Cleartext HTTP is allowed** in the manifest because identity and confidentiality come from the handshake, not
  from TLS. Production deployments should still use https.
- **Night sleep dims instead of switching the panel off**: turning the screen off needs device admin rights. A
  Famoco device owner setup can replace this with a real screen off.
- **Audio commands expire after five minutes** on the server, so a terminal that comes back late never plays stale audio.

## Reservations

`reservations.set` carries the next bookings of the room (at most five, kept in the digital twin). The door shows the one that is
on now ("Now: ...") or the next ("Next: ..."), below the status, unless the console hid it (`show.nextReservation`). Times are compared
with the server clock, past bookings drop off by themselves, and the list is ignored when it belongs to another room than the one the
terminal is linked to (a moved terminal must not show its old room's bookings).

## Intercom: calls and live announcements

Nothing shows on the door but a barely visible tick at the right edge. Put a finger on the right edge and slide it towards the middle to open the extras menu.
**Intercom** lists the other door terminals (offline ones greyed out); choosing one rings it. The called terminal takes over its screen with **Answer** and **Decline**
and its microphone stays closed until it answers (nobody can open a room's microphone from outside); then both sides talk through the server until either hangs up.
A terminal that is announcing, in an emergency or already in a call declines by itself. **Make an announcement** asks for a card first: the server accepts only an
administrator's or operator's, the same grant as the maintenance menu, and the unit opens an announcement panel instead of the menu. **Go on air** sends `live.start`;
the microphone opens only when the server answers `intercom.result` ok, then 100 ms slices of 16 kHz mono PCM go out as `live.chunk` in order until **Stop**. The server opens the stream with
the manual override chime and passes the voice to every *other* screen: this terminal never plays its own microphone, so it cannot howl. An announcement from someone else plays on the
alarm stream (`AudioTrackLive`) and an alarm steps back while it speaks; a call plays on the voice-communication stream, the one Android's echo canceller listens to. The microphone
permission is asked the first time it is needed; an emergency or losing the link closes the microphone at once.

## Emergency alerts

`emergency.set` replaces the whole screen with a terminal-style bulletin, the same as on the Faculty screens: the system's name and the door code, the
alert in large yellow on the dark colour of its kind, what is happening, the order to follow (`EVACUATE NOW`, `STAY INSIDE`...), three numbered steps
and hazard stripes (two columns on a wide screen). `emergency.clear` restores the door screen. Decisions:

- **A read-out is heard over the alarm.** While an urgent announcement (the spoken alert) plays, the alarm drops to a fifth of its level (`AlarmPlayer.duck`) and comes back when it ends. The read-out plays at full volume on the alarm stream, so a low media volume cannot make it a whisper.
- **The alarm is synthesised on the device** (`AlarmSynth`), one loop per kind, played on the alarm stream through
  `AudioTrack`. No file to download, so it works offline and starts at once. The alarm stream is pushed to its
  maximum and the level is applied on the track, so a low system volume cannot silence a real alert.
- **A real alert ignores the volume setting and quiet hours.** A power cut is a soft chime (35%). A **test** shows a
  TEST tag, plays at the volume chosen in the console and stays silent in quiet hours.
- **A read-out is urgent too.** An `audio.play` with `urgent` (the voice reading the instructions) plays at full volume and ignores
  quiet hours, like the alarm. Any other broadcast still obeys both.
- **The end of an alert can carry a message.** `emergency.clear` with a `message` shows an "Alert over" screen with that text for the time
  the operator chose in the console (10 s to 15 min, two minutes by default; it also wakes a sleeping panel), then the door screen returns. A new alert replaces it, and it survives a restart inside the window.
- **It survives restarts and outages.** The emergency is persisted; after a reboot the alarm resumes. On reconnect the
  full desired state decides: no emergency in it means the alert ended, even if the clear was missed.
- **It overrides night sleep** and sets the brightness to full.
- A clear for an alert that a newer one replaced does not end the newer one.

## Cards and the maintenance menu

The NFC antenna is read in reader mode while the app is in front (`NfcCardReader`). Only the card's serial number is read, never its
content. Each tap becomes an `event.badge` and the server answers with `card.result`.

What the person sees and hears:

- **Sounds** (system tones, `ToneGeneratorCards`): a short beep when the card is read, a rising pair when it was accepted, a low buzz
  when it was refused or could not be checked. They follow the volume setting and stay silent in quiet hours and during an emergency.
- **Confirmation screen** for 1.5 s after each read: "Card read, checking", then "Access granted" (green check, with the name) or
  "Access denied" with the reason (card not registered, disabled, too many cards, offline). A card whose number was read is never
  reported as "not recognised"; an unregistered card is "not registered". The screen comes before the maintenance menu opens.
- **Maintenance menu** for an enabled administrator's or operator's card, closed after 60 s without a touch: Information (terminal,
  server, connection, key fingerprint, version), Set room status (the statuses the server offers, applied to the terminal's room),
  Restart app, and Revoke this terminal (administrators only, with a confirmation). The answer of the server is shown under the buttons.
- While an admin runs a card registration on this terminal, cards are collected by the server, the confirmation reads "Card read, added
  to the registration" and the menu stays shut. The mode ends by itself at its expiry time, even offline.

Every menu button is a `maintenance.action` that the server checks against the card that opened the menu (still valid, person still
enabled, administrator for revoke) and writes to the audit log, so the screen is a convenience and not the permission.

**Restart app is not a hardware reboot.** An ordinary app cannot reboot the terminal. The process is killed and Android starts the home
app again (an alarm restarts it on a terminal where the app is not the home app). A real reboot needs device-owner or system rights, for
example through the Famoco MDM.

**Decision: the unit never decides who is an administrator.** It forwards the serial number and obeys the answer. Alternative rejected:
keeping a list of privileged cards on the unit, which works offline but keeps opening the menu for someone removed from the group.
Mitigations: the menu is closed when the connection drops, when an emergency starts (and cannot be opened during one), and an answer
that does not match a recent tap of ours, or is older than 10 s, is ignored. A card resting on the antenna counts as one tap for 2 s.

**Known limitations.** A serial number is not a secret and can be cloned, so the menu is only as trustworthy as the card. Some phones and
newer cards present a random UID that changes on every tap; those cannot be enrolled. Not tested on a real NFC reader (the emulator has
none): the logic is covered by `CardAccessTest`, the antenna itself needs a Famoco or any phone with NFC.

## Android versions

The minimum is Android 6 (API 23), the first version where the Keystore signs with ECDSA. What that took:
`java.util.Base64` and `AlgorithmParameters("EC")` do not exist there, so the core uses `B64` and the written-out P-256
parameters (a test checks them against the platform's curve); `java.time` is backported by core library desugaring; the
QR scanner pins an older zxing core; and before Android 11 the kiosk mode sets the hide flags again whenever the system
clears them. Android 6 shows a one-time "Viewing full screen" tip the first time the app goes full screen.
Checked on an API 23 emulator: provisioning, key fetch, enrollment, handshake, status, announcement, clock and the
emergency alarm (an active alarm-stream track at the test volume).

## Tests

`./gradlew :core:test` runs the unit tests (RFC 5869 HKDF vector, channel tampering, replay and reflection,
handshake refusal, state, expiry, time windows, controller behaviour including quiet hours and restarts).

The end to end test talks to a real server and is skipped unless `SC_E2E_URL` is set. It enrolls a terminal,
approves it through the admin API, completes the handshake, follows display, announcement and audio commands,
downloads a sound with a signed request, and checks that revocation stops the link:

```bash
SC_E2E_URL=http://localhost:3000 \
SC_E2E_SERVER_KEY=<base64 of secrets/server-key.pub> \
SC_E2E_ADMIN_EMAIL=admin@smartcampus.local SC_E2E_ADMIN_PASSWORD=changeme123 \
./gradlew :core:test --tests '*EndToEndTest*'
```

## Run on an emulator

Install the emulator and a system image (`sdkmanager "emulator" "system-images;android-34;google_apis;x86_64"`),
create an AVD (`avdmanager create avd -n door-test -k "system-images;android-34;google_apis;x86_64" -d pixel_tablet`),
boot it (it needs KVM, and `-no-window` works for a headless machine) and `adb install` the debug APK. The emulator
reaches the host at `10.0.2.2`, so the server address is `http://10.0.2.2:3000`.

Checked this way against a local central server: provisioning and enrollment (the fingerprint on screen matches the
console), live status changes, split layout with the light theme, urgent announcement, audio download and playback
at the configured volume, offline badge and a cold restart without network, automatic reconnection (about 6 s),
night sleep (black screen, the device reports `screen: sleep`) and revocation (key and state wiped, back to setup).
Also checked on the API 34 tablet emulator against the dev stack: next reservation (landscape and portrait), a real emergency started
through the server's two-step confirmation, the end-of-alert screen and its return to the door after two minutes, and an urgent
voice read-out generated by the GPU engine, downloaded, hash-checked and played during all-day quiet hours.

## Not covered yet

Not tried on a real Famoco terminal, and the alarm loops have not been judged on a real speaker (on the emulator a
started `USAGE_ALARM` track was confirmed, and the loops are tested for loudness and a seamless loop point). Scanning the
provisioning QR code was not tested with a real camera feed (the emulator has no QR to show); the code's format is
tested against the console's encoder. A managed configuration (MDM) is a further option. No boot receiver: the app is
meant to be set as the home app of the terminal.

## Themes

The server can give this unit a visual theme (`theme.set`, part of the desired state): a few JSON tokens, never code. The app reads the colours
(background, cards, text, secondary text, accent), light or dark mode, a font family from a fixed list (system, sans, serif, mono, condensed), a text
scale (0.8 to 1.4) and a corner radius. What a theme leaves out keeps the app's own look. Every value is read defensively (`core/.../theme/ThemeResolver.kt`):
a bad colour, an unknown font or a silly scale falls back instead of drawing something unreadable. The CSS a theme may carry is for the Faculty screens and is
ignored here. **Emergency alerts keep their colour, wording, alarm and white text.** A theme can only dress them through its `emergency` section (read by `resolveEmergency`):
the alert's background (only if white text stays readable on it, a contrast of at least 4.5:1), the theme's fonts, a plain yellow band instead of the hazard stripes,
and the corners of the boxes. Anything unreadable gives the default alert. The unit reports `themeId` and `themeVersion` in its state.

A theme may also name font files for three slots: `body` (all text), `heading` (room name and status) and `mono` (room code and clock). The controller
downloads each one like a sound (signed request, SHA-256 checked, cached by asset id, so after a restart they load without a network) and the
screen uses it once verified. Android reads TrueType and OpenType only: a WOFF or WOFF2 font is not downloaded, and a slot without a usable file keeps
the font family of the tokens.
