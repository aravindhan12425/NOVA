package com.assistant.nova.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsefulAnswersTest {

    private fun p(s: String) = CommandParser.parse(s, "unlock")
    private fun calc(s: String) = (p(s) as Command.Calculate).expression.let { Calculator.evaluate(it)!! }

    @Test fun `calculator`() {
        assertEquals(100.0, calc("what's 25 times 4"), 1e-9)
        assertEquals(300.0, calc("15 percent of 2000"), 1e-9)
        assertEquals(12.0, calc("square root of 144"), 1e-9)
        assertEquals(1024.0, calc("2 to the power of 10"), 1e-9)
        assertEquals(8333.33, calc("1 lakh divided by 12"), 0.01)
        assertEquals(6.0, calc("calculate 5 plus 3 minus 2"), 1e-9)
        assertEquals(11.0, calc("what is 5 plus 3 times 2"), 1e-9) // times before plus
        assertEquals(7.5, calc("2.5 times 3"), 1e-9)
        assertEquals(2600.0, calc("two thousand five hundred plus 100"), 1e-9)
        // A number on its own isn't a calculation
        assertNull(Calculator.evaluate("2 thousand 500"))
        assertEquals(20.0, calc("5 into 4"), 1e-9)
        assertEquals(42.0, calc("what's 6 x 7"), 1e-9)
        assertEquals("100", Calculator.format(100.0))
        assertEquals("3.33", Calculator.format(10.0 / 3))
        assertEquals("1,234,567", Calculator.format(1234567.0))
        assertNull(Calculator.evaluate("the time"))
    }

    @Test fun `conversions`() {
        assertEquals(Command.Convert(5.0, "kilometres", "miles"), p("convert 5 km to miles"))
        assertEquals(Command.Convert(100.0, "fahrenheit", "celsius"), p("100 fahrenheit in celsius"))
        assertEquals(Command.Convert(2.0, "metres", "feet"), p("how many feet in 2 metres"))
        assertEquals(Command.Convert(2.0, "kilograms", "pounds"), p("what is 2 kg in pounds"))
        assertEquals(Command.Convert(1.0, "miles", "kilometres"), p("how many kilometres in a mile"))
        assertEquals(37.78, Units.convert(100.0, Units.unit("f")!!, Units.unit("c")!!)!!, 0.01)
        assertEquals(3.107, Units.convert(5.0, Units.unit("km")!!, Units.unit("miles")!!)!!, 0.001)
    }

    @Test fun `countdowns`() {
        assertEquals(Command.DaysUntil(12, 25, "christmas"), p("how many days until Christmas"))
        assertEquals(12, (p("how many days until 25 December") as Command.DaysUntil).month)
        assertEquals(25, (p("days left for the 25th of December") as Command.DaysUntil).day)
        assertEquals(Command.DaysUntil(1, 1, "new year"), p("days until new year"))
        assertEquals(3, (p("how many days until march 3") as Command.DaysUntil).month)
    }

    @Test fun `next alarm, storage, spelling`() {
        assertEquals(Command.Info(InfoQuery.NEXT_ALARM), p("when is my next alarm"))
        assertEquals(Command.Info(InfoQuery.NEXT_ALARM), p("what time is my alarm"))
        assertEquals(Command.Info(InfoQuery.NEXT_ALARM), p("do I have an alarm set for tomorrow"))
        assertEquals(Command.Info(InfoQuery.STORAGE), p("how much storage is left"))
        assertEquals(Command.Info(InfoQuery.STORAGE), p("how much space do I have"))
        assertEquals(Command.Spell("necessary"), p("how do you spell necessary"))
        assertEquals(Command.Info(InfoQuery.TIME), p("Hey Nova what's the time"))
        // Setting alarms still works
        assertEquals(Command.Alarm(6, 30), p("set an alarm for 6:30 am"))
        assertEquals(Command.Alarm(7, 30), p("set an alarm for 7.30 am"))
    }

    @Test fun `reminders`() {
        assertEquals(Command.Alarm(18, 0, "call mom"), p("remind me to call mom at 6 pm"))
        assertEquals(Command.AlarmIn(20 * 60, "drink water"), p("remind me in 20 minutes to drink water"))
        assertEquals(Command.AlarmIn(30 * 60, "check the oven"), p("remind me to check the oven in 30 minutes"))
        assertEquals(Command.Alarm(21, 0, "look at the stars"), p("remind me to look at the stars at 9 pm"))
        assertEquals(Command.Alarm(-1, -1, "pay the bill"), p("remind me to pay the bill"))
    }

    @Test fun `notes`() {
        assertEquals(Command.AddNote("buy milk"), p("take a note buy milk"))
        assertEquals(Command.AddNote("the car is parked on level 2"), p("remember that the car is parked on level 2"))
        assertEquals(Command.AddNote("call the bank"), p("note down call the bank"))
        assertEquals(Command.AddNote(""), p("take a note"))
        assertEquals(Command.ReadNotes, p("read my notes"))
        assertEquals(Command.ReadNotes, p("what are my notes"))
        assertEquals(Command.ClearNotes, p("clear my notes"))
    }

    @Test fun `a cut-off song request still works`() {
        assertEquals(Command.IdentifySong, p("the song"))
    }
}
