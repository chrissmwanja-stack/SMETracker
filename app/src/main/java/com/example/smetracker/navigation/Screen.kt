// navigation/Screen.kt
package com.example.smetracker.navigation

sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object AddSale : Screen("add_sale")
    object AddDebt : Screen("add_debt")
    object AddCustomer : Screen("add_customer")
    object AddInventory : Screen("add_inventory")
    object Customers : Screen("customers")
    object Inventory : Screen("inventory")
    object Reports : Screen("reports")
    object SalesReport : Screen("sales_report")
    object DebtReport : Screen("debt_report")
    object InventoryReport : Screen("inventory_report")
    object TopCustomers : Screen("top_customers")
    object PaymentBreakdown : Screen("payment_breakdown")
}