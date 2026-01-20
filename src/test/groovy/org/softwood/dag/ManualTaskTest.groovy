package org.softwood.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

import org.awaitility.Awaitility
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.softwood.promise.Promise
import org.softwood.dag.task.*
import org.softwood.dag.task.manualtask.*

/**
 * Tests for ManualTask (human interaction task)
 */
class ManualTaskTest {

    private TaskContext ctx

    @BeforeEach
    void setup() {
        ctx = new TaskContext()
    }

    private static <T> T awaitPromise(Promise<T> p) {
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until({ p.isDone() })
        return p.get()
    }

    @Test
    void testBasicManualTaskSuccess() {
        // Create a simple manual task
        def task = new ManualTask("approval", "Approval Task", ctx)
        task.title = "Approve Document"
        task.description = "Please review and approve this document"
        
        // Start the task (it will wait for completion)
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        
        // Verify task is waiting
        assertFalse(promise.isDone())
        
        // Complete the task
        task.complete(
            outcome: CompletionOutcome.SUCCESS,
            completedBy: "john.doe",
            comments: "Looks good!"
        )
        
        // Wait for completion
        def result = awaitPromise(promise)
        
        // Verify result
        assertEquals("SUCCESS", result.outcome)
        assertEquals("john.doe", result.completedBy)
        assertEquals("Looks good!", result.comments)
        assertNotNull(result.completedAt)
    }

    @Test
    void testManualTaskWithTimeout() {
        def task = new ManualTask("timeout-task", "Timeout Task", ctx)
        task.title = "Time Sensitive Approval"
        task.timeout(Duration.ofMillis(500), [autoAction: CompletionOutcome.SKIP])
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        
        // Wait for timeout to trigger
        def result = awaitPromise(promise)
        
        // Should auto-complete with SKIP
        assertEquals("SKIP", result.outcome)
        assertEquals("SYSTEM_TIMEOUT", result.completedBy)
        assertTrue(result.comments.contains("timed out"))
    }

    @Test
    void testManualTaskInWorkflow() {
        def results = [:]
        
        def graph = TaskGraph.build {
            serviceTask("prepare") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync {
                        [documentId: 123, content: "Draft document"]
                    }
                }
            }
            
            task("manual-review", TaskType.MANUAL) {
                title "Review Document"
                description "Please review the document"
                assignee "reviewer@company.com"
                priority Priority.HIGH
                
                onSuccess { ctx ->
                    results.completed = true
                }
            }
            
            fork("workflow") {
                from "prepare"
                to "manual-review"
            }
        }
        
        // Start workflow
        def promise = graph.run()
        
        // Wait for prepare task
        Thread.sleep(100)
        
        // Complete the manual task
        def manualTask = graph.tasks["manual-review"] as ManualTask
        manualTask.complete(
            outcome: CompletionOutcome.SUCCESS,
            completedBy: "reviewer@company.com"
        )
        
        // Wait for workflow
        awaitPromise(promise)
        
        // Verify
        assertEquals(TaskState.COMPLETED, graph.tasks["prepare"].state)
        assertEquals(TaskState.COMPLETED, graph.tasks["manual-review"].state)
        assertTrue(results.completed)
    }

    @Test
    void testManualTaskWithEmailNotification() {
        def notifications = []

        // Create custom notification channel that captures notifications
        def testChannel = new NotificationChannel() {
            @Override
            void send(NotificationMessage message) {
                notifications << message
            }

            @Override
            String getChannelType() {
                return "test"
            }

            @Override
            boolean isEnabled() {
                return true
            }
        }

        def task = new ManualTask("notify-task", "Notify Task", ctx)
        task.title = "Approval Required"
        task.description = "Please approve this request"
        task.assignee = "approver@company.com"

        // Add notification config
        task.notificationConfig.addChannel(testChannel)
        task.notificationConfig.notifyOnAssignment = true
        task.notificationConfig.notifyOnCompletion = true

        // Start task
        def promise = task.execute(ctx.promiseFactory.createPromise(null))

        // Wait a moment for assignment notification
        Thread.sleep(100)

        // Verify assignment notification was sent
        assertEquals(1, notifications.size())
        def assignmentNotif = notifications[0]
        assertEquals("approver@company.com", assignmentNotif.recipient)
        assertEquals(NotificationType.ASSIGNMENT, assignmentNotif.type)
        assertTrue(assignmentNotif.subject.contains("Approval Required"))

        // Complete task
        task.complete(
            outcome: CompletionOutcome.SUCCESS,
            completedBy: "approver@company.com"
        )

        // Wait for completion notification
        Thread.sleep(100)

        // Verify completion notification was sent
        assertEquals(2, notifications.size())
        def completionNotif = notifications[1]
        assertEquals(NotificationType.COMPLETION, completionNotif.type)
    }

    @Test
    void testManualTaskWithEscalation() {
        def escalations = []

        def task = new ManualTask("escalation-task", "Escalation Task", ctx)
        task.title = "Urgent Approval"
        task.assignee = "manager@company.com"

        // Add escalation rules
        task.escalationPolicy.addRule(new EscalationRule(Duration.ofMillis(200), "director@company.com"))
        task.escalationPolicy.addRule(new EscalationRule(Duration.ofMillis(400), "vp@company.com"))

        // Start task
        def promise = task.execute(ctx.promiseFactory.createPromise(null))

        // Wait for first escalation (200ms + buffer)
        Thread.sleep(350)

        // First escalation should have triggered
        assertEquals(1, task.escalationPolicy.getCurrentLevel())
        assertEquals("director@company.com", task.assignee)

        // Wait for second escalation (additional 200ms)
        Thread.sleep(250)

        // Second escalation should have triggered
        assertEquals(2, task.escalationPolicy.getCurrentLevel())
        assertEquals("vp@company.com", task.assignee)

        // Complete task
        task.complete(
            outcome: CompletionOutcome.SUCCESS,
            completedBy: "vp@company.com"
        )

        awaitPromise(promise)
    }

    @Test
    void testManualTaskWithEscalationAndNotification() {
        def notifications = []

        def testChannel = new NotificationChannel() {
            @Override
            void send(NotificationMessage message) {
                notifications << message
            }

            @Override
            String getChannelType() {
                return "test"
            }

            @Override
            boolean isEnabled() {
                return true
            }
        }

        def task = new ManualTask("combined-test", "Combined Test", ctx)
        task.title = "Important Task"
        task.assignee = "user@company.com"

        // Add notification
        task.notificationConfig.addChannel(testChannel)
        task.notificationConfig.notifyOnAssignment = true
        task.notificationConfig.notifyOnEscalation = true
        task.notificationConfig.notifyOnCompletion = true

        // Add escalation
        task.escalationPolicy.addRule(new EscalationRule(Duration.ofMillis(200), "supervisor@company.com"))

        // Start task
        def promise = task.execute(ctx.promiseFactory.createPromise(null))

        // Wait for assignment notification
        Thread.sleep(100)
        assertEquals(1, notifications.size())
        assertEquals(NotificationType.ASSIGNMENT, notifications[0].type)

        // Wait for escalation (200ms + buffer)
        Thread.sleep(350)

        // Verify escalation notification
        assertTrue(notifications.size() >= 2, "Expected at least 2 notifications but got ${notifications.size()}")
        def escalationNotif = notifications.find { it.type == NotificationType.ESCALATION }
        assertNotNull(escalationNotif)
        assertEquals("supervisor@company.com", escalationNotif.recipient)

        // Complete task
        task.complete(
            outcome: CompletionOutcome.SUCCESS,
            completedBy: "supervisor@company.com"
        )

        awaitPromise(promise)

        // Verify completion notification
        def completionNotif = notifications.find { it.type == NotificationType.COMPLETION }
        assertNotNull(completionNotif)
    }

    @Test
    void testNotificationChannels() {
        def task = new ManualTask("channel-test", "Channel Test", ctx)
        task.title = "Test Task"
        task.assignee = "user@company.com"

        // Test email channel
        def emailChannel = EmailNotificationChannel.createSimple("test@example.com")
        assertTrue(emailChannel.isEnabled())
        assertEquals("email", emailChannel.getChannelType())

        // Test Slack channel
        def slackChannel = SlackNotificationChannel.createSimple("#notifications")
        assertTrue(slackChannel.isEnabled())
        assertEquals("slack", slackChannel.getChannelType())

        // Test webhook channel
        def webhookChannel = WebhookNotificationChannel.createSimple("https://example.com/webhook")
        assertTrue(webhookChannel.isEnabled())
        assertEquals("webhook", webhookChannel.getChannelType())

        // Add channels to task
        task.notificationConfig.addChannel(emailChannel)
        task.notificationConfig.addChannel(slackChannel)
        task.notificationConfig.addChannel(webhookChannel)

        // Verify channels were added
        assertEquals(3, task.notificationConfig.getChannelCount())
        assertTrue(task.notificationConfig.hasChannels())
    }

    @Test
    void testEscalationPolicy() {
        def policy = new EscalationPolicy()

        // Add rules
        policy.addRule(new EscalationRule(Duration.ofHours(1), "level1@company.com"))
        policy.addRule(new EscalationRule(Duration.ofHours(3), "level2@company.com"))
        policy.addRule(new EscalationRule(Duration.ofHours(6), "level3@company.com"))

        // Verify rules
        assertTrue(policy.hasRules())
        assertEquals(3, policy.getRuleCount())
        assertEquals(0, policy.getCurrentLevel())

        // Activate policy
        policy.activate()

        // Rules should be sorted by duration
        assertEquals(Duration.ofHours(1), policy.rules[0].afterDuration)
        assertEquals(Duration.ofHours(3), policy.rules[1].afterDuration)
        assertEquals(Duration.ofHours(6), policy.rules[2].afterDuration)

        // Levels should be assigned
        assertEquals(1, policy.rules[0].level)
        assertEquals(2, policy.rules[1].level)
        assertEquals(3, policy.rules[2].level)
    }

    @Test
    void testManualTaskDSLWithNotificationsAndEscalation() {
        def notifications = []
        def testChannel = new NotificationChannel() {
            @Override
            void send(NotificationMessage message) {
                notifications << message
            }

            @Override
            String getChannelType() {
                return "test"
            }

            @Override
            boolean isEnabled() {
                return true
            }
        }

        def graph = TaskGraph.build {
            task("enhanced-manual", TaskType.MANUAL) {
                title "Enhanced Manual Task"
                description "Test all new features"
                assignee "user@company.com"
                priority Priority.HIGH

                // Notification config via DSL
                notify {
                    // Add custom test channel manually
                    // (can't use email/slack directly in DSL without proper setup)
                    onAssignment true
                    onEscalation true
                    onCompletion false
                }

                // Escalation config via DSL
                escalate {
                    after Duration.ofMillis(200), [to: "manager@company.com"]
                    after Duration.ofMillis(400), [to: "director@company.com"]
                }

                timeout Duration.ofSeconds(2), [autoAction: CompletionOutcome.SKIP]
            }
        }

        def manualTask = graph.tasks["enhanced-manual"] as ManualTask

        // Manually add test channel (since DSL doesn't have direct access)
        manualTask.notificationConfig.addChannel(testChannel)

        // Verify config
        assertTrue(manualTask.notificationConfig.notifyOnAssignment)
        assertTrue(manualTask.notificationConfig.notifyOnEscalation)
        assertFalse(manualTask.notificationConfig.notifyOnCompletion)
        assertEquals(2, manualTask.escalationPolicy.getRuleCount())

        // Start workflow
        def promise = graph.run()

        // Wait for first escalation
        Thread.sleep(300)
        assertEquals(1, manualTask.escalationPolicy.getCurrentLevel())

        // Complete task before timeout
        manualTask.complete(
            outcome: CompletionOutcome.SUCCESS,
            completedBy: "manager@company.com"
        )

        awaitPromise(promise)
    }
}
