# Uygulama İçinde Xbox Bağlantısı – Uygulama Planı

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** play.xbox.com'u uygulama içi WebView'da açıp altyazıyı video karesinden okumak; eski ekran kaydı yolu yedek olarak kalır.

**Architecture:** OCR→kararlılık→çeviri→TV hattı `CaptureService`'ten `AltyaziHatti` sınıfına çıkarılır; saf metin/kararlılık mantığı `MetinIsleme.kt`'ye taşınır ve JVM birim testleriyle korunur. Yeni `UzaktanOynatActivity` WebView'daki `video` öğesinden JS ile kırpılmış JPEG kareleri `AltyaziHatti`'na verir.

**Tech Stack:** Kotlin, Android SDK 35 (minSdk 30), WebView, ML Kit text-recognition/translate, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-19-uygulama-ici-baglanti-design.md`

## Global Constraints

- Yerelde Android SDK yok: derleme ve birim testi GitHub Actions'ta (`.github/workflows/apk.yml`) doğrulanır. `main`'e push yeni Release yayınlar; bu yüzden `main`'e yalnızca son görevde, sürüm 0.10.0 ile push edilir. Ara görevler `uygulama-ici` dalına push edilip `workflow_dispatch` ile derlenir.
- Sürüm: `versionCode = 10`, `versionName = "0.10.0"`.
- Şifre uygulama tarafından okunmaz/saklanmaz; giriş yalnızca Microsoft sayfasında, uygulama sadece çerezleri tutar. Giriş sayfalarına (`login.live.com`, `login.microsoftonline.com`) JS enjekte edilmez.
- Tarayıcı kimliği: WebView varsayılan UA'sından `; wv` çıkarılır (Chrome mobil gibi görünür).
- Tüm arayüz metinleri Türkçe. Telif satırı: `© Mehmet Karadeniz, 2026-2027`.
- Eski ekran kaydı modu davranışı değişmez.

## Review Focus

1. Giriş yapılmamış/oturum süresi dolmuş: video yokken JS kare göndermez, hat boşta kalır; çökme olmaz.
2. Remote Play bağlantısı koptu/video yeniden oluştu: JS her turda `video`'yu yeniden arar, eski referansa takılmaz.
3. Video henüz oynamıyor (`videoWidth == 0`): kare gönderilmez.
4. Activity arka plana alınır/döndürülür: kare döngüsü durur, dönüşte devam eder; WebView yeniden yüklenmez (`configChanges`).
5. Seçilen bölge videonun siyah şerit (letterbox) alanına taşar: oranlar 0..1'e kırpılır, çok küçük bölge (<8 px) atlanır.

---

### Task 1: Saf metin ve kararlılık mantığını çıkar

**Files:**
- Create: `app/src/main/java/com/karadeniz/altyazi/MetinIsleme.kt`
- Create: `app/src/test/java/com/karadeniz/altyazi/MetinIslemeTest.kt`
- Modify: `app/src/main/java/com/karadeniz/altyazi/CaptureService.kt` (`normalize`, `benzerlik`, `temizle` içindeki parça temizleme, `metinGeldi` durum değişkenleri)
- Modify: `app/build.gradle.kts` (`testImplementation("junit:junit:4.13.2")`)
- Modify: `.github/workflows/apk.yml` (derlemeden önce `gradle :app:testDebugUnitTest --no-daemon`)

**Interfaces:**
- Produces:
  - `object MetinIsleme { fun normalize(s: String): String; fun benzerlik(a: String, b: String): Double; fun parcalariTemizle(satirlar: List<String>): String }`
  - `sealed class Karar { object Bekle : Karar(); object Temizle : Karar(); data class Cevir(val metin: String) : Karar() }`
  - `class KararlilikTakip(private val bosTemizlemeMs: Long = 1300L) { fun gelen(metin: String, aralikMs: Long): Karar; fun ceviriGosterildi() }`

- [ ] **Step 1: Testleri yaz** (`MetinIslemeTest`)
  - `normalize("Hello, World!") == "helloworld"`
  - `benzerlik("abc","abc") == 1.0`, `benzerlik("","x") == 0.0`
  - `parcalariTemizle(listOf("B Where are you going? A")) == "Where are you going?"`; `parcalariTemizle(listOf("X Run!")) == "Run!"`; `parcalariTemizle(listOf("I")) == ""` (tek harfli satır filtrelenir: harf sayısı < 2)
  - `KararlilikTakip`: aynı metin 1. kez → `Bekle`, 2. kez → `Cevir(metin)`, 3. kez → `Bekle`; `ceviriGosterildi()` sonrası boş metin 400 ms aralıkla: 1.–3. çağrı `Bekle`, 4. çağrı (1600 ms ≥ 1300) `Temizle`, 5. çağrı `Bekle`; `ceviriGosterildi()` çağrılmadan boş metin hiç `Temizle` vermez.
- [ ] **Step 2: `MetinIsleme.kt`'yi yaz.** Gövdeler `CaptureService`'teki mevcut mantığın birebir taşınmasıdır (eşikler 0.82 / 0.9 / 2 kare / harf ≥ 2 / baştaki `B X Y` / sondaki tek karakter, `I` hariç). Kaplama alanı filtresi (kutu/bar) `CaptureService`'te kalır; `parcalariTemizle`'ye filtrelenmiş satır metinleri gelir.
- [ ] **Step 3: `CaptureService`'i yeni fonksiyonları kullanacak şekilde değiştir;** davranış aynı.
- [ ] **Step 4:** `uygulama-ici` dalına push, `gh api repos/karadenizmehmet-debug/xbx/actions/workflows/apk.yml/dispatches -f ref=uygulama-ici`. Beklenen: iş başarılı, testler geçer.
- [ ] **Step 5: Commit** – `refactor: metin ve kararlılık mantığını MetinIsleme'ye taşı`

### Task 2: `AltyaziHatti` – ortak OCR/çeviri/TV hattı

**Files:**
- Create: `app/src/main/java/com/karadeniz/altyazi/AltyaziHatti.kt`
- Modify: `app/src/main/java/com/karadeniz/altyazi/CaptureService.kt` (ML Kit kurulumu, `kareIsle`'nin OCR kısmı, `metinGeldi`, `yerelCevir`, `ceviriBitti`, TV gönderimi hatta taşınır; kaplama güncellemesi `CaptureService`'te kalır)

**Interfaces:**
- Consumes: Task 1'deki `MetinIsleme`, `KararlilikTakip`, `Karar`.
- Produces:
  - `class AltyaziHatti(context: Context, ayar: Ayarlar, gosterildi: (tr: String, orijinal: String) -> Unit, durum: (String) -> Unit)`
  - `fun baslat()` (iş parçacığı, ML Kit tanıyıcı + EN→TR modeli indirme; mevcut durum mesajları korunur)
  - `fun isle(kirpik: Bitmap, atla: (x: Int, y: Int) -> Boolean = { _, _ -> false })` – `mesgul` veya model hazır değilse bitmap'i geri dönüştürüp döner; `atla` OCR satır merkezleri (kırpık koordinatında) için kaplama filtresi.
  - `val hazir: Boolean` (model hazır ve meşgul değil)
  - `fun kapat()`
  - TV'ye gönderim ve `Durum.sonCeviriDinleyici` çağrısı hattın içinde; `gosterildi` ana iş parçacığında çağrılır.
- [ ] **Step 1:** Hattı yaz, `CaptureService`'i ona bağla (`atla` = mevcut `kutuAlani`/`barAlani` + `kirpX/kirpY` kontrolü).
- [ ] **Step 2:** Dalda derle (Task 1 Step 4 komutu). Beklenen: başarılı.
- [ ] **Step 3:** Telefonda eski mod el testi: Xbox uygulamasıyla altyazı okunur, TV'ye gider (Mehmet).
- [ ] **Step 4: Commit** – `refactor: OCR/çeviri/TV hattını AltyaziHatti'na çıkar`

### Task 3: `UzaktanOynatActivity` – gömülü Remote Play

**Files:**
- Create: `app/src/main/java/com/karadeniz/altyazi/UzaktanOynatActivity.kt`
- Create: `app/src/main/assets/kare.js`
- Create: `app/src/test/java/com/karadeniz/altyazi/VideoOranTest.kt`
- Modify: `app/src/main/java/com/karadeniz/altyazi/MetinIsleme.kt` (oran fonksiyonu)
- Modify: `app/src/main/java/com/karadeniz/altyazi/Ayarlar.kt` (`webBolge`, `webSes`)
- Modify: `app/src/main/java/com/karadeniz/altyazi/BolgeSecici.kt` (pencere tipi parametresi)
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `AltyaziHatti` (Task 2).
- Produces:
  - `fun ekrandanVideoya(secim: RectF, videoAlani: RectF): RectF` (`MetinIsleme` içinde; ekran pikseli → video oranı, 0..1'e kırpılır)
  - `Ayarlar.webBolge: RectF` (varsayılan `0.10, 0.72, 0.90, 0.95`, anahtarlar `wl/wt/wr/wb`), `Ayarlar.webSes: Boolean` (varsayılan `true`)
  - `BolgeSecici(..., pencereTipi: Int = TYPE_APPLICATION_OVERLAY)` – activity içinden `TYPE_APPLICATION` ile çağrılır.
  - JS köprüsü adı `AltyaziKopru`: `@JavascriptInterface fun kare(base64Jpeg: String)`, `@JavascriptInterface fun video(sol: Float, ust: Float, sag: Float, alt: Float)` (videonun ekrandaki içerik alanı, CSS px × `devicePixelRatio`).
  - JS tarafı: `window.__altyazi = { bolge(l,t,r,b), ses(acik), dur(), devam() }`.

- [ ] **Step 1: Test yaz** (`VideoOranTest`): `videoAlani = RectF(0,100,1000,662.5)` için `ekrandanVideoya(RectF(100,100,900,662.5), videoAlani) == RectF(0.1f,0f,0.9f,1f)`; video dışına taşan seçim `0..1`'e kırpılır.
- [ ] **Step 2:** `ekrandanVideoya`, `Ayarlar` alanları, `BolgeSecici` parametresi.
- [ ] **Step 3: `kare.js`:** her `ayar.aralikMs`'de (köprüden okunur) en büyük oynayan `video`'yu yeniden bul; `videoWidth == 0` veya duraklatıldıysa atla; `object-fit: contain` hesabıyla içerik alanını `video(...)` ile bildir; bölgeyi `drawImage` ile canvas'a kırp, `toDataURL('image/jpeg', 0.85)` → `kare(...)`; kırpık < 8 px ise atla. Ses: `video.muted = !acik`.
- [ ] **Step 4: Activity:** tam ekran yatay, `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`, `FLAG_KEEP_SCREEN_ON`. WebView: `javaScriptEnabled`, `domStorageEnabled`, `mediaPlaybackRequiresUserGesture=false`, `CookieManager` kalıcı + `setAcceptThirdPartyCookies`, UA'dan `; wv` sil. Açılış URL'si `https://www.xbox.com/tr-TR/play`. `onPageFinished`'de host `xbox.com` ile bitiyorsa `kare.js` enjekte et; giriş hostlarına enjekte etme. `onPause` → `__altyazi.dur()` ve `CookieManager.flush()`, `onResume` → `devam()`. Geri tuşu: WebView geçmişi varsa geri, yoksa kapat. Base64 → `BitmapFactory` → `hat.isle(bmp)` (hat meşgulse kare atılır).
- [ ] **Step 5: Üst çubuk** (yarı saydam, dokununca 4 sn görünür): "Bölge" (`BolgeSecici` → `ekrandanVideoya` → `ayar.webBolge` → `__altyazi.bolge`), "Ses telefondan / Ses kapalı" (`ayar.webSes`), "Durdur" (`finish()`). `ayar.telefondaGoster` açıksa çeviri video üstünde `kutu` alanında gösterilir (`stilUygula` mantığıyla aynı yazı tipi/renk).
- [ ] **Step 6:** Manifest'e activity ekle. Dalda derle; testler geçer.
- [ ] **Step 7:** Telefonda el testi (Mehmet): giriş → konsola bağlan → bölge seç → çeviri TV'de; uygulamayı kapatıp açınca tekrar giriş istemez.
- [ ] **Step 8: Commit** – `feat: uygulama içi Remote Play (WebView) modu`

### Task 4: Ana ekranda mod seçimi ve sürüm

**Files:**
- Modify: `app/src/main/java/com/karadeniz/altyazi/MainActivity.kt` (`durumKarti`, `baslatDurdur`)
- Modify: `app/src/main/java/com/karadeniz/altyazi/Ayarlar.kt` (`mod: String`, `"web"` | `"ekran"`, varsayılan `"web"`)
- Modify: `app/build.gradle.kts` (sürüm), `README.md`

**Interfaces:**
- Consumes: `UzaktanOynatActivity` (Task 3), `Ayarlar.mod`.

- [ ] **Step 1:** Başlat kartına `pillGrubu` ile iki seçenek: "Uygulama içinde bağlan" (`web`), "Xbox uygulamasıyla (ekran kaydı)" (`ekran`). `web` modunda Başlat → `UzaktanOynatActivity`; overlay/ekran kaydı izni istenmez. `ekran` modu bugünkü akış.
- [ ] **Step 2:** `versionCode = 10`, `versionName = "0.10.0"`; README "Çalışma şekli" ve "Gereken izinler"e iki mod.
- [ ] **Step 3:** Dalda derle. `main`'e merge + push → Release `v0.10.0`. Beklenen: Release'de `Altyazi-v0.10.0.apk`, `AltyaziTV-v0.10.0.apk`.
- [ ] **Step 4: Commit** – `feat: ana ekranda bağlantı modu seçimi, v0.10.0`
