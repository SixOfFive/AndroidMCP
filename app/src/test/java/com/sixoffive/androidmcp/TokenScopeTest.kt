package com.sixoffive.androidmcp

import com.sixoffive.androidmcp.core.Capabilities
import com.sixoffive.androidmcp.core.ClientToken
import com.sixoffive.androidmcp.core.TokenStore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-token capability scoping: the storage codec and the scope decision, both pure so they can be
 * tested without a device or DataStore. The on-device path (Mcp.toolsCall -> TokenStore.allows ->
 * scopeAllows) is exercised end to end on hardware; here we pin the logic it rests on.
 */
class TokenScopeTest {

    private val hash = "a".repeat(64)

    @Test
    fun `legacy entry with no scope decodes to unrestricted`() {
        // Everything minted before scoping is "hash + name" with no NUL — it must keep full access.
        val t = TokenStore.decodeEntry(hash + "client-1")!!
        assertEquals("client-1", t.name)
        assertEquals(hash, t.hashHex)
        assertNull(t.scope, "a legacy token must decode to null (unrestricted) scope")
    }

    @Test
    fun `a scoped token round-trips through the codec`() {
        val t = ClientToken("client-2", hash, setOf("device_info", "battery_status"))
        val back = TokenStore.decodeEntry(TokenStore.encodeEntry(t))!!
        assertEquals(t.name, back.name)
        assertEquals(t.hashHex, back.hashHex)
        assertEquals(setOf("device_info", "battery_status"), back.scope)
    }

    @Test
    fun `an empty scope round-trips and is distinct from unrestricted`() {
        val back = TokenStore.decodeEntry(TokenStore.encodeEntry(ClientToken("client-3", hash, emptySet())))!!
        assertEquals(emptySet<String>(), back.scope, "empty scope must not collapse to null")
    }

    @Test
    fun `unrestricted scope permits every capability`() {
        Capabilities.REGISTRY.forEach { assertTrue(TokenStore.scopeAllows(null, it.id), "'${it.id}' denied for an unrestricted token") }
    }

    @Test
    fun `a scoped token permits only its set, plus list_capabilities`() {
        val scope = setOf("device_info", "read_sensors")
        assertTrue(TokenStore.scopeAllows(scope, "device_info"))
        assertTrue(TokenStore.scopeAllows(scope, "read_sensors"))
        assertTrue(TokenStore.scopeAllows(scope, "list_capabilities"), "list_capabilities is always discoverable")
        assertFalse(TokenStore.scopeAllows(scope, "root_shell"))
        assertFalse(TokenStore.scopeAllows(scope, "read_sms"))
    }

    @Test
    fun `even an empty scope can still call list_capabilities and nothing else`() {
        assertTrue(TokenStore.scopeAllows(emptySet(), "list_capabilities"))
        assertFalse(TokenStore.scopeAllows(emptySet(), "device_info"))
    }

    @Test
    fun `the read-only preset excludes every high-impact and elevated tool`() {
        // The whole point of the preset: an untrusted client cannot read sensitive data, change
        // device state, or reach the elevated tier.
        Capabilities.lowRiskPresetIds().forEach {
            val c = Capabilities.byId(it)!!
            assertFalse(c.highImpact, "read-only preset leaked high-impact '$it'")
            assertFalse(c.rootRequired, "read-only preset leaked elevated '$it'")
        }
        // And it is non-trivial — it should contain the obvious safe reads.
        assertTrue(Capabilities.lowRiskPresetIds().containsAll(setOf("device_info", "battery_status", "wifi_info")))
    }
}
