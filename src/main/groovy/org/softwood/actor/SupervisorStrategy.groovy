/*
 * Copyright (c) 2025 the authors of this project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// SupervisorStrategy.groovy
// Part of the integrated actor system bundle

package org.softwood.actor

import groovy.transform.CompileStatic

@CompileStatic
enum SupervisorStrategy {
    /** Restart the failing actor (clear state, restart loop). */
    RESTART,

    /** Stop the failing actor permanently. */
    STOP,

    /** Resume actor (ignore failure, continue). */
    RESUME,

    /** Escalate error upward (parent handles it). */
    ESCALATE
}