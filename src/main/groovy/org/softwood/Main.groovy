package org.softwood

import org.softwood.actor.ActorSystem
import org.softwood.actor.remote.microstream.MicroStreamTransport

/**
 * Minimal smoke demo to exercise the new pluggable remoting support.
 *
 * Creates an ActorSystem, defines a simple echo actor, enables the
 * MicroStream transport (loopback mode), and performs a tell and ask
 * via a remote URI that resolves locally when host == 'local'.
 */
static void main(String[] args) {
    def system = new ActorSystem('demoSystem')
    try {
        // Create a simple echo actor
        def echo = system.actor {
            name 'echo'
            onMessage { msg, ctx ->
                // reply only when asked
                if (ctx.isAskMessage()) {
                    ctx.reply("echo:${msg}")
                } else {
                    println "[echo] received: ${msg}"
                }
            }
        }

        // Enable MicroStream transport with local loopback
        system.enableRemoting(new MicroStreamTransport(system))

        // Obtain a remote reference via URI and interact
        def remote = system.remote('microstream://local:0/demoSystem/echo')
        remote.tell('fire-and-forget')
        def reply = remote.askSync('ping')
        println "Remote ask reply: ${reply}"

        // Print system status
        println system.status
    } finally {
        system.close()
    }
}