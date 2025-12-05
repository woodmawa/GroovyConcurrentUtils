package org.softwood.dag.task

import groovy.util.logging.Slf4j

/**
 * ShardingRouterTask
 *
 * Produces N parallel branches based on a collection returned
 * by shardSource(prevValue).
 *
 * The router returns a List<String> of shard IDs.
 * The engine should wire these IDs to "template" tasks.
 */
@Slf4j
class ShardingRouterTask extends RouterTask {

    /**
     * User closure:
     *    prevValue -> Collection items
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

        // evenly divided shards
        def shardSize = Math.max(1, Math.ceil(collection.size() / shardCount))

        List<String> shardIds = []

        (0..<shardCount).each { i ->
            def shardId = "${templateTargetId}_shard_${i}"
            shardIds << shardId
        }

        log.debug("ShardingRouterTask($id): generated shardIds = $shardIds")

        return shardIds
    }
}