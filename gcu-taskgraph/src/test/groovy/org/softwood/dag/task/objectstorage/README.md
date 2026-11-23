# Object Storage Provider Tests

This directory contains comprehensive tests for the Object Storage provider implementations.

## Test Files

### 1. ObjectStoreBasicsTest.groovy ✅ (Runs without external services)
Tests the core framework components without requiring any external storage services:
- ObjectRef immutability and equality
- ObjectInfo metadata handling
- Options classes (PutOptions, GetOptions, etc.)
- ProviderCapabilities flags
- ObjectStoreException error handling
- ContainerInfo metadata
- AbstractObjectStoreProvider configuration management
- Provider identity and display names

**Run this test first** - it requires no setup and validates the core framework.

```bash
./gradlew test --tests ObjectStoreBasicsTest
```

### 2. ObjectStoreConfigTest.groovy ⚠️ (May require dependencies)
Tests provider configuration and initialization:
- MinIO configuration and validation
- Garage configuration
- AWS S3 configuration (with credential chain support)
- Azure Blob configuration (connection string vs. account credentials)
- Google Cloud Storage configuration
- Provider capabilities verification
- Configuration helper methods
- Close/cleanup operations

**Note:** Some tests may fail if SDK dependencies are not available, but configuration tests should mostly pass.

```bash
./gradlew test --tests ObjectStoreConfigTest
```

### 3. ObjectStoreProvidersTest.groovy 🚫 (Requires external services)
Tests actual provider operations against real storage services. **All tests are @Ignore'd by default** because they require running services.

Tests include:
- Bucket/container creation and deletion
- Object upload and download
- Metadata handling
- Prefix-based listing
- Object copying
- Range reads
- Pagination
- Cross-provider consistency
- Error handling

## Running Integration Tests

To run the provider tests, you need to:

### Option 1: Use Docker Compose (Recommended)

Create a `docker-compose.yml` in your project root:

```yaml
version: '3.8'

services:
  minio:
    image: minio/minio:latest
    ports:
      - "9000:9000"
      - "9001:9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    command: server /data --console-address ":9001"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 30s
      timeout: 20s
      retries: 3

  garage:
    image: dxflrs/garage:latest
    ports:
      - "3900:3900"
      - "3901:3901"
    environment:
      GARAGE_RPC_SECRET: "some-secret-key"
    command: server
```

Then start services:
```bash
docker-compose up -d
```

### Option 2: Install Locally

#### MinIO
```bash
# macOS
brew install minio/stable/minio
minio server /tmp/minio-data

# Linux
wget https://dl.min.io/server/minio/release/linux-amd64/minio
chmod +x minio
./minio server /tmp/minio-data
```

#### Garage
```bash
# Download from https://garagehq.deuxfleurs.fr/
# Follow installation instructions
```

### Option 3: Use Cloud Services

For AWS S3, Azure, and GCS tests, you'll need actual cloud accounts:

**AWS S3:**
```bash
export AWS_ACCESS_KEY_ID=your-key
export AWS_SECRET_ACCESS_KEY=your-secret
export AWS_REGION=us-east-1
```

**Azure Blob:**
```bash
export AZURE_STORAGE_CONNECTION_STRING="your-connection-string"
# OR
export AZURE_STORAGE_ACCOUNT=your-account
export AZURE_STORAGE_KEY=your-key
```

**Google Cloud Storage:**
```bash
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
```

## Running Specific Tests

### Run only basic tests (no external dependencies):
```bash
./gradlew test --tests ObjectStoreBasicsTest
```

### Run configuration tests:
```bash
./gradlew test --tests ObjectStoreConfigTest
```

### Run specific provider test (after removing @Ignore):
```bash
# Edit ObjectStoreProvidersTest.groovy and remove @Ignore from specific test
./gradlew test --tests "ObjectStoreProvidersTest.MinIO should create and delete bucket"
```

### Run all object storage tests:
```bash
./gradlew test --tests "org.softwood.dag.task.objectstorage.*"
```

## Enabling Provider Integration Tests

To run integration tests:

1. Start the required service (MinIO, Garage, etc.)
2. Open `ObjectStoreProvidersTest.groovy`
3. Find the test you want to run
4. Remove the `@Ignore` annotation
5. Update configuration if needed (endpoint, credentials, etc.)
6. Run the test

Example:
```groovy
// Before
@Ignore("Requires MinIO running on localhost:9000")
def "MinIO should create and delete bucket"() {
    // ... test code
}

// After (to enable test)
def "MinIO should create and delete bucket"() {
    // ... test code
}
```

## Test Coverage

### What's Tested

✅ **Core Framework** (ObjectStoreBasicsTest)
- Value objects (ObjectRef, ObjectInfo, ContainerInfo)
- Options classes
- Exception handling
- Provider capabilities
- Configuration management

✅ **Configuration** (ObjectStoreConfigTest)
- Provider initialization
- Configuration validation
- Credential handling
- Capability flags
- Error conditions

⚠️ **Operations** (ObjectStoreProvidersTest - @Ignore'd)
- CRUD operations
- Container management
- Metadata handling
- Listing and pagination
- Range reads
- Object copying
- Cross-provider consistency

### What's NOT Tested (Yet)

- Concurrent access
- Large file uploads (multipart)
- Presigned URLs
- Advanced versioning scenarios
- Lifecycle policies
- Access control policies
- Encryption at rest
- Network failure scenarios
- Retry logic
- Connection pooling

## Continuous Integration

For CI/CD pipelines, consider:

1. **Basic Tests Only** (default):
   ```yaml
   - run: ./gradlew test --tests ObjectStoreBasicsTest
   ```

2. **With MinIO** (using Docker):
   ```yaml
   services:
     minio:
       image: minio/minio
       ports:
         - 9000:9000
       env:
         MINIO_ROOT_USER: minioadmin
         MINIO_ROOT_PASSWORD: minioadmin
   
   - run: ./gradlew test --tests ObjectStoreProvidersTest
   ```

3. **With Cloud Services** (use secrets):
   ```yaml
   - run: |
       export AWS_ACCESS_KEY_ID=${{ secrets.AWS_KEY }}
       export AWS_SECRET_ACCESS_KEY=${{ secrets.AWS_SECRET }}
       ./gradlew test --tests "ObjectStoreProvidersTest.AWS*"
   ```

## Troubleshooting

### "Cannot resolve class" errors
Make sure dependencies are in build.gradle:
```groovy
dependencies {
    implementation 'io.minio:minio:8.5.7'
    implementation platform('software.amazon.awssdk:bom:2.20.0')
    implementation 'software.amazon.awssdk:s3'
    implementation 'com.azure:azure-storage-blob:12.20.0'
    implementation 'com.google.cloud:google-cloud-storage:2.15.0'
    
    testImplementation 'org.spockframework:spock-core:2.3-groovy-4.0'
}
```

### Connection refused errors
Ensure the service is running:
```bash
# MinIO
curl http://localhost:9000/minio/health/live

# Check container logs
docker logs <container-id>
```

### Authentication errors
Verify credentials are correct and have necessary permissions.

### "Bucket already exists" errors
Tests create unique bucket names using timestamps, but if running tests rapidly, you may need to add cleanup logic or wait between runs.

## Best Practices

1. **Always run ObjectStoreBasicsTest first** - validates framework is working
2. **Use unique bucket names** - include timestamps or UUIDs to avoid conflicts
3. **Clean up resources** - always delete test buckets/containers in cleanup blocks
4. **Mock for unit tests** - only use real services for integration tests
5. **Use @Ignore liberally** - don't break CI with tests requiring external services
6. **Document requirements** - clearly state what each test needs to run

## Contributing

When adding new tests:

1. Add unit tests to ObjectStoreBasicsTest (no external dependencies)
2. Add configuration tests to ObjectStoreConfigTest
3. Add integration tests to ObjectStoreProvidersTest (with @Ignore)
4. Update this README with any new requirements
5. Consider adding cross-provider consistency tests

## Related Documentation

- [Object Storage Providers Complete](../../../../../../OBJECT_STORAGE_PROVIDERS_COMPLETE.md)
- [Spock Framework](http://spockframework.org/)
- [MinIO Documentation](https://min.io/docs/)
- [Garage Documentation](https://garagehq.deuxfleurs.fr/)
