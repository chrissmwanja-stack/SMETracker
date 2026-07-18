// data/DashboardAnalytics.kt
package com.vestateck.smetracker.data

import com.vestateck.smetracker.data.entities.Debt
import com.vestateck.smetracker.data.entities.Expense
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.PaymentMethod
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.utils.TimeUtils

data class SalesPeriodData(
    val count: Int,
    val revenue: Double,
    val profit: Double,
    val expenses: Double = 0.0
) {
    // Net profit = gross profit from sales margins, minus operating expenses for the same period.
    // This is the number that actually tells an owner if they made money, not just gross margin.
    val netProfit: Double get() = profit - expenses
}

data class ProductRanking(
    val name: String,
    val unitsSold: Int,
    val revenue: Double
)

data class CustomerRanking(
    val name: String,
    val totalSpent: Double
)

data class PaymentBreakdown(
    val cash: Double = 0.0,
    val mtnMoMo: Double = 0.0,
    val airtelMoney: Double = 0.0,
    val debt: Double = 0.0
)

data class DashboardAnalytics(
    val dailySales: SalesPeriodData,
    val weeklySales: SalesPeriodData,
    val monthlySales: SalesPeriodData,
    val allTimeSales: SalesPeriodData,
    val topCustomers: List<CustomerRanking>,
    val paidDebts: List<Debt>,
    val unpaidDebts: List<Debt>,
    val overdueDebts: List<Debt>,
    val paidDebtTotal: Double,
    val unpaidDebtTotal: Double,
    val totalStockUnits: Int,
    val outOfStockCount: Int,
    val categoryBreakdown: Map<String, Int>,
    val topSellingProducts: List<ProductRanking>,
    val paymentBreakdown: PaymentBreakdown
) {
    companion object {
        fun from(
            sales: List<Sale>,
            debts: List<Debt>,
            inventoryItems: List<InventoryItem>,
            expenses: List<Expense> = emptyList()
        ): DashboardAnalytics {
            val now = System.currentTimeMillis()
            val startOfDay = TimeUtils.getStartOfDay()
            val startOfWeek = TimeUtils.getStartOfWeek()
            val startOfMonth = TimeUtils.getStartOfMonth()

            val paymentBreakdown = PaymentBreakdown(
                cash = sales.filter { it.paymentMethod == PaymentMethod.CASH }.sumOf { it.amount },
                mtnMoMo = sales.filter { it.paymentMethod == PaymentMethod.MTN_MOMO }.sumOf { it.amount },
                airtelMoney = sales.filter { it.paymentMethod == PaymentMethod.AIRTEL_MONEY }.sumOf { it.amount },
                debt = sales.filter { it.paymentMethod == PaymentMethod.DEBT }.sumOf { it.amount }
            )

            var dailyCount = 0; var dailyRevenue = 0.0; var dailyProfit = 0.0
            var weeklyCount = 0; var weeklyRevenue = 0.0; var weeklyProfit = 0.0
            var monthlyCount = 0; var monthlyRevenue = 0.0; var monthlyProfit = 0.0

            sales.forEach { sale ->
                val amt = sale.amount
                val prf = sale.profit
                when {
                    sale.date >= startOfDay -> {
                        dailyCount++; dailyRevenue += amt; dailyProfit += prf
                        weeklyCount++; weeklyRevenue += amt; weeklyProfit += prf
                        monthlyCount++; monthlyRevenue += amt; monthlyProfit += prf
                    }
                    sale.date >= startOfWeek -> {
                        weeklyCount++; weeklyRevenue += amt; weeklyProfit += prf
                        monthlyCount++; monthlyRevenue += amt; monthlyProfit += prf
                    }
                    sale.date >= startOfMonth -> {
                        monthlyCount++; monthlyRevenue += amt; monthlyProfit += prf
                    }
                }
            }

            var dailyExpenses = 0.0
            var weeklyExpenses = 0.0
            var monthlyExpenses = 0.0

            expenses.forEach { expense ->
                val amt = expense.amount
                when {
                    expense.date >= startOfDay -> {
                        dailyExpenses += amt; weeklyExpenses += amt; monthlyExpenses += amt
                    }
                    expense.date >= startOfWeek -> {
                        weeklyExpenses += amt; monthlyExpenses += amt
                    }
                    expense.date >= startOfMonth -> {
                        monthlyExpenses += amt
                    }
                }
            }
            val allTimeExpenses = expenses.sumOf { it.amount }

            val customerMap = mutableMapOf<String, Double>()
            sales.forEach {
                customerMap[it.customerName] = (customerMap[it.customerName] ?: 0.0) + it.amount
            }
            val topCustomers = customerMap.entries
                .map { CustomerRanking(it.key, it.value) }
                .sortedByDescending { it.totalSpent }
                .take(5)

            data class ProductAccumulator(var unitsSold: Int = 0, var revenue: Double = 0.0)
            val productMap = mutableMapOf<String, ProductAccumulator>()
            sales.forEach { sale ->
                val acc = productMap.getOrPut(sale.description) { ProductAccumulator() }
                acc.unitsSold += 1
                acc.revenue += sale.amount
            }
            val topSellingProducts = productMap.entries
                .map { (name, acc) -> ProductRanking(name, acc.unitsSold, acc.revenue) }
                .sortedByDescending { it.revenue }
                .take(5)

            val paidDebts = debts.filter { it.isPaid }
            val unpaidDebts = debts.filter { !it.isPaid }

            return DashboardAnalytics(
                dailySales = SalesPeriodData(dailyCount, dailyRevenue, dailyProfit, dailyExpenses),
                weeklySales = SalesPeriodData(weeklyCount, weeklyRevenue, weeklyProfit, weeklyExpenses),
                monthlySales = SalesPeriodData(monthlyCount, monthlyRevenue, monthlyProfit, monthlyExpenses),
                allTimeSales = SalesPeriodData(
                    count = sales.size,
                    revenue = sales.sumOf { it.amount },
                    profit = sales.sumOf { it.profit },
                    expenses = allTimeExpenses
                ),
                topCustomers = topCustomers,
                paidDebts = paidDebts,
                unpaidDebts = unpaidDebts,
                overdueDebts = unpaidDebts.filter { it.dueDate != null && it.dueDate < now },
                paidDebtTotal = paidDebts.sumOf { it.amount },
                unpaidDebtTotal = unpaidDebts.sumOf { it.amount },
                totalStockUnits = inventoryItems.sumOf { it.quantity },
                outOfStockCount = inventoryItems.count { it.quantity == 0 },
                categoryBreakdown = inventoryItems
                    .groupBy { it.category.ifBlank { "Uncategorized" } }
                    .mapValues { (_, items) -> items.sumOf { it.quantity } },
                topSellingProducts = topSellingProducts,
                paymentBreakdown = paymentBreakdown
            )
        }
    }
}