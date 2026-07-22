// utils/ReportPdfMappers.kt
package com.vestateck.smetracker.utils

import com.vestateck.smetracker.data.DashboardAnalytics
import com.vestateck.smetracker.data.entities.Debt
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.data.remote.model.Business
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Converts each report screen's on-screen numbers plus the underlying entity
 * lists into a ReportPdfData - one function per report card on ReportsScreen.
 * Kept separate from ReportPdfRenderer (which only knows about the generic
 * section/table shape) so each report's specific columns/business rules live
 * in one obvious place.
 */
object ReportPdfMappers {

    private val shortDate = SimpleDateFormat("dd MMM yy", Locale.getDefault())

    private fun header(business: Business?): Triple<String, String, String> = Triple(
        business?.name?.ifBlank { null } ?: "SME Tracker",
        business?.address ?: "",
        business?.ownerPhone ?: ""
    )

    // ── Sales Report ──────────────────────────────────────────────────

    fun salesReport(business: Business?, analytics: DashboardAnalytics, sales: List<Sale>, isOwner: Boolean): ReportPdfData {
        val (name, address, phone) = header(business)
        val sections = mutableListOf<ReportSection>()

        sections += ReportSection(
            heading = "Sales Summary",
            summaryRows = listOf(
                ReportSummaryRow("Today", "${analytics.dailySales.count} sales · ${CurrencyUtils.formatUgx(analytics.dailySales.revenue)}"),
                ReportSummaryRow("This Week", "${analytics.weeklySales.count} sales · ${CurrencyUtils.formatUgx(analytics.weeklySales.revenue)}"),
                ReportSummaryRow("This Month", "${analytics.monthlySales.count} sales · ${CurrencyUtils.formatUgx(analytics.monthlySales.revenue)}"),
                ReportSummaryRow("All Time", "${analytics.allTimeSales.count} sales · ${CurrencyUtils.formatUgx(analytics.allTimeSales.revenue)}", emphasize = true)
            )
        )

        if (isOwner) {
            sections += ReportSection(
                heading = "Profit & Loss",
                summaryRows = listOf(
                    ReportSummaryRow("Today", "Gross ${CurrencyUtils.formatUgx(analytics.dailySales.profit)} − Exp ${CurrencyUtils.formatUgx(analytics.dailySales.expenses)} = ${CurrencyUtils.formatUgx(analytics.dailySales.netProfit)}"),
                    ReportSummaryRow("This Week", "Gross ${CurrencyUtils.formatUgx(analytics.weeklySales.profit)} − Exp ${CurrencyUtils.formatUgx(analytics.weeklySales.expenses)} = ${CurrencyUtils.formatUgx(analytics.weeklySales.netProfit)}"),
                    ReportSummaryRow("This Month", "Gross ${CurrencyUtils.formatUgx(analytics.monthlySales.profit)} − Exp ${CurrencyUtils.formatUgx(analytics.monthlySales.expenses)} = ${CurrencyUtils.formatUgx(analytics.monthlySales.netProfit)}"),
                    ReportSummaryRow("All Time", "Gross ${CurrencyUtils.formatUgx(analytics.allTimeSales.profit)} − Exp ${CurrencyUtils.formatUgx(analytics.allTimeSales.expenses)} = ${CurrencyUtils.formatUgx(analytics.allTimeSales.netProfit)}", emphasize = true)
                )
            )
        }

        val ordered = sales.sortedByDescending { it.date }
        val headers = if (isOwner) listOf("Date", "Item", "Qty", "Customer", "Payment", "Amount", "Profit")
        else listOf("Date", "Item", "Qty", "Customer", "Payment", "Amount")
        val weights = if (isOwner) listOf(0.11f, 0.22f, 0.06f, 0.17f, 0.13f, 0.15f, 0.16f)
        else listOf(0.13f, 0.28f, 0.08f, 0.20f, 0.15f, 0.16f)
        val rightAligned = if (isOwner) setOf(2, 5, 6) else setOf(2, 5)
        val rows = ordered.map { sale ->
            val base = listOf(
                shortDate.format(java.util.Date(sale.date)),
                sale.description,
                sale.quantity.toString(),
                sale.customerName,
                sale.paymentMethod.name.replace("_", " "),
                CurrencyUtils.formatUgx(sale.amount)
            )
            if (isOwner) base + CurrencyUtils.formatUgx(sale.profit) else base
        }

        sections += ReportSection(
            tables = listOf(
                ReportTable(
                    heading = "All Sales (${ordered.size})",
                    headers = headers,
                    rows = rows,
                    weights = weights,
                    rightAlignedColumns = rightAligned
                )
            )
        )

        return ReportPdfData(name, address, phone, "Sales Report", sections = sections)
    }

    // ── Debt Report ──────────────────────────────────────────────────

    fun debtReport(business: Business?, analytics: DashboardAnalytics): ReportPdfData {
        val (name, address, phone) = header(business)
        val totalDebt = analytics.paidDebtTotal + analytics.unpaidDebtTotal
        val totalCount = analytics.paidDebts.size + analytics.unpaidDebts.size

        fun debtRows(debts: List<Debt>) = debts.sortedByDescending { it.date }.map { debt ->
            listOf(
                debt.customerName,
                debt.description.ifBlank { "—" },
                debt.dueDate?.let { shortDate.format(java.util.Date(it)) } ?: "—",
                shortDate.format(java.util.Date(debt.date)),
                CurrencyUtils.formatUgx(debt.amount)
            )
        }
        val debtHeaders = listOf("Customer", "Description", "Due Date", "Recorded", "Amount")
        val debtWeights = listOf(0.22f, 0.30f, 0.16f, 0.16f, 0.16f)

        return ReportPdfData(
            businessName = name, businessAddress = address, businessPhone = phone,
            reportTitle = "Debt Report",
            sections = listOf(
                ReportSection(
                    heading = "Debt Summary",
                    summaryRows = listOf(
                        ReportSummaryRow("Total Debts", "$totalCount records · ${CurrencyUtils.formatUgx(totalDebt)}", emphasize = true),
                        ReportSummaryRow("Paid", "${analytics.paidDebts.size} debts · ${CurrencyUtils.formatUgx(analytics.paidDebtTotal)}"),
                        ReportSummaryRow("Unpaid", "${analytics.unpaidDebts.size} debts · ${CurrencyUtils.formatUgx(analytics.unpaidDebtTotal)}"),
                        ReportSummaryRow("Overdue", "${analytics.overdueDebts.size} debts")
                    )
                ),
                ReportSection(
                    tables = listOf(
                        ReportTable("Unpaid Debts (${analytics.unpaidDebts.size})", debtHeaders, debtRows(analytics.unpaidDebts), debtWeights, rightAlignedColumns = setOf(4)),
                        ReportTable("Paid Debts (${analytics.paidDebts.size})", debtHeaders, debtRows(analytics.paidDebts), debtWeights, rightAlignedColumns = setOf(4))
                    )
                )
            )
        )
    }

    // ── Inventory Report ─────────────────────────────────────────────

    fun inventoryReport(
        business: Business?,
        analytics: DashboardAnalytics,
        inventoryItems: List<InventoryItem>,
        totalStockValue: Double,
        lowStockCount: Int
    ): ReportPdfData {
        val (name, address, phone) = header(business)
        val sections = mutableListOf<ReportSection>()

        sections += ReportSection(
            heading = "Inventory Summary",
            summaryRows = listOf(
                ReportSummaryRow("Total Products", "${inventoryItems.size} SKUs · ${CurrencyUtils.formatUgx(totalStockValue)}", emphasize = true),
                ReportSummaryRow("Total Units", "${analytics.totalStockUnits} units"),
                ReportSummaryRow("Low Stock", "$lowStockCount items"),
                ReportSummaryRow("Out of Stock", "${analytics.outOfStockCount} items")
            )
        )

        if (analytics.categoryBreakdown.isNotEmpty()) {
            sections += ReportSection(
                tables = listOf(
                    ReportTable(
                        heading = "By Category",
                        headers = listOf("Category", "Units"),
                        rows = analytics.categoryBreakdown.entries.sortedByDescending { it.value }
                            .map { (cat, qty) -> listOf(cat, "$qty") },
                        weights = listOf(0.7f, 0.3f),
                        rightAlignedColumns = setOf(1)
                    )
                )
            )
        }

        if (analytics.topSellingProducts.isNotEmpty()) {
            sections += ReportSection(
                tables = listOf(
                    ReportTable(
                        heading = "Top Selling Products",
                        headers = listOf("#", "Product", "Units Sold", "Revenue"),
                        rows = analytics.topSellingProducts.mapIndexed { i, p ->
                            listOf("${i + 1}", p.name, "${p.unitsSold}", CurrencyUtils.formatUgx(p.revenue))
                        },
                        weights = listOf(0.08f, 0.42f, 0.2f, 0.3f),
                        rightAlignedColumns = setOf(2, 3)
                    )
                )
            )
        }

        val sortedItems = inventoryItems.sortedBy { it.quantity }
        sections += ReportSection(
            tables = listOf(
                ReportTable(
                    heading = "All Inventory Items (${sortedItems.size})",
                    headers = listOf("Product", "Category", "Qty", "Reorder Lvl", "Unit Price", "Stock Value"),
                    rows = sortedItems.map { item ->
                        listOf(
                            item.name,
                            item.category.ifBlank { "Uncategorized" },
                            "${item.quantity}",
                            "${item.reorderLevel}",
                            CurrencyUtils.formatUgx(item.sellingPrice),
                            CurrencyUtils.formatUgx(item.quantity * item.sellingPrice)
                        )
                    },
                    weights = listOf(0.26f, 0.18f, 0.1f, 0.14f, 0.16f, 0.16f),
                    rightAlignedColumns = setOf(2, 3, 4, 5)
                )
            )
        )

        return ReportPdfData(name, address, phone, "Inventory Report", sections = sections)
    }

    // ── Top Customers ────────────────────────────────────────────────

    fun topCustomersReport(business: Business?, sales: List<Sale>): ReportPdfData {
        val (name, address, phone) = header(business)
        // Full ranking, not capped at 5 like the on-screen/dashboard version -
        // "itemized" means every customer with a purchase, not just the top slice.
        val ranking = sales
            .groupBy { it.customerName }
            .map { (customerName, customerSales) -> customerName to customerSales.sumOf { it.amount } }
            .sortedByDescending { it.second }

        return ReportPdfData(
            businessName = name, businessAddress = address, businessPhone = phone,
            reportTitle = "Top Customers",
            sections = listOf(
                ReportSection(
                    tables = listOf(
                        ReportTable(
                            heading = "Customers by Revenue (${ranking.size})",
                            headers = listOf("Rank", "Customer", "Total Spent"),
                            rows = ranking.mapIndexed { i, (customerName, total) ->
                                listOf("${i + 1}", customerName, CurrencyUtils.formatUgx(total))
                            },
                            weights = listOf(0.12f, 0.58f, 0.3f),
                            rightAlignedColumns = setOf(2)
                        )
                    )
                )
            )
        )
    }

    // ── Payment Breakdown ────────────────────────────────────────────

    fun paymentBreakdownReport(business: Business?, analytics: DashboardAnalytics, sales: List<Sale>): ReportPdfData {
        val (name, address, phone) = header(business)
        val breakdown = analytics.paymentBreakdown
        val total = breakdown.cash + breakdown.mtnMoMo + breakdown.airtelMoney + breakdown.debt

        fun txRows(method: com.vestateck.smetracker.data.entities.PaymentMethod) =
            sales.filter { it.paymentMethod == method }.sortedByDescending { it.date }.map { sale ->
                listOf(shortDate.format(java.util.Date(sale.date)), sale.customerName, sale.description, CurrencyUtils.formatUgx(sale.amount))
            }
        val txHeaders = listOf("Date", "Customer", "Item", "Amount")
        val txWeights = listOf(0.16f, 0.28f, 0.36f, 0.2f)

        val standaloneUnpaidDebtRows = analytics.unpaidDebts.map { debt ->
            listOf(shortDate.format(java.util.Date(debt.date)), debt.customerName, debt.description.ifBlank { "—" }, CurrencyUtils.formatUgx(debt.amount))
        }

        return ReportPdfData(
            businessName = name, businessAddress = address, businessPhone = phone,
            reportTitle = "Payment Breakdown",
            sections = listOf(
                ReportSection(
                    heading = "Summary",
                    summaryRows = listOf(
                        ReportSummaryRow("Total Sales", CurrencyUtils.formatUgx(total), emphasize = true),
                        ReportSummaryRow("Cash", CurrencyUtils.formatUgx(breakdown.cash)),
                        ReportSummaryRow("MTN MoMo", CurrencyUtils.formatUgx(breakdown.mtnMoMo)),
                        ReportSummaryRow("Airtel Money", CurrencyUtils.formatUgx(breakdown.airtelMoney)),
                        ReportSummaryRow("Debt / Credit", CurrencyUtils.formatUgx(breakdown.debt))
                    )
                ),
                ReportSection(tables = listOf(ReportTable("Cash Transactions", txHeaders, txRows(com.vestateck.smetracker.data.entities.PaymentMethod.CASH), txWeights, rightAlignedColumns = setOf(3)))),
                ReportSection(tables = listOf(ReportTable("MTN MoMo Transactions", txHeaders, txRows(com.vestateck.smetracker.data.entities.PaymentMethod.MTN_MOMO), txWeights, rightAlignedColumns = setOf(3)))),
                ReportSection(tables = listOf(ReportTable("Airtel Money Transactions", txHeaders, txRows(com.vestateck.smetracker.data.entities.PaymentMethod.AIRTEL_MONEY), txWeights, rightAlignedColumns = setOf(3)))),
                ReportSection(tables = listOf(ReportTable("Debt Sales", txHeaders, txRows(com.vestateck.smetracker.data.entities.PaymentMethod.DEBT), txWeights, rightAlignedColumns = setOf(3)))),
                ReportSection(tables = listOf(ReportTable("Standalone Unpaid Debts", listOf("Recorded", "Customer", "Description", "Amount"), standaloneUnpaidDebtRows, txWeights, rightAlignedColumns = setOf(3))))
            )
        )
    }
}