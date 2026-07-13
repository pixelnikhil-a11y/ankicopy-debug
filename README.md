# AnkiCopy — gamepad-driven Anki + AI bridge

Standalone Accessibility Service. No MacroDroid/Tasker/AutoInput. Intercepts
gamepad buttons directly and drives AnkiDroid + your AI apps.

## Current button mapping
| Button | Action |
|---|---|
| A, B | Untouched - configure "Show Answer"/"Again" in AnkiDroid's own gamepad settings |
| X | Bring AnkiDroid to fullscreen (collapses split screen back to just Anki) |
| Y | Split screen: AnkiDroid + ChatGPT |
| Select | Split screen: AnkiDroid + Gemini |
| D-pad Up | Controlled smooth scroll up |
| D-pad Down | Controlled smooth scroll down |
| L1 | Copy whole card + explanation prompt -> switch app -> paste -> send |
| R1 | Copy whole card + "apply to scenario" prompt -> switch app -> paste -> send |
| L2 | Untouched - left as AnkiDroid's own menu-navigation button |
| R2 | Close to home |
| Start | Cycle which app L1/R1 send to (ChatGPT -> Claude -> Gemini), toggle status overlay |

## If a button isn't doing what you expect: turn on Debug Mode
Open the app, flip the **Debug mode** switch on. Now press any button that
isn't working as expected. If it's unmapped (falls to the `else` branch),
you'll get a toast showing its real Android keycode, e.g.
`Unmapped key: KEYCODE_BUTTON_C`.

This matters because **gamepads don't all report the same keycode for the
same physical button** - what your controller calls "X" might arrive as
`KEYCODE_BUTTON_X`, `KEYCODE_BUTTON_3`, or something else depending on its
HID mapping and Android's driver for it. If your cycle/scroll buttons
weren't working, this is almost certainly why - the code assumed standard
Xbox-style keycodes (`BUTTON_X`, `BUTTON_Y`, `BUTTON_L1`, etc.), and your
controller may use different ones.

**To fix a mismatch:** note the keycode debug mode shows you for the
physical button you pressed, then open
`AnkiAccessibilityService.kt` -> `onKeyEvent()` and swap the `KeyEvent.KEYCODE_BUTTON_X`
(etc.) constant for whatever keycode debug mode reported for that button.

## Fix for "copies but doesn't send"
This was almost certainly the send-button heuristic failing to find
ChatGPT's actual send button. Two changes address it:
1. **Retries** - it now tries up to 4 times (350ms apart) to find the send
   button after pasting, instead of giving up after one attempt. Some apps
   don't enable Send until a moment after the text lands.
2. **IME-Enter fallback** - if no send button is ever found, it now tries
   simulating the keyboard's "Enter/Send" action directly on the input
   field (`ACTION_IME_ENTER`), which many chat apps treat as "submit" even
   without a visible tappable button matching our hints.

If it *still* doesn't send after this, that means ChatGPT's actual
button/field identifiers don't match any of the hint substrings in
`AiTarget.kt` (`inputHints` / `sendHints`). The most reliable way to find
the real ones: enable Android's **Accessibility Scanner** or **Layout
Inspector** (or a simpler option — install "Accessibility Inspector" apps
from Play Store) while ChatGPT is open, tap the send button, and read its
reported resource-id/content-description, then add that exact string to
`sendHints` for `CHATGPT` in `AiTarget.kt`.

## Fix for scroll not working
D-pad keycodes (`KEYCODE_DPAD_UP`/`DOWN`) are far more standardized across
controllers than face buttons, so this should now be reliable. If it still
doesn't scroll, turn on Debug Mode and confirm D-pad presses aren't getting
silently swallowed by AnkiDroid itself first (some apps intercept D-pad for
their own navigation) - in that case toggle "Debug mode" and watch whether
you get an "Unmapped key" toast at all when pressing D-pad; if you get
nothing, AnkiDroid's own UI is consuming the key before it reaches this
service, which would need to be checked in AnkiDroid's gamepad settings.

## Split-screen limitation (unchanged)
`FLAG_ACTIVITY_LAUNCH_ADJACENT` reliably works on tablets/foldables/Samsung
multi-window; plain-phone Android doesn't guarantee automatic split-screen
entry from a background service. Some phones will open the second app
fullscreen instead, requiring one manual drag-in from Recents - this is a
device/OEM limitation, not something fixable without root.

## Build & install
Same as before - open in Android Studio and build an APK, or use the
included `.github/workflows/build.yml` to build via GitHub Actions if you
don't have Android Studio installed. See prior instructions for exact
GitHub Actions setup steps (Java 17, Android SDK platform 34 + build-tools
34.0.0, Gradle 8.4 pinned for AGP 8.2.0 compatibility).
