# Uygulama içinde Xbox bağlantısı (kısa tasarım)

© Mehmet Karadeniz, 2026-2027

## Amaç
Dijitransio'daki gibi Remote Play'i Altyazı uygulamasının içinde açmak. Microsoft hesabına uygulama içinde bir kez giriş yapılır. Ayrı Xbox uygulaması ve ekran kaydı izni gerekmez.

## Hedefler / hedef olmayanlar
- Hedef: play.xbox.com uygulama içinde açılır, oturum saklanır, altyazı video karesinden okunur, çeviri TV'ye gider.
- Hedef: Eski yöntem (Xbox uygulaması + ekran kaydı) yedek mod olarak kalır.
- Hedef değil: Telefondan oyunu kontrol etmek (oyun TV'de kendi kumandasıyla oynanır).
- Başarısızlık sayılır: Şifreyi uygulamanın görmesi veya saklaması. Giriş yalnızca Microsoft'un kendi sayfasında yapılır, uygulama sadece çerezleri tutar.

## Yaklaşımlar
- A) Yalnızca gömülü mod, ekran kaydını kaldır. Basit ama Microsoft WebView'ı engellerse geri dönüş yok.
- B) Gömülü mod ana yol, ekran kaydı yedek. **Seçilen (Mehmet).** Sebep: Microsoft tarafı bizim kontrolümüzde değil.

## Tasarım
- Yeni `UzaktanOynatActivity`: tam ekran yatay WebView, `https://www.xbox.com/tr-TR/play` açılır. JavaScript, DOM storage, kalıcı ve üçüncü taraf çerezler açık. Kullanıcı jesti olmadan medya oynatılır. Tarayıcı kimliği, "wv" işareti olmayan Chrome mobil kimliği olur. Ekran açık kalır.
- Kare okuma: Sayfaya enjekte edilen JS, `video` öğesinden altyazı bölgesini canvas'a çizer. Bunu yaklaşık 400 ms'de bir JPEG olarak `addJavascriptInterface` köprüsüyle Kotlin'e verir. Bölge, video karesinin oranı olarak saklanır (`webBolge`). Ekran oranındaki eski `bolge` ayarından ayrıdır.
- Ortak hat: OCR, temizleme, çeviri ve TV'ye gönderme `CaptureService` içinden `AltyaziHatti` sınıfına taşınır. İki mod da bu sınıfa Bitmap verir. Davranış değişmez.
- Activity üst çubuğu: Bölge seç, Ses (telefondan / kapalı), Durdur. Türkçe çeviri isteğe bağlı olarak telefonda video üstünde de görünür.
- Ana ekran: Başlat kartında iki seçenek bulunur: "Uygulama içinde bağlan" (varsayılan) ve "Xbox uygulamasıyla (ekran kaydı)".
- Sürüm 0.10.0 olur.

## Kurucuya kalanlar
- JS'nin video öğesini bulma yöntemi ve bağlantı koptuğunda yeniden deneme.
- Desteklenmeyen tarayıcı uyarısı çıkarsa kimlik ayarı.
