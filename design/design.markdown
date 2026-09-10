# tpce Design

## Goal

tpce provides simple teleportation while keeping commands available for players who prefer them.

Normal player actions should be accessible through clickable chat, so players do not need to memorize commands or type player names.

The interface should work for:

- Java players.
- Bedrock players using Geyser/Floodgate.
- Mobile players.
- Slow typers.
- Players who prefer not to use commands.

No client-side mod or resource pack is required.

## Commands

```ts
/tpce <reload>

/tpr <player>
/tpa
/tpd
/tpb

/bed

/home set <name> [primary]
/home <name>
/home

/spawn
```

Commands remain fully functional.

Clickable chat provides an alternative for normal player interaction.

## Clickable Chat

Important actions should produce clickable chat components.

A clickable action should perform the action directly rather than requiring the player to copy or type a command.

Example:

```ts
Teleport request from <player>.

[ Accept ] [ Decline ]
```

Clicking `Accept` accepts the request.

Clicking `Decline` declines it.

### Teleport Request

Instead of requiring:

```ts
/tpr PlayerName
```

the player can receive a list of clickable player names:

```ts
Who do you want to teleport to?

[ PlayerOne ] [ PlayerTwo ]
[ PlayerThree ] [ PlayerFour ]
```

Clicking a player sends the request.

The player does not need to type their username.

If there are more players than can reasonably fit in one message, provide:

```ts
[ Next ] [ Back ]
```

The player list should be paginated.

## Request Feedback

After sending a request:

```ts
Teleport request sent to <player>.

[ Cancel Request ]
```

The target receives:

```ts
<player> wants to teleport to you.

[ Accept ] [ Decline ]
```

The requester does not need to type `/tpa`.

The target does not need to type `/tpa` or `/tpd`.

## Homes

`/home` remains available for command users.

Clickable chat should provide access to homes when appropriate.

Example:

```ts
Homes:

[ Home 1 ] [ Home 2 ] [ Home 3 ]
```

Clicking a home teleports the player there.

A home can also provide actions:

```ts
Home: base

[ Teleport ] [ Set Primary ] [ Delete ]
```

Deleting a home requires confirmation:

```ts
Delete home "base"?

[ Confirm ] [ Cancel ]
```

## Setting Homes

A player can still use:

```ts
/home set <name>
```

For players who prefer clickable interaction, the plugin should provide an appropriate clickable action when setting or managing homes.

Custom home names may require text input.

Typing should only be necessary where the player is actually providing new information, such as a custom home name.

## Primary Home

Players can select a home and click:

```ts
[ Set Primary ]
```

`/home` teleports to the primary home.

If no primary home exists, `/home` should provide clickable choices rather than leaving the player with no useful next action.

## Bed

`/bed` remains available.

Clickable chat should provide a direct bed teleport action where useful.

If the player has no valid bed location:

```ts
No valid bed location is available.
```

No alternative destination should be selected automatically.

## Spawn

`/spawn` remains available.

Clickable chat can provide:

```ts
[ Teleport to Spawn ]
```

when spawn access is presented as part of the plugin's normal interaction.

## Back

`/tpb` remains available.

Clickable chat can provide:

```ts
[ Teleport Back ]
```

when a previous teleport location exists.

Only the most recent location is stored.

Using `/tpb` swaps the current and previous locations.

## Teleport Delay

Teleporting has a 3-second delay.

```ts
Teleporting in 3...
Teleporting in 2...
Teleporting in 1...
```

Moving cancels the teleport.

Taking damage cancels the teleport.

Starting another teleport cancels the previous teleport.

A cancelled teleport does not trigger the cooldown.

## Cooldown

A successful teleport starts a 5-second cooldown.

The cooldown applies equally to command and clickable-chat teleports.

## Bedrock and Mobile Accessibility

Clickable chat is the primary accessibility feature.

The plugin should avoid relying on:

- Exact username typing.
- Long command sequences.
- Java-only client features.
- Client-side mods.
- Resource packs.

Clickable actions should have short, clear labels.

The same actions should work for Java and Bedrock players connected through Geyser/Floodgate.

## Permissions

```ts
tpce.reload
tpce.request
tpce.accept
tpce.decline
tpce.back
tpce.bed
tpce.home
tpce.spawn
```

Normal player permissions are granted by default.

`tpce.reload` is restricted to administrators.

## Teleport Safety

- Teleports must not place players inside blocks.
- Destinations must have a valid world.
- Destinations should have a safe position.
- Failed teleports leave the player at their current location.
- Invalid destinations produce an explicit error.
- The plugin must not silently substitute another destination.
- Command and clickable-chat teleports use the same safety checks.

## Failure Feedback

Failures should clearly explain what happened and, where useful, provide a clickable next action.

Examples:

```ts
Player is no longer online.

That request has expired.

You do not have a valid bed location.

That home no longer exists.

That destination is not safe.

You cannot teleport yet. Please wait <seconds> seconds.
```

## Persistence

Homes persist across server restarts.

Teleport requests do not persist across server restarts.

Back locations do not need to persist across server restarts.

## Reload

`/tpce reload` reloads configuration.

* Invalid configuration produces a clear error.
* The previous configuration remains active if the new configuration cannot be loaded.
* Homes are not deleted during reload.
* Active requests are not unnecessarily cancelled.

## Configuration

```yaml
request-expiration: 60
teleport-delay: 3
teleport-cooldown: 5

homes:
  limit: 3

clickable-chat:
  enabled: true

safety:
  require-safe-destination: true
  cancel-on-movement: true
  cancel-on-damage: true
```

## Implementation Constraints

* Paper 26.2.
* No client-side mod.
* No resource pack.
* No external dependency required for core functionality.
* Clickable chat and commands must use the same underlying operations.
* Avoid separate Java and Bedrock implementations.
* Keep the implementation small and explicit.
