# Noir Kamera

Aparat na Androida, który robi zdjęcia jak kamera z lat 30. i 40.: głębokie czernie, jasne biele, dużo odcieni szarości pomiędzy, twarde światło i ziarno prawdziwej kliszy.

To nie jest filtr nakładany na gotowe zdjęcie. Aplikacja:

- **naprawdę naświetla matrycę jak stary aparat** (tryb **KLISZA**): czułość bierze się z wybranej kliszy (ISO 25, 100, 800…), a czas migawki ustawiasz sam (od 1/1000 do 1 s). Automatyka jest wyłączona. Przy długich czasach poruszone ujęcia są prawdziwe, nie symulowane. Światłomierz u góry pokazuje, czy kadr jest dobrze naświetlony;
- **przelicza światło tak jak emulsja**: każda klisza ma własną czułość na kolory (ortochromatyczna z 1927 r. prawie nie widzi czerwieni, a filtr czerwony z lat 40. robi czarne niebo), własną krzywą charakterystyczną (stopa → prosta → ramię), halację wokół świateł i ziarno zależne od gęstości;
- **używa AI** (ML Kit, działa w telefonie, bez internetu), żeby oddzielić postać od tła. Tło zapada się w ciemność jak w film noir, a twarz zostaje w świetle;
- **ma tryb B (Bulb)**: migawka otwarta, dopóki trzymasz spust. Aplikacja sumuje światło kolejnych klatek, więc nocą świecące punkty zostawiają smugi;
- **symuluje efekt Schwarzschilda**: przy naświetlaniu dłuższym niż 1/2 s stara emulsja traci czułość i rośnie kontrast, dokładnie jak prawdziwa klisza;
- **ma ciemnię**: gradację papieru 0–5, efekt krawędziowy wywoływacza i przeterminowaną kliszę (zadymienie, nierówna czułość);
- **ma obiektyw**: filtr dyfuzyjny („pończocha” z hollywoodzkich portretów), głębię ostrości z przysłoną f/1.4–f/16 i ręczną ostrość opartą na AI oraz wirujące rozmycie jak w Heliosie;
- **pokazuje wszystko na żywo** w podglądzie, a zdjęcie „wywołuje” w pełnej rozdzielczości tym samym procesem.

### Klisze kolorowe (do lat 70.)

Kolorowa klisza ma trzy warstwy (czułą na czerwień, zieleń i błękit), każdą z własną krzywą, z przesłuchem między warstwami, nieczystymi barwnikami i czerwoną halacją wokół świateł:

- **Dwubarwny 1929**: tylko czerwony i zielony rekord, świat w pomarańczach i turkusach.
- **Trójbarwny 1939**: druk barwnikowy, ogromne nasycenie, najgłębsza czerń, złocista skóra, bardzo drobne ziarno.
- **Odwracalny 1955**: legendarne czerwienie, stonowane błękity, wysoki kontrast.
- **Negatyw 1962**: miękki, przygaszony, zielonkawe cienie.
- **Wschodni 1971**: chłodny, turkusowo-zielony, wyraźne ziarno.
- **Kinowy 1975 100T**: tylko 7–8 działek rozpiętości (twardy kontrast), soczyste czerwienie, chłodne zielenie, czerwona poświata wokół lamp.
- **Wyblakła odbitka 1974**: barwnik cyjanowy i żółty wyblakły, obraz idzie w czerwień i róż, najmocniej w cieniach.

Kolorowe klisze mają **stały balans bieli**: dzienna pod żarówką daje pomarańczowe zdjęcia, a „T” w słońcu niebieskie, tak jak prawdziwa emulsja. Przytrzymaj nazwę kliszy, żeby wybrać ją z listy.

### Jak prawdziwy aparat

- **Rolka** 12/24/36 klatek. Zdjęć nie widać, dopóki nie wywołasz całej rolki. Kliszy nie zmienisz w połowie rolki. Stan rolki przetrwa zamknięcie aplikacji.
- **Ręczny naciąg filmu**: po każdym zdjęciu spust jest zablokowany, dopóki nie naciągniesz filmu (słychać zapadkę).
- **Wielokrotna ekspozycja**: spust bez naciągania naświetla tę samą klatkę jeszcze raz. Światło się sumuje jak na emulsji.
- **Wizjer z matówką**: faktura szkła, klin ostrości w środku (połówki schodzą się przy trafionej ostrości) i migoczący pierścień mikropryzmatów.
- **Dźwięk migawki szczelinowej**: dwie kurtyny „klak… klak” rozdzielone prawdziwym czasem naświetlania.
- **Ramka negatywu**: czarny brzeg, perforacja i numer klatki na odbitce (opcja).

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
