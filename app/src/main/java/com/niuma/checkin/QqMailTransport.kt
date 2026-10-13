package com.niuma.checkin

import android.content.Context
import android.content.pm.ApplicationInfo
import com.niuma.core.Alert
import com.niuma.core.Engine
import org.json.JSONObject
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.BodyPart
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource
import com.niuma.core.requiresScreenshotAttachment
import com.niuma.core.screenshotEvidenceFileName

class QqMailTransport(private val context: Context) : MailTransport {
    companion object { private const val SENDER = "67751@qq.com" }
    private data class ScreenshotAttachment(val fileName: String, val bytes: ByteArray)
    private val prefs = context.getSharedPreferences("mail-provider", Context.MODE_PRIVATE)
    override val configured get() = prefs.getBoolean("qq-configured", false)

    fun ensureBundledConfiguration(engine: Engine) {
        val settings = engine.store.transaction { it.settings.copy() }
        val vault = CredentialVault(context)
        val existing = settings.credentialRef.takeIf(String::isNotBlank)?.let { ref ->
            runCatching { JSONObject(String(vault.read(ref), Charsets.UTF_8)) }.getOrNull()
        }
        if (existing?.optString("account") == SENDER &&
            existing.optString("password") == BuildConfig.QQ_MAIL_AUTH_CODE) {
            if (!configured) check(prefs.edit().putBoolean("qq-configured", true).commit())
            return
        }
        val recipient = settings.recipient.ifBlank { "hui18@me.com" }
        val bytes = JSONObject().put("account", SENDER).put("recipient", recipient)
            .put("password", BuildConfig.QQ_MAIL_AUTH_CODE).toString().toByteArray(Charsets.UTF_8)
        try {
            val ref = vault.put(bytes)
            try {
                engine.configureMail(recipient, ref)
                check(prefs.edit().putBoolean("qq-configured", true).commit())
            } catch (error: Exception) {
                vault.remove(ref)
                throw error
            }
            if (settings.credentialRef.isNotBlank()) vault.remove(settings.credentialRef)
        } finally { bytes.fill(0) }
    }

    // Development provisioning is private to the app UID; plaintext is removed before use.
    fun importPrivateConfiguration(app: CheckinApp) {
        if (!prefs.getBoolean("recipient-v2", false)) {
            val settings = app.store.transaction { it.settings.copy() }
            if (settings.recipient.isBlank() || settings.recipient == SENDER)
                app.engine.configureMail("hui18@me.com", settings.credentialRef)
            check(prefs.edit().putBoolean("recipient-v2", true).commit())
        }
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val file = java.io.File(context.filesDir, "mail-import.json")
        if (!file.exists()) return
        val bytes = file.readBytes()
        check(file.delete()) { "安全导入文件清理失败" }
        try {
            val config = JSONObject(String(bytes, Charsets.UTF_8))
            require(config.getString("account") == SENDER) { "发件账号不匹配" }
            val recipient = config.getString("recipient")
            require(config.getString("password").isNotBlank()) { "授权码不能为空" }
            val old = app.store.transaction { it.settings.credentialRef }
            val vault = CredentialVault(context)
            val ref = vault.put(bytes)
            try { app.engine.configureMail(recipient, ref) }
            catch (e: Exception) { vault.remove(ref); throw e }
            check(prefs.edit().putBoolean("qq-configured", true).commit())
            if (old.isNotBlank()) vault.remove(old)
        } finally { bytes.fill(0) }
    }

    override fun send(alert: Alert, recipient: String, credentialRef: String): Boolean {
        val bytes = CredentialVault(context).read(credentialRef)
        try {
            val config = JSONObject(String(bytes, Charsets.UTF_8))
            val account = SENDER
            require(config.getString("account") == SENDER) { "发件账号不匹配" }
            val props = Properties().apply {
                setProperty("mail.smtp.auth", "true")
                setProperty("mail.smtp.ssl.enable", "true")
                setProperty("mail.smtp.ssl.checkserveridentity", "true")
                setProperty("mail.smtp.connectiontimeout", "10000")
                setProperty("mail.smtp.timeout", "10000")
                setProperty("mail.smtp.writetimeout", "10000")
                setProperty("mail.smtp.localhost", "localhost")
            }
            val session = Session.getInstance(props)
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(account, "牛马签到", "UTF-8"))
                setRecipient(Message.RecipientType.TO, InternetAddress(recipient, true))
                setSubject(alert.subject, "UTF-8")
                val body = alert.body + "\n\n通知编号：" + alert.id
                val attachment = readScreenshotAttachment(alert)
                if (attachment == null) {
                    setText(body, "UTF-8")
                } else {
                    setContent(MimeMultipart().apply {
                        addBodyPart(MimeBodyPart().apply { setText(body, "UTF-8") })
                        addBodyPart(MimeBodyPart().apply {
                            dataHandler = DataHandler(ByteArrayDataSource(attachment.bytes, "image/png"))
                            fileName = attachment.fileName
                            disposition = BodyPart.ATTACHMENT
                        })
                    })
                }
                sentDate = Date()
                saveChanges()
            }
            val transport = session.getTransport("smtp")
            try {
                transport.connect("smtp.qq.com", 465, account, config.getString("password"))
                transport.sendMessage(message, message.allRecipients)
                return true
            } finally { try { transport.close() } catch (_: Exception) { } }
        } finally { bytes.fill(0) }
    }

    private fun readScreenshotAttachment(alert: Alert): ScreenshotAttachment? {
        if (!alert.requiresScreenshotAttachment()) return null
        val runId = alert.runId ?: return null
        val file = java.io.File(context.filesDir, "screenshot-evidence/${screenshotEvidenceFileName(runId)}")
        return runCatching {
            if (!file.isFile || !file.canRead()) return@runCatching null
            ScreenshotAttachment(file.name, file.readBytes())
        }.getOrNull()
    }
}
