# Bot Link

Phone app for an AI-bot note pipe (Grok Bot and other suite bots). It sits **on top of FlipSend**. It is not FlipSend, and it does not replace FlipSend’s file transfer.

- Display name: **Bot Link**
- Android package: `org.stellartimelock.botlink`
- FlipSend package (required, separate app): `org.stellartimelock.stellarsend`

FlipSend stays phone ↔ PC files and text. Bot Link is how Grok Bot, Accounts, and The One send **short encrypted notes** through that same pairing world — with **Approve on the phone** before anything acts for you.

## What to install

1. **FlipSend** on the phone (and its Windows app, if you use it for files). Bot Link will refuse to run a session until FlipSend is present.
2. **Bot Link** — from F-Droid, or build it from this repo.
3. **PC companion** — one step. Windows: double-click `companion/BotLink.bat`. macOS or Linux: `./companion/BotLink.sh`. The first run asks for the `BL-` code (and you can pass `--phone` for same-Wi-Fi). Later runs remember it.

```bash
# Phone APK
export ANDROID_HOME="$HOME/android-sdk"
./scripts/build-apk.sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# PC, one step
./companion/BotLink.sh --pair BL-XXXX-XXXX-XXXX-XXXX --phone 192.168.x.x
```

On the phone the home is the **AI room (Beta)**, Allow, and the pairing code. **Settings** holds approvals when there is something pending or approved, the relay status, and the agent spend cap ($1 a call, $5 an agent each month). x402 is on: StellarTimeLock signs via SEP-7, then the companion submits the signed XDR to Horizon. `paid` is true only after Horizon accepts. Bot Link never holds keys. Companion saves live in `StellarTimeLock/backups/Bot Link/`.

Share a FlipSend APK to the phone over Bot Link (same Wi-Fi or the public relay — never FlipSend’s file room):

```bash
./companion/BotLink.sh --send-apk /path/FlipSend-x.y.z-main.apk
```

Windows: `companion\BotLink.bat --send-apk path\FlipSend-x.y.z-main.apk`. The phone installs only if the package is `org.stellartimelock.stellarsend`, the cert SHA-256 starts with `7994727d`, and `versionCode` is higher than the installed copy. Never downgrade. Never uninstall. One Install tap.

To reinstall another Stellar suite app whose copy is on the wrong key (for example Market), send it with `kind=replace`:

```bash
./companion/BotLink.sh --replace-apk /path/Market.apk
./companion/BotLink.sh --auto /path/Market.apk
```

`--replace-apk` accepts only Stellar suite packages (`org.stellartimelock.`, `com.stellartimelock.`, `app.stellar.`) whose incoming cert SHA-256 starts with `fac61745` (house) or `7994727d` (FlipSend). Same cert: upgrade, higher `versionCode` only. Different cert: Bot Link raises the system Uninstall confirm immediately (and a notification if it is not in the foreground), then starts Install after `PACKAGE_REMOVED`. Android still requires those two confirms. Phone data for that app clears; `StellarTimeLock backups/<App>` is kept. `--auto` asks the phone for the installed cert + `versionCode` and picks `--send-apk` vs `--replace-apk`.

Same Wi-Fi is direct. Otherwise both sides use FlipSend’s relay at `wss://stellar-send-relay.onrender.com/v1`: a JSON join (`role` `listen` on the phone, `dial` on the PC), a quota meter, then SSND frames whose payload is ciphertext. The room hash is Bot Link’s, not FlipSend’s file room.

Grok Bot talks to the companion on loopback only:

```http
POST http://127.0.0.1:18765/v1/notes
Content-Type: application/json

{"from":"grok-bot","kind":"act","text":"Send the draft to Alice?","wantsAction":true}
```

A summary for the AI room:

```http
POST http://127.0.0.1:18765/v1/room
Content-Type: application/json

{"from":"grok-bot","summary":"Agreed to send the draft.","html":"<p>Alice: …</p><p>Bot: …</p>"}
```

The response includes `url` (`http://127.0.0.1:18765/v1/room/{id}`), an HTML page of that transcript. The phone shows the same blurb with **Open**.

Then poll `GET /v1/notes/{id}` until `status` is `approved` or `denied`. Do not act before Approve.

Spend check (x402 on; wallet signs):

```http
POST http://127.0.0.1:18765/v1/spend
Content-Type: application/json

{"from":"grok-bot","cents":40,"reason":"lookup"}
```

Over $1 a call or $5 an agent this month, that returns 403 and nothing is sent. A blank `payTo` (and the spec example G-address) is refused — set `--pay-to` / `BOTLINK_X402_PAY_TO`. Inside the cap the response includes the x402 v2 + SEP-7 signing request. After the wallet signs, the companion POSTs the XDR to Horizon. Act only when `status` is `approved` **and** `paid` is true.

## Signing

No signing key is in this repo. Debug builds use the normal Android debug key. Release builds are unsigned unless you set `BOTLINK_KEYSTORE` (and `BOTLINK_KEYSTORE_PASS`, `BOTLINK_KEY_ALIAS`, `BOTLINK_KEY_PASS`) in the environment or in an untracked `keystore.properties`. F-Droid builds are signed by F-Droid.

## Tests

```bash
./gradlew :app:testDebugUnitTest
cd companion && python3 -m unittest
```

## Docs

Scopuly x402 / SEP-7 request: [docs/x402-scopuly.md](docs/x402-scopuly.md). Contributing: [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Bot Link is free software: GPL-3.0-or-later. See [LICENSE](LICENSE). Contact: stellartimelock@gmail.com.
