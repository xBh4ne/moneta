# Moneta

Mod di valuta **server-side** per Minecraft **26.3** (Fabric). Non richiede l'installazione sul client: aggiunge saldi persistenti, pagamenti anche verso giocatori offline, comandi amministrativi, registro delle transazioni e configurazione.

## Comandi

| Comando | Descrizione |
|---|---|
| `/balance` | Mostra il tuo saldo. |
| `/balance <player>` | Mostra il saldo di un giocatore, anche offline. |
| `/send <player> <amount>` | Invia denaro a un giocatore online o offline. |
| `/mint <amount>` | Crea denaro e lo distribuisce ai giocatori online. |
| `/economy [limit]` | Mostra il totale in circolazione e i saldi più alti. |
| `/setbalance <player> <amount>` | Imposta il saldo di un giocatore (admin). |
| `/takebalance <player> <amount>` | Rimuove denaro dal saldo di un giocatore (admin). |
| `/transactions [player] [limit]` | Mostra le ultime transazioni (admin). |

Gli importi sono interi (`long`), senza decimali. I nomi dei giocatori offline vengono risolti tramite la cache profili del server (`usercache.json`).

## Configurazione

Il file viene creato automaticamente in `config/moneta.json`:

```json
{
  "currencySymbol": "$",
  "startingBalance": 0,
  "adminPermission": "gamemaster",
  "economyTopLimit": 10,
  "economyMaxLimit": 100,
  "transactionLogLimit": 1000,
  "autosaveSeconds": 30
}
```

- `currencySymbol`: simbolo mostrato nei messaggi.
- `startingBalance`: saldo iniziale dei nuovi giocatori.
- `adminPermission`: permesso richiesto dai comandi admin (`moderator`, `gamemaster`, `admin`, `owner`).
- `economyTopLimit`: numero di giocatori mostrati da `/economy` senza argomento.
- `economyMaxLimit`: limite massimo richiedibile con `/economy <limit>`.
- `transactionLogLimit`: numero massimo di transazioni conservate.
- `autosaveSeconds`: intervallo di salvataggio automatico; `0` salva subito dopo ogni modifica.

## Dati

I dati sono salvati nella cartella del mondo:

- `world/moneta/balances.json` — saldi e nomi dei giocatori.
- `world/moneta/transactions.json` — registro delle transazioni.

I salvataggi usano scritture atomiche (file temporaneo + rename) e avvengono in un thread separato per non bloccare il thread principale del server.

## Build

```bash
./gradlew build
```

Il jar viene creato in `build/libs/`.
