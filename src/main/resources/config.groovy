
// Base config
//distributed = false

// Remote Actor Transport Configuration
import org.softwood.actor.remote.security.SecretsResolver

actor {
    remote {
        // Transport: 'rsocket' (default) or 'http'
        transport = 'rsocket'
        
        // Serialization: 'messagepack' (default, recommended), 'json' (debugging), 'microstream' (experimental)
        serialization = 'messagepack'
        
        // RSocket configuration
        rsocket {
            enabled = true
            port = 7000
            host = '0.0.0.0'
            
            // TLS/SSL configuration (optional)
            tls {
                enabled = false
                keyStore = '/etc/actors/server-keystore.jks'
                keyStorePassword = SecretsResolver.resolve('TLS_KEYSTORE_PASSWORD', 'changeit')
                trustStore = '/etc/actors/truststore.jks'
                trustStorePassword = SecretsResolver.resolve('TLS_TRUSTSTORE_PASSWORD', 'changeit')
            }
        }
        
        // HTTP configuration (fallback)
        http {
            enabled = false
            port = 8080
            host = '0.0.0.0'
            
            // HTTPS/TLS configuration (optional)
            tls {
                enabled = false
                keyStore = '/etc/actors/server-keystore.jks'
                keyStorePassword = SecretsResolver.resolve('TLS_KEYSTORE_PASSWORD', 'changeit')
            }
        }
    }
}

hazelcast {

    port = 5701
}

database {
    url = 'jdbc:h2:mem:testdb'
    username = 'sa'
    password = ''
}

//one of "DATAFLOW" | "VERTX" | "COMPLETABLE_FUTURE"
promises.defaultImplementation = "DATAFLOW"

// TaskGraph Persistence Configuration
taskgraph {
    persistence {
        enabled = false
        baseDir = 'graphState'
        snapshotOn = 'failure'  // Options: 'always', 'failure', 'never'
        compression = false
        retentionPolicy {
            maxSnapshots = 2
            maxRetries = 2
        }
    }
}

// Profile-specific overrides
environments {
    dev {
        distributed = false
        hazelcast {
            cluster {
                name = 'dev-cluster'
            }
        }
        logging {
            level = 'DEBUG'
        }
        taskgraph {
            persistence {
                enabled = false
                baseDir = 'src/test/resources'
                snapshotOn = 'always'
            }
        }
    }

    test {
        distributed = false
        hazelcast {
            cluster {
                name = 'test-cluster'
            }
            port = 5702
        }
        database {
            url = 'jdbc:h2:mem:testdb'
            username = 'test'
            password = 'test123'
        }
        taskgraph {
            persistence {
                enabled = false
                baseDir = 'build/test-snapshots'
                snapshotOn = 'always'
            }
        }
    }

    production {
        distributed = true
        hazelcast {
            cluster {
                name = 'production-cluster'
            }
            port = 5703
        }
        database {
            url = 'jdbc:postgresql://prod-db:5432/myapp'
            username = 'prod_user'
        }
        logging {
            level = 'WARN'
        }
    }
}