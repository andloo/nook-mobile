package com.nook.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.appcompat.widget.AppCompatSeekBar;

/**
 * 竖直 SeekBar（主题曲编辑器 16 音符滑块用，FR-22）。
 * 通过旋转画布实现：进度从下（min）到上（max）。
 */
public class VerticalSeekBar extends AppCompatSeekBar {

    public VerticalSeekBar(Context context) {
        super(context);
    }

    public VerticalSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VerticalSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // 宽高互换后交给父类布局轨道
        super.onSizeChanged(h, w, oldh, oldw);
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(heightMeasureSpec, widthMeasureSpec);
        setMeasuredDimension(getMeasuredHeight(), getMeasuredWidth());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.rotate(-90);
        canvas.translate(-getHeight(), 0);
        super.onDraw(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                int max = getMax();
                int progress = max - (int) (max * event.getY() / getHeight());
                progress = Math.max(0, Math.min(max, progress));
                if (progress != getProgress()) {
                    // 必须带 onSizeChanged 刷新内部轨道/thumb 布局，否则旋转坐标下 thumb 会错位沉底
                    setProgressAndRefresh(progress);
                    // 手势改动不会触发 onProgressChanged 的 fromUser 分支，手动派发
                    if (changeListener != null) {
                        changeListener.onProgressChanged(this, progress, true);
                    }
                }
                invalidate();
                // 阻止父容器（HorizontalScrollView）抢占滑动手势
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** 用户拖动回调（旋转绘制下 setProgress 不派发 fromUser=true，改用自有监听）。 */
    public interface OnUserChangeListener {
        void onProgressChanged(VerticalSeekBar bar, int progress, boolean fromUser);
    }

    private OnUserChangeListener changeListener;

    public void setOnUserChangeListener(OnUserChangeListener listener) {
        this.changeListener = listener;
    }

    /** 代码设置进度并刷新旋转后的绘制。 */
    public void setProgressAndRefresh(int progress) {
        setProgress(progress);
        onSizeChanged(getWidth(), getHeight(), 0, 0);
    }
}
