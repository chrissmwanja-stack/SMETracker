// navigation/Screen.kt
package com.vestateck.smetracker.navigation

sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object AddSale : Screen("add_sale")
    object AddDebt : Screen("add_debt")
    object AddCustomer : Screen("add_customer")
    object AddInventory : Screen("add_inventory")
    object BulkAddInventory : Screen("bulk_add_inventory")
    object Customers : Screen("customers")
    object Inventory : Screen("inventory")
    object Reports : Screen("reports")
    object SalesReport : Screen("sales_report")
    object DebtReport : Screen("debt_report")
    object InventoryReport : Screen("inventory_report")
    object TopCustomers : Screen("top_customers")
    object PaymentBreakdown : Screen("payment_breakdown")
    object Expenses : Screen("expenses")
    object Tasks : Screen("tasks")
    object AddWorker : Screen("add_worker")
    object BusinessSettings : Screen("business_settings")
    // saleIds: comma-joined Sale.id list from one addSaleLines() checkout -
    // see SMEViewModel.addSaleLines' onSalesCreated callback and
    // ReceiptData.from. IDs are plain UUID strings (IdGenerator.newId()),
    // so joining with "," is safe - none of them can contain a comma.
    object SaleReceipt : Screen("sale_receipt/{saleIds}") {
        fun createRoute(saleIds: List<String>) = "sale_receipt/${saleIds.joinToString(",")}"
    }
    object Reconciliation : Screen("reconciliation")
}