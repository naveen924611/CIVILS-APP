package com.naveen.civilscompanion

import com.naveen.civilscompanion.data.decodeFacts
import com.naveen.civilscompanion.data.decodeStrings
import com.naveen.civilscompanion.data.parseInstant
import com.naveen.civilscompanion.data.payloadValue
import com.naveen.civilscompanion.data.remote.dto.NewsItemDto
import com.naveen.civilscompanion.data.remote.dto.SyncPullDto
import com.naveen.civilscompanion.data.repo.SyncRepository
import com.naveen.civilscompanion.data.toEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Shaped exactly like the server's GET /sync/pull answer (backend/app/api/sync.py).
    private val sample = """
    {
      "server_time": "2026-09-20T05:30:00.123456Z",
      "more": false,
      "news_items": [{
        "id": "i1", "url": "https://example.com/a", "source": "PIB", "title": "Repo rate held",
        "summary": "The MPC kept the rate.", "relevance_upsc": 8, "relevance_appsc": 6,
        "papers": ["UPSC GS3"], "topic_ids": [],
        "prelims_facts": [{"q": "Who sets the repo rate?", "a": "The RBI"}],
        "mains_angle": "Monetary policy.", "keywords": ["RBI"], "is_ap_specific": false, "mcqs": [],
        "published_at": "2026-09-19T06:00:00Z", "audio_url": "/audio/i1.mp3", "audio_seconds": 95,
        "brief_id": "b1", "updated_at": "2026-09-20T05:29:00.000001Z", "deleted": false
      }],
      "briefs": [{
        "id": "b1", "kind": "morning", "scheduled_for": "2026-09-21T01:30:00Z", "status": "ready",
        "item_ids": ["i1"], "audio_seconds_total": 95, "note": "", "created_at": "2026-09-20T05:00:00Z",
        "updated_at": "2026-09-20T05:29:30Z", "deleted": false
      }],
      "cards": [{
        "id": "c1", "front": "Q?", "back": "A", "topic_id": null, "source_type": "news", "source_id": "i1",
        "group": "Current affairs", "fsrs_state": null, "due_at": null,
        "updated_at": "2026-09-20T05:29:00Z", "deleted": false
      }],
      "alerts": [{
        "id": "a1", "kind": "brief_ready", "title": "Your morning brief is ready", "body": "1 items · 2 min",
        "payload": {"brief_id": "b1", "kind": "morning"}, "read": false,
        "created_at": "2026-09-20T05:29:40Z", "updated_at": "2026-09-20T05:29:40Z", "deleted": false
      }]
    }
    """.trimIndent()

    @Test
    fun serverAnswerIsUnderstood() {
        val page = json.decodeFromString(SyncPullDto.serializer(), sample)
        assertEquals("2026-09-20T05:30:00.123456Z", page.serverTime)
        assertEquals("Repo rate held", page.newsItems.single().title)
        assertEquals("/audio/i1.mp3", page.newsItems.single().audioUrl)
        assertEquals(listOf("i1"), page.briefs.single().itemIds)
        assertEquals("i1", page.cards.single().sourceId)
        assertEquals("b1", page.alerts.single().payload["brief_id"].toString().trim('"'))
    }

    @Test
    fun rowsBecomeDatabaseEntities() {
        val page = json.decodeFromString(SyncPullDto.serializer(), sample)
        val item = page.newsItems.single().toEntity(json)
        assertEquals(listOf("UPSC GS3"), decodeStrings(json, item.papersJson))
        assertEquals("The RBI", decodeFacts(json, item.prelimsFactsJson).single().a)
        assertEquals(1_789_797_600_000L, item.publishedAt)
        val brief = page.briefs.single().toEntity(json)
        assertEquals(listOf("i1"), decodeStrings(json, brief.itemIdsJson))
        val alert = page.alerts.single().toEntity(json)
        assertEquals("b1", payloadValue(json, alert.payloadJson, "brief_id"))
        assertNull(payloadValue(json, alert.payloadJson, "missing"))
    }

    @Test
    fun timestampsWithMicrosecondsParse() {
        assertEquals(1_758_346_200_123L, parseInstant("2025-09-20T05:30:00.123456Z"))
        assertNull(parseInstant("not a date"))
        assertNull(parseInstant(null))
    }

    @Test
    fun nextCursorOnlyAppliesToFullLists() {
        fun item(i: Int) = NewsItemDto(
            id = "i$i", url = "u", title = "t", updatedAt = "2026-09-20T05:00:%02d.%06dZ".format(i / 1000, i % 1000),
        )
        val small = SyncPullDto(serverTime = "x", newsItems = List(10) { item(it) })
        assertNull(SyncRepository.nextCursor(small))
        val full = SyncPullDto(serverTime = "x", more = true, newsItems = List(500) { item(it) })
        assertTrue(SyncRepository.nextCursor(full)!!.startsWith("2026-09-20T05:00:00.000499"))
    }
}
