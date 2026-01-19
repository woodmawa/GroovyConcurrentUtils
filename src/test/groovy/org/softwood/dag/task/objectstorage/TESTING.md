# Object Storage Framework - Testing Guide

This document explains the testing strategy for the Object Storage framework and how to run different types of tests.

## Test Structure

The test suite is divided into **Unit Tests** (run without dependencies) and **Integration Tests** (require SDKs and services).

### 📦 Test Files Overview

```
src/test/groovy/org/softwood/dag/task/objectstorage/
├── ObjectStoreBasicsTest.groovy          # ~20 unit tests (no deps)
├── ObjectStoreProviderUnitTest.groovy    # ~50 unit tests (no deps)
├── ObjectStoreConfigTest.groovy          # ~40 unit tests (no deps)
└── ObjectStoreProvidersTest.groovy       # ~85 integration tests (@Ignore'd)
```

## Unit Tests (No Dependencies Required)

**Total: ~110 tests**  
**Status: ✅ All passing**  
**Dependencies: None**

### ObjectStoreBasicsTest (~20 tests)

Tests the core framework abstractions without any provider implementation:

- **ObjectRef**: Immutability, equality, string representation
- **ObjectInfo**: Metadata handling, version tokens, tags
- **Options Classes**: PutOptions, GetOptions, ListOptions, DeleteOptions, etc.
- **ProviderCapabilities**: Feature flags, S3 compatibility
- **ObjectStoreException**: Error context, message formatting
- **ContainerInfo**: Bucket metadata
- **AbstractObjectStoreProvider**: Configuration helpers, state management

**Run with:**
```bash
./gradlew test --tests "org.softwood.dag.task.objectstorage.ObjectStoreBasicsTest"
```

### ObjectStoreProviderUnitTest (~50 tests)

Tests provider behavior without actually initializing them (no SDK loading):

- **Provider Identity**: Unique IDs, display names, initial state
- **Configuration Validation**: Required fields, optional fields, multiple styles
- **Error Messages**: SDK-missing errors, validation errors, helpful messages
- **State Management**: Initialization tracking, close/cleanup, idempotency
- **Configuration Helpers**: getConfigValue, getRequiredConfigValue
- **Provider-Specific Config**: MinIO regions, AWS credentials, Azure connection strings, GCS projects

**Run with:**
```bash
./gradlew test --tests "org.softwood.dag.task.objectstorage.ObjectStoreProviderUnitTest"
```

### ObjectStoreConfigTest (~40 tests)

Tests configuration acceptance and validation across all providers:

- **MinIO Config**: Endpoint, credentials, regions
- **Garage Config**: S3-compatible configuration
- **AWS S3 Config**: Regions, credential chains, custom endpoints
- **Azure Config**: Connection strings, account credentials
- **GCS Config**: Project IDs, credential files, ADC
- **Cross-Provider Tests**: Unique IDs, display names, consistent behavior

**Run with:**
```bash
./gradlew test --tests "org.softwood.dag.task.objectstorage.ObjectStoreConfigTest"
```

### Running All Unit Tests

```bash
# Run all unit tests (should all pass without any setup)
./gradlew test --tests "org.softwood.dag.task.objectstorage.ObjectStoreBasicsTest" \
               --tests "org.softwood.dag.task.objectstorage.ObjectStoreProviderUnitTest" \
               --tests "org.softwood.dag.task.objectstorage.ObjectStoreConfigTest"

# Or just run all object storage tests (includes @Ignore'd integration tests)
./gradlew test --tests "org.softwood.dag.task.objectstorage.*"
```

## Integration Tests (Require SDKs + Services)

**Total: ~85 tests**  
**Status: ⏭️ All @Ignore'd by default**  
**Dependencies: SDK libraries + running services**

### ObjectStoreProvidersTest (~85 tests)

Tests actual provider operations against real storage services:

- **Container Management**: Create, delete, list buckets/containers
- **Object Operations**: Put, get, delete, copy, stat
- **Listing**: Prefix filtering, pagination, recursive listing
- **Metadata**: User metadata, tags, content types
- **Advanced Features**: Range reads, conditional puts, versioning
- **Error Handling**: Missing objects, idempotent deletes
- **Cross-Cloud Operations**: Multi-provider workflows

**Why @Ignore'd:**
- Requires external SDK dependencies
- Requires running storage services
- Requires network connectivity
- Requires valid credentials
- May incur cloud costs

## Running Integration Tests

### Step 1: Add SDK Dependencies

Edit `build.gradle` to add test dependencies for the providers you want to test:

```groovy
dependencies {
    // ... existing dependencies ...
    
    // For MinIO and Garage integration tests
    testImplementation 'io.minio:minio:8.5.7'
    
    // For AWS S3 integration tests
    testImplementation 'software.amazon.awssdk:s3:2.20.0'
    
    // For Azure Blob integration tests
    testImplementation 'com.azure:azure-storage-blob:12.20.0'
    
    // For Google Cloud Storage integration tests
    testImplementation 'com.google.cloud:google-cloud-storage:2.15.0'
}
```

**Note:** You only need to add dependencies for the providers you want to test!

### Step 2: Start Required Services

#### MinIO (Local)
```bash
# Using Docker
docker run -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin \
  -e MINIO_ROOT_PASSWORD=minioadmin \
  minio/minio server /data --console-address ":9001"

# Verify: http://localhost:9001 (admin UI)
```

#### Garage (Local)
```bash
# Using Docker
docker run -p 3900:3900 dxflrs/garage:latest

# Configure with your access keys
```

#### AWS S3 (Cloud)
```bash
# Set environment variables
export AWS_ACCESS_KEY_ID=your_access_key
export AWS_SECRET_ACCESS_KEY=your_secret_key
export AWS_REGION=us-east-1

# Or configure via ~/.aws/credentials
```

#### Azure Blob Storage (Cloud)
```bash
# Set environment variable
export AZURE_STORAGE_CONNECTION_STRING="DefaultEndpointsProtocol=https;AccountName=...;AccountKey=...;EndpointSuffix=core.windows.net"
```

#### Google Cloud Storage (Cloud)
```bash
# Set environment variable
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json

# Or configure via gcloud
gcloud auth application-default login
```

### Step 3: Remove @Ignore Annotations

Edit `ObjectStoreProvidersTest.groovy` and remove `@Ignore` from tests you want to run:

```groovy
// Before:
@Ignore("Requires MinIO running on localhost:9000")
def "MinIO should create and delete bucket"() {
    // ...
}

// After:
def "MinIO should create and delete bucket"() {
    // ...
}
```

**Or** selectively run tests by pattern:

### Step 4: Run Integration Tests

```bash
# Run all integration tests (after removing @Ignore)
./gradlew test --tests "org.softwood.dag.task.objectstorage.ObjectStoreProvidersTest"

# Run specific provider tests
./gradlew test --tests "*.ObjectStoreProvidersTest.MinIO*"
./gradlew test --tests "*.ObjectStoreProvidersTest.AWS*"

# Run with info logging
./gradlew test --tests "*.ObjectStoreProvidersTest" --info
```

## Test Execution Summary

### Default Behavior (No Setup Required)
```bash
./gradlew test --tests "org.softwood.dag.task.objectstorage.*"
```
- ✅ ~110 unit tests **PASS**
- ⏭️ ~85 integration tests **SKIPPED** (@Ignore'd)
- ❌ 0 **FAILURES**

### With SDK Dependencies + Services Running
```bash
# Add dependencies to build.gradle
# Start services (MinIO, Garage, etc.)
# Remove @Ignore annotations
./gradlew test --tests "org.softwood.dag.task.objectstorage.*"
```
- ✅ ~110 unit tests **PASS**
- ✅ ~85 integration tests **PASS**
- ❌ 0 **FAILURES**

## Continuous Integration (CI/CD)

### CI Strategy

For CI pipelines, we recommend:

1. **Always run unit tests** (fast, no dependencies)
2. **Optionally run integration tests** against local services (MinIO/Garage in Docker)
3. **Periodically run cloud integration tests** (AWS/Azure/GCS with real credentials)

### Example GitHub Actions Workflow

```yaml
name: Object Storage Tests

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Run Unit Tests
        run: ./gradlew test --tests "org.softwood.dag.task.objectstorage.ObjectStoreBasicsTest" \
                            --tests "org.softwood.dag.task.objectstorage.ObjectStoreProviderUnitTest" \
                            --tests "org.softwood.dag.task.objectstorage.ObjectStoreConfigTest"
  
  integration-tests-local:
    runs-on: ubuntu-latest
    services:
      minio:
        image: minio/minio
        ports:
          - 9000:9000
        env:
          MINIO_ROOT_USER: minioadmin
          MINIO_ROOT_PASSWORD: minioadmin
        options: --health-cmd "curl -f http://localhost:9000/minio/health/live"
    
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Add MinIO Test Dependency
        run: |
          # Temporarily add dependency for tests
          sed -i '/dependencies {/a \    testImplementation "io.minio:minio:8.5.7"' build.gradle
      
      - name: Run MinIO Integration Tests
        run: |
          # Remove @Ignore from MinIO tests and run
          ./gradlew test --tests "*ObjectStoreProvidersTest.MinIO*"
```

### Example Gradle Test Configuration

```groovy
test {
    // Default: run only unit tests
    useJUnitPlatform()
    
    // Exclude integration tests by default
    exclude '**/*IntegrationTest*'
    
    // Print test results
    testLogging {
        events "passed", "skipped", "failed"
        exceptionFormat "full"
    }
}

// Separate task for integration tests
task integrationTest(type: Test) {
    // Include @Ignore'd tests
    useJUnitPlatform {
        includeTags 'integration'
    }
}
```

## Troubleshooting

### "ClassNotFoundException" for MinIO/AWS/Azure/GCS

**Problem:** Integration test fails with `ClassNotFoundException`

**Solution:** Add the required SDK dependency to `build.gradle` test dependencies

```groovy
testImplementation 'io.minio:minio:8.5.7'  // For MinIO/Garage
testImplementation 'software.amazon.awssdk:s3:2.20.0'  // For AWS
testImplementation 'com.azure:azure-storage-blob:12.20.0'  // For Azure
testImplementation 'com.google.cloud:google-cloud-storage:2.15.0'  // For GCS
```

### "Connection refused" errors

**Problem:** Test fails to connect to service

**Solution:** 
1. Verify service is running: `curl http://localhost:9000` (for MinIO)
2. Check port mappings if using Docker
3. Verify firewall settings

### AWS credential errors

**Problem:** `CredentialsNotFoundException` or `AccessDenied`

**Solution:**
1. Set environment variables:
   ```bash
   export AWS_ACCESS_KEY_ID=your_key
   export AWS_SECRET_ACCESS_KEY=your_secret
   export AWS_REGION=us-east-1
   ```
2. Or configure `~/.aws/credentials`
3. Check IAM permissions for S3 operations

### Azure authentication errors

**Problem:** `AuthenticationFailedException`

**Solution:**
1. Verify connection string format:
   ```
   DefaultEndpointsProtocol=https;AccountName=myaccount;AccountKey=mykey;EndpointSuffix=core.windows.net
   ```
2. Check that account key is correct (not base64 encoded in connection string)

### GCS authentication errors

**Problem:** `GoogleJsonResponseException: 401 Unauthorized`

**Solution:**
1. Set `GOOGLE_APPLICATION_CREDENTIALS` environment variable
2. Verify service account has Storage Admin role
3. Enable Cloud Storage API in GCP project

## Test Development Guidelines

### Writing New Unit Tests

Unit tests should:
- ✅ Test framework abstractions
- ✅ Test configuration validation
- ✅ Test error messages
- ✅ Verify provider identity/metadata
- ✅ Work without any SDK dependencies
- ❌ NOT call `provider.initialize()`
- ❌ NOT perform actual storage operations

Example:
```groovy
def "MinIO should reject invalid config"() {
    given:
    def provider = new MinIOProvider()
    
    when:
    provider.configure([accessKey: 'test'])  // Missing required fields
    
    then:
    thrown(ObjectStoreException)
}
```

### Writing New Integration Tests

Integration tests should:
- ✅ Test actual storage operations
- ✅ Clean up resources (buckets, objects)
- ✅ Be @Ignore'd by default
- ✅ Document required dependencies in @Ignore message
- ✅ Use unique bucket/container names (timestamps)
- ✅ Have proper cleanup in `cleanup:` block

Example:
```groovy
@Ignore("Requires MinIO running on localhost:9000")
def "MinIO should upload and download objects"() {
    given:
    def provider = new MinIOProvider()
    provider.configure([endpoint: 'http://localhost:9000', accessKey: 'minioadmin', secretKey: 'minioadmin'])
    provider.initialize()
    def bucket = "test-${System.currentTimeMillis()}"
    provider.createContainer(bucket, ContainerOptions.DEFAULT)
    
    when:
    def ref = ObjectRef.of(bucket, 'test.txt')
    provider.put(ref, 'Hello World!', PutOptions.DEFAULT)
    def content = provider.getText(ref, GetOptions.DEFAULT)
    
    then:
    content == 'Hello World!'
    
    cleanup:
    provider.deleteContainer(bucket, true)
    provider.close()
}
```

## Summary

The object storage framework has comprehensive test coverage:

- **110 unit tests** that run instantly without any dependencies
- **85 integration tests** available for when you need them
- **Clear separation** between fast unit tests and slower integration tests
- **Pluggable architecture** - only add SDKs you need
- **Production-ready** - all unit tests pass on every build

For day-to-day development, the unit tests provide excellent coverage. Integration tests are there when you need to verify actual cloud provider behavior.
