package com.niuma.checkin

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.niuma.core.Engine
import com.niuma.core.State
import com.niuma.core.Store
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MailConfigurationTest {
    @Test fun bundledMailKeyRestoresConfigurationAfterFreshInstall() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("mail-test-$name", mode)
        }
        val state = State()
        val engine = Engine(object : Store {
            override fun <T> transaction(block: (State) -> T): T = block(state)
        })
        val transport = QqMailTransport(context)
        transport.ensureBundledConfiguration(engine)

        val ref = state.settings.credentialRef
        try {
            assertTrue(transport.configured)
            assertEquals("hui18@me.com", state.settings.recipient)
            assertFalse(state.settings.mailVerified)
            assertTrue(ref.isNotBlank())
            val config = org.json.JSONObject(String(CredentialVault(context).read(ref)))
            assertEquals("67751@qq.com", config.getString("account"))
            assertTrue(config.getString("password").isNotBlank())
            transport.ensureBundledConfiguration(engine)
            assertEquals(ref, state.settings.credentialRef)
        } finally {
            if (ref.isNotBlank()) CredentialVault(context).remove(ref)
            target.deleteSharedPreferences("mail-test-mail-provider")
            target.deleteSharedPreferences("mail-test-mail-vault")
        }
    }
}
