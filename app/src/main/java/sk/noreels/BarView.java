package sk.noreels;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * Prekrytie spodnej lišty.
 * - Jednoduchý režim: plná farba len cez skrytú ikonu (kým sa appka "učí" vzhľad tlačidiel).
 * - Režim lišty: nakreslí celú lištu nanovo z uložených obrázkov ostatných tlačidiel,
 *   rovnomerne rozložených. Ťuknutia posiela späť na skutočné tlačidlá.
 */
final class BarView extends View {

    interface Listener {
        void onTap(int index);
        void onLongPress(int index);
    }

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final GestureDetector gestures;
    private final android.graphics.Rect src = new android.graphics.Rect();
    private final android.graphics.Rect dst = new android.graphics.Rect();

    private Bitmap[] icons;         // obrázky ponechaných tlačidiel zľava doprava
    private int solid = 0xFF000000; // farba pozadia lišty

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
        icons = null;
        solid = color;
        invalidate();
    }

    void showBar(Bitmap[] icons, int bgColor) {
        this.icons = icons;
        this.solid = bgColor;
        invalidate();
    }

    boolean isBarMode() {
        return icons != null;
    }

    private int slotAt(float x) {
        if (icons == null || icons.length == 0 || getWidth() == 0) return -1;
        int i = (int) (x / (getWidth() / (float) icons.length));
        return Math.max(0, Math.min(icons.length - 1, i));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(solid);
        if (icons == null) return;
        // pozadie lišty: okrajový stĺpec posledného tlačidla roztiahnutý na celú šírku
        // (zachová aj deliacu čiaru nad lištou)
        Bitmap edge = icons[icons.length - 1];
        if (edge != null && !edge.isRecycled() && edge.getWidth() > 2) {
            src.set(edge.getWidth() - 2, 0, edge.getWidth() - 1, edge.getHeight());
            dst.set(0, 0, getWidth(), edge.getHeight());
            canvas.drawBitmap(edge, src, dst, null);
        }
        float slot = getWidth() / (float) icons.length;
        for (int i = 0; i < icons.length; i++) {
            Bitmap b = icons[i];
            if (b == null || b.isRecycled()) continue;
            float left = Math.round(i * slot + (slot - b.getWidth()) / 2f);
            canvas.drawBitmap(b, left, 0, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (icons == null) return true; // jednoduchý režim: len zjesť ťuknutie
        gestures.onTouchEvent(e);
        return true;
    }
}
