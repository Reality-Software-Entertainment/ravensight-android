package com.realityse.ravensight

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.realityse.ravensight.core.RavensightWire
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The delivery, ads and attribution, exposure and device field contracts
 * through the Android layer: the shared vectors (a copy of the server's
 * docs/sdk/delivery-vectors.json), the queue file across a restart, origins,
 * signing, gzip marking, the response table and the helpers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RavensightWaveTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var now = 1_800_000_000_000L
    private val worker = ManualWorker { now }
    private var http = FakeTransport()
    private var session = "ses_one"
    private var signing: JSONObject? = null
    private var configBody: String? = null

    @Before
    fun setUp() {
        Ravensight.resetForTest()
        app.getSharedPreferences(Ravensight.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        File(app.filesDir, "ravensight").deleteRecursively()
    }

    @After
    fun tearDown() {
        Ravensight.resetForTest()
        File(app.filesDir, "ravensight").deleteRecursively()
    }

    private fun config(persistQueue: Boolean = true) = RavensightConfig(
        ingestKey = "gt_pub_wave",
        apiUrl = "https://api.example.test/",
        gameVersion = "2.0.0",
        persistQueue = persistQueue,
        trackLifecycleEvents = false,
    )

    /** A fresh transport whose session answers carry an origin (and signing, when set). */
    private fun transport(): FakeTransport = FakeTransport().also { fake ->
        fake.override = { req ->
            when {
                req.path == "/session" -> {
                    val answer = JSONObject()
                        .put("token", "tok_$session").put("expiresIn", 3600)
                        .put("origin", JSONObject().put("session", session).put("build", "2.0.0").put("platform", "android").put("stamp", "o1.a.b"))
                    signing?.let { answer.put("signing", it) }
                    HttpResult(201, answer.toString(), null)
                }
                req.path.startsWith("/ingest/config?") && configBody != null -> HttpResult(200, configBody!!, null)
                else -> null
            }
        }
    }

    private fun launch(cfg: RavensightConfig = config(), device: Map<String, String> = mapOf("model" to "Pixel 7", "osVersion" to "14")) {
        Ravensight.initialize(
            app, cfg, null,
            RavensightDeps(
                transport = http, clockMs = { now }, worker = worker, mainPost = { it.run() },
                env = { null }, observeProcessLifecycle = false, registerActivityCallbacks = false,
                random = { 0.999 }, deviceFields = { device },
            ),
        )
        worker.runDue()
        Ravensight.controllerForTest!!.onForegroundSignal()
        worker.runDue()
    }

    private fun flush() {
        Ravensight.flush()
        worker.runDue()
    }

    private fun batches() = http.requests.filter { it.path == "/track/batch" }

    private fun events(): List<JSONObject> = batches().flatMap { r ->
        val arr = JSONObject(r.body!!).getJSONArray("events")
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun queueFile() = File(File(app.filesDir, "ravensight"), RavensightWire.queueFileName("gt_pub_wave"))

    private fun vectors(): JSONObject =
        JSONObject(javaClass.classLoader!!.getResourceAsStream("delivery-vectors.json").bufferedReader().readText())

    private fun expand(value: Any?): Any? =
        if (value is JSONObject && value.has("repeat")) value.getString("repeat").repeat(value.getInt("count")) else value

    // --- Vectors --------------------------------------------------------------

    @Test
    fun `signing header vectors`() {
        val signing = vectors().getJSONObject("signing")
        val cases = signing.getJSONArray("header")
        assertEquals(4, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getString("name"), c.getString("header"),
                RavensightWire.signatureHeader(signing.getString("secret"), c.getLong("timestamp"), c.getString("body")))
        }
    }

    @Test
    fun `signing beacon vector`() {
        val signing = vectors().getJSONObject("signing")
        val c = signing.getJSONArray("beacon").getJSONObject(0)
        val expected = c.getJSONObject("envelope").getJSONObject("signed")
        assertEquals(expected.getString("body"), c.getString("inner"))
        assertEquals(expected.getString("sig"), RavensightWire.beaconSignature(signing.getString("secret"), c.getLong("t"), c.getString("inner")))
    }

    @Test
    fun `any key event size vectors`() {
        val cases = vectors().getJSONObject("anyKeyEventSize").getJSONArray("cases")
        assertEquals(6, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val raw = c.getJSONObject("data")
            val data = linkedMapOf<String, Any?>()
            for (key in raw.keys()) data[key] = expand(raw.get(key))
            val refused = Ravensight.countedBytes(data) > 8192
            assertEquals(c.getString("name"), !c.isNull("expect"), refused)
        }
    }

    @Test
    fun `queue file name and gzip`() {
        assertEquals("queue-e0347efbebb7d40b.json", RavensightWire.queueFileName("gt_pub_test"))
        val body = "x".repeat(5000).toByteArray()
        val packed = RavensightWire.gzip(body)
        assertEquals(0x1f, packed[0].toInt() and 0xff)
        assertTrue(packed.size < body.size)
        assertTrue(body.contentEquals(RavensightWire.gunzip(packed)))
    }

    // --- Persisted queue and origins ---------------------------------------------

    @Test
    fun `an offline run is sent next launch under its own origin`() {
        http = transport()
        http.batchStatus = 0
        launch()
        Ravensight.track("pickup", mapOf("coins" to 3))
        flush()
        Ravensight.controllerForTest!!.onBackgroundSignal()
        worker.runDue()
        val saved = JSONObject(queueFile().readText())
        assertEquals(2, saved.getInt("version"))
        assertFalse(saved.toString().contains("tok_ses_one"))
        assertTrue(saved.toString().contains("ses_one"))

        Ravensight.resetForTest()
        session = "ses_two"
        http = transport()
        launch()
        Ravensight.track("next_run")
        flush()
        val sent = events()
        val pickup = sent.first { it.getString("event") == "pickup" }
        assertEquals("ses_one", pickup.getJSONObject("origin").getString("session"))
        assertEquals("o1.a.b", pickup.getJSONObject("origin").getString("stamp"))
        assertTrue("milliseconds", pickup.getLong("timestamp") > 1_000_000_000_000L)
        assertFalse(sent.first { it.getString("event") == "next_run" }.has("origin"))
        advance(2_000L)
        assertFalse("delivered, so the file is gone", queueFile().exists())
    }

    @Test
    fun `persistQueue off writes no file and an opt-out deletes it`() {
        http = transport()
        http.batchStatus = 0
        launch(config(persistQueue = false))
        Ravensight.track("a")
        flush()
        advance(2_000L)
        assertFalse(queueFile().exists())
        Ravensight.resetForTest()
        launch()
        Ravensight.track("a")
        flush()
        advance(2_000L)
        assertTrue(queueFile().exists())
        Ravensight.setTrackingEnabled(app, false)
        worker.runDue()
        assertFalse(queueFile().exists())
    }

    // --- Signing, gzip, device fields ----------------------------------------------

    @Test
    fun `batches are signed over the plain body and marked gzip from 1 KB`() {
        val secret = "dGVzdC1zZWNyZXQtZm9yLWRlbGl2ZXJ5LWNvbnRyYWN0"
        signing = JSONObject().put("algorithm", "HMAC-SHA256").put("version", 1).put("secret", secret).put("serverTime", now / 1000 + 1000)
        http = transport()
        launch()
        Ravensight.track("small")
        flush()
        Ravensight.track("big", mapOf("blob" to "x".repeat(1500)))
        flush()
        val sent = batches()
        assertEquals(2, sent.size)
        assertNull(sent[0].headers["Content-Encoding"])
        assertEquals("gzip", sent[1].headers["Content-Encoding"])
        for (request in sent) {
            val ts = request.headers["X-Ravensight-Timestamp"]!!.toLong()
            assertEquals(now / 1000 + 1000, ts)
            assertEquals(RavensightWire.signatureHeader(secret, ts, request.body!!), request.headers["X-Ravensight-Signature"])
        }
        val sessionBody = JSONObject(http.requests.first { it.path == "/session" }.body!!)
        assertEquals("Pixel 7", sessionBody.getJSONObject("device").getString("model"))
        assertEquals("14", sessionBody.getJSONObject("device").getString("osVersion"))
    }

    @Test
    fun `a 415 on a compressed body resends plain`() {
        http = transport()
        val previous = http.override!!
        http.override = { req ->
            if (req.path == "/track/batch") {
                if (req.headers["Content-Encoding"] == "gzip") HttpResult(415, "{}", null) else HttpResult(202, "{}", null)
            } else previous(req)
        }
        launch()
        Ravensight.track("big", mapOf("blob" to "x".repeat(1500)))
        flush()
        assertEquals(2, batches().size)
        assertNull(batches()[1].headers["Content-Encoding"])
    }

    @Test
    fun `no device fields when there are none`() {
        http = transport()
        launch(device = emptyMap())
        Ravensight.track("a")
        flush()
        assertFalse(JSONObject(http.requests.first { it.path == "/session" }.body!!).has("device"))
        val fields = Ravensight.defaultDeviceFields()
        assertTrue(fields.values.all { it.length <= 128 && it.isNotBlank() })
        assertNull(fields["gpu"])
    }

    // --- Response table --------------------------------------------------------------

    @Test
    fun `a 400 naming an index drops that event and a 403 keeps the rest on disk`() {
        http = transport()
        val previous = http.override!!
        var step = 0
        http.override = { req ->
            if (req.path == "/track/batch") {
                step++
                if (step == 1) HttpResult(400, "{\"error\":\"invalid_event\",\"index\":1}", null)
                else HttpResult(403, "{\"error\":\"tracking_disabled\"}", null)
            } else previous(req)
        }
        launch()
        Ravensight.track("a")
        Ravensight.track("bad")
        Ravensight.track("c")
        flush()
        val second = JSONObject(batches()[1].body!!).getJSONArray("events")
        assertEquals(listOf("a", "c"), (0 until second.length()).map { second.getJSONObject(it).getString("event") })
        val saved = queueFile().readText()
        assertTrue(saved.contains("\"a\"") && saved.contains("\"c\"") && !saved.contains("\"bad\""))
    }

    // --- Errors behind gameplay ----------------------------------------------------

    @Test
    fun `the queue file keeps the automatic capture flag`() {
        val store = RavensightQueueStore(File(File(app.filesDir, "ravensight"), "queue-test.json"))
        val events = listOf(
            com.realityse.ravensight.core.RavensightEvent("play", emptyMap(), now),
            com.realityse.ravensight.core.RavensightEvent("error", mapOf("rs_kind" to "error"), now, auto = true),
        )
        assertTrue(store.save(events))
        val loaded = store.load(now, 500).events
        assertEquals(listOf(false, true), loaded.map { it.auto })
        assertTrue(loaded.all { it.restored })
    }

    @Test
    fun `rule 3, rs_trail never reaches the queue file`() {
        val store = RavensightQueueStore(File(File(app.filesDir, "ravensight"), "queue-trail.json"))
        val capture = com.realityse.ravensight.core.RavensightEvent(
            "error", mapOf("rs_kind" to "error", "rs_message" to "boom", "rs_trail" to "secret trail"), now, auto = true,
        )
        assertTrue(store.save(listOf(capture)))
        assertFalse(store.file.readText().contains("rs_trail"))
        assertFalse(store.file.readText().contains("secret trail"))
        val loaded = store.load(now, 500).events.single()
        assertTrue(loaded.auto)
        assertNull(loaded.data["rs_trail"])
        assertEquals("boom", loaded.data["rs_message"])
    }

    @Test
    fun `rule 3, opt-out and a device id reset delete persisted captures`() {
        http = transport()
        http.batchStatus = 0
        launch()
        Ravensight.track("play")
        flush()
        advance(2_000L)
        assertTrue(queueFile().exists())
        Ravensight.resetDeviceId()
        worker.runDue()
        assertFalse("a reset deletes the queue file", queueFile().exists())
        Ravensight.track("again")
        flush()
        advance(2_000L)
        assertTrue(queueFile().exists())
        Ravensight.setTrackingEnabled(app, false)
        worker.runDue()
        assertFalse("an opt-out deletes it", queueFile().exists())
    }

    // --- Exposure -------------------------------------------------------------------

    @Test
    fun `exposure follows the first typed read`() {
        configBody = JSONObject()
            .put("values", JSONObject().put("speed", 2))
            .put("experiments", org.json.JSONArray().put(JSONObject().put("key", "difficulty").put("variant", "easy")))
            .put("etag", "\"" + "a".repeat(64) + "\"").toString()
        http = transport()
        launch()
        Ravensight.track("first")
        flush()
        Ravensight.track("fetched_not_read")
        assertEquals(2, Ravensight.getConfig("speed", 0))
        Ravensight.getConfig("speed", 0)
        Ravensight.track("after_read")
        flush()
        val sent = events()
        fun tag(name: String) = sent.first { it.getString("event") == name }.getJSONObject("data").optString("rs_exp_difficulty", "")
        assertEquals("", tag("first"))
        assertEquals("", tag("fetched_not_read"))
        assertEquals("easy", tag("rs_exposure"))
        assertEquals("easy", tag("after_read"))
        assertEquals(1, sent.count { it.getString("event") == "rs_exposure" })
        // A stopped experiment is not tagged after the next fetch.
        configBody = JSONObject().put("values", JSONObject()).put("experiments", org.json.JSONArray())
            .put("etag", "\"" + "b".repeat(64) + "\"").toString()
        Ravensight.fetchConfig()
        worker.runDue()
        Ravensight.track("after_stop")
        flush()
        assertEquals("", events().first { it.getString("event") == "after_stop" }.getJSONObject("data").optString("rs_exp_difficulty", ""))
    }

    // --- Ads and attribution --------------------------------------------------------

    @Test
    fun `micros are decimal arithmetic`() {
        assertEquals("1500", RavensightAd.microsFromDecimalString("0.0015"))
        assertEquals("1234568", RavensightAd.microsFromDecimalString("1.23456789"))
        assertEquals("1000000", RavensightAd.microsFromDecimalString("0.9999995"))
        assertNull(RavensightAd.microsFromDecimalString("-1"))
        assertNull(RavensightAd.microsFromDecimalString("1e-3"))
        assertNull(RavensightAd.microsFromDecimalString("1."))
        assertEquals("1500", RavensightAd.microsFromDouble(0.0015))
        assertEquals("1234568", RavensightAd.microsFromDouble(1.23456789))
        assertEquals("1", RavensightAd.microsFromDouble(0.000001))
        assertNull(RavensightAd.microsFromDouble(Double.NaN))
    }

    @Test
    fun `formats and mediation adapters`() {
        assertEquals("banner", RavensightAd.mapFormat("MREC"))
        assertEquals("interstitial", RavensightAd.mapFormat("INTER"))
        assertEquals("rewarded", RavensightAd.mapFormat("rewarded_video"))
        assertEquals("rewarded", RavensightAd.mapFormat("REWARDED_INTER"))
        assertEquals("app_open", RavensightAd.mapFormat("APP_OPEN"))
        assertEquals("other", RavensightAd.mapFormat("CAROUSEL"))
        val admob = RavensightAd.fromAdMob(1500, "usd", null, "rewarded", "shop")
        assertEquals("admob", admob.network)
        assertEquals("USD", admob.currency)
        assertEquals("1500", RavensightAd.fromAppLovinMax(0.0015, "AppLovin", "INTER", null).revenueMicros)
        assertEquals("1234568", RavensightAd.fromIronSource(1.23456789, "ironSource", "Rewarded Video", null).revenueMicros)
        assertEquals("CNY", RavensightAd.fromTopOn(0.02, "cny", "Pangle", "BANNER", "home").currency)
    }

    @Test
    fun `ad revenue sends exactly the seven keys and broken revenue is refused`() {
        http = transport()
        launch()
        assertTrue(Ravensight.trackAdRevenue("admob", "rewarded", "level_end", "1500", "USD"))
        assertFalse(Ravensight.trackAd(RavensightAd("admob", "rewarded", "shown", revenueMicros = "1500")))
        assertFalse(Ravensight.trackAd(RavensightAd("admob", "rewarded", "clicked", revenueMicros = "1500", currency = "USD")))
        assertFalse(Ravensight.trackAd(RavensightAd("admob", "rewarded", "shown", revenueMicros = "0.0015", currency = "USD")))
        assertTrue(Ravensight.trackAd(RavensightAd("admob", "rewarded", "rewarded")))
        flush()
        val ads = events().filter { it.getString("event") == "rs_ad" }
        assertEquals(2, ads.size)
        val data = ads[0].getJSONObject("data")
        assertEquals(
            setOf("rs_kind", "rs_ad_network", "rs_ad_format", "rs_ad_placement", "rs_ad_result", "rs_revenue_micros", "rs_currency"),
            data.keys().asSequence().filter { it != "rs_event_id" }.toSet(),
        )
        assertEquals("1500", data.getString("rs_revenue_micros"))
        assertFalse(ads[1].getJSONObject("data").has("rs_revenue_micros"))
    }

    @Test
    fun `attribution is sent once per install across restarts`() {
        http = transport()
        launch()
        val attribution = RavensightAttribution("store_referrer", campaign = "spring", touchTimeMs = 1_767_225_600_000L)
        assertTrue(Ravensight.setAttribution(attribution))
        assertFalse(Ravensight.setAttribution(attribution))
        flush()
        Ravensight.resetForTest()
        launch()
        assertFalse("a restart does not send it again", Ravensight.setAttribution(attribution))
        assertFalse(Ravensight.setAttribution(RavensightAttribution(" ")))
        flush()
        val sent = events().filter { it.getString("event") == "rs_attribution" }
        assertEquals(1, sent.size)
        val data = sent[0].getJSONObject("data")
        assertEquals("store_referrer", data.getString("rs_attr_source"))
        assertEquals("spring", data.getString("rs_campaign"))
        assertEquals(1_767_225_600_000L, data.getLong("rs_touch_time"))
        assertFalse(data.has("rs_adset"))
        Ravensight.resetDeviceId()
        assertTrue("a reset forgets the flag", Ravensight.setAttribution(attribution))
        assertNotNull(sent)
    }

    private fun advance(ms: Long) {
        now += ms
        worker.runDue()
    }
}
