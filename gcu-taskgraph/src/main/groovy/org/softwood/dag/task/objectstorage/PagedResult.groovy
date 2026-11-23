package org.softwood.dag.task.objectstorage

/**
 * Paged result abstraction
 */
interface PagedResult<T> extends Iterable<T> {
    List<T> getResults()
    String getNextPageToken()
    boolean hasMore()
}
