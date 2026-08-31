# Wisp

A two-person video calling app. Native Android, no chat, no group calls — a
list of people you've added by their device code, tap one to call them.

The backend is entirely Cloudflare: a Worker with one Durable Object for
signaling, and Cloudflare Realtime for TURN. There is no database and no
server to run.

```
Android app  ──wss──►  Worker  ──►  Signaling Durable Object (routing table)
     │                    │
     │                    └──►  Cloudflare Realtime TURN API (mints ICE creds)
     │
     └────────── WebRTC P2P media, STUN first, TURN only as fallback ─────────►
```

## Layout

| Path | What's in it |
| --- | --- |
| `app/` | The Android app (Kotlin, plain Views, minSdk 26) |
| `worker/` | Cloudflare Worker: signaling + TURN credentials |
| `.github/workflows/android.yml` | Builds and signs the APK, publishes a Release |
| `.github/workflows/worker.yml` | Deploys the Worker with `wrangler` |
| `scripts/setup-repo.sh` | One-time keystore + secrets + repo settings setup |

## Getting it running

### 1. GitHub

Two ways, pick one.

**From a terminal:**

```bash
./scripts/setup-repo.sh
```

Generates the release keystore (outside the git tree), uploads the four signing
secrets, gives Actions write permission so it can publish releases, and turns
off Wiki/Projects/Discussions.

**From the browser only:** run the **Generate signing key** workflow once from
the Actions tab. It produces a downloadable artifact holding the keystore, its
password and a `READ-ME-FIRST.txt`; you then paste four values into
*Settings → Secrets and variables → Actions*. Set workflow permissions to
read/write under *Settings → Actions → General* so releases can be published.

Either way: back up the keystore file and its password. Every build has to be
signed with that same key or Android won't treat a new APK as an update to the
installed one. Until those secrets exist, release builds fall back to a debug
key and the workflow deliberately publishes **no** Release — installing a
debug-signed APK would block every later signed update.

### 2. Cloudflare

```bash
cd worker
npm install
npx wrangler login
npx wrangler deploy                       # first deploy creates the Worker
# Optional. Skip these and the Worker falls back to a free public relay.
npx wrangler secret put TURN_KEY_ID       # from Realtime -> TURN Keys
npx wrangler secret put TURN_KEY_API_TOKEN
```

Then, so CI can redeploy on every push to `main`:

```bash
gh secret set CLOUDFLARE_API_TOKEN
```

Browser-only equivalent: add `CLOUDFLARE_API_TOKEN` (and `CLOUDFLARE_ACCOUNT_ID`
if your token can see more than one account) under *Settings → Secrets and
variables → Actions*, then run the **Worker** workflow from the Actions tab.
The two TURN secrets can be added in the Cloudflare dashboard instead, under
*Workers & Pages → wisp-signaling → Settings → Variables and secrets*.

The TURN key ID and token stay as Worker secrets. They are never sent to the
app — the app asks the Worker for credentials at the start of each call.

### 3. Point the app at the Worker

`app/src/main/java/com/wisp/app/Config.kt`:

```kotlin
const val SIGNALING_HOST = "wisp-signaling.<your-subdomain>.workers.dev"
```

Push to `main`. The Android workflow builds a signed APK and attaches it to a
GitHub Release. Install it on both phones, and each of you adds the other's
code once.

## How it works

### Identity and contacts

On first launch the app generates an 8-character code from an alphabet with no
`O`/`0`/`I`/`1`, and keeps it in SharedPreferences forever. That code is the
device's whole identity. Contacts are a JSON array in the same
SharedPreferences file — no database.

Adding someone doesn't add you on their side, the same way saving a phone
number doesn't. You each add the other once.

The code is an identity, not a secret. Anyone who knows a code can ring that
device — which is the intended trade-off here, not an oversight (see *Out of
scope*).

### Signaling

One Durable Object holds the routing table. It uses the WebSocket Hibernation
API (`ctx.acceptWebSocket`), so while nobody is calling, the object is evicted
from memory and costs nothing — but the sockets stay open, so there's no cold
start when a call finally arrives.

Because memory doesn't survive hibernation, per-socket state lives in the
socket itself: the device code is the accept *tag* (which is how
offer/answer/ICE find a device), and the list of codes it's watching is its
*attachment*.

| Message | Direction | Effect |
| --- | --- | --- |
| `watch {codes}` | app → DO | Registers interest; replies with current presence for each code |
| `presence` | DO → app | Pushed when a watched code connects or disconnects |
| `offer` / `answer` / `ice` / `bye` | app → DO → app | Forwarded to `to`, stamped with `from` |
| `ice-restart` | app → DO → app | Callee asking the caller to renegotiate |
| `unavailable` | DO → app | The target has no live socket |

### TURN

`GET /turn-credentials` hands the app an `iceServers` array it passes straight
into `RTCConfiguration`.

TURN is **optional**. Set `TURN_KEY_ID` and `TURN_KEY_API_TOKEN` and the Worker
mints Cloudflare Realtime credentials with a 24-hour TTL — long enough that no
call needs a mid-call refresh. Leave them unset (Cloudflare Realtime asks for a
payment method even on its free tier) and the Worker returns public STUN plus a
free community TURN relay instead, so calls still have a fallback path at no
cost. Either way STUN is tried first and a relay is only used when the two
phones cannot reach each other directly; WebRTC encrypts media end to end, so a
relay operator carries packets it cannot read.

The free relay is a shared service with no uptime guarantee. If calls connect on
WiFi but not on mobile data, that relay is the thing to replace — by adding a
Cloudflare TURN key, or by pointing `PUBLIC_TURN` at another provider.

### Media

- Hardware codecs via `DefaultVideoEncoderFactory` (battery).
- Capture 720p, framerate capped at 30.
- `degradationPreference = MAINTAIN_FRAMERATE` with a 150 kbps floor and a
  2.5 Mbps ceiling. WebRTC's own bandwidth estimator drops resolution when the
  link is poor — that logic is deliberately not reimplemented here.
- Muting the mic detaches the track from its sender rather than just silencing
  it, so WebRTC stops paying for an audio stream at all and the bandwidth
  allocator gives that headroom to video. The video ceiling is set high enough
  for the encoder to actually use it.
- Volume is a gain applied directly to the remote WebRTC audio track
  (`setVolume`, 0.0–2.0), not the system stream — which is how the slider gets
  below and above what the Android in-call volume floor allows. The UI shows
  0–200%.

### Call drop protection

ICE `DISCONNECTED` is transient — a lift, a tunnel, a lift-and-tunnel — and is
never treated as a hangup:

1. Show a "reconnecting…" overlay and start a 90-second local grace timer.
2. If the signaling socket is alive, trigger an ICE restart over it and keep
   retrying every 8 seconds. Only the side that made the original offer
   renegotiates; the other side sends `ice-restart` to ask for one, so the two
   ends can't collide.
3. If the socket is also down, reconnect it first, then restart ICE.
4. When the timer expires, close the call locally and tell the other side if
   signaling is still reachable. If it isn't, their own identical timer lands
   them in the same place.

`FAILED` gets an immediate restart attempt but the same full grace window.

### Network switching

A `ConnectivityManager.NetworkCallback` watches for the active network
changing. On a switch the app reconnects the signaling socket if it's down and
triggers an ICE restart straight away — there's no reason to sit through the
grace timer when we already know the path changed.

Before placing a new call the app checks the socket is open, reconnects first
if it isn't, and only then sends the offer.

### Background reliability

- A `START_STICKY` foreground service holds the signaling socket for as long as
  the app is alive. It runs as `specialUse` while idle and escalates to
  `camera|microphone` when a call starts, which is what Android 14+ requires
  before touching either device. The notification's *Answer* action opens the
  call screen rather than commanding the service directly, because that
  escalation is only granted while the app is visible.
- `BootReceiver` restarts the service after a reboot or an app update.
- First launch asks for the battery-optimization exemption, and on Android 14+
  for the full-screen-intent grant separately from the manifest declaration. If
  full-screen intents are denied, incoming calls degrade to a heads-up banner
  instead of failing.
- Samsung, Huawei/Honor and Xiaomi get a deep link to the settings screen where
  their background killer can be told to leave Wisp alone, with the app-info
  page as a fallback when the deep link doesn't exist on that firmware.

## Building locally

CI does the real builds, but if you want one:

```bash
ANDROID_HOME=/path/to/android-sdk ./gradlew assembleRelease
```

Without `KEYSTORE_FILE` set, the release build falls back to the debug signing
key so the build still completes — that APK will *not* install as an update
over a CI-signed one.

## Out of scope

Chat, group calls, real access control beyond code-as-identity, and local
network/Bluetooth discovery. Calls work over the internet, not just in the same
room.
