package com.realityse.ravensight

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * setCampaignLink on the Android layer: the rs_link id the game read from an
 * App Link, a deep link or an install referrer goes once, as campaignLink,
 * on the next POST /session the SDK opens for its own player, and is dropped
 * only when that session opens. Fake transport, fake clock, manual worker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CampaignLinkAndroidTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var now = 1_800_000_000_000L
    private val worker = ManualWorker { now }
    private val http = FakeTransport()

    private val linkId = "64b0000000000000000000a1"
    private val otherId = "64b0000000000000000000b2"

    /** Everything the contract says to ignore. */
    private val invalid = listOf(
        null,
        "",
        "abc",
        "a".repeat(23),
        "a".repeat(25),
        "g".repeat(24),
        " $linkId",
        "$linkId ",
    )

    @Before
    fun setUp() {
        Ravensight.resetForTest()
        prefs().edit().clear().commit()
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        Ravensight.resetForTest()
    }

    private fun prefs() = app.getSharedPreferences(Ravensight.PREFS, Context.MODE_PRIVATE)

    private fun config(
        ingestKey: String = "gt_live_test",
        consent: RavensightConsent = RavensightConsent.OPT_OUT,
        playtestToken: String? = null,
        enabled: Boolean = true,
    ) = RavensightConfig(
        ingestKey = ingestKey,
        apiUrl = "https://api.example.test/",
        gameVersion = "1.0.0",
        optional = true,
        consent = consent,
        enabled = enabled,
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
                identifyRunner = { it.run() },
            ),
        )
        worker.runDue()
    }

    private fun foreground() {
        Ravensight.controllerForTest!!.onForegroundSignal()
        worker.runDue()
    }

    private fun settle() = worker.runDue()

    private fun flush() {
        Ravensight.flush()
        settle()
    }

    /** Lets the session expire, then tracks so the next flush opens a fresh one. */
    private fun nextSession() {
        now += 86_400_000L + 60_000L
        Ravensight.track("later")
        flush()
    }

    private fun sessions() = http.requests.filter { it.path == "/session" }

    /** campaignLink of every POST /session, in order ("-" for none). */
    private fun sentLinks() = sessions().map { JSONObject(it.body!!).optString("campaignLink", "-") }

    private fun pending() = Ravensight.controllerForTest!!.campaignLinkPendingForTest

    @Test
    fun `the first session carries it lower case and a later one none`() {
        init()
        assertTrue(Ravensight.setCampaignLink(linkId.uppercase()))
        foreground()
        nextSession()
        assertEquals(listOf(linkId, "-"), sentLinks())
        assertEquals("gt_live_test", sessions().first().headers["X-API-Key"])
        assertNull(pending())
    }

    @Test
    fun `a call after the first session goes with the next one`() {
        init(); foreground()
        assertTrue(Ravensight.setCampaignLink(linkId))
        nextSession()
        nextSession()
        assertEquals(listOf("-", linkId, "-"), sentLinks())
    }

    @Test
    fun `no link set sends no field`() {
        init(); foreground()
        assertEquals(listOf("-"), sentLinks())
    }

    @Test
    fun `invalid ids return false, send nothing and keep an earlier link`() {
        init()
        for (value in invalid) assertFalse(value.toString(), Ravensight.setCampaignLink(value))
        assertNull(pending())
        assertTrue(Ravensight.setCampaignLink(linkId))
        for (value in invalid) Ravensight.setCampaignLink(value)
        assertEquals(linkId, pending())
        foreground()
        assertEquals(listOf(linkId), sentLinks())
    }

    @Test
    fun `a failed session keeps it and the next successful one carries it`() {
        http.sessionStatus = 500
        init()
        Ravensight.setCampaignLink(linkId)
        foreground()
        assertEquals(linkId, pending())
        http.sessionStatus = 201
        now += 20_000L
        settle()
        assertNull(pending())
        nextSession()
        assertEquals(listOf(linkId, linkId, "-"), sentLinks())
    }

    @Test
    fun `an offline session keeps it`() {
        http.override = { r -> if (r.path == "/session") HttpResult(0, "", null) else null }
        init()
        Ravensight.setCampaignLink(linkId)
        foreground()
        assertEquals(linkId, pending())
        http.override = null
        now += 20_000L
        settle()
        assertEquals(listOf(linkId, linkId), sentLinks())
        assertNull(pending())
    }

    @Test
    fun `a link set while the session is out goes with the next session`() {
        init()
        Ravensight.setCampaignLink(linkId)
        http.override = { r ->
            if (r.path == "/session") Ravensight.setCampaignLink(otherId)
            null
        }
        foreground()
        http.override = null
        assertEquals(otherId, pending())
        nextSession()
        assertEquals(listOf(linkId, otherId), sentLinks())
    }

    @Test
    fun `opt-in sends nothing before the yes, then the link goes with the first session after`() {
        init(config(ingestKey = "gt_pub_test", consent = RavensightConsent.OPT_IN))
        foreground()
        assertTrue(Ravensight.setCampaignLink(linkId))
        Ravensight.track("before"); flush()
        assertTrue(http.requests.isEmpty())
        Ravensight.setTrackingEnabled(app, true)
        settle()
        Ravensight.track("after"); flush()
        assertEquals(listOf(linkId), sentLinks())
    }

    @Test
    fun `an opted out player sends nothing`() {
        Ravensight.setTrackingEnabled(app, false)
        init()
        assertTrue(Ravensight.setCampaignLink(linkId))
        foreground()
        Ravensight.track("e"); flush()
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `a stopped SDK sends nothing`() {
        init(config(enabled = false))
        assertTrue(Ravensight.setCampaignLink(linkId))
        foreground()
        Ravensight.track("e"); flush()
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `without a key nothing is sent`() {
        init(config(ingestKey = ""))
        assertTrue(Ravensight.setCampaignLink(linkId))
        foreground()
        Ravensight.track("e"); flush()
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `a playtest run never sends it`() {
        init(config(playtestToken = "pt_token"))
        assertTrue(Ravensight.setCampaignLink(linkId))
        foreground()
        Ravensight.track("e"); flush()
        assertEquals(listOf("-"), sentLinks())
        assertEquals("pt_token", sessions().first().headers["X-Ravensight-Playtest"])
        assertEquals(linkId, pending())
    }

    @Test
    fun `nothing is saved`() {
        http.sessionStatus = 500
        init()
        Ravensight.setCampaignLink(linkId)
        foreground()
        Ravensight.track("e")
        Ravensight.controllerForTest!!.onBackgroundSignal()
        settle()
        assertEquals(linkId, pending())
        val saved = prefs().all.values.joinToString(" ") { it.toString() }
        assertFalse(saved.lowercase().contains(linkId))
        app.filesDir.walkTopDown().filter { it.isFile }.forEach { file ->
            assertFalse(file.name, file.readText().contains(linkId))
        }
    }

    @Test
    fun `the facade holds a link set before initialize`() {
        assertFalse(Ravensight.setCampaignLink("abc"))
        assertTrue(Ravensight.setCampaignLink(linkId.uppercase()))
        init(); foreground()
        assertTrue(Ravensight.setCampaignLink(otherId))
        assertFalse(Ravensight.setCampaignLink("g".repeat(24)))
        nextSession()
        assertEquals(listOf(linkId, otherId), sentLinks())
    }
}
