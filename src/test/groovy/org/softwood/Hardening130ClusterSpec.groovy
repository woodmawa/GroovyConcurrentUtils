package org.softwood

import com.hazelcast.config.Config
import groovy.transform.CompileDynamic
import org.softwood.cluster.HazelcastManager
import org.softwood.cluster.HazelcastSecurityConfig
import org.softwood.dag.cluster.ClusterTaskEvent
import spock.lang.Specification
import spock.lang.Timeout

/**
 * GCU 1.3.0: cluster hardening on open-source Hazelcast (decision D11, brief
 * gcu/brief-core-hardening-2026-11-18).
 *
 * With a security configuration, task events are signed when published and checked when received,
 * and Java deserialisation inside the cluster is limited to an allow-list. No cluster is started by
 * this spec: it works on the built configuration and on the manager's signing methods.
 * Each feature was run red against 1.2.2.
 */
@CompileDynamic
@Timeout(60)
class Hardening130ClusterSpec extends Specification {

    static final String KEY = 'h130-signing-key-h130-signing-key-0123456789'

    private static HazelcastSecurityConfig signing() {
        HazelcastSecurityConfig.builder()
                .disableMulticast(true)
                .allowedMembers(['10.0.0.1:5701'])
                .messageSigningEnabled(true)
                .messageSigningKey(KEY)
                .build()
    }

    def cleanup() {
        try {
            HazelcastManager.instance.useMessageSigning(null)
        } catch (Throwable ignored) {
        }
    }

    def "with a security configuration, Java deserialisation in the cluster is limited to an allow-list"() {
        given:
        def security = HazelcastSecurityConfig.builder()
                .disableMulticast(true)
                .allowedMembers(['10.0.0.1:5701'])
                .serializationAllowedPrefixes(['com.acme.orders.'])
                .build()

        when:
        Config cfg = HazelcastManager.buildConfig([enabled: true], security)
        def filter = cfg.serializationConfig.javaSerializationFilterConfig

        then:
        filter != null
        filter.whitelist.prefixes.contains('org.softwood.dag.cluster.')
        filter.whitelist.prefixes.contains('org.softwood.actor.cluster.')
        filter.whitelist.prefixes.contains('com.acme.orders.')
    }

    def "with no security configuration the built configuration is as before: no filter"() {
        when:
        Config cfg = HazelcastManager.buildConfig([enabled: true, cluster: [members: ['10.0.0.1:5701']]], null)

        then:
        cfg.serializationConfig.javaSerializationFilterConfig == null
    }

    def "with signing configured, a published task event is signed and an event that verifies is accepted"() {
        given:
        def manager = HazelcastManager.instance
        manager.useMessageSigning(signing())
        def event = new ClusterTaskEvent('t1', 'run-1', 'COMPLETED', 'node-a')

        when:
        def outgoing = manager.signIfConfigured(event)

        then:
        outgoing.signature != null
        manager.acceptTaskEvent(outgoing)
    }

    def "with signing configured, an unsigned event and a tampered event are refused"() {
        given:
        def manager = HazelcastManager.instance
        manager.useMessageSigning(signing())
        def unsigned = new ClusterTaskEvent('t1', 'run-1', 'COMPLETED', 'node-a')
        def tampered = manager.signIfConfigured(new ClusterTaskEvent('t2', 'run-1', 'FAILED', 'node-a'))
        tampered.state = 'COMPLETED'

        expect:
        !manager.acceptTaskEvent(unsigned)
        !manager.acceptTaskEvent(tampered)
        !manager.acceptTaskEvent(null)
    }

    def "an event signed with another key is refused"() {
        given:
        def manager = HazelcastManager.instance
        manager.useMessageSigning(HazelcastSecurityConfig.builder()
                .disableMulticast(true).allowedMembers(['10.0.0.1:5701'])
                .messageSigningEnabled(true).messageSigningKey('another-key-another-key-another-key-98765')
                .build())
        def foreign = manager.signIfConfigured(new ClusterTaskEvent('t1', 'run-1', 'COMPLETED', 'node-b'))
        manager.useMessageSigning(signing())

        expect:
        !manager.acceptTaskEvent(foreign)
    }

    def "without signing configured, events pass unsigned, as before"() {
        given:
        def manager = HazelcastManager.instance
        manager.useMessageSigning(null)
        def event = new ClusterTaskEvent('t1', 'run-1', 'COMPLETED', 'node-a')

        expect:
        manager.signIfConfigured(event).signature == null
        manager.acceptTaskEvent(event)
    }
}
