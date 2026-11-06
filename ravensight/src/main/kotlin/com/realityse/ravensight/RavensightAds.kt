package com.realityse.ravensight

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One ad lifecycle step (docs/sdk/ADS_ATTRIBUTION_CONTRACT.md). Build one by
 * hand or with a mediation adapter in [Companion], then send it with
 * [Ravensight.trackAd].
 */
data class RavensightAd(
    /** The network that served the ad: admob, applovin, ironsource, ... */
    val network: String,
    /** One of [FORMATS]. */
    val format: String,
    /** One of [RESULTS]. */
    val result: String,
    /** The game's own placement name. Null or blank is not sent. */
    val placement: String? = null,
    /** Revenue of this impression in micros of [currency], as a digit string ("1500" is 0.0015). Only on shown. */
    val revenueMicros: String? = null,
    /** ISO 4217, three letters. Required with [revenueMicros]. */
    val currency: String? = null,
) {
    companion object {
        val FORMATS: List<String> = listOf("banner", "interstitial", "rewarded", "native", "app_open", "other")
        val RESULTS: List<String> = listOf("shown", "clicked", "failed", "rewarded")

        private val DIGITS = Regex("^[0-9]+(\\.[0-9]+)?$")

        /**
         * "0.0015" to "1500": decimal text in major units to whole micros,
         * rounding half up past the sixth decimal, on the digits (BigDecimal),
         * never a float. Null when the text is not a plain non-negative
         * decimal or the result has more than 18 digits.
         */
        @JvmStatic
        fun microsFromDecimalString(text: String): String? {
            val trimmed = text.trim()
            if (!DIGITS.matches(trimmed)) return null
            val micros = BigDecimal(trimmed).movePointRight(6).setScale(0, RoundingMode.HALF_UP).toPlainString()
            return micros.takeIf { it.length <= 18 }
        }

        /**
         * A mediation callback's revenue double in major units to micros: the
         * double's own shortest decimal text (BigDecimal.valueOf), then the
         * digits. Never multiplies the double.
         */
        @JvmStatic
        fun microsFromDouble(value: Double): String? {
            if (!value.isFinite() || value < 0 || value > 1e12) return null
            return microsFromDecimalString(BigDecimal.valueOf(value).toPlainString())
        }

        /** A mediation SDK's format label to one of [FORMATS]; unknown is "other". */
        @JvmStatic
        fun mapFormat(label: String): String = when (label.trim().uppercase().replace(' ', '_').replace('-', '_')) {
            "BANNER", "MREC", "LEADER", "LEADERBOARD" -> "banner"
            "INTER", "INTERSTITIAL" -> "interstitial"
            "REWARDED", "REWARDED_VIDEO", "REWARDEDVIDEO", "REWARDED_INTER", "REWARDED_INTERSTITIAL" -> "rewarded"
            "NATIVE" -> "native"
            "APP_OPEN", "APPOPEN", "SPLASH" -> "app_open"
            else -> "other"
        }

        // Mediation adapters. Each takes the values a mediation SDK's
        // impression-level revenue callback reports, so this library never
        // links that SDK, and returns a shown ad with revenue.

        /** AdMob OnPaidEventListener (AdValue): valueMicros is already micros. */
        @JvmStatic
        fun fromAdMob(valueMicros: Long, currencyCode: String, adSourceName: String?, format: String, placement: String?): RavensightAd =
            RavensightAd(
                network = adSourceName?.trim()?.takeIf { it.isNotEmpty() } ?: "admob",
                format = mapFormat(format), result = "shown", placement = placement,
                revenueMicros = valueMicros.takeIf { it >= 0 }?.toString(), currency = currencyCode.trim().uppercase(),
            )

        /** AppLovin MAX onAdRevenuePaid (MaxAd): getRevenue() in USD major units. */
        @JvmStatic
        fun fromAppLovinMax(revenue: Double, networkName: String, format: String, placement: String?): RavensightAd =
            RavensightAd(networkName, mapFormat(format), "shown", placement, microsFromDouble(revenue), "USD")

        /** ironSource LevelPlay onImpressionDataSuccess (ImpressionData): getRevenue() in USD major units. */
        @JvmStatic
        fun fromIronSource(revenue: Double, adNetwork: String, adUnit: String, placement: String?): RavensightAd =
            RavensightAd(adNetwork, mapFormat(adUnit), "shown", placement, microsFromDouble(revenue), "USD")

        /** TopOn onAdRevenue or a show callback's ATAdInfo: publisher revenue in major units of [currency]. */
        @JvmStatic
        fun fromTopOn(publisherRevenue: Double, currency: String, networkName: String, adType: String, placement: String?): RavensightAd =
            RavensightAd(networkName, mapFormat(adType), "shown", placement, microsFromDouble(publisherRevenue), currency.trim().uppercase())
    }

    /**
     * The event data, or null when the ad breaks the contract (refused
     * locally): no network, an unknown format or result, a revenue that is
     * not a digit string, revenue without a three letter currency, or
     * revenue on a result other than shown.
     */
    internal fun eventData(): Map<String, Any?>? {
        val network = network.trim()
        val placement = placement?.trim().orEmpty()
        val currency = currency?.trim()?.uppercase().orEmpty()
        val revenue = revenueMicros.orEmpty()
        if (network.isEmpty() || network.length > 64 || format !in FORMATS || result !in RESULTS || placement.length > 100) return null
        val data = linkedMapOf<String, Any?>(
            "rs_kind" to "ad", "rs_ad_network" to network, "rs_ad_format" to format, "rs_ad_result" to result,
        )
        if (placement.isNotEmpty()) data["rs_ad_placement"] = placement
        if (revenue.isNotEmpty()) {
            val digits = revenue.length <= 18 && revenue.all { it in '0'..'9' }
            val iso = currency.length == 3 && currency.all { it in 'A'..'Z' }
            if (!digits || !iso || result != "shown") return null
            data["rs_revenue_micros"] = revenue
            data["rs_currency"] = currency
        }
        return data
    }
}

/** Where this install was acquired. Sent once per install by [Ravensight.setAttribution]. */
data class RavensightAttribution(
    /** utm, store_referrer, organic, or a partner name. Required. */
    val source: String,
    val network: String? = null,
    val campaign: String? = null,
    val adset: String? = null,
    val creative: String? = null,
    /** When the player clicked or opened the link, epoch milliseconds. */
    val touchTimeMs: Long? = null,
) {
    internal fun eventData(): Map<String, Any?>? {
        val source = source.trim()
        if (source.isEmpty() || source.length > 64) return null
        val data = linkedMapOf<String, Any?>("rs_kind" to "attribution", "rs_attr_source" to source)
        for ((key, value, max) in listOf(
            Triple("rs_attr_network", network, 100), Triple("rs_campaign", campaign, 200),
            Triple("rs_adset", adset, 200), Triple("rs_creative", creative, 200),
        )) {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) continue
            if (trimmed.length > max) return null
            data[key] = trimmed
        }
        touchTimeMs?.takeIf { it > 0 }?.let { data["rs_touch_time"] = it }
        return data
    }
}
