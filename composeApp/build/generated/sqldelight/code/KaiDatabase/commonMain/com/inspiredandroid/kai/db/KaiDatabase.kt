package com.inspiredandroid.kai.db

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.inspiredandroid.kai.db.composeApp.newInstance
import com.inspiredandroid.kai.db.composeApp.schema
import kotlin.Unit

public interface KaiDatabase : Transacter {
  public val conversationQueries: ConversationQueries

  public companion object {
    public val Schema: SqlSchema<QueryResult.Value<Unit>>
      get() = KaiDatabase::class.schema

    public operator fun invoke(driver: SqlDriver): KaiDatabase = KaiDatabase::class.newInstance(driver)
  }
}
