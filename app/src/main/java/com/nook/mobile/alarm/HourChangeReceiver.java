package com.nook.mobile.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.nook.mobile.service.PlayerService;

/**
 * 整点兜底闹钟广播（FR-01 辅助）：AlarmManager setExactAndAllowWhileIdle 在整点触发，
 * 转发给 PlayerService 立即执行一次 timeCheck（弥补 Doze 下 5s 轮询被冻结的情况）。
 */
public final class HourChangeReceiver extends BroadcastReceiver {

    public static final String ACTION_HOUR_ALARM = "com.nook.mobile.action.HOUR_ALARM";

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent service = new Intent(context, PlayerService.class);
        service.setAction(PlayerService.ACTION_TIME_CHECK);
        // 服务处于前台播放中才会安排闹钟，此处用前台方式启动是安全的
        ContextCompat.startForegroundService(context, service);
    }
}
