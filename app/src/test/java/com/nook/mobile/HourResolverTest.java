package com.nook.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.nook.mobile.domain.HourResolver;

import org.junit.Test;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * HourResolver 单测：24 段小时值换算、rainy 特例、Pocket Camp 时段全映射（FR-01/12/13）。
 */
public class HourResolverTest {

    private static Calendar calAt(int hourOfDay) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hourOfDay);
        return c;
    }

    @Test
    public void midnightIs12am() {
        assertEquals("12am", HourResolver.getHour("new-leaf", calAt(0)));
    }

    @Test
    public void thirteenIs1pm() {
        assertEquals("1pm", HourResolver.getHour("new-leaf", calAt(13)));
    }

    @Test
    public void fullDayMapping() {
        String[] expected = {
                "12am", "1am", "2am", "3am", "4am", "5am", "6am", "7am", "8am", "9am", "10am", "11am",
                "12pm", "1pm", "2pm", "3pm", "4pm", "5pm", "6pm", "7pm", "8pm", "9pm", "10pm", "11pm"
        };
        for (int h = 0; h < 24; h++) {
            assertEquals("hour " + h, expected[h], HourResolver.getHour("new-leaf", calAt(h)));
        }
    }

    @Test
    public void rainySpecialAlways12am() {
        for (int h = 0; h < 24; h++) {
            assertEquals("population-growing-rainy at " + h,
                    "12am", HourResolver.getHour("population-growing-rainy", calAt(h)));
        }
    }

    @Test
    public void pocketCampCoversAll24HoursNoGap() {
        String[] hours = {
                "12am", "1am", "2am", "3am", "4am", "5am", "6am", "7am", "8am", "9am", "10am", "11am",
                "12pm", "1pm", "2pm", "3pm", "4pm", "5pm", "6pm", "7pm", "8pm", "9pm", "10pm", "11pm"
        };
        Map<String, Integer> counts = new HashMap<>();
        for (String hour : hours) {
            String cat = HourResolver.hourToPocketCamp(hour);
            assertNotNull("no category for " + hour, cat);
            counts.put(cat, counts.getOrDefault(cat, 0) + 1);
        }
        assertEquals(Integer.valueOf(4), counts.get("morning"));
        assertEquals(Integer.valueOf(8), counts.get("day"));
        assertEquals(Integer.valueOf(2), counts.get("evening"));
        assertEquals(Integer.valueOf(10), counts.get("night"));
    }

    @Test
    public void pocketCampBoundaryValues() {
        assertEquals("morning", HourResolver.hourToPocketCamp("5am"));
        assertEquals("morning", HourResolver.hourToPocketCamp("8am"));
        assertEquals("day", HourResolver.hourToPocketCamp("9am"));
        assertEquals("day", HourResolver.hourToPocketCamp("4pm"));
        assertEquals("evening", HourResolver.hourToPocketCamp("5pm"));
        assertEquals("evening", HourResolver.hourToPocketCamp("6pm"));
        assertEquals("night", HourResolver.hourToPocketCamp("7pm"));
        assertEquals("night", HourResolver.hourToPocketCamp("4am"));
    }
}
