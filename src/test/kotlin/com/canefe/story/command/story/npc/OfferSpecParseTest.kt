package com.canefe.story.command.story.npc

import com.canefe.story.storage.OfferItemSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class OfferSpecParseTest {
    @Test
    fun parsesTagAndItemTokens() {
        assertEquals(OfferItemSpec(tag = "currency", qty = 3), parseOfferToken("tag:currency:3"))
        assertEquals(OfferItemSpec(item = "wheat", qty = 1), parseOfferToken("item:wheat:1"))
    }
}
