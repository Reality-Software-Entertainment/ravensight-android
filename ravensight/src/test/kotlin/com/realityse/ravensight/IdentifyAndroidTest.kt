package com.realityse.ravensight

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * identify and resetIdentity on the Android layer, against the binding
 * vectors' sequence (identify-vectors.json, version 2), with the fake
 * transport, a fake clock and the manual worker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdentifyAndroidTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var now = 1_800_000_000_000L
    private val worker = ManualWorker { now }
    private val http = FakeTransport()
    /** Identify requests run here; null runs them at once. */
    private var heldIdentify: MutableList<Runnable>? = null

    private val vectors = JSONObject(javaClass.classLoader!!.getResource("identify-vectors.json")!!.readText())
    private val salt = vectors.getString("salt")
    private val sequence = vectors.getJSONArray("sequence")

    @Before
    fun setUp() {
        Ravensight.resetForTest()
        prefs().edit().clear().commit()
        ShadowLog.clear()
        assertEquals(2, vectors.getInt("version"))
    }

    @After
    fun tearDown() {
        Ravensight.resetForTest()
    }

    private fun prefs() = app.getSharedPreferences(Ravensight.PREFS, Context.MODE_PRIVATE)

    private fun config(ingestKey: String = "gt_live_test", consent: RavensightConsent = RavensightConsent.OPT_OUT, playtestToken: String? = null) =
        RavensightConfig(
            ingestKey = ingestKey,
            apiUrl = "https://api.example.test/",
            gameVersion = "1.0.0",
            optional = true,
            consent = consent,
            playtestToken = playtestToken,
            playtestRunId = playtestToken?.let { "run_7" },
        )

    private fun init(cfg: RavensightConfig = config()) {
        Ravensight.initialize(
            app, cfg, null,
            RavensightDeps(
                transport = http,
                clockMs = { now },
                worker = worker,
                mainPost = { it.run() },
                env = { null },
                observeProcessLifecycle = false,
                registerActivityCallbacks = false,
                random = { 0.5 },
                installCrashHandler = false,
                identifyRunner = { r -> heldIdentify?.add(r) ?: r.run() },
            ),
        )
        worker.runDue()
    }

    private fun foreground() {
        Ravensight.controllerForTest!!.onForegroundSignal()
        worker.runDue()
    }

    private fun settle() = worker.runDue()

    private fun relaunch(cfg: RavensightConfig = config()) {
        Ravensight.resetForTest()
        init(cfg)
        foreground()
    }

    private fun flush() {
        Ravensight.flush()
        settle()
    }

    private fun identifies() = http.requests.filter { it.path == "/identify" }
    private fun sessions() = http.requests.filter { it.path == "/session" }
    private fun hashes() = identifies().map { JSONObject(it.body!!).getString("accountHash") }
    private fun tokens() = identifies().map { it.headers["X-Session-Token"] }

    private fun stored(): JSONObject? = prefs().getString(Ravensight.KEY_IDENTITY, null)?.let { JSONObject(it) }

    private fun expect(step: Int): JSONObject = sequence.getJSONObject(step).getJSONObject("expect")
    private fun sentHash(step: Int) = expect(step).getJSONArray("sends").getJSONObject(0).getString("accountHash")
    private fun storedHash(step: Int) = expect(step).getJSONObject("stored").getString("accountHash")
    private fun held() = Ravensight.controllerForTest!!.identityHeldForTest

    // --- Conformance 2: refusals -----------------------------------------------

    @Test
    fun `every refuse vector is refused with nothing sent or stored and the device kept`() {
        http.identifySalt = salt
        init(); foreground()
        val device = Ravensight.deviceId
        assertNotNull(device)
        val refuse = vectors.getJSONArray("refuse")
        var checked = 0
        for (i in 0 until refuse.length()) {
            val case = refuse.getJSONObject(i)
            val raw = case.get("accountId")
            // A fraction cannot be passed as a String; JSON null is Java's null.
            val id: String? = when (raw) {
                JSONObject.NULL -> null
                is String -> raw
                else -> continue
            }
            assertFalse(case.getString("note"), Ravensight.identify(id))
            checked++
        }
        settle()
        assertEquals(refuse.length() - 1, checked)
        assertTrue(identifies().isEmpty())
        assertNull(stored())
        assertFalse(Ravensight.isIdentified)
        assertEquals(device, Ravensight.deviceId)
        assertTrue(ShadowLog.getLogsForTag(Ravensight.TAG).any { it.msg.contains("identify refused (looks_like_email)") })
    }

    // --- Conformance 3: the sequence ---------------------------------------------

    @Test
    fun `the sequence holds`() {
        assertEquals(12, sequence.length())
        http.identifySalt = salt

        // 0. Before any session: held in memory only, nothing sent or stored.
        init()
        assertTrue(Ravensight.identify("u1"))
        settle()
        assertTrue(held())
        assertNull(stored())
        assertTrue(http.requests.isEmpty())

        // 1. S1 opens with the salt: hashed, stored, sent once.
        foreground()
        assertEquals(listOf("tok_1"), tokens())
        assertEquals(listOf(sentHash(1)), hashes())
        assertEquals(storedHash(1), stored()!!.getString("accountHash"))
        assertEquals(salt, stored()!!.getString("salt"))
        assertFalse(held())

        // 2. The same account again: nothing.
        val device1 = Ravensight.deviceId
        assertTrue(Ravensight.identify("u1"))
        Ravensight.track("a"); flush()
        assertEquals(1, identifies().size)
        assertEquals(device1, Ravensight.deviceId)

        // 3. Relaunch: S2 identifies with no call.
        relaunch()
        assertEquals(listOf("tok_1", "tok_2"), tokens())
        assertEquals(sentHash(3), hashes().last())

        // 4. An email: refused, nothing changes.
        val device2 = Ravensight.deviceId
        assertFalse(Ravensight.identify("player@example.com"))
        settle()
        assertEquals(2, identifies().size)
        assertEquals(storedHash(4), stored()!!.getString("accountHash"))
        assertEquals(device2, Ravensight.deviceId)

        // 5. Another account: fresh device and session, then sent there.
        assertTrue(Ravensight.identify("u2"))
        settle()
        assertNotEquals(device2, Ravensight.deviceId)
        assertEquals(listOf("tok_1", "tok_2", "tok_3"), tokens())
        assertEquals(sentHash(5), hashes().last())
        assertEquals(storedHash(5), stored()!!.getString("accountHash"))

        // 6. resetIdentity: fresh device, nothing stored or sent.
        val device3 = Ravensight.deviceId
        Ravensight.resetIdentity()
        settle()
        assertNotEquals(device3, Ravensight.deviceId)
        assertNull(stored())
        assertFalse(Ravensight.isIdentified)
        Ravensight.track("b"); flush()
        assertEquals(4, sessions().size)
        assertEquals(3, identifies().size)

        // 7. resetIdentity with nothing stored: nothing at all.
        val device4 = Ravensight.deviceId
        Ravensight.resetIdentity()
        settle()
        assertEquals(device4, Ravensight.deviceId)
        assertEquals(3, identifies().size)

        // 8. needs_funds: kept, sent once, not again this session.
        http.identifyStatus = 429
        http.identifyError = "quota_exceeded"
        assertTrue(Ravensight.identify("u3"))
        settle()
        assertEquals("tok_4", tokens().last())
        assertEquals(sentHash(8), hashes().last())
        assertEquals(storedHash(8), stored()!!.getString("accountHash"))
        repeat(3) { Ravensight.track("c"); flush(); now += 60_000; settle() }
        assertEquals(4, identifies().size)
        // Retried at the next session.
        http.identifyStatus = 200
        relaunch()
        assertEquals(5, identifies().size)
        assertEquals(sentHash(8), hashes().last())

        // 9. Another game's salt: deleted, never sent.
        http.identifySalt = vectors.getJSONObject("salts_differ").getString("salt_b")
        relaunch()
        Ravensight.track("d"); flush()
        assertEquals(5, identifies().size)
        assertNull(stored())
        assertFalse(Ravensight.isIdentified)

        // 10. No salt in the answer: unavailable this launch, the held id dropped.
        http.identifySalt = null
        Ravensight.resetForTest()
        init()
        assertTrue(Ravensight.identify("u4"))
        foreground()
        Ravensight.track("e"); flush()
        assertEquals(5, identifies().size)
        assertNull(stored())
        assertFalse(held())
        assertFalse(Ravensight.isIdentified)

        // 11. Opt-out deletes the stored identity.
        http.identifySalt = salt
        relaunch()
        assertTrue(Ravensight.identify("u5"))
        settle()
        assertNotNull(stored())
        assertEquals(6, identifies().size)
        Ravensight.setTrackingEnabled(app, false)
        settle()
        assertNull(stored())
        assertFalse(Ravensight.isIdentified)
        assertEquals(6, identifies().size)
    }

    @Test
    fun `resetDeviceId keeps the identity and the next session identifies again`() {
        http.identifySalt = salt
        init(); foreground()
        Ravensight.identify("u1")
        settle()
        val kept = stored()!!.toString()
        Ravensight.resetDeviceId()
        Ravensight.track("a"); flush()
        assertEquals(kept, stored()!!.toString())
        assertEquals(listOf("tok_1", "tok_2"), tokens())
    }

    @Test
    fun `404 and invalid_account_hash stop identify for the launch`() {
        for ((status, error) in listOf(404 to "not_found", 400 to "invalid_account_hash")) {
            Ravensight.resetForTest()
            prefs().edit().clear().commit()
            http.requests.clear()
            http.identifySalt = salt
            http.identifyStatus = status
            http.identifyError = error
            init(); foreground()
            Ravensight.identify("u1")
            settle()
            Ravensight.resetDeviceId()
            Ravensight.track("a"); flush()
            assertEquals(2, sessions().size)
            assertEquals("$status $error", 1, identifies().size)
            assertNotNull(stored())
        }
    }

    @Test
    fun `every other answer waits for the next session, never a loop`() {
        val cases = listOf(400 to "no_session", 409 to "session_taken", 409 to "synthetic_session",
            429 to "identify_limited", 429 to "rate_limited", 503 to "unavailable", 0 to "offline")
        for ((status, error) in cases) {
            Ravensight.resetForTest()
            prefs().edit().clear().commit()
            http.requests.clear()
            http.identifySalt = salt
            http.identifyStatus = status
            http.identifyError = error
            init(); foreground()
            Ravensight.identify("u1")
            Ravensight.track("a"); flush()
            Ravensight.track("b"); flush()
            assertEquals("no loop on $error", 1, identifies().size)
            Ravensight.resetDeviceId()
            Ravensight.track("c"); flush()
            assertEquals("next session on $error", 2, identifies().size)
            assertNotNull(error, stored())
        }
    }

    @Test
    fun `a late 200 after resetIdentity does not mark the new session identified`() {
        http.identifySalt = salt
        heldIdentify = mutableListOf()
        init(); foreground()
        Ravensight.identify("u1")
        settle()
        // The request has not run yet: the answer will arrive after the reset.
        assertEquals(1, heldIdentify!!.size)
        Ravensight.resetIdentity()
        Ravensight.track("a"); flush()
        assertEquals(2, sessions().size)
        val late = heldIdentify!!.removeAt(0)
        heldIdentify = null
        late.run()
        settle()
        assertFalse(Ravensight.isIdentified)
        assertNull(stored())
        Ravensight.identify("u2")
        settle()
        assertEquals(listOf("tok_1", "tok_2"), tokens())
    }

    @Test
    fun `identify runs off the event worker so it never delays a batch`() {
        http.identifySalt = salt
        heldIdentify = mutableListOf()
        init(); foreground()
        Ravensight.identify("u1")
        Ravensight.track("a"); flush()
        // The identify request is still waiting; the batch went anyway.
        assertEquals(1, heldIdentify!!.size)
        assertTrue(http.requests.any { it.path == "/track/batch" })
        assertTrue(identifies().isEmpty())
    }

    // --- Conformance 4: the request ----------------------------------------------

    @Test
    fun `the request carries the key, the session token and only the hash`() {
        http.identifySalt = salt
        init(); foreground()
        Ravensight.identify("12345")
        settle()
        val request = identifies().single()
        assertEquals("POST", request.method)
        assertEquals("https://api.example.test/api/v1/identify", request.url)
        assertEquals("gt_live_test", request.headers["X-API-Key"])
        assertEquals("tok_1", request.headers["X-Session-Token"])
        val body = JSONObject(request.body!!)
        assertEquals(listOf("accountHash"), body.keys().asSequence().toList())
        assertEquals(vectors.getJSONArray("accept").getJSONObject(0).getString("wire"), body.getString("accountHash"))
        assertFalse(request.body!!.contains(Ravensight.deviceId!!))
        assertFalse(prefs().all.values.any { it.toString().contains("\"12345\"") || it == "12345" })
    }

    @Test
    fun `identify never enters the event queue`() {
        http.identifySalt = salt
        init(); foreground()
        Ravensight.identify("u1")
        settle()
        assertEquals(0, Ravensight.controllerForTest!!.queueSizeForTest())
        val names = http.requests.filter { it.path == "/track/batch" }
            .flatMap { r -> JSONObject(r.body!!).getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("event") } } }
        assertFalse(names.any { it.contains("identify") })
    }

    // --- Conformance 5: nothing without a key or before consent ---------------------

    @Test
    fun `inert without a key, the id is held in memory only`() {
        http.identifySalt = salt
        init(config(ingestKey = ""))
        assertTrue(Ravensight.identify("u1"))
        assertFalse(Ravensight.identify("a@b"))
        assertTrue(http.requests.isEmpty())
        assertNull(stored())
        assertTrue(Ravensight.isIdentified)
        Ravensight.resetIdentity()
        assertFalse(Ravensight.isIdentified)
        assertTrue(http.requests.isEmpty())
        assertNull(prefs().getString(Ravensight.KEY_DEVICE_ID, null))
    }

    @Test
    fun `opt-in holds the id in memory until the player's yes`() {
        http.identifySalt = salt
        init(config(ingestKey = "gt_pub_test", consent = RavensightConsent.OPT_IN))
        foreground()
        assertTrue(Ravensight.identify("u1"))
        Ravensight.track("a")
        settle()
        assertTrue(http.requests.isEmpty())
        assertNull(stored())
        assertTrue(held())
        Ravensight.setTrackingEnabled(app, true)
        settle()
        assertEquals(listOf(sentHash(1)), hashes())
        assertEquals(sentHash(1), stored()!!.getString("accountHash"))
        assertFalse(held())
    }

    @Test
    fun `withdrawing consent drops a held id`() {
        http.identifySalt = salt
        init(config(ingestKey = "gt_pub_test", consent = RavensightConsent.OPT_IN))
        foreground()
        Ravensight.identify("u1")
        Ravensight.setTrackingEnabled(app, false)
        Ravensight.setTrackingEnabled(app, true)
        settle()
        assertTrue(identifies().isEmpty())
        assertNull(stored())
    }

    @Test
    fun `a playtest run never stores or sends an identity`() {
        http.identifySalt = salt
        init(config(playtestToken = "pt_token"))
        foreground()
        assertTrue(Ravensight.identify("u1"))
        Ravensight.track("a"); flush()
        assertTrue(identifies().isEmpty())
        assertNull(stored())
    }

    @Test
    fun `before initialize identify refuses and resetIdentity is a no-op`() {
        assertFalse(Ravensight.identify("u1"))
        assertFalse(Ravensight.isIdentified)
        Ravensight.resetIdentity()
        assertTrue(http.requests.isEmpty())
    }
}
