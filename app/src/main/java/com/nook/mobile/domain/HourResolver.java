package com.nook.mobile.domain;

import java.util.Calendar;

/**
 * 小时解析器：把真实世界的小时转换为整点音乐使用的“小时值”，并映射 Pocket Camp 时段。
 * 对应桌面版 player.js 的 getHour() / hourToPocketCamp()（FR-01 / FR-12 / FR-13）。
 */
public final class HourResolver {

    private HourResolver() {
    }

    /**
     * 计算当前小时值。
     * <p>
     * game 为 "population-growing-rainy" 时恒返回 "12am"（FR-13 特例）；
     * 否则采用 12 小时制：hour12 = (h + 24) % 12 || 12，后缀 h &gt;= 12 ? "pm" : "am"（FR-01）。
     *
     * @param game 当前游戏 ID（可为 null）
     * @param cal  提供当前时间的日历（使用设备本地时区，见 §7.3）
     * @return 形如 "12am"、"1pm" 的小时值
     */
    public static String getHour(String game, Calendar cal) {
        if ("population-growing-rainy".equals(game)) {
            return "12am";
        }
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int hour12 = (h + 24) % 12;
        if (hour12 == 0) {
            hour12 = 12;
        }
        String suffix = h >= 12 ? "pm" : "am";
        return hour12 + suffix;
    }

    /**
     * 把 24 段小时值映射为 Pocket Camp 的 4 个时段键（FR-12）。
     * morning(5am-8am)/day(9am-4pm)/evening(5pm-6pm)/night(7pm-4am)。
     *
     * @param hour 24 段小时值
     * @return "morning" / "day" / "evening" / "night"；无法识别时返回 null
     */
    public static String hourToPocketCamp(String hour) {
        switch (hour) {
            case "5am":
            case "6am":
            case "7am":
            case "8am":
                return "morning";
            case "9am":
            case "10am":
            case "11am":
            case "12pm":
            case "1pm":
            case "2pm":
            case "3pm":
            case "4pm":
                return "day";
            case "5pm":
            case "6pm":
                return "evening";
            case "7pm":
            case "8pm":
            case "9pm":
            case "10pm":
            case "11pm":
            case "12am":
            case "1am":
            case "2am":
            case "3am":
            case "4am":
                return "night";
            default:
                return null;
        }
    }
}
