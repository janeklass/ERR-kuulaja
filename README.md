# ERR kuulaja

Lihtne Androidi äpp, mis mängib järjest ERR-i artiklite "Kuula artiklit" helisid – ilma et peaks ise
artikleid vahetama. Sobib hommikuks, autosse või kõrvaklappidega jalutuskäigule.

**Paigaldamine:** laadi alla [`ERR-kuulaja.apk`](https://github.com/janeklass/ERR-kuulaja/releases/latest)
(või sama fail selle repo juurest), ava telefonis ja luba paigaldamine tundmatutest allikatest.
Vajab Android 8.0 või uuemat. Äpp ei kogu isikuandmeid ega vaja kontot.

## Mida see teeb
- Artiklid vahetuvad ise, kuulamine jätkub automaatselt järgmisega; värskemad eelisjärjekorras,
  pärast iga artiklit kontrollitakse uusi
- Allikad: err.ee üldvoog + rubriigivood (Eesti, Välismaa, Majandus, Arvamus) + sport, kultuur, Novaator
- Peab meeles kuulatud artiklid (ka "Järgmine"-ga vahele jäetud) ja pooleli jäänud koha – ka järgmisel päeval
- Mängib taustal ja lukus ekraaniga (foreground service + MediaSession: lukuekraani ja kõrvaklappide nupud,
  paus kõne ajal, paus kui klapid välja tõmmata)
- **Nimekiri** on ekraani põhiosa: pilt, rubriik, kellaaeg, pealkiri; vajutus = mängi, pikk vajutus = ava brauseris;
  kuulatud on hallid ✓-ga
- **Mini-mängija** ekraani all: pealkiri, Esita/Paus, Järgmine, kiirus (0,9×–1,5×), juhuslik järjekord,
  keritav ajariba; vajutus pealkirjale kerib nimekirja mängiva artikli juurde
- **Filtrid** eraldi aknas: rubriigid sisse/välja (pikk vajutus = kuulan ainult seda), "Kõik sisse/maha",
  "Näita ka kuulatud", "Unusta kuulamise ajalugu"
- Leht **Novaator**: ainult Novaatori lood, filtreeritavad alateemade kaupa (Tervis, Keskkond, Tehnika …)
- Kujundus "Ajaleht": paberitoon, serif-pealkirjad, teravad nurgad; tume režiim süsteemi seade järgi
- Lõpeta-nupp (×) peatab mängimise ja sulgeb äpi

## Kuidas ERR-i heli leitakse
RSS-voogudest (`https://www.err.ee/rss`, `/rss/eesti`, `/rss/valismaa`, …, `sport.err.ee/rss`,
`kultuur.err.ee/rss`, `novaator.err.ee/rss`) tulevad pealkirjad, lingid ja pisipildid. Artikli HTML-is on
`toggleNeuroSpeech(...)` juures JSON `neuroSpeechUrl`, nt `https://kone.err.ee/meelis_<artikli-id>_<nr>.mp3`;
see võetakse regexiga välja (`Feed.java`, `AUDIO`). Kui ERR lehe ülesehitust muudab, tuleb see regex üle vaadata.
Artiklid, millel heli pole (nt Eetri saatelood), jäetakse vahele.

## Ehitamine
Natiivne Android, Java, ilma AndroidX/Media3 sõltuvusteta (ainult raamistiku API-d). minSdk 26, targetSdk 34,
compileSdk 36, AGP 8.13.2, Gradle 8.14 (wrapper kaasas), Java 17+.

1. Loo `local.properties` reaga `sdk.dir=<Android SDK kaust>`.
2. Android Studio: File > Open > see kaust > Build > Build APK(s). Või käsurealt:
   `gradlew assembleRelease` → `<kodukaust>/AppData/Local/ERR-kuulaja-build/app/outputs/apk/release/app-release.apk`
   (ehituskaust on projektist väljas, vt `build.gradle` – OneDrive lukustas muidu vahefaile).
3. Release on allkirjastatud debug-võtmega, seega kohe paigaldatav; uuendamisel läheb sama võtmega APK vana peale.

Kood: `app/src/main/java/ee/janek/errkuulaja/`
- `MainActivity.java` – kasutajaliides (programmiline, XML-paigutusi pole)
- `PlayerService.java` – taustamängija, järjekord, MediaSession, teavitus
- `Feed.java` – RSS-voogude laadimine ja helifaili leidmine
- `Store.java` – meelespeetav olek (kuulatud, rubriigid, kiirus, pooleli koht)
- `Images.java` – pisipiltide laadija, `Ui.java` – värvid, kirjad ja joonistusabid, `Article.java` – andmeklass

Arendusmärkmed ja versioonide ajalugu: `CLAUDE.md`.
