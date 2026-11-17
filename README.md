<p align="center"><img src="docs/logo.png" width="140" alt="MeshWardrive logo"></p>

# MeshWardrive

**English** · [Español](README.es.md)

Android app for mapping the coverage of a **Meshtastic** network while you move (*wardriving*), in the spirit of MeshMapper for MeshCore.

**Author:** EB7BWD

<p align="center">
  <img src="docs/screenshots/map.png" width="16%" alt="Heat map">
  <img src="docs/screenshots/nodes.png" width="16%" alt="Nodes">
  <img src="docs/screenshots/connection.png" width="16%" alt="Connection">
  <img src="docs/screenshots/analysis.png" width="16%" alt="Data and analysis">
  <img src="docs/screenshots/log.png" width="16%" alt="Log">
  <img src="docs/screenshots/settings.png" width="16%" alt="Settings">
</p>

## Download

Download the APK from **[Releases](../../releases/latest)** and install it (Android 8 or later). That is the **only** official source: don't install copies from other sites, because you can't tell whether they were modified. Every version is signed with the same key, so updates install over the previous one without losing data.

## ⚠️ Important notice

MeshWardrive is an **experimental** app, created by EB7BWD and developed **almost entirely by artificial intelligence** (Claude, by Anthropic). The code has not been audited and may contain errors.

It is provided **"as is", without warranty of any kind**, express or implied, including warranties of operation, data accuracy or fitness for a particular purpose.

By using it you accept that:

- You use it **entirely at your own risk**.
- You are responsible for complying with the law in your country: radio spectrum use, duty cycle and power, data protection and traffic rules.
- Every ping uses airtime on a shared network. It is your responsibility to configure it considerately and not flood the mesh.
- You must not handle the phone while driving.
- Coverage maps are indicative only and must not be relied on for emergency or safety communications.

To the maximum extent permitted by law, **the author accepts no liability** for any damage, loss, penalty, data loss or misuse arising from this app.

If you do not agree, do not use it. The app shows this same notice on first launch and it must be accepted to continue.

## Privacy

**MeshWardrive does not collect any data.** It has no servers or accounts, no analytics or advertising, and sends nothing to the author or to third parties. Your measurements stay on your phone and only leave it if you export or share them.

What does leave the phone:

- **Pings**, which go out over the radio. Anyone with the channel can read them, including your coordinates if you enable "Include coordinates in the message" (off by default).
- **With the online map**, the OpenStreetMap servers receive your IP address and the area you are viewing, as with any web map. With an offline map, they don't.

Data from other nodes (names, positions, relays) arrives over the radio and cannot be verified: anyone can fake it. This notice is also in the app, under **Settings → About**.

## What it does

- Connects to your Meshtastic node over Bluetooth.
- Sends periodic pings with ACK to a channel (`wardriving` by default).
- For every ping it stores the GPS position, whether anyone heard it, which node relayed it, and the SNR/RSSI.
- Also logs everything your node hears over the air, with your position.
- Draws it on a map as a heat map: green where there is coverage, red where there is none.
- Lists the nodes that probably relayed you.
- Plots the estimated noise floor along the route; tap a point to see it on the map.
- Draws the route travelled and marks unmeasured gaps near you.
- Keeps every wardrive separately: view one, several combined, or all of them, and rename or delete them.
- Builds a report for each outing and compares two wardrives to show where coverage got better or worse.
- Shares wardrives with friends as a `.mwd` file they import into their app.
- Works without Internet using offline maps.
- Exports the result as an image and in formats for building coverage maps.

Besides the normal (channel) mode there is a **direct mode**, where the ping goes to a specific node and its confirmation is recorded, and a **listen-only mode**, which transmits nothing and just records what your node hears.

## Screens

Five tabs, switched by swiping or with the bottom bar: **Map**, **Connection**, **Data**, **Log** and **Settings**. On the map, swiping moves the map; leave that tab with the bottom bar.

- **Map:** the card at the top sums up the connection, GPS and last ping; tap it to fold it. When a ping isn't sent, the reason shows there. Below are **Start/Stop**, **Ping** and **Layers**, plus a legend explaining each colour.
- **Data** has three sections: **Wardrives** (choose which are shown, rename, share, import, delete), **Analysis** (summary, report, compare, noise chart, repeaters) and **Export** (image, coverage files, offline maps).
- **Settings** is grouped by topic: Ping, Measurement, Map, Connection, Privacy and Language.

A skippable tutorial is shown the first time; you can see it again from **Settings**.

## Languages

English, Spanish, French, Italian, Portuguese, German, Russian, Arabic, Greek and Japanese. By default it follows the phone's language (English if the phone's isn't one of these). You can change it in **Settings → Language**. Translations were machine-generated and corrections are welcome.

## The `wardriving` channel: a proposed standard

The idea behind this project is that everyone uses **the same channel** for wardriving, so that pings from different people are comparable and anyone can see and log them:

- **Name:** `wardriving`
- **Key (PSK):** `AQ==`

`AQ==` is Meshtastic's default public key. Since everybody knows it, the channel is in practice **unencrypted**: anyone who adds it can read the pings, including your coordinates if you enable "Include coordinates in the message".

This is a proposal, not a requirement. **The choice is ultimately yours:** you can use a private key so only your group sees the pings, or a different channel name (set it in **Settings**). The app works the same either way.

## How to use it

1. Create a secondary channel named `wardriving` with key `AQ==` on your node with the official Meshtastic app (see above).
2. Close the official app (the node accepts only one Bluetooth connection).
3. Open MeshWardrive, accept the notice, go to **Connection**, tap **Scan and connect** and choose your node.
4. In **Map**, tap **Start** and move around.

If the connection fails, the reason is shown in **Connection** and in **Log**. To stop without opening the app, use the **Stop** button in the notification.

## Options

Everything that changes how measuring works lives in **Settings** and is **off** by default, so the app behaves as before until you decide otherwise.

| Option | Where | What it does |
|---|---|---|
| Listen-only mode | Settings → Ping → Mode (or the target button in **Connection**) | Sends no pings. Records everything your node hears, with your position. Useful for measuring without using airtime. |
| Ping text | Settings → Ping | The word before the number (`WD` by default, for *wardrive*). Up to 10 letters, digits or `-_#!*+.`. |
| Record everything the node hears | Settings → Measurement | **On** by default. If you turn it off, only what is addressed to you (ACKs and replies) is saved besides the pings: it takes far less space, but the noise chart and the nodes-in-range list get less data. Listen-only mode always records everything. |
| Smart ping | Settings → Measurement | Only sends pings in cells with no data or fewer measurements than set (3 by default). Saves airtime when you repeat routes. |
| Pause when the channel is busy | Settings → Measurement | If the node reports channel utilization above the threshold (25 % by default), pings stop until it drops. Current utilization is shown on the map card. |
| Node GPS | Settings → Measurement | If your node has GPS, its position is used when the phone's is missing or not accurate enough. The node only sends its position when it broadcasts it (by default about every 30 s while moving, far less when still), so positions up to 1 minute old are accepted. The map card shows the last one received. |
| Stop reconnecting | Settings → Connection | If the node has been disconnected for longer than this (10 min by default), the wardrive stops and a notification tells you. 0 = never give up. |
| Your name when sharing | Settings → Privacy | The name shown in `.mwd` files. If empty, your node's name is used. |

Under **Layers** (Map tab) you can turn on or off the heat map, cells, pings, passive reception, nodes, the **route travelled** and **nearby gaps** (unmeasured neighbouring cells, so you know where to go next).

## Storage and backups

Pings and the route take very little space and have no limit. What can grow a lot is **passive reception** (everything your node hears): on a busy mesh that is thousands of packets per outing, and with listen-only mode running for hours it fills up sooner. So the app keeps at most **300,000 packets** (a few tens of MB); beyond that it deletes the oldest.

Before that happens, it warns you:
- **Data → Wardrives** always shows how many received packets are stored and the percentage of the limit.
- At **80 %** and **95 %** the app shows a warning (and a notification if you are wardriving) with three choices: manage wardrives, stop recording passive traffic, or remind you later.
- When importing a `.mwd` that would go over the limit, the summary tells you how many old packets would be deleted, before you accept.

To make a **backup**, share your wardrives as `.mwd` and save the file (to Drive, your computer…). Untick "Trim 1 km" so the copy is complete. You can then delete those wardrives from the phone and import them again whenever you like.

## Managing and sharing wardrives

In **Data → Wardrives**, tap any wardrive to show only that one on the map, rename it, share it or delete it. The checkboxes choose which ones are shown together.

**Share.** **Share wardrives…** builds a `.mwd` file with the wardrives you choose and opens Android's share menu. Before that it lets you:
- **Trim 1 km at the start and end** (on by default), since those are usually your home or workplace.
- **Remove coordinates from the ping text.**

The file contains your route with times: check what you share and with whom.

**Import.** Open the `.mwd` with MeshWardrive (from a chat or a file manager) or use **Import wardrive…**. A summary is shown before importing. The importer is built not to trust the file: it limits its size, only accepts known fields with values in range, drops anything that doesn't fit, and rejects the whole file if there is too much junk. Imported text is never interpreted as code or as a formula. Imported wardrives carry the sharer's name and can be viewed, compared or deleted like your own.

## Analysis

In **Data → Analysis**:

- **Report:** distance, duration, pings and share answered, cells measured and covered, and the best and worst area (tap them to go to the map). **Share image** creates an image with the map and the summary.
- **Compare:** choose a "before" group of wardrives and an "after" group. The map colours each cell by whether it got better, worse, stayed the same, is new or was only measured before. Useful to see the effect of a new repeater or an antenna change. A cell counts as changed if its success rate moves by 15 points or its best SNR by 3 dB.
- **Noise chart:** tap a point to see its time, noise, RSSI, SNR and status; tap the box to see it on the map. Pinch to zoom, double-tap to reset. Several wardrives are drawn one after another, separated by a dashed line.
- **Repeaters:** tap one to see it on the map or show **only its coverage** (the pings it relayed) and its maximum range.
- **How each value is calculated:** the same explanation as below, inside the app.

## How each value is calculated

**Pings**

| Value | How it is obtained |
|---|---|
| Heard by the mesh | Your node heard a neighbour rebroadcast the ping (Meshtastic's implicit ACK). |
| ACK from target | Direct mode only: the target node sent back a real acknowledgement. |
| Rejected / nobody listening | The node reported an error. The usual one is `MAX_RETRANSMIT`: it retried and nobody rebroadcast. |
| No response | Nothing arrived within the maximum wait (90 s by default). |
| Relay | The `relay_node` field of the acknowledgement: the last byte of the ID of the node that rebroadcast. The app lists every known node whose ID ends in that byte. |
| Relay SNR / RSSI | Measured by **your** node when it heard that rebroadcast. They describe the path from the relay to you, not how well the relay heard you. |
| ACK SNR / RSSI | Direct mode: measured by your node on the last hop of the acknowledgement. |
| Hops | `hop_start − hop_limit` of the packet received. |
| Latency | Time between sending the ping and receiving its response. |
| Success % | Answered ÷ (answered + unanswered). Pings still waiting are not counted. |

A ping is sent automatically only when the minimum interval has passed, you have moved the minimum distance, the GPS fix is recent and accurate enough, and the previous ping has finished. The default hop limit is 1: only direct neighbours rebroadcast it.

**Map**

| Value | How it is obtained |
|---|---|
| Colour | By SNR: green ≥ −7 dB, amber from −7 to −13 dB, orange below −13 dB. Red means no response. A ping heard without an SNR value is green. |
| Heat map | One blurred spot per ping, in its colour, with a radius equal to the cell size (never smaller than a fingertip on screen). Failures are drawn first and successes on top. |
| Cells | A grid of squares of the configured size (150 m by default). The colour is that of the **best** SNR among the answered pings in the cell; red if none was answered. |
| Cell average SNR | Arithmetic mean of the SNR of the answered pings in the cell. |
| Passive reception | Every packet your node receives over the radio (not via MQTT), stored with your position at that moment, if the GPS fix is under 15 s old. |

**Analysis**

| Value | How it is obtained |
|---|---|
| Noise floor | Meshtastic does not report it, so it is an estimate from each packet heard: `RSSI − SNR` when the SNR is positive, and the `RSSI` itself when the SNR is negative (the signal is then below the noise). Packets with an SNR above 5 dB are left out, because LoRa's SNR saturates and the subtraction would give a false, much higher noise. There are only values where something was received. |
| Ping markers on the chart | Placed at the time of each ping, on the noise line (interpolated between the nearest samples). |
| Probable repeaters | If the firmware reports who relayed each ping, pings grouped by relay byte. Many firmware versions do not report it; the list then shows the nodes within range: those heard directly (with their full ID) and those seen relaying other traffic. "Other packets" counts third-party traffic that reached you through that same byte. Best and average SNR come from those receptions. |
| Repeater position on the map | The node's own position if exactly one known node matches and it has shared it; otherwise the place where it was heard best. |

## Offline maps

The OpenStreetMap tile servers do not allow downloading areas for offline use, so the app uses Mapsforge vector maps, which are made for it:

1. Download the `.map` file for your area, for example from <https://download.mapsforge.org/>.
2. In **Data → Export → Offline map… → Import map**, choose the file.
3. The app copies it and starts using it. From the same menu you can go back to the online map or delete it.

## Export

From the **Data** tab:

| Format | Use |
|---|---|
| PNG image | The map as shown, with legend and summary. For sharing. |
| Cells GeoJSON | Coverage polygons with statistics and colour. For QGIS, uMap, geojson.io. |
| KML | Cells, pings and nodes in folders. For Google Earth and OsmAnd. |
| Points GeoJSON | Every ping and every packet heard, with all its data. |
| CSV | Pings and passive reception as tables. For spreadsheets. |

### How to use each export

Exports are under **Data → Export** and include only the wardrives currently shown. They contain your route: check them before sharing. To pass wardrives to someone else who uses MeshWardrive, the `.mwd` file is better (see above).

**PNG image.** A picture of the map as you see it at that moment, so first set the zoom and the layers you want. Share it by chat or social media, or paste it into a document.

**Cells GeoJSON** (`coverage-cells-….geojson`). The coverage map ready to publish.
- *geojson.io*: open the site and drag the file onto the map. The cells appear already coloured.
- *uMap*: create a map, then **Import data** and choose the file. Useful for a shareable online map.
- *QGIS*: drag the file into the window. Colour it by the `class` field (`good`, `marginal`, `weak`, `no_coverage`) or by `success_pct`.
- Fields per cell: `pings`, `ok`, `fail`, `success_pct`, `best_snr`, `avg_snr`. Cells with `layer` = `rx` are passive reception.

**KML** (`coverage-….kml`). For viewing on a globe or in a navigation app.
- *Google Earth*: on a computer, **File → Import KML file**; on a phone, open the file with Google Earth.
- *OsmAnd*: open the file with OsmAnd, or import it as a favourite/track.
- It has four folders you can switch on and off: coverage cells, pings, passive reception and nodes. Tap any item to see its details.

**Points GeoJSON** (`wardrive-….geojson`). Every single measurement, for your own analysis.
- *QGIS* or *kepler.gl*: load the file and colour the points by `status`, `relay_snr` or `snr`.
- The `kind` field separates pings (`ping`) from packets heard (`rx`).
- Use it if you want a different grid or your own heat map.

**CSV** (`wardrive-pings-….csv` and `wardrive-rx-….csv`). Tables for spreadsheets and scripts.
- Open them with LibreOffice Calc or Excel (comma separator, UTF-8).
- Pings: one row per ping, with time, position, status, error, latency, relay and SNR/RSSI.
- Passive reception: one row per packet heard, with origin node, port, SNR, RSSI, hops and relay.

## Build

Only needed if you want to modify the app; to use it, download the APK from **Releases**.

With Android Studio or locally:

```bash
./gradlew assembleDebug
```

With GitHub Actions, every *push* builds the signed APK, available under **Actions → Artifacts**, and every `v*` tag (for example `v0.6.0`) also publishes a release with the APK and the notes from `docs/release-notes/<tag>.md`. Signing uses two repository secrets, `KEYSTORE_BASE64` (the keystore in base64) and `KEYSTORE_PASSWORD`, with alias `meshwardrive`. The key must never be committed to the repository.

## Airtime

Keep the interval at 30 s or more and the `hop_limit` low.

## License

Copyright © 2026 EB7BWD

Free software under the [GNU General Public License v3.0](LICENSE) or later. You may use, modify and redistribute it, provided derived versions remain free under the same license. It is distributed **without any warranty**.

## Credits

- Idea and project: **EB7BWD**
- Code generated almost entirely with AI (Claude, by Anthropic)
- Maps © OpenStreetMap contributors
- osmdroid, AndroidX, Material Components and kotlinx.coroutines (Apache 2.0); Mapsforge (LGPL v3)

Independent project, not affiliated with Meshtastic or MeshMapper. Meshtastic® is a registered trademark of Meshtastic LLC.
