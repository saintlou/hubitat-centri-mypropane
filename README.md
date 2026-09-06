# Centri MyPropane Tank Monitor - Hubitat driver

A Hubitat Elevation driver for the [Centri / CentriConnect MyPropane](https://centriconnect.myshopify.com/)
solar-powered cellular propane tank monitor. It polls Centri's REST API and exposes tank level,
remaining volume, battery, solar voltage, device temperature and LTE signal to your hub, plus
locally-derived consumption figures.

---

## ⚠️ Unsupported - please read

**This is provided as-is, for free, with no support and no warranty of any kind.**

- There is **no commitment to maintain it**, fix bugs, or respond to problem reports.
- It is **not affiliated with, endorsed by, or connected to Centri / CentriConnect** in any way.
- It was written against **one tank monitor on one account**. Multiple monitors, unusual tank
  configurations and future API changes are all untested.
- Centri can change or withdraw the API at any time without notice, which would break this driver.
- **Do not rely on it as your only indication of propane level.** Use the MyPropane app or a
  physical gauge for anything that matters - running a tank dry has real consequences.

Issues and pull requests are welcome and may be looked at, but no response is promised.
If you need it to do something it doesn't, fork it.

---

## What you need

Three values, from Centri's integration email or the MyPropane app:

| Value | Where to find it |
|---|---|
| **User ID** | MyPropane app account settings, or ask Centri support |
| **Device ID** | Printed on the tank monitor, on the setup card, and shown in the app |
| **Device Authentication Code** | Supplied with the monitor, on the setup card |

There is nothing to buy. API access comes with the monitor - email `support@centriconnect.com`
if you don't have your credentials to hand.

## Install

1. **Drivers Code → New Driver → Import**, paste:
   `https://raw.githubusercontent.com/saintlou/hubitat-centri-mypropane/main/drivers/centri-mypropane-tank-monitor.groovy`
2. Save.
3. **Devices → Add Device → Virtual**, choose type **Centri MyPropane Tank Monitor**.
4. Enter your three credentials on the device page and click **Save Preferences**.

The first poll runs a few seconds later. Check the logs.

Not distributed through Hubitat Package Manager. To pick up a later version, use the Import
button again with the same URL - Hubitat caches the fetched code, so it won't refresh on its own.

## Polling and the API limit

Centri caps API access at **4 calls per device per day**, matching the monitor's own posting
frequency. Rather than a fixed 6-hour timer, this driver reads `NextPostTimeIso` from each
response and schedules the next poll about 10 minutes after the device's own next post. That
picks up each reading as soon as it exists while staying inside the cap. It falls back to 6 hours
if that timestamp is missing or implausible.

A rolling 24-hour call count is exposed as `callsLast24h`. Manual **Refresh** always polls, but
logs a warning if you're already at four.

## Attributes

**Tank** - `tankLevel` (%), `gallonsRemaining`, `tankSize`, `tankSizeUnit`, `alertStatus`
(`No Alert` / `Low Level` / `Critical Level`, from the thresholds you set in the app), `lowLevel`
(`true`/`false`, from the threshold set in the driver).

**Consumption, derived locally** - `usedSinceLastRead`, `usedTotal`, `dailyUseRate`,
`daysRemaining`, `lastRefillDate`. The API reports level only and never usage, so these are
calculated from level changes between readings, deduplicated on `LastPostTimeIso` so a repeated
poll never double-counts. A level increase of more than 5% is treated as a delivery rather than
negative usage. The daily rate needs a couple of days of readings before it means anything.

**Device health** - `battery` (%), `batteryVolts`, `solarVolts`, `temperature` (in your hub's
scale), `rssi`, `versionHW`, `versionLTE`, `deviceLabel`.

**Diagnostics** - `lastPostTime`, `nextPostTime`, `lastPollTime`, `callsLast24h`, `apiStatus`.

### A note on battery percentage

Centri's documentation states 4.0V is full and 3.5V is critically low. `battery` is interpolated
between 3.40V (0%) and 4.00V (100%) on that basis. It is an approximation. `batteryVolts` is
exposed raw if you'd rather alert on the voltage directly.

## Commands

- **Refresh** / **Poll** - fetch now.
- **Initialize** - re-read preferences and re-arm the schedule.
- **Reset Consumption Totals** - zero `usedTotal` and clear the 14-day history.

## Known limitations

- One device per driver instance. Two monitors need two devices.
- No support for tank configuration data that the app shows but the API doesn't return.
- Consumption is inferred from level percentage, so it inherits whatever resolution and noise the
  monitor's own level reading has.
- Written and tested against API Integration Guide REV C (5 July 2026).

## Licence

MIT. See [LICENSE](LICENSE).
