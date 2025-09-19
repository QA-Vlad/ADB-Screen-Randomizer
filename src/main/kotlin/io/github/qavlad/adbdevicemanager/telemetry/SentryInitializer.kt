package io.github.qavlad.adbdevicemanager.telemetry

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PermanentInstallationID
import com.intellij.openapi.extensions.PluginId
import io.github.qavlad.adbdevicemanager.utils.PluginLogger
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.protocol.User

object SentryInitializer {
    
    // DSN для проекта ADB Device Manager на Sentry
    private const val SENTRY_DSN = "https://ce983a1c3d1fef3b7b708067d31161be@o4509922726576128.ingest.us.sentry.io/4509931463245824"
    
    // Флаг для включения/отключения Sentry (может быть изменён через настройки)
    @Volatile
    private var isEnabled = false
    
    fun initialize(enableTelemetry: Boolean = true) {
        isEnabled = enableTelemetry
        
        if (!isEnabled) {
            PluginLogger.info("Sentry telemetry is disabled")
            return
        }
        
        PluginLogger.info("Initializing Sentry telemetry...")
        
        try {
            Sentry.init { options: SentryOptions ->
                options.dsn = SENTRY_DSN
                options.environment = if (isDebugMode()) "development" else "production"
                
                // Production настройки
                options.isDebug = false
                
                // Включаем сессии для отслеживания стабильности
                options.isEnableAutoSessionTracking = true
                
                // Установка release версии плагина
                getPluginVersion()?.let { version ->
                    options.release = "adb-device-manager@$version"
                }
                
                // Настройка sample rate (процент событий, которые будут отправлены)
                options.tracesSampleRate = 0.1 // 10% производительностных трейсов
                options.sampleRate = 1.0 // 100% ошибок
                
                // Отключаем отправку персональных данных
                options.isSendDefaultPii = false
                
                // Добавляем теги
                options.setTag("ide.name", ApplicationInfo.getInstance().versionName)
                options.setTag("ide.version", ApplicationInfo.getInstance().fullVersion)
                options.setTag("ide.build", ApplicationInfo.getInstance().build.asString())
                options.setTag("os.name", System.getProperty("os.name"))
                options.setTag("os.version", System.getProperty("os.version"))
                options.setTag("java.version", System.getProperty("java.version"))
                
                // Устанавливаем таймаут для подключения (по умолчанию 5 секунд)
                options.connectionTimeoutMillis = 10000 // 10 секунд
                options.readTimeoutMillis = 10000 // 10 секунд
                
                // Фильтруем чувствительные данные и нежелательные ошибки
                options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                    // Проверяем, относится ли ошибка к ADB Device Manager
                    val isAdbDeviceManagerError = event.exceptions?.any { exception ->
                        exception.stacktrace?.frames?.any { frame ->
                            frame.module?.startsWith("io.github.qavlad.adbdevicemanager") == true ||
                            frame.filename?.contains("adbdevicemanager") == true
                        } == true
                    } == true
                    
                    // Если ошибка не от ADB Device Manager, не отправляем её
                    if (!isAdbDeviceManagerError) {
                        PluginLogger.debug("Filtering out non-ADB Device Manager error: ${event.message}")
                        return@BeforeSendCallback null
                    }
                    
                    // Фильтруем нормальные ADB ошибки, которые не являются багами
                    val isNormalAdbError = event.exceptions?.any { exception ->
                        val exceptionType = exception.type
                        val message = exception.value ?: ""
                        
                        // ADB ошибки авторизации - это нормально
                        (exceptionType == "AdbCommandRejectedException" && message.contains("device unauthorized")) ||
                        // ADB ошибки подключения - тоже нормально  
                        (exceptionType == "AdbCommandRejectedException" && message.contains("device offline")) ||
                        // Устройство не найдено - нормально
                        (exceptionType == "AdbCommandRejectedException" && message.contains("device not found"))
                    } == true
                    
                    if (isNormalAdbError) {
                        PluginLogger.debug("Filtering out normal ADB error: ${event.message}")
                        return@BeforeSendCallback null
                    }
                    
                    // Удаляем пути к файлам пользователя из стек-трейсов
                    event.exceptions?.forEach { exception ->
                        exception.stacktrace?.frames?.forEach { frame ->
                            frame.filename = frame.filename?.replace(Regex("/Users/[^/]+/"), "/Users/****/")
                            frame.filename = frame.filename?.replace(Regex("\\\\Users\\\\[^\\\\]+\\\\"), "\\Users\\****\\")
                            frame.filename = frame.filename?.replace(Regex("C:\\\\Users\\\\[^\\\\]+\\\\"), "C:\\Users\\****\\")
                        }
                    }
                    event
                }
            }
            
            // Устанавливаем анонимный ID пользователя
            setupUser()
            
            PluginLogger.info("Sentry telemetry initialized successfully")
            
        } catch (e: Exception) {
            PluginLogger.error("Failed to initialize Sentry", e)
        }
    }
    
    private fun setupUser() {
        val user = User().apply {
            // Используем анонимный ID, основанный на установке IDE
            id = PermanentInstallationID.get()
        }
        Sentry.setUser(user)
    }
    
    private fun getPluginVersion(): String? {
        return try {
            val plugin = PluginManagerCore.getPlugin(PluginId.getId("io.github.qavlad.adbdevicemanager"))
            plugin?.version
        } catch (_: Exception) {
            null
        }
    }
    
    private fun isDebugMode(): Boolean {
        return System.getProperty("idea.is.internal")?.toBoolean() == true ||
               System.getProperty("idea.debug.mode")?.toBoolean() == true
    }
    
    fun setEnabled(enabled: Boolean) {
        if (isEnabled != enabled) {
            isEnabled = enabled
            if (!enabled) {
                Sentry.close()
                PluginLogger.info("Sentry telemetry disabled")
            } else {
                initialize(true)
            }
        }
    }
    
    @Suppress("unused") // Может быть полезно в будущем для отладки и отслеживания ошибок
    fun captureException(throwable: Throwable, additionalInfo: Map<String, Any>? = null) {
        if (!isEnabled) return
        
        Sentry.withScope { scope ->
            additionalInfo?.forEach { (key, value) ->
                scope.setContexts(key, value)
            }
            Sentry.captureException(throwable)
        }
    }
    
    @Suppress("unused") // Может быть полезно в будущем для информационных сообщений
    fun captureMessage(message: String, level: io.sentry.SentryLevel = io.sentry.SentryLevel.INFO) {
        if (!isEnabled) return
        
        Sentry.captureMessage(message, level)
    }
}