package com.kovedash.app.proto

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Minimal JSON message builders for the OLD ThinkerRide protocol (our 2022 firmware).
 *
 * Format on the wire:
 *   BLE (ffe1): wrapped by ByteCat.framesFor(...) -> 104-byte chunks
 *   17818 TCP: wrapped by DashJsonEnvelope.encode(...) -> EE FD <len:be4> <json> FF
 *
 * BLE carries the handshake, telemetry pushes (weather / altitude), and native
 * turn-by-turn. The 17818 TCP side carries the dash's own bootstrap chatter
 * (firmware/MAC/device-type).
 */
object DashMessages {

    /** 17818 binary heartbeat — phone sends this to dash every 2s. */
    val HEARTBEAT_17818: ByteArray = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00)

    /** type=01 sub=0E — requestProductType (binary, not JSON). */
    val REQ_PRODUCT_TYPE_BIN: ByteArray = byteArrayOf(0x01, 0x0E, 0x00, 0x00, 0x00, 0x00)

    /** type=01 sub=11 — requestMac (binary, not JSON). */
    val REQ_MAC_BIN: ByteArray = byteArrayOf(0x01, 0x11, 0x00, 0x00, 0x00, 0x00)


    /** msg_id 13 — phone asks dash for its version code. */
    fun requestVersionCode(): String = """{"msg_id":13}"""

    /** msg_id 24 — phone announces its identifier. */
    fun sendLinkInfo(uniqueInfo: String = "kovedash"): String =
        """{"msg_id":24,"unique_info":"${escape(uniqueInfo)}"}"""

    /** msg_id 26 — requestProductType. */
    fun requestProductType(): String = """{"msg_id":26}"""

    /** msg_id 54 — checkVehicleCurStatus. Dash gates 17818 dial on this in OEM handshake. */
    fun checkVehicleCurStatus(): String = """{"msg_id":54}"""

    /** msg_id 27 INSIDENAVI query=2 — queryDevicePlayerVoiceStatus. */
    fun queryDevicePlayerVoiceStatus(): String =
        """{"msg_id":27,"func":"INSIDENAVI","query":2}"""

    /** msg_id 27 INSIDENAVI query=1 — queryInsideNaviStatus. */
    fun queryInsideNaviStatus(): String =
        """{"msg_id":27,"func":"INSIDENAVI","query":1}"""

    /** msg_id 27 — generic GET with arbitrary func. */
    fun probeFunc(func: String, act: Int = 0): String =
        """{"msg_id":27,"func":"${escape(func)}","act":$act}"""

    /** msg_id 27, func=NAVI, act=1 — set destination (probe variant). */
    fun naviStatus(): String = """{"msg_id":27,"func":"NAVI","act":3}"""

    // ── Native turn-by-turn (non-projection). PROVEN WORKING (2026-07-30) on our SV=3.0.4
    // dash: the native arrow renders from these BLE-JSON messages alone — no video, no
    // bitmap, no 17818 binary frame. `icon` is the dash glyph enum from
    // MapUtils.getNavigationTurnCode (2=left, 3=right, 4=slight-left, 5=slight-right,
    // 6=sharp-left, 7=sharp-right, 8=uturn, 9=straight/fallback, 21=arrive, …).
    // The dash `cv` version selects the legacy (msg_id=1) vs modern (msg_id=27) branch, and
    // we don't read `cv`, so callers send BOTH shapes one-shot per turn and let the dash use
    // whichever it parses. The one hard requirement is a QUIET BLE link so the multi-frame
    // message can reassemble — see forwardTbtInternal in DashService for the full recipe.
    // `{"msg_id":15}` clears the widget.

    /** msg_id 15 — endNavi; tears down the native nav widget. JsonManager:166. */
    fun endNavi(): String = """{"msg_id":15}"""

    /**
     * msg_id 1 — legacy native TBT (empty-`cv` dashes, i.e. our SV=3.0.4). Full 6-field
     * shape: icon + next_road + cur_retain_distance (meters to the turn) + path_retain_distance
     * + remain_time. This spans 2–3 BLE frames; the dash reassembles it fine PROVIDED the
     * link is quiet (no competing traffic while the frames land). One of the two shapes
     * forwardTbtInternal sends per turn. JsonManager.sendNaviInfoOld:1409.
     */
    fun naviLegacy(icon: Int, nextRoad: String, curMeters: Int, pathMeters: Int, remainSec: Int): String =
        """{"msg_id":1,"icon":$icon,"next_road":"${escape(nextRoad)}","cur_retain_distance":$curMeters,"path_retain_distance":$pathMeters,"remain_time":$remainSec}"""

    /**
     * msg_id 27 func=NAVI act=3 — modern native TBT (non-empty `cv`). Field semantics
     * verified against MapboxNaviHelper.java:283-320:
     *   cur/path_retain_distance — STRING; meters ("%d") if <1000m, else km ("%.1f")
     *   cur/path_cur_unittype    — 0=m, 1=km (threshold is 1000m)
     *   cur_retain_time          — int SECONDS to next maneuver
     *   remain_time              — LONG unix-epoch seconds of DESTINATION arrival (trip ETA)
     *   retain_rate              — int 0..100, percent of route TRAVELED (not remaining)
     * Distances forced to Locale.US so decimals use dots (the OEM's unlocalized
     * String.format emits comma-decimals in EU locales, which the dash mis-parses).
     * JsonManager.getNaviInfo:209.
     */
    fun naviModern(
        icon: Int,
        nextRoad: String,
        curMeters: Int,
        pathMeters: Int,
        curRetainSec: Int,
        etaEpochSec: Long,
        retainRatePct: Int,
    ): String {
        val (curStr, curUnit) = distanceField(curMeters)
        val (pathStr, pathUnit) = distanceField(pathMeters)
        return """{"msg_id":27,"func":"NAVI","act":3,"icon":$icon,"next_road":"${escape(nextRoad)}","cur_retain_distance":"$curStr","cur_unittype":$curUnit,"path_retain_distance":"$pathStr","path_cur_unittype":$pathUnit,"cur_retain_time":$curRetainSec,"remain_time":$etaEpochSec,"retain_rate":$retainRatePct}"""
    }

    /** meters<1000 → ("%d", 0=m); else → ("%.1f" km, 1=km). Locale.US forces dot-decimal. */
    private fun distanceField(meters: Int): Pair<String, Int> =
        if (meters < 1000) meters.toString() to 0
        else String.format(java.util.Locale.US, "%.1f", meters / 1000.0) to 1

    // NOTE: the OEM also fires a PARALLEL 17818 TCP binary nav frame (type=0x03 sub=0x01)
    // alongside the BLE JSON. We DON'T need it — the BLE-JSON messages above (msg_id=1 +
    // msg_id=27) drive the native nav card on their own once the link is quiet. The binary
    // builder was removed after that was proven; resurrect from git history if a future
    // firmware ever requires the TCP half.

    /** msg_id 50 — bound-ID forge (irrelevant for SV=3.0.4 but kept for completeness). */
    fun bidForge(bid: String): String = """{"msg_id":50,"bid":"${escape(bid)}"}"""

    /**
     * msg_id 27 / func=CAR_INFO / act=get_car_info — query dash capabilities. OEM apps
     * (green_trip + cn_thinkerride) send this during handshake and parse the response
     * for capability flags (`altitude:1`, `weather:1`, etc.). Without this query the
     * dash may not enable native altitude/weather widget rendering even if we're
     * pushing the data. Sent right after setTime in our handshake.
     */
    fun requestCarInfo(): String =
        """{"msg_id":27,"func":"CAR_INFO","act":"get_car_info"}"""

    /**
     * msg_id 11 — push current wall-clock time as `yyyy-MM-dd HH:mm:ss` in the phone's
     * LOCAL timezone (dash has no tz field). [tag] = -1 marks unsolicited (the
     * post-version handshake burst); when the dash sends msg_id=10 item=4 with a tag,
     * we echo that tag back. Source: green_trip d72.java:905-921; cn_thinkerride
     * n01.java:884-900; reaction logic at greentrip.md §5.4.
     */
    fun setTime(now: LocalDateTime = LocalDateTime.now(), tag: Int = -1): String {
        val s = now.format(TIME_FORMAT)
        return """{"msg_id":11,"time":"$s","tag":$tag}"""
    }

    /** Sentinel time probe: TODAY'S date (so the dash won't reject it as implausible)
     *  but the clock forced to 03:33:33 — obviously different from the real time, so a
     *  change is unmistakable. If the dash clock jumps to 03:33, msg_id=11 drives it. */
    fun setTimeFixed(now: LocalDateTime = LocalDateTime.now()): String {
        val s = now.withHour(3).withMinute(33).withSecond(33).format(TIME_FORMAT)
        return """{"msg_id":11,"time":"$s","tag":-1}"""
    }

    /**
     * msg_id 25 / msg_type 1 / control_info 1 — start ride. Per
     * _re_report_thinkerride.md §3a-ii, ride-control message with control_info enum
     * (1=start, 2=pause, 3=stop, 4=report). Zeros for all stats since we're at t=0.
     *
     * NOT sent in the current handshake — see DashService.runHandshake. It flips the dash
     * into ride/telemetry mode and off the nav page, and its traffic contributed to the
     * link noise that blocked native turn-by-turn reassembly. Kept as a builder for future
     * experiments; the live path leaves the dash quiet instead.
     */
    fun startRide(): String =
        """{"msg_id":25,"msg_type":1,"msg_source":2,"control_info":1,"time":0,"calorie":0,"max_speed":0,"ave_speed":0.0,"total_deep":0,"ave_altitude":0}"""

    /**
     * msg_id 25 / msg_type 9 — altitude + climb stats push. OEM SiQi shape:
     * d72.java:939-960. Despite the field name, `head` in the OEM is total-descent-
     * meters, not heading/bearing (per thinkerride.md §6.3). Per-GPS-update pushes
     * use `(altitude, 0, 0.0, 0, 0, 0)` — only ride-completion fills the other
     * fields with real stats.
     */
    fun setAltitude(
        altitudeMeters: Int,
        aveAltitudeMeters: Int,
        maxAltitudeMeters: Int,
        headingDeg: Int = 0,
        pondDistance: Double = 0.0,
        pondTimeSec: Int = 0,
    ): String =
        """{"msg_id":25,"msg_type":9,"msg_source":2,"altitude":$altitudeMeters,"ave_altitude":$aveAltitudeMeters,"max_altitude":$maxAltitudeMeters,"pond_distance":$pondDistance,"pond_time":$pondTimeSec,"head":$headingDeg}"""

    /**
     * msg_id 25 / msg_type 11 — weather push. OEM ThinkerRide n01.java:902-935.
     * `weather` is an int icon code (0-N, dash-firmware-defined; we don't know
     * the full mapping — empirically discover by sending values and watching the
     * glyph). `temperature` and `wind_power` are free-form strings displayed as-is.
     */
    fun setWeather(weatherCode: Int, temperature: String, windPower: String): String =
        """{"msg_id":25,"msg_type":11,"msg_source":2,"weather":$weatherCode,"temperature":"${escape(temperature)}","wind_power":"${escape(windPower)}"}"""

    /**
     * msg_id 25 msg_type 14 — set the dash's display unit system for app-pushed values.
     * ThinkerRide JsonManager.sendSetUnit:2033 (`{msg_id:25,msg_type:14,msg_source:2,unit:N}`);
     * value mapping from DeviceSettingActivity's picker: **1 = metric, 2 = imperial** (British).
     * We push distances in meters/km (cur/path_unittype 0/1, same as the OEM) and rely on this
     * setting for the dash to convert to the rider's unit (miles/feet). Without it the nav widget
     * defaults to metric even when the dash's own odo menu is imperial.
     */
    fun setUnit(imperial: Boolean): String =
        """{"msg_id":25,"msg_type":14,"msg_source":2,"unit":${if (imperial) 2 else 1}}"""

    /**
     * msg_id 27 func=MUSIC act=ret_msg — now-playing metadata pushed to the dash.
     * JsonManager.sendMusicPlayInfo:1354. title/author/album/lyrics are display strings.
     */
    fun musicPlayInfo(title: String, author: String, album: String, lyrics: String = ""): String =
        """{"msg_id":27,"func":"MUSIC","act":"ret_msg","title":"${escape(title)}","author":"${escape(author)}","album":"${escape(album)}","lyrics":"${escape(lyrics)}"}"""

    /** msg_id 27 func=MUSIC act=ret_status — playback state (0=stopped/paused, 1=playing;
     *  dash-defined). JsonManager.sendMusicStatus:1372. */
    fun musicStatus(status: Int): String =
        """{"msg_id":27,"func":"MUSIC","act":"ret_status","status":$status}"""

    /** msg_id 22 — current speed to the dash (units dash-defined). JsonManager.sendCurrentSpeed:1097. */
    fun currentSpeed(speed: Int): String =
        """{"msg_id":22,"cur_speed":$speed}"""

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * Tiny key extraction. We do not pull in a JSON parser because the surface area is small
 * and we just need to grab a few primitive values from the dash's flat messages.
 */
object MiniJson {
    fun string(json: String, key: String): String? = capture(json, "\"$key\"\\s*:\\s*\"([^\"]*)\"")
    fun number(json: String, key: String): String? = capture(json, "\"$key\"\\s*:\\s*([0-9.\\-]+)")
    fun any(json: String, key: String): String? = string(json, key) ?: number(json, key)

    private fun capture(json: String, pattern: String): String? =
        Regex(pattern).find(json)?.groupValues?.getOrNull(1)
}
