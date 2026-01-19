
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
    
    // PostgreSQL defaults
    postgres {
        host = 'localhost'
        port = 5432
        database = 'postgres'
        username = 'postgres'
        password = 'postgres'
        schema = 'public'
    }
    
    // MongoDB defaults
    mongodb {
        connectionString = 'mongodb://localhost:27017'
        database = 'test'
    }
    
    // ArangoDB defaults
    arangodb {
        host = 'localhost'
        port = 8529
        database = '_system'
        username = 'root'
        password = ''
    }
    
    // H2 defaults (in-memory for testing)
    h2 {
        databaseName = 'test'
        inMemory = true
        username = 'sa'
        password = ''
    }
    
    // AWS DynamoDB defaults
    dynamodb {
        region = 'us-east-1'
        accessKeyId = ''
        secretAccessKey = ''
        endpointUrl = ''  // For local testing
        connectionTimeout = 10000
        requestTimeout = 30000
    }
    
    // Azure CosmosDB defaults
    cosmosdb {
        endpoint = ''
        key = ''
        database = ''
        consistencyLevel = 'SESSION'
        connectionMode = 'DIRECT'
        maxRetryAttempts = 3
        requestTimeout = 60
    }

    firestore {
        projectId = '' //# GCP Project ID (required)
        key = ''
        databaseId = "(default)"  //# Firestore database ID
        credentialsPath = '' // Path to service account JSON (optional - uses default credentials if not provided)
        emulatorHost = '' // e.g., localhost:8080 for local Firestore emulator
        connectionTimeout  = 30000
        requestTimeout = 60000
    }

    // Google Cloud SQL defaults
    googlecloudsql {
        instanceConnectionName = ''  // Format: project:region:instance
        databaseType = 'postgresql'  // Options: postgresql, mysql, sqlserver
        database = 'postgres'
        username = 'postgres'
        password = ''
        schema = 'public'  // PostgreSQL/SQL Server only
        enableIamAuth = false  // Use IAM database authentication
        credentialsPath = ''  // Path to service account JSON
        poolSize = 1  // 1 = no pooling
        maxPoolSize = 10
        connectionTimeout = 30  // seconds
        idleTimeout = 600  // seconds (10 minutes)
        logQueries = false
        // Optional: Direct TCP/IP (not recommended for production)
        host = ''
        port = null
    }
}

// Messaging Configuration
messaging {
    // RabbitMQ (AMQP)
    rabbitmq {
        host = 'localhost'
        port = 5672
        username = 'guest'
        password = 'guest'
        virtualHost = '/'
        exchange = ''
        exchangeType = 'direct'  // direct, fanout, topic, headers
        durable = true
        autoAck = false
    }
    
    // Apache Kafka
    kafka {
        bootstrapServers = 'localhost:9092'
        clientId = 'kafka-producer'
        compression = 'none'  // none, gzip, snappy, lz4, zstd
        idempotence = true
        acks = -1  // all replicas must acknowledge
        retries = Integer.MAX_VALUE
        batchSize = 16384
        lingerMs = 0
        bufferMemory = 33554432
    }
    
    // AWS SQS
    'aws-sqs' {
        region = 'us-east-1'
        accessKeyId = ''  // Optional - uses default credential chain if not provided
        secretAccessKey = ''
        queueUrl = ''
        fifo = false
        delaySeconds = 0  // 0-900 seconds
        messageRetentionPeriod = 345600  // 4 days (in seconds)
        visibilityTimeout = 30
    }
    
    // AWS SNS
    'aws-sns' {
        region = 'us-east-1'
        accessKeyId = ''  // Optional - uses default credential chain if not provided
        secretAccessKey = ''
        topicArn = ''
    }
    
    // Azure Service Bus
    'azure-servicebus' {
        connectionString = ''
        queueName = ''
        topicName = ''
        maxConcurrentCalls = 1
        prefetchCount = 0
        autoComplete = true
    }
    
    // Apache ActiveMQ Artemis
    activemq {
        brokerUrl = 'tcp://localhost:61616'
        username = 'admin'
        password = 'admin'
        queueName = ''
        topicName = ''
        persistent = true
        priority = 4  // 0-9
        sessionTransacted = false
        acknowledgeMode = 'AUTO_ACKNOWLEDGE'
    }
    
    // Vert.x Event Bus (uses existing Vert.x dependency)
    'vertx-eventbus' {
        publishMode = false  // false = send (point-to-point), true = publish (broadcast)
        clustered = false
        localOnly = true
    }
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