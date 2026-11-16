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
        void onExitDone();
    }

    private static final int NONE = 0, EXIT = 1, ENTER = 2;
    private final Listener listener;
    private int slide;      // lišta appky je posunutá doprava o toľko px (swipe na kameru)
    private int anim = NONE; // prechod na inú obrazovku appky (chat) a späť
    private long animStart;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final GestureDetector gestures;
    private final android.graphics.Rect src = new android.graphics.Rect();
    private final android.graphics.Rect dst = new android.graphics.Rect();

    private Bitmap[] icons;         // obrázky ponechaných tlačidiel zľava doprava
    private int solid = 0xFF000000; // farba pozadia lišty

    BarView(Context c, Listener listener) {
        super(c);
        this.listener = listener;
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

    /** Lišta appky sa posúva do strany (swipe na kameru) — kresliť posunuté rovnako. */
    void setSlide(int px) {
        if (px == slide) return;
        slide = px;
        invalidate();
    }

    /** Appka otvára ďalšiu obrazovku, ktorá sa zasúva sprava cez lištu. */
    void animateExit() {
        anim = EXIT;
        animStart = android.os.SystemClock.uptimeMillis();
        invalidate();
    }

    /** Appka sa vracia na obrazovku s lištou (tá predošlá odchádza doprava). */
    void animateEnter() {
        anim = ENTER;
        animStart = android.os.SystemClock.uptimeMillis() - 60; // udalosť o návrate chodí asi o 60 ms neskôr, než animácia začne
        invalidate();
    }

    boolean isAnimating() {
        return anim != NONE;
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
        int w = getWidth();
        float shift = slide;
        if (slide > 0) canvas.clipRect(slide, 0, w, getHeight());
        if (anim != NONE) {
            // napodobenie prechodu Instagramu: nová obrazovka sa zasúva sprava (rýchly dobeh),
            // obrazovka s lištou sa pritom posunie kúsok doľava
            float t = android.os.SystemClock.uptimeMillis() - animStart;
            float k = (float) Math.exp(-t / 48f);
            float edge;
            if (anim == EXIT) {
                edge = w * k;
                shift = -0.12f * w * (1f - (float) Math.exp(-t / 28f));
                if (t > 230) {
                    anim = NONE;
                    post(listener::onExitDone);
                    return;
                }
            } else {
                edge = w * (1f - k);
                shift = -0.12f * w * (float) Math.exp(-t / 85f);
                if (t > 380) {
                    anim = NONE;
                    edge = w;
                    shift = 0;
                }
            }
            canvas.clipRect(0, 0, edge, getHeight());
            if (anim != NONE) postInvalidateOnAnimation();
        }
        canvas.drawColor(solid);
        if (icons == null) return;
        canvas.translate(Math.round(shift), 0);
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
        if (icons == null || anim != NONE || slide != 0) return true; // len zjesť ťuknutie
        gestures.onTouchEvent(e);
        return true;
    }
}
