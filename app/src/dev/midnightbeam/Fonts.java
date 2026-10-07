package dev.midnightbeam;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * The app's typefaces, shared with the phone page: Chakra Petch (text) and Inconsolata (monospace), both SIL Open
 * Font License 1.1 (assets/fonts, subset to Latin). Falls back to the system font if an asset cannot be read.
 */
final class Fonts {
    private static Typeface regular;
    private static Typeface bold;

    private Fonts() {
    }

    static synchronized Typeface regular(Context c) {
        if (regular == null) regular = load(c, "fonts/ChakraPetch-Regular.ttf", Typeface.DEFAULT);
        return regular;
    }

    static synchronized Typeface bold(Context c) {
        if (bold == null) bold = load(c, "fonts/ChakraPetch-SemiBold.ttf", Typeface.DEFAULT_BOLD);
        return bold;
    }

    private static Typeface load(Context c, String asset, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), asset);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Sets the app typeface on every text of a view tree, keeping bold where it was bold. */
    static void apply(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            Typeface current = t.getTypeface();
            boolean isBold = current != null && current.isBold();
            t.setTypeface(isBold ? bold(v.getContext()) : regular(v.getContext()));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }
}
