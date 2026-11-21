package com.stevencastro.wornpixel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * Draws each frame into a 160x120 bitmap with antialiasing off, then scales
 * it up 4x with no filtering so every low-res pixel becomes a crisp block.
 * Black is see-through on the glasses, so the background stays black.
 */
public class PixelView extends View {
    private static final int W = 160;
    private static final int H = 120;
    private static final int FRAME_MS = 66;
    private static final float SWIPE_MIN_PX = 60;

    private static final int SKIN = Color.rgb(255, 214, 186);
    private static final int SKIN_SHADE = Color.rgb(230, 170, 140);
    private static final int EYE_WHITE = Color.rgb(250, 250, 255);
    private static final int MOUTH = Color.rgb(220, 90, 110);
    private static final int BLUSH = Color.rgb(255, 150, 160);

    // Per girl: hair, hair shade, eye color.
    private static final int[][] GIRLS = {
            {Color.rgb(255, 120, 190), Color.rgb(200, 70, 140), Color.rgb(90, 200, 255)},
            {Color.rgb(120, 170, 255), Color.rgb(70, 110, 210), Color.rgb(255, 120, 80)},
            {Color.rgb(255, 220, 90), Color.rgb(210, 160, 40), Color.rgb(120, 230, 140)},
    };

    // Outfit sets: top, top shade, skirt, legwear, shoes.
    private static final int[][] OUTFITS = {
            {Color.rgb(250, 250, 255), Color.rgb(190, 200, 230), Color.rgb(40, 60, 150), Color.rgb(30, 30, 40), Color.rgb(120, 60, 40)},
            {Color.rgb(230, 40, 80), Color.rgb(160, 20, 60), Color.rgb(30, 30, 40), Color.rgb(30, 30, 40), Color.rgb(30, 30, 40)},
            {Color.rgb(60, 220, 200), Color.rgb(30, 160, 150), Color.rgb(250, 250, 255), Color.rgb(255, 214, 186), Color.rgb(250, 250, 255)},
            {Color.rgb(160, 90, 255), Color.rgb(110, 50, 200), Color.rgb(60, 30, 90), Color.rgb(60, 30, 90), Color.rgb(200, 180, 255)},
    };

    private static final int[] CENTERS = {30, 80, 130};

    private final Bitmap frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas c = new Canvas(frame);
    private final Paint p = new Paint();
    private final Paint blit = new Paint();
    private final Rect src = new Rect(0, 0, W, H);
    private final Rect dst = new Rect();
    private final RectF oval = new RectF();
    private final Random random = new Random();
    private final int[][] stars = new int[24][3];

    private long startMs;
    private boolean running;
    private boolean waving;
    private int outfit;
    private float downX;
    private float downY;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    public PixelView(Context context) {
        super(context);
        p.setAntiAlias(false);
        p.setStyle(Paint.Style.FILL);
        blit.setFilterBitmap(false);
        blit.setAntiAlias(false);
        for (int[] s : stars) {
            s[0] = random.nextInt(W);
            s[1] = random.nextInt(H / 2);
            s[2] = random.nextInt(1000);
        }
    }

    public void start() {
        startMs = System.currentTimeMillis();
        running = true;
        post(loop);
    }

    public void stop() {
        running = false;
        removeCallbacks(loop);
    }

    public void nextOutfit(int delta) {
        outfit = (outfit + delta + OUTFITS.length) % OUTFITS.length;
    }

    public void toggleWave() {
        waving = !waving;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_UP: {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) >= SWIPE_MIN_PX && Math.abs(dx) > Math.abs(dy)) {
                    nextOutfit(dx > 0 ? 1 : -1);
                } else if (Math.abs(dy) >= SWIPE_MIN_PX) {
                    nextOutfit(dy > 0 ? 1 : -1);
                } else {
                    toggleWave();
                }
                return true;
            }
            default:
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float t = (System.currentTimeMillis() - startMs) / 1000f;

        frame.eraseColor(Color.BLACK);
        drawStars(t);
        for (int i = 0; i < CENTERS.length; i++) {
            drawGirl(CENTERS[i], GIRLS[i], OUTFITS[(outfit + i) % OUTFITS.length], t + i * 0.7f, i);
        }

        // Letterbox to keep pixels square.
        int scale = Math.max(1, Math.min(getWidth() / W, getHeight() / H));
        int left = (getWidth() - W * scale) / 2;
        int top = (getHeight() - H * scale) / 2;
        dst.set(left, top, left + W * scale, top + H * scale);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(frame, src, dst, blit);
    }

    private void drawStars(float t) {
        for (int[] s : stars) {
            float phase = (t * 1000 + s[2]) % 1600 / 1600f;
            if (phase < 0.5f) {
                int a = (int) (255 * (1 - Math.abs(phase - 0.25f) * 4));
                px(s[0], s[1], Color.argb(a, 255, 255, 200));
            }
        }
    }

    private void drawGirl(int cx, int[] look, int[] fit, float t, int index) {
        int hair = look[0], hairShade = look[1], eye = look[2];
        int top = fit[0], topShade = fit[1], skirt = fit[2], legs = fit[3], shoes = fit[4];

        // Whole-body bob and breathing, snapped to whole low-res pixels.
        int bob = Math.round((float) Math.sin(t * 2.2f) * 1.2f);
        int breath = Math.round((float) Math.sin(t * 2.2f + 0.6f) * 1f);
        int sway = Math.round((float) Math.sin(t * 1.6f) * 1.5f);
        boolean blink = ((t + index * 1.3f) % 3.6f) < 0.15f;

        int y0 = 6 + bob;              // top of hair
        int headCy = y0 + 10;
        int neckY = headCy + 7;
        int shoulderY = neckY + 3;
        int chestY = shoulderY + 6 + breath;
        int waistY = shoulderY + 15;
        int hipY = waistY + 5;
        int skirtEnd = hipY + 9;
        int footY = 116;

        // Long back hair, swaying.
        rect(cx - 8 + sway / 2, headCy - 4, cx + 8 + sway, waistY - 2, hairShade);
        rect(cx - 7 + sway / 2, headCy - 4, cx + 7 + sway, waistY - 4, hair);

        // Legs and shoes.
        rect(cx - 5, skirtEnd - 1, cx - 2, footY - 2, legs);
        rect(cx + 2, skirtEnd - 1, cx + 5, footY - 2, legs);
        rect(cx - 6, footY - 2, cx - 1, footY, shoes);
        rect(cx + 1, footY - 2, cx + 6, footY, shoes);

        // Arms. The right arm (viewer's right) raises and waves when toggled.
        rect(cx - 10, shoulderY + 1, cx - 7, hipY + 1, SKIN);
        if (waving) {
            int wave = Math.round((float) Math.sin(t * 9f) * 1.5f);
            rect(cx + 7, shoulderY - 8, cx + 10, shoulderY + 2, SKIN);
            rect(cx + 7 + wave, shoulderY - 12, cx + 11 + wave, shoulderY - 8, SKIN);
        } else {
            rect(cx + 7, shoulderY + 1, cx + 10, hipY + 1, SKIN);
        }

        // Torso: shoulders taper to the waist, then out to the hips.
        rect(cx - 8, shoulderY, cx + 8, shoulderY + 4, top);
        rect(cx - 6, shoulderY + 4, cx + 6, waistY, top);
        rect(cx - 4, waistY, cx + 4, hipY, topShade);
        // Chest: two filled ovals with a shade line underneath.
        ellipse(cx - 4, chestY, 4.5f, 4f, topShade);
        ellipse(cx + 4, chestY, 4.5f, 4f, topShade);
        ellipse(cx - 4, chestY - 1, 4f, 3.5f, top);
        ellipse(cx + 4, chestY - 1, 4f, 3.5f, top);
        px(cx, chestY - 2, topShade);
        // Collar.
        rect(cx - 2, shoulderY, cx + 2, shoulderY + 2, SKIN);

        // Skirt flares out from the hips.
        rect(cx - 7, hipY - 1, cx + 7, hipY + 3, skirt);
        rect(cx - 9, hipY + 3, cx + 9, skirtEnd, skirt);

        // Neck and head.
        rect(cx - 1, neckY - 1, cx + 2, shoulderY, SKIN_SHADE);
        ellipse(cx, headCy, 6f, 7f, SKIN);

        // Bangs and side locks.
        rect(cx - 7, y0 + 1, cx + 7, headCy - 3, hair);
        rect(cx - 7, headCy - 3, cx - 5, headCy + 6, hair);
        rect(cx + 5, headCy - 3, cx + 7, headCy + 6, hair);
        px(cx - 2, headCy - 3, hair);
        px(cx + 2, headCy - 3, hair);
        rect(cx - 3, y0, cx + 4, y0 + 1, hair);

        // Eyes: big anime eyes with a highlight, or a line when blinking.
        if (blink) {
            rect(cx - 4, headCy + 1, cx - 1, headCy + 2, hairShade);
            rect(cx + 1, headCy + 1, cx + 4, headCy + 2, hairShade);
        } else {
            rect(cx - 4, headCy - 1, cx - 1, headCy + 2, eye);
            rect(cx + 1, headCy - 1, cx + 4, headCy + 2, eye);
            px(cx - 3, headCy - 1, EYE_WHITE);
            px(cx + 2, headCy - 1, EYE_WHITE);
        }
        px(cx - 5, headCy + 3, BLUSH);
        px(cx + 4, headCy + 3, BLUSH);
        rect(cx - 1, headCy + 4, cx + 1, headCy + 5, MOUTH);
    }

    private void px(int x, int y, int color) {
        p.setColor(color);
        c.drawRect(x, y, x + 1, y + 1, p);
    }

    private void rect(int l, int t, int r, int b, int color) {
        p.setColor(color);
        c.drawRect(l, t, r, b, p);
    }

    private void ellipse(float cx, float cy, float rx, float ry, int color) {
        p.setColor(color);
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
        c.drawOval(oval, p);
    }
}
