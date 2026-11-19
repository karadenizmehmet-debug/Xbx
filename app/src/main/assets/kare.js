// Altyazı – play.xbox.com sayfasına enjekte edilir.
// Remote Play videosundan altyazı bölgesini kırpıp JPEG olarak AltyaziKopru'ya verir.
// © Mehmet Karadeniz, 2026-2027
(function () {
  if (window.__altyazi) return;
  var K = window.AltyaziKopru;
  if (!K) return;

  var bolge = [0.10, 0.72, 0.90, 0.95];
  var sesAcik = true;
  var calisiyor = true;
  var tuval = document.createElement('canvas');
  var ctx = tuval.getContext('2d');
  var sonAlan = '';
  var zamanlayici = null;

  // En büyük, oynayan video (Remote Play bağlantısı yenilenince yeni öğe gelir; her turda yeniden ara)
  function videoBul() {
    var en = null, enAlan = 0;
    var liste = document.getElementsByTagName('video');
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

  function tur() {
    zamanlayici = setTimeout(tur, Math.max(200, K.aralik()));
    if (!calisiyor) return;
    var v = videoBul();
    if (!v) return;
    v.muted = !sesAcik;
    if (v.paused) return;

    var alan = icerikAlani(v);
    var anahtar = alan.map(Math.round).join(',');
    if (anahtar !== sonAlan) { sonAlan = anahtar; K.video(alan[0], alan[1], alan[2], alan[3]); }

    if (!K.hazir()) return;
    var sx = Math.round(bolge[0] * v.videoWidth), sy = Math.round(bolge[1] * v.videoHeight);
    var sw = Math.round((bolge[2] - bolge[0]) * v.videoWidth), sh = Math.round((bolge[3] - bolge[1]) * v.videoHeight);
    if (sw < 8 || sh < 8) return;
    tuval.width = sw; tuval.height = sh;
    try {
      ctx.drawImage(v, sx, sy, sw, sh, 0, 0, sw, sh);
      var veri = tuval.toDataURL('image/jpeg', 0.85);
      K.kare(veri.substring(veri.indexOf(',') + 1));
    } catch (e) { /* kare henüz hazır değil */ }
  }

  window.__altyazi = {
    bolge: function (l, t, r, b) { bolge = [l, t, r, b]; },
    ses: function (acik) { sesAcik = !!acik; var v = videoBul(); if (v) v.muted = !sesAcik; },
    dur: function () { calisiyor = false; },
    devam: function () { calisiyor = true; sonAlan = ''; }
  };
  K.yuklendi();
  tur();
})();
