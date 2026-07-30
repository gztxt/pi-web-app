package com.gztxt.piweb;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** 简单的圆角矩形背景 Drawable(Splash 图标 / π 悬浮按钮)。 */
public class RoundRectDrawable extends Drawable {

    private final Paint paint;
    private final float radius;
    private final RectF rect = new RectF();

    public RoundRectDrawable(int color, float radius) {
        this.radius = radius;
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
    }

    @Override
    public void draw(Canvas canvas) {
        rect.set(getBounds());
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
