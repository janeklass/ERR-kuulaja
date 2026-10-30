package ee.janek.errkuulaja;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Põhiekraan (v1.8, "Ajaleht"): kompaktne päis, lehe tabid, nimekiri võtab kogu ülejäänud ruumi,
 * all kahe reaga mini-mängija. Filtrid, "Näita kuulatuid" ja "Unusta ajalugu" on eraldi dialoogis.
 */
public class MainActivity extends Activity {
    private static final float[] SPEEDS = {1.0f, 1.15f, 1.25f, 1.5f, 0.9f};
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private Store store;
    private Ui ui;

    // päis + tabid
    private ProgressBar spinner;
    private TextView filterBtn, footer, tabNews, tabNov;
    private View ulNews, ulNov;
    // nimekiri
    private ListView list;
    private Adapter adapter;
    private TextView emptyText;
    /** Mida tühja nimekirja teate vajutus teeb: 0 = midagi, 1 = näita kuulatuid, 2 = värskenda. */
    private int emptyAction = 0;
    // mini-mängija
    private ImageView nowImage;
    private TextView nowRubric, nowTitle, timeText, speedBtn;
    private ImageButton playBtn, shuffleBtn;
    private SeekBar seek;
    private boolean seeking;
    // filtrite dialoog (kui avatud)
    private LinearLayout filterRows;
    private TextView filterAll, filterHeard;

    private int shownVersion = -1;
    private long shownCurrentId = -2;
    private boolean showHeard = false;
    private boolean refreshing = false;
    private List<String> shownRubrics = new ArrayList<>();
    private boolean novaatorPage = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateNow();
            if (Feed.version() != shownVersion) rebuildList();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        store = Store.get(this);
        ui = new Ui(this, store.theme());
        setTheme(ui.night ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(b);
        novaatorPage = store.novaatorPage();
        PlayerService.installHttpCache(this);
        Feed.init(this);
        setContentView(buildUi());
        styleSystemBars();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        refresh(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(tick);
        if (System.currentTimeMillis() - Feed.lastFetch() > 5 * 60 * 1000L) refresh(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        ex.shutdownNow();
        super.onDestroy();
    }

    private void styleSystemBars() {
        getWindow().setStatusBarColor(ui.bg);
        getWindow().setNavigationBarColor(ui.surface);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(ui.night ? 0 : mask, mask);
            }
        } else {
            View d = getWindow().getDecorView();
            int f = d.getSystemUiVisibility();
            f = ui.night ? f & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
                    : f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            d.setSystemUiVisibility(f);
        }
    }

    // ---------- kasutajaliides ----------
    private int dp(int v) {
        return ui.dp(v);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);

        // Päis: ERR kuulaja | laadimisring | FILTRID n/N | ×
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(10), dp(18), 0);
        TextView title = ui.text(this, "ERR kuulaja", 24, ui.text, ui.serifBold);
        title.setIncludeFontPadding(false);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(ui.accent));
        spinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(20), dp(20));
        sl.setMargins(0, 0, dp(10), 0);
        header.addView(spinner, sl);
        filterBtn = capsBordered("Filtrid", v -> showFilters());
        header.addView(filterBtn, new LinearLayout.LayoutParams(WRAP, dp(34)));
        // Hele/tume: heledas kuu (→ tume), tumedas päike (→ hele); valik jääb meelde
        ImageButton themeBtn = squareButton(ui.night ? R.drawable.ic_sun : R.drawable.ic_moon, 34, false, v -> {
            store.setTheme(ui.night ? "light" : "dark");
            recreate();
        });
        themeBtn.setContentDescription(ui.night ? "Hele kujundus" : "Tume kujundus");
        LinearLayout.LayoutParams thl = new LinearLayout.LayoutParams(dp(34), dp(34));
        thl.setMargins(dp(8), 0, 0, 0);
        header.addView(themeBtn, thl);
        ImageButton closeBtn = squareButton(R.drawable.ic_close, 34, false, v -> quit());
        closeBtn.setContentDescription("Lõpeta ja sulge");
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(34), dp(34));
        cl.setMargins(dp(8), 0, 0, 0);
        header.addView(closeBtn, cl);
        root.addView(header);
        rule(root, 3, 8, 18);
        rule(root, 1, 2, 18);

        // Tabid: Uudised | Novaator ... "23 kuulamata · 21:30"
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.BOTTOM);
        tabRow.setPadding(dp(18), dp(4), dp(18), 0);
        tabNews = tab("Uudised", v -> setPage(false));
        tabNov = tab("Novaator", v -> setPage(true));
        ulNews = new View(this);
        ulNov = new View(this);
        tabRow.addView(tabBox(tabNews, ulNews));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(WRAP, WRAP);
        tl.setMargins(dp(18), 0, 0, 0);
        tabRow.addView(tabBox(tabNov, ulNov), tl);
        footer = ui.text(this, "", 11, ui.text2, ui.serifItalic);
        footer.setGravity(Gravity.END);
        footer.setMaxLines(1);
        footer.setEllipsize(android.text.TextUtils.TruncateAt.END);
        footer.setPadding(dp(12), 0, 0, dp(10));
        tabRow.addView(footer, new LinearLayout.LayoutParams(0, WRAP, 1f));
        styleTabs();
        root.addView(tabRow);
        rule(root, 1, 0, 18);

        // Tühi olek + artiklite nimekiri (võtab kogu ülejäänud ruumi)
        emptyText = ui.text(this, "Laen uudisvoogu…", 15, ui.text2, ui.serifItalic);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(dp(24), dp(32), dp(24), dp(32));
        emptyText.setLineSpacing(0, 1.2f);
        emptyText.setVisibility(Feed.snapshot().isEmpty() ? View.VISIBLE : View.GONE);
        emptyText.setOnClickListener(v -> {
            if (emptyAction == 1) toggleHeard();
            else if (emptyAction == 2) refresh(true);
        });
        root.addView(emptyText);

        list = new ListView(this);
        list.setDivider(new ColorDrawable(ui.outline));
        list.setDividerHeight(dp(1));
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(dp(18), 0, dp(18), dp(4));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            Article a = adapter.getItem(pos);
            startForegroundService(new Intent(this, PlayerService.class)
                    .setAction(PlayerService.A_PLAY_ID)
                    .putExtra(PlayerService.EXTRA_ID, a.id));
        });
        list.setOnItemLongClickListener((parent, view, pos, id) -> {
            Article a = adapter.getItem(pos);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(a.link)));
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // Mini-mängija all
        root.addView(buildPlayer());
        return root;
    }

    /** Kahe reaga mini-mängija: pilt | rubriik + pealkiri | Järgmine | Esita; kiirus | juhuslik | ajariba | aeg. */
    private View buildPlayer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(ui.surface);
        rule(box, 2, 0, 0);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        nowImage = ui.image(this, 48);
        nowImage.setVisibility(View.GONE);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(48), dp(48));
        il.setMargins(0, 0, dp(10), 0);
        top.addView(nowImage, il);

        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        nowRubric = ui.label(this, "", ui.accent);
        nowRubric.setTextSize(10);
        nowRubric.setMaxLines(1);
        nowRubric.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tcol.addView(nowRubric);
        nowTitle = ui.text(this, "Vajuta esitusnuppu, et alustada", 15, ui.text, ui.serifBold);
        nowTitle.setMaxLines(2);
        nowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowTitle.setLineSpacing(0, 1.02f);
        tcol.addView(nowTitle, lp(MATCH, dp(2), 0, 0));
        tcol.setClickable(true);
        tcol.setBackground(ui.rippleOnly(this, false));
        tcol.setOnClickListener(v -> scrollToCurrent());
        tcol.setOnLongClickListener(v -> {
            Article a = PlayerService.current;
            if (a == null) return false;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(a.link)));
            return true;
        });
        top.addView(tcol, new LinearLayout.LayoutParams(0, WRAP, 1f));

        ImageButton nextBtn = squareButton(R.drawable.ic_next, 44, false, v -> send(PlayerService.A_NEXT));
        nextBtn.setContentDescription("Järgmine");
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(dp(44), dp(44));
        nl.setMargins(dp(10), 0, 0, 0);
        top.addView(nextBtn, nl);
        playBtn = squareButton(R.drawable.ic_play, 44, true, v -> send(PlayerService.A_TOGGLE));
        playBtn.setContentDescription("Esita / paus");
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(52), dp(44));
        pl.setMargins(dp(8), 0, 0, 0);
        top.addView(playBtn, pl);
        inner.addView(top);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        speedBtn = capsBordered(speedLabel(), v -> cycleSpeed());
        speedBtn.setAllCaps(false);
        speedBtn.setLetterSpacing(0f);
        speedBtn.setTextSize(13);
        speedBtn.setMinWidth(dp(52));
        row2.addView(speedBtn, new LinearLayout.LayoutParams(WRAP, dp(32)));
        shuffleBtn = squareButton(R.drawable.ic_shuffle, 32, false, v -> toggleShuffle());
        LinearLayout.LayoutParams shl = new LinearLayout.LayoutParams(dp(32), dp(32));
        shl.setMargins(dp(6), 0, 0, 0);
        row2.addView(shuffleBtn, shl);
        styleShuffle();

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgressTintList(ColorStateList.valueOf(ui.accent));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(ui.outline));
        seek.setThumb(ui.square(ui.text, 12));
        seek.setThumbOffset(dp(6));
        seek.setSplitTrack(false);
        seek.setPadding(dp(8), 0, dp(8), 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int dur = PlayerService.duration();
                if (fromUser && dur > 0) timeText.setText(clock((int) ((long) p * dur / 1000)) + " / " + clock(dur));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                int dur = PlayerService.duration();
                if (dur > 0 && PlayerService.isRunning()) {
                    startForegroundService(new Intent(MainActivity.this, PlayerService.class)
                            .setAction(PlayerService.A_SEEK)
                            .putExtra(PlayerService.EXTRA_POS, (int) ((long) s.getProgress() * dur / 1000)));
                }
            }
        });
        LinearLayout.LayoutParams skl = new LinearLayout.LayoutParams(0, dp(32), 1f);
        skl.setMargins(dp(4), 0, 0, 0);
        row2.addView(seek, skl);
        timeText = ui.text(this, "", 11, ui.text2, ui.regular);
        timeText.setMinWidth(dp(66));
        timeText.setGravity(Gravity.END);
        row2.addView(timeText);
        inner.addView(row2, lp(MATCH, dp(6), 0, 0));
        box.addView(inner);
        return box;
    }

    /** Kerib nimekirja praegu mängiva artikli juurde. */
    private void scrollToCurrent() {
        Article a = PlayerService.current;
        if (a == null) return;
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i).id == a.id) {
                list.smoothScrollToPositionFromTop(i, dp(8));
                return;
            }
        }
        Toast.makeText(this, "Mängiv artikkel pole praeguses nimekirjas", Toast.LENGTH_SHORT).show();
    }

    // ---------- filtrite dialoog ----------
    private void showFilters() {
        final boolean nov = novaatorPage;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(16), dp(20), dp(8));

        TextView head = ui.text(this, nov ? "Novaatori alateemad" : "Rubriigid", 20, ui.text, ui.serifBold);
        body.addView(head);
        TextView hint = ui.text(this, nov ? "Vajutus = sisse/välja · pikk vajutus = ainult see alateema"
                : "Vajutus = sisse/välja · pikk vajutus = ainult see rubriik", 12, ui.text2, ui.serifItalic);
        hint.setPadding(0, dp(2), 0, 0);
        body.addView(hint);
        rule(body, 2, 10, 0);

        LinearLayout allRow = new LinearLayout(this);
        allRow.setOrientation(LinearLayout.HORIZONTAL);
        allRow.setGravity(Gravity.CENTER_VERTICAL);
        filterAll = capsBordered("", v -> {
            boolean on = allOn(nov);
            if (nov) store.setTopics(shownRubrics, !on); else store.setAll(shownRubrics, !on);
            rebuildFilterRows(nov);
            rebuildList();
        });
        allRow.addView(filterAll, new LinearLayout.LayoutParams(WRAP, dp(34)));
        body.addView(allRow, lp(MATCH, dp(10), 0, 0));

        filterRows = new LinearLayout(this);
        filterRows.setOrientation(LinearLayout.VERTICAL);
        body.addView(filterRows, lp(MATCH, dp(6), 0, 0));
        rebuildFilterRows(nov);

        rule(body, 1, 8, 0);
        filterHeard = checkRow("Näita ka kuulatud artikleid", showHeard, ui.text, v -> {
            toggleHeard();
            setChecked(filterHeard, showHeard, ui.text);
        });
        body.addView(filterHeard);
        TextView forget = ui.capsButton(this, "Unusta kuulamise ajalugu", ui.accent, v -> confirmClear());
        forget.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        forget.setPadding(dp(2), dp(10), dp(8), dp(10));
        body.addView(forget);

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        AlertDialog d = new AlertDialog.Builder(this, ui.night
                ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setView(sv)
                .setPositiveButton("Valmis", null)
                .setOnDismissListener(x -> { filterRows = null; filterAll = null; filterHeard = null; })
                .create();
        d.show();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(ui.bordered(ui.surface, ui.text, 2f));
        android.widget.Button ok = d.getButton(AlertDialog.BUTTON_POSITIVE);
        if (ok != null) {
            ok.setTextColor(ui.accent);
            ok.setTypeface(ui.medium);
        }
    }

    private boolean allOn(boolean nov) {
        for (String k : shownRubrics) if (!(nov ? store.isTopicEnabled(k) : store.isEnabled(k))) return false;
        return true;
    }

    private void rebuildFilterRows(boolean nov) {
        if (filterRows == null) return;
        shownRubrics = rubrics();
        filterRows.removeAllViews();
        filterAll.setText(allOn(nov) ? "Kõik maha" : "Kõik sisse");
        int novColor = ui.rubricColor("Novaator");
        for (String key : shownRubrics) {
            boolean on = nov ? store.isTopicEnabled(key) : store.isEnabled(key);
            int color = nov ? novColor : ui.rubricColor(key);
            TextView row = checkRow(key, on, color, v -> {
                if (nov) store.setTopicEnabled(key, !store.isTopicEnabled(key));
                else store.setEnabled(key, !store.isEnabled(key));
                rebuildFilterRows(nov);
                rebuildList();
            });
            row.setOnLongClickListener(v -> {
                if (nov) store.setOnlyTopic(key, shownRubrics); else store.setOnly(key, shownRubrics);
                rebuildFilterRows(nov);
                rebuildList();
                Toast.makeText(this, "Kuulan ainult: " + key, Toast.LENGTH_SHORT).show();
                return true;
            });
            filterRows.addView(row);
        }
    }

    /** Rida ruudukujulise märkekastiga (vasakul) ja serif-sildiga. */
    private TextView checkRow(String label, boolean on, int color, View.OnClickListener l) {
        TextView t = ui.text(this, label, 16, ui.text, ui.serif);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(2), dp(9), dp(8), dp(9));
        t.setCompoundDrawablePadding(dp(14));
        t.setBackground(ui.rippleOnly(this, false));
        t.setClickable(true);
        t.setOnClickListener(l);
        setChecked(t, on, color);
        return t;
    }

    private void setChecked(TextView t, boolean on, int color) {
        android.graphics.drawable.Drawable box;
        if (on) {
            android.graphics.drawable.GradientDrawable g = ui.bordered(color, color, 1.5f);
            g.setSize(dp(22), dp(22));
            android.graphics.drawable.Drawable check = getDrawable(R.drawable.ic_check);
            check.setTint(ui.onAccent);
            android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{g, check});
            ld.setLayerInset(1, dp(3), dp(3), dp(3), dp(3));
            box = ld;
        } else {
            android.graphics.drawable.GradientDrawable g = ui.bordered(0, ui.text, 1.5f);
            g.setSize(dp(22), dp(22));
            box = g;
        }
        box.setBounds(0, 0, dp(22), dp(22));
        t.setCompoundDrawables(box, null, null, null);
        t.setTextColor(on ? ui.text : ui.text2);
        t.setTypeface(on ? ui.serifBold : ui.serif);
    }

    private void toggleHeard() {
        showHeard = !showHeard;
        rebuildList();
    }

    // ---------- tabid ja abid ----------
    private TextView tab(String label, View.OnClickListener l) {
        TextView t = ui.text(this, label, 16, ui.text2, ui.serif);
        t.setPadding(0, dp(6), 0, dp(7));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    /** Tab koos allajoonega (3 dp tinti valitud lehel). */
    private LinearLayout tabBox(TextView t, View underline) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(WRAP, WRAP));
        // NB: vertikaalses LinearLayoutis on vaikimisi laius MATCH_PARENT – anna laius ise, muidu kast venib üle rea
        box.addView(t, new LinearLayout.LayoutParams(WRAP, WRAP));
        box.addView(underline, new LinearLayout.LayoutParams(MATCH, dp(3)));
        return box;
    }

    /** Horisontaalne tindijoon (ajalehe reegel) külgvarudega. */
    private void rule(LinearLayout parent, int heightDp, int topDp, int sideDp) {
        View v = new View(this);
        v.setBackgroundColor(ui.text);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, dp(heightDp));
        p.setMargins(dp(sideDp), dp(topDp), dp(sideDp), 0);
        parent.addView(v, p);
    }

    private void styleTabs() {
        tabNews.setTypeface(novaatorPage ? ui.serif : ui.serifBold);
        tabNews.setTextColor(novaatorPage ? ui.text2 : ui.text);
        ulNews.setBackgroundColor(novaatorPage ? 0 : ui.text);
        tabNov.setTypeface(novaatorPage ? ui.serifBold : ui.serif);
        tabNov.setTextColor(novaatorPage ? ui.text : ui.text2);
        ulNov.setBackgroundColor(novaatorPage ? ui.text : 0);
    }

    private void setPage(boolean nov) {
        if (novaatorPage == nov) return;
        novaatorPage = nov;
        store.setNovaatorPage(nov);   // mängija järjekord järgib sama lehte
        styleTabs();
        rebuildList();
        list.setSelection(0);
        if (PlayerService.current != null && !store.allows(PlayerService.current)) {
            Toast.makeText(this, nov ? "Järgmisena mängivad Novaatori lood" : "Järgmisena mängivad valitud rubriigid",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Lõpetab mängimise, eemaldab teavituse ja sulgeb äpi. */
    private void quit() {
        if (PlayerService.isRunning()) send(PlayerService.A_STOP);
        h.removeCallbacks(tick);
        finishAndRemoveTask();
    }

    /** Ruudukujuline ikooninupp: täidetud = tint paberivärvi ikooniga, muidu tindiraam. */
    private ImageButton squareButton(int icon, int sizeDp, boolean filled, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(filled ? ui.onAccent : ui.text));
        b.setBackground(ui.ripple(filled ? ui.rounded(ui.text, 0) : ui.bordered(0, ui.text, 1.5f),
                filled ? ui.onAccent : ui.text));
        b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = dp(sizeDp / 4);
        b.setPadding(pad, pad, pad, pad);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        b.setOnClickListener(l);
        return b;
    }

    /** Kapiteelis tekst tindiraamis (FILTRID, kiirus, Kõik). */
    private TextView capsBordered(String label, View.OnClickListener l) {
        TextView t = ui.text(this, label, 12, ui.text, ui.medium);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), 0, dp(10), 0);
        t.setBackground(ui.ripple(ui.bordered(0, ui.text, 1.5f), ui.text));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int top, int left, int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, WRAP);
        p.setMargins(left, top, right, 0);
        return p;
    }

    private void send(String action) {
        startForegroundService(new Intent(this, PlayerService.class).setAction(action));
    }

    private String speedLabel() {
        String s = String.format(Locale.US, "%.2f", store.speed());
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.replace(".", ",") + "×";
    }

    private void cycleSpeed() {
        float cur = store.speed();
        int idx = 0;
        for (int i = 0; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i] - cur) < 0.01f) idx = i;
        store.setSpeed(SPEEDS[(idx + 1) % SPEEDS.length]);
        speedBtn.setText(speedLabel());
        if (PlayerService.isRunning()) send(PlayerService.A_SPEED);
    }

    private void toggleShuffle() {
        boolean on = !store.shuffle();
        store.setShuffle(on);
        styleShuffle();
        Toast.makeText(this, on ? "Juhuslik järjekord sees" : "Uuemad enne", Toast.LENGTH_SHORT).show();
    }

    private void styleShuffle() {
        boolean on = store.shuffle();
        shuffleBtn.setImageTintList(ColorStateList.valueOf(on ? ui.onAccent : ui.text));
        shuffleBtn.setBackground(ui.ripple(on ? ui.rounded(ui.accent, 0) : ui.bordered(0, ui.text, 1.5f),
                on ? ui.onAccent : ui.text));
        shuffleBtn.setContentDescription(on ? "Juhuslik järjekord sees" : "Juhuslik järjekord väljas");
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Unusta ajalugu?")
                .setMessage("Kõik artiklid loetakse uuesti kuulamata.")
                .setPositiveButton("Jah", (d, w) -> {
                    store.clearHistory();
                    rebuildList();
                })
                .setNegativeButton("Ei", null)
                .show();
    }

    private void refresh(boolean userAsked) {
        if (refreshing) return;
        refreshing = true;
        footer.setText("Laen uudisvoogu…");
        spinner.setVisibility(View.VISIBLE);
        if (adapter.getCount() == 0) {
            emptyText.setText("Laen uudisvoogu…");
            emptyText.setVisibility(View.VISIBLE);
            emptyAction = 0;
        }
        ex.execute(() -> {
            String err = null;
            try {
                Feed.refresh();
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fErr = err;
            h.post(() -> {
                refreshing = false;
                spinner.setVisibility(View.GONE);
                if (fErr != null) {
                    footer.setText("Viga: " + fErr);
                    if (adapter.getCount() == 0) {
                        emptyText.setText("Uudisvoogu ei saanud laadida.\nKontrolli internetiühendust ja vajuta siia, et proovida uuesti.");
                        emptyText.setVisibility(View.VISIBLE);
                        emptyAction = 2;
                    }
                    if (userAsked) Toast.makeText(this, "Uudisvoogu ei saanud laadida", Toast.LENGTH_SHORT).show();
                } else {
                    rebuildList();
                }
            });
        });
    }

    private static String clock(int ms) {
        return DateUtils.formatElapsedTime(Math.max(0, ms) / 1000);
    }

    private void updateNow() {
        Article a = PlayerService.current;
        boolean playing = PlayerService.playing;
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        String st = PlayerService.isRunning() ? PlayerService.status : "";
        if (st == null) st = "";
        int pos = PlayerService.position(), dur = PlayerService.duration();
        if (a != null) {
            nowRubric.setText(a.sourceLabel() + " · " + (st.isEmpty() ? (playing ? "mängib" : "paus") : st));
            nowRubric.setTextColor(ui.rubricColor(a.source()));
            nowTitle.setText(a.title);
            if (a.id != shownCurrentId) {
                nowImage.setVisibility(a.image != null ? View.VISIBLE : View.GONE);
                Images.load(nowImage, a.image, dp(48), null);
            }
        } else {
            String order = store.shuffle() ? "juhuslikus järjekorras" : "uuemad enne";
            nowRubric.setText(st.isEmpty() ? (store.novaatorPage() ? "Novaator · " : "Kuulamata artiklid · ") + order : st);
            nowRubric.setTextColor(ui.text2);
            nowTitle.setText(PlayerService.isRunning() ? "ERR kuulaja" : "Vajuta esitusnuppu, et alustada");
            nowImage.setVisibility(View.GONE);
        }
        if (dur > 0) {
            if (!seeking) {
                seek.setProgress((int) ((long) pos * 1000 / dur));
                timeText.setText(clock(pos) + " / " + clock(dur));
            }
            seek.setEnabled(true);
        } else {
            seek.setProgress(0);
            seek.setEnabled(false);
            timeText.setText("");
        }
        long cid = a != null ? a.id : -1;
        if (cid != shownCurrentId) {
            shownCurrentId = cid;
            adapter.notifyDataSetChanged();
        }
    }

    // ---------- rubriigid ----------
    /** Kõik püsirubriigid (Store.ORDER) + voost tulnud tundmatud, tähestikus lõpus. */
    private List<String> rubrics() {
        java.util.TreeSet<String> extra = new java.util.TreeSet<>(java.text.Collator.getInstance(new Locale("et")));
        if (novaatorPage) {
            for (Article a : Feed.snapshot()) {
                String t = a.topic();
                if (t != null) extra.add(t);
            }
            return new ArrayList<>(extra);
        }
        for (Article a : Feed.snapshot()) extra.add(a.source());
        List<String> out = new ArrayList<>();
        for (String k : Store.ORDER) {
            out.add(k);
            extra.remove(k);
        }
        out.addAll(extra);
        return out;
    }

    private void rebuildList() {
        shownVersion = Feed.version();
        shownRubrics = rubrics();
        List<Article> out = new ArrayList<>();
        int unheard = 0;
        for (Article a : Feed.snapshot()) {
            if (!store.allows(a)) continue;   // sama reegel, mida kasutab mängija järjekord
            boolean heard = store.isHeard(a.id);
            if (!heard && !store.isNoAudio(a.id)) unheard++;
            if (heard && !showHeard) continue;
            out.add(a);
        }
        adapter.set(out);
        boolean empty = out.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            if (Feed.snapshot().isEmpty()) {
                emptyText.setText(refreshing ? "Laen uudisvoogu…" : "Artikleid ei leitud.\nVajuta siia, et proovida uuesti.");
                emptyAction = refreshing ? 0 : 2;
            } else if (!showHeard && store.heardCount() > 0) {
                emptyText.setText("Valitud rubriikides pole kuulamata artikleid.\nVajuta siia, et näha kuulatuid.");
                emptyAction = 1;
            } else {
                emptyText.setText("Valitud rubriikides pole artikleid.\nAva \"Filtrid\" ja lülita mõni rubriik sisse.");
                emptyAction = 0;
            }
        }
        // FILTRID n/N, kui mõni on väljas
        int on = 0;
        for (String k : shownRubrics) if (novaatorPage ? store.isTopicEnabled(k) : store.isEnabled(k)) on++;
        boolean all = shownRubrics.isEmpty() || on == shownRubrics.size();
        filterBtn.setText(all ? "Filtrid" : "Filtrid " + on + "/" + shownRubrics.size());
        filterBtn.setTextColor(all ? ui.text : ui.accent);
        filterBtn.setBackground(ui.ripple(ui.bordered(0, all ? ui.text : ui.accent, 1.5f), ui.text));
        // Tabirea parem serv
        long lf = Feed.lastFetch();
        footer.setText(unheard + " kuulamata · " + store.heardCount() + " kuulatud"
                + (showHeard ? " (näidatakse)" : "")
                + (lf > 0 ? " · " + DateUtils.formatDateTime(this, lf,
                DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_24HOUR) : ""));
    }

    // ---------- nimekiri ----------
    private class Adapter extends BaseAdapter {
        private List<Article> items = new ArrayList<>();

        void set(List<Article> l) {
            items = l;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Article getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).id; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row;
            TextView rub, when, t1, check;
            ImageView img;
            if (convert instanceof LinearLayout) {
                row = (LinearLayout) convert;
                img = (ImageView) row.getChildAt(0);
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                LinearLayout meta = (LinearLayout) col.getChildAt(0);
                rub = (TextView) meta.getChildAt(0);
                when = (TextView) meta.getChildAt(1);
                t1 = (TextView) col.getChildAt(1);
                check = (TextView) row.getChildAt(2);
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.TOP);
                row.setPadding(0, dp(11), 0, dp(11));
                row.setBackground(ui.rippleOnly(MainActivity.this, false));
                img = ui.image(MainActivity.this, 56);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(56), dp(56));
                ip.setMargins(0, 0, dp(12), 0);
                row.addView(img, ip);
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout meta = new LinearLayout(MainActivity.this);
                meta.setOrientation(LinearLayout.HORIZONTAL);
                meta.setGravity(Gravity.CENTER_VERTICAL);
                rub = ui.label(MainActivity.this, "", ui.text2);
                meta.addView(rub, new LinearLayout.LayoutParams(0, WRAP, 1f));
                when = ui.text(MainActivity.this, "", 11, ui.text2, ui.medium);
                meta.addView(when);
                col.addView(meta);
                t1 = ui.text(MainActivity.this, "", 16, ui.text, ui.serif);
                t1.setLineSpacing(0, 1.05f);
                t1.setPadding(0, dp(3), 0, 0);
                col.addView(t1);
                row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
                check = ui.text(MainActivity.this, "✓", 16, ui.text2, ui.medium);
                check.setPadding(dp(8), dp(14), 0, 0);
                row.addView(check);
            }
            Article a = items.get(i);
            boolean heard = store.isHeard(a.id);
            boolean noAudio = store.isNoAudio(a.id);
            Article cur = PlayerService.current;
            boolean isCurrent = cur != null && cur.id == a.id;
            boolean dim = heard || noAudio;

            img.setVisibility(a.image != null ? View.VISIBLE : View.GONE);
            Images.load(img, a.image, dp(56), null);
            img.setAlpha(dim ? 0.5f : 1f);

            StringBuilder r = new StringBuilder(a.sourceLabel());
            if (a.topic() != null) r.append(" · ").append(a.topic());
            if (isCurrent) r.append(" · mängib");
            else if (heard) r.append(" · kuulatud");
            else if (noAudio) r.append(" · heli puudub");
            rub.setText(r);
            rub.setTextColor(dim && !isCurrent ? ui.text2 : ui.rubricColor(a.source()));

            String w = "";
            if (a.pubMillis > 0) {
                boolean today = DateUtils.isToday(a.pubMillis);
                w = DateUtils.formatDateTime(MainActivity.this, a.pubMillis, today
                        ? DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_24HOUR
                        : DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR);
            }
            when.setText(w);

            t1.setText(a.title);
            t1.setTypeface(isCurrent ? ui.serifBold : ui.serif);
            t1.setTextColor(isCurrent ? ui.accent : dim ? ui.text2 : ui.text);
            check.setVisibility(heard ? View.VISIBLE : View.GONE);
            return row;
        }
    }
}
