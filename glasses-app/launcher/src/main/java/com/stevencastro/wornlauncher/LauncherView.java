package com.stevencastro.wornlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * App grid driven by a head-tracked cursor. Hovering only highlights; a
 * touchpad tap opens the highlighted app. The middle of the grid is an
 * empty rest zone where the cursor starts, and each icon's hit area is inset
 * from its cell, so there is always somewhere to look that selects nothing.
 * The strips on the left and right edges flip pages the same way, and
 * pages wrap around.
 *
 * Black is see-through on the glasses, so the background stays black and
 * everything else is drawn bright.
 */
public class LauncherView extends View {
    public interface Callbacks {
        void onLaunch(AppEntry app);
    }

    public static final class AppEntry {
        final String label;
        final String packageName;
        final String activityName;
        final Drawable icon;

        AppEntry(String label, String packageName, String activityName, Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.activityName = activityName;
            this.icon = icon;
        }
    }

    private static final int COLS = 4;
    private static final int ROWS = 3;
    // Grid cells (row * COLS + col) that hold icons. The two middle cells of
    // the middle row are the rest zone.
    private static final int[] SLOTS = {0, 1, 2, 3, 4, 7, 8, 9, 10, 11};
    private static final int PER_PAGE = SLOTS.length;
    private static final int REST_ROW = 1;
    private static final int REST_COL_FIRST = 1;
    private static final int REST_COL_LAST = 2;
    // Fraction of each cell (per side) that doesn't count as hovering the icon.
    private static final float HIT_INSET = 0.12f;

    private static final int NONE = -1;
    private static final int PAGE_PREV = -2;
    private static final int PAGE_NEXT = -3;

    private static final int ACCENT = Color.rgb(80, 220, 255);
    private static final int DIM = Color.rgb(90, 110, 130);

    private final Callbacks callbacks;
    private final List<AppEntry> apps = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint header = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private float headerH;
    private float edgeW;
    private float cellW;
    private float cellH;
    private float iconSize;

    private float cursorX = -1;
    private float cursorY = -1;
    private int page;
    private int hover = NONE;
    private String status;
    // Non-null while the head calibration flow is showing.
    private String calPrompt;
    private float calProgress;

    public LauncherView(Context context, Callbacks callbacks) {
        super(context);
        this.callbacks = callbacks;
        setBackgroundColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        label.setColor(Color.WHITE);
        label.setTextAlign(Paint.Align.CENTER);
        header.setColor(DIM);
    }

    public void setApps(List<AppEntry> list) {
        apps.clear();
        apps.addAll(list);
        if (page >= pageCount()) page = Math.max(0, pageCount() - 1);
        refreshHover();
    }

    public void setStatus(String status) {
        this.status = status;
        invalidate();
    }

    /** Shows a calibration step; pass null to return to the app grid. */
    public void setCalibration(String prompt, float progress) {
        calPrompt = prompt;
        calProgress = progress;
        invalidate();
    }

    public boolean isCalibrating() {
        return calPrompt != null;
    }

    /** Re-reads what's under the cursor (after a page flip or relayout). */
    public void refreshHover() {
        hover = targetAt(cursorX, cursorY);
        invalidate();
    }

    public void setCursor(float x, float y) {
        cursorX = x;
        cursorY = y;
        refreshHover();
    }

    /** Opens whatever is highlighted right now (touchpad tap). */
    public void selectHovered() {
        if (calPrompt != null) return;
        int t = targetAt(cursorX, cursorY);
        if (t == PAGE_PREV) {
            flipPage(-1);
        } else if (t == PAGE_NEXT) {
            flipPage(1);
        } else if (t >= 0 && t < apps.size()) {
            callbacks.onLaunch(apps.get(t));
        }
    }

    /** Changes page, wrapping from the last page to the first and back. */
    public void flipPage(int delta) {
        int pages = pageCount();
        if (pages < 2) return;
        page = ((page + delta) % pages + pages) % pages;
        refreshHover();
    }

    private int pageCount() {
        return Math.max(1, (apps.size() + PER_PAGE - 1) / PER_PAGE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        headerH = h * 0.09f;
        edgeW = w * 0.06f;
        cellW = (w - 2 * edgeW) / COLS;
        cellH = (h - headerH) / ROWS;
        iconSize = Math.min(cellW, cellH) * 0.5f;
        label.setTextSize(cellH * 0.13f);
        header.setTextSize(headerH * 0.55f);
        stroke.setStrokeWidth(Math.max(2f, w / 220f));
        refreshHover();
    }

    private float cellLeft(int col) {
        return edgeW + col * cellW;
    }

    private float cellTop(int row) {
        return headerH + row * cellH;
    }

    private int targetAt(float x, float y) {
        if (x < 0 || y < headerH || cellW == 0) return NONE;
        if (x < edgeW) return pageCount() > 1 ? PAGE_PREV : NONE;
        if (x >= getWidth() - edgeW) return pageCount() > 1 ? PAGE_NEXT : NONE;
        int col = (int) ((x - edgeW) / cellW);
        int row = (int) ((y - headerH) / cellH);
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return NONE;

        // Only the inner part of a cell counts, leaving gaps between icons.
        float fx = (x - cellLeft(col)) / cellW;
        float fy = (y - cellTop(row)) / cellH;
        if (fx < HIT_INSET || fx > 1 - HIT_INSET || fy < HIT_INSET || fy > 1 - HIT_INSET) return NONE;

        int cell = row * COLS + col;
        for (int slot = 0; slot < SLOTS.length; slot++) {
            if (SLOTS[slot] == cell) {
                int index = page * PER_PAGE + slot;
                return index < apps.size() ? index : NONE;
            }
        }
        return NONE; // rest zone
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();

        if (calPrompt != null) {
            drawCalibration(canvas, w, h);
            return;
        }

        // Header: clock on the left, page dots on the right.
        float baseline = headerH * 0.72f;
        header.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(clock.format(new Date()), edgeW, baseline, header);
        if (status != null) {
            header.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(status, w / 2f, baseline, header);
        }
        int pages = pageCount();
        if (pages > 1) {
            float r = headerH * 0.1f;
            float gap = r * 3.5f;
            float startX = w - edgeW - (pages - 1) * gap;
            for (int i = 0; i < pages; i++) {
                fill.setColor(i == page ? ACCENT : DIM);
                canvas.drawCircle(startX + i * gap, headerH * 0.5f, r, fill);
            }
        }

        drawRestZone(canvas);

        if (apps.isEmpty()) {
            label.setColor(DIM);
            canvas.drawText("No apps found", w / 2f, h / 2f, label);
            label.setColor(Color.WHITE);
        }

        // Icons on this page.
        int first = page * PER_PAGE;
        int last = Math.min(apps.size(), first + PER_PAGE);
        for (int i = first; i < last; i++) {
            int cell = SLOTS[i - first];
            int col = cell % COLS;
            int row = cell / COLS;
            float cx = cellLeft(col) + cellW / 2f;
            float top = cellTop(row);
            float iconTop = top + cellH * 0.12f;
            AppEntry app = apps.get(i);
            boolean hot = i == hover;

            if (hot) {
                rect.set(cellLeft(col) + cellW * HIT_INSET * 0.5f, top + cellH * HIT_INSET * 0.3f,
                        cellLeft(col) + cellW * (1 - HIT_INSET * 0.5f), top + cellH * (1 - HIT_INSET * 0.3f));
                float corner = cellW * 0.12f;
                fill.setColor(ACCENT);
                fill.setAlpha(40);
                canvas.drawRoundRect(rect, corner, corner, fill);
                fill.setAlpha(255);
                stroke.setColor(ACCENT);
                canvas.drawRoundRect(rect, corner, corner, stroke);
            }

            if (app.icon != null) {
                int l = (int) (cx - iconSize / 2f);
                int t = (int) iconTop;
                app.icon.setBounds(l, t, l + (int) iconSize, t + (int) iconSize);
                app.icon.draw(canvas);
            }

            label.setColor(hot ? ACCENT : Color.WHITE);
            CharSequence text = TextUtils.ellipsize(app.label, label, cellW * 0.92f,
                    TextUtils.TruncateAt.END);
            canvas.drawText(text, 0, text.length(), cx,
                    iconTop + iconSize + label.getTextSize() * 1.4f, label);
        }
        label.setColor(Color.WHITE);

        // Page-flip strips.
        drawEdge(canvas, pages > 1, true, hover == PAGE_PREV);
        drawEdge(canvas, pages > 1, false, hover == PAGE_NEXT);

        // Cursor.
        if (cursorX >= 0) {
            float r = Math.max(6f, w / 70f);
            stroke.setColor(Color.WHITE);
            canvas.drawCircle(cursorX, cursorY, r, stroke);
            fill.setColor(ACCENT);
            canvas.drawCircle(cursorX, cursorY, r * 0.35f, fill);
        }
    }

    /** Faint outline and hint in the empty middle, where the cursor rests. */
    private void drawRestZone(Canvas canvas) {
        float l = cellLeft(REST_COL_FIRST) + cellW * HIT_INSET;
        float r = cellLeft(REST_COL_LAST + 1) - cellW * HIT_INSET;
        float t = cellTop(REST_ROW) + cellH * HIT_INSET;
        float b = cellTop(REST_ROW + 1) - cellH * HIT_INSET;
        rect.set(l, t, r, b);
        float corner = cellH * 0.2f;
        stroke.setColor(DIM);
        stroke.setAlpha(70);
        canvas.drawRoundRect(rect, corner, corner, stroke);
        stroke.setAlpha(255);

        label.setColor(DIM);
        float y = (t + b) / 2f + label.getTextSize() * 0.35f;
        canvas.drawText(hover >= 0 ? "tap to open" : "look at an app", (l + r) / 2f, y, label);
        label.setColor(Color.WHITE);
    }

    private void drawCalibration(Canvas canvas, int w, int h) {
        float size = label.getTextSize();
        label.setColor(DIM);
        canvas.drawText("Head calibration", w / 2f, h * 0.28f, label);

        label.setTextSize(size * 1.6f);
        label.setColor(Color.WHITE);
        canvas.drawText(calPrompt, w / 2f, h * 0.48f, label);
        label.setTextSize(size);

        float barW = w * 0.5f;
        float barH = Math.max(6f, h / 60f);
        float left = (w - barW) / 2f;
        float top = h * 0.58f;
        fill.setColor(DIM);
        rect.set(left, top, left + barW, top + barH);
        canvas.drawRoundRect(rect, barH / 2f, barH / 2f, fill);
        fill.setColor(ACCENT);
        rect.set(left, top, left + barW * Math.min(1f, calProgress), top + barH);
        canvas.drawRoundRect(rect, barH / 2f, barH / 2f, fill);

        label.setColor(DIM);
        canvas.drawText("Tap to skip", w / 2f, h * 0.8f, label);
        label.setColor(Color.WHITE);
    }

    private void drawEdge(Canvas canvas, boolean enabled, boolean left, boolean hot) {
        if (!enabled) return;
        float w = getWidth();
        float midY = headerH + (getHeight() - headerH) / 2f;
        float x0 = left ? 0 : w - edgeW;
        float cx = x0 + edgeW / 2f;
        float s = edgeW * 0.28f;

        if (hot) {
            fill.setColor(ACCENT);
            fill.setAlpha(50);
            canvas.drawRect(x0, headerH, x0 + edgeW, getHeight(), fill);
            fill.setAlpha(255);
        }

        stroke.setColor(hot ? ACCENT : DIM);
        float dir = left ? -1 : 1;
        canvas.drawLine(cx - dir * s * 0.5f, midY - s, cx + dir * s * 0.5f, midY, stroke);
        canvas.drawLine(cx + dir * s * 0.5f, midY, cx - dir * s * 0.5f, midY + s, stroke);
    }
}
