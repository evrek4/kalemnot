# KalemNot

Öğrenciler için kalem destekli Android not uygulaması (telefon ve tablet).

## Özellikler
- **Kalem, fosforlu kalem, silgi, metin** araçları; kalem basıncına duyarlı çizgi kalınlığı
- Kalem algılanınca **avuç içi reddi**: parmak kaydırır/yakınlaştırır, kalem yazar (el simgesiyle değiştirilebilir)
- Çizgili / kareli / noktalı / boş sayfalar, sınırsız sayfa, geri al / yinele
- **PDF ve görsel içe aktarma**: ders PDF'inin üstüne yaz-çiz. Dosyayı uygulamaya **sürükleyip bırakabilir**
  (bölünmüş ekran), paylaş menüsünden gönderebilir veya "Birlikte aç" ile açabilirsin
- **Ses kaydı**: ders sırasında kayıt al, notun içinde dinle
- **PDF olarak dışa aktar / paylaş** (çizimlerle birlikte)
- **Yedekleme**: tüm notlar tek zip dosyası olarak Google Drive'a (veya herhangi bir konuma) yedeklenir, geri yüklenir
- Koyu tema, internet izni gerektirmez

## Derleme
GitHub Actions her `main` push'unda APK üretir:
**Actions → son çalışma → Artifacts → KalemNot-apk**, ya da **Releases** sayfasındaki `KalemNot.apk`.

Yerelde: Android Studio ile aç ve çalıştır (JDK 17).

## Yedekleme nasıl çalışır?
Uygulama Google Drive API'si yerine Android'in dosya seçicisini kullanır; bu yüzden anahtar/hesap ayarı gerekmez.
Yedekle → "Yeni yedek dosyası oluştur" → açılan pencerede **Drive**'ı seç. Sonraki yedeklemeler aynı dosyanın üstüne yazılır.
