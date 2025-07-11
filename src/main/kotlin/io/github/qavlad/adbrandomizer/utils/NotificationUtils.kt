// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/utils/NotificationUtils.kt
package io.github.qavlad.adbrandomizer.utils

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.config.PluginConfig

object NotificationUtils {
    private const val NOTIFICATION_GROUP_ID = PluginConfig.UI.NOTIFICATION_GROUP_ID

    /**
     * Показывает информационное уведомление
     */
    fun showInfo(project: Project, message: String) {
        showNotification(project, message, NotificationType.INFORMATION)
    }

    /**
     * Показывает уведомление об успехе
     */
    fun showSuccess(project: Project, message: String) {
        showNotification(project, message, NotificationType.INFORMATION)
    }

    /**
     * Показывает уведомление об ошибке
     */
    fun showError(project: Project, message: String) {
        showNotification(project, message, NotificationType.ERROR)
    }

    /**
     * Базовый метод для показа уведомления
     */
    private fun showNotification(project: Project, message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(message, type)
                .notify(project)
        }
    }
}