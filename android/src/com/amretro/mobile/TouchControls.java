package com.amretro.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class TouchControls extends View {
    interface Listener { void onButtons(int mask); }
    private final Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mask;
    private float u, leftX, leftY, ax, ay, bx, by, selectX, startX, smallY;
    TouchControls(Context context, Listener listener) { super(context); this.listener = listener; setFocusable(false); }
    private void positions() {
        float w = getWidth(), h = getHeight();
        u = Math.min(w / 9.7f, h / 5.9f);
        leftX = 1.55f * u; leftY = h - 1.55f * u;
        ax = w - 1.12f * u; ay = h - 1.75f * u;
        bx = w - 2.28f * u; by = h - 1.10f * u;
        selectX = w / 2 - 0.60f * u; startX = w / 2 + 0.60f * u; smallY = h - 0.54f * u;
    }
    private void circle(Canvas c, float x, float y, float r, String text, int bits) {
        paint.setColor((mask & bits) != 0 ? 0xb5a98cff : 0x6677869f); c.drawCircle(x, y, r, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(2, u * .025f)); paint.setColor(0x99ffffff); c.drawCircle(x, y, r, paint); paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE); paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0)); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(r * .82f);
        c.drawText(text, x, y - (paint.ascent() + paint.descent()) / 2, paint);
    }
    private void pill(Canvas c, float x, float y, String text, int bits) {
        paint.setColor((mask & bits) != 0 ? 0xb5a98cff : 0x6677869f);
        RectF rect = new RectF(x - .42f * u, y - .16f * u, x + .42f * u, y + .16f * u); c.drawRoundRect(rect, .16f * u, .16f * u, paint);
        paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(.18f * u); c.drawText(text, x, y - (paint.ascent() + paint.descent()) / 2, paint);
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); positions();
        circle(c, leftX, leftY, 1.02f * u, "", (1<<4)|(1<<5)|(1<<6)|(1<<7));
        paint.setColor(0xe8ffffff); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(.40f * u);
        c.drawText("▲", leftX, leftY - .52f*u, paint); c.drawText("▼", leftX, leftY + .78f*u, paint);
        c.drawText("◀", leftX - .63f*u, leftY + .14f*u, paint); c.drawText("▶", leftX + .63f*u, leftY + .14f*u, paint);
        circle(c, ax, ay, .52f*u, "A", 1<<8); circle(c, bx, by, .52f*u, "B", 1);
        pill(c, selectX, smallY, "SELECT", 1<<2); pill(c, startX, smallY, "START", 1<<3);
        pill(c, .78f*u, .42f*u, "L", 1<<10); pill(c, getWidth()-.78f*u, .42f*u, "R", 1<<11);
    }
    private boolean near(float x, float y, float cx, float cy, float r) { float dx=x-cx, dy=y-cy; return dx*dx+dy*dy<=r*r; }
    private boolean inPill(float x, float y, float cx, float cy) { return Math.abs(x-cx)<.53f*u && Math.abs(y-cy)<.27f*u; }
    @Override public boolean onTouchEvent(MotionEvent event) {
        positions(); int next = 0, skip = event.getActionMasked()==MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
        if (event.getActionMasked()!=MotionEvent.ACTION_UP && event.getActionMasked()!=MotionEvent.ACTION_CANCEL) {
            for (int i=0; i<event.getPointerCount(); i++) {
                if (i==skip) continue;
                float x=event.getX(i), y=event.getY(i), dx=x-leftX, dy=y-leftY;
                if (near(x,y,leftX,leftY,1.16f*u)) {
                    if (dy < -.28f*u) next |= 1<<4; if (dy > .28f*u) next |= 1<<5;
                    if (dx < -.28f*u) next |= 1<<6; if (dx > .28f*u) next |= 1<<7;
                }
                if (near(x,y,ax,ay,.66f*u)) next |= 1<<8;
                if (near(x,y,bx,by,.66f*u)) next |= 1;
                if (inPill(x,y,selectX,smallY)) next |= 1<<2;
                if (inPill(x,y,startX,smallY)) next |= 1<<3;
                if (inPill(x,y,.78f*u,.42f*u)) next |= 1<<10;
                if (inPill(x,y,getWidth()-.78f*u,.42f*u)) next |= 1<<11;
            }
        }
        if (next != mask) { mask=next; listener.onButtons(next); invalidate(); }
        return true;
    }
}
