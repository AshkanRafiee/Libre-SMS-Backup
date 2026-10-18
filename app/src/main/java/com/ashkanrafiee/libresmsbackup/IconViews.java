package com.ashkanrafiee.libresmsbackup;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Small platform-drawn marks keep the hero independent from emoji fonts and device glyphs. */
final class AppMarkView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    AppMarkView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setContentDescription(getContext().getString(R.string.app_mark_description));
    }

    @Override protected void onDraw(Canvas canvas) {
        float inset = 2 * density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getResources().getColor(R.color.accent));
        canvas.drawRoundRect(new RectF(inset, inset, getWidth() - inset, getHeight() - inset), 16 * density, 16 * density, paint);
        paint.setColor(getResources().getColor(R.color.accent_ink));
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(getHeight() * .30f);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText("LSB", getWidth() / 2f, baseline, paint);
    }
}
