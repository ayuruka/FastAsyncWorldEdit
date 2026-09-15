'use strict'
// Forge Mod Loader handshake for Minecraft 1.7.10 (FML protocol 2), for a minecraft-protocol client.
// minecraft-protocol-forge only implements the 1.8+ RegistryData variant, which 1.7.10 servers do not send.
//
// Flow (cpw.mods.fml.common.network.handshake.FMLHandshakeClientState):
//   S: ServerHello(0)  -> C: REGISTER, ClientHello(1), ModList(2)
//   S: ModList(2)      -> C: HandshakeAck(-1, WAITINGSERVERDATA=2)
//   S: ModIdData(3)    -> C: HandshakeAck(-1, WAITINGSERVERCOMPLETE=3)
//   S: HandshakeAck    -> C: HandshakeAck(-1, PENDINGCOMPLETE=4)
//   S: HandshakeAck    -> C: HandshakeAck(-1, COMPLETE=5)
// Payloads larger than one packet arrive split over the "FML|MP" channel.

const FML_PROTOCOL = 2

function varint (n) {
  const out = []
  do {
    let b = n & 0x7f
    n >>>= 7
    if (n !== 0) b |= 0x80
    out.push(b)
  } while (n !== 0)
  return Buffer.from(out)
}

function utf8String (s) {
  const bytes = Buffer.from(s, 'utf8')
  return Buffer.concat([varint(bytes.length), bytes])
}

function readVarint (buf, offset) {
  let value = 0
  let shift = 0
  let pos = offset
  for (;;) {
    const b = buf[pos++]
    value |= (b & 0x7f) << shift
    if ((b & 0x80) === 0) break
    shift += 7
  }
  return { value, next: pos }
}

/** Parses "modid@version,modid@version" as logged by the server for a real client. */
function parseModList (text) {
  const mods = []
  for (const entry of text.trim().split(',')) {
    const at = entry.indexOf('@')
    if (at > 0) mods.push({ modid: entry.slice(0, at), version: entry.slice(at + 1) })
  }
  return mods
}

/**
 * @param client minecraft-protocol client, before it connects
 * @param {{modid: string, version: string}[]} mods mod list to announce
 * @param log optional logger
 */
function install (client, mods, log = () => {}) {
  client.tagHost = '\0FML\0'
  let serverAcks = 0
  let multipart = null

  const sendHs = (bytes) => client.write('custom_payload', { channel: 'FML|HS', data: Buffer.from(bytes) })
  const ack = (phase) => sendHs([0xff, phase])

  function onHandshake (data) {
    const discriminator = data.readInt8(0)
    switch (discriminator) {
      case 0: // ServerHello
        log(`FML ServerHello (protocol ${data[1]})`)
        client.write('custom_payload', { channel: 'REGISTER', data: Buffer.from('FML|HS\0FML', 'utf8') })
        sendHs([1, FML_PROTOCOL])
        client.write('custom_payload', {
          channel: 'FML|HS',
          data: Buffer.concat([Buffer.from([2]), varint(mods.length),
            ...mods.flatMap(m => [utf8String(m.modid), utf8String(m.version)])])
        })
        break
      case 2: { // ModList
        const count = readVarint(data, 1).value
        log(`FML server ModList (${count} mods)`)
        ack(2)
        break
      }
      case 3: // ModIdData
        log(`FML ModIdData (${data.length} bytes)`)
        ack(3)
        break
      case -1: // HandshakeAck from the server
        serverAcks++
        ack(serverAcks === 1 ? 4 : 5)
        if (serverAcks >= 2) {
          log('FML handshake complete')
          client.emit('fml_handshake_complete')
        }
        break
      case -2: // HandshakeReset
        serverAcks = 0
        break
      default:
        log(`FML unknown handshake discriminator ${discriminator}`)
    }
  }

  client.on('custom_payload', (packet) => {
    if (packet.channel === 'FML|HS') {
      onHandshake(packet.data)
    } else if (packet.channel === 'FML|MP') {
      const data = packet.data
      if (multipart === null) {
        const len = readVarint(data, 0)
        const channel = data.subarray(len.next, len.next + len.value).toString('utf8')
        let pos = len.next + len.value
        const parts = data[pos]
        pos += 1
        const total = data.readInt32BE(pos)
        multipart = { channel, parts, total, chunks: [] }
      } else {
        multipart.chunks.push(data.subarray(1))
        if (multipart.chunks.length === multipart.parts) {
          const whole = Buffer.concat(multipart.chunks).subarray(0, multipart.total)
          const channel = multipart.channel
          multipart = null
          if (channel === 'FML|HS') onHandshake(whole)
        }
      }
    }
  })
}

module.exports = { install, parseModList }
