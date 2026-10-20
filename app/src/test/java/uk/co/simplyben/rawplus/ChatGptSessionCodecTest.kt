package uk.co.simplyben.rawplus

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatGptSessionCodecTest {
    private val id = "123e4567-e89b-12d3-a456-426614174000"
    private val otherId = "223e4567-e89b-12d3-a456-426614174001"
    private val epoch = "123e4567-e89b-12d3-a456-426614174002"
    private val tokens = ChatGptTokens("access-secret", "refresh-secret", "identity-secret",
        ChatGptAuthProtocol.SCOPES.split(' ').toSet(), 1_700_003_600_000, 1_700_003_000_000)
    private fun record() = ChatGptRecord(id, "oaiapp_registered", "validated-subject", "person@example.com", true,
        tokens, epoch, listOf(ChatGptModel("account-model", "Available model")), "account-model")
    private fun sessions() = ChatGptSessions("urn:uuid:$id", id, epoch, listOf(record()))
    private fun encoded() = JSONObject(ChatGptSessionCodec.encode(sessions()))

    @Test fun `registration and credential records survive together with host and selected catalog`() {
        val restored = ChatGptSessionCodec.decode(ChatGptSessionCodec.encode(sessions()))
        assertEquals(sessions().hostId, restored.hostId)
        assertEquals(id, restored.selected)
        assertEquals("validated-subject", restored.selectedRecord()?.subject)
        assertEquals("oaiapp_registered", restored.selectedRecord()?.clientId)
        assertEquals("refresh-secret", restored.selectedRecord()?.tokens?.refresh)
        assertEquals("account-model", restored.selectedRecord()?.selectedModel)
    }

    @Test fun `disconnection retains registration and host while removing every token and model`() {
        val disconnected = sessions().copy(records = listOf(record().copy(tokens = null, models = emptyList(), selectedModel = null)))
        val json = ChatGptSessionCodec.encode(disconnected)
        assertFalse(json.contains("access-secret"))
        assertFalse(json.contains("refresh-secret"))
        assertFalse(json.contains("identity-secret"))
        val restored = ChatGptSessionCodec.decode(json)
        assertEquals(sessions().hostId, restored.hostId)
        assertEquals("oaiapp_registered", restored.selectedRecord()?.clientId)
        assertEquals("validated-subject", restored.selectedRecord()?.subject)
        assertFalse(checkNotNull(restored.selectedRecord()).publicAccount().connected)
        assertFalse(checkNotNull(restored.selectedRecord()).publicAccount().planEnabled)
    }

    @Test fun `same email or subject does not collapse distinct workspace registrations`() {
        val first = record()
        val second = first.copy(id = otherId, clientId = "oaiapp_other_workspace")
        val restored = ChatGptSessionCodec.decode(ChatGptSessionCodec.encode(sessions().copy(records = listOf(first, second))))
        assertEquals(2, restored.records.size)
        assertNotEquals(restored.records[0].clientId, restored.records[1].clientId)
        assertNotEquals(restored.records[0].publicAccount().label, restored.records[1].publicAccount().label)
    }

    @Test fun `codec rejects duplicate registration or account identifiers and absent selected record`() {
        val duplicateRegistration = encoded()
        duplicateRegistration.getJSONArray("accounts").put(JSONObject(duplicateRegistration.getJSONArray("accounts").getJSONObject(0).toString()).put("id", otherId))
        assertThrows(Exception::class.java) { ChatGptSessionCodec.decode(duplicateRegistration.toString()) }
        val duplicateId = encoded()
        duplicateId.getJSONArray("accounts").put(JSONObject(duplicateId.getJSONArray("accounts").getJSONObject(0).toString()).put("client_id", "oaiapp_another"))
        assertThrows(Exception::class.java) { ChatGptSessionCodec.decode(duplicateId.toString()) }
        assertThrows(Exception::class.java) { ChatGptSessionCodec.decode(encoded().put("selected", otherId).toString()) }
    }

    @Test fun `codec rejects model selection from another account or leftover catalog on disconnect`() {
        val invalid = encoded()
        invalid.getJSONArray("accounts").getJSONObject(0).put("selected_model", "not-in-this-catalog")
        assertThrows(Exception::class.java) { ChatGptSessionCodec.decode(invalid.toString()) }
        val disconnected = encoded()
        disconnected.getJSONArray("accounts").getJSONObject(0).put("tokens", JSONObject.NULL)
        assertThrows(Exception::class.java) { ChatGptSessionCodec.decode(disconnected.toString()) }
    }

    @Test fun `codec rejects initial dynamic placeholder invalid tokens corrupted host and excessive accounts`() {
        val invalidClient = encoded()
        invalidClient.getJSONArray("accounts").getJSONObject(0).put("client_id", "dynamic_agent_client")
        assertThrows(Exception::class.java) { ChatGptSessionCodec.decode(invalidClient.toString()) }
        val invalidToken = encoded()
        invalidToken.getJSONArray("accounts").getJSONObject(0).getJSONObject("tokens").put("access", "credential\nleak")
        assertThrows(Exception::class.java) { ChatGptSessionCodec.decode(invalidToken.toString()) }
        assertThrows(Exception::class.java) { ChatGptSessionCodec.decode(encoded().put("host_id", "urn:uuid:invalid").toString()) }
        assertThrows(Exception::class.java) { ChatGptSessionCodec.decode(encoded().put("accounts", JSONArray((1..11).map { JSONObject() })).toString()) }
    }

    @Test fun `unverified email is not presented as the account label and diagnostics redact credentials`() {
        assertFalse(record().copy(emailVerified = false).publicAccount().label.contains("person@example.com"))
        assertFalse(record().toString().contains("access-secret"))
        assertFalse(sessions().toString().contains("refresh-secret"))
    }
}
