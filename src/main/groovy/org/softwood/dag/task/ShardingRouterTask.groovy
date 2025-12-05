package org.softwood.dag.task

import groovy.util.logging.Slf4j

/**
 * ShardingRouterTask - COMPLETE FIXED VERSION
 *
 * Splits input data into N shards, stores them in an instance variable,
 * and returns the list of shard task IDs to execute.
 *
 * TaskGraph reads the shard data via getShardData() and passes it
 * directly to each shard task through the promise chain.
 */
@Slf4j
class ShardingRouterTask extends RouterTask {

    /**
     * User closure: prevValue -> Collection items
     */
    Closure shardSource

    /**
     * Number of shards to produce.
     */
    int shardCount = 1

    /**
     * A template downstream task ID.
     * Actual generated successor IDs will be:
     *    "${templateId}_shard_${i}"
     */
    String templateTargetId

    /**
     * Store computed shard data so TaskGraph can access it.
     * Package-scope so TaskGraph can read it.
     */
    @groovy.transform.PackageScope
    Map<String, List> computedShardData = [:]

    ShardingRouterTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    @Override
    protected List<String> route(Object prevValue) {

        if (!shardSource)
            throw new IllegalStateException("ShardingRouterTask($id) requires shardSource closure")

        if (!templateTargetId)
            throw new IllegalStateException("ShardingRouterTask($id) requires templateTargetId")

        if (shardCount <= 0)
            throw new IllegalStateException("ShardingRouterTask($id) shardCount must be >= 1")

        log.debug("ShardingRouterTask($id): computing shard source from prevValue=$prevValue")

        def collection = shardSource(prevValue)

        if (!(collection instanceof Collection))
            throw new IllegalStateException(
                    "ShardingRouterTask($id) shardSource must return a Collection, got: $collection"
            )

        log.debug("ShardingRouterTask($id): received collection size = ${collection.size()}")

        // Calculate shard size (divide collection into N parts)
        List collectionList = collection.toList()
        int totalSize = collectionList.size()
        int shardSize = Math.max(1, (int) Math.ceil(totalSize / (double) shardCount))

        log.debug("ShardingRouterTask($id): totalSize=$totalSize, shardCount=$shardCount, shardSize=$shardSize")

        // Split data into shards and store in instance variable
        List<String> shardIds = []
        computedShardData.clear()  // Clear previous data

        (0..<shardCount).each { i ->
            def shardId = "${templateTargetId}_shard_${i}"
            shardIds << shardId

            // Calculate slice indices for this shard
            int startIdx = i * shardSize
            int endIdx = Math.min(startIdx + shardSize, totalSize)

            // Extract the shard data
            List shard = (startIdx < totalSize) ? collectionList[startIdx..<endIdx] : []

            log.debug("ShardingRouterTask($id): shard $i -> [$startIdx..<$endIdx] = $shard")

            // Store shard data in instance variable
            computedShardData[shardId] = shard
        }

        log.debug("ShardingRouterTask($id): generated shardIds = $shardIds")
        log.debug("ShardingRouterTask($id): stored shard data for: ${computedShardData.keySet()}")

        return shardIds
    }

    /**
     * Get the shard data for a specific shard ID.
     * Called by TaskGraph when scheduling shard tasks.
     *
     * @param shardId The ID of the shard task
     * @return The data for this shard, or empty list if not found
     */
    List getShardData(String shardId) {
        List data = computedShardData[shardId]
        log.debug("ShardingRouterTask($id): getShardData($shardId) returning: $data")
        return data ?: []
    }
}