package ee.janek.errkuulaja;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lihtne pildilaadija: mälus LruCache, kettal HttpResponseCache (paigaldatakse MainActivity's). */
final class Images {
    private static final LruCache<String, Bitmap> mem =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
            };
    private static final ExecutorService pool = Executors.newFixedThreadPool(3);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Set<String> failed = Collections.synchronizedSet(new HashSet<>());

    private Images() { }

    /** Näitab pilti, kui see on olemas; vahepeal kohatäidet. Vaate taaskasutus on tag'iga kaitstud. */
    static void load(ImageView iv, String url, int sizePx, Drawable placeholder) {
        iv.setTag(url);
        if (url == null || url.isEmpty() || failed.contains(url)) {
            iv.setImageDrawable(placeholder);
            return;
        }
        Bitmap b = mem.get(url);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageDrawable(placeholder);
        pool.execute(() -> {
            Bitmap bm = fetch(url, sizePx);
            if (bm == null) {
                failed.add(url);
                return;
            }
            mem.put(url, bm);
            main.post(() -> {
                if (url.equals(iv.getTag())) iv.setImageBitmap(bm);
            });
        });
    }

    private static Bitmap fetch(String url, int sizePx) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
            try {
                if (c.getResponseCode() != 200) return null;
                byte[] data;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                    data = bo.toByteArray();
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sample = 1;
                while (sizePx > 0 && o.outWidth / (sample * 2) >= sizePx && o.outHeight / (sample * 2) >= sizePx) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
