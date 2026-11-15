package sk.noreels;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * Prekrytie spodnej lišty.
 * - Jednoduchý režim: plná farba len cez skrytú ikonu.
 * - Režim lišty: nakreslí celú lištu nanovo zo snímky okna appky — bez skrytej ikony,
 *   ostatné ikony rovnomerne rozložené. Ťuknutia posiela späť na skutočné tlačidlá.
 */
final class BarView extends View {

    interface Listener {
        void onTap(int index);
        void onLongPress(int index);
    }

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private final Rect tmpSrc = new Rect();
    private final Rect tmpDst = new Rect();

    private Bitmap bar;      // výrez celej lišty
    private Rect[] icons;    // ponechané tlačidlá (súradnice v rámci lišty)
    private int bgX;         // stĺpec pozadia lišty (bez ikon)
    private int solid = 0xFF000000;

    BarView(Context c, Listener listener) {
        super(c);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                int i = slotAt(e.getX());
                if (i >= 0) listener.onTap(i);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                int i = slotAt(e.getX());
                if (i >= 0) listener.onLongPress(i);
            }
        });
    }

    void showSolid(int color) {
        bar = null;
        icons = null;
        solid = color;
        invalidate();
    }

    void showBar(Bitmap bar, Rect[] icons, int bgX) {
        this.bar = bar;
        this.icons = icons;
        this.bgX = bgX;
        invalidate();
    }

    boolean isBarMode() {
        return bar != null;
    }

    private int slotAt(float x) {
        if (bar == null || icons == null || icons.length == 0 || getWidth() == 0) return -1;
        int i = (int) (x / (getWidth() / (float) icons.length));
        return Math.max(0, Math.min(icons.length - 1, i));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bar == null || icons == null || bar.isRecycled()) {
            canvas.drawColor(solid);
            return;
        }
        int w = getWidth();
        int h = getHeight();

        // pozadie: 1 px stĺpec z prázdneho miesta lišty roztiahnutý na celú šírku
        // (zachová aj prípadnú deliacu čiaru hore)
        tmpSrc.set(bgX, 0, bgX + 1, bar.getHeight());
        tmpDst.set(0, 0, w, h);
        canvas.drawBitmap(bar, tmpSrc, tmpDst, paint);

        float slot = w / (float) icons.length;
        for (int i = 0; i < icons.length; i++) {
            Rect s = icons[i];
            int left = Math.round(i * slot + (slot - s.width()) / 2f);
            tmpDst.set(left, s.top, left + s.width(), s.bottom);
            canvas.drawBitmap(bar, s, tmpDst, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bar == null) return true; // jednoduchý režim: len zjesť ťuknutie
        gestures.onTouchEvent(e);
        return true;
    }
}
