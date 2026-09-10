# tpce

Small teleportation utility for Paper 26.2.

## Features

- Teleport requests with clickable Accept and Decline actions.
- Clickable player selection through `/tpr`.
- Paginated player selection for larger servers.
- Three persistent home slots by default.
- Primary homes.
- Bed, spawn, and previous-location teleportation.
- Teleport delay, cooldown, movement cancellation, and damage cancellation.
- Safe destination checks.
- Java and Geyser/Floodgate players use the same chat interface.
- No client-side mod or resource pack required.

## Commands

```text
/tpce
/tpce reload
/tpr [player|cancel|page <number>]
/tpa
/tpd
/tpb
/bed
/home
/home list
/home set <name> [primary]
/home delete <name>
/home primary <name>
/spawn
```

`/tpce` opens the clickable teleport menu. Normal players can use it without the reload permission.

## Build

Requires Java 25 and Maven.

```text
mvn verify
```

The Paper API is provided by the server and is not bundled into the plugin jar.

## Runtime behavior

Teleport requests expire according to `request-expiration` and are cancelled when either player disconnects. Teleports use the configured delay and cooldown. Movement and damage can cancel a pending teleport.

Homes are stored in `plugins/tpce/homes.yml` and are saved immediately after home mutations and again during plugin shutdown.

Invalid required configuration stops plugin startup with the exact configuration path that failed.
