# WinSipPhone Tester

Telefono SIP gratuito e open source per smartphone Android. Fa chiamate e videochiamate tramite centralino (FreePBX/Asterisk) oppure dirette a un indirizzo IP. Include gli strumenti per collaudare centralini e videocitofoni (2N, Hikvision...).

## Funzioni
- **Account centralino** (UDP/TCP/TLS), sempre registrato anche con app chiusa, schermata di chiamata a telefono bloccato.
- **Chiamate dirette IP** in uscita e in arrivo, con filtro degli IP autorizzati.
- **Videochiamate** H.264/VP8: anteprima, cambio fotocamera, video attivabile durante la chiamata.
- **In chiamata**: muto, vivavoce, attesa, toni DTMF, trasferimento.
- **Rubrica** con preferiti e **pulsanti apertura porta**: toni DTMF o comandi HTTP con autenticazione Basic/Digest. Si può esportare e importare.
- **Stato interni (BLF)**: libero, squilla o occupato (RFC 4235 dialog-info).
- **Storico chiamate** con il motivo SIP dei fallimenti (486, 404...).
- **Strumenti di collaudo**:
  - registro dei messaggi SIP, con filtro e condivisione;
  - statistiche di chiamata: codec, banda, perdite, jitter, ritardo, indirizzi RTP;
  - test di raggiungibilità: DNS, ping, porte TCP, SIP OPTIONS su UDP/TCP con modello del dispositivo.

## Download
Gli APK sono nelle [Releases](https://github.com/winniko/WinSipPhone/releases):
- `-64bit`: smartphone recenti;
- `-32bit`: smartphone vecchi;
- `-universale`: se non sai quale scegliere.

Android 8.0 o successivo.

## Istruzioni
Vedi [ISTRUZIONI.md](ISTRUZIONI.md): configurazione del FreePBX, uso dell'app, collaudo, problemi comuni.

## Compilazione
- JDK 17, Android SDK 35, Gradle wrapper incluso: `./gradlew assembleRelease`.
- `-Pabi=arm64-v8a` produce l'APK per un solo processore; `-Pemu` aggiunge x86_64 per l'emulatore.
- La chiave di firma va in `keystore/keystore.properties` e non è inclusa.
- Per i test senza un vero centralino c'è un finto FreePBX: `python test/fake_pbx.py 15060 6 incoming`.

## Licenza
GNU Affero General Public License v3. Vedi [LICENSE](LICENSE) e [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Usa [liblinphone](https://www.linphone.org) di Belledonne Communications (AGPL v3).
