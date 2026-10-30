package ee.janek.errkuulaja;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Laeb ERR-i RSS-vood ja leiab artikli "Kuula artiklit" helifaili. */
public final class Feed {
    /**
     * Vood: {URL, rubriik}. Rubriik omistatakse www.err.ee artiklile, kui RSS-i kategooria
     * ise rubriiki ei ütle (vt Article.rubricFor). Üldvoog on esimene, et rubriigivood
     * saaksid sama artikli täpsema rubriigiga üle kirjutada.
     */
    private static final String[][] FEEDS = {
            {"https://www.err.ee/rss", null},
            {"https://www.err.ee/rss/eesti", "Eesti"},
            {"https://www.err.ee/rss/valismaa", "Välismaa"},
            {"https://www.err.ee/rss/majandus", "Majandus"},
            {"https://www.err.ee/rss/arvamus", "Arvamus"},
            {"https://sport.err.ee/rss", "Sport"},
            {"https://kultuur.err.ee/rss", "Kultuur"},
            {"https://novaator.err.ee/rss", "Novaator"},
    };
    private static final int MAX_ITEMS = 600;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36";

    // Artikli lehel: {"neuroSpeechUrl":"https:\/\/kone.err.ee\/meelis_1610180251_820825.mp3", ...}
    private static final Pattern AUDIO = Pattern.compile("kone\\.err\\.ee\\\\?/([A-Za-z0-9_\\-]+\\.mp3)");
    private static final Pattern ID = Pattern.compile("err\\.ee/(\\d{6,})");

    private static final List<Article> items = new ArrayList<>();
    private static long lastFetch = 0;
    private static volatile int version = 0;
    private static final ExecutorService pool = Executors.newFixedThreadPool(4);
    private static File cacheFile;

    private Feed() { }

    /**
     * Laeb viimati salvestatud voo kettalt, et nimekiri oleks avamisel kohe olemas
     * (värske voog laetakse ikka võrgust peale). Kutsu Activity/Service onCreate-s.
     */
    public static synchronized void init(android.content.Context c) {
        if (cacheFile != null) return;
        cacheFile = new File(c.getCacheDir(), "feed.tsv");
        if (!items.isEmpty() || !cacheFile.exists()) return;
        List<Article> list = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(cacheFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length < 7) continue;
                list.add(new Article(Long.parseLong(f[0]), f[2], f[3], Long.parseLong(f[1]),
                        nz(f[4]), nz(f[5]), nz(f[6])));
            }
        } catch (Exception e) {
            return;
        }
        if (!list.isEmpty()) {
            items.addAll(list);
            version++;
        }
    }

    private static String nz(String s) {
        return s.isEmpty() ? null : s;
    }

    private static String tsv(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Salvestab praeguse nimekirja kettale (taustal). */
    private static void save(List<Article> list) {
        File f = cacheFile;
        if (f == null) return;
        File tmp = new File(f.getPath() + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            for (Article a : list) {
                w.write(a.id + "\t" + a.pubMillis + "\t" + tsv(a.title) + "\t" + tsv(a.link) + "\t"
                        + tsv(a.category) + "\t" + tsv(a.rubric) + "\t" + tsv(a.image) + "\n");
            }
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    public static synchronized List<Article> snapshot() {
        return new ArrayList<>(items);
    }

    public static int version() {
        return version;
    }

    public static synchronized long lastFetch() {
        return lastFetch;
    }

    public static synchronized Article find(long id) {
        for (Article a : items) if (a.id == id) return a;
        return null;
    }

    /** Värskendab, kui viimasest laadimisest on möödas üle maxAgeMs. */
    public static void refreshIfStale(long maxAgeMs) throws IOException {
        if (System.currentTimeMillis() - lastFetch() > maxAgeMs) refresh();
    }

    /** Laeb kõik vood paralleelselt; õnnestub, kui vähemalt üks voog tuli. */
    public static void refresh() throws IOException {
        List<Future<Map<Long, Article>>> futures = new ArrayList<>();
        for (String[] f : FEEDS) {
            final String url = f[0], rubric = f[1];
            futures.add(pool.submit(() -> {
                Map<Long, Article> out = new LinkedHashMap<>();
                parse(get(url), rubric, out);
                return out;
            }));
        }
        Map<Long, Article> fresh = new HashMap<>();
        IOException last = null;
        boolean any = false;
        for (Future<Map<Long, Article>> fu : futures) {
            try {
                fresh.putAll(fu.get());
                any = true;
            } catch (Exception e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                last = c instanceof IOException ? (IOException) c : new IOException(c);
            }
        }
        if (!any) throw last != null ? last : new IOException("Uudisvoogu ei saanud laadida");

        synchronized (Feed.class) {
            for (Article old : items) {
                if (!fresh.containsKey(old.id)) fresh.put(old.id, old);
            }
            List<Article> list = new ArrayList<>(fresh.values());
            // Värskemad ees
            Collections.sort(list, (a, b) -> {
                int c = Long.compare(b.pubMillis, a.pubMillis);
                return c != 0 ? c : Long.compare(b.id, a.id);
            });
            if (list.size() > MAX_ITEMS) list = new ArrayList<>(list.subList(0, MAX_ITEMS));
            items.clear();
            items.addAll(list);
            lastFetch = System.currentTimeMillis();
            version++;
            final List<Article> toSave = new ArrayList<>(list);
            pool.execute(() -> save(toSave));
        }
    }

    /** Tagastab mp3 aadressi või null, kui artiklil pole kuulamisvõimalust. */
    public static String resolveAudio(Store store, Article a) throws IOException {
        String cached = store.audioUrl(a.id);
        if (cached != null) return cached;
        String html = get(a.link);
        Matcher m = AUDIO.matcher(html);
        if (!m.find()) return null;
        String url = "https://kone.err.ee/" + m.group(1);
        store.setAudioUrl(a.id, url);
        return url;
    }

    private static void parse(String xml, String feedRubric, Map<Long, Article> out) throws Exception {
        XmlPullParser x = Xml.newPullParser();
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        x.setInput(new StringReader(xml));
        SimpleDateFormat df = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

        boolean inItem = false;
        String title = null, link = null, pub = null, cat = null, img = null;
        int ev = x.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String n = x.getName();
                if ("item".equals(n)) {
                    inItem = true;
                    title = link = pub = cat = img = null;
                } else if (inItem) {
                    if ("title".equals(n)) title = text(x);
                    else if ("link".equals(n)) link = text(x);
                    else if ("pubDate".equals(n)) pub = text(x);
                    else if ("category".equals(n) && cat == null) cat = text(x);
                    else if (("media:thumbnail".equals(n) || "enclosure".equals(n)) && img == null) {
                        img = x.getAttributeValue(null, "url");
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && "item".equals(x.getName())) {
                inItem = false;
                if (link != null && title != null) {
                    Matcher m = ID.matcher(link);
                    if (m.find()) {
                        long id = Long.parseLong(m.group(1));
                        long t = 0;
                        if (pub != null) {
                            try {
                                Date d = df.parse(pub.trim());
                                if (d != null) t = d.getTime();
                            } catch (Exception ignored) { }
                        }
                        out.put(id, new Article(id, title.trim(), link.trim(), t, cat, feedRubric, img));
                    }
                }
            }
            ev = x.next();
        }
    }

    private static String text(XmlPullParser x) throws Exception {
        StringBuilder sb = new StringBuilder();
        int ev = x.next();
        while (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT || ev == XmlPullParser.ENTITY_REF) {
            sb.append(x.getText());
            ev = x.next();
        }
        return sb.toString();
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
