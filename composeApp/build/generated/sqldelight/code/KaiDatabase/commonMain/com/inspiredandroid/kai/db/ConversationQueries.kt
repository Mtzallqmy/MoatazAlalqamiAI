package com.inspiredandroid.kai.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.Any
import kotlin.Long
import kotlin.String

public class ConversationQueries(
  driver: SqlDriver,
) : TransacterImpl(driver) {
  public fun <T : Any> selectAllConversations(mapper: (
    id: String,
    title: String,
    type: String,
    createdAt: Long,
    updatedAt: Long,
    shellTranscriptJson: String,
  ) -> T): Query<T> = Query(586_613_991, arrayOf("conversationEntity"), driver, "conversation.sq", "selectAllConversations", "SELECT conversationEntity.id, conversationEntity.title, conversationEntity.type, conversationEntity.createdAt, conversationEntity.updatedAt, conversationEntity.shellTranscriptJson FROM conversationEntity ORDER BY createdAt, id") { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getLong(3)!!,
      cursor.getLong(4)!!,
      cursor.getString(5)!!
    )
  }

  public fun selectAllConversations(): Query<ConversationEntity> = selectAllConversations(::ConversationEntity)

  public fun <T : Any> selectAllMessages(mapper: (
    conversationId: String,
    orderIndex: Long,
    messageJson: String,
  ) -> T): Query<T> = Query(1_467_182_677, arrayOf("messageEntity"), driver, "conversation.sq", "selectAllMessages", "SELECT messageEntity.conversationId, messageEntity.orderIndex, messageEntity.messageJson FROM messageEntity ORDER BY conversationId, orderIndex") { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getLong(1)!!,
      cursor.getString(2)!!
    )
  }

  public fun selectAllMessages(): Query<MessageEntity> = selectAllMessages(::MessageEntity)

  /**
   * @return The number of rows updated.
   */
  public fun upsertConversation(
    id: String,
    title: String,
    type: String,
    createdAt: Long,
    updatedAt: Long,
    shellTranscriptJson: String,
  ): QueryResult<Long> {
    val result = driver.execute(1_059_083_502, """
        |INSERT OR REPLACE INTO conversationEntity (id, title, type, createdAt, updatedAt, shellTranscriptJson)
        |VALUES (?, ?, ?, ?, ?, ?)
        """.trimMargin(), 6) {
          var parameterIndex = 0
          bindString(parameterIndex++, id)
          bindString(parameterIndex++, title)
          bindString(parameterIndex++, type)
          bindLong(parameterIndex++, createdAt)
          bindLong(parameterIndex++, updatedAt)
          bindString(parameterIndex++, shellTranscriptJson)
        }
    notifyQueries(1_059_083_502) { emit ->
      emit("conversationEntity")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public fun updateShellTranscript(shellTranscriptJson: String, id: String): QueryResult<Long> {
    val result = driver.execute(-813_638_943, """UPDATE conversationEntity SET shellTranscriptJson = ? WHERE id = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, shellTranscriptJson)
          bindString(parameterIndex++, id)
        }
    notifyQueries(-813_638_943) { emit ->
      emit("conversationEntity")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public fun deleteConversation(id: String): QueryResult<Long> {
    val result = driver.execute(1_496_639_338, """DELETE FROM conversationEntity WHERE id = ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, id)
        }
    notifyQueries(1_496_639_338) { emit ->
      emit("conversationEntity")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public fun insertMessage(
    conversationId: String,
    orderIndex: Long,
    messageJson: String,
  ): QueryResult<Long> {
    val result = driver.execute(-1_892_935_694, """
        |INSERT INTO messageEntity (conversationId, orderIndex, messageJson)
        |VALUES (?, ?, ?)
        """.trimMargin(), 3) {
          var parameterIndex = 0
          bindString(parameterIndex++, conversationId)
          bindLong(parameterIndex++, orderIndex)
          bindString(parameterIndex++, messageJson)
        }
    notifyQueries(-1_892_935_694) { emit ->
      emit("messageEntity")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public fun deleteMessages(conversationId: String): QueryResult<Long> {
    val result = driver.execute(-1_927_571_533, """DELETE FROM messageEntity WHERE conversationId = ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, conversationId)
        }
    notifyQueries(-1_927_571_533) { emit ->
      emit("messageEntity")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public fun deleteAllConversations(): QueryResult<Long> {
    val result = driver.execute(-1_797_449_834, """DELETE FROM conversationEntity""", 0)
    notifyQueries(-1_797_449_834) { emit ->
      emit("conversationEntity")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public fun deleteAllMessages(): QueryResult<Long> {
    val result = driver.execute(13_249_094, """DELETE FROM messageEntity""", 0)
    notifyQueries(13_249_094) { emit ->
      emit("messageEntity")
    }
    return result
  }
}
