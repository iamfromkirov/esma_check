package com.example.connectioncheck

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class MainActivity : Activity() {

    // ───────────── Модели ─────────────

    private data class Target(val label: String, val url: String, val smsName: String)

    private data class NetResult(
        val label: String,
        val ok: Boolean,
        val details: String, // для экрана
        val shortText: String // для SMS
    )

    private data class MockInfo(
        val devOptions: Boolean,
        val selectedApps: List<String>, // приложения, выбранные в настройках разработчика как mock
        val candidateApps: List<String> // установленные приложения с правом ACCESS_MOCK_LOCATION
    )

    // ───────────── Что проверяем ─────────────

    private val targets = listOf(
        Target("МТС (mts.ru)", "https://mts.ru", "MTS"),
        Target("МРМ (10.146.130.17:80)", "http://10.146.130.17:80", "MRM"),
        Target("ЕСМА Центр (10.146.130.75:7780)", "http://10.146.130.75:7780", "ESMA")
    )

    // ───────────── Состояние ─────────────

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var btnCheck: Button
    private lateinit var btnSmsDirect: Button
    private lateinit var btnSmsApp: Button
    private lateinit var btnCopy: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvReport: TextView
    private lateinit var etPhone: EditText

    private val fixes = linkedMapOf<String, Location>()
    private var satsVisible = -1
    private var satsUsed = -1
    private var satsByConstellation = ""
    private var ttffMs = -1
    private var gnssRegistered = false

    private var imeiValue: String? = null
    private var imeiText = "—"
    private var operatorText = "—"
    private var simLines = "—"
    private var simSms = "SIM:n/a"
    private var mockInfo = MockInfo(false, emptyList(), emptyList())

    private var netResults: List<NetResult> = emptyList()
    private var wifiText = "Wi-Fi: не проверялось"
    private var wifiSms = "WIFI:n/a"
    private var lastCheckTime = ""
    private var checking = false

    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }

    // ───────────── Жизненный цикл ─────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnCheck = findViewById(R.id.btnCheck)
        btnSmsDirect = findViewById(R.id.btnSmsDirect)
        btnSmsApp = findViewById(R.id.btnSmsApp)
        btnCopy = findViewById(R.id.btnCopy)
        tvStatus = findViewById(R.id.tvStatus)
        tvReport = findViewById(R.id.tvReport)
        etPhone = findViewById(R.id.etPhone)

        etPhone.setText(prefs.getString("phone", ""))

        btnCheck.setOnClickListener { runCheck() }
        btnSmsDirect.setOnClickListener { confirmAndSendSms() }
        btnSmsApp.setOnClickListener { sendSmsViaApp() }
        btnCopy.setOnClickListener { copyReport() }

        val need = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_INIT)

        render()
    }

    override fun onResume() {
        super.onResume()
        startLocation()
        refreshStaticAsync()
    }

    override fun onPause() {
        super.onPause()
        stopLocation()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_INIT -> {
                startLocation()
                refreshStaticAsync()
            }
            REQ_SMS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    confirmAndSendSms()
                } else {
                    toast("Без разрешения SEND_SMS отправка невозможна. Используйте «SMS через приложение».")
                }
            }
        }
    }

    // ───────────── Геолокация / GPS ─────────────

    private fun hasLocationPerm() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            fixes[location.provider ?: "?"] = location
            render()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {
            render()
        }

        override fun onProviderDisabled(provider: String) {
            render()
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            val perConst = linkedMapOf<String, Int>()
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) {
                    used++
                    val name = when (status.getConstellationType(i)) {
                        GnssStatus.CONSTELLATION_GPS -> "GPS"
                        GnssStatus.CONSTELLATION_GLONASS -> "ГЛОНАСС"
                        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
                        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                        else -> "др."
                    }
                    perConst[name] = (perConst[name] ?: 0) + 1
                }
            }
            satsVisible = status.satelliteCount
            satsUsed = used
            satsByConstellation = perConst.entries.joinToString(", ") { "${it.key} ${it.value}" }
            render()
        }

        override fun onFirstFix(ttffMillis: Int) {
            ttffMs = ttffMillis
            render()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPerm()) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) providers.add(LocationManager.FUSED_PROVIDER)

        for (p in providers) {
            if (!lm.allProviders.contains(p)) continue
            try {
                lm.getLastKnownLocation(p)?.let { fixes[p] = it }
                lm.requestLocationUpdates(p, 1000L, 0f, locListener, Looper.getMainLooper())
            } catch (_: Exception) {
            }
        }
        if (!gnssRegistered) {
            try {
                @Suppress("DEPRECATION")
                lm.registerGnssStatusCallback(gnssCallback, ui)
                gnssRegistered = true
            } catch (_: Exception) {
            }
        }
        render()
    }

    private fun stopLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            lm.removeUpdates(locListener)
        } catch (_: Exception) {
        }
        if (gnssRegistered) {
            try {
                lm.unregisterGnssStatusCallback(gnssCallback)
            } catch (_: Exception) {
            }
            gnssRegistered = false
        }
    }

    private fun ageSec(l: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000L

    private fun bestFix(): Location? {
        val gps = fixes[LocationManager.GPS_PROVIDER]
        if (gps != null && ageSec(gps) < 60) return gps
        return fixes.values.maxByOrNull { it.elapsedRealtimeNanos }
    }

    @Suppress("DEPRECATION")
    private fun isMock(l: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider

    private fun sourceText(l: Location): String = when (l.provider) {
        LocationManager.GPS_PROVIDER -> "GPS (спутники)"
        LocationManager.NETWORK_PROVIDER -> "Network (Wi-Fi / вышки сотовой связи)"
        "fused" -> "Fused (комбинированный, Google)"
        "passive" -> "Passive (от других приложений)"
        else -> l.provider ?: "неизвестно"
    }

    // ───────────── Fake GPS ─────────────

    @Suppress("DEPRECATION")
    private fun collectMockInfo(): MockInfo {
        val dev = try {
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }
        val pm = packageManager
        val pkgs = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            }
        } catch (_: Exception) {
            emptyList()
        }
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val candidates = mutableListOf<String>()
        val selected = mutableListOf<String>()
        for (p in pkgs) {
            val ai = p.applicationInfo ?: continue
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            if (p.requestedPermissions?.contains("android.permission.ACCESS_MOCK_LOCATION") != true) continue
            val label = "${pm.getApplicationLabel(ai)} (${p.packageName})"
            candidates += label
            val mode = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ops.unsafeCheckOpNoThrow("android:mock_location", ai.uid, p.packageName)
                } else {
                    ops.checkOpNoThrow("android:mock_location", ai.uid, p.packageName)
                }
            } catch (_: Exception) {
                AppOpsManager.MODE_ERRORED
            }
            if (mode == AppOpsManager.MODE_ALLOWED) selected += label
        }
        return MockInfo(dev, selected, candidates)
    }

    // ───────────── IMEI / оператор ─────────────

    @Suppress("DEPRECATION")
    private fun legacyDeviceId(tm: TelephonyManager): String? = tm.deviceId

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun readImei(): Pair<String?, String> {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return null to "нет разрешения READ_PHONE_STATE"
        }
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        return try {
            val raw = mutableListOf<String>()
            val labeled = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 26) {
                val n = maxOf(tm.phoneCount, 1)
                for (i in 0 until n) tm.getImei(i)?.let {
                    raw += it
                    labeled += "слот ${i + 1}: $it"
                }
            } else {
                legacyDeviceId(tm)?.let { raw += it }
            }
            if (raw.isEmpty()) {
                null to "недоступен (нет модема/SIM)"
            } else {
                // для SMS - только номера, для экрана - с подписью слота
                raw.joinToString(" / ") to (if (raw.size > 1) labeled else raw).joinToString(" / ")
            }
        } catch (_: SecurityException) {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            null to "недоступен: Android 10+ не выдаёт IMEI обычным приложениям. " +
                "Наберите *#06#. Android ID: $androidId"
        } catch (e: Exception) {
            null to "ошибка: ${e.javaClass.simpleName}"
        }
    }

    // ───────────── SIM-карты (двухсимочные телефоны) ─────────────

    /** Возвращает (текст для экрана, короткий текст для SMS). */
    @SuppressLint("MissingPermission")
    private fun collectSim(): Pair<String, String> {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return "нет разрешения READ_PHONE_STATE" to "SIM:n/a"
        }
        return try {
            val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subs = (sm.activeSubscriptionInfoList ?: emptyList()).sortedBy { it.simSlotIndex }
            if (subs.isEmpty()) {
                "SIM-карта не обнаружена" to "SIM:none"
            } else {
                val dataId = SubscriptionManager.getDefaultDataSubscriptionId()
                val smsId = SubscriptionManager.getDefaultSmsSubscriptionId()
                val voiceId = SubscriptionManager.getDefaultVoiceSubscriptionId()

                val sb = StringBuilder()
                for (sub in subs) {
                    val marks = mutableListOf<String>()
                    if (sub.subscriptionId == dataId) marks += "данные"
                    if (sub.subscriptionId == smsId) marks += "SMS"
                    if (sub.subscriptionId == voiceId) marks += "звонки"
                    val name = sub.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "?"
                    sb.append("  SIM ${sub.simSlotIndex + 1}: $name")
                    if (Build.VERSION.SDK_INT >= 28 && sub.isEmbedded) sb.append(" (eSIM)")
                    if (marks.isNotEmpty()) sb.append(" — по умолчанию: ").append(marks.joinToString(", "))
                    sb.appendLine()
                }

                val dataSlot = subs.firstOrNull { it.subscriptionId == dataId }?.let { it.simSlotIndex + 1 }
                val smsSlot = subs.firstOrNull { it.subscriptionId == smsId }?.let { it.simSlotIndex + 1 }
                val type = connectionType()
                val conn = when (type) {
                    "мобильная сеть" ->
                        if (dataSlot != null) "мобильная сеть через SIM $dataSlot" else "мобильная сеть (SIM не определена)"
                    "Wi-Fi" -> "Wi-Fi, SIM не используется (мобильные данные: SIM ${dataSlot ?: "?"})"
                    else -> type
                }
                sb.append("Соединение идёт через: $conn")

                val net = when (type) {
                    "мобильная сеть" -> "mobile"
                    "Wi-Fi" -> "wifi"
                    "VPN" -> "vpn"
                    else -> "other"
                }
                sb.toString() to "SIM:${subs.size}sim data=${dataSlot ?: "?"} sms=${smsSlot ?: "?"} net=$net" +
                    if (net == "mobile") "/sim${dataSlot ?: "?"}" else ""
            }
        } catch (e: Exception) {
            "ошибка: ${e.javaClass.simpleName}" to "SIM:n/a"
        }
    }

    private fun refreshStaticAsync() {
        Thread {
            val (imei, imeiTxt) = readImei()
            val mock = collectMockInfo()
            val sim = collectSim()
            val op = try {
                val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                tm.networkOperatorName.takeIf { it.isNotBlank() } ?: "нет сети/SIM"
            } catch (_: Exception) {
                "—"
            }
            ui.post {
                imeiValue = imei
                imeiText = imeiTxt
                mockInfo = mock
                operatorText = op
                simLines = sim.first
                simSms = sim.second
                render()
            }
        }.start()
    }

    // ───────────── Сеть: сайты и Wi-Fi ─────────────

    private fun checkTarget(t: Target): NetResult {
        val u = URL(t.url)
        val port = if (u.port != -1) u.port else u.defaultPort
        val t0 = SystemClock.elapsedRealtime()
        try {
            Socket().use { it.connect(InetSocketAddress(u.host, port), 4000) }
        } catch (e: Exception) {
            val why = e.javaClass.simpleName + (e.message?.let { ": " + it.take(50) } ?: "")
            return NetResult(
                t.label, false,
                "НЕДОСТУПЕН — TCP-соединение не установлено ($why)",
                "FAIL tcp ${e.javaClass.simpleName}"
            )
        }
        val tcpMs = SystemClock.elapsedRealtime() - t0

        val t1 = SystemClock.elapsedRealtime()
        var conn: HttpURLConnection? = null
        return try {
            conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 6000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "ConnectionCheck/1.0")
            val code = conn.responseCode // любой HTTP-ответ = сервер доступен
            val ms = SystemClock.elapsedRealtime() - t1
            NetResult(
                t.label, true,
                "ДОСТУПЕН — HTTP $code, TCP $tcpMs мс, ответ $ms мс",
                "OK $code ${ms}ms"
            )
        } catch (e: Exception) {
            val why = e.javaClass.simpleName + (e.message?.let { ": " + it.take(50) } ?: "")
            NetResult(
                t.label, false,
                "порт открыт (TCP $tcpMs мс), но HTTP не отвечает ($why)",
                "TCP ok, HTTP fail"
            )
        } finally {
            conn?.disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun collectWifi(): Pair<String, String> {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) return "Wi-Fi: выключен" to "WIFI:off"

        val sb = StringBuilder()
        val sms = StringBuilder("WIFI:")

        val ci = wm.connectionInfo
        val ssid = ci?.ssid?.trim('"') ?: ""
        val connected = ci != null && ci.networkId != -1
        if (connected) {
            val shown = if (ssid.isEmpty() || ssid == "<unknown ssid>") "скрыто (нужна геолокация)" else ssid
            sb.appendLine("Подключено к: $shown")
            sb.appendLine("  BSSID: ${ci.bssid ?: "?"}")
            sb.appendLine("  Сигнал ${ci.rssi} dBm, ${ci.linkSpeed} Мбит/с, ${ci.frequency} МГц")
            sms.append("conn ").append(shown).append(' ').append(ci.rssi).append("dBm")
        } else {
            sb.appendLine("Подключено к: нет")
            sms.append("not conn")
        }

        val results = try {
            wm.scanResults ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }.sortedByDescending { it.level }

        if (results.isEmpty()) {
            sb.appendLine("Найденные сети: нет данных (включите геолокацию; Android ограничивает частоту сканирования)")
            sms.append("; scan empty")
        } else {
            sb.appendLine("Найденные сети (${results.size}):")
            results.take(15).forEachIndexed { i, r ->
                val name = r.SSID.ifEmpty { "<скрытая>" }
                sb.appendLine("  ${i + 1}. $name  ${r.level} dBm  ${r.frequency} МГц  ${r.BSSID}")
            }
            sms.append("; n=").append(results.size).append("; top: ")
            sms.append(results.take(3).joinToString(", ") { "${it.SSID.ifEmpty { "hidden" }} ${it.level}" })
        }
        return sb.toString().trimEnd() to sms.toString()
    }

    /** VPN: (текст для экрана, короткий текст для SMS). */
    @Suppress("DEPRECATION")
    private fun collectVpn(): Pair<String, String> {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val vpns = cm.allNetworks.filter {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            if (vpns.isEmpty()) {
                "VPN: не обнаружен" to "VPN:off"
            } else {
                val sb = StringBuilder("VPN: ПОДКЛЮЧЕН")
                val sms = StringBuilder("VPN:on")
                for (n in vpns) {
                    val iface = cm.getLinkProperties(n)?.interfaceName ?: "?"
                    val validated = cm.getNetworkCapabilities(n)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                    val routed = n == active
                    sb.append("\n  интерфейс $iface; интернет через VPN: ")
                        .append(if (validated) "работает" else "не подтверждён")
                        .append("; трафик приложения идёт через VPN: ")
                        .append(if (routed) "да" else "нет")
                    sms.append(" if=").append(iface)
                        .append(" net=").append(if (validated) "ok" else "noinet")
                        .append(" route=").append(if (routed) "yes" else "no")
                }
                sb.toString() to sms.toString()
            }
        } catch (e: Exception) {
            "VPN: ошибка (${e.javaClass.simpleName})" to "VPN:n/a"
        }
    }

    private fun connectionType(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "нет подключения"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная сеть"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "другое"
        }
    }

    @Suppress("DEPRECATION")
    private fun runCheck() {
        if (checking) return
        checking = true
        btnCheck.isEnabled = false
        tvStatus.text = "Проверка идёт…"

        Thread {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                try {
                    wm.startScan()
                } catch (_: Exception) {
                }

                val mock = collectMockInfo()
                val (imei, imeiTxt) = readImei()
                val sim = collectSim()

                val pool = Executors.newFixedThreadPool(targets.size)
                val res = try {
                    pool.invokeAll(targets.map { t -> Callable { checkTarget(t) } }).map { it.get() }
                } finally {
                    pool.shutdown()
                }
                val (wText, wSms) = collectWifi()

                ui.post {
                    mockInfo = mock
                    imeiValue = imei
                    imeiText = imeiTxt
                    simLines = sim.first
                    simSms = sim.second
                    netResults = res
                    wifiText = wText
                    wifiSms = wSms
                    lastCheckTime = nowText()
                    checking = false
                    btnCheck.isEnabled = true
                    tvStatus.text = "Готово: $lastCheckTime"
                    render()
                }
            } catch (e: Exception) {
                ui.post {
                    checking = false
                    btnCheck.isEnabled = true
                    tvStatus.text = "Ошибка проверки: ${e.javaClass.simpleName}"
                }
            }
        }.start()
    }

    // ───────────── Отчёты ─────────────

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun f(d: Double, digits: Int = 6): String = String.format(Locale.US, "%.${digits}f", d)

    private enum class FakeLevel { NO, SUSPECT, LIKELY, YES }

    private fun fakeLevel(): Pair<FakeLevel, String> {
        val mockFlag = fixes.values.any { ageSec(it) < 120 && isMock(it) }
        val gps = fixes[LocationManager.GPS_PROVIDER]
        val gpsNoSats = gps != null && ageSec(gps) < 10 && satsUsed == 0 && gnssRegistered
        return when {
            mockFlag -> FakeLevel.YES to "ОБНАРУЖЕН — система помечает координаты как mock"
            mockInfo.selectedApps.isNotEmpty() ->
                FakeLevel.LIKELY to "ВЕРОЯТЕН — приложение выбрано источником фиктивных координат"
            gpsNoSats ->
                FakeLevel.SUSPECT to "ПОДОЗРИТЕЛЬНО — есть GPS-fix, но ни один спутник не участвует в решении"
            mockInfo.candidateApps.isNotEmpty() ->
                FakeLevel.SUSPECT to "подозрение — установлены приложения с правом mock, сейчас не активны"
            else -> FakeLevel.NO to "не обнаружен"
        }
    }

    private fun buildReport(): String {
        val sb = StringBuilder()
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager

        sb.appendLine("Время проверки: ${if (lastCheckTime.isEmpty()) "— (нажмите «Проверить»)" else lastCheckTime}")
        sb.appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()

        sb.appendLine("── ТЕЛЕФОН ──")
        sb.appendLine("IMEI: $imeiText")
        sb.appendLine("Оператор: $operatorText")
        sb.appendLine("Активное подключение: ${connectionType()}")
        sb.appendLine(collectVpn().first)
        sb.appendLine("SIM-карты:")
        sb.appendLine(simLines)
        sb.appendLine()

        sb.appendLine("── GPS ──")
        val gpsOn = try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
        sb.appendLine("Разрешение геолокации: ${if (hasLocationPerm()) "есть" else "НЕТ"}")
        sb.appendLine("Модуль GPS: ${if (gpsOn) "включён" else "ВЫКЛЮЧЕН в настройках"}")
        if (satsVisible < 0) {
            sb.appendLine("Спутники: ожидание данных…")
        } else {
            sb.append("Спутники: в поле зрения $satsVisible, в решении $satsUsed")
            if (satsByConstellation.isNotEmpty()) sb.append(" ($satsByConstellation)")
            sb.appendLine()
        }
        if (ttffMs >= 0) sb.appendLine("Время до первого fix: ${ttffMs / 1000.0} с")

        val fix = bestFix()
        if (fix == null) {
            sb.appendLine("Координаты: нет данных (выйдите на открытое место)")
        } else {
            sb.appendLine("Координаты: ${f(fix.latitude)}, ${f(fix.longitude)}")
            sb.appendLine(
                "Точность: " + if (fix.hasAccuracy()) "±${f(fix.accuracy.toDouble(), 1)} м" else "не определена"
            )
            sb.appendLine("Источник: ${sourceText(fix)}")
            if (fix.hasAltitude()) sb.appendLine("Высота: ${f(fix.altitude, 0)} м")
            if (fix.hasSpeed()) sb.appendLine("Скорость: ${f(fix.speed * 3.6, 1)} км/ч")
            sb.appendLine("Возраст данных: ${ageSec(fix)} с")
        }
        val others = fixes.filter { it.value !== fix }
        if (others.isNotEmpty()) {
            sb.appendLine("Другие источники:")
            for ((p, l) in others) {
                val acc = if (l.hasAccuracy()) "±${f(l.accuracy.toDouble(), 0)} м" else "?"
                sb.appendLine("  $p: ${f(l.latitude, 5)}, ${f(l.longitude, 5)} $acc, ${ageSec(l)} с назад")
            }
        }
        sb.appendLine()

        sb.appendLine("── FAKE GPS ──")
        val (_, verdict) = fakeLevel()
        sb.appendLine("Вердикт: $verdict")
        sb.appendLine("Флаг mock у координат: ${if (fixes.values.any { isMock(it) }) "ДА" else "нет"}")
        sb.appendLine("Режим разработчика: ${if (mockInfo.devOptions) "включён" else "выключен"}")
        sb.appendLine(
            "Выбрано как mock-приложение: " +
                if (mockInfo.selectedApps.isEmpty()) "нет" else mockInfo.selectedApps.joinToString("; ")
        )
        sb.appendLine(
            "Установлены приложения с правом mock: " +
                if (mockInfo.candidateApps.isEmpty()) "нет" else mockInfo.candidateApps.joinToString("; ")
        )
        sb.appendLine()

        sb.appendLine("── WI-FI ──")
        sb.appendLine(wifiText)
        sb.appendLine()

        sb.appendLine("── ДОСТУПНОСТЬ САЙТОВ ──")
        if (netResults.isEmpty()) {
            sb.appendLine("не проверялось")
        } else {
            for (r in netResults) {
                sb.appendLine("${if (r.ok) "✔" else "✘"} ${r.label}: ${r.details}")
            }
        }
        return sb.toString().trimEnd()
    }

    /** Компактный отчёт для SMS (только ASCII, чтобы уложиться в 160 символов на сегмент). */
    private fun buildSmsText(): String {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val gpsOn = try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
        val sb = StringBuilder()
        sb.append("CHK ").append(SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date())).append('\n')
        sb.append("IMEI:").append(imeiValue ?: "n/a").append('\n')
        sb.append(simSms).append('\n')
        sb.append(collectVpn().second).append('\n')

        val sats = "${if (satsUsed < 0) "-" else satsUsed.toString()}/${if (satsVisible < 0) "-" else satsVisible.toString()}"
        val fix = bestFix()
        if (fix == null) {
            sb.append("GPS:").append(if (gpsOn) "ON" else "OFF").append(" nofix sat ").append(sats)
        } else {
            val src = fix.provider ?: "?"
            val acc = if (fix.hasAccuracy()) f(fix.accuracy.toDouble(), 0) + "m" else "?"
            sb.append("GPS:").append(if (gpsOn) "ON " else "OFF ")
                .append(f(fix.latitude, 5)).append(',').append(f(fix.longitude, 5))
                .append(" acc ").append(acc).append(" src ").append(src)
                .append(" sat ").append(sats).append(" age ").append(ageSec(fix)).append('s')
        }
        sb.append('\n')

        val (level, _) = fakeLevel()
        sb.append("FAKE:").append(
            when (level) {
                FakeLevel.NO -> "no"
                FakeLevel.SUSPECT -> "suspect"
                FakeLevel.LIKELY -> "likely"
                FakeLevel.YES -> "YES"
            }
        ).append('\n')
        sb.append(wifiSms).append('\n')
        for (r in netResults) {
            val name = targets.firstOrNull { it.label == r.label }?.smsName ?: "node"
            sb.append(name).append(": ").append(r.shortText).append('\n')
        }
        if (netResults.isEmpty()) sb.append("sites: not checked\n")

        return sb.toString().trim().map { if (it.code in 32..126 || it == '\n') it else '?' }.joinToString("")
    }

    private fun render() {
        tvReport.text = buildReport()
    }

    // ───────────── SMS ─────────────

    private fun phoneOrNull(): String? {
        val number = etPhone.text.toString().trim()
        if (number.length < 3) {
            toast("Введите номер получателя")
            return null
        }
        prefs.edit().putString("phone", number).apply()
        return number
    }

    private fun confirmAndSendSms() {
        val number = phoneOrNull() ?: return
        if (lastCheckTime.isEmpty()) {
            toast("Сначала нажмите «Проверить»")
            return
        }
        val text = buildSmsText()
        AlertDialog.Builder(this)
            .setTitle("Отправить SMS на $number?")
            .setMessage(text)
            .setPositiveButton("Отправить") { _, _ -> sendSmsDirect(number, text) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    private fun sendSmsDirect(number: String, text: String) {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), REQ_SMS)
            return
        }
        try {
            val sm = smsManager()
            val parts = sm.divideMessage(text)
            val action = "$packageName.SMS_SENT"
            var remaining = parts.size
            var failed = 0

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (resultCode != RESULT_OK) failed++
                    remaining--
                    if (remaining <= 0) {
                        try {
                            unregisterReceiver(this)
                        } catch (_: Exception) {
                        }
                        toast(
                            if (failed == 0) "SMS отправлено (${parts.size} шт.)"
                            else "Ошибка отправки SMS (неудачных частей: $failed)"
                        )
                    }
                }
            }
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val sentIntents = ArrayList<PendingIntent>()
            for (idx in parts.indices) {
                sentIntents.add(
                    PendingIntent.getBroadcast(this, idx, Intent(action).setPackage(packageName), flags)
                )
            }
            sm.sendMultipartTextMessage(number, null, parts, sentIntents, null)
            toast("Отправка SMS…")
        } catch (e: Exception) {
            toast("Не удалось отправить: ${e.javaClass.simpleName}")
        }
    }

    private fun sendSmsViaApp() {
        val number = phoneOrNull() ?: return
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .putExtra("sms_body", buildSmsText())
        try {
            startActivity(intent)
        } catch (_: Exception) {
            toast("Приложение для SMS не найдено")
        }
    }

    private fun copyReport() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("report", buildReport()))
        toast("Отчёт скопирован")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_INIT = 1
        private const val REQ_SMS = 2
    }
}
