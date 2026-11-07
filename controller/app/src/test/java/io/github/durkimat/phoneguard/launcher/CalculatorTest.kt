package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorTest {
    private fun Calculator.type(s: String) { for (ch in s) if (ch == '.') point() else digit(ch - '0') }

    @Test fun addsAndShowsThePendingSum() {
        val c = Calculator(); c.type("12.5"); c.operator('+')
        assertEquals("12.5 +", c.pending); c.type("3"); assertEquals("3", c.display)
        c.equals(); assertEquals("15.5", c.display)
    }

    @Test fun chainsOperationsLeftToRight() {
        val c = Calculator(); c.type("2"); c.operator('+'); c.type("3"); c.operator('x'); assertEquals("5 x", c.pending)
        c.type("4"); c.equals(); assertEquals("20", c.display)
    }

    @Test fun starStepsThroughTheOperators() {
        val c = Calculator(); c.type("8"); c.cycleOperator(); assertEquals("8 +", c.pending)
        c.cycleOperator(); assertEquals("8 -", c.pending); c.cycleOperator(); c.cycleOperator(); assertEquals("8 /", c.pending)
        c.cycleOperator(); assertEquals("8 +", c.pending)
    }

    @Test fun divisionAndDecimals() {
        val c = Calculator(); c.type("1"); c.operator('/'); c.type("3"); c.equals(); assertEquals("0.333333333333", c.display)
        val d = Calculator(); d.type("10"); d.operator('/'); d.type("4"); d.equals(); assertEquals("2.5", d.display)
    }

    @Test fun divideByZeroShowsErrorAndTheNextKeyStartsAgain() {
        val c = Calculator(); c.type("5"); c.operator('/'); c.type("0"); c.equals(); assertEquals("Error", c.display)
        c.digit(7); assertEquals("7", c.display)
    }

    @Test fun anAnswerCanBeUsedForTheNextSum() {
        val c = Calculator(); c.type("6"); c.operator('x'); c.type("7"); c.equals(); assertEquals("42", c.display)
        c.operator('+'); c.type("8"); c.equals(); assertEquals("50", c.display)
    }

    @Test fun typingAfterAnAnswerStartsANewNumber() {
        val c = Calculator(); c.type("1"); c.operator('+'); c.type("1"); c.equals(); c.type("9"); assertEquals("9", c.display)
    }

    @Test fun clearRemovesDigitsThenTheSumThenReportsNothingLeft() {
        val c = Calculator(); c.type("12"); c.operator('+'); c.type("34")
        assertTrue(c.clear()); assertEquals("3", c.display)
        assertTrue(c.clear()); assertEquals("12 +", c.pending) // typed number gone, sum remains
        assertTrue(c.clear()); assertTrue(c.isEmpty)
        assertFalse(c.clear())
    }

    @Test fun pointAndLimits() {
        val c = Calculator(); c.point(); assertEquals("0.", c.display); c.type("5"); c.point(); assertEquals("0.5", c.display)
        val d = Calculator(); d.type("1234567890123"); assertEquals("123456789012", d.display) // twelve digits at most
    }

    @Test fun negativeResultsAndNoNegativeZero() {
        val c = Calculator(); c.type("3"); c.operator('-'); c.type("5"); c.equals(); assertEquals("-2", c.display)
    }
}
