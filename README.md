# tpce

Small teleportation utility for Paper 26.2.

## Features

- Teleport requests with `/tpr`, `/tpa`, and `/tpd`.
- Clickable player selection and request actions in chat.
- Up to three persistent homes.
- Bed, spawn, and previous-location teleports.
- Configurable teleport delay and cooldown.
- Movement and damage cancellation.
- Safe-destination checks.
- No client-side mod or resource pack required.

## Build

Requires JDK 25 and Maven.

```text
mvn package
```

The plugin jar is produced in `target/`.

## Commands

```text
/tpr [player]
/tpa
/tpd
/tpb
/bed
/home [name|set <name> [primary]]
/spawn
/tpce reload
```

`/tpr` without a player shows clickable online players. Teleport requests and home selection use clickable chat so players do not need to type player names.

## Configuration

Configuration is in `config.yml`. Homes are stored in `homes.yml` and survive server restarts.
