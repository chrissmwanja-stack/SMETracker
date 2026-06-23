package com.example.smetracker.data.database

import androidx.room.TypeConverter
import com.example.smetracker.data.entities.PaymentMethod

class Converters {
    @TypeConverter
    fun fromPaymentMethod(value: PaymentMethod): String {
        return value.name
    }

    @TypeConverter
    fun toPaymentMethod(value: String): PaymentMethod {
        return PaymentMethod.valueOf(value)
    }
}