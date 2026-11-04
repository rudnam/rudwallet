package com.rudnam.wallet.core.database

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json
import java.time.Instant

internal class Converters {
    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun tagsToJson(value: List<String>): String = Json.encodeToString(value)

    @TypeConverter
    fun jsonToTags(value: String): List<String> = Json.decodeFromString(value)
}
