package com.example.smetracker.data.database

import androidx.room.TypeConverter
import com.example.smetracker.data.entities.PaymentMethod
import com.example.smetracker.data.entities.StockAdjustmentReason

class Converters {
    @TypeConverter
    fun fromPaymentMethod(value: PaymentMethod): String = value.name

    @TypeConverter
    fun toPaymentMethod(value: String): PaymentMethod = PaymentMethod.valueOf(value)

    @TypeConverter
    fun fromStockAdjustmentReason(value: StockAdjustmentReason): String = value.name

    @TypeConverter
    fun toStockAdjustmentReason(value: String): StockAdjustmentReason = StockAdjustmentReason.valueOf(value)
}
