# Material 3 Expressive modernizáció – megvalósíthatósági terv

**Státusz:** elfogadva (D0–D8, 7. fejezet); az F0 és az F1 kész, az F2 folyamatban (az F2A–F2D kész).
**Alap:** read-only kódbázis-audit kilenc subagent-feladattal (Expressive alapok és képernyők, Classic UI és runtime-utak, WebView-oldalak, build/függőségek/tesztek, akadálymentesség/lokalizáció/reszponzivitás), közvetlen dokumentum- és worktree-ellenőrzés, valamint a Google Maven AAR-metaadatainak lekérdezése. Az 1., 4. és 5. fejezet számai és „jelenlegi” állapotai az audit idejére, az F1 előtti `feature/p0-foundation` worktree-re vonatkoznak; az azóta elért állapotot a 2. fejezet írja le.

## 1. Verdikt

**Megvalósítható, de három, eltérő kockázatú szinten:**

| Szint | Ítélet | Indoklás |
|---|---|---|
| **A** – az Expressive Compose kliens valódi M3E-re hozása | ✅ alacsony technikai kockázat | Az audit idején rögzített `material3 1.4.0-alpha10` is tartalmazta a fő M3E API-kat (`MaterialExpressiveTheme`, `MotionScheme`, `ButtonGroup`, `FloatingToolbar`, `LoadingIndicator`, `FloatingActionButtonMenu`, `ShortNavigationBar`, `WideNavigationRail`, `MaterialShapes`, `SplitButton`, `ToggleButton` – az AAR-ból ellenőrizve); az F1 óta `1.5.0-alpha18` van rögzítve. A hiány a kódban volt: egyetlen M3E komponenst használtunk (`LinearWavyProgressIndicator`). |
| **B** – teljes app, nulla Classic runtime, egységes M3E | ⚠️ megvalósítható, de nagy program | A maradék Classic felületek egy részét natívan újra kell írni (jegyzetszerkesztő, beállítások, jegyzettípus- és sablonszerkesztő, indítási lánc, külső belépések, reviewer-extrák). A backend WebView-oldalak (deck options, statisztika, image occlusion) CSS-tokenhíddal, újraírás nélkül M3E-konzisztenssé tehetők. |
| **C** – nem lehetséges vagy nem cél | ❌ | A felhasználói kártyasablonok M3E-sítése (felhasználói tartalom). Teljes M3E stabil `material3`-mal (az 1.4.0 stabilból hiányoznak a komponensek). Új M3E komponensek a View-alapú Classic UI-ban (az MDC Views karbantartási módban van, csak a `Theme.Material3Expressive.*` témák érhetők el). |

**Durva nagyságrend** (1 fő teljes munkaidőben, agent-támogatással, ±50%): az A szint (F0–F4) ~2–3,5 hónap, a teljes B szint ~6–9 hónap. A legnagyobb bizonytalanság a jegyzetszerkesztő.

## 2. Hol tartunk

**Tasklista:** a Task 2–13 lezárva és semantic review-val jóváhagyva (a Task 13 utolsó köre: `semantic-review/2026-09-29-062212-pr-0.md`). A Task 14–18 még hátravan.

**A Task 13 (backup, restore, karbantartás) lezárva**, négy review-kör után `APPROVED`:

- **Natív Maintenance útvonal**, Classic fallback nélkül: backupok és megőrzési beállítások, visszaállítás backupból vagy COLPKG-fájlból, adatbázis-ellenőrzés, üres kártyák, médiaellenőrzés.
- **Visszaállítás:** izolált staging és validálás még a megerősítés előtt. Utána mindig új biztonsági mentés készül, az adatbázis journalozott cserével kerül a helyére, és rollback történik, ha nem nyílik meg. A média Anki-szemantikával olvad be; ami nem mozdítható, karanténba kerül, és a Backups képernyőn újra hozzáadható vagy törölhető.
- **Sync-kizárás:** a gyűjtemény- és a médiaszinkron külön kezelt, a csak várakozó médiaszinkron semmit nem blokkol, a backend saját állapotát a collection-sorban is újraellenőrizzük.
- **Bizonyíték:** 32 gateway-teszt valódi backenddel (összeomlás minden journal-fázisban, rollback-hiba, karantén, elárvult staging), 29 coordinator- és 6 Activity-teszt, ktlint zöld.
- **Részben nyitott:** a Maintenance TalkBack-szemantikáját az F1 óta 5 Compose UI-teszt fedi. Az automatikus backupot indító resume-effekt Compose-szintű tesztje még hátravan; a teljes Android lint az F2D óta hibamentes.

**Az F1 (toolchain és design-system alap) kész**, két review-kör és két delta-review után `APPROVED` (`semantic-review/2026-09-29-190247-pr-0.md` és a későbbi delta-review-k):

- **Toolchain (A-út):** `material3 1.5.0-alpha18` szigorú pinnel a version catalogban, az `ExperimentalMaterial3ExpressiveApi` opt-in központilag az `:AnkiDroid` modulra. A B-út (AGP 9.4, compileSdk 37, alpha25) spike-ja a G1 után, az F3 széles migrációja előtt jön.
- **Téma:** `MaterialExpressiveTheme`, egyetlen `MotionScheme.expressive()`, a teljes M3E shape-skála és az M3E token-típusskála a kiemelt (emphasized) stílusokkal.
- **Színek:** a hat paletta magszín, a teljes 48 szerepes „tonal spot” séma ebből generálódik, az MCU sémájával egyező tónusokkal. A válaszszínek az aktív primaryhoz harmonizálnak: az Again és a Hard legfeljebb 5°-ot fordul, a világos konténerek chromája legfeljebb 24.
- **Kontraszt:** a konténereken lévő feliratok átlátszatlan on-container szerepeket használnak (a tónusokból adódóan ~7:1; a tesztek minden sémában ≥4,5:1-et követelnek meg), a számláló chipek konténer-szerep párokat, a kijelölt paklikártyán tömör akcentusokat.
- **Teszt-infrastruktúra:** Compose ui-test (v2 rule), szerződésteszt a teljes típusskálára az alpha18 tokenjei ellen, tónus-, kontraszt- és harmonizációs tesztek, a Maintenance TalkBack-szemantikája, valamint 20 Roborazzi-screenshot világos és sötét témában: színszerepek, komponensek, paletták, 2× betűméret, RTL, az Account képernyő és az app saját komponensei.
- **Bizonyíték:** az alpha18-on a `material3`-at használó összes kód tesztjei zöldek (193 teszt 21 osztályban): az Expressive, az account, a `com.ichi2.compose` (Classic Compose-téma), a `com.ichi2.themes` és a manifest-téma tesztcsomag, valamint a `verifyStandaloneApp` (azonosító- és FLOSS-függőségi szerződés). A ktlint és a `git diff --check` is zöld. A teljes `testFullDebugUnitTest` csomag az F1-ben nem futott.
- **Örökölt hibák, amelyeket a szélesebb futás talált:**
  - az import-oldal hostja (`ExpressiveImportPageActivity`) a manifestben a sötét launcher-témát kapta, és nem alkalmazta a felhasználó témáját, ezért világos módban is sötéten, a splash-háttér fölött jelent meg; most az `AnkiActivity`-hez hasonlóan a `Themes.setTheme` állítja be, teszttel;
  - a Classic Compose-téma tesztje (`ThemeTest`) még a palettacsere előtti primary színeket várta.
- **Továbbvitt tételek:**
  - a vékony `ui/expressive/designsystem` komponensréteg és az Expressive lint-szabályok (színliterál, `tween`, szövegliterál, `indication = null`) az F2-be;
  - a Home összesítő kártyája 2× betűméretnél kilóg (`app_components_font_2x` screenshot), ez az F3 Home-DoD része;
  - a dinamikus szín csak eszközön ellenőrizhető, mert Robolectricben nincs háttérkép-szín;
  - a teljes unit-tesztcsomag futtatása a B-út spike-jával együtt; az `ExistingProfileMigrationActivity`-nek nincs tesztje, az alpha18-on csak a fordítása ellenőrzött;
  - ~~**külön javításként, az F2 előtt:** a `Themes.setTheme(activity, …)` sorrendhibája (29edb22b3a)~~ – külön commitban javítva: a launcher-splash felismerése újra a manifest-téma alapján történik, a dinamikus szín pedig mindig a paletta után kerül fel, a párbeszédablakok újratémázásakor is. A paletta a `PrefsRepository`-n át olvasódik, így a backupból visszaállított, inicializálatlan app útja sem omlik össze.

**Az F2 (a tanulás képernyő vertikális szelete) folyamatban**, minden rész semantic review-val jóváhagyva:

- **F2A** (`3dfe04c14b`): a kártyasablon úgy jelenik meg, ahogy a Classic reviewerben: nincsenek kényszerített `!important`-színek és a kártya DOM-ját mozgató CSS-animációk. A kártyaváltás a kártyapanel dolga lett, a téma motion scheme-jével; kikapcsolt rendszeranimációknál semmi sem mozog.
- **F2B** (`8fd196ea76`): M3E chrome. `Scaffold` + TopAppBar alcímmel és wavy haladásjelzővel, `HorizontalFloatingToolbar` a kártyaműveletekhez, `ButtonGroup` válaszgombok intervallummal, medium „Show answer”, csoportosított és görgethető menük, haptika; rövid, széles ablakban a vezérlők egy sorba kerülnek.
- **F2C** (`6dc0e09204`): a töltés-, hiba- és gratulációs panel, valamint a timebox-, unbury- és custom study párbeszédablak az Anki saját szövegeivel (`TR`) és `MaterialShapes`-jelvényekkel.
- **Fordítás** (`394b040a6a`): 16 string, amely `TR`-fordítást duplikált, most a `TR`-ből jön.
- **F2D:** az Expressive lint-szabályok (a 3. fejezet 11. pontja) és hibamentes teljes Android lint; a futás 69 örökölt hibát talált, mind javítva.
- **Hátravan:** az F2E (a vékony design-system réteg), a screenshot-mátrix újrarögzítése és a G1-átadás: debug APK és az eszközön ellenőrizendő lista.

**Worktree:** a Task 2–13 és az F1 munka helyi checkpoint commitokban van, push nélkül.

**UI-mérőszámok** az audit idején, az F1 előtt (`ui/expressive/**`, 39 fájl, 15 316 LOC):

| Minta | Előfordulás |
|---|---|
| `MaterialExpressiveTheme` / `MotionScheme` | 0 / 0 |
| `ButtonGroup` / `FloatingToolbar` / `FloatingActionButtonMenu` | 0 / 0 / 0 |
| `LinearWavyProgressIndicator` (az egyetlen M3E API) / `CircularProgressIndicator` | 2 / 14 |
| `Scaffold(` | 1 (csak az AccountScreen) |
| `indication = null` (nincs ripple és fókuszjelzés) | 9 |
| `Color(0x…)` literál / `tween(` | 170 / 27 |
| `Text("…")` literál / `stringResource(` | 123 / 73 |
| `expressive_*` string-kulcs / lefordítva | 71 / 0 (82 locale) |
| Compose UI- vagy screenshot-teszt Expressive képernyőre | 0 (a 23 Roborazzi-teszt mind Classic) |

**Classic kód:** 671 fájl / 121 803 LOC, 215 layout; a témák `Theme.Material3.*` baseline-ok.

**Classic runtime-utak ma:**

- **Indítás:** az `IntentHandler.kt:385` a Classic `DeckPicker` bootstrapon át (`DeckPicker.kt:1926`) jut el az Expressive-ig. A bevezető és a migráció is Classic képernyő.
- **Expressive-ből indított Classic felületek:**
  - a jegyzetszerkesztő, rajta keresztül a sablonszerkesztő, az image occlusion, a multimédia és a címkék;
  - a deck options és a statisztika (`SingleFragmentActivity`-ben);
  - az import-oldal hostja (Classic layout és téma);
  - a `TtsVoicesDialogFragment`.
- **Külső belépések:**
  - az értesítés `Intent(DeckPicker)`-t indít (`NotificationService.kt:199, 403`);
  - a widget és a review intent a Classic reviewert nyitja;
  - a böngésző deep link a `CardBrowser`-t, a megosztás a `NoteEditor`-t nyitja;
  - a parancsikonok Classic célpontokra mutatnak.
- **Reviewer:** a `processViewerAction` 25-nél több `ViewerAction`-t nem kezel (`StudyScreen.kt:360-423`, `else -> false`), például a REDO, DELETE, címkézés, átütemezés, `USER_ACTION_1–9` és a hintek. Nincs whiteboard, hangfelvétel, JS API, card info és auto-advance.

## 3. A „valódi M3 Expressive" mérhető Definition of Done

Egy képernyő akkor M3E-kész, ha az alábbiak mind teljesülnek:

1. **Téma:** a gyökér `MaterialExpressiveTheme` + `MotionScheme.expressive()`. A szín csak color role-ból jön, `Color(0x…)` csak a téma- és palettafájlokban megengedett. A teljes shape-skála (a bővített szintekkel) és az emphasized tipográfia definiálva van.
2. **Komponensek:** ahol van szabványos M3E komponens, azt használjuk (5. fejezet). Nincs `Surface + clickable(indication = null)` vezérlő.
3. **Szerkezet:** `Scaffold` + (Flexible) TopAppBar, vagy dokumentált kivétel (immersive tanulás). Nincs „Back" `TextButton`-sor.
4. **Mozgás:** a komponensmozgás a `MaterialTheme.motionScheme` rugóiból jön. Van predictive back animáció. A rendszer animációcsökkentését tiszteletben tartjuk, a kártya-WebView-ban is (`prefers-reduced-motion`).
5. **Visszajelzés:** a töltést `LoadingIndicator`/`ContainedLoadingIndicator` jelzi, a determinált folyamatot wavy indikátor. A tanulási döntések haptikát adnak.
6. **Navigáció:** `NavigationSuiteScaffold` (kompakt ablakban `ShortNavigationBar`, széles ablakban `WideNavigationRail`), `Role.Tab`/selected szemantikával.
7. **Adaptív elrendezés:** compact, medium és expanded ablakméretre (Browse list–detail, tanulás tájképben).
8. **Akadálymentesség:**
   - szerep- és állapot-szemantika minden vezérlőn;
   - legalább 48 dp érintési cél és látható fókusz;
   - 200%-os betűméret vágás nélkül;
   - helyes TalkBack-sorrend;
   - minden long-press műveletnek van látható alternatívája.
9. **Lokalizáció:** nincs hard-coded szöveg; a pluralok és az RTL-tükrözés kezelve. Ahol lehet, meglévő AnkiDroid-fordítást vagy a backend `TR` szövegeit használjuk.
10. **Bizonyíték:**
    - Roborazzi screenshot-mátrix: világos/sötét, dinamikus szín be/ki, 1.0/2.0 betűméret, RTL, compact/expanded;
    - Compose UI-teszt a kulcsfolyamatokra;
    - tulajdonosi vizuális jóváhagyás referencia-eszközön.
11. **Kikényszerítés:** egyedi lint-szabályok a meglévő `:lint-rules` modulban a `ui/expressive` csomagra. Tiltott: `indication = null`, `Color(0x` a témán kívül, `Text("…")` literál, `CircularProgressIndicator`, nyers `tween(` komponensmozgásra, szerep nélküli `clickable`.
    - Az F2D óta az `ExpressiveDesignDetector` hibaként jelzi ezeket. A szövegszabály a `Text` mellett az `Icon`/`Image` `contentDescription`-jét, a `showSnackbar` üzenetét és gombfeliratát, a `clickable` click labeljeit, valamint az `if`, `when` és elvis ágait is nézi.
    - A lint nem látja a képernyőn kívül, például view modelben összeállított szövegeket (`ExpressiveBrowseViewModel.kt:340`), sem a `semantics {}` blokkban beállítottakat: ezek review-tételek.
    - A szabályok előtti sértéseket fájlonkénti `<ignore>`-lista mentesíti (`lint-release.xml`). Egy fájl akkor kerül le róla, amikor a képernyője migrál (F3: a shell, Home és Browse; F4: More, Settings és Stats); új fájl nem kerülhet fel.

## 4. Könyvtár- és toolchain-kapu (ellenőrzött tények)

| Opció | `material3` | Toolchain-igény (AAR-metaadat) | Compose-alap | Értékelés |
|---|---|---|---|---|
| Az F1 előtt | `1.4.0-alpha10` strict pin (`AnkiDroid/build.gradle:639-652`) | compileSdk ≥ 35, AGP ≥ 8.6 | BOM 2026.06.00 → 1.11.3 | Függőségváltozás nélkül is elkezdhető, de régi az API-alak (pl. nincs `SegmentedListItem`). A pin „Compose 1.7.5" kommentje elavult. |
| **A** – az F1-ben bevezetve | `1.5.0-alpha18` + `material3-adaptive-navigation-suite 1.5.0-alpha18` | nincs változás (compileSdk 36, AGP 9.0.1) | az igénye 1.11.0-beta02, a BOM 1.11.3-at ad | Az utolsó alpha, amely a jelenlegi toolchainnel működik. Az M3E API-k itt még experimentalok. A navigation-suite az F3 shell-munkájával kerül be. |
| **B** – spike után | `1.5.0-alpha25` körül + BOM 2026.09.00 | compileSdk 37 (az `android-37.0` platform telepítve) és AGP ≥ 9.1.0 (stabil 9.2.0–9.4.1 elérhető) | az igénye 1.12.0-beta01, a BOM 1.12.1 stabilt ad | Graduált API-k: `ButtonGroup` és `FloatingToolbar` (alpha22), flexible top app barok és expressive list itemek (alpha23). Blokkoló: az AGP 9.0.1 pin oka az issuetracker 491718901 (androidTest release-módú körkörös függőség, `gradle/libs.versions.toml:23-25`). |
| Legfrissebb | `1.5.0-alpha28/29` | mint B | 1.13.0-alpha01 | Release-hez nem ajánlott, mert Compose UI alphát húz be. |
| Stabil | `1.4.0` | — | — | Nem járható: hiányzik a `ButtonGroup`, a `FloatingToolbar`, a `LoadingIndicator`, a FAB menu és a `MaterialShapes`. |
| Views | MDC `1.14.0` | — | — | Van `Theme.Material3Expressive.*`, de az MDC Views karbantartási módban van, új funkció nem várható. |

**Javaslat:** induljunk az A-val, és párhuzamosan futtassunk spike-ot a B-re. Ha az AGP-hiba megoldott és a teljes tesztcsomag zöld, a széles képernyőmigráció előtt váltsunk B-re, hogy később kevesebb alpha API-változást kelljen követni.

Kötelező kísérő lépések:

- ~~a verzió a version catalogba kerüljön a hard-coded `build.gradle`-sorok helyett~~ – az F1-ben kész;
- ~~a `-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi` központilag kerüljön a `build.gradle.kts:147-154` opt-in blokkba (a warnings-as-errors miatt)~~ – az F1-ben kész;
- vékony design-system réteg (`ui/expressive/designsystem`), hogy az alpha API-változások egy helyen csapódjanak le. A téma része az F1-ben a `ui/expressive/theme` csomagba került; a komponensréteg az F2-ben, az első M3E komponensekkel együtt jön.

## 5. Terület-mátrix

A „Ma” oszlop az audit idejének állapota.

| # | Terület | Ma | M3E cél | Megv. | Méret |
|---|---|---|---|---|---|
| 1 | Téma, tokenek | sima `MaterialTheme` (`ExpressiveTheme.kt:84-90`), kézi paletták, 5 shape-szint, nincs emphasized | `MaterialExpressiveTheme`, `MotionScheme.expressive()`, seed-alapú tonális paletták + dinamikus szín, kontrasztszintek, teljes shape-skála, emphasized tipográfia, harmonizált válaszszínek | ✅ az F1-ben kész, a kontrasztszintek nélkül | S–M |
| 2 | Shell, navigáció | egyedi 44 dp alsó sáv (`ExpressiveBottomBar.kt:98`), `AnimatedVisibility`-overlayek, `BackHandler` | `NavigationSuiteScaffold`, predictive back (`PredictiveBackHandler` vagy Navigation 3), képernyőnként `Scaffold` | ✅ | M |
| 3 | Tanulás (chrome) | `Surface` válaszgombok, szöveges „Loading…", 7 ikonos sor | `ButtonGroup` válaszgombok intervallummal, shape-morph „Show answer", `HorizontalFloatingToolbar` + overflow, `LoadingIndicator`, MotionScheme-átmenetek, haptika; a kártya-WebView marad | ✅ | M |
| 4 | Home | egyedi speed-dial FAB, `Surface` deck-sorok | flexible top app bar, `FloatingActionButtonMenu`, szegmentált `ListItem`-csoportok, `ModalBottomSheet` deck-műveletek | ✅ | M |
| 5 | Browse | pill `OutlinedTextField`, 11 `TextButton` | `SearchBar`, `HorizontalFloatingToolbar` a tömeges műveletekhez, jelölős `ListItem`, tableten `ListDetailPaneScaffold` | ✅ | L |
| 6 | Beállítások | 4 szekció, halott sorok, hard-coded verzió | a 14 Classic beállítás-fejléc natív megfelelője M3E listaelemekkel | ✅ | L |
| 7 | More, Account, Transfer, Maintenance | ad-hoc fejlécek | közös `Scaffold` + TopAppBar minta, `ListItem`, `LoadingIndicator`/wavy | ✅ | S–M |
| 8 | Statisztika | natív összesítő + Classic-hostolt WebView | natív összesítő M3E-ben; a részletes grafikonok WebView-ban, tokenhíddal | ✅ hibrid | M |
| 9 | Backend WebView-oldalak | csak `#night` hash (`PageFragment.kt:143`), Classic host | CSS-tokenhíd az `index.html` kiszolgálásánál (`PageWebViewClient.kt:54`) + Expressive Compose host-chrome a `SingleFragmentActivity` helyett | ✅ | S + M |
| 10 | Card info, change notetype, APKG-opciók | WebView / Classic | natív Compose (a Kotlin-wrapperek többnyire léteznek) | ✅ | S–M |
| 11 | Jegyzetszerkesztő (címkék, multimédia, cloze, sticky, előnézet) | Classic, ~7,6k LOC | natív Compose; a legnehezebb a HTML-mezőszerkesztés (D3) | ⚠️ | XL |
| 12 | Jegyzettípusok, mezők, kártyasablon-szerkesztő | Classic, ~3,7k LOC | natív Compose | ✅ | L |
| 13 | Indítás, bevezető, migráció, tárhely-kapu | Classic `DeckPicker` bootstrap | saját Expressive indítás, `DeckPicker` nélkül | ✅ | M–L |
| 14 | Külső belépések (értesítés, widget, parancsikon, megosztás, deep link, review intent) | Classic célpontok | minden az Expressive-be routolva; widgetek Glance-szel vagy M3-színű RemoteViews-zal | ✅ | L |
| 15 | Reviewer-paritás | kezeletlen `ViewerAction`-ök; nincs whiteboard, hangfelvétel, JS API, card info, auto-advance | natívan, M3E chrome-mal | ✅ | L |
| 16 | Felhasználói kártyasablon | a `themedCardHtml` `!important`-tal felülírja a `.card` színeit (`StudyScreen.kt:964-965`) | nem M3E-sítjük: az override-ok megszűnnek, csak alacsony specificitású `:where()` alapértékek és opcionális `--m3-*` változók maradnak, a `night_mode` az Expressive témát követi | ❌ tervezetten | S |
| 17 | Classic View-k az átmenet alatt | `Theme.Material3.*` baseline | opcionális átmeneti csere `Theme.Material3Expressive.*`-ra, új View-munka nélkül | ⚠️ átmeneti | S–M |

## 6. Ütemterv és kapuk

Durva időkeretek, azonos feltételezéssel (1 fő, ±50%):

| Fázis | Tartalom | Kilépési kapu | Idő |
|---|---|---|---|
| F0 | A Task 13 lezárása: a blocking-hiba javítása (az automatikus backup ne blokkolja a collectiont), overview-címke, stringek, tesztek, ktlint, semantic review, `PARITY_CONTRACT.md` | a review `APPROVED`, a Task 13 validációja zöld | **kész** |
| F1 | Toolchain: A azonnal, B spike-ban. Design-system réteg, `MaterialExpressiveTheme`, tokenek, lint-szabályok, Compose screenshot- és UI-teszt alap | A-n a build és a unit tesztek zöldek; döntés a B-ről | **kész** az A-n, a `material3`-at használó kód tesztjeivel (a komponensréteg és a lint-szabályok az F2-ben, a B-spike az F3 előtt) |
| F2 | A tanulás képernyő mint vertikális szelet a teljes DoD-vel; a kártyasablon-hűség javítása | **G1: tulajdonosi vizuális jóváhagyás** referencia-eszközön; screenshot-mátrix | 1–2 hét |
| F3 | Shell és navigáció, Home, Browse | G2: predictive back, adaptív elrendezés, TalkBack-ellenőrzés | 3–4 hét |
| F4 | Beállítás-paritás; More, Account, Transfer, Maintenance, Stats | DoD minden képernyőn, 0 lint-sértés | 3–5 hét |
| F5 | WebView-tokenhíd és Expressive page host; card info, change notetype és APKG-opciók natívan | G3: nincs látható Classic host | 1–2 hét |
| F6 | Classic-eltávolítás: jegyzetszerkesztő (4–6 hét), jegyzettípus/sablon (3–4), indítás és külső belépések (2–3), widget/értesítés (2–3), reviewer-paritás (2–3) | G4: minden belépési pont Expressive; Robolectric intent-teszt bizonyítja, hogy nem indul Classic Activity | 13–19 hét |
| F7 | Kiadás: fordítási pipeline, akadálymentességi audit, teljesítmény (baseline profile), F-Droid | G5: kiadási checklist | 3–5 hét |

Az F0–F4 után a felhasználó által látott Expressive kliens valódi M3E (A szint). Az F5–F7 viszi el a nulla Classic állapotig (B szint).

**Hatás a meglévő tasklistára:**

| Meglévő task | Hová kerül |
|---|---|
| Task 13 | F0 |
| Task 14 (beállítások, értesítések, widgetek, intentek) | a beállítások az F4-be, a többi az F6-ba |
| Task 15 (lokalizáció, reszponzivitás, akadálymentesség) | képernyőnként az F1–F4 DoD-jába, a fordítási pipeline az F7-be |
| Task 16 (megbízhatóság, teljesítmény, biztonság) | F7 |
| Task 17 (a Classic eltávolítása) | F5–F6 |
| Task 18 (kiadás) | F7 |

Így a UI-munkát nem kell kétszer elvégezni.

## 7. Tulajdonosi döntések

| # | Kérdés | Javaslat |
|---|---|---|
| D0 | Feloldhatók-e a korábbi munkaszabályok (nincs függőségmódosítás, nincs új tesztfájl)? A `material3`-frissítés, a navigation-suite/adaptive és a Compose ui-test függőség, valamint az Expressive screenshot-tesztek ezt igénylik. | Igen, az M3E programhoz. Enélkül csak az alpha10-en, régi API-alakkal lehet haladni, és a screenshot-bizonyíték is korlátozott. |
| D1 | Toolchain: A (alpha18) vagy B (AGP 9.4.x + compileSdk 37 + alpha25)? | Az A-val indulás, a B spike-ban; a széles migráció előtt váltás a B-re, ha zöld. |
| D2 | Elfogadható-e alpha `material3` a release-ben? | Igen, mert M3E-hez nincs stabil alternatíva. Pin + design-system réteg + screenshot-diff. |
| D3 | Jegyzetszerkesztő: teljesen natív Compose, vagy átmenetileg M3E Compose host a meglévő mezőszerkesztő View-k körül? | Átmeneti host a Classic shell gyors megszüntetéséhez, utána natív csere. |
| D4 | Deck options, statisztika, image occlusion: WebView + tokenhíd vagy natív újraírás? | Tokenhíd az 1.0-hoz; natív csak később, indokolt esetben. |
| D5 | Lokalizáció: saját Crowdin/Weblate projekt a fork stringjeinek, vagy upstream-együttműködés? | Saját projekt, a meglévő AnkiDroid-fordítások és a backend `TR` maximális újrahasznosításával. |
| D6 | Legyen-e átmeneti Classic re-skin `Theme.Material3Expressive.*`-szal? | Csak akkor, ha az F6 elhúzódik. |
| D7 | Mi a referencia-eszköz és a vizuális elfogadási kritérium? | 1 kompakt telefon + 1 tablet vagy összecsukható; dinamikus szín be/ki; világos és sötét téma. |
| D8 | Lehetnek-e checkpoint commitok a nagy átalakítás előtt? | Igen, lokális branchen, push nélkül. |

## 8. Kockázatok

| Kockázat | Hatás | Mitigáció |
|---|---|---|
| Alpha API-változás (a kéthetes alpha-kiadásokban gyakori az átnevezés és az overload-törlés) | fordítási törés, újramunka | pin, design-system réteg, kapunkénti frissítés, screenshot-diff |
| AGP 9.0.1 pin (issuetracker 491718901) | a B-út blokkolva | spike; tartalék az A-út |
| Compose alpha behúzása (az alpha28-tól Compose 1.13 alpha) | instabil alap | a B-n alpha25 körüli plafon |
| Jegyzetszerkesztő (HTML-mezők, IME, média) | a legnagyobb ütemterv-kockázat | D3: átmeneti host, szakaszos natív csere |
| A WebView-tokenhíd törékenysége (a backend bundle CSS-e változhat, és felülírja a `<html>` osztályait) | inkonzisztens oldalak backend-frissítéskor | verziózott híd, `:root:root` specificitás, oldal-screenshotok, a backend-verzióhoz kötve |
| Kártyasablon-hűség (`!important`-override-ok) | sérül az Anki-paritás | az F2-ben javítva |
| Lokalizáció (123+ literál, 71 fordítatlan kulcs, az upstreamhez kötött Crowdin) | kiadás-blokkoló | lint-kikényszerítés, D5 |
| Akadálymentesség (szerep nélküli vezérlők, fix magasságok, csak long-press műveletek) | kiadás-blokkoló | szabványos M3 komponensek, szemantikai tesztek, TalkBack-checklist |
| Teljesítmény (Compose, WebView-újrahasznosítás) | akadozás, lassú indulás | baseline profile (a plugin már megvan), macrobenchmark |
| Hatókör-kúszás (M3E vs. paritás) | csúszás | kapuk, DoD, fázisonkénti review |
| Commit nélküli worktree | munkavesztés | D8: helyi checkpoint commitok |

## 9. Következő lépések

1. ~~**F0:** a Task 13 lezárása~~ – kész.
2. ~~**Döntések:** D0–D8~~ – elfogadva: D0 igen, D1 az A-út, D8 igen; a D2–D7 a javaslat szerint.
3. ~~**F1:** toolchain és design-system alap~~ – kész.
4. **F2:** a tanulás képernyő vertikális szelete tulajdonosi jóváhagyásra (G1): `ButtonGroup` válaszgombok, shape-morph „Show answer”, `LoadingIndicator`, lebegő eszköztár, `MotionScheme`-átmenetek, haptika, a kártyasablon `!important`-override-jainak megszüntetése, Expressive lint-szabályok és screenshot-mátrix, a DoD élő ellenőrzésével.
5. **A B-út spike-ja** a G1 után, az F3 széles migrációja előtt.

## Források

- [Compose Material 3 kiadási jegyzetek](https://developer.android.com/jetpack/androidx/releases/compose-material3): az Expressive API-k graduálása és változásai.
- [Material Components for Android – Getting started](https://github.com/material-components/material-components-android/blob/master/docs/getting-started.md): az MDC Views karbantartási módja.
- [Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3): az M3 Expressive áttekintése.
- [Google Maven](https://dl.google.com/android/maven2/) (közvetlen lekérdezés): a verziólisták, az AAR `minCompileSdk`/`minAndroidGradlePluginVersion` metaadatai és a BOM-leképezések.

A külső forrásokból származó tartalmat a licencfeltételek miatt átfogalmaztam.
