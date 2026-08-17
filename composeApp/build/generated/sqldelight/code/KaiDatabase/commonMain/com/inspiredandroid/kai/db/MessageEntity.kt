package com.inspiredandroid.kai.db

import kotlin.Long
import kotlin.String

public data class MessageEntity(
  public val conversationId: String,
  public val orderIndex: Long,
  public val messageJson: String,
)
