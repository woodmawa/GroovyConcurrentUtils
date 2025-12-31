# ConfigLoader — Production-Grade Multi-Source Configuration System

**Version:** 2.0  
**Package:** `org.softwood.config`  
**Purpose:** Unified, extensible, traceable configuration loading for Groovy applications

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Core Concepts](#3-core-concepts)
4. [Configuration Sources & Precedence](#4-configuration-sources--precedence)
5. [Profile System](#5-profile-system)
6. [Extension Points](#6-extension-points)
7. [Schema Validation](#7-schema-validation)
8. [Custom Validators](#8-custom-validators)
9. [Trace System](#9-trace-system)
10. [Usage Examples](#10-usage-examples)
11. [API Reference](#11-api-reference)
12. [Best Practices](#12-best-practices)
13. [Migration Guide](#13-migration-guide)

---

## 1. Overview

`ConfigLoader` is a **production-grade configuration system** providing:

- ✅ **Multiple Sources** - Classpath, external files, system properties, environment variables
- ✅ **Profile Support** - Environment-specific configuration (dev, staging, prod)
- ✅ **Precedence Rules** - Deterministic layered override system
- ✅ **Schema Validation** - Type checking and coercion
- ✅ **Custom Validators** - Business logic validation
- ✅ **Trace System** - Full audit trail of config sources
- ✅ **Extension Points** - Library users can customize behavior
- ✅ **Multiple Formats** - JSON, YAML, Properties, Groovy
- ✅ **Immutable Results** - Thread-safe configuration
- ✅ **Cache Optimization** - Avoid reparsing unchanged configurations
- ✅ **Revoke Support** - Clear cache and force reload when needed

### Key Features

```groovy
// Simple usage
def config = ConfigLoader.loadConfig()
println config['database.url']

// Advanced usage with validation
def result = ConfigLoader.load(
    spec: customSpec,
    profile: 'prod',
    externalFiles: ['/etc/myapp/config.yml'],
    overrides: [
        'database.pool.size': 50
    ]
)

if (!result.isValid()) {
    result.errors.each { println "ERROR: ${it}" }
    System.exit(1)
}

println "Loaded profile: ${result.profile}"
```

### Cache Optimization

ConfigLoader **automatically caches** parsed configurations to avoid expensive re-parsing:

```groovy
// First call: Parses YAML/JSON files
def result1 = ConfigLoader.load()

// Second call: Returns cached result (if config files unchanged)
def result2 = ConfigLoader.load()
// → Instant return! No file I/O, no parsing

// Force reload if config changed externally
ConfigLoader.revoke()  // Clear cache
def result3 = ConfigLoader.load()  // Re-parse from files
```

**Performance Benefits:**
- **First call:** ~50-200ms (parse YAML/JSON files)
- **Cached calls:** <1ms (return cached `ConfigResult`)
- **Memory:** ~1-5 KB per cached configuration

**Cache Invalidation:**
- Automatic: Never expires (intentional - stable configs)
- Manual: Call `ConfigLoader.revoke()` to force reload
- File changes: Call `revoke()` before `load()` if external files updated

---

## 2. Architecture

### Component Diagram

```
┌──────────────────────────────────────────────────────────────────┐
│                         ConfigLoader                              │
│                    (Orchestration Layer)                          │
└───────────────┬──────────────────────────────────────────────────┘
                │
                ├──> ConfigSpec (Configuration of the loader itself)
                │
                ├──> ProfileResolver (Determines active profile)
                │
                ├──> ClasspathConfigSource (Library + user configs)
                │
                ├──> ExternalConfigSource (External config files)
                │
                ├──> SystemConfigSource (System properties)
                │
                ├──> EnvConfigSource (Environment variables)
                │
                ├──> MergeSupport (Deep merge logic)
                │
                ├──> Trace (Audit trail tracker)
                │
                ├──> ConfigSchema (Type validation & coercion)
                │
                ├──> ConfigValidator[] (Custom validators)
                │
                └──> ConfigResult (Immutable result with validation)
```

### Data Flow

```
1. Profile Resolution
   ↓
2. Load Classpath Configs (library defaults → user configs)
   ↓
3. Load External Files (from filesystem)
   ↓
4. Load System Properties (app.* → config keys)
   ↓
5. Load Environment Variables (DB_URL → database.url)
   ↓
6. Apply Explicit Overrides (runtime overrides)
   ↓
7. Schema Validation (type checking & coercion)
   ↓
8. Custom Validation (business rules)
   ↓
9. Return ConfigResult (immutable + trace + errors/warnings)
```

---

## 3. Core Concepts

### 3.1 ConfigSpec

**Purpose:** Configures the ConfigLoader itself (meta-configuration)

```groovy
class ConfigSpec {
    // Profile
    String defaultProfile = 'dev'
    String profileKey = 'profile'
    List<String> profileEnvKeys = ['APP_PROFILE', 'PROFILE']
    List<String> profileSysKeys = ['app.profile', 'profile']
    
    // System properties
    String sysPropPrefix = 'app.'
    
    // Classpath configs
    String libraryDefaultsPath = '/org/softwood/config'
    List<String> libraryBaseNames = ['defaults']
    List<String> userBaseNames = ['config']
    
    // Extension points
    List<EnvMapping> envMappings = []
    List<ConfigValidator> validators = []
    ConfigSchema schema = null
}
```

**Default Spec** (backward compatible):

```groovy
ConfigSpec.defaultSpec()
```

### 3.2 ConfigResult

**Purpose:** Immutable result container

```groovy
class ConfigResult {
    Map<String, Object> config      // Flattened, unmodifiable
    TraceSnapshot trace              // Audit trail
    List<String> errors              // Validation errors
    List<String> warnings            // Validation warnings
    String profile                   // Active profile
    
    boolean isValid()                // true if errors.isEmpty()
}
```

### 3.3 Trace System

**Purpose:** Full audit trail of where each config value came from

```groovy
result.trace.lastSourceByKey        // Map<String, String>
result.trace.events                 // List<TraceEvent>
result.trace.notes                  // List<String>

// Example
result.trace.lastSourceByKey['database.url']
// → "classpath:/config-prod.yml"
```

---

## 4. Configuration Sources & Precedence

### 4.1 Precedence Order (Lowest to Highest)

| Priority | Source | Example | Notes |
|---------|--------|---------|-------|
| 1 (Lowest) | Library Defaults | `/org/softwood/config/defaults.yml` | Bundled in JAR |
| 2 | User Classpath Base | `/config.yml` | User's config files |
| 3 | User Classpath Profile | `/config-prod.yml` | Profile-specific |
| 4 | External Files (Base) | `/etc/myapp/config.yml` | Filesystem configs |
| 5 | External Files (Profile) | `/etc/myapp/config-prod.yml` | Profile-specific external |
| 6 | System Properties | `-Dapp.database.url=...` | Command-line overrides |
| 7 | Environment Variables | `DB_URL=...` | Container/OS environment |
| 8 (Highest) | Explicit Overrides | `overrides: [...]` | Runtime programmatic |

**Rule:** Higher priority sources OVERRIDE lower priority sources.

### 4.2 Source Details

#### 4.2.1 Library Defaults

**Purpose:** Ship sensible defaults with your library

**Location:** `/org/softwood/config/defaults.*` (in library JAR)

**Formats:** `defaults.yml`, `defaults.json`, `defaults.properties`, `defaults.groovy`

**Example:**
```yaml
# /org/softwood/config/defaults.yml
distributed: false
hazelcast:
  port: 5701
  cluster:
    name: dev-cluster
```

**Use Case:** Library authors ship baseline configuration

#### 4.2.2 User Classpath Configs

**Purpose:** Application-specific configuration

**Location:** `/config.*` (in application's classpath/resources)

**Formats:** `config.yml`, `config-{profile}.yml`, etc.

**Example:**
```yaml
# /config.yml (base)
database:
  url: jdbc:h2:mem:test
  username: sa

# /config-prod.yml (production override)
database:
  url: jdbc:postgresql://prod-db:5432/myapp
  username: prod_user
```

**Use Case:** Application developers override library defaults

#### 4.2.3 External Files

**Purpose:** Environment-specific config outside JAR

**Location:** Filesystem paths

**Specification:**
```groovy
ConfigLoader.load(
    externalFiles: [
        '/etc/myapp/config.yml',
        '/etc/myapp/secrets.yml'
    ]
)
```

**Or via system property:**
```bash
java -Dapp.config=/etc/myapp/config.yml ...
```

**Use Case:**
- Kubernetes ConfigMaps
- Docker volume mounts
- Server-specific configs
- Secrets from vault

#### 4.2.4 System Properties

**Purpose:** Command-line overrides

**Prefix:** `app.` (configurable via `ConfigSpec.sysPropPrefix`)

**Mapping:**
```bash
-Dapp.database.url=jdbc:...     → config['database.url']
-Dapp.database.pool.size=50     → config['database.pool.size']
```

**Use Case:**
- Container startup
- Testing
- CI/CD overrides

#### 4.2.5 Environment Variables

**Purpose:** Container/OS environment integration

**Mapping:** Configured via `EnvMapping` in `ConfigSpec`

**Default Mappings:**
```groovy
USE_DISTRIBUTED         → distributed
HAZELCAST_CLUSTER_NAME  → hazelcast.cluster.name
HAZELCAST_PORT          → hazelcast.port
DB_URL                  → database.url
DB_USERNAME             → database.username
DB_PASSWORD             → database.password
```

**Use Case:**
- Docker/Kubernetes environments
- Secrets management
- Cloud platform integration

#### 4.2.6 Explicit Overrides

**Purpose:** Runtime programmatic overrides

**Usage:**
```groovy
ConfigLoader.load(
    overrides: [
        'database.pool.size': 100,
        'feature.newUI': true
    ]
)
```

**Use Case:**
- Testing
- Feature flags
- Dynamic configuration

---

## 5. Profile System

### 5.1 Profile Resolution

**Order:**

| Priority | Source | Example |
|---------|--------|---------|
| 1 (Highest) | Environment Variable | `APP_PROFILE=prod` or `PROFILE=prod` |
| 2 | System Property | `-Dapp.profile=staging` or `-Dprofile=staging` |
| 3 (Lowest) | Default | `'dev'` |

**Customization:**
```groovy
ConfigSpec spec = ConfigSpec.defaultSpec()
spec.defaultProfile = 'local'
spec.profileEnvKeys = ['MY_APP_ENV', 'APP_PROFILE']
spec.profileSysKeys = ['myapp.env', 'app.profile']
```

### 5.2 Profile-Specific Configs

**Pattern:** `{basename}-{profile}.{ext}`

**Examples:**
```
config.yml              (base, all profiles)
config-dev.yml          (dev profile only)
config-staging.yml      (staging profile only)
config-prod.yml         (prod profile only)
```

**Loading:**
1. Load `config.yml` (base)
2. Load `config-{active-profile}.yml` (if exists)
3. Merge (profile overrides base)

### 5.3 Profile in Result

```groovy
def result = ConfigLoader.load()
println result.profile    // → "prod"
println result.config.profile    // → "prod" (also in config map)
```

---

## 6. Extension Points

### 6.1 Custom Environment Mappings

**Purpose:** Map additional environment variables

```groovy
ConfigSpec spec = ConfigSpec.defaultSpec()

// Add custom mapping
spec.envMappings << new EnvMapping('REDIS_HOST', 'redis.host')
spec.envMappings << new EnvMapping('REDIS_PORT', 'redis.port')

// Custom coercion
spec.envMappings << new EnvMapping('MAX_WORKERS', 'workers.max') {
    @Override
    Object coerce(String rawValue) {
        return Integer.parseInt(rawValue)
    }
}

def result = ConfigLoader.load(spec: spec)
```

**Environment:**
```bash
export REDIS_HOST=redis.example.com
export REDIS_PORT=6379
export MAX_WORKERS=10
```

**Result:**
```groovy
config['redis.host'] == 'redis.example.com'
config['redis.port'] == '6379'
config['workers.max'] == 10  // Integer (coerced)
```

### 6.2 Custom Validators

**Purpose:** Business logic validation

**Interface:**
```groovy
interface ConfigValidator {
    void validate(
        Map<String, Object> config,
        List<String> errors,
        List<String> warnings,
        String profile,
        TraceSnapshot trace
    )
}
```

**Example:**
```groovy
class DatabaseValidator implements ConfigValidator {
    @Override
    void validate(
        Map<String, Object> config,
        List<String> errors,
        List<String> warnings,
        String profile,
        TraceSnapshot trace
    ) {
        // Production must have SSL
        if (profile == 'prod') {
            String url = config['database.url']
            if (url && !url.contains('ssl=true')) {
                errors.add('Production database must use SSL')
            }
        }
        
        // Warn if using default password
        if (config['database.password'] == 'changeme') {
            warnings.add('Using default database password')
        }
    }
}

// Register validator
ConfigSpec spec = ConfigSpec.defaultSpec()
spec.validators << new DatabaseValidator()

def result = ConfigLoader.load(spec: spec)
```

### 6.3 Custom Config Names

**Purpose:** Use different config file names

```groovy
ConfigSpec spec = ConfigSpec.defaultSpec()

// Library ships: /com/mylib/defaults.yml
spec.libraryDefaultsPath = '/com/mylib'
spec.libraryBaseNames = ['defaults']

// Users create: /myapp.yml
spec.userBaseNames = ['myapp', 'application']

def result = ConfigLoader.load(spec: spec)
```

**Loading order:**
1. `/com/mylib/defaults.yml`
2. `/com/mylib/defaults-{profile}.yml`
3. `/myapp.yml`
4. `/myapp-{profile}.yml`
5. `/application.yml`
6. `/application-{profile}.yml`

---

## 7. Schema Validation

### 7.1 Purpose

- Validate required keys exist
- Coerce types (String → Integer, etc.)
- Provide clear error messages

### 7.2 Usage

```groovy
ConfigSchema schema = new ConfigSchema()
    .require('database.url', String)
    .require('database.pool.size', Integer)
    .optional('database.pool.timeout', Integer)
    .optional('feature.newUI', Boolean)

ConfigSpec spec = ConfigSpec.defaultSpec()
spec.schema = schema

def result = ConfigLoader.load(spec: spec)

if (!result.isValid()) {
    result.errors.each { println "ERROR: ${it}" }
    System.exit(1)
}
```

### 7.3 Type Coercion

**Supported Types:**
- `Boolean` - Parses "true", "false", "1", "0", "yes", "no"
- `Integer` - Parses numeric strings
- `Long` - Parses numeric strings
- `String` - Converts anything to string
- `Enum` - Parses enum name

**Example:**
```yaml
# config.yml
database:
  pool:
    size: "50"          # String in YAML
    enabled: "true"     # String in YAML
```

```groovy
schema
    .require('database.pool.size', Integer)
    .require('database.pool.enabled', Boolean)

// Result after coercion
config['database.pool.size'] == 50       // Integer
config['database.pool.enabled'] == true  // Boolean
```

---

## 8. Custom Validators

### 8.1 Cross-Field Validation

```groovy
class PoolValidator implements ConfigValidator {
    @Override
    void validate(
        Map<String, Object> config,
        List<String> errors,
        List<String> warnings,
        String profile,
        TraceSnapshot trace
    ) {
        def min = config['database.pool.min'] as Integer
        def max = config['database.pool.max'] as Integer
        
        if (min && max && min > max) {
            errors.add('Pool min size cannot exceed max size')
        }
    }
}
```

### 8.2 Profile-Specific Validation

```groovy
class ProductionValidator implements ConfigValidator {
    @Override
    void validate(
        Map<String, Object> config,
        List<String> errors,
        List<String> warnings,
        String profile,
        TraceSnapshot trace
    ) {
        if (profile != 'prod') return  // Skip non-prod
        
        // Production-specific requirements
        if (!config['monitoring.enabled']) {
            errors.add('Monitoring must be enabled in production')
        }
        
        if (config['logging.level'] == 'DEBUG') {
            warnings.add('DEBUG logging in production may impact performance')
        }
    }
}
```

### 8.3 Trace-Based Validation

```groovy
class SourceValidator implements ConfigValidator {
    @Override
    void validate(
        Map<String, Object> config,
        List<String> errors,
        List<String> warnings,
        String profile,
        TraceSnapshot trace
    ) {
        // Ensure secrets come from environment
        String passwordSource = trace.lastSourceByKey['database.password']
        
        if (passwordSource && !passwordSource.startsWith('environment')) {
            warnings.add('Database password should come from environment variables')
        }
    }
}
```

---

## 9. Trace System

### 9.1 Purpose

- **Audit trail** - Know exactly where each config value came from
- **Debugging** - Understand why a value is what it is
- **Security** - Ensure secrets come from proper sources
- **Compliance** - Track configuration provenance

### 9.2 TraceSnapshot

```groovy
class TraceSnapshot {
    Map<String, String> lastSourceByKey    // Final source for each key
    List<TraceEvent> events                // All override events
    List<String> notes                     // Warnings/notes
}
```

### 9.3 TraceEvent

```groovy
class TraceEvent {
    enum Kind { SET, OVERRIDE }
    
    String key
    Object value
    String source
    Kind kind
    long timestampMillis
}
```

### 9.4 Usage Examples

**Check where a value came from:**
```groovy
def result = ConfigLoader.load()
def source = result.trace.lastSourceByKey['database.url']
println "database.url came from: ${source}"
// → "environment" or "classpath:/config-prod.yml"
```

**List all overrides:**
```groovy
result.trace.events
    .findAll { it.kind == TraceEvent.Kind.OVERRIDE }
    .each { event ->
        println "${event.key} overridden by ${event.source}"
    }
```

**Security audit:**
```groovy
def secrets = ['database.password', 'api.key', 'jwt.secret']
secrets.each { key ->
    def source = result.trace.lastSourceByKey[key]
    if (source && !source.startsWith('environment')) {
        println "WARNING: Secret '${key}' not from environment: ${source}"
    }
}
```

---

## 10. Usage Examples

### 10.1 Simple Usage (Backward Compatible)

```groovy
import org.softwood.config.ConfigLoader

// Load with defaults
def config = ConfigLoader.loadConfig()

// Access values
println config['database.url']
println config['hazelcast.cluster.name']

// Typed accessors
String dbUrl = ConfigLoader.getString(config, 'database.url', 'jdbc:h2:mem:test')
int port = ConfigLoader.getInt(config, 'hazelcast.port', 5701)
boolean distributed = ConfigLoader.getBoolean(config, 'distributed', false)
```

### 10.2 Production Application

```groovy
class MyApp {
    static void main(String[] args) {
        // Load config with validation
        ConfigSpec spec = ConfigSpec.defaultSpec()
        
        // Add schema
        spec.schema = new ConfigSchema()
            .require('database.url', String)
            .require('database.username', String)
            .require('database.password', String)
            .require('hazelcast.cluster.name', String)
            .optional('distributed', Boolean)
        
        // Add validators
        spec.validators << new ProductionValidator()
        spec.validators << new DatabaseValidator()
        
        // Load
        def result = ConfigLoader.load(
            spec: spec,
            externalFiles: ['/etc/myapp/config.yml']
        )
        
        // Check validity
        if (!result.isValid()) {
            System.err.println("Configuration errors:")
            result.errors.each { System.err.println("  - ${it}") }
            System.exit(1)
        }
        
        // Log warnings
        result.warnings.each { println "WARNING: ${it}" }
        
        // Use config
        def config = result.config
        Database.connect(
            url: config['database.url'],
            user: config['database.username'],
            pass: config['database.password']
        )
        
        if (config.distributed) {
            Hazelcast.start(config)
        }
    }
}
```

### 10.3 Testing with Overrides

```groovy
class ConfigTest extends Specification {
    
    def "test database pool configuration"() {
        when:
        def result = ConfigLoader.load(
            overrides: [
                'database.pool.min': 5,
                'database.pool.max': 20,
                'database.pool.timeout': 30
            ]
        )
        
        then:
        result.config['database.pool.min'] == 5
        result.config['database.pool.max'] == 20
        result.config['database.pool.timeout'] == 30
    }
    
    def "test production validation"() {
        when:
        ConfigSpec spec = ConfigSpec.defaultSpec()
        spec.validators << new ProductionValidator()
        
        def result = ConfigLoader.load(
            spec: spec,
            profile: 'prod',
            overrides: [
                'monitoring.enabled': false  // Invalid for prod
            ]
        )
        
        then:
        !result.isValid()
        result.errors.contains('Monitoring must be enabled in production')
    }
}
```

### 10.4 Library with Defaults

```groovy
// Library JAR contains: /org/mylib/defaults.yml
database:
  pool:
    min: 5
    max: 20
    timeout: 30

// Library code:
class MyLibrary {
    static ConfigResult loadConfig() {
        ConfigSpec spec = new ConfigSpec()
        spec.libraryDefaultsPath = '/org/mylib'
        spec.libraryBaseNames = ['defaults']
        spec.userBaseNames = ['mylib', 'config']
        
        return ConfigLoader.load(spec: spec)
    }
}

// User application creates: /mylib.yml
database:
  pool:
    max: 50  // Override library default

// Result:
config['database.pool.min'] == 5      // From library
config['database.pool.max'] == 50     // From user
config['database.pool.timeout'] == 30  // From library
```

### 10.5 Kubernetes ConfigMap

```yaml
# configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: myapp-config
data:
  config.yml: |
    database:
      url: jdbc:postgresql://postgres:5432/myapp
      pool:
        size: 50
```

```yaml
# deployment.yaml
spec:
  containers:
  - name: myapp
    image: myapp:latest
    env:
    - name: APP_PROFILE
      value: "prod"
    - name: DB_PASSWORD
      valueFrom:
        secretKeyRef:
          name: myapp-secrets
          key: db-password
    volumeMounts:
    - name: config
      mountPath: /etc/myapp
  volumes:
  - name: config
    configMap:
      name: myapp-config
```

```groovy
// Application startup
def result = ConfigLoader.load(
    externalFiles: ['/etc/myapp/config.yml']
)

// Result:
// - APP_PROFILE → profile = "prod"
// - DB_PASSWORD → config['database.password'] (from secret)
// - /etc/myapp/config.yml → overrides defaults
```

---

## 11. API Reference

### 11.1 ConfigLoader

```groovy
class ConfigLoader {
    
    // Primary API
    static ConfigResult load(Map options = [:])
    
    // Backward compatible API
    static Map<String, Object> loadConfig()
    
    // Cache management
    static void revoke()                  // Clear cached config, force reload
    
    // Typed accessors (backward compatible)
    static String getString(Map config, String key)
    static String getString(Map config, String key, String defaultValue)
    static Boolean getBoolean(Map config, String key)
    static Boolean getBoolean(Map config, String key, Boolean defaultValue)
    static Integer getInt(Map config, String key)
    static Integer getInt(Map config, String key, Integer defaultValue)
}
```

**Options Map:**
- `spec: ConfigSpec` - Custom specification (default: `ConfigSpec.defaultSpec()`)
- `profile: String` - Override profile (default: resolved from env/sys/default)
- `externalFiles: List<String>` - External config file paths
- `overrides: Map` - Explicit overrides (highest precedence)

### 11.2 ConfigSpec

```groovy
class ConfigSpec {
    String defaultProfile
    String profileKey
    List<String> profileEnvKeys
    List<String> profileSysKeys
    String sysPropPrefix
    String libraryDefaultsPath
    List<String> libraryBaseNames
    List<String> userBaseNames
    List<EnvMapping> envMappings
    List<ConfigValidator> validators
    ConfigSchema schema
    
    static ConfigSpec defaultSpec()
}
```

### 11.3 ConfigResult

```groovy
class ConfigResult {
    Map<String, Object> config        // Immutable config map
    TraceSnapshot trace               // Audit trail
    List<String> errors               // Validation errors
    List<String> warnings             // Validation warnings
    String profile                    // Active profile
    
    boolean isValid()                 // true if no errors
}
```

### 11.4 ConfigSchema

```groovy
class ConfigSchema {
    ConfigSchema require(String key, Class type = null)
    ConfigSchema optional(String key, Class type = null)
    void validate(Map config, List errors, List warnings)
}
```

### 11.5 ConfigValidator

```groovy
interface ConfigValidator {
    void validate(
        Map<String, Object> config,
        List<String> errors,
        List<String> warnings,
        String profile,
        TraceSnapshot trace
    )
}
```

### 11.6 EnvMapping

```groovy
class EnvMapping {
    final String envKey      // Environment variable name
    final String configKey   // Config key (dot notation)
    
    EnvMapping(String envKey, String configKey)
    Object coerce(String rawValue)  // Override for custom coercion
}
```

---

## 12. Best Practices

### ✅ DO: Use Profiles for Environment-Specific Config

```groovy
// config.yml (dev defaults)
database:
  url: jdbc:h2:mem:test

// config-prod.yml (production overrides)
database:
  url: jdbc:postgresql://prod-db/myapp
```

### ✅ DO: Leverage Configuration Cache

```groovy
// Application startup
class MyApp {
    static void main(String[] args) {
        // Load once at startup
        def result = ConfigLoader.load()
        
        // Cache handles subsequent calls
        def db = Database.connect(result.config)
        def cache = Cache.initialize(result.config)
        def server = Server.start(result.config)
        // All three get same cached config → fast!
    }
}
```

### ✅ DO: Use revoke() for Dynamic Reloads

```groovy
// Watch for config file changes
FileWatcher.watch('/etc/myapp/config.yml') { event ->
    if (event.kind == ENTRY_MODIFY) {
        println "Config file changed, reloading..."
        ConfigLoader.revoke()  // Clear cache
        
        def newConfig = ConfigLoader.load()
        Application.reconfigure(newConfig.config)
    }
}
```

### ✅ DO: Use revoke() Between Tests

```groovy
class ConfigTest extends Specification {
    
    def cleanup() {
        // Clear cache between tests
        ConfigLoader.revoke()
    }
    
    def "test production config"() {
        when:
        def result = ConfigLoader.load(
            profile: 'prod',
            overrides: ['monitoring.enabled': true]
        )
        
        then:
        result.config['monitoring.enabled'] == true
    }
}
```

### ✅ DO: Use Environment Variables for Secrets

```bash
# Never put passwords in config files!
export DB_PASSWORD=secret123
```

```groovy
spec.envMappings << new EnvMapping('DB_PASSWORD', 'database.password')
```

### ✅ DO: Use Schema Validation

```groovy
spec.schema = new ConfigSchema()
    .require('database.url', String)
    .require('database.pool.size', Integer)
```

### ✅ DO: Use Custom Validators for Business Rules

```groovy
spec.validators << new ProductionValidator()
spec.validators << new SecurityValidator()
```

### ✅ DO: Check Validation Errors

```groovy
def result = ConfigLoader.load(spec: spec)
if (!result.isValid()) {
    result.errors.each { System.err.println it }
    System.exit(1)
}
```

### ✅ DO: Use Trace for Security Audits

```groovy
def secrets = ['database.password', 'api.key']
secrets.each { key ->
    def source = result.trace.lastSourceByKey[key]
    assert source.startsWith('environment'), 
        "Secret '${key}' must come from environment"
}
```

### ❌ DON'T: Hardcode Secrets in Config Files

```yaml
# BAD!
database:
  password: secret123
```

### ❌ DON'T: Ignore Validation Errors

```groovy
// BAD!
def result = ConfigLoader.load()
// ... proceed without checking result.isValid()
```

### ❌ DON'T: Mutate Config Map

```groovy
// BAD! (throws UnsupportedOperationException)
config['new.key'] = 'value'
```

---

## 13. Migration Guide

### From Old ConfigLoader

**Old API:**
```groovy
def config = ConfigLoader.loadConfig()
```

**Still Works!** (Backward compatible)

**New API (Recommended):**
```groovy
def result = ConfigLoader.load()
def config = result.config

if (!result.isValid()) {
    // Handle errors
}
```

### Key Differences

| Feature | Old | New |
|---------|-----|-----|
| **Return Type** | `Map` | `ConfigResult` |
| **Validation** | None | Schema + Custom validators |
| **Trace** | None | Full audit trail |
| **Errors** | Silent failures | Explicit error list |
| **Immutability** | Mutable map | Immutable map |
| **Extension** | Limited | Fully extensible |

### Migration Steps

1. **Replace calls:**
   ```groovy
   // Old
   def config = ConfigLoader.loadConfig()
   
   // New
   def result = ConfigLoader.load()
   def config = result.config
   ```

2. **Add validation:**
   ```groovy
   if (!result.isValid()) {
       result.errors.each { println "ERROR: ${it}" }
       System.exit(1)
   }
   ```

3. **Add schema (optional but recommended):**
   ```groovy
   ConfigSpec spec = ConfigSpec.defaultSpec()
   spec.schema = new ConfigSchema()
       .require('database.url', String)
   
   def result = ConfigLoader.load(spec: spec)
   ```

4. **Add custom validators (optional):**
   ```groovy
   spec.validators << new YourCustomValidator()
   ```

---

## Summary

`ConfigLoader` 2.0 is a **production-grade configuration system** providing:

- ✅ **Multiple sources** with deterministic precedence
- ✅ **Profile support** for environment-specific config
- ✅ **Schema validation** with type coercion
- ✅ **Custom validators** for business rules
- ✅ **Full audit trail** via trace system
- ✅ **Extension points** for library users
- ✅ **Backward compatible** with 1.0 API
- ✅ **Immutable results** for thread safety
- ✅ **Multiple formats** (JSON, YAML, Properties, Groovy)

**One call, complete configuration:**

```groovy
def result = ConfigLoader.load()
```

---

*End of README*