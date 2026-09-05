<p align="center">
  <img src="presentation/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="SMS & Forward" width="96">
</p>

# SMS & Forward

An Android SMS app that forwards your texts to your own computer, and lets it reply.
A fork of [QUIK](https://github.com/octoshrimpy/quik), which is a continuation of QKSMS.

Your phone stays the phone: it receives and sends every text as before. What this fork adds
is a bridge to a machine you own, so that texts show up on your desktop, replies typed there
go out from the phone, and the 2FA codes that land on your phone reach the agents working on
your computer without you reaching for it. Nothing goes through anyone's cloud. The phone
talks only to your bridge, over your tailnet or LAN, and initiates every connection itself.

The other half lives in [sms-bridge](https://github.com/cortexist/sms-bridge): the small
service that receives the messages, the desktop app, and the skill that teaches coding
agents to use it.

## What it does

- **Forwards every incoming text** to your bridge, with attachments, retrying across resets and
  reboots until it lands. Nothing is filtered on the phone, so a code from a number nobody
  predicted still arrives.
- **Replies from the desk.** A message typed in the desktop app is sent by the phone on its
  default SIM. While you are at the desk, the phone holds a live link and the reply leaves
  within a second; otherwise it goes out on the next poll. If the send fails, the desktop
  learns that too.
- **Talks to your agents.** Agents on your computer can put a message in front of you as a text
  from **AGENTS**, in its own thread in this app. Whatever you type back in that thread reaches
  them. `AGENTS` is not a number, so nothing in that thread can ever reach a carrier.
- **Answers "is the phone home".** The bridge can ask whether the phone is on the same network
  as the computer, so an agent only texts you when you are actually away. No location
  permission: the phone reports only its own wifi address.
- **Looks like your desktop.** Monospace type, sharp corners, the Tokyo Night and Flexoki
  palettes, and a control-shape setting (square, rounded, round, squircle) to match your
  launcher. Made to sit next to an [Omarchy](https://omarchy.org) desktop.

Everything QUIK does, it still does: scheduled messages, backup, speech-to-text, blocking,
archiving, voice messages, pinning, swipe actions, and the rest.

## Setup

1. Run the bridge on your computer and note its address and token (see sms-bridge).
2. Install the APK and make SMS & Forward the default SMS app.
3. In **Settings → SMS bridge**, turn forwarding on and enter the bridge endpoint
   (for example `http://100.64.0.3:8090/sms`) and the token.
4. Nothing else. The live link and the perimeter check work from the phone's own wifi
   address, which needs no extra permission.

The phone polls the bridge every fifteen minutes and whenever it forwards a message; the
bridge never connects to the phone. There is no listener on the phone and no permanent
notification, except the small one while the live link is up.

## Building

```
./gradlew :presentation:assembleDebug
```

The APK lands in `presentation/build/outputs/apk/debug/`. Android 7 (API 24) or newer.

## Privacy

The app has exactly one network destination, the bridge you configure. Message bodies are
never logged. The bridge holds every forwarded text on your disk, so read its notes on
retention before you point it at a shared machine.

## Credits and licence

This is a fork of [QUIK](https://github.com/octoshrimpy/quik) by octoshrimpy, itself a
continuation of [QKSMS](https://github.com/moezbhatti/qksms) by Moez Bhatti. All of the
messaging app is their work; the bridge, the agents thread, the live link and the Omarchy
look are ours. GPLv3, like QUIK. See `LICENSE`.
