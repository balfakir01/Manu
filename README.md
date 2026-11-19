# Data Guard (Manu)

An Android app that shows **which companies each of your apps is sending data to**, flags
known trackers, ad networks, data brokers and device-fingerprinting firms, and lets you
**block them per app, everywhere, or cut an app off from the internet entirely**.

Everything runs on your phone. There is no server and no analytics, and the app uses no
third-party libraries.

## What you get

- **Apps tab**: every app that has gone online, how many destinations it contacted, how many
  are known trackers, and which tracker companies they belong to.
- **Live feed**: lookups as they happen, including from apps running in the background.
- **Map**: a world map with a line from your country to every country your data went to.
  Red means trackers were involved. Tap a country to see the servers and apps behind it.
  Each app also has its own map.
- **Per-app screen**: every domain that app contacted, with a switch to block all of its internet.
- **Tap any destination** to block it for that app, block it in every app, or allow it.
- **One switch** to block about 130 known tracking and advertising domains in all apps
  (Google/Meta ad SDKs, AppsFlyer, Adjust, Braze, LexisNexis ThreatMetrix, LiveRamp, location
  brokers, session recorders and more; see `Trackers.kt`).

## How it works

Before an app can send anything to a server, it has to look up the server's address
(DNS). Data Guard uses Android's `VpnService` to set up a VPN that stays on the device and
captures only those lookups:

1. An app asks, for example, "where is `app-measurement.com`?"
2. Data Guard works out which app asked (Android 10+ `getConnectionOwnerUid`) and records it.
3. If you blocked that domain, it answers "doesn't exist" and the app can't connect.
   Otherwise it passes the question to your network's normal DNS server.

Your actual traffic (web pages, videos and so on) does **not** go through the tunnel. It
travels exactly as it did before.

## How the map works

When a lookup is allowed, Data Guard reads the server address from the answer and finds its
country in an IP-to-country database bundled inside the app. Nothing is sent to a location
service. A server's country is where its address is registered. Big companies answer from data
centres near you, so a "local" server can still belong to a company abroad. Blocked lookups never
get an address, so the map shows them as "never left your phone". Only IPv4 addresses are
located for now.

## Honest limitations

- It shows **who** an app talks to, not **what** it sends. Request contents are encrypted
  (HTTPS), and Android stops normal apps from decrypting other apps' traffic. Seeing payloads,
  as the researchers did in the documentary, needs a rooted or lab phone.
- An app that uses hard-coded IP addresses or its own encrypted DNS can slip past. Most
  tracking SDKs don't do this.
- Blocking applies to the app's *next* lookup. Restart the app after you block something.
- Turn off **Settings › Network & internet › Private DNS**. If it's on, Android may skip the
  local lookups.
- Android runs only one VPN at a time, so this can't run alongside another VPN app.
- Blocking some domains can break features in an app. Use **Allow** to undo it.

## Install it

There's no Play Store listing; you sideload the APK.

1. Open the repo's **Actions** tab › latest **Build APK** run › download the
   `DataGuard-apk` artifact and unzip it.
2. Copy `app-debug.apk` to your phone and open it. Allow "install unknown apps" when asked.
3. Open **Data Guard**, tap **Start watching**, and accept the VPN prompt.
4. Optional: in **Settings › Network › VPN › Data Guard** turn on **Always-on VPN** so it
   restarts after a reboot.

Requires Android 10 or newer.

## Build it yourself

You need Android Studio, or JDK 17 + the Android SDK + Gradle 8.11 or newer:

```bash
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Code map

| File | What it does |
|---|---|
| `vpn/GuardVpnService.kt` | The on-device VPN: reads DNS lookups, identifies the app, blocks or forwards them |
| `vpn/Packets.kt` | Minimal IPv4/UDP/DNS parsing and building |
| `data/Trackers.kt` | Built-in list of tracker/ad/fingerprinting domains and owners |
| `data/Rules.kt` | Your block/allow choices and how they're applied |
| `data/EventStore.kt` | 7-day lookup history in SQLite on the phone |
| `ui/MainActivity.kt` | Apps list, live feed, on/off |
| `ui/AppDetailActivity.kt` | Per-app destinations and the "block all internet" switch |
| `ui/MapActivity.kt`, `ui/WorldMapView.kt` | The "where your data goes" map and country list |
| `data/Geo.kt` | Offline IP-to-country lookup and map outlines |
| `tools/build_geo_assets.py` | Rebuilds `app/src/main/assets/geoip4.bin` and `world.bin` |

## Rebuilding the map data

The two files in `app/src/main/assets/` are generated from npm packages:

```bash
npm pack @ip-location-db/dbip-country world-atlas i18n-iso-countries
```

Unpack each `.tgz` into its own folder, then:

```bash
python3 tools/build_geo_assets.py dbip/package/dbip-country-ipv4-num.csv atlas/package iso/package/codes.json app/src/main/assets
```

## Data credits

- IP geolocation by [DB-IP](https://db-ip.com), "IP to Country Lite", licensed under
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- Country outlines from [Natural Earth](https://www.naturalearthdata.com) (public domain), via
  the `world-atlas` package.
- ISO country codes from the `i18n-iso-countries` package (MIT).
