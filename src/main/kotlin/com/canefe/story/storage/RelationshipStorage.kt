package com.canefe.story.storage

import com.canefe.story.npc.relationship.Relationship

interface RelationshipStorage {
    fun loadAllRelationships(): Map<String, MutableMap<String, Relationship>>

    fun saveRelationship(
        sourceId: String,
        relationships: Map<String, Relationship>,
    )
}
