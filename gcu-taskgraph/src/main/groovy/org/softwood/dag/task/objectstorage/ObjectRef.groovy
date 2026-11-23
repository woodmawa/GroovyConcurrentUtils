package org.softwood.dag.task.objectstorage

import groovy.transform.Immutable

/**
 * Strongly-typed object reference (container + key)
 */
@Immutable
class ObjectRef {
    String container
    String key

    @Override
    String toString() {
        "${container}/${key}"
    }

    static ObjectRef of(String container, String key) {
        new ObjectRef(container: container, key: key)
    }
}