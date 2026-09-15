# In-game test bot for FAWE on Forge 1.7.10

Connects to a Forge 1.7.10 (Crucible) test server as a player, runs WorldEdit commands and checks the results in the
native world with `/faweselftest` (enabled with `-Dfawe.forge1710.selftest=true`).

Mineflayer does not support 1.7.10, so the bot uses `minecraft-protocol` directly with its own FML handshake
(`fml1710.js`). The server must run in offline mode and the bot account (`FaweBot`) must be an operator.

```
npm install
./build-deploy.sh                          # build, check for client-only API use, copy the jar to the server
node run-tests.js --start-server           # basic, data, transform and features suites
node run-tests.js --start-server --suite smoke
node run-tests.js --start-server --locale ja_JP --commands "//help set"
```

Reports are written to `reports/` (`last-report.txt`, `server-errors.txt`).
