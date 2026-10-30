# ERR kuulaja – juhend ja üleandmine (Claude Code jaoks)

## Eesmärk
Lihtne Androidi äpp Janekule, mis mängib järjest ERR-i artiklite "Kuula artiklit" helisid
ilma, et peaks ise artikleid vahetama.

Nõuded (Janeki soovid):
1. Artiklid vahetuvad ise ja kuulamine käivitub automaatselt.
2. Peab meeles, mis on juba kuulatud (ei korda).
3. Värskemad artiklid eelisjärjekorras; pärast iga artiklit kontrollib uusi.
4. Allikad: kõik ERR-i uudised (err.ee) + soovi korral Novaator (novaator.err.ee).
5. Mängib ka lukus ekraaniga ja taustal (teised äpid lahti).
6. Lõpptulemus: valmis APK fail, mille Janek paigaldab oma telefoni.
7. Kaust on OneDrive'is, et arendust saaks hiljem jätkata.

## Uurimise tulemused (oktoober 2026)
- RSS: `https://www.err.ee/rss` (~50 viimast, kõik alamdomeenid segamini). Lisaks rubriigivood,
  igaüks ~50 viimast: `https://www.err.ee/rss/eesti`, `/rss/valismaa`, `/rss/majandus`, `/rss/arvamus`
  ning `https://sport.err.ee/rss`, `https://kultuur.err.ee/rss`, `https://novaator.err.ee/rss`,
  `https://eeter.err.ee/rss`, `https://news.err.ee/rss` (inglise). `menu.err.ee/rss` on tühi,
  `www.err.ee/<rubriik>/rss` ja raadiote alamdomeenide /rss ei tööta. Väljad: title, link, pubDate,
  category (www.err.ee-l rubriik: Eesti, Välismaa, Majandus, Arvamus, Raadiouudised, Teleuudised;
  teistel alateema, nt Korvpall, Kirjandus, Tervis).
- Heli ("Kuula artiklit") on Eesti, Välismaa, Majanduse, Arvamuse, Spordi, Kultuuri ja Novaatori
  artiklitel. Eetri saatelugudel ja raadio-/teleuudiste blokkidel seda pole → äpis rubriik "Muu".
- Artikli ID on lingis: `https://www.err.ee/<ID>/<slug>`. Suurem ID = uuem.
- Heli on tavaline MP3. Artikli HTML-is (serveris renderdatud) on nupu juures:
  `toggleNeuroSpeech($event, '{&quot;neuroSpeechUrl&quot;:&quot;https:\/\/kone.err.ee\/meelis_<ID>_<nr>.mp3&quot;,&quot;duration&quot;:5}', ...)`
  `<nr>` ei ole ID-st tuletatav, seega tuleb artikli leht alla laadida ja regexiga välja võtta:
  `kone\.err\.ee\\?/([A-Za-z0-9_\-]+\.mp3)` → `https://kone.err.ee/<grupp1>`.
- Testis 20 artiklist 18-l oli heli olemas (puudus nt eeter.err.ee ja üksikutel uudistel).
  Kui heli pole, märgitakse artikkel "heli puudub" ja jäetakse vahele.

## Tehnilised otsused
- Natiivne Android, Java, **ilma AndroidX/Media3 sõltuvusteta** (ainult raamistiku API-d) –
  lihtne ehitada ja hooldada.
- minSdk 26, targetSdk 34, compileSdk 36, AGP 8.13.2, Gradle 8.14 (wrapper kaasas), Java 17 (ehitatakse
  Android Studio JBR-iga, Java 21). Need versioonid valiti selle järgi, mis masinas olemas oli
  (SDK platform 36, Gradle 8.14 ja AGP 8.13.2 vahemälus) – nii ei pea midagi alla laadima.
- `local.properties` (ei commitita): `sdk.dir=C:/Users/lassj/AppData/Local/Android/Sdk`.
- **Ehituskaust on OneDrive'ist väljas**: `build.gradle` suunab selle kausta
  `C:/Users/lassj/AppData/Local/ERR-kuulaja-build/<projekt>` (APK: `.../app/outputs/apk/release/`).
  Põhjus: OneDrive lukustas vahefaile ja ehitus kukkus "Unable to delete directory" / AccessDenied veaga.
- Ehitamine käsurealt (Git Bash):
  `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug assembleRelease --no-daemon`
- Pakett: `ee.janek.errkuulaja`.
- Mängimine: `MediaPlayer` + foreground service (`foregroundServiceType="mediaPlayback"`),
  `MediaSession` (lukuekraan, kõrvaklapinupud), `Notification.MediaStyle`, partial wake lock +
  WiFi lock, audio focus (paus kõne/teise heli ajal), "becoming noisy" (klapid välja → paus).
- Release-build allkirjastatakse debug-võtmega, et APK oleks kohe paigaldatav.

## Failid
- `app/src/main/java/ee/janek/errkuulaja/`
  - `MainActivity.java` – UI (programmiliselt, XML layoute pole): praegune lugu, Esita/Paus,
    Järgmine, kiirus, rubriikide linnukesed, Värskenda, Näita kuulatuid, Unusta ajalugu,
    artiklite nimekiri (vajutus = mängi, pikk vajutus = ava brauseris).
  - `PlayerService.java` – järjekord ja mängija. `playNext()` valib: (1) pooleli jäänud artikkel,
    (2) uuim kuulamata + lubatud rubriik + heli olemas. Lõpus → märgib kuulatuks → `playNext(true)`
    (värskendab voogu, kui see on üle 3 min vana). Kui kõik kuulatud, proovib 5 min pärast uuesti.
  - `Feed.java` – 8 RSS-voo paralleelne laadimine (4 lõime), parsimine (XmlPullParser), mp3 leidmine,
    mälus vahemälu (max 600). Üldvoog on esimene, rubriigivood kirjutavad sama artikli üle.
  - `Ui.java` – "Ajalehe" palett (paber/tint, hele/tume süsteemi järgi), rubriigivärvid (trükivärvid,
    tumedas heledamad), serif-kirjad (`serif`, `serifBold`, `serifItalic`), tindiraamid (`bordered`),
    ruut-pide (`square`), kapiteelsilt (`label`), `capsButton`. **Kõik nurgad on teravad** –
    `rounded()` jätab raadiuse arvestamata. `image(c, size)` = pildivaade 1 dp tindiraamiga.
  - `Images.java` – pisipiltide laadija (LruCache mälus + `HttpResponseCache` kettal, 3 lõime).
    ERR annab RSS-is `media:thumbnail` 256×256 (`...t4.jpg`); muid suurusi URL-ist tuletada ei saa.
  - `Store.java` – SharedPreferences: kuulatud ID-d (max 3000), heli puudub, mp3 URL-i vahemälu,
    rubriigid (`src_<rubriik>`, vaikimisi kõik sees peale "Muu"), kiirus, pooleli koht.
  - `Article.java` – andmeklass; `rubric` arvutatakse `rubricFor()`-ga: sport/kultuur/novaator/menu
    alamdomeen → portaal; www.err.ee → RSS-kategooria, kui see on Eesti/Välismaa/Majandus/Arvamus,
    raadio-/teleuudised → Muu, muidu voo rubriik; kõik muu (eeter jt) → Muu.
- Rubriigikiibid UI-s: alati kõik `Store.ORDER` rubriigid (Eesti, Välismaa, Majandus, Arvamus, Sport,
  Kultuur, Novaator, Menu, Muu) + voost tulnud tundmatud. Vajutus = sisse/välja, **pikk vajutus =
  ainult see rubriik**, kiip "Kõik" paneb kõik tagasi. "Muu" on vaikimisi väljas (seal pole heli).
- Kujundus (v1.1): programmiline, ilma AndroidX-ita. Kaart "praegu mängib" (rubriik, pealkiri, olek,
  keritav ajariba, kiirus / suur ümar Esita / Järgmine), kaardilaadsed read rubriigivärviga,
  tume režiim süsteemi järgi (`Ui.night`), NoActionBar-teema, süsteemiribad taustavärvi.
  Ajariba kerimine → `PlayerService.A_SEEK` (+ MediaSession `onSeekTo`).
- v1.2 lisad: pisipildid kaardil ja ridadel; kiip "Kõik"/"Kõik maha" lülitab kõik rubriigid korraga;
  **Juhuslik** (ic_shuffle, `Store.shuffle`) – `playNext()` segab kandidaadid, pooleli jäänud jääb esimeseks;
  **Lõpeta** (×, päises) → `A_STOP` + `finishAndRemoveTask()`; **leht "Uudised | Novaator"** –
  Novaatori lehel on alateema-kiibid (RSS `<category>`: Tervis, Keskkond, Tehnika, Ajalugu …),
  salvestatakse `top_<teema>` (vaikimisi sees), `Store.allows(a)` = rubriik sees JA alateema sees.
- v1.3: mängija kaart oli **kokkupakitav** (v1.6-s eemaldatud, vt allpool). **Rida "Aktiivsed:"**
  kiipide all (`rebuildActive()`): iga sees oleva rubriigi/alateema juures ✕, mis võtab selle maha;
  kui kõik sees → "Aktiivsed: kõik rubriigid". **Nurgad vähem ümarad**: kaart/read 12dp, kiibid 8dp,
  nupud ümarnurksed ruudud (10–12dp) ringide asemel, pildid 8dp. Janek ei taha väga ümaraid nurki.
- v1.4: **filtrite riba on peidus** nagu poodide äppides: real [Uudised | Novaator] [Filtrid ▼ n/N]
  avab `filterPanel`-i (kiibid + vihje); olek `Store.filtersOpen` / `filters_open`, vaikimisi kinni.
  Rida "Aktiivsed" ✕-ga on nähtav ainult siis, kui mõni rubriik/alateema on väljas (kõik sees → peidus).
- v1.5: **voog salvestatakse kettale** (`cacheDir/feed.tsv`, `Feed.init(Context)` laeb avamisel kohe,
  `Feed.save()` kirjutab pärast iga värskendust taustal), et ekraan poleks avamisel tühi. Päises on
  laadimisring värskenduse ajal; tühja nimekirja teade on kohe nähtav ja vajutatav (`emptyAction`:
  näita kuulatuid / proovi uuesti).
- v1.6: **laiendaja kaotatud** – mängija kaardil on kõik alati nähtav: rida pilt 56dp + rubriik +
  pealkiri (2 rida) + olek + Esita + Järgmine, selle all ajariba ja rida aeg | kiirus | juhuslik.
  `Store.cardOpen`/`card_open`, `details` ja `expandBtn` on eemaldatud (Janeki soov: ei pea laiendama).
- v1.7: **kujundus "Ajaleht"** (Janek valis Claude Designi lõuendilt kolme stiili seast; lõuend:
  https://claude.ai/artifact/PvPuVQLkPfCwWa9mrTV6kg). Paberitoon #F3EEE4 / tint #1A1714 / ERR-punane
  #B8321F, tume režiim = negatiiv. Päis: "ERR kuulaja" serif 30 + kaldkirjas kuupäevarida
  ("Neljapäev, 30. oktoober · 23 kuulamata", `subtitle`, uuendatakse `rebuildList()`-is) + Värskenda-
  ja Lõpeta-ikoonid tindiraamis; all topeltjoon (`rule()` 3 dp + 1 dp). Mängija kaart: tindiraam 1,5 dp,
  pilt 68 dp raamiga, rubriik kapiteelis ("EESTI · MÄNGIB"), pealkiri serif-bold 19, olek serif-kaldkiri,
  ajariba punase täite ja ruudukujulise pidemega, aeg vasakul / kestus paremal (`timeText`, `durText`),
  nupud: kiirus (raam), juhuslik (raam, sees = punane täide), Järgmine (raam 48), Esita (tindiga
  täidetud 56×48). Tabid: serif, valitud = bold + 3 dp allajoon (`ulNews`/`ulNov`, `tabBox()`), "FILTRID"
  kapiteelis raamis (avatud = tindiga täidetud). Kiibid: kapiteel, raam / täidetud rubriigivärviga.
  Nimekiri: 1 dp hairline-eraldajad (`ui.outline`), rida = pilt 56 + [rubriik kapiteelis … kell] +
  pealkiri serif 16; mängiv = punane bold; kuulatud/heli puudub = hall + ✓ paremal. `roundButton` →
  `squareButton(icon, size, filled, l)`. Uus ikoon `ic_refresh`.
- v1.8: **nimekiri on ekraani põhiosa** (Janek: mängija ja filtrid võtsid 80% ekraanist). Ülesehitus:
  päis (ERR kuulaja | laadimisring | FILTRID n/N | ×) + topeltjoon; tabirida (Uudised | Novaator …
  kaldkirjas "N kuulamata · M kuulatud · kell") + joon; nimekiri `weight 1`; **mini-mängija all**
  (`buildPlayer()`, 2 rida: pilt 48 | rubriik + pealkiri (2 rida) | Järgmine | Esita; kiirus | juhuslik |
  ajariba | aeg "0:27 / 0:50"). Vajutus pealkirjale kerib nimekirja mängiva artikli juurde, pikk vajutus
  avab brauseris. **Filtrid on dialoogis** (`showFilters()`): "Kõik sisse/maha", rubriigid/alateemad
  märkekastidega (`checkRow`, vajutus = sisse/välja, pikk = ainult see), "Näita ka kuulatud artikleid",
  "Unusta kuulamise ajalugu". Filtripaneel, "Aktiivsed" rida, kiibid ja `Store.filtersOpen` kasutus
  on eemaldatud. Kell alati 24h (`FORMAT_24HOUR`). Uus ikoon `ic_check`.
  **Lõks:** vertikaalse LinearLayouti vaikimisi LayoutParams on MATCH_PARENT laiusega – `tabBox()`
  annab lastele laiuse ise, muidu venis "Uudised" kast üle terve rea ja ülejäänud jäid ekraanist välja.
- Kuulatud artiklid on püsimälus (SharedPreferences), säilivad äpi sulgemisel ja järgmisel päeval;
  "Järgmine"-ga vahele jäetud loetakse samuti kuulatuks. "Unusta ajalugu" kustutab.
- `app/src/main/res/` – vektorikoonid (Material icons), adaptiivne ikoon, stringid.

## Hetkeseis
- [x] ERR-i heli mehhanism uuritud
- [x] Kood kirjutatud
- [x] Kompileeritud (29.10.2026, vigu polnud)
- [x] Heli-regex kontrollitud päris artikli vastu (Java-test): mp3 leitakse, server annab audio/mpeg
- [x] Rubriigifilter RSS-kategooria järgi (nt ainult Välismaa) lisatud
- [x] APK ehitatud: `ERR-kuulaja.apk` kausta juures ja Downloads-kaustas (release, debug-võtmega)
- [x] v1.0 Janeki telefonis testitud: põhiline töötab
- [x] v1.1 (versionCode 2): kõik rubriigid eraldi voogudest, uus kaasaegne kujundus, ajariba kerimine
- [x] v1.2 (versionCode 3): pildid, Juhuslik, Lõpeta, Novaatori leht alateemadega, Kõik maha
- [x] v1.3 (versionCode 4): kokkupakitav mängija kaart, rida "Aktiivsed" ✕-ga, vähem ümarad nurgad
- [x] v1.4 (versionCode 5): filtrite riba peidus nupu "Filtrid" taga
- [x] v1.5 (versionCode 6): voo ketta-vahemälu (nimekiri kohe avamisel), laadimisring, vajutatav tühi olek
- [x] v1.6 (versionCode 7): laiendaja eemaldatud, ajariba/kiirus/juhuslik alati kaardil nähtaval
- [x] v1.7 (versionCode 8): kujundus "Ajaleht" (serif, paber/tint, teravad nurgad, hairline-read)
- [x] v1.8 (versionCode 9): nimekiri põhiosa, mini-mängija all, filtrid dialoogis – **emulaatoris kontrollitud**
  (30.10.2026: nimekiri, mängimine, ajariba, filtrite dialoog töötavad)
- [x] v1.9 (versionCode 10): paus ka siis, kui Bluetooth-klapid tühjaks saavad – lisaks AUDIO_BECOMING_NOISY
  teatele jälgib `PlayerService` `AudioDeviceCallback`-iga heliseadmeid: kui mängimise ajal kaob kõrvaklapi-tüüpi
  seade (BT A2DP/SCO/BLE, juhtmega, USB, kuuldeaparaat), läheb pausile (olek "Paus – kõrvaklapid lahti").
  Noisy-teade testitud emulaatoris: `adb root` + `adb shell am broadcast -a android.media.AUDIO_BECOMING_NOISY`.
- [x] Avalik GitHubi repo https://github.com/janeklass/ERR-kuulaja (MIT), APK release'ina:
  `gh release create vX.Y ERR-kuulaja.apk --title ... --notes ...`; link "latest" annab alati viimase.
- [x] v1.10 (versionCode 11): **Novaatori tab = mängitakse ainult Novaatorit** – leht salvestatakse
  (`Store.novaatorPage` / `page_novaator`), `Store.allows()` arvestab seda, nii nimekiri kui PlayerService
  kasutavad sama reeglit. **Uute rubriikide vaikeväärtus** `src_default` / `top_default`: "ainult see" paneb
  selle false (hiljem lisanduvad rubriigid on väljas), "Kõik sisse/maha" paneb sama väärtuse. Põhjus: Janekil
  oli vanas versioonis ainult Välismaa sees, uuendusega lisandunud Sport/Kultuur/Novaator/Menu tulid sisse.
- [x] v1.11 (versionCode 12): **hele/tume nupp päises** (kuu heledas → tume, päike tumedas → hele; vektorikoonid
  `ic_moon`/`ic_sun`). `Store.theme()` = "system" (vaikimisi, telefoni järgi) / "light" / "dark";
  `Ui(Context, theme)`; vajutus salvestab ja teeb `recreate()`. Janek eelistab heledat.
- [ ] v1.1–v1.11 Janeki telefonis testimata (v1.9 oli telefonis: BT-klappide paus, filtrid, Novaator – tagasiside saadud)

## Järgmised sammud
1. Janek paigaldab `ERR-kuulaja.apk` telefoni (luba "tundmatutest allikatest"). Uuendamisel
   sama võtmega APK läheb vana peale; kui versionCode ei muutu, võib olla vaja vana enne eemaldada.
2. **Emulaator on töökorras** (paigaldatud 30.10.2026): `cmdline-tools/latest` (sdkmanager, avdmanager)
   ja system image `system-images;android-34;google_apis;x86_64` on SDK-s, AVD "liivakast" (Pixel 6,
   Android 14) on `C:\Users\lassj\.android\avd`. WHPX kiirendus töötab. Käivitamine (Git Bash, taustal):
   `$SDK/emulator/emulator.exe -avd liivakast -no-boot-anim -no-audio -gpu swiftshader_indirect`
   (SDK = `/c/Users/lassj/AppData/Local/Android/Sdk`; boot ~50 s). Seejärel
   `adb install -r <apk>`, `adb shell am start -n ee.janek.errkuulaja/.MainActivity`,
   ekraanipilt `adb exec-out screencap -p > shot.png` (Read-tööriistaga vaadatav),
   vaatepuu `adb shell uiautomator dump //sdcard/ui.xml` + `adb exec-out cat //sdcard/ui.xml`
   (Git Bashis `MSYS_NO_PATHCONV=1`, et `/sdcard` ei muutuks Windowsi teeks). Emulaatori lokaat on en-US,
   seega kuupäevad on seal inglise keeles; Janeki telefonis eesti keeles.
3. Uue versiooni tegemisel tõsta `app/build.gradle` versionCode/versionName.
4. Testi (emulaator või telefon):
   - äpi avamine laeb nimekirja, uuemad ees
   - Esita → mängib; lõpus läheb ise järgmisele ja eelmine on ✓
   - Järgmine → jätab vahele ja märgib kuulatuks
   - lukus ekraan: heli jätkub, lukuekraanil on nupud
   - äpi sulgemine ja uuesti avamine: kuulatud on meeles, pooleli kohast jätkab
   - rubriigi väljalülitamine mõjub järgmisele valikule; pikk vajutus "Välismaa" → mängib ainult neid
   - teavituse luba (Android 13+) küsitakse
5. Hiljem võimalikud lisad: maksimaalne artikli vanus (nt ainult viimased 24 h), rubriigi
   prioriteedid, uneaeg/taimer, Android Auto, rohkem RSS-vooge (nt news.err.ee inglise keeles).

## Tähelepanu
- Kui ERR muudab lehe ülesehitust, siis tuleb `Feed.AUDIO` regex üle vaadata.
- Ära pane kasutaja andmeid ega võtmeid repo sisse; äpp ei kogu isikuandmeid.
- OneDrive'i juurkaustas on `_to_delete/ERR-kuulaja.tar.gz` – ülekandeks kasutatud arhiiv,
  selle võib kustutada.
