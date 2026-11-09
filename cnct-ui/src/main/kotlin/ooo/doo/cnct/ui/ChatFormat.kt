package ooo.doo.cnct.ui

import ooo.doo.cnct.CnctChatAvailability
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Times, days and sizes in the chosen language, on the visitor's own clock. Every number is written
 * with Latin digits, Arabic included — the formatters here never pick up a locale's own numerals.
 */
internal class ChatFormat(
    private val words: Words,
    private val locale: Locale,
    private val zone: ZoneId,
    private val now: () -> Instant = Instant::now,
) {
    private val clock: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withDecimalStyle(DecimalStyle.STANDARD)
    private val dayMonth: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMMM", locale).withDecimalStyle(DecimalStyle.STANDARD)
    private val weekday: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", locale)

    private fun today(): LocalDate = now().atZone(zone).toLocalDate()

    /** "2:13 PM", "14:13", "2:13 م". */
    fun time(at: Instant): String = clock.format(at.atZone(zone))

    /** "Today", "Yesterday", or "9 November" — the stamp above a day's messages. */
    fun day(at: Instant): String {
        val date = at.atZone(zone).toLocalDate()
        val today = today()
        return when (date) {
            today -> words(R.string.cnct_today)
            today.minusDays(1) -> words(R.string.cnct_yesterday)
            else -> dayMonth.format(date)
        }
    }

    /** "Conversation ended today", "… yesterday", "… on 9 November". */
    fun ended(at: Instant): String {
        val date = at.atZone(zone).toLocalDate()
        val today = today()
        val `when` = when (date) {
            today -> words(R.string.cnct_ended_today)
            today.minusDays(1) -> words(R.string.cnct_ended_yesterday)
            else -> words(R.string.cnct_ended_on, dayMonth.format(date))
        }
        return words(R.string.cnct_ended, `when`)
    }

    /**
     * **What the visitor can expect, said honestly** — the hosted widget's sentence, from the inbox's
     * `availability`. Closed: when the team is back, on the visitor's clock, and whether the assistant
     * can help meanwhile. Open: how fast replies have really come. Without a measurement it promises
     * nothing it cannot keep.
     */
    fun availability(now: CnctChatAvailability?): String {
        if (now == null) return words(R.string.cnct_reply_unknown)
        val opensAt = now.opensAt
        if (now.open == false && opensAt != null) {
            val back = backAt(opensAt)
            return if (now.assistant) words(R.string.cnct_back_assistant, back) else back
        }
        val minutes = now.replyMinutes ?: return words(if (now.assistant) R.string.cnct_assistant_now else R.string.cnct_reply_unknown)
        return when {
            minutes < 1 -> words(if (now.assistant) R.string.cnct_assistant_now else R.string.cnct_reply_under1)
            minutes <= 5 -> words(R.string.cnct_reply_few)
            minutes <= 20 -> words(R.string.cnct_reply20)
            minutes <= 60 -> words(R.string.cnct_reply_hour)
            minutes <= 240 -> words(R.string.cnct_reply_hours)
            else -> words(R.string.cnct_reply_day)
        }
    }

    /** "back today at 9:00", "tomorrow at 9:00", "Sunday at 9:00" — in the visitor's clock. */
    private fun backAt(at: Instant): String {
        val time = time(at)
        val date = at.atZone(zone).toLocalDate()
        val today = today()
        return when (date) {
            today -> words(R.string.cnct_back_today, time)
            today.plusDays(1) -> words(R.string.cnct_back_tomorrow, time)
            else -> words(R.string.cnct_back_on, weekday.format(date), time)
        }
    }

    companion object {
        /**
         * "1.2 MB", "220 KB", "980 B" — decimal units, as Android's own file sizes are, Latin digits
         * always, and held left to right so an Arabic line does not turn it into "KB 220".
         */
        fun size(bytes: Long?): String? {
            if (bytes == null || bytes < 0) return null
            return "\u2066" + plainSize(bytes) + "\u2069"
        }

        private fun plainSize(bytes: Long): String {
            return when {
                bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
                bytes >= 10_000_000 -> String.format(Locale.US, "%d MB", Math.round(bytes / 1e6))
                bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
                bytes >= 1_000 -> String.format(Locale.US, "%d KB", Math.round(bytes / 1e3))
                else -> String.format(Locale.US, "%d B", bytes)
            }
        }
    }
}
