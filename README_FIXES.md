# Connect Four Project — Fixed Version

This zip contains the same Java project reorganized into a normal package structure under:

```text
src/main/java/com/example/demo/...
```

## Main fixes made

- Removed the hardcoded database password from `ServerApp.java`.
- Added environment-variable database configuration:
  - `C4_DB_HOST`
  - `C4_DB_PORT`
  - `C4_DB_NAME`
  - `C4_DB_USER`
  - `C4_DB_PASS`
- Improved MySQL connection URL with `createDatabaseIfNotExist=true`.
- Added schema bootstrap/migration in `Database.java`.
- Added stronger database tables/indexes and extra player stats:
  - matches played
  - wins
  - losses
  - current streak
  - best streak
  - last seen time
- Made DAO database access synchronized so multiple server/client threads do not corrupt the shared JDBC connection.
- Made match recording transactional.
- Ensured player rows exist before match results update player points.
- Connected tournament bracket recording to actual tournament/match results.
- Fixed tournament logic so the 3rd-place match is not accidentally treated as a normal match if the final finishes first.
- Added tournament draw rematch handling so tournament brackets do not get stuck on a draw.
- Fixed lobby countdown logic so tournaments start only when 4 available/idle players exist.
- Fixed manual challenge logic so it cannot conflict with a tournament countdown.
- Fixed the client UI lock bug where an invalid move/column-full error could leave `animating = true` forever.
- Made packets safer by escaping `|`, `=`, and backslashes.
- Sanitized player names so lobby packet parsing does not break on commas/colons/pipes.
- Made board rendering safer against malformed packets.
- Made highlight JSON writing escape strings correctly.

## Important database note

You still need MySQL Connector/J available when running the server. If you run from an IDE, add the MySQL Connector/J jar/dependency to the project.

If running from the command line, set your DB password through the environment or pass it as an argument. Do not hardcode it in the source code.

Example:

```bash
export C4_DB_USER=root
export C4_DB_PASS=your_password_here
```

Or:

```bash
java ... com.example.demo.server.ServerApp --db-user root --db-pass your_password_here
```

## Server run options

```text
--port N
--variant 4|5
--turn-seconds N
--db-host HOST
--db-port PORT
--db-name NAME
--db-user USER
--db-pass PASSWORD
```

## Build note

The server-side code was compile-checked without JavaFX. The client JavaFX files need JavaFX libraries configured in your IDE/build tool.

## New mode-selection update

The client now asks for a playing mode after the display-name prompt:

1. **Normal mode**
   - Regular Connect Four.
   - Manual challenge request: pick another Normal player and click **Send challenge request**. The target player must accept before the match starts.
   - Uses connect-4 rules.
   - No power discs.

2. **Tournament mode**
   - Players wait in the lobby until 4 Tournament players are available.
   - The server automatically starts a 4-player bracket.
   - The bracket includes two semifinals, a final, and a 3rd-place match.

3. **Extras mode**
   - Manual challenge request: pick another Extras player and click **Send challenge request**. The target player must accept before the match starts.
   - Uses the 5-in-a-row variant.
   - Timed turns are enabled.
   - Each player gets one **Clear Column** power and one **Double Points** power per match.

The server now reads mode preferences from the initial `HELLO` packet and starts matches with the correct rule set.


## Challenge accept/decline update

Normal and Extras matches no longer start immediately when a player clicks challenge. The server now sends a challenge request to the selected player. The selected player sees an **Accept / Decline** dialog, and the match starts only if they accept. Challenge requests expire after 30 seconds and are cancelled if either player disconnects, enters a match, or becomes unavailable.
