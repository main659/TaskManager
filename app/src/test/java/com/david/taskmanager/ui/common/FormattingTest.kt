package com.david.taskmanager.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class FormattingTest {

    private val today = LocalDate.of(2026, 12, 1)

    @Test
    fun `dates in the current year omit the year`() {
        assertEquals("3 Dec", formatDate(LocalDate.of(2026, 12, 3), today, Locale.UK))
    }

    @Test
    fun `dates in another year include it`() {
        assertEquals("5 Jan 2027", formatDate(LocalDate.of(2027, 1, 5), today, Locale.UK))
    }
}
