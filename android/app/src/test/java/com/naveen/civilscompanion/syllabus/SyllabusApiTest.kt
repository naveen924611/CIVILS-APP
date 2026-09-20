package com.naveen.civilscompanion.syllabus

import com.naveen.civilscompanion.ui.syllabus.ApproveBody
import com.naveen.civilscompanion.ui.syllabus.ApproveResult
import com.naveen.civilscompanion.ui.syllabus.SyllabusTree
import com.naveen.civilscompanion.ui.syllabus.describeError
import com.naveen.civilscompanion.ui.syllabus.serverDetail
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyllabusApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun serverDetailReadsTheKindMessage() {
        assertEquals("Nothing to approve.", serverDetail("""{"detail":"Nothing to approve."}"""))
        assertNull(serverDetail("not json"))
        assertNull(serverDetail("""{"detail":[{"msg":"bad"}]}"""))
        assertNull(serverDetail(""))
        assertNull(serverDetail(null))
    }

    @Test
    fun describeErrorIsPlainLanguage() {
        assertTrue(describeError(IOException("boom")).startsWith("Can't reach the server"))
        assertTrue(describeError(IllegalStateException()).startsWith("Something went wrong"))
    }

    @Test
    fun approveBodyMatchesTheServerContract() {
        val tree = SyllabusTree.toJson(SyllabusTree.parse(json.parseToJsonElement("""[{"title":"Polity","children":[{"title":"Preamble"}]}]""")))
        val text = json.encodeToString(ApproveBody.serializer(), ApproveBody("UPSC", true, tree))
        val obj = json.parseToJsonElement(text) as JsonObject
        assertEquals(JsonPrimitive("UPSC"), obj["exam_filter"])
        assertEquals(JsonPrimitive(true), obj["merge_into_existing"])
        assertTrue(obj["tree"].toString().contains("Preamble"))
        val bare = json.parseToJsonElement(json.encodeToString(ApproveBody.serializer(), ApproveBody(null, true, null))) as JsonObject
        assertTrue(bare.containsKey("exam_filter"))
    }

    @Test
    fun approveResultToleratesExtraAndMissingKeys() {
        val r = json.decodeFromString(ApproveResult.serializer(), """{"import_id":"x","status":"approved","total":4,"created":3}""")
        assertEquals(3, r.created)
        assertEquals(0, r.merged)
        assertEquals("approved", r.status)
    }
}
