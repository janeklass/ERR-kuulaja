package ee.janek.errkuulaja;

import android.net.Uri;

/** Üks ERR-i artikkel uudisvoost. */
public final class Article {
    public final long id;
    public final String title;
    public final String link;
    public final long pubMillis;
    public final String category;
    /** Pisipildi URL (RSS media:thumbnail, 256×256) või null. */
    public final String image;
    /** Rubriik, mille järgi kuulamist filtreeritakse (vt {@link #rubricFor}). */
    public final String rubric;

    public Article(long id, String title, String link, long pubMillis, String category, String feedRubric,
                   String image) {
        this.id = id;
        this.image = image;
        this.title = title;
        this.link = link;
        this.pubMillis = pubMillis;
        this.category = category;
        this.rubric = rubricFor(link, category, feedRubric);
    }

    /**
     * Rubriigi määramine:
     * - sport/kultuur/novaator/menu alamdomeen → vastav portaal;
     * - www.err.ee artiklil RSS-i kategooria, kui see on Eesti, Välismaa, Majandus või Arvamus;
     *   raadio-/teleuudiste blokid (neil pole "Kuula artiklit" heli) → Muu;
     *   muu kategooria korral voo enda rubriik (nt /rss/majandus → Majandus), vaikimisi Eesti;
     * - kõik ülejäänud (eeter, vikerraadio jt – heli pole) → Muu.
     */
    static String rubricFor(String link, String category, String feedRubric) {
        String host = Uri.parse(link).getHost();
        if (host == null) return "Muu";
        switch (host) {
            case "sport.err.ee":
                return "Sport";
            case "kultuur.err.ee":
                return "Kultuur";
            case "novaator.err.ee":
                return "Novaator";
            case "menu.err.ee":
                return "Menu";
            case "www.err.ee":
            case "err.ee":
                break;
            default:
                return "Muu";
        }
        String c = category == null ? "" : category.trim();
        switch (c) {
            case "Eesti":
            case "Välismaa":
            case "Majandus":
            case "Arvamus":
                return c;
            case "Raadiouudised":
            case "Teleuudised":
                return "Muu";
            default:
                return feedRubric != null ? feedRubric : "Eesti";
        }
    }

    public String source() {
        return rubric;
    }

    /** Novaatori alateema (Tervis, Keskkond, Tehnika …) või null, kui artikkel pole Novaatorist. */
    public String topic() {
        if (!"Novaator".equals(rubric) || category == null) return null;
        String c = category.trim();
        return c.isEmpty() ? null : c;
    }

    public String sourceLabel() {
        return rubric;
    }
}
