package com.example.mediconnect_ai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ChatMessageDao {
    @Insert
    suspend fun insert(chatMessage: ChatMessage)

    @Query("SELECT * FROM chat_messages_table ORDER BY timestamp ASC")
    suspend fun getAllMessages(): List<ChatMessage>

    @Query("DELETE FROM chat_messages_table")
    suspend fun clearAllMessages()
}