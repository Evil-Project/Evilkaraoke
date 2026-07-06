package org.evilproject.evilkaraoke.common.protocol;

public sealed interface ProtocolPacket permits ClientHelloPacket, AudioCommandPacket, AudioStreamChunkPacket, ClientStatusPacket {
}
