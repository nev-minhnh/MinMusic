/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** Non-secret login state per source; tokens and cookies live in encrypted platform storage. */
@Entity(tableName = "source_account")
data class SourceAccountEntity(
    @PrimaryKey val source: String,
    val displayName: String?,
    val entitlement: String,
    val updatedAt: Long,
)
