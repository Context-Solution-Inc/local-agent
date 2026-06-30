package com.contextsolutions.localagent.notification

/**
 * iOS [NotificationPresenter] (PR #41) — logs for now. A `UNUserNotificationCenter`
 * (local-notification) presenter is a follow-up; jobs/clock are deferred on iOS so
 * nothing relies on real delivery yet.
 */
class IosNotificationPresenter : NotificationPresenter {
    override fun present(notification: AppNotification) {
        println("[notify] ${notification.kind} ${notification.title}: ${notification.body}")
    }

    override fun dismiss(id: String) {}
}
