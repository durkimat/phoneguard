package io.github.durkimat.phoneguard.launcher

/** The short lists the first-run wizard and Settings offer for time zone and language, so nobody is sent to Android's own settings. */
object Regional {
    /** (Java time zone id, what the list shows). Ordered by where the phones are likely to be, then west to east. */
    val zones: List<Opt> = listOf(
        Opt("Europe/London", "London (UK)"), Opt("Europe/Dublin", "Dublin"), Opt("Atlantic/Reykjavik", "Reykjavik"),
        Opt("Europe/Lisbon", "Lisbon"), Opt("Europe/Madrid", "Madrid"), Opt("Europe/Paris", "Paris"), Opt("Europe/Brussels", "Brussels"),
        Opt("Europe/Amsterdam", "Amsterdam"), Opt("Europe/Berlin", "Berlin"), Opt("Europe/Zurich", "Zurich"), Opt("Europe/Rome", "Rome"),
        Opt("Europe/Vienna", "Vienna"), Opt("Europe/Stockholm", "Stockholm"), Opt("Europe/Warsaw", "Warsaw"), Opt("Europe/Athens", "Athens"),
        Opt("Europe/Helsinki", "Helsinki"), Opt("Europe/Istanbul", "Istanbul"), Opt("Europe/Moscow", "Moscow"),
        Opt("Africa/Johannesburg", "Johannesburg"), Opt("Africa/Cairo", "Cairo"), Opt("Africa/Lagos", "Lagos"), Opt("Africa/Nairobi", "Nairobi"),
        Opt("Asia/Dubai", "Dubai"), Opt("Asia/Karachi", "Karachi"), Opt("Asia/Kolkata", "India"), Opt("Asia/Dhaka", "Dhaka"),
        Opt("Asia/Bangkok", "Bangkok"), Opt("Asia/Singapore", "Singapore"), Opt("Asia/Hong_Kong", "Hong Kong"), Opt("Asia/Shanghai", "China"),
        Opt("Asia/Manila", "Manila"), Opt("Asia/Tokyo", "Tokyo"), Opt("Asia/Seoul", "Seoul"),
        Opt("Australia/Perth", "Perth"), Opt("Australia/Adelaide", "Adelaide"), Opt("Australia/Sydney", "Sydney"), Opt("Pacific/Auckland", "Auckland"),
        Opt("America/St_Johns", "Newfoundland"), Opt("America/Halifax", "Halifax"), Opt("America/New_York", "New York"), Opt("America/Toronto", "Toronto"),
        Opt("America/Chicago", "Chicago"), Opt("America/Denver", "Denver"), Opt("America/Phoenix", "Phoenix"), Opt("America/Los_Angeles", "Los Angeles"),
        Opt("America/Anchorage", "Alaska"), Opt("Pacific/Honolulu", "Hawaii"), Opt("America/Mexico_City", "Mexico City"),
        Opt("America/Bogota", "Bogota"), Opt("America/Sao_Paulo", "Sao Paulo"), Opt("America/Argentina/Buenos_Aires", "Buenos Aires"), Opt("UTC", "UTC"),
    )

    /** (language tag, what the list shows). English first: Phone Guard's own screens are English whatever is chosen, the rest of the phone follows it. */
    val languages: List<Opt> = listOf(
        Opt("en-GB", "English (UK)"), Opt("en-US", "English (US)"), Opt("en-AU", "English (Australia)"), Opt("en-CA", "English (Canada)"),
        Opt("en-IE", "English (Ireland)"), Opt("en-NZ", "English (New Zealand)"), Opt("en-IN", "English (India)"), Opt("en-ZA", "English (South Africa)"),
        Opt("fr-FR", "Francais"), Opt("de-DE", "Deutsch"), Opt("es-ES", "Espanol"), Opt("it-IT", "Italiano"),
        Opt("nl-NL", "Nederlands"), Opt("pt-PT", "Portugues"), Opt("pl-PL", "Polski"),
    )

    fun zoneName(id: String): String = zones.firstOrNull { it.id == id }?.name ?: id.substringAfterLast('/').replace('_', ' ')
    fun languageName(tag: String): String = languages.firstOrNull { it.id.equals(tag, true) }?.name
        ?: languages.firstOrNull { it.id.substringBefore('-').equals(tag.substringBefore('-'), true) }?.name ?: tag

    /** Sets the phone's time zone. Needs SET_TIME_ZONE, which the system app has. Returns true when it took. */
    fun applyZone(c: android.content.Context, id: String): Boolean = try {
        c.getSystemService(android.app.AlarmManager::class.java).setTimeZone(id)
        true
    } catch (e: Throwable) { io.github.durkimat.phoneguard.Log.log(c, "regional: time zone not set: ${e.javaClass.simpleName} ${e.message}"); false }

    /**
     * Sets the phone's language the way Android's own picker does (the activity manager's persistent configuration; needs CHANGE_CONFIGURATION and, as it
     * is a hidden call, the app being declared as a system app that uses them). Returns true when it took.
     */
    fun applyLanguage(c: android.content.Context, tag: String): Boolean = try {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val cls = Class.forName("android.app.IActivityManager")
        val cfg = cls.getMethod("getConfiguration").invoke(am) as android.content.res.Configuration
        cfg.setLocales(android.os.LocaleList(java.util.Locale.forLanguageTag(tag)))
        android.content.res.Configuration::class.java.getField("userSetLocale").setBoolean(cfg, true)
        cls.getMethod("updatePersistentConfiguration", android.content.res.Configuration::class.java).invoke(am, cfg)
        true
    } catch (e: Throwable) { io.github.durkimat.phoneguard.Log.log(c, "regional: language not set: ${e.javaClass.simpleName} ${e.cause?.message ?: e.message}"); false }
}
