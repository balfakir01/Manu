package com.manu.dataguard.data

class Tracker(val domain: String, val company: String, val category: String) {
    fun describe() = "$company · $category"
}

/**
 * Built-in list of well-known tracking, advertising, data-broker and device-fingerprinting
 * domains. Matching covers subdomains too ("ads.x.doubleclick.net" matches "doubleclick.net").
 *
 * Deliberately left out: domains an app needs to work at all (e.g. graph.facebook.com is both
 * Facebook's SDK and the Facebook app itself). Those can still be blocked by hand.
 */
object Trackers {
    private const val ADS = "Advertising"
    private const val ANALYTICS = "Analytics / behaviour tracking"
    private const val ATTRIBUTION = "Install & ad attribution"
    private const val FINGERPRINT = "Device fingerprinting"
    private const val LOCATION = "Location data"
    private const val RECORDING = "Session recording"
    private const val MARKETING = "Marketing profile"
    private const val CRASH = "Crash / performance reporting"

    private val list = listOf(
        // Google
        Tracker("doubleclick.net", "Google", ADS),
        Tracker("googleadservices.com", "Google", ADS),
        Tracker("googlesyndication.com", "Google", ADS),
        Tracker("adservice.google.com", "Google", ADS),
        Tracker("google-analytics.com", "Google", ANALYTICS),
        Tracker("app-measurement.com", "Google Firebase", ANALYTICS),
        Tracker("firebaselogging-pa.googleapis.com", "Google Firebase", ANALYTICS),
        Tracker("crashlytics.com", "Google Firebase", CRASH),
        // Meta
        Tracker("an.facebook.com", "Meta", ADS),
        Tracker("connect.facebook.net", "Meta", ANALYTICS),
        // Amazon / Microsoft / others
        Tracker("amazon-adsystem.com", "Amazon", ADS),
        Tracker("clarity.ms", "Microsoft", RECORDING),
        Tracker("ads.linkedin.com", "LinkedIn", ADS),
        Tracker("ads-api.tiktok.com", "TikTok", ADS),
        Tracker("analytics.tiktok.com", "TikTok", ANALYTICS),
        Tracker("pangle.io", "ByteDance (Pangle)", ADS),
        Tracker("byteoversea.com", "ByteDance", ANALYTICS),
        Tracker("appmetrica.yandex.net", "Yandex", ANALYTICS),
        Tracker("umeng.com", "Alibaba (Umeng)", ANALYTICS),
        // Attribution
        Tracker("appsflyer.com", "AppsFlyer", ATTRIBUTION),
        Tracker("appsflyersdk.com", "AppsFlyer", ATTRIBUTION),
        Tracker("adjust.com", "Adjust", ATTRIBUTION),
        Tracker("adjust.io", "Adjust", ATTRIBUTION),
        Tracker("branch.io", "Branch", ATTRIBUTION),
        Tracker("kochava.com", "Kochava", ATTRIBUTION),
        Tracker("singular.net", "Singular", ATTRIBUTION),
        Tracker("tenjin.com", "Tenjin", ATTRIBUTION),
        // Analytics
        Tracker("mixpanel.com", "Mixpanel", ANALYTICS),
        Tracker("amplitude.com", "Amplitude", ANALYTICS),
        Tracker("segment.io", "Segment (Twilio)", ANALYTICS),
        Tracker("segment.com", "Segment (Twilio)", ANALYTICS),
        Tracker("flurry.com", "Flurry", ANALYTICS),
        Tracker("scorecardresearch.com", "comScore", ANALYTICS),
        Tracker("quantserve.com", "Quantcast", ANALYTICS),
        Tracker("heap.io", "Heap", ANALYTICS),
        Tracker("heapanalytics.com", "Heap", ANALYTICS),
        Tracker("apptimize.com", "Apptimize", ANALYTICS),
        Tracker("optimizely.com", "Optimizely", ANALYTICS),
        // Session recording
        Tracker("uxcam.com", "UXCam", RECORDING),
        Tracker("fullstory.com", "FullStory", RECORDING),
        Tracker("hotjar.com", "Hotjar", RECORDING),
        Tracker("contentsquare.net", "Contentsquare", RECORDING),
        Tracker("smartlook.com", "Smartlook", RECORDING),
        Tracker("quantummetric.com", "Quantum Metric", RECORDING),
        Tracker("glassbox.com", "Glassbox", RECORDING),
        // Marketing / engagement profiles
        Tracker("braze.com", "Braze", MARKETING),
        Tracker("appboy.com", "Braze", MARKETING),
        Tracker("onesignal.com", "OneSignal", MARKETING),
        Tracker("clevertap-prod.com", "CleverTap", MARKETING),
        Tracker("clevertap.com", "CleverTap", MARKETING),
        Tracker("leanplum.com", "Leanplum", MARKETING),
        Tracker("urbanairship.com", "Airship", MARKETING),
        Tracker("localytics.com", "Localytics", MARKETING),
        Tracker("swrve.com", "Swrve", MARKETING),
        Tracker("moengage.com", "MoEngage", MARKETING),
        Tracker("iterable.com", "Iterable", MARKETING),
        Tracker("salesforce-marketingcloud.com", "Salesforce", MARKETING),
        Tracker("exacttarget.com", "Salesforce", MARKETING),
        Tracker("demdex.net", "Adobe", MARKETING),
        Tracker("omtrdc.net", "Adobe", ANALYTICS),
        Tracker("everesttech.net", "Adobe", ADS),
        // Device fingerprinting / "fraud" SDKs
        Tracker("online-metrix.net", "LexisNexis ThreatMetrix", FINGERPRINT),
        Tracker("iovation.com", "TransUnion iovation", FINGERPRINT),
        Tracker("sift.com", "Sift", FINGERPRINT),
        Tracker("siftscience.com", "Sift", FINGERPRINT),
        Tracker("forter.com", "Forter", FINGERPRINT),
        Tracker("fingerprint.com", "Fingerprint", FINGERPRINT),
        Tracker("fpjs.io", "Fingerprint", FINGERPRINT),
        Tracker("perimeterx.net", "HUMAN", FINGERPRINT),
        Tracker("px-cloud.net", "HUMAN", FINGERPRINT),
        // Location data
        Tracker("foursquare.com", "Foursquare", LOCATION),
        Tracker("cuebiq.com", "Cuebiq", LOCATION),
        Tracker("safegraph.com", "SafeGraph", LOCATION),
        Tracker("placed.com", "Placed (Foursquare)", LOCATION),
        Tracker("gimbal.com", "Gimbal", LOCATION),
        Tracker("radar.io", "Radar", LOCATION),
        Tracker("inmarket.com", "InMarket", LOCATION),
        Tracker("groundtruth.com", "GroundTruth", LOCATION),
        // Ad networks / exchanges
        Tracker("applovin.com", "AppLovin", ADS),
        Tracker("applvn.com", "AppLovin", ADS),
        Tracker("unityads.unity3d.com", "Unity", ADS),
        Tracker("ironsrc.com", "Unity ironSource", ADS),
        Tracker("ironsrc.mobi", "Unity ironSource", ADS),
        Tracker("supersonicads.com", "Unity ironSource", ADS),
        Tracker("inmobi.com", "InMobi", ADS),
        Tracker("chartboost.com", "Chartboost", ADS),
        Tracker("vungle.com", "Liftoff Vungle", ADS),
        Tracker("liftoff.io", "Liftoff", ADS),
        Tracker("adcolony.com", "AdColony", ADS),
        Tracker("mopub.com", "MoPub", ADS),
        Tracker("mintegral.com", "Mintegral", ADS),
        Tracker("rayjump.com", "Mintegral", ADS),
        Tracker("smaato.net", "Smaato", ADS),
        Tracker("tapjoy.com", "Tapjoy", ADS),
        Tracker("fyber.com", "Fyber", ADS),
        Tracker("criteo.com", "Criteo", ADS),
        Tracker("criteo.net", "Criteo", ADS),
        Tracker("taboola.com", "Taboola", ADS),
        Tracker("outbrain.com", "Outbrain", ADS),
        Tracker("moatads.com", "Oracle Moat", ADS),
        Tracker("adsrvr.org", "The Trade Desk", ADS),
        Tracker("rubiconproject.com", "Magnite", ADS),
        Tracker("pubmatic.com", "PubMatic", ADS),
        Tracker("openx.net", "OpenX", ADS),
        Tracker("casalemedia.com", "Index Exchange", ADS),
        Tracker("adnxs.com", "Xandr (Microsoft)", ADS),
        Tracker("bidswitch.net", "BidSwitch", ADS),
        Tracker("smartadserver.com", "Equativ", ADS),
        Tracker("33across.com", "33Across", ADS),
        Tracker("teads.tv", "Teads", ADS),
        Tracker("yieldmo.com", "Yieldmo", ADS),
        Tracker("adform.net", "Adform", ADS),
        Tracker("lijit.com", "Sovrn", ADS),
        Tracker("sharethrough.com", "Sharethrough", ADS),
        Tracker("rlcdn.com", "LiveRamp", MARKETING),
        Tracker("liveramp.com", "LiveRamp", MARKETING),
        Tracker("bluekai.com", "Oracle BlueKai", MARKETING),
        Tracker("krxd.net", "Salesforce Krux", MARKETING),
        Tracker("tapad.com", "Tapad (Experian)", MARKETING),
        Tracker("agkn.com", "Neustar", MARKETING),
        // Crash / performance (lower risk, still third parties)
        Tracker("bugsnag.com", "Bugsnag", CRASH),
        Tracker("sentry.io", "Sentry", CRASH),
        Tracker("instabug.com", "Instabug", CRASH),
        Tracker("nr-data.net", "New Relic", CRASH),
        Tracker("newrelic.com", "New Relic", CRASH),
        Tracker("appdynamics.com", "AppDynamics", CRASH),
        Tracker("dynatrace.com", "Dynatrace", CRASH),
        Tracker("browser-intake-datadoghq.com", "Datadog", CRASH),
    )

    private val byDomain = list.associateBy { it.domain }

    /** Returns the tracker entry for [host] or any of its parent domains, if listed. */
    fun lookup(host: String): Tracker? {
        for (suffix in suffixes(host)) byDomain[suffix]?.let { return it }
        return null
    }

    /** "a.b.example.com" -> ["a.b.example.com", "b.example.com", "example.com", "com"] */
    fun suffixes(host: String): List<String> {
        val out = mutableListOf(host)
        var i = host.indexOf('.')
        while (i >= 0 && i + 1 < host.length) {
            out += host.substring(i + 1)
            i = host.indexOf('.', i + 1)
        }
        return out
    }
}
