# Lobby and Select

NeoForge 1.21.1 server mod. Every player who connects lands in a lobby dimension (`lobbyandselect:lobby`).

- **Queue** - if the main world holds `maxMainWorldPlayers` players, newcomers wait in a FIFO queue (boss bar shows position). Ops skip it (configurable).
- **Joined detection** - the mod remembers each player (`joinedBefore`, `spawnChosen`, last position). Returning players are put back exactly where they logged out; first-timers pick a spawn.
- **One-time spawn pick** - a zoomable Web Mercator map of the Earth (Blue Marble imagery from [Earth Platform](https://github.com/pirut/earth-platform)). Picking is permanent; only `/lobby admin resetspawn <player>` undoes it. Players without the mod on their client use `/lobby spawn <lat> <lon>` (clickable places in chat).
- **Earth Platform integration** - travel uses `/earth goto <lat> <lon> <player>` so terrain is loaded and the landing is safe. Main world defaults to `earthplatform:cubic`. Without Earth Platform it falls back to the same Mercator math on the overworld.
- **Minigames while waiting** - parkour (timed, checkpoints, `/lobby parkour top`), spleef (stand on the lapis pad), and geography trivia in chat.

## Commands
`/lobby` help, `status`, `spawn [lat lon]`, `parkour [top]`, `spleef`, `leave`, `stats`
Admin: `/lobby admin resetspawn|admit|send|max|bypass|rebuild|trivia`

## Config
`config/lobbyandselect-server.toml` (or per-world `serverconfig/`): cap, op bypass, pick timeout, main dimension, places list, respawn-at-spawn, minigame settings.

## Build
Java 21: `./gradlew build` -> `build/libs/lobbyandselect-1.0.0.jar`. Install on the server **and** clients (clients need it for the map screen).
