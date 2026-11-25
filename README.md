# Noir Kamera

Aparat na Androida, który robi zdjęcia jak kamera z lat 30. i 40.: głębokie czernie, jasne biele, dużo odcieni szarości pomiędzy, twarde światło i ziarno prawdziwej kliszy.

To nie jest filtr nakładany na gotowe zdjęcie. Aplikacja:

- **naprawdę naświetla matrycę jak stary aparat** (tryb **KLISZA**): czułość bierze się z wybranej kliszy (ISO 25, 100, 800…), a czas migawki ustawiasz sam (od 1/1000 do 1 s). Automatyka jest wyłączona. Przy długich czasach poruszone ujęcia są prawdziwe, nie symulowane. Światłomierz u góry pokazuje, czy kadr jest dobrze naświetlony;
- **przelicza światło tak jak emulsja**: każda klisza ma własną czułość na kolory (ortochromatyczna z 1927 r. prawie nie widzi czerwieni, a filtr czerwony z lat 40. robi czarne niebo), własną krzywą charakterystyczną (stopa → prosta → ramię), halację wokół świateł i ziarno zależne od gęstości;
- **używa AI** (ML Kit, działa w telefonie, bez internetu), żeby oddzielić postać od tła. Tło zapada się w ciemność jak w film noir, a twarz zostaje w świetle;
- **pokazuje wszystko na żywo** w podglądzie, a zdjęcie „wywołuje” w pełnej rozdzielczości tym samym procesem.

## Instalacja na telefonie

1. Na telefonie otwórz: **Releases → najnowsza wersja → `NoirKamera.apk`**
   (bezpośredni link: `https://github.com/rirmowy-droid/noir-kamera/releases/latest/download/NoirKamera.apk`).
2. Pozwól przeglądarce instalować aplikacje z nieznanych źródeł (Android sam o to zapyta).
3. Zainstaluj i zezwól na dostęp do aparatu.

Każda zmiana w kodzie wysłana na GitHub sama buduje nową wersję (zakładka **Actions**).
Uwaga: każda wersja jest podpisana innym, tymczasowym kluczem. Przed instalacją nowej wersji odinstaluj starą (zdjęcia w Galerii zostają).

## Obsługa

- **Dotknij nazwy kliszy** u góry, żeby ją zmienić.
- **Tryb AUTO / KLISZA**: w trybie KLISZA ustawiasz czas migawki (dotknięcie: krótszy/dłuższy po kolei, przytrzymanie: w drugą stronę) i ISO.
- **Ustawienia**: kontrast, ziarno, poświata, winieta, korekta ekspozycji, wywołanie push/pull, siła AI i efekt starej taśmy.
- Zdjęcia trafiają do **Galeria → Pictures/NoirKamera**.

Najlepszy efekt daje **jedno twarde źródło światła z boku** (lampa, latarka, słońce przez żaluzje) i brak doświetlenia z drugiej strony.

## Budowa

- `Shaders.kt`: cała „chemia” kliszy (GLSL na karcie graficznej)
- `FilmStock.kt`: definicje klisz, które łatwo zmieniać i dodawać
- `NoirRenderer.kt`: podgląd na żywo i wywołanie pełnej rozdzielczości
- `MainActivity.kt`: kamera (CameraX + ręczne sterowanie Camera2), AI, interfejs

Wymaga Androida 10 lub nowszego.
