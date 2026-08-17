package com.inspiredandroid.kai.db.composeApp

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.inspiredandroid.kai.db.ConversationQueries
import com.inspiredandroid.kai.db.KaiDatabase
import kotlin.Long
import kotlin.Unit
import kotlin.reflect.KClass

internal val KClass<KaiDatabase>.schema: SqlSchema<QueryResult.Value<Unit>>
  get() = KaiDatabaseImpl.Schema

internal fun KClass<KaiDatabase>.newInstance(driver: SqlDriver): KaiDatabase = KaiDatabaseImpl(driver)

private class KaiDatabaseImpl(
  driver: SqlDriver,
) : TransacterImpl(driver),
    KaiDatabase {
  override val conversationQueries: ConversationQueries = ConversationQueries(driver)

  public object Schema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long
      get() = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
      driver.execute(null, """
          |CREATE TABLE conversationEntity (
          |    id TEXT NOT NULL PRIMARY KEY,
          |    title TEXT NOT NULL,
          |    type TEXT NOT NULL,
          |    createdAt INTEGER NOT NULL,
          |    updatedAt INTEGER NOT NULL,
          |    shellTranscriptJson TEXT NOT NULL
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE messageEntity (
          |    conversationId TEXT NOT NULL,
          |    orderIndex INTEGER NOT NULL,
          |    messageJson TEXT NOT NULL,
          |    PRIMARY KEY (conversationId, orderIndex)
          |)
          """.trimMargin(), 0)
      return QueryResult.Unit
    }

    override fun migrate(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
      vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
  }
}
