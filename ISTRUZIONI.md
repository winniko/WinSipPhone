# WinSipPhone Tester — Istruzioni

Telefono SIP per smartphone Android (8.0 o successivo): chiamate e videochiamate tramite centralino (FreePBX, Asterisk o altri) oppure dirette a un indirizzo IP, con strumenti per collaudare impianti e videocitofoni.

---

## 1. Installazione

1. Copia sul telefono il file **`WinSipPhone-<versione>-64bit.apk`**. Se il telefono è molto vecchio usa `-32bit`; se non sai quale scegliere usa `-universale`.
2. Apri il file dal telefono e consenti "Installa app sconosciute" quando Android lo chiede.
3. Al primo avvio l'app chiede:
   - **microfono e fotocamera**: servono per chiamare e per le videochiamate;
   - **notifiche**: servono per la chiamata in arrivo e le chiamate perse;
   - **nessun limite di batteria**: rispondi **Consenti**, altrimenti con lo schermo spento le chiamate possono non arrivare;
   - **notifiche a schermo intero** (Android 14 e successivi): attivale, così la chiamata compare anche a telefono bloccato.

Puoi ricontrollare tutto in **Strumenti → Permessi e batteria**. Lo stato di ogni permesso è indicato in **Strumenti → Stato**.

> Alcuni telefoni (Xiaomi, Huawei, Oppo, Samsung) hanno un risparmio energetico aggiuntivo. Nelle impostazioni dell'app di sistema imposta **Batteria → Nessuna restrizione** e attiva **Avvio automatico**.

L'app resta sempre attiva, con una piccola notifica fissa "Interno 201: registrato". Si riavvia da sola all'accensione del telefono.

---

## 2. Collegamento al centralino FreePBX

### Sul FreePBX
1. Vai in **Applications → Extensions → Add Extension → PJSIP**.
2. Annota il numero dell'interno (es. **201**) e il **Secret** (la password).
3. Per le videochiamate apri la scheda **Advanced** e in **Allow** aggiungi `h264` e/o `vp8` (es. `ulaw&alaw&g722&opus&h264&vp8`). Imposta anche **Video Support = Yes**, se presente.
4. Premi **Submit** e poi **Apply Config**.

### Nell'app
Tocca l'ingranaggio in alto a destra, poi **Account centralino (FreePBX)**, e compila:

| Campo | Esempio |
|---|---|
| Usa il centralino | attivo |
| Interno | 201 |
| Password (secret) | quella del FreePBX |
| Indirizzo del centralino | 192.168.1.10 |
| Porta del centralino | 5060 (alcuni FreePBX usano 5160 per chan_sip) |
| Protocollo | UDP in casa; TCP fuori casa o con VPN |

Premi **Prova connessione al centralino** per verificare che risponda, poi **Salva**. In alto, il pallino diventa **verde** con la scritta "registrato".

Se compare un errore:
- **401 / 403**: interno o password errati;
- **404**: l'interno non esiste sul centralino;
- **non raggiungibile**: indirizzo sbagliato, centralino spento o telefono su un'altra rete.

### Usare l'app fuori casa
Il telefono deve poter raggiungere il centralino. Il modo consigliato è una **VPN** (es. WireGuard sul router o sul FreePBX) con protocollo **TCP** nell'app. In alternativa si può esporre il centralino su internet: è più rischioso e va protetto bene.

---

## 3. Chiamare

**Tastiera.** Scrivi il numero e premi la **cornetta verde** (audio) oppure la **telecamera** (video). Si possono chiamare:

| Cosa | Esempio |
|---|---|
| Un interno del centralino | `202` |
| Un dispositivo direttamente, senza centralino | `192.168.1.50` |
| Un interno su un dispositivo/porta precisi | `201@192.168.1.50` oppure `192.168.1.50:5062` |

- Per scrivere punti, `@` e `:` tocca la scritta blu **"abc"**: si apre la tastiera del telefono.
- Cornetta premuta a vuoto: ricompare l'ultimo numero chiamato.
- Tieni premuto ⌫ per cancellare tutto.

**Rubrica.** Tocca un contatto per chiamarlo. Tieni premuto per videochiamare, modificare o eliminare.

**Interni.** Tocca un interno per chiamarlo.

**Storico.** Tocca una riga per richiamare. Tieni premuto per aggiungere il numero in rubrica.

### Durante la chiamata
| Pulsante | Funzione |
|---|---|
| Muto | spegne il microfono |
| Vivavoce | altoparlante ↔ auricolare (o cuffie/bluetooth) |
| Attesa | mette la chiamata in attesa |
| Tastiera | invia toni DTMF (es. codici per aprire una porta) |
| Trasferisci | passa la chiamata a un altro interno o indirizzo |
| Video | attiva o disattiva il video |
| Cambia cam | fotocamera frontale ↔ posteriore |
| Statistiche | codec, banda, pacchetti persi, jitter, ritardo, indirizzi RTP |

Nelle chiamate audio con l'auricolare, lo schermo si spegne quando avvicini il telefono all'orecchio.

### Chiamate in arrivo
- Arrivano anche con app chiusa e telefono bloccato: lo schermo si accende e mostra **Rifiuta / Rispondi**, più **Video** se l'altra parte propone il video.
- La suoneria è quella del telefono e rispetta silenzioso e vibrazione.
- Se non rispondi ricevi una notifica "Chiamata persa".

---

## 4. Rubrica e pulsanti apertura porta

**Rubrica → + Nuovo**:
- **Nome** e **Numero o indirizzo**, scritti come nella tastiera;
- **Chiama in video**: il tocco sul contatto avvia una videochiamata;
- **Preferito**: il contatto va in cima all'elenco.

**Pulsanti apertura porta** (fino a 3 per contatto) compaiono durante la chiamata con quel contatto:
- **DTMF**: l'app invia i toni in chiamata. Esempio 2N: il codice impostato nel citofono in *Servizi → Telefono → Chiamate → codici DTMF*. Funziona solo dopo aver risposto.
- **HTTP**: l'app manda un comando diretto al dispositivo. Esempi:
  - 2N: `http://192.168.1.50/api/switch/ctrl?switch=1&action=trigger`, con utente e password dell'API 2N (Digest supportato);
  - Hikvision: `http://192.168.1.60/ISAPI/AccessControl/RemoteControl/door/1`, metodo `PUT`, corpo `<RemoteControlDoor><cmd>open</cmd></RemoteControlDoor>`.
- **Riaggancia dopo l'apertura**: chiude la chiamata 1,5 secondi dopo il comando.

La rubrica si può esportare e importare (pulsanti in fondo) per copiarla su altri telefoni.

---

## 5. Interni (stato libero / occupato)

**Interni → + Aggiungi interno**: scrivi uno o più interni (es. `202,203,204`).

| Colore | Significato |
|---|---|
| verde | libero |
| arancione | squilla |
| rosso | occupato |
| grigio | non disponibile / sconosciuto |

Serve l'account del centralino. Sul FreePBX gli "hint" degli interni sono attivi di norma. Se lo stato resta grigio, controlla in **Settings → Asterisk SIP Settings** che il BLF/presence sia consentito.

---

## 6. Strumenti per il collaudo

- **Stato**: registrazione, account, porta SIP del telefono, IP del telefono (servono per farsi chiamare direttamente), permessi.
- **Registro SIP**: tutti i messaggi SIP scambiati (REGISTER, INVITE, 180, 200 OK, 401, BYE...), dal più recente.
  - Colori: blu = inviati, giallo = ricevuti, verde = 2xx, rosso = errori 4xx/5xx.
  - Tocca un messaggio per vederlo per intero e copiarlo.
  - Il campo **Filtra** cerca testo (es. `INVITE`, `401`, un IP).
  - **Condividi** invia il registro completo come file .txt (WhatsApp, email...), utile per l'assistenza.
- **Test raggiungibilità**: scrivi l'indirizzo di un centralino, citofono o telefono.
  - **Test completo**: DNS, ping, SIP OPTIONS su UDP, porta TCP, SIP su TCP, porta 80 (web).
  - Il test SIP dice se il dispositivo risponde, con quale codice e che modello è (User-Agent/Server), e se c'è un NAT di mezzo.
- **Ri-registra ora**: forza una nuova registrazione al centralino.
- **Registro dell'app**: eventi interni dell'app, condivisibile.

---

## 7. Farsi chiamare direttamente (senza centralino)

Un citofono o un altro telefono può chiamare questo smartphone all'indirizzo `IP-del-telefono:5062`. L'IP si legge in **Strumenti → Stato**.

- In **Impostazioni → Chiamate dirette IP** puoi limitare gli IP autorizzati (es. solo il citofono `192.168.1.50`).
- Conviene dare al telefono un **IP fisso** (prenotazione DHCP nel router), così l'indirizzo non cambia.
- La **porta del telefono** si cambia in Impostazioni (0 = casuale; in quel caso le chiamate dirette in arrivo non sono possibili).
- **Non usare la porta 5060 per il telefono.** Molti smartphone (ad esempio OnePlus) la riservano alle chiamate dell'operatore (VoLTE / chiamate Wi-Fi) e scartano le risposte SIP. Sintomi:
  - il dispositivo chiamato squilla, ma l'app resta su "chiamata in corso" o "il dispositivo non risponde";
  - riagganciando, l'altro telefono continua a squillare.

  Dalla versione 1.0.2 la porta predefinita è **5062**.

> Se centralino e dispositivo da chiamare direttamente hanno lo **stesso indirizzo IP** (cambia solo la porta), la chiamata passa dal centralino. Caso raro: in un impianto normale hanno IP diversi.

---

### Citofoni Hikvision senza centralino
I posti esterni Hikvision (es. DS-KD8003) rifiutano con **"400 Bad Request"** le chiamate dirette che non sembrano arrivare dal loro server SIP.

1. Nel citofono guarda **Configurazione → VoIP → Indirizzo IP del server** (es. `192.168.1.10`).
2. Nell'app vai in **Impostazioni → Chiamate dirette IP → Dominio del mittente** e scrivi lo stesso indirizzo. Facoltativo: un **Numero del mittente**, es. `200`.
3. Chiama il citofono con `101@IP-del-citofono`, dove 101 è il "Numero" del citofono in VoIP.

Se l'account del centralino è attivo, l'app usa automaticamente il suo indirizzo come mittente.

## 8. Impostazioni avanzate

| Impostazione | A cosa serve |
|---|---|
| Guadagno microfono / altoparlante | volume più alto o più basso in conversazione (dB, es. 3 o 6) |
| Invio toni DTMF | RFC 2833 va bene quasi sempre; SIP INFO per alcuni citofoni |
| Codec | ordine di preferenza (audio `OPUS,G722,PCMA,PCMU`; video `H264,VP8`) |
| Server STUN / ICE | solo fuori casa senza VPN |
| Cifratura | SRTP/ZRTP se il centralino lo supporta |
| Risposta automatica | utile per collaudi (risponde dopo 1,5 s) |
| Registro SIP attivo | spegnilo se non serve (risparmia un po' di batteria) |
| Backup configurazione | esporta/importa le impostazioni (con o senza password) |

---

## 9. Problemi comuni

| Problema | Soluzione |
|---|---|
| Pallino rosso "401" | interno o password errati |
| Pallino arancione fisso | centralino non raggiungibile: usa *Test raggiungibilità* |
| Chiamate in arrivo solo con app aperta | togli i limiti batteria (Strumenti → Permessi e batteria) e l'eventuale risparmio energetico del produttore |
| Si sente solo in una direzione | NAT/firewall: usa la VPN o TCP; guarda "RTP remoto" nelle Statistiche |
| Niente video | abilita h264/vp8 sull'interno del FreePBX; controlla il permesso fotocamera |
| Il telefono chiamato squilla ma l'app non lo vede / continua a squillare dopo il riaggancio | porta del telefono 5060 bloccata dallo smartphone: Impostazioni → Chiamate dirette IP → Porta SIP di QUESTO telefono = 5062 |
| Citofono Hikvision: "400 Bad Request" | imposta *Dominio del mittente* = server SIP del citofono (vedi sezione 7) |
| Il citofono non apre | prova tipo DTMF diverso (Impostazioni → Audio) o il comando HTTP |

---

## Licenza

Software libero e gratuito, licenza **GNU AGPL v3**. Usa liblinphone (Belledonne Communications, AGPL v3). Codice sorgente: https://github.com/winniko/WinSipPhone

Fornito senza garanzia: non usarlo come unico mezzo per le chiamate di emergenza.
