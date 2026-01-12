
// Base config
//distributed = false

// Remote Actor Transport Configuration
import org.softwood.security.SecretsResolver

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
                // SECURITY: No default passwords - must be configured via environment
                keyStorePassword = {
                    def env = System.getenv('ENVIRONMENT') ?: 'development'
                    if (env.toLowerCase() in ['production', 'prod', 'staging']) {
                        return SecretsResolver.resolveRequired('TLS_KEYSTORE_PASSWORD')
                    } else {
                        def password = SecretsResolver.resolve('TLS_KEYSTORE_PASSWORD', 'changeit')
                        if (password == 'changeit') {
                            println "⚠️  WARNING: Using default TLS keystore password in ${env} environment - NOT FOR PRODUCTION"
                        }
                        return password
                    }
                }()
                trustStore = '/etc/actors/truststore.jks'
                trustStorePassword = {
                    def env = System.getenv('ENVIRONMENT') ?: 'development'
                    if (env.toLowerCase() in ['production', 'prod', 'staging']) {
                        return SecretsResolver.resolveRequired('TLS_TRUSTSTORE_PASSWORD')
                    } else {
                        def password = SecretsResolver.resolve('TLS_TRUSTSTORE_PASSWORD', 'changeit')
                        if (password == 'changeit') {
                            println "⚠️  WARNING: Using default TLS truststore password in ${env} environment - NOT FOR PRODUCTION"
                        }
                        return password
                    }
                }()
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
                // SECURITY: No default passwords - must be configured via environment
                keyStorePassword = {
                    def env = System.getenv('ENVIRONMENT') ?: 'development'
                    if (env.toLowerCase() in ['production', 'prod', 'staging']) {
                        return SecretsResolver.resolveRequired('TLS_KEYSTORE_PASSWORD')
                    } else {
                        def password = SecretsResolver.resolve('TLS_KEYSTORE_PASSWORD', 'changeit')
                        if (password == 'changeit') {
                            println "⚠️  WARNING: Using default HTTPS keystore password in ${env} environment - NOT FOR PRODUCTION"
                        }
                        return password
                    }
                }()
            }
        }
    }
}

hazelcast {
    enabled = false  // Master switch for clustering
    port = 5701
    cluster {
        name = 'taskgraph-cluster'
        members = []  // Empty = multicast discovery, or specify ['host1:5701', 'host2:5701']
    }
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

// ==============================================================================
// EXECUTION TIMEOUT CONFIGURATION
// ==============================================================================
timeout {
    defaults {
        taskTimeout = java.time.Duration.ofMinutes(5)     // Default task execution timeout
        graphTimeout = java.time.Duration.ofHours(1)      // Default graph execution timeout
        warningThreshold = java.time.Duration.ofMinutes(4)  // 80% of task timeout (4 min of 5 min)
        enabled = false  // Opt-in by default
    }
    
    // Common timeout presets
    presets {
        quick {
            taskTimeout = java.time.Duration.ofSeconds(5)
            warningThreshold = java.time.Duration.ofSeconds(4)
        }
        
        standard {
            taskTimeout = java.time.Duration.ofSeconds(30)
            warningThreshold = java.time.Duration.ofSeconds(25)
        }
        
        long {
            taskTimeout = java.time.Duration.ofMinutes(5)
            warningThreshold = java.time.Duration.ofMinutes(4)
        }
        
        'very-long' {
            taskTimeout = java.time.Duration.ofMinutes(30)
            warningThreshold = java.time.Duration.ofMinutes(25)
        }
    }
}

// Profile-specific overrides
environments {
    dev {
        distributed = false
        hazelcast {
            enabled = false  // Clustering disabled in dev
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
            enabled = false  // Clustering disabled in test
            cluster {
                name = 'test-cluster'
            }
            port = 5702
        }
        database {
            url = 'jdbc:h2:mem:testdb'
            username = 'test'
            // SECURITY: Generate random password for test database
            password = {
                def random = new java.security.SecureRandom()
                def bytes = new byte[16]
                random.nextBytes(bytes)
                return "test_" + bytes.encodeBase64().toString()
            }()
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
            enabled = true  // Clustering enabled in production
            cluster {
                name = 'production-cluster'
                members = []  // Configure with actual production node addresses
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