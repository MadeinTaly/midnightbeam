package dev.midnightbeam;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * The app's typefaces, shared with the phone page: Chivo (text, 400/700/900), Bungee (wordmark, heads) and Silkscreen
 * (figures), all SIL Open
 * Font License 1.1 (assets/fonts, subset to Latin). Falls back to the system font if an asset cannot be read.
 */
final class Fonts {
    /** View tags read by {@link #apply}: the Bungee wordmark, a Chivo Black section head. */
    static final String DISPLAY = "font:display";
    static final String HEAD = "font:head";
    static final String FIGURE = "font:figure";

    private static Typeface regular;
    private static Typeface bold;
    private static Typeface black;
    private static Typeface display;
    private static Typeface figure;

    private Fonts() {
    }

    static synchronized Typeface regular(Context c) {
        if (regular == null) regular = load(c, "fonts/Chivo-Regular.ttf", Typeface.DEFAULT);
        return regular;
    }

    static synchronized Typeface bold(Context c) {
        if (bold == null) bold = load(c, "fonts/Chivo-Bold.ttf", Typeface.DEFAULT_BOLD);
        return bold;
    }

    static synchronized Typeface black(Context c) {
        if (black == null) black = load(c, "fonts/Chivo-Black.ttf", Typeface.DEFAULT_BOLD);
        return black;
    }

    static synchronized Typeface display(Context c) {
        if (display == null) display = load(c, "fonts/Bungee-Regular.ttf", Typeface.DEFAULT_BOLD);
        return display;
    }

    static synchronized Typeface figure(Context c) {
        if (figure == null) figure = load(c, "fonts/Silkscreen-Bold.ttf", Typeface.MONOSPACE);
        return figure;
    }

    private static Typeface load(Context c, String asset, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), asset);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Sets the app typeface on every text of a view tree: tagged heads, then bold where it was bold. */
    static void apply(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            Context c = v.getContext();
            Typeface current = t.getTypeface();
            boolean isBold = current != null && current.isBold();
            t.setTypeface(DISPLAY.equals(v.getTag()) ? display(c) : HEAD.equals(v.getTag()) ? black(c) : FIGURE.equals(v.getTag()) ? figure(c)
                    : isBold || v instanceof android.widget.Button ? bold(c) : regular(c));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }
}
