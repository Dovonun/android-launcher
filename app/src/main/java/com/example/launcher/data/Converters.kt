package com.example.launcher.data

import androidx.room.TypeConverter

class Converters {
    // Room stores enums as primitive DB values.
    // We persist TagItemType as its stable enum name.
    @TypeConverter
    fun fromTagItemType(value: TagItemType): String {
        return value.name
    }

    // Converts DB string values back into TagItemType.
    @TypeConverter
    fun toTagItemType(value: String): TagItemType {
        return TagItemType.valueOf(value)
    }
}
