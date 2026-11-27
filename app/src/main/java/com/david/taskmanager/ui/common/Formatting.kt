package com.david.taskmanager.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.david.taskmanager.R
import com.david.taskmanager.core.LockReason
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "3 Dec", or "3 Dec 2027" when the year differs from [today]'s. */
fun formatDate(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val pattern = if (date.year == today.year) "d MMM" else "d MMM yyyy"
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}

@Composable
fun lockReasonText(reason: LockReason, today: LocalDate): String = when (reason) {
    is LockReason.NotBefore -> stringResource(R.string.available_from, formatDate(reason.date, today))
    is LockReason.WaitingOn ->
        if (reason.delayDays == 0) {
            stringResource(R.string.waiting_on, reason.title)
        } else {
            pluralStringResource(R.plurals.waiting_on_with_delay, reason.delayDays, reason.delayDays, reason.title)
        }
    is LockReason.DelayAfter -> pluralStringResource(
        R.plurals.available_days_after,
        reason.delayDays,
        reason.delayDays,
        reason.title,
        formatDate(reason.unlockDate, today),
    )
}
