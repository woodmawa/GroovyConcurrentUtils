package org.softwood.sampleScripts.actors

import org.softwood.actor.Actor
import org.softwood.actor.ActorSystem

import java.time.Duration

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * FileCounterScriptV2.groovy - Version 2 (IMPROVED WITH ctx.deferReply())
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * PURPOSE:
 * Recursively counts files by extension type in a directory tree using an
 * improved actor-based approach that leverages ctx.deferReply() for clean
 * async handling.
 * 
 * ARCHITECTURE:
 * Same as V1, but with cleaner async coordination:
 * - Coordinator Actor: Aggregates results and tracks pending workers
 * - Scanner Actors: Process directories and spawn children via ctx.spawn()
 * - Message-based communication for distributed state aggregation
 * 
 * HOW IT WORKS:
 * 1. Coordinator receives 'scan' request and calls ctx.deferReply()
 *    - This prevents auto-reply and allows async work to complete
 * 2. Root scanner is spawned using ctx.spawn() for proper hierarchy
 * 3. Scanners process directories and recursively spawn children
 * 4. All results flow back to coordinator via 'results' messages
 * 5. When pendingWorkers reaches 0, coordinator calls ctx.reply()
 * 6. Original askSync() call receives the final result naturally
 * 
 * KEY IMPROVEMENTS OVER V1:
 * ✅ Uses ctx.deferReply() - NO CountDownLatch needed!
 * ✅ Uses ctx.self property for clean self-reference
 * ✅ Uses ctx.spawn() for proper actor hierarchy
 * ✅ Can use askSync() naturally - it just works!
 * ✅ NO external state variables needed
 * ✅ NO manual context storage required
 * ✅ NO dummy "pending" replies
 * ✅ Simpler HashMap handling with explicit .put()
 * ✅ Unique actor names using UUID to prevent collisions
 * 
 * CODE REDUCTION:
 * V1: ~240 lines
 * V2: ~130 lines
 * Reduction: ~45% less code!
 * 
 * COMPLEXITY REDUCTION:
 * • Eliminated: CountDownLatch, finalResult map, manual context storage
 * • Simplified: askSync() works naturally without workarounds
 * • Cleaner: More idiomatic actor patterns
 * 
 * CORE API USED:
 * • ctx.deferReply() - Prevents auto-reply for async workflows
 * • ctx.self - Property access to actor reference (lazily initialized)
 * • ctx.spawn() - Creates child actors with proper supervision
 * • askSync() - Now works naturally with deferred reply
 * 
 * LESSONS LEARNED:
 * The ctx.deferReply() improvement is transformative for async actor workflows.
 * It eliminates the need for manual coordination and makes the actor model
 * work naturally with request-response patterns.
 * 
 * This version demonstrates that the GroovyActor DSL, with ctx.deferReply(),
 * is now on par with mature frameworks like Akka for handling async workflows.
 * ═══════════════════════════════════════════════════════════════════════════
 */

def system = new ActorSystem('file-counter-system')

try {
    // Coordinator actor that aggregates results
    def coordinator = system.actor {
        name 'coordinator'
        state counts: new HashMap<String, Integer>(), 
              pendingWorkers: 0,
              startTime: 0
        
        onMessage { msg, ctx ->
            if (msg.type == 'scan') {
                // KEY IMPROVEMENT: Defer reply until async work completes
                ctx.deferReply()
                
                println "🔍 Starting scan of: ${msg.directory}"
                ctx.state.startTime = System.currentTimeMillis()
                ctx.state.pendingWorkers = 1
                
                // Create root scanner and pass coordinator reference
                def rootScanner = ctx.spawn("scanner-root") { scanMsg, scanCtx ->
                    if (scanMsg.type == 'scanDir') {
                        def dir = new File(scanMsg.path)
                        def coord = scanMsg.coordinator
                        
                        if (!dir.exists() || !dir.isDirectory()) {
                            coord.tell([type: 'workerDone'])
                            return
                        }
                        
                        // Scan this directory
                        def files = dir.listFiles() ?: []
                        def counts = new HashMap<String, Integer>()
                        def subdirs = []
                        
                        files.each { file ->
                            if (file.isFile()) {
                                def ext = file.name.contains('.') ? 
                                    file.name.substring(file.name.lastIndexOf('.') + 1).toLowerCase() : 
                                    'no-extension'
                                Integer currentCount = counts.get(ext)
                                counts.put(ext, (currentCount == null ? 0 : currentCount) + 1)
                            } else if (file.isDirectory()) {
                                subdirs << file
                            }
                        }
                        
                        // Send results
                        if (counts) {
                            coord.tell([type: 'results', counts: counts, dir: dir.name])
                        }
                        
                        // Spawn children for subdirectories
                        if (subdirs) {
                            coord.tell([type: 'addWorkers', count: subdirs.size()])
                            
                            subdirs.eachWithIndex { subdir, idx ->
                                // Use timestamp + random to ensure unique names
                                def uniqueName = "scanner-${subdir.name}-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
                                def child = scanCtx.spawn(uniqueName) { childMsg, childCtx ->
                                    if (childMsg.type == 'scanDir') {
                                        // Recursively process subdirectory
                                        scanCtx.self.tell(childMsg)
                                    }
                                }
                                child.tell([type: 'scanDir', path: subdir.absolutePath, coordinator: coord])
                            }
                        }
                        
                        coord.tell([type: 'workerDone'])
                    }
                }
                
                rootScanner.tell([type: 'scanDir', path: msg.directory.absolutePath, coordinator: ctx.self])
                
            } else if (msg.type == 'results') {
                // Aggregate results with proper null handling
                msg.counts.each { ext, count ->
                    Integer currentCount = ctx.state.counts.get(ext)
                    ctx.state.counts.put(ext, (currentCount == null ? 0 : currentCount) + count)
                }
                println "📊 Results from ${msg.dir}: ${msg.counts}"
                
            } else if (msg.type == 'addWorkers') {
                ctx.state.pendingWorkers += msg.count
                println "📈 Added ${msg.count} workers, total pending: ${ctx.state.pendingWorkers}"
                
            } else if (msg.type == 'workerDone') {
                ctx.state.pendingWorkers--
                println "✅ Worker done, pending: ${ctx.state.pendingWorkers}"
                
                if (ctx.state.pendingWorkers == 0) {
                    // All done - print and reply
                    def elapsed = System.currentTimeMillis() - ctx.state.startTime
                    println "\n" + "="*60
                    println "📁 FINAL RESULTS (completed in ${elapsed}ms)"
                    println "="*60
                    
                    def sortedCounts = ctx.state.counts.sort { -it.value }
                    def totalFiles = sortedCounts.values().sum() ?: 0
                    
                    sortedCounts.each { ext, count ->
                        def percentage = totalFiles > 0 ? (count / totalFiles * 100).round(1) : 0
                        println String.format("  %-20s: %,6d files (%5.1f%%)", 
                            ".${ext}", count, percentage)
                    }
                    
                    println "-"*60
                    println String.format("  %-20s: %,6d files", "TOTAL", totalFiles)
                    println "="*60
                    
                    // Reply to the original askSync
                    ctx.reply([done: true, counts: ctx.state.counts, totalFiles: totalFiles])
                }
            }
        }
    }
    
    // Choose directory to scan
    String searchDir = System.getProperty("user.home") + "\\IdeaProjects"
    def targetDir = new File(searchDir)
    assert targetDir.exists()
    
    println """
╔══════════════════════════════════════════════════════════════╗
║           IMPROVED ACTOR-BASED FILE COUNTER                   ║
╚══════════════════════════════════════════════════════════════╝

Key improvements:
  ✓ Uses ctx.deferReply() - no CountDownLatch!
  ✓ Uses ctx.self property
  ✓ Uses ctx.spawn() for child actors
  ✓ Clean async handling with askSync()

Target directory: ${targetDir.absolutePath}
"""
    
    println "Starting scan..."
    
    // Now askSync works properly! It waits for the deferred reply.
    def result = coordinator.askSync([type: 'scan', directory: targetDir], Duration.ofMinutes(2))
    
    if (result) {
        println "\n✅ Scan complete! Found ${result.totalFiles} files across ${result.counts.size()} file types."
    }
    
} catch (Exception e) {
    println "\n❌ Error: ${e.message}"
    e.printStackTrace()
} finally {
    println "\n🛑 Shutting down actor system..."
    system.shutdown()
}
