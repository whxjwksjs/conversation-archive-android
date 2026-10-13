# Userscript capture guide — Conversation Archive

> v1.1 note: the app now has in-app browsers (Gemini / AI Studio / Jules on
> the home screen) with one-tap "Save conversation". This userscript guide
> remains as the fallback if a site won't log in inside the app.

The app cannot see inside your logged-in Google sessions, and it must never
handle your passwords or cookies. So capture happens in your own browser,
with a small companion userscript, and the app imports the file you get.

Status: EXPERIMENTAL. Nothing here is verified to work on every layout —
the scripts report what they found ("N messages found"), so a silent miss
is unlikely, but Google changes these pages regularly.

## Which Android browser?

Browser support verified 2026-10-12. Pick one of these (desktop Chrome on
Android does NOT support userscripts/extensions):

- **Firefox for Android + Tampermonkey** — easiest path. Tampermonkey is in
  Firefox's official Android add-on catalog: Firefox menu > Add-ons.
- **Microsoft Edge for Android** — supports extensions; Violentmonkey is
  verified working.
- **Quetta, Lemur Browser, Helium, Mises Browser** — Chromium-based Android
  browsers with Chrome Web Store extension support; Tampermonkey or
  Violentmonkey install from the Web Store.
- **Kiwi Browser: do NOT use.** Discontinued January 2025 — frozen Chromium,
  no security updates.

Note: mobile pages sometimes use different markup than desktop. The scripts
are written against desktop-ish selectors with generic fallbacks, but they
have not been re-verified against every mobile layout. If the count looks
wrong, try the same site on desktop mode or a desktop browser.

## Steps

1. In your chosen browser, install a userscript manager: **Tampermonkey** or
   **Violentmonkey**.
2. Install the companion script for the site you want to archive:
   - `convarchive-gemini.user.js` for gemini.google.com
   - `convarchive-aistudio.user.js` for aistudio.google.com
   - `convarchive-jules.user.js` for jules.google.com
   Open the `.user.js` file in the manager's editor and paste the contents
   (or use "Install from URL" with the raw file), then enable it.
3. Log in to the Google site in that same browser and open the conversation
   or task you want to archive.
   - Gemini: scroll through long conversations first (virtualized turns may
     not be rendered).
   - AI Studio: the script auto-scrolls history into view with a
     "Loading history… N%" progress label on its button — wait for it.
   - Jules: open the individual task page.
4. Tap the blue **"⬇ Export to Archive (JSON)"** floating button. It
   downloads a JSON file and shows how many messages/entries it found.
5. Import the file into Conversation Archive:
   - Share sheet: open the downloaded file and share it to Conversation
     Archive, or
   - In-app: Import Conversations > pick the file.

## Honest limits

- You must be logged in. The script reads only what your browser already
  shows — it is not a login bypass, and it never reads or sends passwords,
  cookies, or tokens.
- Only *rendered* content is captured. Virtualized, collapsed, or
  scrolled-away content may be missed. Counts are shown so you can tell.
- Selectors are DOM-dependent and may break when Google changes a layout.
  The header of each `.user.js` file lists the selectors used and what the
  script cannot capture — read it before relying on an export.
- AI Studio: thought blocks, model name, temperature, and system
  instruction are best-effort; missing fields come back null, never invented.
- Jules: activity-log contents, plan steps, per-entry timestamps, and diffs
  were not confirmed extractable by any reviewed source — they are attempted
  with generic selectors and may come back empty.

## Fallbacks

- **Gemini via Google Takeout:** takeout.google.com > Deselect all >
  My Activity > keep only "Gemini Apps" (not the standalone "Gemini"
  product — that exports Gems, not chats) > JSON. Then import the file
  here. No Takeout coverage was found for AI Studio or Jules.
- **Third-party exporters:** the app imports Voyager JSON, the
  aistudio-dump-script format, and other generic exports — see the README.
- **Manual:** copy/paste a conversation to a `.md`/`.txt` file and import
  it; the app splits it heuristically and says so.
