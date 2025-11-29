
# ConfigLoader – Unified Multi-Source Configuration System

This README documents the **complete architecture**, **resolution model**, and **usage patterns** for  
`org.softwood.config.ConfigLoader` — a fully generic, multi-format, profile‑aware configuration loader  
used across the Softwood GroovyConcurrentUtils ecosystem.  


---

# 1. Overview

`ConfigLoader` provides a *single, consistent* way to load configuration from:

1. **Base config files**
2. **Profile‑specific config files**
3. **System properties**
4. **Environment variables**

All sources are merged using a deterministic override order, producing a  
**single flat Map with dot‑notation keys**.

It supports:

- `config.json`, `config-{profile}.json`
- `config.groovy`, `config-{profile}.groovy`
- `config.yml` / `config.yaml`, and their profile variants
- `config.properties`, `config-{profile}.properties`
- System properties like: `-Dapp.db.url=...`
- Environment variables like: `DB_URL=...`
- Profile resolution through env/system/DEFAULT (`dev`)

The final unified config is a **flat Map<String, Object>**, convenient for all downstream modules.

---

# 2. Architecture

```
┌───────────────────────────────────────────────────────────────────────┐
│                            ConfigLoader                               │
│                    (Unified Multi-Source Loader)                      │
└──────────────────────────────────┬────────────────────────────────────┘
                                   │
                         determines active profile
                                   │
                         resolveProfile() method
                                   │
                   ┌───────────────┴────────────────┐
                   │                                │
       Env: APP_PROFILE / PROFILE            System: app.profile
                   │                                │
                   └───────────────┬────────────────┘
                                   │
                               Default: "dev"
                                   │
                     ┌─────────────┴────────────────────┐
                     │                                  │
       Load base configs                         Load profile configs
   (json, groovy, yaml, properties)      (json, groovy, yaml, properties)
                     │                                  │
                     └───────────────┬──────────────────┘
                                     │
                               Deep merge maps
                                     │
                         Add profile into final map
                                     │
        ┌────────────────────────────┴────────────────────────────┐
        │                                                        │
Override with system properties                          Override with env vars
  (-Dapp.xxx=value → key "xxx")                       (DB_URL → "database.url")
        │                                                        │
        └────────────────────────────┬────────────────────────────┘
                                     │
                       Final flat merged config Map<String,Object>
```

---

# 3. Profile Resolution

Profile detection order:

| Priority | Source |
|---------|--------|
| 1 | Environment: `APP_PROFILE` or `PROFILE` |
| 2 | System property: `-Dapp.profile` or `-Dprofile` |
| 3 | Default: `"dev"` |

Example:

```bash
export APP_PROFILE=prod
java -Dapp.profile=staging ...
# active profile = prod  (environment wins)
```

---

# 4. Config Loading Pipeline

## 4.1 Base configs (loaded first)
`config.json`  
`config.groovy`  
`config.yml` / `config.yaml`  
`config.properties`

## 4.2 Profile configs (override base)
`config-prod.json`, `config-prod.groovy`, etc.

## 4.3 System property overrides

System properties beginning with **`app.`** become config keys:

```
-Dapp.db.url=jdbc:h2:mem:test →  db.url = "jdbc:h2:mem:test"
```

## 4.4 Environment variable overrides

Common variables are automatically mapped:

| Environment Variable | Resulting Key |
|---------------------|----------------|
| `USE_DISTRIBUTED` | `distributed` |
| `HAZELCAST_CLUSTER_NAME` | `hazelcast.cluster.name` |
| `HAZELCAST_PORT` | `hazelcast.port` |
| `DB_URL` | `database.url` |
| `DB_USERNAME` | `database.username` |
| `DB_PASSWORD` | `database.password` |

---

# 5. Map Flattening

Nested config maps are flattened into **dot-notation**:

```groovy
[
    hazelcast: [
        cluster: [
            name: "prodCluster"
        ]
    ]
]
```

Becomes:

```
[
    "hazelcast.cluster.name": "prodCluster"
]
```

This ensures **consistent lookups** across all formats.

---

# 6. Deep Merge Semantics

`deepMerge(base, override)` works recursively:

- If both values are maps → deep merge
- Otherwise override replaces base

This achieves **predictable layer precedence**.

---

# 7. Accessor API

The loader provides common typed getters:

### `ConfigLoader.get(Map config, String key, Object defaultValue)`
Generic retrieval.

### `ConfigLoader.getBoolean(...)`
Handles:
- `"true"`, `"1"`, `"yes"`, `"on"` (case‑insensitive)
- boolean values directly

### `ConfigLoader.getInt(...)`
Converts numbers or parses Strings.

### `ConfigLoader.getString(...)`
Always returns a String or default.

---

# 8. Usage Examples

## 8.1 Basic Use

```groovy
import org.softwood.config.ConfigLoader

def config = ConfigLoader.loadConfig()

println config['database.url']
println config['hazelcast.cluster.name']
println config.profile    // active profile
```

---

## 8.2 Using Typed Accessors

```groovy
def cfg = ConfigLoader.loadConfig()

boolean distributed = ConfigLoader.getBoolean(cfg, "distributed", false)
int port = ConfigLoader.getInt(cfg, "hazelcast.port", 5701)
String dbUser = ConfigLoader.getString(cfg, "database.username", "root")
```

---

## 8.3 Using With Application Frameworks

```groovy
class App {
    static void main(args) {
        def config = ConfigLoader.loadConfig()

        Database.connect(
            url: config["database.url"],
            user: config["database.username"],
            pass: config["database.password"]
        )

        if (config.distributed) {
            HazelcastBootstrap.start(config)
        }
    }
}
```

---

# 9. Adding Support for New Environment Variables

Add mapping inside:

```groovy
private static Map loadEnvironmentVariables()
```

Example:

```groovy
if (System.getenv("REDIS_HOST")) {
    envConfig."redis.host" = System.getenv("REDIS_HOST")
}
```

---

# 10. File Format Examples

### `config.yml`

```yaml
database:
  url: jdbc:postgresql://localhost/db
  username: admin
  password: secret

hazelcast:
  port: 5701
```

### `config.json`

```json
{
  "database": { "url": "...", "username": "..." },
  "hazelcast": { "port": 5701 }
}
```

### `config.properties`

```
database.url=jdbc:h2:mem:test
hazelcast.port=5701
```

### `config.groovy`

```groovy
database {
    url = "jdbc:mysql://localhost/db"
    username = "root"
}
```

All of these resolve to the **same final flat map**.

---

# 11. Best Practices

### ✔ Use profile-specific files for structured overrides
`config-prod.yml`, `config-staging.groovy`, etc.

### ✔ Use `app.*` system properties for last‑mile overrides
Useful for container deployment.

### ✔ Use typed getters in downstream code
Avoid manual parsing.

### ✔ Keep environment variable names UPPER_SNAKE_CASE
These map cleanly to dot‑notation.

---

# 12. Testing Guidelines

### Test default profile:

```groovy
assert ConfigLoader.loadConfig().profile == "dev"
```

### Test profile override:

```bash
APP_PROFILE=prod
```

```groovy
assert ConfigLoader.loadConfig().profile == "prod"
```

### Test system property overrides:

```groovy
System.setProperty("app.database.url", "jdbc:test")
```

### Test flattening:

```groovy
assert cfg["hazelcast.cluster.name"] == "testName"
```

---

# 13. Summary

`ConfigLoader` is a **robust multi-source configuration resolver** providing:

- Flexible profile support
- Multiple file format loaders
- Deterministic layered merging
- Dot-notation flattening
- System & environment overrides
- Simple typed access helpers

It acts as the **central configuration backbone** for Softwood modules including:

- PromiseConfiguration (Promise system)
- Dataflow runtime
- Distributed runtimes
- Application bootstraps
- Service integration layers

A single call:

```groovy
def config = ConfigLoader.loadConfig()
```

provides **all configuration in one unified map**.

---

# End of README
