package org.softwood.sampleScripts.actors

import org.softwood.actor.Actor
import org.softwood.actor.ActorSystem

import java.time.Duration
import java.util.concurrent.CountDownLatch

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * FileCounterScript.groovy - Version 1 (ORIGINAL IMPLEMENTATION)
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * PURPOSE:
 * Recursively counts files by extension type in a directory tree using an
 * actor-based approach for parallel processing.
 * 
 * ARCHITECTURE:
 * - Coordinator Actor: Aggregates results and tracks pending workers
 * - Scanner Actors: Process individual directories and spawn children for subdirs
 * - Message-based communication for distributed state aggregation
 * 
 * HOW IT WORKS:
 * 1. Coordinator receives 'scan' request and creates root scanner actor
 * 2. Root scanner processes its directory:
 *    - Counts files by extension
 *    - Identifies subdirectories
 *    - Spawns child scanner actors for each subdirectory
 *    - Each child recursively scans its entire tree
 * 3. All scanners send 'results' messages back to coordinator
 * 4. Coordinator aggregates counts and tracks pending workers
 * 5. When all workers complete, final results are printed
 * 
 * KEY PATTERNS DEMONSTRATED:
 * • Parallel directory traversal (one actor per top-level subdirectory)
 * • Recursive subdirectory scanning within each actor
 * • Distributed state aggregation via message passing
 * • Worker coordination using pending counter
 * 
 * CHALLENGES IN THIS VERSION:
 * ⚠️  Uses CountDownLatch for manual async coordination
 * ⚠️  Requires external state (finalResult map)
 * ⚠️  Cannot use askSync() properly - forces tell() + manual waiting
 * ⚠️  Stores reply context manually for later use
 * ⚠️  Sends dummy "pending" reply to prevent auto-reply
 * ⚠️  Complex workarounds due to lack of ctx.deferReply()
 * ⚠️  Extensive Groovy closure scope management needed
 * ⚠️  Must use HashMap.put() instead of map[key] in nested closures
 * ⚠️  Must use traditional for loops instead of .each in some places
 * 
 * LINES OF CODE: ~240
 * 
 * SEE FileCounterScriptV2.groovy for the improved version using ctx.deferReply()
 * which eliminates most of these workarounds and reduces code by ~45%.
 * ═══════════════════════════════════════════════════════════════════════════
 */

def system = new ActorSystem('file-counter-system')
def completionLatch = new CountDownLatch(1)
def finalResult = [:]

try {
    // Coordinator actor that aggregates results from directory scanners
    def coordinator = system.actor {
        name 'coordinator'
        state counts: [:].withDefault { 0 }, 
              pendingWorkers: 0,
              startTime: System.currentTimeMillis(),
              scanReplyContext: null  // Store the context to reply later
        
        onMessage { msg, ctx ->
            if (msg.type == 'scan') {
                // Initial scan request
                println "🔍 Starting scan of: ${msg.directory}"
                ctx.state.startTime = System.currentTimeMillis()
                ctx.state.scanReplyContext = ctx  // Save context to reply later
                
                // Capture coordinator reference using ctx.self property
                def coordRef = ctx.self
                println "👥 Coordinator reference: ${coordRef}"
                
                // Create a scanner actor for the root directory
                def scanner = system.actor {
                    name "scanner-${msg.directory.name}"
                    
                    onMessage { scanMsg, scanCtx ->
                        println "⚡ Scanner received message: ${scanMsg.type}"
                        if (scanMsg.type == 'scanDir') {
                            def dir = new File(scanMsg.path)
                            def coordinator = scanMsg.coordinator  // Get coordinator from message
                            println "📁 Scanning directory: ${dir.absolutePath}, coordinator: ${coordinator}"
                            
                            if (!dir.exists() || !dir.isDirectory()) {
                                coordinator.tell([type: 'workerDone'])
                                return
                            }
                            
                            def files = dir.listFiles() ?: []
                            def subdirs = []
                            def localCounts = [:].withDefault { 0 }
                            
                            // Process files and identify subdirectories
                            files.each { file ->
                                if (file.isFile()) {
                                    def extension = file.name.contains('.') ? 
                                        file.name.substring(file.name.lastIndexOf('.') + 1).toLowerCase() : 
                                        'no-extension'
                                    localCounts[extension]++
                                } else if (file.isDirectory()) {
                                    subdirs << file
                                }
                            }
                            
                            // Send local results back to coordinator
                            if (localCounts) {
                                coordinator.tell([type: 'results', counts: localCounts, dir: dir.name])
                            }
                            
                            // Spawn child actors for subdirectories
                            if (subdirs) {
                                // Tell coordinator about new workers BEFORE creating them
                                coordinator.tell([type: 'addWorkers', count: subdirs.size()])
                                
                                // Use traditional for loop instead of .each to avoid closure issues
                                for (int idx = 0; idx < subdirs.size(); idx++) {
                                    File subdir = subdirs[idx]
                                    
                                    // Define recursive scanning function BEFORE creating the actor
                                    def scanDirRecursive
                                    scanDirRecursive = { File dirToScan, Actor coordActor ->
                                        if (!dirToScan.exists() || !dirToScan.isDirectory()) return
                                        
                                        File[] filesArray = dirToScan.listFiles()
                                        if (filesArray == null) return
                                        
                                        Map<String, Integer> recursiveCounts = new HashMap<>()
                                        List<File> recursiveSubdirs = []
                                        
                                        for (File fileItem : filesArray) {
                                            if (fileItem.isFile()) {
                                                String fileItemName = fileItem.name
                                                String fileExt = fileItemName.contains('.') ? fileItemName.substring(fileItemName.lastIndexOf('.') + 1).toLowerCase() : 'no-extension'
                                                Integer currentCountValue = recursiveCounts.get(fileExt)
                                                recursiveCounts.put(fileExt, (currentCountValue == null ? 0 : currentCountValue) + 1)
                                            } else if (fileItem.isDirectory()) {
                                                recursiveSubdirs.add(fileItem)
                                            }
                                        }
                                        
                                        if (!recursiveCounts.isEmpty()) {
                                            coordActor.tell([type: 'results', counts: recursiveCounts, dir: dirToScan.name])
                                        }
                                        
                                        // Recurse into subdirectories
                                        for (File subdirItem : recursiveSubdirs) {
                                            scanDirRecursive(subdirItem, coordActor)
                                        }
                                    }
                                    
                                    def childScanner = system.actor {
                                        name "scanner-${subdir.name}-${System.currentTimeMillis()}-${idx}"
                                        
                                        onMessage { childMsg, childCtx ->
                                            if (childMsg.type == 'scanDir') {
                                                File childDir = new File((String)childMsg.path)
                                                Actor coord = (Actor)childMsg.coordinator
                                                
                                                if (!childDir.exists() || !childDir.isDirectory()) {
                                                    coord.tell([type: 'workerDone'])
                                                    return
                                                }
                                                
                                                File[] fileArray = childDir.listFiles()
                                                List<File> childFilesList = fileArray ? Arrays.asList(fileArray) : []
                                                List<File> childSubdirsList = []
                                                Map<String, Integer> childCountsMap = new HashMap<>()
                                                
                                                // Process files
                                                for (File f : childFilesList) {
                                                    if (f.isFile()) {
                                                        String fileName = f.name
                                                        String ext = fileName.contains('.') ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : 'no-extension'
                                                        Integer currentCount = childCountsMap.get(ext)
                                                        childCountsMap.put(ext, (currentCount == null ? 0 : currentCount) + 1)
                                                    } else if (f.isDirectory()) {
                                                        childSubdirsList.add(f)
                                                    }
                                                }
                                                
                                                // Send results
                                                if (!childCountsMap.isEmpty()) {
                                                    coord.tell([type: 'results', counts: childCountsMap, dir: childDir.name])
                                                }
                                                
                                                // Handle subdirectories - recursively scan them
                                                if (!childSubdirsList.isEmpty()) {
                                                    for (File subsubdir : childSubdirsList) {
                                                        // Recursively scan subdirectories using the closure
                                                        scanDirRecursive(subsubdir, coord)
                                                    }
                                                }
                                                
                                                coord.tell([type: 'workerDone'])
                                            }
                                        }
                                    }
                                    
                                    childScanner.tell([type: 'scanDir', path: subdir.absolutePath, coordinator: coordinator])
                                }
                            }
                            
                            // Mark this worker as done
                            coordinator.tell([type: 'workerDone'])
                        }
                    }
                }
                
                ctx.state.pendingWorkers = 1
                println "🔵 Starting with 1 pending worker, sending scanDir to scanner"
                scanner.tell([type: 'scanDir', path: msg.directory.absolutePath, coordinator: coordRef])
                
                // Reply immediately with "pending" to prevent null auto-reply
                // The real result will be sent when work completes
                ctx.reply([status: 'pending', message: 'Scan in progress'])
                
            } else if (msg.type == 'results') {
                // Aggregate results from a scanner
                msg.counts.each { ext, count ->
                    Integer currentCount = ctx.state.counts.get(ext)
                    ctx.state.counts.put(ext, (currentCount == null ? 0 : currentCount) + count)
                }
                println "📊 Results from ${msg.dir}: ${msg.counts}"
                
            } else if (msg.type == 'addWorkers') {
                // Track additional workers that were spawned
                ctx.state.pendingWorkers += msg.count
                println "📈 Added ${msg.count} workers, total pending: ${ctx.state.pendingWorkers}"
                
            } else if (msg.type == 'workerDone') {
                // Worker completed
                ctx.state.pendingWorkers--
                println "✅ Worker done, pending: ${ctx.state.pendingWorkers}"
                
                if (ctx.state.pendingWorkers == 0) {
                    // All workers done - print final results
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
                    
                    // Store result and signal completion
                    finalResult.done = true
                    finalResult.counts = ctx.state.counts
                    finalResult.totalFiles = totalFiles
                    completionLatch.countDown()
                    
                    // Reply to the original scan request using stored context
                    ctx.state.scanReplyContext.reply([done: true, counts: ctx.state.counts, totalFiles: totalFiles])
                }
            } else if (msg.type == 'getStatus') {
                ctx.reply([
                    pendingWorkers: ctx.state.pendingWorkers,
                    currentCounts: ctx.state.counts
                ])
            }
        }
    }
    
    // Choose directory to scan (modify as needed)
    String searchDir = System.getProperty("user.home")

    searchDir  = searchDir + "\\IdeaProjects"
    def targetDir = new File(searchDir)
    assert targetDir.exists()
    // Or scan current project:
    // def targetDir = new File("C:\\Users\\willw\\IdeaProjects\\GroovyConcurrentUtils")
    
    println """
╔══════════════════════════════════════════════════════════════╗
║           ACTOR-BASED FILE COUNTER EXAMPLE                    ║
╚══════════════════════════════════════════════════════════════╝

This example demonstrates:
  • Parallel directory traversal using actors
  • Recursive subdirectory scanning
  • Distributed state aggregation
  • Coordination of asynchronous workers

Target directory: ${targetDir.absolutePath}
"""
    
    // Start the scan (it will reply immediately with "pending")
    coordinator.tell([type: 'scan', directory: targetDir])
    
    // Wait for actual completion
    println "Waiting for scan to complete..."
    completionLatch.await()
    
    if (finalResult.done) {
        println "\n✅ Scan complete! Found ${finalResult.totalFiles} files across ${finalResult.counts.size()} file types."
    } else {
        println "\n⚠️ Scan did not complete successfully"
    }
    
} finally {
    println "\n🛑 Shutting down actor system..."
    system.shutdown()
}
