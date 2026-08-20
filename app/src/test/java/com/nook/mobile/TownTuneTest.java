package com.nook.mobile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.nook.mobile.domain.TownTune;

import org.junit.Test;

/**
 * TownTune 单测：滑块↔符号互转与默认旋律（FR-20）。
 */
public class TownTuneTest {

    @Test
    public void symbolSetSizes() {
        assertEquals(16, TownTune.SYMBOLS.length);
        assertEquals(13, TownTune.PITCHES.length);
    }

    @Test
    public void sliderToSymbolBoundaries() {
        assertEquals("zZz", TownTune.symbolForSlider(1));
        assertEquals("-", TownTune.symbolForSlider(2));
        assertEquals("G1", TownTune.symbolForSlider(10));
        assertEquals("?", TownTune.symbolForSlider(16));
    }

    @Test
    public void symbolToSliderBoundaries() {
        assertEquals(1, TownTune.sliderForSymbol("zZz"));
        assertEquals(2, TownTune.sliderForSymbol("-"));
        assertEquals(10, TownTune.sliderForSymbol("G1"));
        assertEquals(16, TownTune.sliderForSymbol("?"));
    }

    @Test
    public void sliderSymbolRoundTrip() {
        for (int v = 1; v <= 16; v++) {
            assertEquals(v, TownTune.sliderForSymbol(TownTune.symbolForSlider(v)));
        }
    }

    @Test
    public void defaultTune() {
        String[] expected = {
                "G1", "E2", "-", "G1", "F1", "D2", "-", "B2",
                "C2", "zZz", "?", "zZz", "C1", "-", "zZz", "zZz"
        };
        assertArrayEquals(expected, TownTune.defaultTune());
        assertEquals(16, TownTune.defaultTune().length);
    }

    @Test
    public void defaultTuneReturnsCopy() {
        String[] a = TownTune.defaultTune();
        a[0] = "MUTATED";
        assertEquals("G1", TownTune.defaultTune()[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void sliderOutOfRangeThrows() {
        TownTune.symbolForSlider(17);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownSymbolThrows() {
        TownTune.sliderForSymbol("XYZ");
    }
}
