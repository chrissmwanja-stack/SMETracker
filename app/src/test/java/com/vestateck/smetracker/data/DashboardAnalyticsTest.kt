package com.vestateck.smetracker.data

import com.vestateck.smetracker.data.entities.Customer
import com.vestateck.smetracker.data.entities.Debt
import com.vestateck.smetracker.data.entities.Expense
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.PaymentMethod
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.utils.TimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Sale/Debt/Expense/InventoryItem below passes an explicit `id` —
 * leaving it to the default triggers IdGenerator.newId(), which calls
 * FirebaseFirestore.getInstance() and crashes in a plain JVM unit test
 * (no Android/Firebase runtime here). Always pass id = "..." explicitly
 * when building test fixtures for these entities.
 */
class DashboardAnalyticsTest {

    private fun sale(
        id: String,
        customerName: String = "Jane",
        customerId: String? = null,
        description: String = "Item",
        amount: Double,
        profit: Double = 0.0,
        date: Long = System.currentTimeMillis(),
        paymentMethod: PaymentMethod = PaymentMethod.CASH
    ) = Sale(
        id = id,
        customerId = customerId,
        customerName = customerName,
        description = description,
        amount = amount,
        profit = profit,
        date = date,
        paymentMethod = paymentMethod
    )

    private fun debt(
        id: String,
        amount: Double,
        isPaid: Boolean = false,
        dueDate: Long? = null
    ) = Debt(id = id, customerName = "Jane", amount = amount, isPaid = isPaid, dueDate = dueDate)

    private fun expense(id: String, amount: Double, date: Long = System.currentTimeMillis()) =
        Expense(id = id, description = "Expense", amount = amount, date = date)

    private fun inventoryItem(id: String, quantity: Int, category: String = "") =
        InventoryItem(id = id, name = "Item $id", quantity = quantity, category = category)

    private fun customer(id: String, name: String) = Customer(id = id, name = name)

    // ── Sales period bucketing ──

    @Test
    fun `sale from today counts toward daily, weekly, and monthly totals`() {
        val now = System.currentTimeMillis()
        val analytics = DashboardAnalytics.from(
            sales = listOf(sale(id = "s1", amount = 1000.0, profit = 200.0, date = now)),
            debts = emptyList(),
            inventoryItems = emptyList()
        )
        assertEquals(1, analytics.dailySales.count)
        assertEquals(1000.0, analytics.dailySales.revenue, 0.0)
        assertEquals(1, analytics.weeklySales.count)
        assertEquals(1, analytics.monthlySales.count)
        assertEquals(1, analytics.allTimeSales.count)
    }

    @Test
    fun `sale from earlier this week but not today counts toward weekly and monthly only`() {
        val startOfWeek = TimeUtils.getStartOfWeek()
        // 1ms after the start of the week, but before today started (assuming
        // this test doesn't run at 00:00:00.000 exactly — extremely unlikely).
        val startOfDay = TimeUtils.getStartOfDay()
        if (startOfWeek >= startOfDay) return // week just started today; bucketing distinction doesn't apply, skip
        val midWeekTimestamp = startOfWeek + 1

        val analytics = DashboardAnalytics.from(
            sales = listOf(sale(id = "s1", amount = 500.0, date = midWeekTimestamp)),
            debts = emptyList(),
            inventoryItems = emptyList()
        )
        assertEquals(0, analytics.dailySales.count)
        assertEquals(1, analytics.weeklySales.count)
        assertEquals(1, analytics.monthlySales.count)
        assertEquals(1, analytics.allTimeSales.count)
    }

    @Test
    fun `sale older than this month does not count toward any period bucket but still counts all-time`() {
        val old = TimeUtils.getStartOfMonth() - 1
        val analytics = DashboardAnalytics.from(
            sales = listOf(sale(id = "s1", amount = 750.0, date = old)),
            debts = emptyList(),
            inventoryItems = emptyList()
        )
        assertEquals(0, analytics.dailySales.count)
        assertEquals(0, analytics.weeklySales.count)
        assertEquals(0, analytics.monthlySales.count)
        assertEquals(1, analytics.allTimeSales.count)
        assertEquals(750.0, analytics.allTimeSales.revenue, 0.0)
    }

    // ── Net profit ──

    @Test
    fun `net profit is gross profit minus expenses for the same period`() {
        val now = System.currentTimeMillis()
        val analytics = DashboardAnalytics.from(
            sales = listOf(sale(id = "s1", amount = 1000.0, profit = 400.0, date = now)),
            debts = emptyList(),
            inventoryItems = emptyList(),
            expenses = listOf(expense(id = "e1", amount = 150.0, date = now))
        )
        assertEquals(250.0, analytics.dailySales.netProfit, 0.0)
    }

    @Test
    fun `net profit can go negative when expenses exceed gross profit`() {
        val now = System.currentTimeMillis()
        val analytics = DashboardAnalytics.from(
            sales = listOf(sale(id = "s1", amount = 500.0, profit = 100.0, date = now)),
            debts = emptyList(),
            inventoryItems = emptyList(),
            expenses = listOf(expense(id = "e1", amount = 300.0, date = now))
        )
        assertEquals(-200.0, analytics.dailySales.netProfit, 0.0)
        assertTrue(analytics.dailySales.netProfit < 0)
    }

    // ── Debts ──

    @Test
    fun `paid and unpaid debts are split correctly with correct totals`() {
        val analytics = DashboardAnalytics.from(
            sales = emptyList(),
            debts = listOf(
                debt(id = "d1", amount = 100.0, isPaid = true),
                debt(id = "d2", amount = 200.0, isPaid = false),
                debt(id = "d3", amount = 50.0, isPaid = false)
            ),
            inventoryItems = emptyList()
        )
        assertEquals(1, analytics.paidDebts.size)
        assertEquals(2, analytics.unpaidDebts.size)
        assertEquals(100.0, analytics.paidDebtTotal, 0.0)
        assertEquals(250.0, analytics.unpaidDebtTotal, 0.0)
    }

    @Test
    fun `overdue debts are unpaid debts with a due date in the past`() {
        val now = System.currentTimeMillis()
        val analytics = DashboardAnalytics.from(
            sales = emptyList(),
            debts = listOf(
                debt(id = "d1", amount = 100.0, isPaid = false, dueDate = now - 86_400_000L), // yesterday: overdue
                debt(id = "d2", amount = 200.0, isPaid = false, dueDate = now + 86_400_000L), // tomorrow: not overdue
                debt(id = "d3", amount = 300.0, isPaid = false, dueDate = null),              // no due date: not overdue
                debt(id = "d4", amount = 400.0, isPaid = true, dueDate = now - 86_400_000L)   // paid: excluded regardless
            ),
            inventoryItems = emptyList()
        )
        assertEquals(1, analytics.overdueDebts.size)
        assertEquals(100.0, analytics.overdueDebts.first().amount, 0.0)
    }

    // ── Inventory ──

    @Test
    fun `total stock units sums quantity across all items`() {
        val analytics = DashboardAnalytics.from(
            sales = emptyList(),
            debts = emptyList(),
            inventoryItems = listOf(
                inventoryItem(id = "i1", quantity = 5),
                inventoryItem(id = "i2", quantity = 10),
                inventoryItem(id = "i3", quantity = 0)
            )
        )
        assertEquals(15, analytics.totalStockUnits)
        assertEquals(1, analytics.outOfStockCount)
    }

    @Test
    fun `blank category is grouped under Uncategorized`() {
        val analytics = DashboardAnalytics.from(
            sales = emptyList(),
            debts = emptyList(),
            inventoryItems = listOf(
                inventoryItem(id = "i1", quantity = 3, category = ""),
                inventoryItem(id = "i2", quantity = 7, category = "Drinks")
            )
        )
        assertEquals(3, analytics.categoryBreakdown["Uncategorized"])
        assertEquals(7, analytics.categoryBreakdown["Drinks"])
    }

    // ── Top customers / top products ──

    @Test
    fun `top customers are ranked by total spend descending, capped at 5`() {
        val sales = (1..6).map { i ->
            sale(id = "s$i", customerName = "Customer$i", amount = i * 100.0)
        }
        val analytics = DashboardAnalytics.from(sales = sales, debts = emptyList(), inventoryItems = emptyList())
        assertEquals(5, analytics.topCustomers.size)
        // Customer6 spent the most (600.0), should be first.
        assertEquals("Customer6", analytics.topCustomers.first().name)
        assertEquals(600.0, analytics.topCustomers.first().totalSpent, 0.0)
        // Customer1 (lowest spend) should have been cut off by the top-5 cap.
        assertTrue(analytics.topCustomers.none { it.name == "Customer1" })
    }

    @Test
    fun `repeat purchases from the same customer accumulate into one ranking entry`() {
        val analytics = DashboardAnalytics.from(
            sales = listOf(
                sale(id = "s1", customerName = "Jane", amount = 100.0),
                sale(id = "s2", customerName = "Jane", amount = 250.0)
            ),
            debts = emptyList(),
            inventoryItems = emptyList()
        )
        assertEquals(1, analytics.topCustomers.size)
        assertEquals(350.0, analytics.topCustomers.first().totalSpent, 0.0)
    }

    @Test
    fun `payment breakdown sums amounts per payment method`() {
        val analytics = DashboardAnalytics.from(
            sales = listOf(
                sale(id = "s1", amount = 100.0, paymentMethod = PaymentMethod.CASH),
                sale(id = "s2", amount = 200.0, paymentMethod = PaymentMethod.MTN_MOMO),
                sale(id = "s3", amount = 50.0, paymentMethod = PaymentMethod.CASH),
                sale(id = "s4", amount = 300.0, paymentMethod = PaymentMethod.DEBT)
            ),
            debts = emptyList(),
            inventoryItems = emptyList()
        )
        assertEquals(150.0, analytics.paymentBreakdown.cash, 0.0)
        assertEquals(200.0, analytics.paymentBreakdown.mtnMoMo, 0.0)
        assertEquals(0.0, analytics.paymentBreakdown.airtelMoney, 0.0)
        assertEquals(300.0, analytics.paymentBreakdown.debt, 0.0)
    }

    @Test
    fun `payment breakdown debt total includes unpaid standalone debts alongside DEBT sales`() {
        val analytics = DashboardAnalytics.from(
            sales = listOf(
                sale(id = "s1", amount = 100.0, paymentMethod = PaymentMethod.CASH),
                sale(id = "s2", amount = 300.0, paymentMethod = PaymentMethod.DEBT)
            ),
            debts = listOf(
                debt(id = "d1", amount = 200.0, isPaid = false), // owed - counts
                debt(id = "d2", amount = 500.0, isPaid = true)   // settled - excluded
            ),
            inventoryItems = emptyList()
        )
        // 300 from the DEBT sale + 200 from the unpaid standalone debt.
        // The paid standalone debt (500) is settled, so it's excluded.
        assertEquals(500.0, analytics.paymentBreakdown.debt, 0.0)
    }

    @Test
    fun `total customer count includes walk-in sales not linked to a saved customer`() {
        val analytics = DashboardAnalytics.from(
            sales = listOf(
                sale(id = "s1", customerName = "Grace", customerId = "c1", amount = 100.0),
                sale(id = "s2", customerName = "Walk-in Moses", amount = 50.0),
                sale(id = "s3", customerName = "Walk-in Moses", amount = 30.0) // same walk-in, repeat visit
            ),
            debts = emptyList(),
            inventoryItems = emptyList(),
            customers = listOf(customer(id = "c1", name = "Grace"))
        )
        // 1 saved customer (Grace) + 1 distinct walk-in (Moses, deduped across his 2 sales) = 2
        assertEquals(2, analytics.totalCustomerCount)
    }

    @Test
    fun `total customer count does not double-count a walk-in typed with a saved customer's exact name`() {
        val analytics = DashboardAnalytics.from(
            sales = listOf(
                sale(id = "s1", customerName = "Grace", amount = 50.0) // typed, not picked from dropdown
            ),
            debts = emptyList(),
            inventoryItems = emptyList(),
            customers = listOf(customer(id = "c1", name = "Grace"))
        )
        assertEquals(1, analytics.totalCustomerCount)
    }

    @Test
    fun `saved customer with no sales still counts`() {
        val analytics = DashboardAnalytics.from(
            sales = emptyList(),
            debts = emptyList(),
            inventoryItems = emptyList(),
            customers = listOf(customer(id = "c1", name = "Grace"))
        )
        assertEquals(1, analytics.totalCustomerCount)
    }

    @Test
    fun `empty input produces zeroed analytics without throwing`() {
        val analytics = DashboardAnalytics.from(emptyList(), emptyList(), emptyList())
        assertEquals(0, analytics.allTimeSales.count)
        assertEquals(0.0, analytics.allTimeSales.revenue, 0.0)
        assertEquals(0, analytics.topCustomers.size)
        assertEquals(0, analytics.totalStockUnits)
        assertEquals(0, analytics.totalCustomerCount)
    }
}