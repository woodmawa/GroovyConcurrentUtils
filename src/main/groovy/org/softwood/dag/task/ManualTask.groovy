package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.dag.task.manualtask.*
import org.softwood.promise.Promise
import org.softwood.promise.Promises

import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeoutException
import java.util.Timer
import java.util.TimerTask as JTimerTask

/**
 * ManualTask - Human Interaction Task
 *
 * Pauses workflow execution for human intervention. The task waits until
 * it is explicitly completed via the complete() method with one of three outcomes:
 * SUCCESS, FAILURE, or SKIP.
 *
 * <p>This task type is critical for workflows that require human decision-making,
 * data entry, approval, or review.</p>
 *
 * <h3>Use Cases:</h3>
 * <ul>
 *   <li>Approval workflows (PR review, expense approval)</li>
 *   <li>Data entry forms (user registration, manual data correction)</li>
 *   <li>Quality control checkpoints</li>
 *   <li>Document review/sign-off</li>
 *   <li>Exception handling (manual retry decisions)</li>
 * </ul>
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Three completion outcomes: SUCCESS, FAILURE, SKIP</li>
 *   <li>Form field support for structured data collection</li>
 *   <li>Attachment upload capability</li>
 *   <li>Assignee/role-based assignment</li>
 *   <li>Priority levels (LOW, NORMAL, HIGH, URGENT)</li>
 *   <li>Due dates with timeout handling</li>
 *   <li>Auto-action on timeout</li>
 *   <li><b>Notifications</b> via email, Slack, webhook</li>
 *   <li><b>Escalation rules</b> for automated reassignment</li>
 * </ul>
 *
 * <h3>DSL Example:</h3>
 * <pre>
 * task("pr-review", TaskType.MANUAL) {
 *     title "Review Pull Request #1234"
 *     description "Review code changes and approve or reject"
 *     assignee "john.doe@company.com"
 *     priority Priority.HIGH
 *     dueDate LocalDateTime.now().plusDays(2)
 *
 *     // Form fields
 *     form {
 *         field "approved", type: FieldType.BOOLEAN, required: true
 *         field "comments", type: FieldType.TEXTAREA, required: false
 *         field "quality_score", type: FieldType.NUMBER, min: 1, max: 10
 *     }
 *
 *     // Notifications
 *     notify {
 *         email "john.doe@company.com"
 *         slack "#code-reviews"
 *         onAssignment true
 *         onCompletion false
 *     }
 *
 *     // Escalation
 *     escalate {
 *         after Duration.ofHours(24), to: "tech-lead@company.com"
 *         after Duration.ofHours(48), to: "director@company.com"
 *     }
 *
 *     // Timeout with auto-action
 *     timeout 72.hours, autoAction: CompletionOutcome.SKIP
 *
 *     onSuccess { ctx ->
 *         println "Approved by ${ctx.completedBy}"
 *     }
 *
 *     onFailure { ctx ->
 *         println "Rejected: ${ctx.formData.comments}"
 *     }
 * }
 * </pre>
 *
 * <h3>Programmatic Completion:</h3>
 * <pre>
 * manualTask.complete(
 *     outcome: CompletionOutcome.SUCCESS,
 *     formData: [approved: true, quality_score: 9],
 *     attachments: [attachment1, attachment2],
 *     completedBy: "john.doe@company.com"
 * )
 * </pre>
 */
@Slf4j
class ManualTask extends TaskBase<Map> {

    // =========================================================================
    // Task Metadata
    // =========================================================================
    
    /** Human-readable title */
    String title
    
    /** Detailed description of what needs to be done */
    String description
    
    /** Email or username of assigned person */
    String assignee
    
    /** Role that can complete this task (alternative to assignee) */
    String role
    
    /** Task priority */
    Priority priority = Priority.NORMAL
    
    /** When the task is due */
    LocalDateTime dueDate
    
    // =========================================================================
    // Form Configuration
    // =========================================================================

    /** Form fields for data collection */
    final Map<String, FormField> formFields = [:]

    // =========================================================================
    // Notification Configuration
    // =========================================================================

    /** Notification configuration */
    NotificationConfig notificationConfig = new NotificationConfig()

    // =========================================================================
    // Escalation Configuration
    // =========================================================================

    /** Escalation policy */
    EscalationPolicy escalationPolicy = new EscalationPolicy()
    
    // =========================================================================
    // Timeout Configuration
    // =========================================================================
    
    /** Timeout duration */
    Duration timeout
    
    /** Action to take when timeout occurs */
    CompletionOutcome autoAction = CompletionOutcome.SKIP
    
    /** Reason for automatic action (used when timeout triggers) */
    String autoActionReason = "Task timed out"
    
    // =========================================================================
    // Completion State
    // =========================================================================
    
    /** Submitted form data */
    Map<String, Object> formData = [:]
    
    /** Uploaded attachments */
    List<Attachment> attachments = []
    
    /** How the task was completed */
    CompletionOutcome outcome
    
    /** Who completed the task */
    String completedBy
    
    /** When the task was completed */
    LocalDateTime completedAt
    
    /** Comments provided at completion */
    String comments
    
    // =========================================================================
    // Completion Handlers
    // =========================================================================
    
    /** Called when task completes with SUCCESS outcome */
    Closure onSuccessHandler
    
    /** Called when task completes with FAILURE outcome */
    Closure onFailureHandler
    
    /** Called when task completes with SKIP outcome */
    Closure onSkipHandler
    
    // =========================================================================
    // Internal State
    // =========================================================================
    
    /** Promise to resolve when task is completed */
    private Promise<Map> completionResultPromise
    
    /** Timeout timer task */
    private Timer timeoutTimer

    /** Escalation timer task */
    private Timer escalationTimer

    /** Flag to ensure complete() is called only once */
    private volatile boolean completed = false

    /** Task start time (for escalation tracking) */
    private LocalDateTime startTime

    ManualTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
        // Initialize promise immediately so complete() can be called anytime
        this.completionResultPromise = ctx.promiseFactory.createPromise()
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    /**
     * DSL method to set title.
     */
    void title(String value) {
        this.title = value
    }
    
    /**
     * DSL method to set description.
     */
    void description(String value) {
        this.description = value
    }
    
    /**
     * DSL method to set assignee.
     */
    void assignee(String value) {
        this.assignee = value
    }
    
    /**
     * DSL method to set role.
     */
    void role(String value) {
        this.role = value
    }
    
    /**
     * DSL method to set priority.
     */
    void priority(Priority value) {
        this.priority = value
    }
    
    /**
     * DSL method to set due date.
     */
    void dueDate(LocalDateTime value) {
        this.dueDate = value
    }
    
    /**
     * Configure form fields for data collection.
     *
     * @param config closure with form field definitions
     */
    void form(@DelegatesTo(FormBuilder) Closure config) {
        def builder = new FormBuilder(this)
        config.delegate = builder
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }

    /**
     * Configure notifications for this task.
     *
     * @param config closure with notification configuration
     */
    void notify(@DelegatesTo(NotificationBuilder) Closure config) {
        def builder = new NotificationBuilder(this)
        config.delegate = builder
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }

    /**
     * Configure escalation rules for this task.
     *
     * @param config closure with escalation rules
     */
    void escalate(@DelegatesTo(EscalationBuilder) Closure config) {
        def builder = new EscalationBuilder(this)
        config.delegate = builder
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }

    /**
     * Set timeout with auto-action.
     * 
     * @param duration timeout duration
     * @param options map with 'autoAction' key
     */
    void timeout(Duration duration, Map options = [:]) {
        this.timeout = duration
        if (options.autoAction) {
            this.autoAction = options.autoAction as CompletionOutcome
        }
        if (options.reason) {
            this.autoActionReason = options.reason as String
        }
        
        log.debug("ManualTask($id): timeout set to $duration with autoAction=$autoAction")
    }
    
    /**
     * Set success completion handler.
     */
    void onSuccess(Closure handler) {
        this.onSuccessHandler = handler
    }
    
    /**
     * Set failure completion handler.
     */
    void onFailure(Closure handler) {
        this.onFailureHandler = handler
    }
    
    /**
     * Set skip completion handler.
     */
    void onSkip(Closure handler) {
        this.onSkipHandler = handler
    }

    // =========================================================================
    // Task Execution
    // =========================================================================

    @Override
    protected Promise<Map> runTask(TaskContext ctx, Object prevValue) {

        log.debug("ManualTask($id): starting - waiting for external completion")

        // Record start time
        this.startTime = LocalDateTime.now()

        // Set up timeout if configured
        if (timeout) {
            scheduleTimeout()
        }

        // Set up escalation if configured
        if (escalationPolicy.hasRules()) {
            escalationPolicy.activate()
            scheduleEscalationChecks()
        }

        // Send assignment notification
        if (notificationConfig.hasChannels() && notificationConfig.notifyOnAssignment) {
            sendAssignmentNotification()
        }

        // Log task information
        log.info("ManualTask($id): '$title' assigned to ${assignee ?: role ?: 'unassigned'} " +
                 "(priority: $priority, due: ${dueDate ?: 'none'})")

        // Return the promise that was created in constructor
        // It will be resolved when complete() is called
        return completionResultPromise
    }

    // =========================================================================
    // Completion API
    // =========================================================================

    /**
     * Complete the manual task.
     *
     * This method should be called by external systems (UI, API, etc.) when
     * a human has completed their work on this task.
     *
     * @param options map with completion details:
     *   - outcome: CompletionOutcome (SUCCESS, FAILURE, or SKIP)
     *   - formData: Map of form field values (optional)
     *   - attachments: List of Attachment objects (optional)
     *   - completedBy: String identifier of who completed it (optional)
     *   - comments: String with additional comments (optional)
     */
    synchronized void complete(Map options) {
        if (completed) {
            log.warn("ManualTask($id): already completed, ignoring duplicate completion")
            return
        }
        
        log.debug("ManualTask($id): completing with options: ${options.keySet()}")

        // Cancel timeout and escalation if active
        cancelTimeout()
        cancelEscalation()

        // Extract and validate options
        this.outcome = options.outcome as CompletionOutcome
        if (!this.outcome) {
            throw new IllegalArgumentException("ManualTask($id): 'outcome' is required")
        }
        
        this.formData = options.formData ?: [:]
        this.attachments = options.attachments ?: []
        this.completedBy = options.completedBy
        this.comments = options.comments
        this.completedAt = LocalDateTime.now()
        
        // Validate form data
        validateFormData()
        
        // Build completion context
        def context = new ManualTaskContext(
            formData: this.formData,
            attachments: this.attachments,
            outcome: this.outcome,
            completedBy: this.completedBy,
            completedAt: this.completedAt,
            comments: this.comments
        )
        
        // Call appropriate handler
        try {
            switch (outcome) {
                case CompletionOutcome.SUCCESS:
                    log.info("ManualTask($id): completed successfully by ${completedBy ?: 'unknown'}")
                    onSuccessHandler?.call(context)
                    break
                    
                case CompletionOutcome.FAILURE:
                    log.info("ManualTask($id): completed with failure by ${completedBy ?: 'unknown'}")
                    onFailureHandler?.call(context)
                    break
                    
                case CompletionOutcome.SKIP:
                    log.info("ManualTask($id): skipped by ${completedBy ?: 'system'}")
                    onSkipHandler?.call(context)
                    break
            }
        } catch (Exception e) {
            log.error("ManualTask($id): error in completion handler", e)
        }
        
        // Build result
        def result = [
            outcome: outcome.name(),
            formData: formData,
            attachments: attachments.collect { it.metadata },
            completedBy: completedBy,
            completedAt: completedAt,
            comments: comments
        ]
        
        // Send completion notification
        if (notificationConfig.hasChannels() && notificationConfig.notifyOnCompletion) {
            sendCompletionNotification()
        }

        // Mark as completed and resolve promise
        completed = true

        if (outcome == CompletionOutcome.SUCCESS) {
            completionResultPromise.accept(result)
        } else if (outcome == CompletionOutcome.FAILURE) {
            def exception = new ManualTaskFailedException("ManualTask completed with FAILURE outcome", result)
            log.debug("ManualTask($id): rejecting promise with exception: ${exception.class.name}")
            completionResultPromise.reject(exception)
        } else {
            // SKIP - treat as success with skip indicator
            completionResultPromise.accept(result)
        }

        log.debug("ManualTask($id): completion processing finished")
    }

    // =========================================================================
    // Timeout Handling
    // =========================================================================

    private void scheduleTimeout() {
        log.debug("ManualTask($id): scheduling timeout for ${timeout.toMillis()}ms")
        
        timeoutTimer = new Timer("ManualTask-${id}-Timeout", true)
        timeoutTimer.schedule(new JTimerTask() {
            @Override
            void run() {
                handleTimeout()
            }
        }, timeout.toMillis())
    }
    
    private void cancelTimeout() {
        if (timeoutTimer) {
            log.debug("ManualTask($id): cancelling timeout")
            timeoutTimer.cancel()
            timeoutTimer = null
        }
    }
    
    private void handleTimeout() {
        log.warn("ManualTask($id): timeout reached, applying autoAction=$autoAction")
        
        // Complete with auto-action
        complete(
            outcome: autoAction,
            completedBy: "SYSTEM_TIMEOUT",
            comments: autoActionReason
        )
    }

    // =========================================================================
    // Escalation Handling
    // =========================================================================

    private void scheduleEscalationChecks() {
        if (!escalationPolicy.hasRules()) {
            return
        }

        log.debug("ManualTask($id): scheduling escalation checks")

        escalationTimer = new Timer("ManualTask-${id}-Escalation", true)

        // Check for escalations every 100ms for responsive escalation
        escalationTimer.scheduleAtFixedRate(new JTimerTask() {
            @Override
            void run() {
                checkAndTriggerEscalation()
            }
        }, 100L, 100L)  // Check every 100ms
    }

    private void cancelEscalation() {
        if (escalationTimer) {
            log.debug("ManualTask($id): cancelling escalation")
            escalationTimer.cancel()
            escalationTimer = null
        }

        if (escalationPolicy) {
            escalationPolicy.deactivate()
        }
    }

    private void checkAndTriggerEscalation() {
        def rule = escalationPolicy.checkEscalation()

        if (rule) {
            log.info("ManualTask($id): escalating to level ${rule.level} - assigning to ${rule.escalateTo}")

            // Update assignee
            this.assignee = rule.escalateTo

            // Send escalation notification
            if (notificationConfig.hasChannels() && notificationConfig.notifyOnEscalation) {
                sendEscalationNotification(rule)
            }

            // Call custom escalation action if provided
            if (rule.onEscalate) {
                try {
                    rule.onEscalate.call(this, rule)
                } catch (Exception e) {
                    log.error("ManualTask($id): error in onEscalate callback", e)
                }
            }
        }
    }

    // =========================================================================
    // Notification Handling
    // =========================================================================

    private void sendAssignmentNotification() {
        try {
            def message = NotificationMessage.create(
                recipient: assignee ?: role,
                subject: "Task Assigned: ${title}",
                body: buildAssignmentMessage(),
                taskId: id,
                taskTitle: title,
                priority: priority?.toString(),
                dueDate: dueDate,
                type: NotificationType.ASSIGNMENT
            )

            notificationConfig.sendNotification(message)
            log.debug("ManualTask($id): sent assignment notification to ${assignee ?: role}")
        } catch (Exception e) {
            log.error("ManualTask($id): failed to send assignment notification", e)
        }
    }

    private void sendEscalationNotification(EscalationRule rule) {
        try {
            def message = NotificationMessage.create(
                recipient: rule.escalateTo,
                subject: "Task Escalated (Level ${rule.level}): ${title}",
                body: buildEscalationMessage(rule),
                taskId: id,
                taskTitle: title,
                priority: priority?.toString(),
                dueDate: dueDate,
                type: NotificationType.ESCALATION,
                metadata: [escalationLevel: rule.level, previousAssignee: assignee]
            )

            notificationConfig.sendNotification(message)
            log.debug("ManualTask($id): sent escalation notification to ${rule.escalateTo}")
        } catch (Exception e) {
            log.error("ManualTask($id): failed to send escalation notification", e)
        }
    }

    private void sendCompletionNotification() {
        try {
            def message = NotificationMessage.create(
                recipient: assignee ?: role,
                subject: "Task Completed: ${title}",
                body: buildCompletionMessage(),
                taskId: id,
                taskTitle: title,
                priority: priority?.toString(),
                dueDate: dueDate,
                type: NotificationType.COMPLETION,
                metadata: [outcome: outcome?.toString(), completedBy: completedBy]
            )

            notificationConfig.sendNotification(message)
            log.debug("ManualTask($id): sent completion notification")
        } catch (Exception e) {
            log.error("ManualTask($id): failed to send completion notification", e)
        }
    }

    private String buildAssignmentMessage() {
        def msg = "A new task has been assigned to you:\n\n"
        msg += "Task: ${title}\n"
        if (description) msg += "Description: ${description}\n"
        msg += "Priority: ${priority}\n"
        if (dueDate) msg += "Due: ${dueDate}\n"
        return msg
    }

    private String buildEscalationMessage(EscalationRule rule) {
        def msg = "A task has been escalated to you (Level ${rule.level}):\n\n"
        msg += "Task: ${title}\n"
        if (description) msg += "Description: ${description}\n"
        msg += "Priority: ${priority}\n"
        if (dueDate) msg += "Due: ${dueDate}\n"
        msg += "\nThis task was previously assigned but not completed within the expected timeframe."
        return msg
    }

    private String buildCompletionMessage() {
        def msg = "Task has been completed:\n\n"
        msg += "Task: ${title}\n"
        msg += "Outcome: ${outcome}\n"
        msg += "Completed by: ${completedBy}\n"
        msg += "Completed at: ${completedAt}\n"
        if (comments) msg += "Comments: ${comments}\n"
        return msg
    }

    // =========================================================================
    // Validation
    // =========================================================================

    private void validateFormData() {
        formFields.each { fieldName, field ->
            def value = formData[fieldName]
            
            if (!field.validate(value)) {
                throw new IllegalArgumentException(
                    "ManualTask($id): validation failed for field '$fieldName' with value '$value'"
                )
            }
        }
    }

    // =========================================================================
    // Helper Classes
    // =========================================================================

    /**
     * Builder for form field configuration.
     * 
     * Note: The Map brackets are required by Groovy syntax when using named parameters.
     * This is standard Groovy, not a limitation of our DSL.
     */
    static class FormBuilder {
        private final ManualTask task
        
        FormBuilder(ManualTask task) {
            this.task = task
        }
        
        /**
         * Add a form field.
         * 
         * Usage:
         *   field "fieldName", [type: FormField.FieldType.BOOLEAN, required: true]
         * 
         * @param name field name
         * @param options field configuration (type, required, min, max, etc.)
         */
        void field(String name, Map options = [:]) {
            def field = new FormField(
                name: name,
                type: options.type as FormField.FieldType ?: FormField.FieldType.TEXT,
                label: options.label ?: name,
                helpText: options.helpText,
                required: options.required ?: false,
                defaultValue: options.defaultValue
            )
            
            // Add constraints
            if (options.min != null) field.constraints.min = options.min
            if (options.max != null) field.constraints.max = options.max
            if (options.pattern) field.constraints.pattern = options.pattern
            if (options.options) field.constraints.options = options.options
            
            task.formFields[name] = field
            
            task.log.debug("ManualTask(${task.id}): added form field '$name' (type=${field.type}, required=${field.required})")
        }
    }
    
    /**
     * Builder for notification configuration.
     */
    static class NotificationBuilder {
        private final ManualTask task

        NotificationBuilder(ManualTask task) {
            this.task = task
        }

        /**
         * Add email notification channel.
         */
        void email(String emailAddress, Map config = [:]) {
            def channel = new EmailNotificationChannel(config + [fromAddress: emailAddress])
            task.notificationConfig.addChannel(channel)
            task.log.debug("ManualTask(${task.id}): added email notification to ${emailAddress}")
        }

        /**
         * Add Slack notification channel.
         */
        void slack(String channel, Map config = [:]) {
            def slackChannel = new SlackNotificationChannel(config + [defaultChannel: channel])
            task.notificationConfig.addChannel(slackChannel)
            task.log.debug("ManualTask(${task.id}): added Slack notification to ${channel}")
        }

        /**
         * Add webhook notification channel.
         */
        void webhook(String url, Map config = [:]) {
            def webhookChannel = new WebhookNotificationChannel(config + [webhookUrl: url])
            task.notificationConfig.addChannel(webhookChannel)
            task.log.debug("ManualTask(${task.id}): added webhook notification to ${url}")
        }

        /**
         * Configure whether to notify on assignment.
         */
        void onAssignment(boolean value) {
            task.notificationConfig.notifyOnAssignment = value
        }

        /**
         * Configure whether to notify on escalation.
         */
        void onEscalation(boolean value) {
            task.notificationConfig.notifyOnEscalation = value
        }

        /**
         * Configure whether to notify on completion.
         */
        void onCompletion(boolean value) {
            task.notificationConfig.notifyOnCompletion = value
        }
    }

    /**
     * Builder for escalation configuration.
     */
    static class EscalationBuilder {
        private final ManualTask task

        EscalationBuilder(ManualTask task) {
            this.task = task
        }

        /**
         * Add an escalation rule.
         *
         * Usage:
         *   after Duration.ofHours(24), to: "manager@company.com"
         */
        void after(Duration duration, Map params) {
            task.escalationPolicy.after(duration, params)
            task.log.debug("ManualTask(${task.id}): added escalation after ${duration} to ${params.to}")
        }
    }

    /**
     * Exception thrown when ManualTask completes with FAILURE outcome
     */
    static class ManualTaskFailedException extends Exception {
        final Map completionData

        ManualTaskFailedException(String message, Map data) {
            super(message)
            this.completionData = data
        }
    }
}
