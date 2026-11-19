// Altyazı – play.xbox.com sayfasına enjekte edilir.
// Remote Play videosundan altyazı bölgesini kırpıp JPEG olarak AltyaziKopru'ya (yalnızca www.xbox.com) verir.
// © Mehmet Karadeniz, 2026-2027
(function () {
  if (window.__altyazi) return;
  var K = window.AltyaziKopru;
  if (!K) return;

  function gonder(m) { try { K.postMessage(JSON.stringify(m)); } catch (e) { } }

  // Okunacak alanlar: id → [sol, üst, sağ, alt] (video oranı) ve açık/kapalı
  var bolgeler = { altyazi: [0.10, 0.72, 0.90, 0.95] };
  var aktif = { altyazi: true };
  var aralikMs = 450;
  var sesAcik = true;
  var calisiyor = true;
  var tuval = document.createElement('canvas');
  var ctx = tuval.getContext('2d');
  var sonAlan = '';
  var sonGoruntu = Date.now();
  var temizlendi = true;
  var hataBildirildi = false;

  var sonDerinArama = 0;
  // Gölge DOM (shadow root) içindeki videoları da bul
  function tumVideolar() {
    var liste = Array.prototype.slice.call(document.getElementsByTagName('video'));
    if (liste.length) return liste;
    if (Date.now() - sonDerinArama < 2000) return liste;   // pahalı; 2 sn'de bir
    sonDerinArama = Date.now();
    var yigin = [document];
    while (yigin.length) {
      var kok = yigin.pop();
      var hepsi = kok.querySelectorAll('*');
      for (var i = 0; i < hepsi.length; i++) {
        var e = hepsi[i];
        if (e.tagName === 'VIDEO') liste.push(e);
        if (e.shadowRoot) yigin.push(e.shadowRoot);
      }
    }
    return liste;
  }

  // En büyük, oynayan video (Remote Play bağlantısı yenilenince yeni öğe gelir; her turda yeniden ara)
  var sonListe = [];
  function videoBul() {
    var en = null, enAlan = 0;
    var liste = tumVideolar();
    sonListe = liste;
    for (var i = 0; i < liste.length; i++) {
      var v = liste[i];
      if (!v.videoWidth || !v.videoHeight) continue;
      var r = v.getBoundingClientRect();
      var a = r.width * r.height;
      if (a > enAlan) { en = v; enAlan = a; }
    }
    return en;
  }

  // object-fit: contain ile videonun ekrandaki gerçek içerik alanı (fiziksel piksel)
  function icerikAlani(v) {
    var r = v.getBoundingClientRect();
    var olcek = Math.min(r.width / v.videoWidth, r.height / v.videoHeight);
    var w = v.videoWidth * olcek, h = v.videoHeight * olcek;
    var x = r.left + (r.width - w) / 2, y = r.top + (r.height - h) / 2;
    var d = window.devicePixelRatio || 1;
    return [x * d, y * d, (x + w) * d, (y + h) * d];
  }

  // Görüntü 1,5 sn'den uzun kesilirse ekrandaki çeviriyi sil
  function goruntuYok() {
    if (!temizlendi && Date.now() - sonGoruntu > 1500) { temizlendi = true; gonder({ t: 'temizle' }); }
  }

  // Ses: Remote Play sesi ayrı bir <audio> öğesinden ya da WebAudio'dan gelebilir.
  // Tüm medya öğelerini sustur ve akışlardaki ses izlerini kapat (WebAudio'ya giden ses de kesilir).
  function sesUygula() {
    var liste = document.querySelectorAll('audio,video');
    for (var i = 0; i < liste.length; i++) {
      var e = liste[i];
      if (e.muted === sesAcik) e.muted = !sesAcik;
      var akis = e.srcObject;
      if (akis && akis.getAudioTracks) {
        var izler = akis.getAudioTracks();
        for (var j = 0; j < izler.length; j++) if (izler[j].enabled !== sesAcik) izler[j].enabled = sesAcik;
      }
    }
  }

  // Kontrol et için 2 sn'de bir durum bildir
  var sonDurum = 0;
  function durumBildir(v) {
    if (Date.now() - sonDurum < 2000) return;
    sonDurum = Date.now();
    gonder({ t: 'durum', n: sonListe.length, w: v ? v.videoWidth : 0, h: v ? v.videoHeight : 0, p: v ? v.paused : false });
  }

  function tur() {
    setTimeout(tur, Math.max(200, aralikMs));
    if (!calisiyor) return;
    var v = videoBul();
    durumBildir(v);
    if (!v) { goruntuYok(); return; }
    sesUygula();
    if (v.paused) { goruntuYok(); return; }
    sonGoruntu = Date.now(); temizlendi = false;

    var alan = icerikAlani(v);
    var anahtar = alan.map(Math.round).join(',');
    if (anahtar !== sonAlan) { sonAlan = anahtar; gonder({ t: 'video', a: alan }); }

    for (var id in bolgeler) {
      if (aktif[id]) kirpVeGonder(v, id, bolgeler[id]);
    }
  }

  // Bölgeyi kırp; küçük bölgeleri 2 katına büyüt (telefon yazıları küçük, OCR daha iyi okur)
  function kirpVeGonder(v, id, b) {
    var sx = Math.round(b[0] * v.videoWidth), sy = Math.round(b[1] * v.videoHeight);
    var sw = Math.round((b[2] - b[0]) * v.videoWidth), sh = Math.round((b[3] - b[1]) * v.videoHeight);
    if (sw < 8 || sh < 8) return;
    // Küçük yazılar (kumanda ipuçları) için 3 kata kadar büyüt
    var olcek = id === 'altyazi' ? Math.max(1, Math.min(2, 1400 / sw)) : Math.max(1, Math.min(3, 1600 / sw));
    tuval.width = Math.round(sw * olcek); tuval.height = Math.round(sh * olcek);
    try {
      ctx.drawImage(v, sx, sy, sw, sh, 0, 0, tuval.width, tuval.height);
      var veri = tuval.toDataURL('image/jpeg', id === 'altyazi' ? 0.92 : 0.93);
      gonder({ t: 'kare', a: id, d: veri.substring(veri.indexOf(',') + 1) });
    } catch (e) {
      // Kare henüz hazır değilse sessiz geç; güvenlik engeli (SecurityError) bir kez bildirilir
      if (e && e.name === 'SecurityError' && !hataBildirildi) {
        hataBildirildi = true;
        gonder({ t: 'hata', m: 'video karesi korumalı (' + e.name + ')' });
      }
    }
  }

  window.__altyazi = {
    bolge: function (id, l, t, r, b) { bolgeler[id] = [l, t, r, b]; },
    aktif: function (id, acik) { aktif[id] = !!acik; },
    ses: function (acik) { sesAcik = !!acik; sesUygula(); },
    aralik: function (ms) { aralikMs = ms; },
    dur: function () { calisiyor = false; },
    devam: function () { calisiyor = true; sonAlan = ''; }
  };
  gonder({ t: 'yuklendi' });
  tur();
})();
