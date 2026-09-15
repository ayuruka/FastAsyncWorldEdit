'use strict'
// Automated in-game test for FastAsyncWorldEdit on the Forge 1.7.10 test server.
//
//   node run-tests.js [--start-server] [--auth offline|microsoft] [--username NAME]
//                     [--driver mineflayer|raw] [--host 127.0.0.1] [--port 25599] [--keep-server]
//                     [--suite basic|data|transform|features|all|smoke] [--commands "cmd1;cmd2"] [--locale ja_JP]
//                     [--server-dir DIR] [--java PATH] [--thread-dump] [--debug-queue]
//
// --start-server  launches the test server (and stops it afterwards unless --keep-server)
// --auth          offline needs online-mode=false on the server; microsoft logs in with a real account
//                 (device code on first run, tokens cached in ./.auth)
// --driver        mineflayer (default) or raw minecraft-protocol; Mineflayer does not officially support 1.7.10

const fs = require('fs')
const path = require('path')
const { spawn } = require('child_process')
const mc = require('minecraft-protocol')
const fml = require('./fml1710')

const args = parseArgs(process.argv.slice(2))
// Test server directory and Java 25 binary; override with --server-dir / --java or FAWE_TEST_SERVER / FAWE_TEST_JAVA.
const SERVER_DIR = path.resolve(args['server-dir'] || process.env.FAWE_TEST_SERVER || 'D:\\MinecraftServerBlilud\\fawe-testserver')
const JAVA = args.java || process.env.FAWE_TEST_JAVA || 'C:\\Program Files\\Java\\jdk-25.0.3\\bin\\java.exe'
const HOST = args.host || '127.0.0.1'
const PORT = Number(args.port || 25599)
const AUTH = args.auth || 'offline'
const USERNAME = args.username || 'FaweBot'
const DRIVER = args.driver || 'mineflayer'
const REPORT_DIR = path.join(__dirname, 'reports')

function parseArgs (argv) {
  const out = {}
  for (let i = 0; i < argv.length; i++) {
    if (!argv[i].startsWith('--')) continue
    const key = argv[i].slice(2)
    const next = argv[i + 1]
    if (next === undefined || next.startsWith('--')) out[key] = true
    else { out[key] = next; i++ }
  }
  return out
}

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms))
const stamp = () => new Date().toISOString().slice(11, 19)
const logLines = []
function log (msg) {
  const line = `[${stamp()}] ${msg}`
  logLines.push(line)
  console.log(line)
}

// ---------------------------------------------------------------- server

function startServer () {
  fs.mkdirSync(REPORT_DIR, { recursive: true })
  const out = fs.createWriteStream(path.join(REPORT_DIR, 'server-stdout.log'))
  const extra = args['debug-queue'] ? ['-Dfawe.forge1710.debugQueue=true'] : []
  const proc = spawn(JAVA, ['@java25args.txt', ...extra, '-Dfml.queryResult=confirm', '-Dfawe.forge1710.selftest=true',
    '-Xms2G', '-Xmx4G', '-jar', 'Crucible-1.7.10-java25-fork-server.jar', 'nogui'], { cwd: SERVER_DIR })
  proc.stdout.pipe(out)
  proc.stderr.pipe(out)
  proc.on('exit', code => log(`server exited with code ${code}`))
  return proc
}

async function waitForServer (timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      await mc.ping({ host: HOST, port: PORT, version: '1.7.10' })
      return true
    } catch (e) {
      await sleep(3000)
    }
  }
  return false
}

async function stopServer (proc) {
  if (!proc || proc.exitCode !== null) return
  log('stopping server')
  proc.stdin.write('stop\n')
  const deadline = Date.now() + 120000
  while (proc.exitCode === null && Date.now() < deadline) await sleep(1000)
  if (proc.exitCode === null) {
    log('server did not stop in time, killing it')
    proc.kill()
  }
}

// ---------------------------------------------------------------- client

function chatToText (json) {
  let node
  try { node = JSON.parse(json) } catch (e) { return String(json) }
  const walk = (n) => {
    if (n === null || n === undefined) return ''
    if (typeof n === 'string') return n
    let s = n.text || ''
    if (n.translate) s += n.translate + (n.with ? ' ' + n.with.map(walk).join(', ') : '')
    if (Array.isArray(n.extra)) s += n.extra.map(walk).join('')
    return s
  }
  return walk(node).replace(/§[0-9a-fk-or]/gi, '')
}

function connect () {
  const mods = fml.parseModList(fs.readFileSync(path.join(__dirname, 'client-mods.txt'), 'utf8'))
  const options = {
    host: HOST,
    port: PORT,
    username: USERNAME,
    version: '1.7.10',
    auth: AUTH,
    profilesFolder: path.join(__dirname, '.auth'),
    onMsaCode: (data) => log(`MICROSOFT LOGIN: open ${data.verification_uri} and enter code ${data.user_code}`)
  }
  const client = mc.createClient({ ...options, hideErrors: true })
  fml.install(client, mods, log)

  const session = { client, bot: null, messages: [], position: null, kicked: null }
  client.on('chat', (packet) => {
    const text = chatToText(packet.message)
    session.messages.push(text)
    log(`<chat> ${text}`)
  })
  // The bot never sends movement packets (sending them made the server refuse chat), so it stays where it spawned.
  client.on('position', (p) => {
    if (!session.position && args.locale) {
      // Client settings as the vanilla client sends them; chatFlags 0 = full chat (anything else hides chat).
      client.write('settings', { locale: String(args.locale), viewDistance: 8, chatFlags: 0, chatColors: true, difficulty: 2, showCape: true })
      log(`sent client locale ${args.locale}`)
    }
    session.position = p
  })
  client.on('kick_disconnect', (p) => { session.kicked = chatToText(p.reason); log(`kicked: ${session.kicked}`) })
  client.on('disconnect', (p) => { session.kicked = chatToText(p.reason); log(`disconnected: ${session.kicked}`) })
  // EndlessIDs changes block/entity packet formats, so minecraft-protocol fails to parse some play packets.
  // Those are irrelevant to the chat-driven tests; log each distinct message once.
  const seenErrors = new Set()
  client.on('error', (e) => {
    const key = String(e.message || e).slice(0, 80)
    if (!seenErrors.has(key)) { seenErrors.add(key); log(`client error (ignored): ${key}`) }
  })
  client.on('end', (reason) => log(`connection ended: ${reason}`))

  if (DRIVER === 'mineflayer') {
    const mineflayer = require('mineflayer')
    try {
      session.bot = mineflayer.createBot({ ...options, client })
      session.bot.on('error', (e) => log(`mineflayer error: ${e.stack || e}`))
      session.bot.on('spawn', () => log('mineflayer: spawned'))
    } catch (e) {
      log(`mineflayer could not attach (${e.message}); continuing with the raw protocol client`)
      session.bot = null
    }
  }
  return session
}

function sendChat (session, text) {
  log(`> ${text}`)
  session.client.write('chat', { message: text })
}

/** Sends a command and collects chat until nothing new arrives for quietMs (or maxMs passes). */
async function command (session, text, { quietMs = 1500, maxMs = 20000, until = null } = {}) {
  const start = session.messages.length
  sendChat(session, text)
  const begin = Date.now()
  let lastCount = start
  let lastChange = Date.now()
  while (Date.now() - begin < maxMs) {
    await sleep(100)
    if (session.messages.length !== lastCount) {
      lastCount = session.messages.length
      lastChange = Date.now()
    }
    const replies = session.messages.slice(start)
    if (until && replies.some(m => until.test(m))) {
      await sleep(300)
      return session.messages.slice(start)
    }
    if (!until && replies.length > 0 && Date.now() - lastChange > quietMs) return replies
  }
  return session.messages.slice(start)
}

// ---------------------------------------------------------------- tests

const results = []
function record (name, ok, detail) {
  results.push({ name, ok, detail })
  log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`)
}

const firstNumber = (texts, re) => {
  for (const t of texts) {
    const m = t.match(re)
    if (m) return Number(m[1].replace(/,/g, ''))
  }
  return null
}

async function runTests (session) {
  const pos = session.position
  const bx = Math.floor(pos.x) + 4
  const bz = Math.floor(pos.z) + 4
  const y1 = 200
  const size = { x: 10, y: 5, z: 10 }
  const volume = size.x * size.y * size.z
  const c1 = [bx, y1, bz]
  const c2 = [bx + size.x - 1, y1 + size.y - 1, bz + size.z - 1]
  log(`test region ${c1} -> ${c2} (${volume} blocks)`)

  const joined = (r) => r.join(' | ') || 'no reply'
  const unknown = (texts) => texts.some(t => /Unknown command|commands\.generic\.notFound|不明なコマンド/i.test(t))
  const noPermission = (texts) => texts.some(t => /permission|権限/i.test(t))
  // "Operation completed (500)." / "500 blocks have been replaced." / "Counted: 500"
  const changedCount = (r) => firstNumber(r, /(?:completed \(|^)\s*([\d,]+)\)?\s*(?:blocks? have been \w+|\)|\.)?/i)
  const counted = (r) => firstNumber(r, /Counted:\s*([\d,]+)/i)

  /** Counts blocks straight from the Minecraft world via /faweselftest native. Returns {"id:meta": count}. */
  async function nativeCounts () {
    const r = await command(session, `/faweselftest native ${c1.join(' ')} ${c2.join(' ')}`, { until: /\[SELFTEST\] native/ })
    const line = r.find(t => t.includes('[SELFTEST] native')) || ''
    const out = {}
    for (const m of line.matchAll(/(\S+:\d+)=(\d+)/g)) out[m[1]] = Number(m[2])
    return { out, line }
  }
  async function expectNative (name, key, expected) {
    await sleep(1000)
    const { out, line } = await nativeCounts()
    const n = Object.entries(out).filter(([k]) => k.toLowerCase() === key.toLowerCase()).reduce((a, [, v]) => a + v, 0)
    record(`${name} (native world)`, n === expected, `${key}=${n} (expected ${expected}) — ${line || 'no reply'}`)
  }

  const before = await nativeCounts()
  log(`native state before the test: ${before.line}`)

  let r = await command(session, '//wand')
  record('//wand is recognised', r.length > 0 && !unknown(r) && !noPermission(r), joined(r))

  r = await command(session, `//pos1 ${c1.join(',')}`)
  record('//pos1', r.some(t => /First position set/i.test(t)), joined(r))

  r = await command(session, `//pos2 ${c2.join(',')}`)
  record('//pos2', r.some(t => new RegExp(`\(${volume}\)`).test(t)), joined(r))

  r = await command(session, '//set stone', { until: /completed|changed|error/i, maxMs: 60000 })
  record('//set stone', changedCount(r) === volume, joined(r))
  await expectNative('//set stone', 'minecraft:stone:0', volume)

  r = await command(session, '//count stone', { until: /Counted|error/i })
  record('//count stone', counted(r) === volume, joined(r))

  r = await command(session, '//replace stone glass', { until: /replaced|completed|error/i, maxMs: 60000 })
  record('//replace stone glass', changedCount(r) === volume, joined(r))
  await expectNative('//replace stone glass', 'minecraft:glass:0', volume)

  // A plain solid block: RTM:pigIron_L is a fluid, so Minecraft itself changes its metadata (flow level) after placement.
  const modBlock = args['mod-block'] || 'minatocc_addblocks:mi_concrete'
  const modMeta = 3
  r = await command(session, `//set ${modBlock}[legacy_meta=${modMeta}]`, { until: /completed|changed|error|not|unknown/i, maxMs: 60000 })
  record(`//set ${modBlock}[legacy_meta=${modMeta}]`, changedCount(r) === volume, joined(r))
  await expectNative(`//set ${modBlock}`, `${modBlock}:${modMeta}`, volume)

  for (let i = 1; i <= 3; i++) {
    r = await command(session, '//undo', { until: /Undid|nothing|error/i })
    record(`//undo #${i}`, r.some(t => /Undid/i.test(t)), joined(r))
    await sleep(500)
  }
  await sleep(1000)
  const after = await nativeCounts()
  const same = JSON.stringify(after.out) === JSON.stringify(before.out)
  record('undo x3 restores the original blocks (native world)', same, `before: ${before.line} / after: ${after.line}`)

  const airBefore = before.out['minecraft:air:0'] || 0
  r = await command(session, '//count air', { until: /Counted|error/i })
  record('//count air matches the native world after undo', counted(r) === airBefore, `${joined(r)} (native air ${airBefore})`)
}


/** Tile entities, entities, biomes, clipboard and schematics (milestone M3). */
async function runDataTests (session) {
  const pos = session.position
  const P = [Math.floor(pos.x), Math.floor(pos.y), Math.floor(pos.z)]
  const bx = P[0] + 4
  const bz = P[2] + 4
  const A1 = [bx, 200, bz]
  const A2 = [bx + 9, 204, bz + 9]
  const chest = [bx + 1, 200, bz + 1]
  const sign = [bx + 3, 200, bz + 1]
  const joined = (r) => r.join(' | ') || 'no reply'
  const selftest = async (args, marker) => {
    const r = await command(session, `/faweselftest ${args}`, { until: new RegExp(`\\[SELFTEST\\] ${marker}`) })
    return r.find(t => t.includes(`[SELFTEST] ${marker}`)) || ''
  }
  const at = (v, dx = 0) => `${v[0] + dx} ${v[1]} ${v[2]}`
  const select = async (a, b) => {
    await command(session, `//pos1 ${a.join(',')}`)
    await command(session, `//pos2 ${b.join(',')}`)
  }
  const chestOk = (line) => /Items/.test(line) && /id:264s/.test(line) && /Count:7b/.test(line)
  const signOk = (line) => /Text1:"FAWE"/.test(line)
  const crystals = (line) => { const m = line.match(/EnderCrystal=(\d+)/); return m ? Number(m[1]) : 0 }

  log(`data test region ${A1} -> ${A2}`)
  // Clean the area (blocks and leftover entities). 1.7.10 has no entity selectors (/kill would kill the bot).
  await select(A1, A2)
  await command(session, '//cut -e', { until: /cut|error/i, maxMs: 60000 })

  await command(session, `/setblock ${at(chest)} chest 0 replace {Items:[{Slot:0b,id:264s,Count:7b,Damage:0s}]}`)
  await command(session, `/setblock ${at(sign)} standing_sign 0 replace {Text1:"FAWE"}`)
  await command(session, `/summon EnderCrystal ${bx + 5.5} 202 ${bz + 5.5}`)
  await sleep(500)
  let line = await selftest(`tile ${at(chest)}`, 'tile')
  record('setup: chest with 7 diamonds', chestOk(line), line)

  // Clipboard round trip in place: //copy -e, //cut -e (removes blocks, tiles and entities), then //paste -e -o puts
  // everything back at the original position.
  let r = await command(session, '//copy -e', { until: /entit|copied|error/i, maxMs: 60000 })
  record('//copy -e counts the entity', r.some(t => /1 entit/i.test(t)), joined(r))
  r = await command(session, '//cut -e', { until: /entit|cut|error/i, maxMs: 60000 })
  await sleep(1500)
  line = await selftest(`tile ${at(chest)}`, 'tile')
  let lineE = await selftest(`entities ${A1.join(' ')} ${A2.join(' ')}`, 'entities')
  record('//cut -e removes tiles and entities (native)', /none/.test(line) && crystals(lineE) === 0, `${joined(r)} / ${line} / ${lineE}`)

  r = await command(session, '//paste -e -o', { until: /pasted|error/i, maxMs: 60000 })
  record('//paste -e -o', r.some(t => /pasted/i.test(t)), joined(r))
  await sleep(1500)
  line = await selftest(`tile ${at(chest)}`, 'tile')
  record('paste keeps chest contents (native)', chestOk(line), line)
  line = await selftest(`tile ${at(sign)}`, 'tile')
  record('paste keeps sign text (native)', signOk(line), line)
  line = await selftest(`entities ${A1.join(' ')} ${A2.join(' ')}`, 'entities')
  record('paste -e restores the entity (native)', crystals(line) === 1, line)

  r = await command(session, '//undo', { until: /Undid|nothing|error/i })
  await sleep(1500)
  line = await selftest(`tile ${at(chest)}`, 'tile')
  lineE = await selftest(`entities ${A1.join(' ')} ${A2.join(' ')}`, 'entities')
  record('undo paste removes tiles and entities again (native)', /none/.test(line) && crystals(lineE) === 0, `${joined(r)} / ${line} / ${lineE}`)

  r = await command(session, '//schem save fawe-bot-test -f', { until: /saved|error|exist/i, maxMs: 60000 })
  record('//schem save', r.some(t => /saved/i.test(t)), joined(r))
  r = await command(session, '//schem load fawe-bot-test', { until: /loaded|error|not/i, maxMs: 60000 })
  record('//schem load', r.some(t => /loaded/i.test(t)), joined(r))
  r = await command(session, '//paste -e -o', { until: /pasted|error/i, maxMs: 60000 })
  await sleep(1500)
  line = await selftest(`tile ${at(chest)}`, 'tile')
  record('schematic paste keeps chest contents (native)', chestOk(line), `${joined(r)} / ${line}`)
  line = await selftest(`tile ${at(sign)}`, 'tile')
  record('schematic paste keeps sign text (native)', signOk(line), line)
  line = await selftest(`entities ${A1.join(' ')} ${A2.join(' ')}`, 'entities')
  record('schematic paste -e restores the entity (native)', crystals(line) === 1, line)

  await select(A1, A2)
  const biomeBefore = await selftest(`biome ${A1[0]} ${A1[2]}`, 'biome')
  r = await command(session, '//setbiome minecraft:desert', { until: /biome|changed|error/i, maxMs: 60000 })
  await sleep(1500)
  line = await selftest(`biome ${A1[0]} ${A1[2]}`, 'biome')
  record('//setbiome desert (native)', /Desert/.test(line), `${joined(r)} / ${line}`)
  await command(session, '//undo', { until: /Undid|nothing|error/i })
  await sleep(1500)
  line = await selftest(`biome ${A1[0]} ${A1[2]}`, 'biome')
  record('undo setbiome restores the biome (native)', line.split(': ')[1] === biomeBefore.split(': ')[1], `${biomeBefore} -> ${line}`)

  r = await command(session, '//replace chest stone', { until: /replaced|error/i, maxMs: 60000 })
  await sleep(1000)
  line = await selftest(`tile ${at(chest)}`, 'tile')
  record('replacing a chest removes its tile entity (native)', /none/.test(line), `${joined(r)} / ${line}`)
  await command(session, '//undo', { until: /Undid|nothing|error/i })
  await sleep(1500)
  line = await selftest(`tile ${at(chest)}`, 'tile')
  record('undo restores the chest contents (native)', chestOk(line), line)
}

/** Rotation and flipping of metadata-oriented blocks, vanilla and modded (milestone M4). */
async function runTransformTests (session) {
  const pos = session.position
  const P = [Math.floor(pos.x), Math.floor(pos.z)]
  const y = 200
  const joined = (r) => r.join(' | ') || 'no reply'
  const area1 = `${P[0] - 12} ${y} ${P[1] - 12}`
  const area2 = `${P[0] + 12} ${y} ${P[1] + 12}`
  const scan = async () => {
    const r = await command(session, `/faweselftest native ${area1} ${area2}`, { until: /\[SELFTEST\] native/ })
    const line = r.find(t => t.includes('[SELFTEST] native')) || ''
    const out = {}
    for (const m of line.matchAll(/(\S+:\d+)=(\d+)/g)) out[m[1].toLowerCase()] = Number(m[2])
    return { out, line }
  }
  const nativeAt = async (x, z) => {
    const r = await command(session, `/faweselftest native ${x} ${y} ${z} ${x} ${y} ${z}`, { until: /\[SELFTEST\] native/ })
    const line = r.find(t => t.includes('[SELFTEST] native')) || ''
    const m = line.match(/native 1: (\S+)=1/)
    return m ? m[1].toLowerCase() : line
  }
  const clear = async () => {
    await command(session, `//pos1 ${area1.replace(/ /g, ',')}`)
    await command(session, `//pos2 ${area2.replace(/ /g, ',')}`)
    await command(session, '//set air', { until: /completed|error/i, maxMs: 60000 })
  }
  // Copies the single block at (ax, az), applies the clipboard transform, pastes at the original origin.
  const transformBlock = async (ax, az, transformCommand) => {
    await command(session, `//pos1 ${ax},${y},${az}`)
    await command(session, `//pos2 ${ax},${y},${az}`)
    await command(session, '//copy', { until: /affected|copied|error/i })
    await command(session, transformCommand, { until: /rotated|flipped|error/i })
    return command(session, '//paste -o', { until: /pasted|error/i, maxMs: 60000 })
  }

  const A = [P[0] + 4, P[1] + 3]
  log(`transform test around ${P}, y=${y}`)

  await clear()
  await command(session, `/setblock ${A[0]} ${y} ${A[1]} oak_stairs 0`)
  let r = await transformBlock(A[0], A[1], '//rotate 180')
  await sleep(1000)
  let got = await nativeAt(2 * P[0] - A[0], 2 * P[1] - A[1])
  record('//rotate 180: stairs east (0) -> west (1) at the mirrored position', got === 'minecraft:oak_stairs:1', `${joined(r)} / ${got}`)

  await clear()
  await command(session, `/setblock ${A[0]} ${y} ${A[1]} oak_stairs 0`)
  r = await transformBlock(A[0], A[1], '//flip east')
  await sleep(1000)
  got = await nativeAt(2 * P[0] - A[0], A[1])
  record('//flip east: stairs east (0) -> west (1)', got === 'minecraft:oak_stairs:1', `${joined(r)} / ${got}`)

  await clear()
  await command(session, `/setblock ${A[0]} ${y} ${A[1]} log 4`)
  r = await transformBlock(A[0], A[1], '//rotate 90')
  await sleep(1000)
  let s = await scan()
  // The paste lands elsewhere (rotation around the player), so the source log stays; exactly one rotated copy must exist.
  record('//rotate 90: log along x (4) -> along z (8)', s.out['minecraft:log:8'] === 1, `${joined(r)} / ${s.line}`)

  await clear()
  await command(session, `/setblock ${A[0]} ${y} ${A[1]} chest 2`)
  // A chest picks its own facing when placed, so read what it really is before rotating.
  const source = await nativeAt(A[0], A[1])
  const opposite = { 2: 3, 3: 2, 4: 5, 5: 4 }[Number(source.split(':').pop())]
  r = await transformBlock(A[0], A[1], '//rotate 180')
  await sleep(1000)
  got = await nativeAt(2 * P[0] - A[0], 2 * P[1] - A[1])
  record('//rotate 180: chest faces the opposite way', got === `minecraft:chest:${opposite}`, `${source} -> ${got} / ${joined(r)}`)

  const modStairs = args['mod-stairs'] || 'minatocc_addblocks:mi_tile_small_01_stairs'
  await clear()
  await command(session, `/setblock ${A[0]} ${y} ${A[1]} ${modStairs} 0`)
  r = await transformBlock(A[0], A[1], '//rotate 180')
  await sleep(1000)
  got = await nativeAt(2 * P[0] - A[0], 2 * P[1] - A[1])
  record(`//rotate 180: mod stairs ${modStairs} 0 -> 1`, got === `${modStairs}:1`.toLowerCase(), `${joined(r)} / ${got}`)

  await clear()
}

/**
 * Writes a gzipped MCEdit schematic one block high and one deep: blocks[i] are numeric ids (up to 4095, the high bits
 * go to AddBlocks), data[i] their metadata.
 */
function writeLegacySchematic (file, blocks, data) {
  const zlib = require('zlib')
  const parts = []
  const str = (s) => { const b = Buffer.from(s, 'utf8'); const h = Buffer.alloc(2); h.writeUInt16BE(b.length); return Buffer.concat([h, b]) }
  const named = (type, name, payload) => Buffer.concat([Buffer.from([type]), str(name), payload])
  const i16 = (v) => { const b = Buffer.alloc(2); b.writeInt16BE(v); return b }
  const i32 = (v) => { const b = Buffer.alloc(4); b.writeInt32BE(v); return b }
  const bytes = (values) => Buffer.concat([i32(values.length), Buffer.from(values.map(v => v & 0xFF))])
  const add = new Array(Math.ceil(blocks.length / 2)).fill(0)
  blocks.forEach((id, i) => {
    const high = (id >> 8) & 0xF
    // Even indices use the low nibble, odd indices the high nibble (MCEdit / WorldEdit convention).
    add[i >> 1] |= (i & 1) === 0 ? high : high << 4
  })
  parts.push(named(2, 'Width', i16(blocks.length)), named(2, 'Height', i16(1)), named(2, 'Length', i16(1)))
  parts.push(named(8, 'Materials', str('Alpha')), named(7, 'Blocks', bytes(blocks)), named(7, 'Data', bytes(data)))
  parts.push(named(7, 'AddBlocks', bytes(add)))
  parts.push(named(9, 'Entities', Buffer.concat([Buffer.from([10]), i32(0)])))
  parts.push(named(9, 'TileEntities', Buffer.concat([Buffer.from([10]), i32(0)])))
  for (const key of ['WEOriginX', 'WEOriginY', 'WEOriginZ', 'WEOffsetX', 'WEOffsetY', 'WEOffsetZ']) parts.push(named(3, key, i32(0)))
  const root = named(10, 'Schematic', Buffer.concat([...parts, Buffer.from([0])]))
  fs.writeFileSync(file, zlib.gzipSync(root))
}

/** Block state syntax, old names, trees, snow and regeneration, checked in the native world. */
async function runFeatureTests (session) {
  const pos = session.position
  const P = [Math.floor(pos.x), Math.floor(pos.z)]
  const joined = (r) => r.join(' | ') || 'no reply'
  const scan = async (a, b) => {
    const r = await command(session, `/faweselftest native ${a.join(' ')} ${b.join(' ')}`, { until: /\[SELFTEST\] native/ })
    const line = r.find(t => t.includes('[SELFTEST] native')) || ''
    const out = {}
    for (const m of line.matchAll(/(\S+:\d+)=(\d+)/g)) out[m[1].toLowerCase()] = Number(m[2])
    return { out, line, count: (prefix) => Object.entries(out).filter(([k]) => k.startsWith(prefix)).reduce((s, [, v]) => s + v, 0) }
  }
  const select = async (a, b) => {
    await command(session, `//pos1 ${a.join(',')}`)
    await command(session, `//pos2 ${b.join(',')}`)
  }
  const done = /completed|changed|affected|created|removed|replaced|error|exception/i

  // Modern block state syntax and 1.7.10 names on single blocks.
  const one = [P[0] + 3, 200, P[1] - 3]
  await select(one, one)
  let r = await command(session, '//set oak_stairs[facing=west,half=top]', { until: done })
  let s = await scan(one, one)
  record('//set oak_stairs[facing=west,half=top] -> native oak_stairs meta 5', s.out['minecraft:oak_stairs:5'] === 1, `${joined(r)} / ${s.line}`)
  r = await command(session, '//set orange_wool', { until: done })
  s = await scan(one, one)
  record('//set orange_wool -> native wool meta 1', s.out['minecraft:wool:1'] === 1, `${joined(r)} / ${s.line}`)
  r = await command(session, '//set wool', { until: done })
  s = await scan(one, one)
  record('//set wool (1.7.10 name) -> native wool meta 0', s.out['minecraft:wool:0'] === 1, `${joined(r)} / ${s.line}`)
  // //set hand keeps the held block's metadata (orange wool is wool:1).
  await command(session, `/clear ${USERNAME}`)
  await command(session, `/give ${USERNAME} wool 1 1`)
  await sleep(500)
  r = await command(session, '//set hand', { until: done })
  s = await scan(one, one)
  record('//set hand with orange wool -> native wool meta 1', s.out['minecraft:wool:1'] === 1, `${joined(r)} / ${s.line}`)
  await command(session, '//set air', { until: done })

  // WorldEditCUI: announce the client, then a selection change must arrive on the WECUI channel.
  const cui = []
  session.client.on('custom_payload', (packet) => {
    if (packet.channel === 'WECUI') cui.push(packet.data.toString('utf8'))
  })
  session.client.write('custom_payload', { channel: 'REGISTER', data: Buffer.from('WECUI', 'utf8') })
  session.client.write('custom_payload', { channel: 'WECUI', data: Buffer.from('v|4', 'utf8') })
  await sleep(1000)
  await command(session, `//pos1 ${one.join(',')}`)
  await sleep(1000)
  record('WorldEditCUI receives the selection over WECUI', cui.some(m => m.startsWith('p|0|')), cui.join(' ; ') || 'nothing received')

  // Ground for trees and snow: grass at y=199 with air above.
  const g1 = [P[0] + 5, 199, P[1] + 5]
  const g2 = [P[0] + 20, 199, P[1] + 20]
  const air1 = [g1[0], 200, g1[2]]
  const air2 = [g2[0], 230, g2[2]]
  await select(air1, air2)
  await command(session, '//set air', { until: done, maxMs: 60000 })
  await select(g1, g2)
  r = await command(session, '//set grass_block', { until: done, maxMs: 60000 })
  s = await scan(g1, g2)
  record('//set grass_block -> native grass', s.count('minecraft:grass:') === 256, `${joined(r)} / ${s.line}`)

  // The region must contain the ground: //forest looks for the top block in each column of the selection.
  await select(g1, [g2[0], 200, g2[2]])
  r = await command(session, '//forest oak 20', { until: /trees|created|error/i, maxMs: 60000 })
  await sleep(1500)
  s = await scan(air1, air2)
  const logs = s.count('minecraft:log:')
  record('//forest oak places trees (native logs)', logs > 0, `${joined(r)} / logs=${logs}`)
  r = await command(session, '//undo', { until: /Undid|nothing|error/i })
  await sleep(1500)
  s = await scan(air1, air2)
  record('undo //forest removes the trees', s.count('minecraft:log:') === 0 && s.count('minecraft:leaves:') === 0, `${joined(r)} / ${s.line.slice(0, 200)}`)

  // //snow works in a radius around the player (the bot stands on the spawn terrain).
  r = await command(session, '//snow 6', { until: done, maxMs: 60000 })
  await sleep(1500)
  const py = Math.floor(pos.y)
  s = await scan([P[0] - 6, py - 12, P[1] - 6], [P[0] + 6, py + 4, P[1] + 6])
  const snowCount = s.count('minecraft:snow_layer:')
  record('//snow places snow layers around the player', snowCount > 0, `${joined(r)} / snow_layer=${snowCount}`)
  r = await command(session, '//undo', { until: /Undid|nothing|error/i })
  await sleep(1500)
  s = await scan([P[0] - 6, py - 12, P[1] - 6], [P[0] + 6, py + 4, P[1] + 6])
  record('undo //snow removes the snow', s.count('minecraft:snow_layer:') < snowCount, `${joined(r)} / snow_layer=${s.count('minecraft:snow_layer:')}`)

  // Lighting after fast writes: a light source in a closed cave lights its neighbours, and removing it darkens them.
  const cave1 = [P[0] + 30, 20, P[1]]
  const cave2 = [P[0] + 34, 24, P[1] + 4]
  const lamp = [P[0] + 32, 22, P[1] + 2]
  const nextToLamp = [lamp[0] + 1, lamp[1], lamp[2]]
  const light = async (p) => {
    const rr = await command(session, `/faweselftest light ${p.join(' ')}`, { until: /\[SELFTEST\] light/ })
    const line = rr.find(t => t.includes('[SELFTEST] light')) || ''
    const m = line.match(/block=(\d+)/)
    return { block: m ? Number(m[1]) : -1, line }
  }
  await select(cave1, cave2)
  await command(session, '//set stone', { until: done, maxMs: 60000 })
  await select([cave1[0] + 1, 21, cave1[2] + 1], [cave2[0] - 1, 23, cave2[2] - 1])
  await command(session, '//set air', { until: done, maxMs: 60000 })
  await select(lamp, lamp)
  r = await command(session, '//set glowstone', { until: done })
  await sleep(4000)
  let l = await light(nextToLamp)
  record('fast path: glowstone lights the block next to it', l.block === 14, `${joined(r)} / ${l.line}`)
  r = await command(session, '//set air', { until: done })
  await sleep(4000)
  l = await light(nextToLamp)
  record('fast path: removing the glowstone darkens the cave', l.block === 0, `${joined(r)} / ${l.line}`)

  // Old MCEdit schematic with numeric ids, including a modded block above 255 (AddBlocks).
  const idReply = await command(session, `/faweselftest id minatocc_addblocks:mi_concrete`, { until: /\[SELFTEST\] id/ })
  const modId = Number((idReply.find(t => t.includes('[SELFTEST] id')) || '').split(': ').pop())
  if (modId > 0) {
    writeLegacySchematic(path.join(SERVER_DIR, 'config', 'worldedit', 'schematics', 'bot-legacy.schematic'),
      [1, 53, 35, modId], [0, 0, 1, 3])
    r = await command(session, '//schem load bot-legacy.schematic', { until: /loaded|error|not/i })
    // The schematic's origin is 0,0,0, so //paste -o places it at the world origin (bedrock layer of the test world).
    await command(session, '//paste -o', { until: /pasted|error/i })
    s = await scan([0, 0, 0], [3, 0, 0])
    record('legacy MCEdit schematic with numeric ids (vanilla and modded id ' + modId + ')',
      s.out['minecraft:stone:0'] === 1 && s.out['minecraft:oak_stairs:0'] === 1 && s.out['minecraft:wool:1'] === 1 &&
      s.out['minatocc_addblocks:mi_concrete:3'] === 1, `${joined(r)} / ${s.line}`)
    await command(session, '//undo', { until: /Undid|nothing|error/i })
  } else {
    record('legacy MCEdit schematic with numeric ids', false, 'could not read the modded block id')
  }

  // Regeneration: fill part of the terrain with glass, regenerate it, undo brings the glass back.
  const t1 = [P[0] - 20, 40, P[1] - 20]
  const t2 = [P[0] - 12, 45, P[1] - 12]
  await select(t1, t2)
  await command(session, '//set glass', { until: done, maxMs: 60000 })
  r = await command(session, '//regen', { until: /regenerated|error|unable/i, maxMs: 120000 })
  await sleep(2000)
  s = await scan(t1, t2)
  record('//regen replaces the glass with generated terrain', s.count('minecraft:glass:') === 0 && s.count('minecraft:stone:') > 0, `${joined(r)} / ${s.line.slice(0, 200)}`)
  r = await command(session, '//undo', { until: /Undid|nothing|error/i, maxMs: 60000 })
  await sleep(2000)
  s = await scan(t1, t2)
  record('undo //regen restores the previous blocks', s.count('minecraft:glass:') === 486, `${joined(r)} / ${s.line.slice(0, 200)}`)
  await command(session, '//undo', { until: /Undid|nothing|error/i, maxMs: 60000 })
}

/**
 * Runs many commands once to find features that are not ported yet: a command fails if it gives no reply or its reply
 * looks like an error. Server-side exceptions are collected from the server log by the caller.
 */
async function runSmokeTests (session) {
  const pos = session.position
  const P = [Math.floor(pos.x), Math.floor(pos.z)]
  const c1 = `${P[0] + 4},200,${P[1] + 4}`
  const c2 = `${P[0] + 12},206,${P[1] + 12}`
  const errorLike = /error|exception|unsupported|not supported|unknown command|could not|couldn't|failed|cannot|can't|invalid|not found|no .* found|予期|できません|見つかりません|不明/i
  const commands = [
    `/clear ${USERNAME}`, `//pos1 ${c1}`, `//pos2 ${c2}`, '//size', '//count stone', '//distr',
    '//set stone', '//walls glass', '//faces sandstone', '//outline cobblestone', '//replace glass wool',
    '//overlay grass', '//naturalize', '//hollow', '//smooth 1', '//center glowstone', '//line obsidian',
    '//move 2 up', '//stack 1 north', '//deform y-=0.2', '//fill water 3', '//drain 4', '//fixwater 4', '//snow 5',
    '//thaw 5', '//green 5', '//extinguish 5', '//removeabove 2', '//removebelow 2', '//removenear stone 2',
    '//replacenear 3 stone dirt', '//sphere stone 2', '//hsphere glass 3', '//cyl stone 2 3', '//hcyl glass 3 3',
    '//pyramid stone 3', '//hpyramid glass 3', '//generate stone (x*x+y*y+z*z)<4', '//forestgen 5 oak 5', '//flora 5',
    `/give ${USERNAME} stick`, '//tree oak', '//regen', '//setbiome minecraft:forest', '/biomeinfo', '//biomelist', '//chunkinfo', '//listchunks',
    '//expand 1 up', '//contract 1 up', '//shift 1 up', '//outset 1', '//inset 1', '//sel poly', '//sel cuboid',
    `//pos1 ${c1}`, `//pos2 ${c2}`, '//copy', '//rotate 90', '//flip', '//paste -a', '//clearclipboard',
    '//schem list', '//snapshot list', '//butcher 20', '//remove items 20', '//fixlighting', '//calc 1+2', '//help set',
    '//searchitem diamond', '//limit 100000', '//fast', '//fast', '//gmask stone', '//gmask', '//ex 3', '//up 1',
    '//ascend', '//descend', '//ceil', '//thru', '//jumpto', '//unstuck', '/fawe version', '//history list',
    '//undo 10', '//redo', '//clearhistory', '//desel'
  ]
  for (const cmd of commands) {
    const r = await command(session, cmd, { quietMs: 1500, maxMs: 30000 })
    const text = r.join(' | ')
    const ok = r.length > 0 && !errorLike.test(text)
    record(`smoke: ${cmd}`, ok, text || 'no reply')
  }
}

// ---------------------------------------------------------------- main

async function main () {
  let server = null
  let exitCode = 1
  try {
    if (args['start-server']) {
      if (await waitForServer(1)) {
        throw new Error(`a server is already answering on ${HOST}:${PORT}; stop it first (or run without --start-server)`)
      }
      log(`starting test server in ${SERVER_DIR}`)
      server = startServer()
    }
    log(`waiting for ${HOST}:${PORT}`)
    if (!await waitForServer(args['start-server'] ? 600000 : 30000)) throw new Error('server did not answer pings')
    if (server) await sleep(5000)

    log(`connecting as ${USERNAME} (auth=${AUTH}, driver=${DRIVER})`)
    const session = connect()
    const joinDeadline = Date.now() + 120000
    while (!session.position && !session.kicked && Date.now() < joinDeadline) await sleep(250)
    if (!session.position) throw new Error(`did not join the world: ${session.kicked || 'timeout'}`)
    log(`joined at ${session.position.x.toFixed(1)}, ${session.position.y.toFixed(1)}, ${session.position.z.toFixed(1)}`)
    await sleep(3000)

    await command(session, '/gamemode 1')
    if (args.commands) {
      for (const cmd of String(args.commands).split(';').map(c => c.trim()).filter(Boolean)) {
        const r = await command(session, cmd, { quietMs: 2000, maxMs: 8000 })
        record(`manual: ${cmd}`, true, r.join(' | ') || 'no reply')
        if (args['thread-dump'] && server) {
          const file = path.join(REPORT_DIR, `threads-${results.length}.txt`)
          require('child_process').execFileSync(path.join(path.dirname(JAVA), 'jcmd.exe'), [String(server.pid), 'Thread.print'],
            { stdio: ['ignore', fs.openSync(file, 'w'), 'ignore'] })
          log(`thread dump written to ${file}`)
        }
      }
    } else {
      const suite = args.suite || 'all'
      if (suite === 'all' || suite === 'basic') await runTests(session)
      if (suite === 'all' || suite === 'data') await runDataTests(session)
      if (suite === 'all' || suite === 'transform') await runTransformTests(session)
      if (suite === 'all' || suite === 'features') await runFeatureTests(session)
      if (suite === 'smoke') await runSmokeTests(session)
    }
    session.client.end('tests finished')
    exitCode = results.length > 0 && results.every(r => r.ok) ? 0 : 1
  } catch (e) {
    log(`ERROR: ${e.stack || e}`)
  } finally {
    if (server && !args['keep-server']) await stopServer(server)
    if (server) {
      // Server-side errors, which FAWE often only logs.
      try {
        const lines = fs.readFileSync(path.join(SERVER_DIR, 'logs', 'latest.log'), 'utf8').split(/\r?\n/)
        const errors = lines.filter(l => /\/(ERROR|FATAL)\]|Exception|Caused by/.test(l) && !/Unknown (block|item):|Unable to find team/.test(l))
        fs.writeFileSync(path.join(REPORT_DIR, 'server-errors.txt'), errors.join('\n'))
        log(`server log: ${errors.length} error lines (reports/server-errors.txt)`)
      } catch (e) {
        log(`could not read server log: ${e.message}`)
      }
    }
    writeReport()
  }
  process.exit(exitCode)
}

function writeReport () {
  fs.mkdirSync(REPORT_DIR, { recursive: true })
  const passed = results.filter(r => r.ok).length
  const summary = `FAWE Forge 1.7.10 test: ${passed}/${results.length} passed`
  const body = [summary, '', ...results.map(r => `${r.ok ? 'PASS' : 'FAIL'}  ${r.name}\n      ${r.detail || ''}`), '', '--- log ---', ...logLines].join('\n')
  const file = path.join(REPORT_DIR, `report-${new Date().toISOString().replace(/[:.]/g, '-')}.txt`)
  fs.writeFileSync(file, body)
  fs.writeFileSync(path.join(REPORT_DIR, 'last-report.txt'), body)
  console.log(`\n${summary}\nreport: ${file}`)
}

main()
