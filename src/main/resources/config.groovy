
// Base config
distributed = false

hazelcast {
    cluster {
        name = 'local-cluster'
    }
    port = 5701
}

database {
    url = 'jdbc:h2:mem:testdb'
    username = 'sa'
    password = ''
}

//one of "DATAFLOW" | "VERTX" | "COMPLETABLE_FUTURE"
promises.defaultImplementation = "DATAFLOW"

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