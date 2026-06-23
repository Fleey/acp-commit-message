package com.leeyf.acpcommit.notification

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object AcpCommitNotifications {
    fun info(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ACP Commit Message")
            .createNotification("ACP commit message", message, NotificationType.INFORMATION)
            .notify(project)
    }

    fun error(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ACP Commit Message")
            .createNotification("ACP commit message failed", message, NotificationType.ERROR)
            .notify(project)
    }
}
