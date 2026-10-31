package com.daymark.app.data

/**
 * A short, bundled, offline list of national crisis lines the person can pick from when they set
 * their own (#521). Picking one only fills in the crisis-line fields: the person still reads them
 * and saves, exactly as if they had typed them, so nothing here confirms a line on its own.
 *
 * What this list must keep true:
 * - It is static. Nothing reads the phone's region, SIM, language or location to choose, sort or
 *   pre-select an entry; the person scrolls and picks (FEATURES §7.3). `CrisisLineDirectoryTest`
 *   holds this file and the picker to that.
 * - Every entry names the page its number was taken from and the day it was last checked against
 *   that page, so whoever keeps the list current knows where to look and how stale it is. A line
 *   changes (the Netherlands' 113 became free to call in 2025), so an entry is re-checked, never
 *   assumed.
 * - [Line.number] is already in the dialable form `CrisisStore.dialable` produces, and
 *   [Line.takesTexts] is true only where texts go to that same number.
 *
 * This file imports nothing, so its test runs on a plain JVM (`tools/jvm-source-tests.sh`).
 */
object CrisisLineDirectory {

    data class Line(
        /** The country or countries the line serves, as the list shows them. */
        val country: String,
        val label: String,
        val contact: String,
        val number: String,
        val takesTexts: Boolean,
        /** The service's own page, or a government page, that gives this number. */
        val source: String,
        /** The day [source] was last read for this entry, as YYYY-MM-DD. */
        val checkedOn: String,
    )

    /** The day the whole list was last checked, shown under the list so its age is never hidden. */
    const val CHECKED_ON = "2026-10-31"

    /** Ordered by country name, and by nothing about the person or their phone. */
    val lines: List<Line> = listOf(
        Line(
            country = "Australia",
            label = "Lifeline (Australia)",
            contact = "Call 13 11 14, or text 0477 13 11 14, any time",
            number = "131114",
            takesTexts = false,
            source = "https://www.lifeline.org.au/crisis-support/",
            checkedOn = CHECKED_ON,
        ),
        Line(
            country = "Canada",
            label = "9-8-8 Suicide Crisis Helpline (Canada)",
            contact = "Call or text 988, any time",
            number = "988",
            takesTexts = true,
            source = "https://988.ca",
            checkedOn = CHECKED_ON,
        ),
        Line(
            country = "France",
            label = "3114, numéro national de prévention du suicide (France)",
            contact = "Call 3114, free, any time",
            number = "3114",
            takesTexts = false,
            source = "https://www.sante.fr/le-numero-national-de-prevention-du-suicide-0",
            checkedOn = CHECKED_ON,
        ),
        Line(
            country = "Germany",
            label = "TelefonSeelsorge (Germany)",
            contact = "Call 0800 111 0 111 or 0800 111 0 222, any time",
            number = "08001110111",
            takesTexts = false,
            source = "https://familienportal.de/familienportal/lebenslagen/krise-und-konflikt/krisetelefone-anlaufstellen",
            checkedOn = CHECKED_ON,
        ),
        Line(
            country = "Ireland",
            label = "Samaritans (Ireland)",
            contact = "Call 116 123, free, any time",
            number = "116123",
            takesTexts = false,
            source = "https://www.samaritans.org/how-we-can-help/contact-samaritan/talk-us-phone/",
            checkedOn = CHECKED_ON,
        ),
        Line(
            country = "Netherlands",
            label = "113 Zelfmoordpreventie (Netherlands)",
            contact = "Call 113 or 0800-0113, free, any time",
            number = "113",
            takesTexts = false,
            source = "https://www.113.nl",
            checkedOn = CHECKED_ON,
        ),
        Line(
            country = "New Zealand",
            label = "1737, Need to talk? (New Zealand)",
            contact = "Call or text 1737, free, any time",
            number = "1737",
            takesTexts = true,
            source = "https://1737.org.nz/contact",
            checkedOn = CHECKED_ON,
        ),
        Line(
            country = "Spain",
            label = "024, Línea de atención a la conducta suicida (Spain)",
            contact = "Call 024, free, any time",
            number = "024",
            takesTexts = false,
            source = "https://www.sanidad.gob.es/linea024/home.htm",
            checkedOn = CHECKED_ON,
        ),
        Line(
            country = "United Kingdom",
            label = "Samaritans (UK)",
            contact = "Call 116 123, free, any time",
            number = "116123",
            takesTexts = false,
            source = "https://www.samaritans.org/how-we-can-help/contact-samaritan/talk-us-phone/",
            checkedOn = CHECKED_ON,
        ),
        Line(
            country = "United States",
            label = "988 Suicide & Crisis Lifeline (US)",
            contact = "Call or text 988",
            number = "988",
            takesTexts = true,
            source = "https://988lifeline.org",
            checkedOn = CHECKED_ON,
        ),
    )
}
