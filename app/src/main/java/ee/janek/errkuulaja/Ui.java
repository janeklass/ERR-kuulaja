package ee.janek.errkuulaja;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/**
 * "Ajaleht"-kujundus (v1.7): paberitoon, tindivärvi jooned ja raamid, serif-pealkirjad,
 * teravad nurgad, ERR-i punane aktsent. Tume režiim = sama leht "negatiivis".
 * Värvipalett (hele/tume), mõõdud ja väikesed joonistusabid programmilise UI jaoks.
 */
final class Ui {
    final boolean night;
    /** bg = paber, surface = heledam paber (kaart), surface2 = kohatäide, text = tint, text2 = hall tint. */
    final int bg, surface, surface2, text, text2, accent, onAccent, outline;
    /** Sans-kirjad siltidele, numbritele ja nuppudele. */
    final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    /** Serif-kirjad pealkirjadele ja "ajalehe" tekstile. */
    final Typeface serif = Typeface.create("serif", Typeface.NORMAL);
    final Typeface serifBold = Typeface.create("serif", Typeface.BOLD);
    final Typeface serifItalic = Typeface.create("serif", Typeface.ITALIC);
    private final float density;

    Ui(Context c) {
        night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        density = c.getResources().getDisplayMetrics().density;
        if (night) {
            bg = 0xFF15130F; surface = 0xFF1E1B16; surface2 = 0xFF2E2922;
            text = 0xFFF3EEE4; text2 = 0xFFB3A99A; accent = 0xFFE25A45; onAccent = 0xFF15130F;
            outline = 0xFF4A443B;
        } else {
            bg = 0xFFF3EEE4; surface = 0xFFFBF8F1; surface2 = 0xFFE4DCCB;
            text = 0xFF1A1714; text2 = 0xFF5A524A; accent = 0xFFB8321F; onAccent = 0xFFF3EEE4;
            outline = 0xFFCFC5B3;
        }
    }

    int dp(int v) {
        return Math.round(v * density);
    }

    int dpf(float v) {
        return Math.max(1, Math.round(v * density));
    }

    static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** Rubriigi tunnusvärv: tumedad trükivärvid paberil, tumedas režiimis heledamad toonid. */
    int rubricColor(String r) {
        if (night) {
            switch (r) {
                case "Eesti": return 0xFFE25A45;
                case "Välismaa": return 0xFF7FA7E0;
                case "Majandus": return 0xFFD9B44A;
                case "Arvamus": return 0xFFC08BD6;
                case "Sport": return 0xFF6FBF8E;
                case "Kultuur": return 0xFFE07AA6;
                case "Novaator": return 0xFFE8905A;
                case "Menu": return 0xFF9AA6E0;
                default: return 0xFFB3A99A;
            }
        }
        switch (r) {
            case "Eesti": return 0xFFB8321F;
            case "Välismaa": return 0xFF1F4E8C;
            case "Majandus": return 0xFF8A6A00;
            case "Arvamus": return 0xFF7A3E8F;
            case "Sport": return 0xFF1E7A4A;
            case "Kultuur": return 0xFFA03A6A;
            case "Novaator": return 0xFFC2541A;
            case "Menu": return 0xFF4A5A9A;
            default: return 0xFF7A7268;
        }
    }

    /** Ühtlane taust. Ajalehe stiilis on nurgad alati teravad – raadius jäetakse arvestamata. */
    GradientDrawable rounded(int fill, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(0);
        return g;
    }

    /** Ühe dp paksuse joonega raam (nt kiibid). */
    GradientDrawable outlined(int fill, int stroke, int radiusDp) {
        return bordered(fill, stroke, 1f);
    }

    /** Tindiraamiga kast; fill 0 = läbipaistev. */
    GradientDrawable bordered(int fill, int stroke, float strokeDp) {
        GradientDrawable g = rounded(fill, 0);
        g.setStroke(dpf(strokeDp), stroke);
        return g;
    }

    /** Täidetud ruut (ajariba pide). */
    GradientDrawable square(int fill, int sizeDp) {
        GradientDrawable g = rounded(fill, 0);
        g.setSize(dp(sizeDp), dp(sizeDp));
        return g;
    }

    GradientDrawable circle(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        return g;
    }

    /** Taust koos vajutuse lainetusega; mask on sama kujuga kui sisu. */
    Drawable ripple(GradientDrawable content, int rippleColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(content.getShape());
        mask.setCornerRadius(content.getCornerRadius());
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(alpha(rippleColor, 0x33)), content, mask);
    }

    /** Läbipaistev lainetus (nt nimekirja rea või tekstinupu jaoks). */
    Drawable rippleOnly(Context c, boolean borderless) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(borderless
                ? android.R.attr.selectableItemBackgroundBorderless
                : android.R.attr.selectableItemBackground, tv, true);
        return c.getDrawable(tv.resourceId);
    }

    /** Pildivaade tindiraamiga (1 dp), pilt raami sees. */
    android.widget.ImageView image(Context c, int sizeDp) {
        android.widget.ImageView iv = new android.widget.ImageView(c);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(bordered(surface2, text, 1f));
        int p = dpf(1f);
        iv.setPadding(p, p, p, p);
        iv.setCropToPadding(true);
        iv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    TextView text(Context c, String s, int sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    /** Väike kapiteelsilt (rubriik, nimekirja pealkiri): 11 sp, suurtähed, hõre. */
    TextView label(Context c, String s, int color) {
        TextView t = text(c, s, 11, color, medium);
        t.setAllCaps(true);
        t.setLetterSpacing(0.1f);
        return t;
    }

    /** Tekstinupp ilma raamita (lainetusega). */
    TextView textButton(Context c, String s, int color, View.OnClickListener l) {
        TextView t = text(c, s, 14, color, medium);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackground(rippleOnly(c, false));
        t.setClickable(true);
        t.setFocusable(true);
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    /** Suurtähtedega väike tekstinupp (nt "Näita kuulatuid", "Unusta ajalugu"). */
    TextView capsButton(Context c, String s, int color, View.OnClickListener l) {
        TextView t = textButton(c, s, color, l);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        return t;
    }
}
