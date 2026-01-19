package org.softwood.dag.task.objectstorage

import groovy.transform.Immutable

@Immutable
class DeleteOptions {
    static final DeleteOptions DEFAULT = new DeleteOptions()

    /** Conditional delete (match version token) */
    String ifMatchVersionToken
}