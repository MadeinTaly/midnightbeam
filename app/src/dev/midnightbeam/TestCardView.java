package dev.midnightbeam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * Test card for the TV screen, same layout as assets/card.svg (the phone preview): a white page with text (glare,
 * readability), a landscape with a blue sky (what the filter takes away), colour patches with a skin tone, and a
 * black-to-white grey ramp (shadow detail lost when dimming). Drawn plain: the real filter of the TV covers it.
 * Coordinates are in the SVG's 320 x 180 units, scaled to the view; the height follows the width (16:9).
 */
public class TestCardView extends View {
    private static final float W = 320, H = 180;
    private static final int[] PATCHES = {0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
            0xFF00ACC1, 0xFF1E5BD8, 0xFF8E24AA, 0xFFE0AC8A};
    private static final int[] GREYS = {0x00, 0x1C, 0x38, 0x55, 0x71, 0x8E, 0xAA, 0xC6, 0xE3, 0xFF};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path hill = new Path();
    private LinearGradient sky;

    public TestCardView(Context context) {
        super(context);
        hill.moveTo(158, 56);
        hill.quadTo(200, 40, 240, 54);
        hill.quadTo(280, 68, 312, 50); // the SVG's "T": control point mirrored around (240, 54)
        hill.lineTo(312, 66);
        hill.quadTo(312, 72, 306, 72);
        hill.lineTo(164, 72);
        hill.quadTo(158, 72, 158, 66);
        hill.close();
        sky = new LinearGradient(0, 8, 0, 72, 0xFF2F6FD6, 0xFFB9DCFF, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, Math.round(w * H / W));
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = getWidth() / W;
        c.save();
        c.scale(s, s);
        p.setShader(null);
        p.setColor(0xFF16171B);
        c.drawRoundRect(0, 0, W, H, 8, 8, p);

        p.setColor(0xFFFFFFFF); // white page with text
        c.drawRoundRect(8, 8, 150, 112, 6, 6, p);
        p.setColor(0xFF1B1B1F);
        p.setTextSize(24);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText("Aa", 18, 38, p);
        float[][] lines = {{50, 120}, {62, 104}, {74, 114}, {86, 80}, {98, 96}};
        for (int i = 0; i < lines.length; i++) {
            p.setColor(i == lines.length - 1 ? 0xFF9A9AA2 : 0xFF3A3A40);
            c.drawRoundRect(18, lines[i][0], 18 + lines[i][1], lines[i][0] + 6, 3, 3, p);
        }

        p.setShader(sky); // landscape
        c.drawRoundRect(158, 8, 312, 72, 6, 6, p);
        p.setShader(null);
        p.setColor(0xFFFFF6C8);
        c.drawCircle(284, 26, 10, p);
        p.setColor(0xFFFFFFFF);
        c.drawOval(178, 23, 214, 37, p);
        p.setColor(0xFF3F9B4C);
        c.drawPath(hill, p);

        for (int i = 0; i < PATCHES.length; i++) { // colour patches
            p.setColor(PATCHES[i]);
            float x = 158 + i * 19.5f;
            c.drawRoundRect(x, 78, i == PATCHES.length - 1 ? 312 : x + 17, 112, 3, 3, p);
        }

        float step = (W - 16) / GREYS.length; // grey ramp
        for (int i = 0; i < GREYS.length; i++) {
            p.setColor(0xFF000000 | GREYS[i] << 16 | GREYS[i] << 8 | GREYS[i]);
            c.drawRect(8 + i * step, 120, 8 + (i + 1) * step, 172, p);
        }
        c.restore();
    }
}
