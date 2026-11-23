package org.softwood.actor.examples

import org.softwood.actor.ActorSystem
import org.softwood.actor.transform.SafeActor

import java.time.Duration

/**
 * Example demonstrating safe patterns for actor code with Groovy closures.
 * 
 * This example shows:
 * - Common pitfalls with Groovy closures in actor code
 * - Safe alternatives using ctx.spawnForEach(), CounterMap, etc.
 * - Best practices for complex actor hierarchies
 * 
 * Note: The @SafeActor annotation (when fully implemented) will detect these
 * patterns at compile-time. For now, this example demonstrates the patterns
 * and helpers that make actor code safer.
 */
class SafeActorExample {
    
    static void main(String[] args) {
        def system = new ActorSystem('safe-actor-demo')
        
        try {
            println "=" * 60
            println "@SafeActor Patterns & Safe Alternatives"
            println "(Demonstrating closure-safe patterns for actors)"
            println "=" * 60
            println()
            
            // ================================================================
            // Example 1: UNSAFE - Actor creation in .each{} (will get warning)
            // ================================================================
            println "Example 1: UNSAFE Pattern (commented out - would cause warnings)"
            println "=" * 60
            
            // This code would trigger @SafeActor warning:
            /*
            @SafeActor
            def unsafeCoordinator = system.actor {
                name 'unsafe-coordinator'
                onMessage { msg, ctx ->
                    if (msg.type == 'spawn-workers') {
                        def items = msg.items
                        
                        // ⚠️ WARNING: Creating actors inside .each{} closure
                        // Variable 'item' may not be captured correctly!
                        items.each { item ->
                            system.actor {  // <-- @SafeActor warns here
                                name "worker-${item}"
                                onMessage { workerMsg, workerCtx ->
                                    println "Processing: ${item}"  // May be wrong value!
                                }
                            }
                        }
                    }
                }
            }
            */
            
            println "⚠️  The above pattern would trigger @SafeActor warnings:"
            println "    - Creating actors inside .each{} closure"
            println "    - Variable capture issues with 'item'"
            println()
            
            // ================================================================
            // Example 2: SAFE - Using ctx.spawnForEach() instead
            // ================================================================
            println "Example 2: SAFE Pattern - Using ctx.spawnForEach()"
            println "=" * 60
            
            def safeCoordinator = system.actor {
                name 'safe-coordinator'
                onMessage { msg, ctx ->
                    // Handle Terminated messages from child actors
                    if (msg instanceof org.softwood.actor.lifecycle.Terminated) {
                        // Child actor terminated - ignore for this example
                        return
                    }
                    
                    if (msg.type == 'spawn-workers') {
                        def items = msg.items
                        
                        // ✅ SAFE: Using ctx.spawnForEach()
                        // This is designed to handle variable capture correctly
                        def workers = ctx.spawnForEach(items, 'worker') { item, index, workerMsg, workerCtx ->
                            if (workerMsg.type == 'work') {
                                println "Worker ${index}: Processing ${item}"
                                workerCtx.reply("Processed: ${item}")
                            }
                        }
                        
                        println "✅ Spawned ${workers.size()} workers safely"
                        
                        // Send work to each worker
                        workers.each { worker ->
                            worker.tell([type: 'work'])
                        }
                        
                        ctx.reply("Spawned ${workers.size()} workers")
                    }
                }
            }
            
            def result = safeCoordinator.askSync([
                type: 'spawn-workers',
                items: ['task-1', 'task-2', 'task-3']
            ], Duration.ofSeconds(2))
            
            println result
            println()
            
            // ================================================================
            // Example 3: SAFE - Traditional for loop
            // ================================================================
            println "Example 3: SAFE Pattern - Traditional for loop"
            println "=" * 60
            
            def forLoopCoordinator = system.actor {
                name 'forloop-coordinator'
                onMessage { msg, ctx ->
                    // Handle Terminated messages from child actors
                    if (msg instanceof org.softwood.actor.lifecycle.Terminated) {
                        // Child actor terminated - ignore for this example
                        return
                    }
                    
                    if (msg.type == 'create-actors') {
                        def items = msg.items
                        
                        // ✅ SAFE: Traditional for loop
                        // No closure scoping issues
                        for (int i = 0; i < items.size(); i++) {
                            def item = items[i]
                            def index = i
                            
                            ctx.spawn("processor-${index}") { procMsg, procCtx ->
                                println "Processor ${index}: ${item}"
                                procCtx.reply("Done: ${item}")
                            }
                        }
                        
                        println "✅ Created ${items.size()} actors using for loop"
                        ctx.reply("Created ${items.size()} actors")
                    }
                }
            }
            
            result = forLoopCoordinator.askSync([
                type: 'create-actors',
                items: ['A', 'B', 'C']
            ], Duration.ofSeconds(2))
            
            println result
            println()
            
            // ================================================================
            // Example 4: Nested .each{} depth checking
            // ================================================================
            println "Example 4: @SafeActor detects deep nesting"
            println "=" * 60
            
            // This would also trigger warnings (commented out):
            /*
            @SafeActor
            def deeplyNested = system.actor {
                name 'deeply-nested'
                onMessage { msg, ctx ->
                    msg.groups.each { group ->           // Depth 1
                        group.items.each { item ->       // Depth 2
                            item.tasks.each { task ->    // Depth 3
                                task.steps.each { step -> // Depth 4
                                    step.actions.each { action -> // Depth 5
                                        action.commands.each { cmd -> // Depth 6 ⚠️
                                            // WARNING: 6+ levels of nesting!
                                            doSomething(cmd)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            */
            
            println "⚠️  @SafeActor warns when .each{} nesting exceeds 5 levels"
            println "    Suggestion: Refactor into separate methods or use iterators"
            println()
            
            // ================================================================
            // Example 5: CounterMap as safer alternative
            // ================================================================
            println "Example 5: Using CounterMap (no @SafeActor needed)"
            println "=" * 60
            
            def counterActor = system.actor {
                name 'counter-actor'
                onMessage { msg, ctx ->
                    // Handle Terminated messages from child actors
                    if (msg instanceof org.softwood.actor.lifecycle.Terminated) {
                        // Child actor terminated - ignore for this example
                        return
                    }
                    
                    if (msg.type == 'count-files') {
                        // ✅ CounterMap handles nested closures safely
                        def counts = ctx.createCounterMap()
                        
                        msg.files.each { file ->
                            def ext = file.substring(file.lastIndexOf('.') + 1)
                            counts.increment(ext)  // Safe in any nesting level!
                        }
                        
                        ctx.reply([
                            total: counts.total(),
                            breakdown: counts.toMap()
                        ])
                    }
                }
            }
            
            result = counterActor.askSync([
                type: 'count-files',
                files: ['doc1.txt', 'doc2.pdf', 'doc3.txt', 'image.jpg', 'doc4.pdf']
            ], Duration.ofSeconds(2))
            
            println "File counts: ${result.breakdown}"
            println "Total files: ${result.total}"
            println()
            
            // ================================================================
            // Summary
            // ================================================================
            println "=" * 60
            println "Summary: @SafeActor Best Practices"
            println "=" * 60
            println()
            println "✅ DO:"
            println "  - Use ctx.spawnForEach() for bulk actor creation"
            println "  - Use traditional for loops instead of .each{}"
            println "  - Use CounterMap for counting in nested closures"
            println "  - Use ActorPatterns for common patterns"
            println()
            println "⚠️  AVOID:"
            println "  - Creating actors inside .each{} closures"
            println "  - Deep nesting (6+ levels) of .each{}"
            println "  - Manual HashMap manipulation in closures"
            println()
            println "@SafeActor annotation (when fully implemented) will detect these issues."
            println "The helpers work fine without @SafeActor annotation."
            println()
            
        } catch (Exception e) {
            println "Error: ${e.message}"
            e.printStackTrace()
        } finally {
            system.shutdown()
        }
    }
}
