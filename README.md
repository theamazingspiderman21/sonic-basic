# S.O.N.I.C Basic

Voice-only Android phone assistant. This edition is the phone-control half of S.O.N.I.C:
no business advisor, no data analysis, no web research. (The full version lives in the `sonic` repo.)

Installs side by side with the full app (id `com.sonic.assistant.basic`). Only run one of them listening at a time.

## Works with NO API key (free)
Needs only a free Picovoice AccessKey for the wake word (default word: "Jarvis").
- "Open WhatsApp" / "Open YouTube and search lo-fi music"
- "Send Rahul on WhatsApp that I'll be late" / "Message Priya saying happy birthday" (say "SMS" for a text)
- "Call Rahul"
- "Set an alarm for 6:30 am" / "Set a 10 minute timer"
- "Fastest route to Koramangala" / "Take me to the airport" (live-traffic comparison needs a Google Maps key; otherwise opens Google Maps)
- "Book an Uber to the airport" or "...tomorrow at 5 am" (asks you to say "confirm"; Uber pre-fills the drop, Ola only opens)
- "Share this with Rahul on WhatsApp"

## Optional: Claude key
With an Anthropic key, requests the basic matcher doesn't understand go to Claude, which can chain phone tools,
read the screen and guide you step by step ("watch my screen and guide me").

## Build
Push to GitHub: the Actions tab builds `app-debug.apk` (artifact `SONIC-debug-apk`).
Setup screen buttons, top to bottom: Save keys, permissions, Accessibility, overlay, exact alarms, Start listening.
Set battery to Unrestricted. After a reboot open the app once.

## Status
Never compiled or run on a device by the author; expect small fixes on first build. Cab and share flows are best-effort.
