package ee.janek.errkuulaja;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Püsiv mälu: kuulatud artiklid, heli-URL-ide vahemälu, seaded, pooleli koht. */
public final class Store {
    /** Rubriikide eelistatud järjekord kasutajaliideses; voost tulevad uued rubriigid lisatakse lõppu. */
    public static final String[] ORDER = {
            "Eesti", "Välismaa", "Majandus", "Arvamus", "Sport", "Kultuur", "Novaator", "Menu", "Muu",
    };
    /** Rubriigid, mis on vaikimisi välja lülitatud. */
    private static final Set<String> DEFAULT_OFF = new HashSet<>(Collections.singletonList("Muu"));

    private static final int MAX_IDS = 3000;
    private static Store instance;
    private final SharedPreferences p;

    private Store(Context c) {
        p = c.getApplicationContext().getSharedPreferences("err_kuulaja", Context.MODE_PRIVATE);
    }

    public static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c);
        return instance;
    }

    // ---- kuulatud ----
    public synchronized boolean isHeard(long id) {
        return p.getStringSet("heard", Collections.emptySet()).contains(Long.toString(id));
    }

    public synchronized void markHeard(long id) {
        addId("heard", id);
    }

    public synchronized int heardCount() {
        return p.getStringSet("heard", Collections.emptySet()).size();
    }

    public synchronized void clearHistory() {
        SharedPreferences.Editor e = p.edit();
        e.remove("heard").remove("noaudio").remove("resume_id").remove("resume_pos");
        e.apply();
    }

    // ---- heli puudub ----
    public synchronized boolean isNoAudio(long id) {
        return p.getStringSet("noaudio", Collections.emptySet()).contains(Long.toString(id));
    }

    public synchronized void markNoAudio(long id) {
        addId("noaudio", id);
    }

    private void addId(String key, long id) {
        Set<String> s = new HashSet<>(p.getStringSet(key, Collections.emptySet()));
        s.add(Long.toString(id));
        if (s.size() > MAX_IDS) {
            // Viska välja kõige vanemad (väiksemad ID-d)
            List<Long> ids = new ArrayList<>();
            for (String x : s) {
                try { ids.add(Long.parseLong(x)); } catch (NumberFormatException ignored) { }
            }
            Collections.sort(ids);
            s.clear();
            for (int i = ids.size() - MAX_IDS; i < ids.size(); i++) s.add(Long.toString(ids.get(i)));
        }
        p.edit().putStringSet(key, s).apply();
    }

    // ---- heli-URL-i vahemälu ----
    public synchronized String audioUrl(long id) {
        return p.getString("a_" + id, null);
    }

    public synchronized void setAudioUrl(long id, String url) {
        p.edit().putString("a_" + id, url).apply();
    }

    // ---- rubriigid ----
    /**
     * Rubriik on sees, kui tal on oma väärtus; muidu kehtib "uute rubriikide vaikeväärtus" `src_default`
     * (pärast "ainult see rubriik" = väljas, pärast "Kõik sisse" = sees; enne kumbagi: kõik peale "Muu").
     * Nii ei ilmu uuendusega lisandunud rubriigid (nt Sport, Kultuur) ootamatult aktiivsena.
     */
    public synchronized boolean isEnabled(String source) {
        return p.getBoolean("src_" + source, p.getBoolean("src_default", !DEFAULT_OFF.contains(source)));
    }

    public synchronized void setEnabled(String source, boolean on) {
        p.edit().putBoolean("src_" + source, on).apply();
    }

    /** Jätab sisse ainult ühe rubriigi; kõik teised, ka hiljem lisanduvad, on väljas. */
    public synchronized void setOnly(String source, Collection<String> all) {
        SharedPreferences.Editor e = p.edit();
        for (String s : all) e.putBoolean("src_" + s, s.equals(source));
        e.putBoolean("src_" + source, true);
        e.putBoolean("src_default", false);
        e.apply();
    }

    public synchronized void enableAll(Collection<String> all) {
        setAll(all, true);
    }

    public synchronized void setAll(Collection<String> all, boolean on) {
        SharedPreferences.Editor e = p.edit();
        for (String s : all) e.putBoolean("src_" + s, on);
        e.putBoolean("src_default", on);
        e.apply();
    }

    // ---- Novaatori alateemad (vaikimisi kõik sees; `top_default` nagu rubriikidel) ----
    public synchronized boolean isTopicEnabled(String topic) {
        return p.getBoolean("top_" + topic, p.getBoolean("top_default", true));
    }

    public synchronized void setTopicEnabled(String topic, boolean on) {
        p.edit().putBoolean("top_" + topic, on).apply();
    }

    public synchronized void setTopics(Collection<String> all, boolean on) {
        SharedPreferences.Editor e = p.edit();
        for (String s : all) e.putBoolean("top_" + s, on);
        e.putBoolean("top_default", on);
        e.apply();
    }

    public synchronized void setOnlyTopic(String topic, Collection<String> all) {
        SharedPreferences.Editor e = p.edit();
        for (String s : all) e.putBoolean("top_" + s, s.equals(topic));
        e.putBoolean("top_" + topic, true);
        e.putBoolean("top_default", false);
        e.apply();
    }

    // ---- valitud leht: Uudised või Novaator ----
    /** Kui Novaatori leht on valitud, mängitakse ja näidatakse ainult Novaatori lugusid (alateemade järgi). */
    public synchronized boolean novaatorPage() {
        return p.getBoolean("page_novaator", false);
    }

    public synchronized void setNovaatorPage(boolean on) {
        p.edit().putBoolean("page_novaator", on).apply();
    }

    /**
     * Kas artikkel sobib praeguste filtritega. Novaatori lehel: ainult Novaator + alateema sees
     * (Uudiste lehe rubriigivalik ei loe). Uudiste lehel: rubriik sees ja (Novaatoril) alateema sees.
     */
    public boolean allows(Article a) {
        String t = a.topic();
        if (novaatorPage()) {
            return "Novaator".equals(a.source()) && (t == null || isTopicEnabled(t));
        }
        if (!isEnabled(a.source())) return false;
        return t == null || isTopicEnabled(t);
    }

    // ---- juhuslik järjekord ----
    public synchronized boolean shuffle() {
        return p.getBoolean("shuffle", false);
    }

    public synchronized void setShuffle(boolean on) {
        p.edit().putBoolean("shuffle", on).apply();
    }

    public synchronized boolean filtersOpen() {
        return p.getBoolean("filters_open", false);
    }

    public synchronized void setFiltersOpen(boolean on) {
        p.edit().putBoolean("filters_open", on).apply();
    }

    // ---- kiirus ----
    public synchronized float speed() {
        return p.getFloat("speed", 1.0f);
    }

    public synchronized void setSpeed(float s) {
        p.edit().putFloat("speed", s).apply();
    }

    // ---- pooleli jäänud koht ----
    public synchronized long resumeId() {
        return p.getLong("resume_id", -1);
    }

    public synchronized int resumePos() {
        return p.getInt("resume_pos", 0);
    }

    public synchronized void setResume(long id, int pos) {
        p.edit().putLong("resume_id", id).putInt("resume_pos", pos).apply();
    }

    public synchronized void clearResume() {
        p.edit().remove("resume_id").remove("resume_pos").apply();
    }
}
