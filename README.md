# Clipz

An IDM-style download manager for Android (minSdk 29 / Android 10+).

## Build
1. Install Android Studio (Ladybug or newer) and open this folder.
2. Let Gradle sync (needs internet once), then Run, or Build > Build APK(s).
3. Install the APK on your phone and open Clipz once.

## First-time setup (Setup tab)
- Allow notifications.
- Allow unrestricted battery use (stops Android killing downloads).
- Tap "Add tile" to put the on/off switch in your Quick Settings panel.

## How it works
- Each file is split into up to 16 parallel byte ranges (HTTP/1.1, so they are real separate connections).
- When one range finishes it takes half of the slowest remaining range, so no connection sits idle.
- Progress of every range is saved to disk every 2 seconds. Network loss, app kill, reboot, or
  airplane mode all resume from the last saved byte. Retries never give up on network errors
  (backoff 1s up to 30s); 60s read timeout suits very slow links.
- If the server's file changes (ETag/Last-Modified) or ignores Range, Clipz restarts cleanly.
- Servers that do not support ranges can only be restarted from zero after an interruption.
- Finished files are saved to Downloads/Clipz. Space for the temporary copy is needed while saving.

## Getting links into Clipz
Android does not let one app take over every other app's downloads, so Clipz uses:
Share sheet, clipboard banner, the built-in Browser tab (captures normal file downloads),
and the "open with" chooser for common file types.

## Limits
- Streaming-only video, DRM-protected media and login-walled content are not downloadable files
  and Clipz does not try to get around that.
- Android 15 caps background data transfers at about 6 hours at a time; Clipz pauses cleanly
  and a notification lets you continue with one tap.

## Screen capture
Setup tab > Capture your screen: Screenshot or Record screen (optional microphone).
Android shows its approval dialog each time. Files go to Pictures/Clipz and Movies/Clipz.
Apps that block capture and DRM video come out black; that is enforced by Android.

## Build without a PC (GitHub)
1. Create a free GitHub repository and upload the contents of this folder (keep the .github folder).
2. Open the Actions tab, run "Build Clipz APK", and download the Clipz-debug-apk artifact.
3. Unzip it, open app-debug.apk on your phone and allow "install unknown apps" when asked.
If the build fails, open the failed step and send me the red error lines.

## Sites with no download button (Browser tab)
While a page plays, Clipz watches what it loads and what its <video>/<audio> elements play.
A "media found" bar appears; tap Get. Direct files (mp4, webm, mp3, m4a ...) download like any other
file, with resume. HLS playlists (.m3u8) are fetched piece by piece into one .ts file (.mp4 for fMP4),
with resume. Not possible this way: encrypted/DRM streams and live streams (refused with a message),
and players that build video in memory (blob:) or use DASH. For those, use Record screen.
Some HLS streams keep audio in a separate playlist; those downloads may have no sound.
Only save media you have the right to keep.

## Quality choice
When Get is tapped on an HLS stream that offers several versions, Clipz shows a picker (e.g. 1080p,
720p, 480p with bitrate) and saves the one you choose; the quality is added to the file name.
Plain files have a single version, so they download directly.

## If something goes wrong
- A crash now opens a "Clipz hit a problem" screen with a Copy details button.
- The Browser tab shows "Scanner: on/waiting - N requests checked" so you can see detection is running.
- If Android System WebView is missing or disabled, the Browser tab explains it instead of the app crashing.
- "App blocked" on install/open is usually Google Play Protect (see below), not the app's code.
  Tap More details > Install anyway, or Play Store > profile > Play Protect > settings > turn off
  "Scan apps with Play Protect" while installing.
- Test pages: https://www.w3schools.com/html/html5_video.asp (plain mp4) and
  https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8 (multi-quality HLS; paste it in Downloads).

## v6: Status saver, Library, Vault, new look
- Status tab: save WhatsApp / WhatsApp Business statuses you have already viewed. Connect once by choosing the
  hidden .Statuses folder (Android only lets apps read it after you pick it). Saved to Library > Status.
- Unlimited queue: no cap on how many downloads or how large. Setup > "Downloads at once" (1, 2, 3, 5 or All)
  controls how many run together; the rest wait automatically.
- Library tab: files are sorted into folders by type: Videos, Music, Images, Documents, Archives, Apps, Status, Other.
  On the phone they live in Movies/Clipz, Music/Clipz, Pictures/Clipz and Download/Clipz/<type>.
- Vault tab: 4-digit PIN, hides files from the gallery and file managers, blocks screenshots while open, locks when
  you leave. Files are NOT encrypted and are deleted if Clipz is uninstalled or its data cleared; restore first.
  A forgotten PIN cannot be recovered; resetting wipes the vault.
- Redesigned interface: live speed header, type-coloured file tiles, gradient progress, in-app viewer for pictures,
  video and audio, bottom navigation with five tabs. Settings moved behind the gear on the Home header.

## v7: games, torrents, compression, cool-down

- **More tab**: Torrents, Compress, Cool down, Vault, Games, Setup.
- **Games**: Snake, 2048, Memory match (offline). Downloaded APKs show under "Game files" with Install / Mark as game. Needs "Install unknown apps" allowed for Clipz. If Play Protect objects, delete the `REQUEST_INSTALL_PACKAGES` line in AndroidManifest.xml and install APKs from your Files app instead.
- **Torrents** (libtorrent4j): paste a magnet, add a .torrent, or tap a magnet link in any browser. Stops seeding when done. Files stay in app storage until you tap Save to library.
- **Compress**: photos (JPEG) and videos (H.264, Media3 Transformer). Originals untouched.
- **Cool down**: a Quick Settings tile that shows battery temperature and, when switched on, pauses downloads/torrents and drops to light settings. Auto-cool triggers at 42 °C. It cannot lower the phone's temperature directly or close other apps.
- **Not included**: an on-device APK builder. Use the GitHub Actions workflow in `.github/workflows/build.yml`.

Never compiled here. The torrent library calls (libtorrent4j 2.1.0-35) are the most likely place for a first build error; send me the Gradle message and I'll fix it.

## v8: Clipz (renamed), clip studio, rights ledger, trending

- App renamed **Clipz** (package id unchanged, so it updates in place).
- **Rights ledger** (More): who allows you to clip their content, with proof link and expiry. The studio won't export without a cleared source.
- **Trending** (More): daily YouTube most-popular per country via the official Data API. Needs your free API key. Refreshes every 24 h in the background and keeps 60 days of history.
- **Clip studio** (More): Quick clips (audio-energy highlights, 9:16 export) and Beat edit (tempo detection, cuts on the beat, zoom punch on alternate cuts, music from your track). Footage can be a local file or openly licensed Internet Archive video (CC without no-derivatives, or the NASA and Prelinger collections). Clips are filed in Movies/Clipz/<topic> and listed under My clips with a copy-credit button.
- Not included: reading other platforms' feeds or "most replayed" data (scraping), reposting, multiple accounts.
- Untested. Media3 Composition and libtorrent4j calls are the likeliest first build errors; send the Gradle message.
