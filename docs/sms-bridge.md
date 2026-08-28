# sms-bridge

Forwards received messages to a machine the user controls, over a private WireGuard network
(Tailscale), so that software running there can read 2FA codes and — later — manage messages
from a desktop.

**Off by default.** The forwarder is inert unless a config file is present. Installing a build
with this branch does not by itself put QUIK on the network.

## Why forward everything rather than filter here

The obvious alternative is to forward only messages from a known set of 2FA senders. It was
rejected. It requires the phone to know which process on the far side is waiting for what, the
sender list needs maintaining as services are added, and **an unrecognised sender is silently
missed** — which is the failure case that matters, because it is invisible.

Forwarding unconditionally removes the coordination problem instead of solving it. This side
becomes stateless: receive → POST → done. The "am I waiting for a code" question is answered
entirely on the receiving machine, which is where the thing that needs the code already runs.

## Configuration

Placed in the app-specific external directory, so it installs with a plain `adb push` and needs
no extra permission, no settings UI, and no exported receiver — an exported config receiver
would be a code-injection surface on a device that handles 2FA.

```
adb push sms-bridge.json /sdcard/Android/data/dev.octoshrimpy.quik.debug/files/
```

```json
{
  "enabled": true,
  "endpoint": "http://<host>:8090/sms",
  "token": "<bearer token shared with the receiver>"
}
```

Absent or malformed config means disabled. Bad config must never break message receipt.

## Reliability

`ForwardMessageWorker` runs under WorkManager with a network constraint and exponential
backoff, and is enqueued **independently** of `ReceiveSmsWorker` rather than chained — a bridge
failure can never delay or block notification of a received message. The SMS app is the
product; the bridge is an add-on.

WorkManager persists its queue across app death and device reboot, so a message received while
the receiving host is unreachable is delivered when it returns rather than lost. This is the
normal case rather than an edge case in the setup this was written for.

## Wire format

One JSON object per message. `id` **must be stable across retries** or every retry duplicates
on the receiving side; the Realm primary key provides that.

| field | notes |
|---|---|
| `id` | `"sms:<rowid>"` / `"mms:<rowid>"` — the dedup key |
| `dir` | `"in"` |
| `ts` | epoch **seconds** of the message timestamp |
| `addr` | sender address |
| `body` | text; for MMS, gathered from `text/*` parts when `body` is empty |
| `kind` | `"sms"` or `"mms"` |
| `sub` | subscription id, `-1` if unknown |

The receiver derives any 2FA code itself. Codes are never extracted, logged, or transmitted as
a separate field by the phone.

## `android.permission.INTERNET`

Upstream QUIK deliberately ships with this permission commented out and has no network access
at all. This branch enables it. That is a real change to the app's privacy posture and is the
reason the forwarder fails closed when unconfigured.
