package org.softwood.dag

class ForkDsl {
    private final TaskGraph graph
    String fromId
    List<String> toIds = []

    ForkDsl(TaskGraph graph) {
        this.graph = graph
    }

    void from(String id) {
        this.fromId = id
    }

    void to(String... ids) {
        this.toIds.addAll(ids)

        // Immediately apply the fork relationships
        if (fromId && !toIds.isEmpty()) {
            toIds.each { tid ->
                graph.tasks[tid]?.dependsOn(fromId)
                graph.tasks[fromId]?.successors?.add(tid)
            }
        }
    }
}