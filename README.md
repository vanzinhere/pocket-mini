# Pocklet Companion (Android)

Source project untuk PRD Pocklet Companion. Minimum Android 12 (API 31), target API 35.

## Cara build APK
1. Buka folder `PockletCompanion` di Android Studio (Ladybug/Koala atau lebih baru).
2. Biarkan Gradle sync selesai. Jika diminta membuat Gradle wrapper, setujui.
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. Hasil debug: `app/build/outputs/apk/debug/app-debug.apk`.
5. Untuk APK release, buat signing key lewat Build > Generate Signed App Bundle / APK.

## Setelah install
1. Buka aplikasi, tekan "Buka pengaturan akses" dan aktifkan Pocklet Companion di akses notifikasi.
2. Centang aplikasi yang notifikasinya mau diteruskan.
3. Masukkan API key Ollama, tekan "Muat model", lalu pilih model.
4. Tekan "Mulai layanan", salin alamat HP dan token ke `Config.h` firmware.
5. Tekan "Minta pengecualian baterai" dan ikuti petunjuk merek HP.

## Catatan
- Endpoint daftar model memakai `https://ollama.com/api/tags`. Jika respons akun Anda berbeda, sesuaikan `Ollama.listModels`.
- `usesCleartextTraffic` aktif agar tombol "Tes koneksi" bisa memanggil `/health` lewat HTTP lokal.
- Belum diuji di perangkat. Perilaku background di Android 15 dan 16 perlu diuji terpisah sesuai PRD.
