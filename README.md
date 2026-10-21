# Koza Zahradník — Android

Plánovač zahrady jako jedna HTML stránka zabalená do WebView. Žádné npm, žádný
Capacitor, jen Gradle a Android SDK.

## Sestavení

```bash
cd koza-zahradnik-android
gradle wrapper          # jen poprvé, doplní gradlew
./gradlew assembleDebug
```

APK najdeš v `app/build/outputs/apk/debug/app-debug.apk`.

Pokud nechceš wrapper, stačí `gradle assembleDebug` tvou nainstalovanou
Gradlí. Verzi Android Gradle Pluginu v kořenovém `build.gradle` si uprav
podle toho, co máš — teď je tam 8.5.2, která chce Gradle 8.7+ a JDK 17.

Podepsaná verze pro obchod:

```bash
./gradlew assembleRelease
```

a doplň si `signingConfigs` v `app/build.gradle`.

## Co kde je

```
app/src/main/
├── assets/www/index.html    ← celá aplikace, jeden soubor
├── java/.../MainActivity.java
├── res/mipmap-*/            ← ikony (kozel)
└── AndroidManifest.xml
```

Aktualizace aplikace = přepsat `assets/www/index.html`. Nic jiného.

## Proč tam je nativní kód

**Assety přes `WebViewAssetLoader`, ne přes `file://`.** Stránka se načítá
z `https://appassets.androidplatform.net/assets/www/index.html`. Je to kvůli
`localStorage` — na `file://` origin ho WebView neukládá spolehlivě a celá
uložená zahrada by se ztrácela.

**Dotazy na katastr přes `KozaNative.get()`.** Služba ČÚZK neposílá hlavičky
CORS, takže `fetch()` z WebView by na ni nedosáhl. Nativní `HttpURLConnection`
žádné CORS neřeší. Stránka si most sama najde:

```js
if (window.KozaNative && typeof window.KozaNative.get === 'function') { ... }
else { fetch(...) }                       // v obyčejném prohlížeči
```

Díky tomu je `index.html` tentýž soubor pro appku i pro web.

## Odkud berou data

**Katastr — ČÚZK, INSPIRE WFS, bez klíče a bez registrace**

```
https://services.cuzk.cz/wfs/inspire-cp-wfs.asp
  ?service=WFS&version=2.0.0&request=GetFeature
  &typenames=CadastralParcel
  &srsName=http://www.opengis.net/def/crs/EPSG/0/4326
  &BBOX=minLat,minLon,maxLat,maxLon,http://www.opengis.net/def/crs/EPSG/0/4326
```

Vrátí GML s parcelami v okolí. Aplikace vybere tu, ve které zadaný bod leží,
a přepočte ji na metry. Pořadí os si ověřuje podle rozsahu ČR (šířka 48–52,
délka 12–19), takže je jedno, jestli služba vrátí `lat lon` nebo `lon lat`.

**Adresa na souřadnice — Nominatim (OpenStreetMap)**

Má v sobě importovaná adresní místa z RÚIAN. Vyžaduje vlastní `User-Agent`
(nastavený v `MainActivity`) a max. jeden dotaz za sekundu. Na občasné
hledání parcely to stačí. Při větším provozu si stáhni adresní data RÚIAN
z veřejného dálkového přístupu ČÚZK a geokóduj lokálně.

**Počasí — Open-Meteo**

Bez API klíče, zdarma do 10 000 dotazů denně, data CC BY 4.0. Součástí je
i geokódování obcí. Aplikace bere denní maxima, minima, pravděpodobnost
srážek a hlavně **srážky v milimetrech**, podle kterých hlídač pozná, jestli
má smysl zalévat.

Licence žádá uvedení zdroje — v aplikaci je Open-Meteo zmíněné na obrazovce
počasí, nech to tam.

## Co ještě dodělat

- **Podepsat** release build vlastním klíčem.
- **Upravit `User-Agent`** v `MainActivity.java` — doplň si kontaktní e-mail,
  Nominatim to ve svých podmínkách chce.
- **Notifikace** — hlídač zálivky zatím jen zobrazuje. Na upozornění do
  telefonu bude potřeba `WorkManager` s periodickou kontrolou předpovědi.
- **Zálohování** — `allowBackup` je zapnuté, ale data jsou v `localStorage`.
  Pokud chceš export zahrady do souboru, nabízí se přidat do mostu metodu
  pro zápis JSONu do `Downloads`.
- **Podkladová mapa** — ČÚZK má kromě WFS i WMS a WMTS s katastrální mapou.
  Dala by se dát pod půdorys jako podklad při kreslení.

## Otestováno a neotestováno

Logika aplikace je ověřená: parsování GML z katastru, přepočet na metry,
výběr správné parcely, zpracování odpovědi z Open-Meteo i chování, když
síť není dostupná.

**Nesestaveno.** Projekt jsem skládal bez Android SDK, takže Gradle build
neproběhl. Počítej s tím, že první `assembleDebug` si možná vyžádá úpravu
verze AGP nebo `compileSdk` podle toho, co máš nainstalované.

**Živé volání na ČÚZK a Open-Meteo neověřeno.** Na oba servery jsem se
z prostředí, kde jsem projekt psal, nedostal. URL jsou poskládané podle
dokumentace ČÚZK a Open-Meteo. Kdyby něco nehrálo, první místo, kam se
podívat, jsou názvy parametrů — a `MainActivity.Most.get()` vrací chybu
jako `KOZA_ERR:...`, takže ji uvidíš přímo v aplikaci.
