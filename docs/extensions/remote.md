# Remote (Pluggable) Actors

This project now supports a pluggable remoting model so you can send messages to actors running in other processes or hosts using different transports. A minimal MicroStream-based transport skeleton is included as one option.

Key concepts:
- RemotingTransport: SPI for transports (e.g., microstream, http, tcp)
- RemoteActorRef: a lightweight reference you obtain from ActorSystem using a URI
- ActorSystem.enableRemoting(...): register one or more transports

URI format
- scheme://host:port/system/actorName
- Example: microstream://local:0/mySystem/echo

Usage example
1) Enable a transport (MicroStream skeleton) and create a local actor:

  def system = new org.softwood.actor.ActorSystem('mySystem')
  def echo = system.actor {
      name 'echo'
      onMessage { msg, ctx ->
          ctx.reply("echo:${msg}")
      }
  }

  // Enable the microstream transport (loopback when host == 'local')
  system.enableRemoting(new org.softwood.actor.remote.microstream.MicroStreamTransport(system))

2) Obtain a remote ref and interact:

  def remote = system.remote('microstream://local:0/mySystem/echo')
  remote.tell('fire-and-forget')
  def reply = remote.askSync('ping')   // -> "echo:ping"

Transport SPI
- Implement org.softwood.actor.remote.RemotingTransport
- Provide: scheme(), start(), tell(uri,msg), ask(uri,msg,timeout), close()
- Register with system.enableRemoting(new YourTransport(...))

Notes
- The MicroStream transport here is a stub for demonstration. It simulates delivery and provides a loopback to the same JVM when host == 'local'. Replace internals with real MicroStream remoting as needed.
- Local operation is unchanged if you do not enable any transport.