package com.inspiredandroid.kai.db

import kotlin.Long
import kotlin.String

public data class ConversationEntity(
  public val id: String,
  public val title: String,
  public val type: String,
  public val createdAt: Long,
  public val updatedAt: Long,
  public val shellTranscriptJson: String,
)
