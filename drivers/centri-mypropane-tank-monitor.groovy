/**
 *  Centri MyPropane Tank Monitor
 *  Hubitat Elevation driver for the CentriConnect / MyPropane cellular tank monitor.
 *
 *  ==================================================================================
 *   UNSUPPORTED — PLEASE READ BEFORE INSTALLING
 *
 *   This driver is published as-is, for free, with NO SUPPORT and NO WARRANTY of any
 *   kind. There is no commitment to maintain it, fix it, or respond to problem
 *   reports. If it stops working you are on your own.
 *
 *   It is NOT affiliated with, endorsed by, or connected to Centri / CentriConnect.
 *   It was written against a single tank monitor on a single account. Behaviour with
 *   multiple monitors, other tank configurations, or future changes to Centri's API
 *   is untested. Centri can change or withdraw the API at any time without notice,
 *   which would break this driver.
 *
 *   Do not rely on it as your only indication of propane level. Use the MyPropane app
 *   or a physical gauge for anything that matters.
 *
 *   Bug reports and pull requests are welcome and may be looked at, but no response
 *   is promised.
 *  ==================================================================================
 *
 *  Written against Centri's published "API Integration Guide" REV C (5 July 2026):
 *    - GET /centriconnect/{user_id}/device/{device_id}/all-data?device_auth={code}
 *    - The device posts up to 4x/day; API access is capped at 4 calls per device per day.
 *    - The response is a JSON object keyed by the device ID.
 *
 *  Because the API reports NextPostTimeIso, this driver schedules its next poll shortly
 *  after the device's own next post rather than blindly running every 6 hours. That stays
 *  inside the 4-calls/day cap while fetching each reading as soon as it exists.
 *
 *  Consumption figures (used since last read, daily rate, days remaining) are derived
 *  locally — the API reports level only, never usage.
 *
 *  Licence: MIT
 *  Version: 1.0.0
 */

import groovy.transform.Field

@Field static final String     BASE_URI      = "https://api.centriconnect.com"
@Field static final BigDecimal VOLTS_MIN     = 3.40G   // treated as 0% battery
@Field static final BigDecimal VOLTS_MAX     = 4.00G   // treated as 100% (per Centri docs)
@Field static final Integer    FALLBACK_SECS = 21600   // 6h fallback if NextPostTimeIso unusable
@Field static final Integer    MAX_CALLS_DAY = 4       // Centri's documented per-device limit

metadata {
    definition(
        name:      "Centri MyPropane Tank Monitor",
        namespace: "platyuk",
        author:    "Emmanuel",
        importUrl: "https://raw.githubusercontent.com/saintlou/hubitat-centri-mypropane/main/drivers/centri-mypropane-tank-monitor.groovy"
    ) {
        capability "Sensor"
        capability "Battery"
        capability "TemperatureMeasurement"
        capability "SignalStrength"
        capability "Refresh"
        capability "Polling"
        capability "Initialize"

        // Tank
        attribute "tankLevel",          "number"   // percent full, 0-100
        attribute "gallonsRemaining",   "number"   // tankLevel% of tankSize
        attribute "tankSize",           "number"
        attribute "tankSizeUnit",       "string"
        attribute "alertStatus",        "string"   // "No Alert" | "Low Level" | "Critical Level"
        attribute "lowLevel",           "enum", ["true", "false"]

        // Derived consumption
        attribute "usedSinceLastRead",  "number"
        attribute "usedTotal",          "number"
        attribute "dailyUseRate",       "number"
        attribute "daysRemaining",      "number"
        attribute "lastRefillDate",     "string"

        // Device health
        attribute "batteryVolts",       "number"
        attribute "solarVolts",         "number"
        attribute "deviceLabel",        "string"
        attribute "versionHW",          "string"
        attribute "versionLTE",         "string"

        // Timing / diagnostics
        attribute "lastPostTime",       "string"   // device's own reading time, local
        attribute "nextPostTime",       "string"   // device's next scheduled post, local
        attribute "lastPollTime",       "string"   // when this driver last called the API
        attribute "callsLast24h",       "number"
        attribute "apiStatus",          "string"   // "ok" | "error: ..."

        command "resetConsumptionTotals"
    }

    preferences {
        // NOTE: driver preferences do not support paragraph() — that is an app-only
        // construct and throws "No signature of method". The notice is rendered as HTML
        // in the first input's title instead.
        input name: "userId", type: "text",
              title: "<div style='padding:10px;margin-bottom:12px;border:1px solid #b00;" +
                     "border-radius:4px;background:#fff5f5;color:#600;'>" +
                     "<b>UNSUPPORTED</b> &mdash; provided as-is, with no support and no warranty. " +
                     "Not affiliated with Centri / CentriConnect. Do not rely on this as your only " +
                     "indication of propane level.</div>User ID",
              description: "UUID from Centri's integration email, or the MyPropane app account settings",
              required: true
        input name: "deviceId", type: "text", title: "Device ID",
              description: "UUID printed on the tank monitor and shown in the app",
              required: true
        input name: "deviceAuth", type: "text", title: "Device Authentication Code",
              description: "Code supplied with the monitor / on the setup card",
              required: true

        input name: "tankSizeOverride", type: "number", title: "Tank size override (optional)",
              description: "Leave blank to trust the capacity the API reports. Set a number only if that is wrong."
        input name: "lowLevelPct", type: "number", title: "Low-level threshold (%)",
              defaultValue: 30,
              description: "Drives the 'lowLevel' attribute. Independent of the alert levels set in the MyPropane app."
        input name: "pollOffsetMin", type: "number", title: "Poll delay after device post (minutes)",
              defaultValue: 10,
              description: "Cushion so the reading has landed server-side before fetching."

        input name: "txtEnable",   type: "bool", title: "Descriptive text logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Debug logging (auto-off in 30 min)", defaultValue: false
    }
}

// ---------------------------------------------------------------- lifecycle

void installed() {
    log.info "Centri MyPropane: installed"
    state.usedTotal = 0
    state.history = []
    state.calls = []
    initialize()
}

void updated() {
    log.info "Centri MyPropane: preferences updated"
    if (debugEnable) runIn(1800, "debugOff")
    initialize()
}

void initialize() {
    unschedule()

    if (!settings.userId || !settings.deviceId || !settings.deviceAuth) {
        log.warn "Centri MyPropane: credentials incomplete — not scheduling polls"
        sendEvent(name: "apiStatus", value: "error: credentials not set")
        return
    }
    if (state.usedTotal == null) state.usedTotal = 0
    if (state.history == null)   state.history = []
    if (state.calls == null)     state.calls = []

    // Watchdog re-arms the schedule if a poll is ever missed (hub reboot, failed callback).
    // It makes no API call itself unless genuinely overdue.
    runEvery1Hour("watchdog")
    runIn(5, "poll")
}

void debugOff() {
    log.info "Centri MyPropane: debug logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
}

// ---------------------------------------------------------------- commands

void refresh() { poll() }

void poll() {
    if (!settings.userId || !settings.deviceId || !settings.deviceAuth) {
        log.warn "Centri MyPropane: credentials not set — skipping poll"
        return
    }

    Integer used = callsInLast24h()
    if (used >= MAX_CALLS_DAY) {
        log.warn "Centri MyPropane: ${used} API calls already made in the last 24h " +
                 "(Centri's limit is ${MAX_CALLS_DAY}). Polling anyway, but expect a rejection."
    }

    Map params = [
        uri:         BASE_URI,
        path:        "/centriconnect/${settings.userId}/device/${settings.deviceId}/all-data",
        query:       [device_auth: settings.deviceAuth],
        contentType: "application/json",
        timeout:     30
    ]
    if (debugEnable) log.debug "Centri MyPropane: GET ${params.path}"
    recordCall()
    asynchttpGet("pollCallback", params)
}

void resetConsumptionTotals() {
    state.usedTotal = 0
    state.history = []
    state.lastLevel = null
    state.lastPostEpoch = null
    sendEvent(name: "usedTotal", value: 0)
    sendEvent(name: "dailyUseRate", value: 0)
    sendEvent(name: "daysRemaining", value: 0)
    log.info "Centri MyPropane: consumption totals reset"
}

// ---------------------------------------------------------------- polling

void watchdog() {
    Long last = state.lastPollEpoch ?: 0L
    if (now() - last > (FALLBACK_SECS * 1000L) + 900000L) {
        log.warn "Centri MyPropane: no successful poll in over 6h15m — re-arming"
        runIn(5, "poll")
    }
}

void pollCallback(hubitat.scheduling.AsyncResponse resp, Map cbData) {
    try {
        if (resp.hasError()) {
            fail("HTTP ${resp.status}: ${resp.errorMessage ?: 'no message'}")
            return
        }
        if (resp.status != 200) {
            fail("HTTP ${resp.status}")
            return
        }

        def payload = resp.json
        if (!(payload instanceof Map) || payload.isEmpty()) {
            fail("unexpected response body")
            return
        }

        // Response is keyed by device ID; fall back to the first entry if the key differs.
        def rec = payload[settings.deviceId] ?: payload.values().find { it instanceof Map }
        if (!(rec instanceof Map)) {
            fail("no record for device ${settings.deviceId} in response")
            return
        }

        state.lastPollEpoch = now()
        sendEvent(name: "lastPollTime", value: localStamp(new Date()))
        sendEvent(name: "apiStatus", value: "ok")
        sendEvent(name: "callsLast24h", value: callsInLast24h())

        processReading(rec)
        scheduleNextPoll(rec.NextPostTimeIso as String)

    } catch (Exception e) {
        fail("parse error: ${e.message}")
    }
}

private void fail(String msg) {
    log.warn "Centri MyPropane: poll failed — ${msg}"
    sendEvent(name: "apiStatus", value: "error: ${msg}")
    // Retry once in 15 minutes, then fall back to the normal cadence.
    runIn(900, "poll")
}

private void scheduleNextPoll(String nextPostIso) {
    Long offsetMs = ((pollOffsetMin ?: 10) as Long) * 60000L
    Date next = parseUtc(nextPostIso)
    Long delay = (next != null) ? (((next.time + offsetMs) - now()) / 1000L) : null

    // Sanity-bound it: at least 30 min out, no more than 12h out.
    if (delay == null || delay < 1800 || delay > 43200) {
        if (debugEnable) log.debug "Centri MyPropane: NextPostTimeIso unusable (${nextPostIso}) — using 6h fallback"
        delay = FALLBACK_SECS as Long
    }

    runIn(delay.intValue(), "poll", [overwrite: true])
    if (txtEnable) log.info "Centri MyPropane: next poll in ${(delay / 60).intValue()} minutes"
}

// ---------------------------------------------------------------- readings

private void processReading(Map rec) {
    BigDecimal level    = toNum(rec.TankLevel)
    BigDecimal override = (tankSizeOverride != null) ? (tankSizeOverride as BigDecimal) : null
    BigDecimal size     = (override != null && override > 0G) ? override : (toNum(rec.TankSize) ?: 0G)
    String unit         = (rec.TankSizeUnit ?: "Gallons") as String
    String alert        = (rec.AlertStatus ?: "Unknown") as String

    if (level != null) {
        BigDecimal remaining = (size > 0G) ? ((level / 100G) * size).setScale(1, BigDecimal.ROUND_HALF_UP) : 0G
        sendEvent(name: "tankLevel", value: level, unit: "%",
                  descriptionText: "Tank level is ${level}%")
        sendEvent(name: "gallonsRemaining", value: remaining, unit: unit,
                  descriptionText: "Approximately ${remaining} ${unit.toLowerCase()} remaining")
        BigDecimal thresh = (lowLevelPct ?: 30) as BigDecimal
        sendEvent(name: "lowLevel", value: (level <= thresh) ? "true" : "false")
    }

    sendEvent(name: "tankSize",     value: size, unit: unit)
    sendEvent(name: "tankSizeUnit", value: unit)
    sendEvent(name: "alertStatus",  value: alert, descriptionText: "Centri alert status: ${alert}")

    // Battery: Centri documents 4.0V as full and 3.5V as critically low.
    BigDecimal volts = toNum(rec.BatteryVolts)
    if (volts != null) {
        sendEvent(name: "batteryVolts", value: volts, unit: "V")
        BigDecimal pct = ((volts - VOLTS_MIN) / (VOLTS_MAX - VOLTS_MIN)) * 100G
        Integer clamped = Math.max(0, Math.min(100, pct.setScale(0, BigDecimal.ROUND_HALF_UP).intValue()))
        sendEvent(name: "battery", value: clamped, unit: "%",
                  descriptionText: "Battery ${clamped}% (${volts}V)")
    }

    BigDecimal solar = toNum(rec.SolarVolts)
    if (solar != null) sendEvent(name: "solarVolts", value: solar, unit: "V")

    // Temperature in the hub's own scale.
    BigDecimal temp = (location.temperatureScale == "F")
                      ? toNum(rec.DeviceTempFahrenheit)
                      : toNum(rec.DeviceTempCelsius)
    if (temp != null) {
        sendEvent(name: "temperature", value: temp, unit: "°${location.temperatureScale}",
                  descriptionText: "Monitor temperature is ${temp}°${location.temperatureScale}")
    }

    BigDecimal rssi = toNum(rec.SignalQualLTE)
    if (rssi != null) sendEvent(name: "rssi", value: rssi, unit: "dBm")

    if (rec.DeviceName) sendEvent(name: "deviceLabel", value: rec.DeviceName as String)
    if (rec.VersionHW)  sendEvent(name: "versionHW",   value: rec.VersionHW as String)
    if (rec.VersionLTE) sendEvent(name: "versionLTE",  value: rec.VersionLTE as String)

    Date lastPost = parseUtc(rec.LastPostTimeIso as String)
    Date nextPost = parseUtc(rec.NextPostTimeIso as String)
    if (lastPost) sendEvent(name: "lastPostTime", value: localStamp(lastPost))
    if (nextPost) sendEvent(name: "nextPostTime", value: localStamp(nextPost))

    trackConsumption(level, size, unit, lastPost)
}

/**
 * Consumption is derived locally: the API gives level only, not usage.
 * Deduplicated on LastPostTimeIso so a repeated poll of the same reading never double-counts.
 */
private void trackConsumption(BigDecimal level, BigDecimal size, String unit, Date lastPost) {
    if (level == null || size == null || size <= 0G || lastPost == null) return

    Long postEpoch = lastPost.time
    if (state.lastPostEpoch != null && postEpoch <= (state.lastPostEpoch as Long)) {
        if (debugEnable) log.debug "Centri MyPropane: reading unchanged since last poll — no consumption update"
        return
    }

    BigDecimal prevLevel = (state.lastLevel != null) ? (state.lastLevel as BigDecimal) : null

    if (prevLevel != null) {
        BigDecimal deltaPct = prevLevel - level
        if (deltaPct < -5G) {
            // Level jumped up meaningfully: a delivery, not consumption.
            sendEvent(name: "lastRefillDate", value: localStamp(lastPost),
                      descriptionText: "Refill detected: ${prevLevel}% to ${level}%")
            sendEvent(name: "usedSinceLastRead", value: 0, unit: unit)
            log.info "Centri MyPropane: refill detected (${prevLevel}% to ${level}%)"
        } else if (deltaPct > 0G) {
            BigDecimal used = ((deltaPct / 100G) * size).setScale(2, BigDecimal.ROUND_HALF_UP)
            state.usedTotal = ((state.usedTotal as BigDecimal) + used).setScale(2, BigDecimal.ROUND_HALF_UP)
            sendEvent(name: "usedSinceLastRead", value: used, unit: unit)
            sendEvent(name: "usedTotal", value: state.usedTotal, unit: unit)
            recordHistory(postEpoch, used)
        } else {
            sendEvent(name: "usedSinceLastRead", value: 0, unit: unit)
            recordHistory(postEpoch, 0G)
        }
    }

    state.lastLevel = level
    state.lastPostEpoch = postEpoch
    updateBurnRate(level, size, unit)
}

private void recordHistory(Long epoch, BigDecimal used) {
    List hist = (state.history ?: []) as List
    hist << [t: epoch, u: used]
    Long cutoff = now() - (14L * 86400000L)   // keep 14 days
    state.history = hist.findAll { (it.t as Long) >= cutoff }
}

private void updateBurnRate(BigDecimal level, BigDecimal size, String unit) {
    List hist = (state.history ?: []) as List
    if (hist.size() < 2) return

    Long spanMs = (hist[-1].t as Long) - (hist[0].t as Long)
    if (spanMs < 86400000L) return   // need at least a day before the rate means anything

    BigDecimal total = hist.inject(0G) { acc, e -> acc + (e.u as BigDecimal) }
    BigDecimal days  = (spanMs / 86400000.0G)
    BigDecimal rate  = (total / days).setScale(2, BigDecimal.ROUND_HALF_UP)
    sendEvent(name: "dailyUseRate", value: rate, unit: "${unit}/day")

    if (rate > 0G) {
        BigDecimal remaining = (level / 100G) * size
        BigDecimal daysLeft = (remaining / rate).setScale(0, BigDecimal.ROUND_HALF_UP)
        sendEvent(name: "daysRemaining", value: daysLeft, unit: "days",
                  descriptionText: "Roughly ${daysLeft} days remaining at ${rate} ${unit.toLowerCase()}/day")
    }
}

// ---------------------------------------------------------------- helpers

private void recordCall() {
    List calls = (state.calls ?: []) as List
    calls << now()
    Long cutoff = now() - 86400000L
    state.calls = calls.findAll { (it as Long) >= cutoff }
}

private Integer callsInLast24h() {
    Long cutoff = now() - 86400000L
    return ((state.calls ?: []) as List).findAll { (it as Long) >= cutoff }.size()
}

/** Centri timestamps are "yyyy-MM-dd HH:mm:ss" in UTC with no zone marker. */
private Date parseUtc(String s) {
    if (!s) return null
    try {
        def fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"))
        fmt.setLenient(false)
        return fmt.parse(s.trim())
    } catch (Exception e) {
        if (debugEnable) log.debug "Centri MyPropane: could not parse timestamp '${s}'"
        return null
    }
}

private String localStamp(Date d) {
    def fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
    fmt.setTimeZone(location.timeZone ?: TimeZone.getDefault())
    return fmt.format(d)
}

private BigDecimal toNum(v) {
    if (v == null) return null
    try { return new BigDecimal(v.toString()) } catch (Exception e) { return null }
}
