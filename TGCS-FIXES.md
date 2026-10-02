# TGCS compatibility fixes

This branch contains local compatibility changes based on upstream commit
`05df8d0d0d8ad510ff1ad391ede6c42d4df57c44` (26.9).
The current local build version is `26.9-tgcs3`.

## Changes

- Add optional `-Dcraftengine.proxy.resource-pack-only=true` mode. It retains
  custom payload and resource pack handling while skipping extra text listeners.
- Restore CraftEngine packet handlers ahead of the native Minecraft codecs after
  login, including LimboAPI compressor restoration without a compression event.
- Reposition the handlers whenever compression is enabled again.
- Synchronize inbound and outbound protocol states independently from the native
  Velocity codecs before interpreting raw packets. Limbo prepared packets can
  bypass CraftEngine's observed state transitions; a stale CONFIGURATION state
  could otherwise interpret a PLAY entity spawn as a plugin message.
- Complete the write promise when cancelling an outbound packet and release the
  corresponding buffers.

## Build and validation

The build uses JDK 26 with Java 21 bytecode output:

```sh
bash gradlew :velocity:test :velocity:shadowJar --no-daemon
```

Three regression tests passed, covering protocol state transitions, compressor
reinsertion, and cancelled-packet buffer/promise handling.

Runtime checks used Velocity 4.1.2-SNAPSHOT build 29, Java 25, Paper 1.21.11,
LimboAuth/LimboAPI, and a Minecraft 1.21.11 client. Login, round-trip server
switching, entity delivery, and resource pack reuse passed without the packet
handling exception during the validation window. This does not establish
compatibility with all proxy builds, client versions, or plugin combinations.

These are local fork changes, not an upstream release.
