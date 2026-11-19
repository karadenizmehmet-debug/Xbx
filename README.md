# Altyazı

Xbox oyunlarındaki İngilizce altyazıyı Türkçeye çeviren telefon uygulaması.

Çalışma şekli:

1. Xbox, HDMI ile TV'de çalışır. Telefon aynı anda Remote Play ile görüntünün bir kopyasını alır. İki bağlantı modu var:
   - **Uygulama içinde bağlan (varsayılan):** play.xbox.com uygulamanın içinde açılır. Microsoft hesabına bir kez, Microsoft'un kendi sayfasında giriş yapılır; uygulama şifreyi görmez, yalnızca oturum çerezleri saklanır. Altyazı bölgesi doğrudan video karesinden okunur.
   - **Xbox uygulamasıyla (ekran kaydı):** Xbox uygulamasında Remote Play açılır, uygulama ekranı kaydedip okur (yedek yol).
2. Uygulama, seçilen altyazı bölgesini okur (ML Kit OCR).
3. Metni cihazda İngilizceden Türkçeye çevirir (ML Kit Translate, internetsiz).
4. Çeviriyi Wi-Fi üzerinden TV uygulamasına gönderir: `POST http://<tv-ip>:8765/sub`, gövde `{"text": "...", "orijinal": "..."}`. Boş `text` altyazıyı ekrandan kaldırır.

## Kurulum

[Releases](../../releases) sayfasından `Altyazi-vX.Y.Z.apk` dosyasını indirip kur. Her push'ta GitHub Actions APK'yı yeniden derler.

## Gereken izinler

Uygulama içi modda ek izin gerekmez. Ekran kaydı modunda:

- Diğer uygulamaların üzerinde gösterme: kontrol çubuğu ve bölge seçimi için.
- Ekran kaydı: Remote Play görüntüsünü okumak için.
- Bildirim: arka planda çalışırken gösterilen bildirim için.

© Mehmet Karadeniz, 2026-2027
