package org.softwood.actor

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.*
import org.softwood.actor.remote.security.AuthContext as SecurityAuthContext
import org.softwood.actor.remote.security.JwtTokenService

import java.time.Duration

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for RBAC (Role-Based Access Control) in ActorContext.
 */
@CompileDynamic
class ActorRbacTest {
    
    private static final String TEST_SECRET = "this-is-a-very-secret-key-with-at-least-32-characters!"
    
    private ActorSystem system
    private JwtTokenService tokenService
    
    @BeforeEach
    void setup() {
        system = new ActorSystem('rbacTestSystem')
        tokenService = new JwtTokenService(TEST_SECRET, Duration.ofHours(1))
    }
    
    @AfterEach
    void cleanup() {
        system?.shutdown()
    }
    
    @Test
    void test_actor_context_with_authenticated_user() {
        // Create token with roles
        def token = tokenService.generateToken('alice', ['USER', 'MANAGER'])
        def claims = tokenService.validateToken(token)
        def authContext = new SecurityAuthContext(claims, token)
        
        // Create actor that uses auth context
        def actor = system.actor {
            name 'secureActor'
            onMessage { msg, ctx ->
                // Check authentication
                assertTrue(ctx.isAuthenticated())
                assertEquals('alice', ctx.subject)
                assertEquals(['USER', 'MANAGER'], ctx.roles)
                
                ctx.reply("Authenticated: ${ctx.subject}")
            }
        }
        
        // Manually create context with auth (simulating authenticated request)
        def context = new ActorContext(
            'secureActor',
            'test message',
            [:],
            system,
            new java.util.concurrent.CompletableFuture(),
            null,
            authContext
        )
        
        // Verify auth context is available
        assertTrue(context.isAuthenticated())
        assertEquals('alice', context.subject)
        assertTrue(context.hasRole('USER'))
        assertTrue(context.hasRole('MANAGER'))
    }
    
    @Test
    void test_actor_context_without_authentication() {
        // Create actor context without auth
        def context = new ActorContext(
            'publicActor',
            'test message',
            [:],
            system,
            new java.util.concurrent.CompletableFuture(),
            null,
            null  // No auth context
        )
        
        // Verify not authenticated
        assertFalse(context.isAuthenticated())
        assertNull(context.subject)
        assertEquals([], context.roles)
        assertFalse(context.hasRole('USER'))
    }
    
    @Test
    void test_hasRole_single_role() {
        def token = tokenService.generateToken('bob', ['ADMIN'])
        def claims = tokenService.validateToken(token)
        def authContext = new SecurityAuthContext(claims, token)
        
        def context = new ActorContext(
            'actor',
            'msg',
            [:],
            system,
            new java.util.concurrent.CompletableFuture(),
            null,
            authContext
        )
        
        assertTrue(context.hasRole('ADMIN'))
        assertFalse(context.hasRole('USER'))
        assertFalse(context.hasRole('MANAGER'))
    }
    
    @Test
    void test_hasAnyRole() {
        def token = tokenService.generateToken('charlie', ['USER', 'ANALYST'])
        def claims = tokenService.validateToken(token)
        def authContext = new SecurityAuthContext(claims, token)
        
        def context = new ActorContext(
            'actor',
            'msg',
            [:],
            system,
            new java.util.concurrent.CompletableFuture(),
            null,
            authContext
        )
        
        // Has at least one of these roles
        assertTrue(context.hasAnyRole(['USER', 'ADMIN']))
        assertTrue(context.hasAnyRole(['ANALYST', 'MANAGER']))
        
        // Doesn't have any of these roles
        assertFalse(context.hasAnyRole(['ADMIN', 'SUPERUSER']))
    }
    
    @Test
    void test_hasAllRoles() {
        def token = tokenService.generateToken('diana', ['USER', 'MANAGER', 'ANALYST'])
        def claims = tokenService.validateToken(token)
        def authContext = new SecurityAuthContext(claims, token)
        
        def context = new ActorContext(
            'actor',
            'msg',
            [:],
            system,
            new java.util.concurrent.CompletableFuture(),
            null,
            authContext
        )
        
        // Has all of these roles
        assertTrue(context.hasAllRoles(['USER', 'MANAGER']))
        assertTrue(context.hasAllRoles(['USER', 'ANALYST']))
        
        // Doesn't have all of these roles
        assertFalse(context.hasAllRoles(['USER', 'ADMIN']))
    }
    
    @Test
    void test_requireAuthenticated_success() {
        def token = tokenService.generateToken('eve', ['USER'])
        def claims = tokenService.validateToken(token)
        def authContext = new SecurityAuthContext(claims, token)
        
        def context = new ActorContext(
            'actor',
            'msg',
            [:],
            system,
            new java.util.concurrent.CompletableFuture(),
            null,
            authContext
        )
        
        // Should not throw
        context.requireAuthenticated()
    }
    
    @Test
    void test_requireAuthenticated_fails_when_not_authenticated() {
        def context = new ActorContext(
            'actor',
            'msg',
            [:],
            system,
            new java.util.concurrent.CompletableFuture(),
            null,
            null  // No auth
        )
        
        // Should throw
        assertThrows(SecurityAuthContext.AuthenticationException) {
            context.requireAuthenticated()
        }
    }
    
    @Test
    void test_requireRole_success() {
        def token = tokenService.generateToken('frank', ['ADMIN'])
        def claims = tokenService.validateToken(token)
        def authContext = new SecurityAuthContext(claims, token)
        
        def context = new ActorContext(
            'actor',
            'msg',
            [:],
            system,
            new java.util.concurrent.CompletableFuture(),
            null,
            authContext
        )
        
        // Should not throw
        context.requireRole('ADMIN')
    }
    
    @Test
    void test_requireRole_fails_when_missing_role() {
        def token = tokenService.generateToken('grace', ['USER'])
        def claims = tokenService.validateToken(token)
        def authContext = new SecurityAuthContext(claims, token)
        
        def context = new ActorContext(
            'actor',
            'msg',
            [:],
            system,
            new java.util.concurrent.CompletableFuture(),
            null,
            authContext
        )
        
        // Should throw - user has USER but not ADMIN
        assertThrows(SecurityAuthContext.AuthorizationException) {
            context.requireRole('ADMIN')
        }
    }
    
    @Test
    void test_requireAnyRole_success() {
        def token = tokenService.generateToken('henry', ['USER', 'ANALYST'])
        def claims = tokenService.validateToken(token)
        def authContext = new SecurityAuthContext(claims, token)
        
        def context = new ActorContext(
            'actor',
            'msg',
            [:],
            system,
            new java.util.concurrent.CompletableFuture(),
            null,
            authContext
        )
        
        // Should not throw - has USER
        context.requireAnyRole(['USER', 'ADMIN'])
    }
    
    @Test
    void test_requireAnyRole_fails_when_no_matching_roles() {
        def token = tokenService.generateToken('iris', ['USER'])
        def claims = tokenService.validateToken(token)
        def authContext = new SecurityAuthContext(claims, token)
        
        def context = new ActorContext(
            'actor',
            'msg',
            [:],
            system,
            new java.util.concurrent.CompletableFuture(),
            null,
            authContext
        )
        
        // Should throw - has USER but needs ADMIN or MANAGER
        assertThrows(SecurityAuthContext.AuthorizationException) {
            context.requireAnyRole(['ADMIN', 'MANAGER'])
        }
    }
    
    @Test
    void test_requireAllRoles_success() {
        def token = tokenService.generateToken('jack', ['USER', 'MANAGER', 'ANALYST'])
        def claims = tokenService.validateToken(token)
        def authContext = new SecurityAuthContext(claims, token)
        
        def context = new ActorContext(
            'actor',
            'msg',
            [:],
            system,
            new java.util.concurrent.CompletableFuture(),
            null,
            authContext
        )
        
        // Should not throw - has all required roles
        context.requireAllRoles(['USER', 'MANAGER'])
    }
    
    @Test
    void test_requireAllRoles_fails_when_missing_one_role() {
        def token = tokenService.generateToken('karen', ['USER', 'ANALYST'])
        def claims = tokenService.validateToken(token)
        def authContext = new SecurityAuthContext(claims, token)
        
        def context = new ActorContext(
            'actor',
            'msg',
            [:],
            system,
            new java.util.concurrent.CompletableFuture(),
            null,
            authContext
        )
        
        // Should throw - has USER and ANALYST but needs MANAGER too
        assertThrows(SecurityAuthContext.AuthorizationException) {
            context.requireAllRoles(['USER', 'ANALYST', 'MANAGER'])
        }
    }
    
    @Test
    void test_actor_with_role_check_in_message_handler() {
        // Create actor that requires ADMIN role
        def adminActor = system.actor {
            name 'adminPanel'
            onMessage { msg, ctx ->
                // Check if user is authenticated and has ADMIN role
                if (!ctx.isAuthenticated()) {
                    ctx.reply("ERROR: Authentication required")
                    return
                }
                
                if (!ctx.hasRole('ADMIN')) {
                    ctx.reply("ERROR: Forbidden - ADMIN role required")
                    return
                }
                
                // User is authorized
                ctx.reply("Welcome Admin ${ctx.subject}")
            }
        }
        
        // Test with ADMIN user
        def adminToken = tokenService.generateToken('admin-user', ['ADMIN'])
        def adminClaims = tokenService.validateToken(adminToken)
        def adminAuthContext = new SecurityAuthContext(adminClaims, adminToken)
        
        def adminFuture = new java.util.concurrent.CompletableFuture()
        def adminContext = new ActorContext(
            'adminPanel',
            'access',
            [:],
            system,
            adminFuture,
            null,
            adminAuthContext
        )
        
        // Simulate message processing (normally done by GroovyActor)
        // This is just to demonstrate the RBAC pattern
        assertTrue(adminContext.hasRole('ADMIN'))
        
        // Test with regular USER (should be denied)
        def userToken = tokenService.generateToken('regular-user', ['USER'])
        def userClaims = tokenService.validateToken(userToken)
        def userAuthContext = new SecurityAuthContext(userClaims, userToken)
        
        def userFuture = new java.util.concurrent.CompletableFuture()
        def userContext = new ActorContext(
            'adminPanel',
            'access',
            [:],
            system,
            userFuture,
            null,
            userAuthContext
        )
        
        // User should NOT have ADMIN role
        assertFalse(userContext.hasRole('ADMIN'))
    }
    
    @Test
    void test_actor_with_multiple_role_requirements() {
        // Create actor that requires either MANAGER or ADMIN
        def reportsActor = system.actor {
            name 'reports'
            onMessage { msg, ctx ->
                // Require any of these roles
                try {
                    ctx.requireAnyRole(['MANAGER', 'ADMIN', 'ANALYST'])
                    ctx.reply("Report generated for ${ctx.subject}")
                } catch (SecurityAuthContext.AuthorizationException e) {
                    ctx.reply("ERROR: ${e.message}")
                }
            }
        }
        
        // Test with MANAGER
        def managerToken = tokenService.generateToken('manager-user', ['USER', 'MANAGER'])
        def managerClaims = tokenService.validateToken(managerToken)
        def managerAuthContext = new SecurityAuthContext(managerClaims, managerToken)
        
        def managerContext = new ActorContext(
            'reports',
            'generate',
            [:],
            system,
            new java.util.concurrent.CompletableFuture(),
            null,
            managerAuthContext
        )
        
        // Should have required role
        assertTrue(managerContext.hasAnyRole(['MANAGER', 'ADMIN', 'ANALYST']))
    }
}
