package com.example.launcher.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromTagItemType(value: TagItemType): String {
        return value.name
    }

    @TypeConverter
    fun toTagItemType(value: String): TagItemType {
        return TagItemType.valueOf(value)
    }
}
