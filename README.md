# NoReels

Android appka (testované pre Xiaomi / HyperOS), ktorá:

- **skryje kartu Reels v Instagrame a Shorts v YouTube**: ikonu prekryje obdĺžnikom vo farbe lišty a ťuknutie na ňu nič neurobí,
- **pri poslanom reeli/shorte zablokuje posun na ďalší**: jeden si pozrieš, pri potiahnutí na ďalší ťa vráti späť.

Funguje cez službu Zjednodušenia ovládania (Accessibility Service). Nič nezbiera ani neodosiela.

## Inštalácia
1. Stiahni `NoReels.apk` z [Releases](../../releases) a nainštaluj.
2. V appke prejdi kroky 1–3 (obmedzené nastavenia, Zjednodušenie ovládania, Autoštart + batéria bez obmedzení).

## Zostavenie
Potrebuje JDK 17, Android SDK 35 a Gradle 8.11 v `~/AndroidTools` (cesta bez diakritiky), potom:

```bash
bash build.sh
```

Ak Instagram/YouTube zmení rozloženie, zapni v appke **Diagnostiku**, zopakuj problém a skopíruj záznam: sú v ňom názvy prvkov, ktoré treba upraviť v `BlockerService.java`.
