# Moneta

**Moneta** is a **server-side** currency mod for **Minecraft 26.3** (Fabric).  
It does not need to be installed on clients: it adds persistent balances, payments to online and offline players, administrative commands, a transaction log, configuration, and an automatic monetary policy.

## Features

- Persistent per-player balances stored in the world folder.
- Payments between online and offline players.
- State mint with cooldown and daily limit.
- Automatic monetary regulation to limit inflation and preserve currency value.
- Transaction tax that burns a percentage of every transfer.
- Maximum balance cap.
- Economy overview with circulation total and top balances.
- Administrative commands for setting, taking, and auditing balances.
- JSON configuration with sensible defaults.
- Atomic saves on a background thread.
- No HUD: balances are shown only through commands.

## Requirements

- Minecraft `26.3`
- Fabric Loader `0.20.1` or newer
- Fabric API `0.165.0+26.3` or newer
- Java `25` or newer
- Server environment only

## Installation

1. Install Fabric Loader and Fabric API on your server.
2. Place the Moneta jar in the `mods` folder.
3. Start the server once to generate `config/moneta.json`.
4. Adjust the configuration if needed and restart.

## Commands

| Command | Description |
|---|---|
| `/balance` | Shows your balance. |
| `/balance <player>` | Shows the balance of a player, including offline players. |
| `/send <player> <amount>` | Sends money to an online or offline player. |
| `/mint <amount>` | Creates money and distributes it to online players. |
| `/economy [limit]` | Shows total circulation and the highest balances. |
| `/setbalance <player> <amount>` | Sets a player's balance. Admin only. |
| `/takebalance <player> <amount>` | Removes money from a player's balance. Admin only. |
| `/transactions [player] [limit]` | Shows recent transactions. Admin only. |
| `/treasury` | Shows the mint/treasury balance. |
| `/settax <percent>` | Sets the transaction tax percentage. Admin only. |
| `/treasurypay <player> <amount>` | Pays a player from the treasury. Admin only. |

Amounts are integers (`long`), without decimals.

## Configuration

The file is created automatically at `config/moneta.json`:

```json
{
  "currencySymbol": "$",
  "startingBalance": 0,
  "adminPermission": "gamemaster",
  "economyTopLimit": 10,
  "economyMaxLimit": 100,
  "transactionLogLimit": 1000,
  "autosaveSeconds": 30,
  "regulationEnabled": true,
  "targetBalancePerPlayer": 1000,
  "regulationIntervalSeconds": 300,
  "regulationTolerancePercent": 10,
  "regulationRatePercent": 2,
  "transactionTaxPercent": 5,
  "maxBalance": 100000,
  "mintCooldownSeconds": 60,
  "mintDailyLimit": 100000
}
```

### General settings

- `currencySymbol`: symbol shown in messages.
- `startingBalance`: initial balance for new players.
- `adminPermission`: permission required by admin commands. Accepted values: `moderator`, `gamemaster`, `admin`, `owner`.
- `economyTopLimit`: number of players shown by `/economy` without an argument.
- `economyMaxLimit`: maximum value accepted by `/economy <limit>`.
- `transactionLogLimit`: maximum number of transactions stored.
- `autosaveSeconds`: automatic save interval. `0` saves immediately after every change.

### Monetary policy

- `regulationEnabled`: enables automatic monetary regulation.
- `targetBalancePerPlayer`: desired average balance per known account.
- `regulationIntervalSeconds`: time between automatic regulations.
- `regulationTolerancePercent`: allowed deviation before the system intervenes.
- `regulationRatePercent`: strength of automatic creation or burning.
- `transactionTaxPercent`: percentage sent to the treasury on every transfer.
- `maxBalance`: maximum balance per account. `0` disables the cap.
- `mintCooldownSeconds`: cooldown for `/mint`.
- `mintDailyLimit`: daily limit for `/mint`. `0` disables the limit.

## Monetary policy

Moneta includes a self-regulating monetary system:

- It calculates a **target money supply** from `targetBalancePerPlayer` and the number of known accounts.
- If the supply is **too low**, the mint creates money and distributes it to online players.
- If the supply is **too high**, it burns a percentage of the excess from balances above the per-player target.
- Every transfer pays a **transaction tax**, which is stored in the treasury instead of being burned.
- Balances cannot exceed `maxBalance`.
- `/mint` is protected by a cooldown and a daily limit.

This keeps the currency from losing value through uncontrolled inflation while still allowing the economy to grow with the player base.

## Treasury

Transaction taxes are not burned: they are stored in the **mint treasury**.  
This balance can be used by administrators to pay players for services that benefit everyone, such as public infrastructure or guaranteed minimum services.

Example: if a free food service is funded by the treasury, the food can be purchased from players who produce meat or crops, redistributing tax money back into the economy.

## Data

Data is stored in the world folder:

- `world/moneta/balances.json` — player balances and names.
- `world/moneta/transactions.json` — transaction history.

Saves use atomic file writes and are performed on a background thread to avoid blocking the server thread.

## Build

```bash
./gradlew build
```

The jar is created in `build/libs/`.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
