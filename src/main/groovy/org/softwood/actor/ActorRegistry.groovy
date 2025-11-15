package org.softwood.actor

import com.hazelcast.core.Hazelcast
import groovy.util.logging.Slf4j
import org.softwood.config.ConfigLoader

import java.util.concurrent.ConcurrentHashMap

@Slf4j
class ActorRegistry {
    private final Map<String, ScopedValueActor> actors

    ActorRegistry() {
        def config = ConfigLoader.loadConfig()

        def finalConf = config.collect {k,v -> "$k = $v"}
        log.debug """
=== Final Configuration ==="
$finalConf
"===========================\
"""
        def useDistributed = ConfigLoader.getBoolean(config, 'distributed', false)
        def clusterName = ConfigLoader.getString(config, 'hazelcast.cluster.name', 'default-cluster')
        def port = ConfigLoader.getInt(config, 'hazelcast.port', 5701)

        log.debug "Configuration: distributed=$useDistributed, cluster=$clusterName"

        if (useDistributed) {
            def hz = Hazelcast.newHazelcastInstance()
            actors = hz.getMap("actors")
            log.info "Using Hazelcast distributed map"
        } else {
            actors = new ConcurrentHashMap<>()
            log.info "Using local ConcurrentHashMap"
        }
    }

    Map<String, ScopedValueActor> getRegistry () {
        actors
    }
}
