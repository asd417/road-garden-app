package ai.roadgarden

import android.Manifest
import android.app.Activity
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.hardware.property.EvChargeState
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import org.json.JSONObject
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var car: Car? = null
    private var vehicle: Vehicle? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val main = Handler(Looper.getMainLooper())
    // 마지막 차량 값. 페이지 로드가 끝나면 다시 넣는다. 회생제동은 읽을 수 있는 차량 데이터가 없어 꺼 둠
    private val latest = linkedMapOf("regen" to "false")
    private var chargeState = false; private var chargeSwitch = false; private var portOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) // chrome://inspect
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true // 정원 상태를 localStorage에 저장 (시동을 다시 걸어도 유지)
            addJavascriptInterface(Speech(), "AndroidTTS")
            webChromeClient = object : WebChromeClient() { // JS 콘솔 → logcat (adb logcat -s RoadGarden)
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.i(TAG, "${m.messageLevel()} ${m.message()} (${m.sourceId()}:${m.lineNumber()})"); return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) = push()
                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                    val url = req.url.toString()
                    // three.js는 CDN 대신 앱에 포함된 파일로 (차량에 네트워크가 없을 수 있음)
                    if (url.startsWith(THREE_CDN)) {
                        val local = assets.shouldInterceptRequest(Uri.parse(ASSETS + "vendor/three/" + url.removePrefix(THREE_CDN)))
                        return local?.apply { responseHeaders = mapOf("Access-Control-Allow-Origin" to "*") }
                    }
                    return assets.shouldInterceptRequest(req.url)
                }
            }
            // debug 빌드에서만 🐞 디버그 패널
            loadUrl(ASSETS + "web/prototype.html#demo=1" + if (BuildConfig.DEBUG) "&debug=1" else "")
        }
        setContentView(web)

        // ponytail: Android 기본 TTS. Pleos TextToSpeech SDK가 공개되면 교체
        tts = TextToSpeech(this) { status ->
            val lang = tts?.setLanguage(Locale.KOREA) ?: -1
            ttsReady = status == TextToSpeech.SUCCESS && lang >= TextToSpeech.LANG_AVAILABLE
            tts?.setSpeechRate(1.05f); tts?.setPitch(1.15f) // 살짝 빠르고 밝게
            Log.i(TAG, "tts ready=$ttsReady (status=$status lang=$lang)") // 엔진이 없으면 자막만 (Pleos 에뮬레이터는 Pleos TTS SDK 필요)
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) = spoken(id)
            @Deprecated("Deprecated in Java") override fun onError(id: String) = spoken(id)
            override fun onStop(id: String, interrupted: Boolean) {} // 출발로 끊긴 발화는 JS가 이미 처리
        })

        vehicle = Vehicle(this).also { it.initialize(); listenPleos(it) }
        car = Car.createCar(this)
        listenUxRestrictions()
        val perms = arrayOf(Car.PERMISSION_SPEED, Car.PERMISSION_ENERGY, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) onPermissions()
        else requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) = onPermissions()

    private fun onPermissions() { listenCar(); updateWeather() }

    // JS 음성 → Android TTS. 발화가 끝나면 roadGarden.spoken(id)로 알려 다음 안내를 이어 간다
    inner class Speech {
        @JavascriptInterface fun speak(text: String, id: String): Boolean =
            ttsReady && tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
        @JavascriptInterface fun stop() { tts?.stop() }
    }
    private fun spoken(id: String) = runOnUiThread { web.evaluateJavascript("roadGarden.spoken('$id')", null) }

    // AAOS 표준 속성: 속도·기어·주차 브레이크·충전
    private fun listenCar() {
        val props = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) {
                when (v.propertyId) {
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> set("speed", "${(v.value as Float) * 3.6f}") // m/s → km/h
                    VehiclePropertyIds.GEAR_SELECTION -> set("gear", "'${GEARS[v.value as Int] ?: "D"}'")
                    VehiclePropertyIds.PARKING_BRAKE_ON -> set("pbrake", "${v.value}")
                    VehiclePropertyIds.EV_CHARGE_STATE -> { chargeState = v.value == EvChargeState.STATE_CHARGING; setCharging() }
                    VehiclePropertyIds.EV_CHARGE_SWITCH -> { chargeSwitch = v.value == true; setCharging() }
                    VehiclePropertyIds.EV_CHARGE_PORT_OPEN -> { portOpen = v.value == true; setCharging() }
                }
            }
            override fun onErrorEvent(propId: Int, zone: Int) { Log.w(TAG, "property error $propId") }
        }
        for (id in listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED, VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.PARKING_BRAKE_ON,
                          VehiclePropertyIds.EV_CHARGE_STATE, VehiclePropertyIds.EV_CHARGE_SWITCH, VehiclePropertyIds.EV_CHARGE_PORT_OPEN)) {
            try { props.registerCallback(cb, id, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
            catch (e: Exception) { Log.w(TAG, "cannot listen to $id", e) } // 권한 없음·미지원 속성
        }
    }

    // 충전 중: 충전 상태가 '충전 중'이거나, 충전구가 열린 채 충전 스위치가 켜졌을 때 (에뮬레이터는 뒤쪽 신호만 바뀜)
    private fun setCharging() = set("charging", "${chargeState || (chargeSwitch && portOpen)}")

    // 주행 중 UX 제한: 플랫폼이 제한하면 정원은 앰비언트 화면만 (터치·텍스트 없음)
    private fun listenUxRestrictions() {
        val ux = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
        ux.currentCarUxRestrictions?.let { set("restricted", "${it.isRequiresDistractionOptimization}") }
        ux.registerListener { set("restricted", "${it.isRequiresDistractionOptimization}") }
    }

    // Pleos Vehicle SDK: 브레이크 페달·와이퍼
    private fun listenPleos(v: Vehicle) {
        val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val fail = { what: String -> { e: Exception -> Log.w(TAG, what, e); Unit } }
        // 등록만으로는 현재 값이 오지 않아 한 번 읽어 둔다
        v.getBrake().getCurrentBrakePedalStatus({ set("brake", "$it") }, fail("brake"))
        v.getWiper().getCurrentWiperState(front, { set("wiper", "${it == WiperState.ON}") }, fail("wiper"))
        v.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(on: Boolean?) = set("brake", "${on == true}")
            override fun onFailed(e: Exception) { Log.w(TAG, "brake", e) }
        })
        v.getWiper().registerWiperState(front, object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) = set("wiper", "${state == WiperState.ON}")
            override fun onFailed(e: Exception) { Log.w(TAG, "wiper", e) }
        })
    }

    // 날씨: 현재 위치(약 1km로 반올림)로 Open-Meteo 조회, 30분마다 (실패하면 1분 뒤 다시)
    // ponytail: Android 기본 위치. Pleos FusedLocation SDK가 공개되면 교체
    private fun updateWeather() {
        val retry = { min: Long -> main.removeCallbacksAndMessages(WEATHER); main.postDelayed(::updateWeather, WEATHER, min * 60 * 1000L) }
        retry(1)
        val lm = getSystemService(LocationManager::class.java)
        val loc = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
                .firstNotNullOfOrNull { lm.getLastKnownLocation(it) }
        } catch (e: SecurityException) { null } ?: return Log.w(TAG, "no location for weather").let {}
        thread {
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code"
                    .format(Locale.US, loc.latitude, loc.longitude)
                val cur = JSONObject(URL(url).readText()).getJSONObject("current")
                val code = cur.getInt("weather_code")
                val sky = when (code) { 0, 1 -> "맑음"; in 71..77, 85, 86 -> "눈"; in 51..67, in 80..82, in 95..99 -> "비"; else -> "흐림" }
                set("weather", "'$sky'"); set("temp", "${cur.getDouble("temperature_2m")}")
                main.post { retry(30) }
            } catch (e: Exception) { Log.w(TAG, "weather: ${e.message}") } // 네트워크 없음: 마지막 날씨 유지, 1분 뒤 다시
        }
    }

    // SDK 콜백은 다른 스레드에서 올 수 있어 UI 스레드에서 모아 넣는다
    private fun set(key: String, value: String) = runOnUiThread {
        if (latest.put(key, value) != value) { Log.d(TAG, "vehicle $key = $value"); push() }
    }

    private fun push() {
        val js = latest.entries.joinToString { "${it.key}: ${it.value}" }
        web.evaluateJavascript("window.roadGarden && roadGarden.vehicle({$js})", null)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        tts?.shutdown()
        vehicle?.release()
        car?.disconnect()
        super.onDestroy()
    }

    companion object {
        const val TAG = "RoadGarden"
        val WEATHER = Any() // 날씨 갱신 예약 표식
        const val ASSETS = "https://appassets.androidplatform.net/assets/"
        const val THREE_CDN = "https://cdn.jsdelivr.net/npm/three@0.160.0/"
        val GEARS = mapOf(VehicleGear.GEAR_PARK to "P", VehicleGear.GEAR_DRIVE to "D", VehicleGear.GEAR_NEUTRAL to "N", VehicleGear.GEAR_REVERSE to "R")
    }
}
