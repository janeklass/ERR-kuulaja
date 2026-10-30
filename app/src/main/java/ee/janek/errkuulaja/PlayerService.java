package ee.janek.errkuulaja;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.net.http.HttpResponseCache;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Taustal mängiv teenus: valib järgmise kuulamata artikli (värskemad ees),
 * mängib selle heli ja liigub lõpus automaatselt edasi. Töötab ka lukus ekraaniga.
 */
public class PlayerService extends Service implements
        MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener {

    public static final String A_TOGGLE = "ee.janek.errkuulaja.TOGGLE";
    public static final String A_NEXT = "ee.janek.errkuulaja.NEXT";
    public static final String A_STOP = "ee.janek.errkuulaja.STOP";
    public static final String A_PLAY_ID = "ee.janek.errkuulaja.PLAY_ID";
    public static final String A_SPEED = "ee.janek.errkuulaja.SPEED";
    public static final String A_SEEK = "ee.janek.errkuulaja.SEEK";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_POS = "pos";

    private static final String CHANNEL = "playback";
    private static final int NID = 1;
    private static final long REFRESH_AFTER_FINISH = 3 * 60 * 1000L;
    private static final long REFRESH_NORMAL = 10 * 60 * 1000L;
    private static final long RETRY_EMPTY = 5 * 60 * 1000L;

    // Olek kasutajaliidesele (loetakse põhilõimes)
    static volatile Article current;
    static volatile boolean playing;
    static volatile String status = "";
    private static PlayerService inst;

    private MediaPlayer mp;
    private boolean prepared;
    private int pendingSeek;
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focusRequest;
    private boolean resumeOnFocus;
    private WifiManager.WifiLock wifiLock;
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private Handler main;
    private Store store;
    private final Set<Long> sessionSkip = Collections.synchronizedSet(new HashSet<>());
    private int loadToken;
    private boolean noisyRegistered;
    private final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) pause();
        }
    };

    /**
     * Teine, sõltumatu "klapid kadusid" kontroll: AUDIO_BECOMING_NOISY ei tule kõigil telefonidel,
     * kui Bluetooth-klapid tühjaks saavad või välja lülitatakse. Kui mängimise ajal kaob heliseadmete
     * nimekirjast mõni kõrvaklapi-tüüpi seade, läheb mängija pausile.
     */
    private final AudioDeviceCallback deviceCb = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            if (!playing || removed == null) return;
            for (AudioDeviceInfo d : removed) {
                if (d.isSink() && isHeadphone(d.getType())) {
                    pause();
                    setStatus("Paus – kõrvaklapid lahti");
                    return;
                }
            }
        }
    };

    private static boolean isHeadphone(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return true;
            default:
                return Build.VERSION.SDK_INT >= 31
                        && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER);
        }
    }

    private final Runnable retryEmpty = () -> {
        if (current == null && !playing) playNext(true);
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (playing) {
                saveResume();
                updateSessionState();
            }
            main.postDelayed(this, 10000);
        }
    };

    /** Ketta vahemälu HTTP-päringutele (pildid, artiklilehed). Ohutu korduvalt kutsuda. */
    static void installHttpCache(Context c) {
        try {
            if (HttpResponseCache.getInstalled() == null) {
                HttpResponseCache.install(new java.io.File(c.getCacheDir(), "http"), 64L * 1024 * 1024);
            }
        } catch (Exception ignored) { }
    }

    // ---------- avalik abi UI jaoks ----------
    static boolean isRunning() {
        return inst != null;
    }

    static int position() {
        PlayerService s = inst;
        if (s == null || s.mp == null || !s.prepared) return 0;
        try { return s.mp.getCurrentPosition(); } catch (IllegalStateException e) { return 0; }
    }

    static int duration() {
        PlayerService s = inst;
        if (s == null || s.mp == null || !s.prepared) return 0;
        try { return s.mp.getDuration(); } catch (IllegalStateException e) { return 0; }
    }

    // ---------- elutsükkel ----------
    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        installHttpCache(this);
        Feed.init(this);
        main = new Handler(Looper.getMainLooper());
        store = Store.get(this);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(this)
                .setWillPauseWhenDucked(false)
                .build();
        am.registerAudioDeviceCallback(deviceCb, main);

        NotificationChannel ch = new NotificationChannel(CHANNEL, "Kuulamine", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);

        session = new MediaSession(this, "ERRkuulaja");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onStop() { stopAll(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
        });
        session.setSessionActivity(openAppIntent());
        session.setActive(true);

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ERRkuulaja");
        wifiLock.setReferenceCounted(false);

        main.postDelayed(ticker, 10000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startFg();
        String a = intent != null && intent.getAction() != null ? intent.getAction() : A_TOGGLE;
        switch (a) {
            case A_PLAY_ID:
                playId(intent.getLongExtra(EXTRA_ID, -1));
                break;
            case A_NEXT:
                next();
                break;
            case A_STOP:
                stopAll();
                break;
            case A_SPEED:
                applySpeed();
                break;
            case A_SEEK:
                seekTo(intent.getIntExtra(EXTRA_POS, 0));
                break;
            case A_TOGGLE:
            default:
                if (mp != null && prepared) {
                    if (mp.isPlaying()) pause(); else play();
                } else if (mp == null) {
                    playNext(false);
                }
                break;
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        saveResume();
        releasePlayer();
        abandonFocus();
        unregisterNoisy();
        am.unregisterAudioDeviceCallback(deviceCb);
        main.removeCallbacksAndMessages(null);
        ex.shutdownNow();
        session.setActive(false);
        session.release();
        current = null;
        playing = false;
        status = "";
        inst = null;
        super.onDestroy();
    }

    // ---------- järjekord ----------
    private void playNext(boolean afterFinish) {
        main.removeCallbacks(retryEmpty);
        final int token = ++loadToken;
        current = null;
        setStatus("Otsin järgmist artiklit…");
        ex.execute(() -> {
            try {
                Feed.refreshIfStale(afterFinish ? REFRESH_AFTER_FINISH : REFRESH_NORMAL);
            } catch (Exception e) {
                if (Feed.snapshot().isEmpty()) {
                    main.post(() -> {
                        if (token != loadToken) return;
                        setStatus("Uudisvoogu ei saanud laadida. Proovin uuesti…");
                        main.postDelayed(retryEmpty, 30000);
                    });
                    return;
                }
            }

            List<Article> all = Feed.snapshot();
            long resumeId = store.resumeId();
            List<Article> cands = new ArrayList<>();
            for (Article a : all) {
                if (a.id == resumeId && !store.isHeard(a.id) && store.allows(a)) cands.add(a);
            }
            for (Article a : all) {
                if (a.id != resumeId && store.allows(a) && !store.isHeard(a.id)
                        && !store.isNoAudio(a.id) && !sessionSkip.contains(a.id)) {
                    cands.add(a);
                }
            }

            // Juhuslik režiim: pooleli jäänud artikkel jääb esimeseks, ülejäänud segatakse
            if (store.shuffle()) {
                int from = (!cands.isEmpty() && cands.get(0).id == resumeId) ? 1 : 0;
                Collections.shuffle(cands.subList(from, cands.size()));
            }

            Article pick = null;
            String url = null;
            for (Article a : cands) {
                if (token != loadToken) return;
                try {
                    String u = Feed.resolveAudio(store, a);
                    if (u != null) { pick = a; url = u; break; }
                    store.markNoAudio(a.id);
                } catch (Exception e) {
                    sessionSkip.add(a.id);
                }
            }

            final Article fPick = pick;
            final String fUrl = url;
            final int seek = (pick != null && pick.id == resumeId) ? store.resumePos() : 0;
            main.post(() -> {
                if (token != loadToken) return;
                if (fPick == null) {
                    setStatus("Kõik on kuulatud. Ootan uusi artikleid…");
                    main.postDelayed(retryEmpty, RETRY_EMPTY);
                    return;
                }
                startPlayback(fPick, fUrl, seek);
            });
        });
    }

    private void playId(long id) {
        main.removeCallbacks(retryEmpty);
        final Article a = Feed.find(id);
        if (a == null) return;
        saveResume();
        final int token = ++loadToken;
        releasePlayer();
        current = a;
        setStatus("Laen…");
        ex.execute(() -> {
            String url = null;
            try { url = Feed.resolveAudio(store, a); } catch (Exception ignored) { }
            final String fUrl = url;
            final int seek = a.id == store.resumeId() ? store.resumePos() : 0;
            main.post(() -> {
                if (token != loadToken) return;
                if (fUrl == null) {
                    store.markNoAudio(a.id);
                    setStatus("Sellel artiklil pole kuulamisvõimalust");
                    playNext(false);
                    return;
                }
                startPlayback(a, fUrl, seek);
            });
        });
    }

    private void startPlayback(Article a, String url, int seekTo) {
        releasePlayer();
        current = a;
        pendingSeek = seekTo;
        mp = new MediaPlayer();
        mp.setAudioAttributes(attrs);
        mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(this);
        mp.setOnCompletionListener(this);
        mp.setOnErrorListener(this);
        if (!wifiLock.isHeld()) wifiLock.acquire();
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, a.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "ERR · " + a.sourceLabel())
                .build());
        setStatus("Laen…");
        try {
            mp.setDataSource(url);
            mp.prepareAsync();
        } catch (Exception e) {
            onError(mp, 0, 0);
        }
    }

    @Override
    public void onPrepared(MediaPlayer p) {
        prepared = true;
        int dur = p.getDuration();
        if (pendingSeek > 0 && (dur <= 0 || pendingSeek < dur - 3000)) p.seekTo(pendingSeek);
        pendingSeek = 0;
        Article a = current;
        if (a != null) {
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, a.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "ERR · " + a.sourceLabel())
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
                    .build());
        }
        play();
    }

    @Override
    public void onCompletion(MediaPlayer p) {
        if (current != null) store.markHeard(current.id);
        store.clearResume();
        releasePlayer();
        playNext(true);
    }

    @Override
    public boolean onError(MediaPlayer p, int what, int extra) {
        if (current != null) sessionSkip.add(current.id);
        releasePlayer();
        setStatus("Viga heli laadimisel, võtan järgmise…");
        main.postDelayed(() -> playNext(false), 1500);
        return true;
    }

    // ---------- juhtimine ----------
    private void play() {
        if (mp == null || !prepared) {
            if (mp == null) playNext(false);
            return;
        }
        if (am.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;
        mp.setVolume(1f, 1f);
        mp.start();
        applySpeed();
        playing = true;
        registerNoisy();
        if (!wifiLock.isHeld()) wifiLock.acquire();
        setStatus("");
    }

    private void pause() {
        if (mp != null && prepared && mp.isPlaying()) mp.pause();
        playing = false;
        saveResume();
        unregisterNoisy();
        setStatus("Paus");
    }

    private void next() {
        // Vahele jäetud artikkel loetakse kuulatuks, et see enam tagasi ei tuleks
        if (current != null) store.markHeard(current.id);
        store.clearResume();
        releasePlayer();
        playNext(false);
    }

    private void stopAll() {
        saveResume();
        loadToken++;
        releasePlayer();
        abandonFocus();
        unregisterNoisy();
        current = null;
        playing = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void seekTo(int pos) {
        if (mp == null || !prepared) return;
        try { mp.seekTo(Math.max(0, pos)); } catch (Exception ignored) { }
        saveResume();
        updateSessionState();
    }

    private void applySpeed() {
        if (mp == null || !prepared || !mp.isPlaying()) return;
        try {
            mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(store.speed()));
        } catch (Exception ignored) { }
        updateSessionState();
    }

    private void releasePlayer() {
        if (mp != null) {
            try { mp.reset(); } catch (Exception ignored) { }
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
        prepared = false;
        playing = false;
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    private void saveResume() {
        if (current != null && mp != null && prepared) {
            try { store.setResume(current.id, mp.getCurrentPosition()); } catch (Exception ignored) { }
        }
    }

    @Override
    public void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocus = false;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (playing) { resumeOnFocus = true; pause(); }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mp != null && prepared) mp.setVolume(0.3f, 0.3f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mp != null && prepared) mp.setVolume(1f, 1f);
                if (resumeOnFocus) { resumeOnFocus = false; play(); }
                break;
        }
    }

    private void abandonFocus() {
        am.abandonAudioFocusRequest(focusRequest);
    }

    private void registerNoisy() {
        if (noisyRegistered) return;
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, f);
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        noisyRegistered = false;
    }

    // ---------- teavitus ja meediasessioon ----------
    private void setStatus(String s) {
        status = s;
        updateSessionState();
    }

    private void updateSessionState() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO;
        int state = playing ? PlaybackState.STATE_PLAYING
                : (mp == null ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PAUSED);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position(), playing ? store.speed() : 0f)
                .build());
        getSystemService(NotificationManager.class).notify(NID, buildNotification());
    }

    private void startFg() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NID, n);
        }
    }

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent action(String a) {
        Intent i = new Intent(this, PlayerService.class).setAction(a);
        return PendingIntent.getService(this, a.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        Article a = current;
        String title = a != null ? a.title : "ERR kuulaja";
        String text = status != null && !status.isEmpty() ? status
                : (a != null ? "ERR · " + a.sourceLabel() : "");
        boolean isPlaying = playing;
        Notification.Action toggle = new Notification.Action.Builder(
                Icon.createWithResource(this, isPlaying ? R.drawable.ic_pause : R.drawable.ic_play),
                isPlaying ? "Paus" : "Esita", action(A_TOGGLE)).build();
        Notification.Action nextA = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_next), "Järgmine", action(A_NEXT)).build();
        Notification.Action stopA = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_close), "Lõpeta", action(A_STOP)).build();

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(toggle)
                .addAction(nextA)
                .addAction(stopA)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1))
                .build();
    }
}
