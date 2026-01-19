package org.softwood.dag.task.objectstorage

import groovy.transform.Immutable

/**
 * Container/bucket information
 */
@Immutable
class ContainerInfo {
    String name
    Date createdDate
    String region
    Map<String, String> metadata = [:]
}
