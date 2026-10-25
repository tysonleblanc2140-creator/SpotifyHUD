# Spotify HUD (Fabric, Minecraft 1.21.11, Java 21)

Client-side overlay showing the current Spotify track: cover, title, artist, progress/times,
play-pause state, prev/play/next controls. Fully customizable and saved to `config/spotifyhud.json`.

STATUS: source only. It has NOT been compiled or run yet. See "Build" and "If the build fails".

## Build
1. Install JDK 21 and Gradle 9.2+ (or just open the folder in IntelliJ IDEA and let it import).
2. In this folder run once: `gradle wrapper --gradle-version 9.2.1`
3. `./gradlew build` (Windows: `gradlew.bat build`)
4. The mod is `build/libs/spotifyhud-1.0.0.jar` (not the `-sources` jar).
Version pins are in gradle.properties; if Gradle says one doesn't exist, use the values from https://fabricmc.net/develop

## Install in Prism Launcher
1. Create/choose a 1.21.11 instance, set Fabric Loader (0.18.x) in Edit Instance > Version, Java 21.
2. Edit Instance > Mods > Add: add Fabric API (0.141.1+1.21.11 or newer for 1.21.11) and spotifyhud-1.0.0.jar.
3. Launch.

## Connect Spotify (one-time)
Spotify requires each user to register their own free developer app (no password ever goes into the mod).
1. https://developer.spotify.com/dashboard > Create app. Redirect URI: `http://127.0.0.1:43019/callback`. Tick "Web API".
2. Copy the app's Client ID.
3. In Minecraft press **H** > Spotify tab > paste Client ID > Connect (or `/spotifyhud clientid <id>` then `/spotifyhud login`).
4. Approve in the browser. Play something in the Spotify desktop app.
Notes: playback buttons need Spotify Premium (Spotify's rule). Spotify may require the developer-app owner to be Premium.
Add your own account under the app's "User management" if the app is in Development mode.

## Use
- **H**: settings menu. Drag the HUD to move it; click its buttons there to control playback.
- In-game controls: `[` previous, `\` play/pause, `]` next (rebind in Controls > Spotify HUD).
- Commands: `/spotifyhud login | logout | config | clientid <id>`

## Troubleshooting
- "Set Spotify Client ID" / "Not connected": do the Connect steps above.
- Browser says INVALID_CLIENT / redirect mismatch: the redirect URI in the dashboard must match exactly `http://127.0.0.1:43019/callback`
  (the settings screen shows the live value). Use 127.0.0.1, not localhost.
- Port in use: change `redirectPort` in config/spotifyhud.json and update the dashboard URI.
- "Nothing playing": the Web API only sees an active device. Press play in the Spotify app once.
- Buttons do nothing / "needs Premium": Spotify blocks remote control on Free accounts.
- Stuck login: `/spotifyhud logout` then login again.
- Logs: search `spotifyhud` in logs/latest.log.

## If the build fails
Minecraft 1.21.11 GUI classes changed a lot recently. The most version-sensitive calls are: NativeImageBackedTexture constructor
and NativeImage.setColorArgb (AlbumArt), DrawContext.drawTexture/Matrix stack (HudRenderer), Click/mouse methods (ConfigScreen),
KeyBinding.Category (SpotifyHudMod). Fix names against Yarn 1.21.11 docs.
