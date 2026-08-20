package com.nook.mobile;

import static org.junit.Assert.assertEquals;

import com.nook.mobile.domain.UrlBuilder;

import org.junit.Test;

/**
 * UrlBuilder 单测：普通/KK（含特殊字符）/雨声 URL 拼接与 toLocalName 各类型（FR-02/40、§4.1）。
 */
public class UrlBuilderTest {

    private static final String BASE = "https://d17orwheorv96d.cloudfront.net";

    @Test
    public void hourlyUrl() {
        assertEquals(BASE + "/new-leaf/3pm.ogg", UrlBuilder.buildHourlyUrl("new-leaf", "3pm"));
    }

    @Test
    public void pocketCampUrl() {
        assertEquals(BASE + "/pocket-camp/evening.ogg",
                UrlBuilder.buildHourlyUrl("pocket-camp", "evening"));
    }

    @Test
    public void kkUrlEncodesSpaces() {
        assertEquals(BASE + "/kk-slider-desktop/Agent%20K.K..ogg",
                UrlBuilder.buildKkUrl("Agent K.K."));
    }

    @Test
    public void rainUrls() {
        assertEquals(BASE + "/rain/rain.ogg", UrlBuilder.buildRainUrl(false, false));
        assertEquals(BASE + "/rain/game-rain.ogg", UrlBuilder.buildRainUrl(true, false));
        assertEquals(BASE + "/rain/no-thunder-rain.ogg", UrlBuilder.buildRainUrl(false, true));
        // gameRain 优先于 peacefulRain
        assertEquals(BASE + "/rain/game-rain.ogg", UrlBuilder.buildRainUrl(true, true));
    }

    @Test
    public void toLocalNameHourly() {
        assertEquals("new-leaf-3pm.ogg", UrlBuilder.toLocalName(BASE + "/new-leaf/3pm.ogg"));
    }

    @Test
    public void toLocalNamePocketCamp() {
        assertEquals("pocket-camp-evening.ogg",
                UrlBuilder.toLocalName(BASE + "/pocket-camp/evening.ogg"));
    }

    @Test
    public void toLocalNameRain() {
        assertEquals("rain-rain.ogg", UrlBuilder.toLocalName(BASE + "/rain/rain.ogg"));
    }

    @Test
    public void toLocalNameKkUsesUnencodedName() {
        // 传入已编码 URL，本地名应还原空格
        assertEquals("kk-slider-desktop-Agent K.K..ogg",
                UrlBuilder.toLocalName(BASE + "/kk-slider-desktop/Agent%20K.K..ogg"));
    }

    @Test
    public void kkUrlToLocalNameRoundTrip() {
        String url = UrlBuilder.buildKkUrl("Agent K.K.");
        assertEquals("kk-slider-desktop-Agent K.K..ogg", UrlBuilder.toLocalName(url));
    }
}
