package com.niuma.checkin

import com.niuma.core.Alert

interface MailTransport {
    val configured: Boolean
    /** Must use TLS and load credentials from the keystore-backed reference, never log provider responses. */
    fun send(alert: Alert, recipient: String, credentialRef: String): Boolean
}
