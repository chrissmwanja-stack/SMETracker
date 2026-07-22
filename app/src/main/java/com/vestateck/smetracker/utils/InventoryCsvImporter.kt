package com.vestateck.smetracker.utils

// BulkInventoryRow itself lives in its own file (utils/BulkInventoryRow.kt) —
// costPrice nullable there for the same reason noted on that class: a blank
// CSV cell means the same "fill this in later via Reconciliation" thing a
// worker's quick-add already means (see SMEViewModel.addInventoryItemsBulk).

data class RowIssue(val rowNumber: Int, val message: String)

data class BulkInventoryParseResult(
    val validRows: List<BulkInventoryRow>,
    val issues: List<RowIssue>,
    // Set only when the header row itself is unusable (a required column is
    // missing entirely) — in that case validRows/issues are both empty,
    // since there's nothing sensible to report per-row.
    val headerError: String? = null
)

object InventoryCsvImporter {

    // Matched case-insensitively and trimmed against InventoryItem's actual
    // field names (sellingPrice, not unitPrice — see InventoryItem.kt) so a
    // template built from this app's own vocabulary always round-trips.
    private const val COL_NAME = "name"
    private const val COL_CATEGORY = "category"
    private const val COL_QUANTITY = "quantity"
    private const val COL_SELLING_PRICE = "sellingprice"
    private const val COL_COST_PRICE = "costprice"
    private const val COL_REORDER_LEVEL = "reorderlevel"

    private val REQUIRED = listOf(COL_NAME, COL_QUANTITY, COL_SELLING_PRICE)

    // Shown to the user as a copyable starting point. costPrice left blank
    // on one row deliberately, to model "optional — reconcile later".
    fun templateCsv(): String =
        "name,category,quantity,sellingPrice,costPrice,reorderLevel\n" +
                "Kitenge Fabric (6yd),Fabrics,20,45000,32000,5\n" +
                "Assorted Buttons,Accessories,150,500,,10\n"

    fun parse(csvText: String): BulkInventoryParseResult {
        val rows = CsvParser.parse(csvText)
        if (rows.isEmpty()) {
            return BulkInventoryParseResult(emptyList(), emptyList(), "That file is empty.")
        }

        val header = rows.first().map { it.trim().lowercase() }
        val colIndex = header.withIndex().associate { (i, colName) -> colName to i }

        val missing = REQUIRED.filterNot { colIndex.containsKey(it) }
        if (missing.isNotEmpty()) {
            val niceNames = missing.joinToString(", ") {
                if (it == COL_SELLING_PRICE) "sellingPrice" else it.replaceFirstChar(Char::uppercase)
            }
            return BulkInventoryParseResult(
                emptyList(),
                emptyList(),
                "Missing required column(s): $niceNames. Check the header row matches the template."
            )
        }

        val validRows = mutableListOf<BulkInventoryRow>()
        val issues = mutableListOf<RowIssue>()

        rows.drop(1).forEachIndexed { idx, cells ->
            // +1 for the header row, +1 to make it 1-based for display.
            val rowNumber = idx + 2
            if (cells.size == 1 && cells[0].isBlank()) return@forEachIndexed

            fun cell(col: String): String =
                colIndex[col]?.let { cells.getOrNull(it) }?.trim().orEmpty()

            val name = cell(COL_NAME)
            if (name.isBlank()) {
                issues.add(RowIssue(rowNumber, "Missing product name"))
                return@forEachIndexed
            }

            val quantity = cell(COL_QUANTITY).toIntOrNull()
            if (quantity == null || quantity < 0) {
                issues.add(RowIssue(rowNumber, "\"$name\": quantity must be a whole number"))
                return@forEachIndexed
            }

            val sellingPrice = cell(COL_SELLING_PRICE).toDoubleOrNull()
            if (sellingPrice == null || sellingPrice < 0) {
                issues.add(RowIssue(rowNumber, "\"$name\": sellingPrice must be a number"))
                return@forEachIndexed
            }

            val costPriceStr = cell(COL_COST_PRICE)
            val costPrice = if (costPriceStr.isBlank()) null else costPriceStr.toDoubleOrNull()
            if (costPriceStr.isNotBlank() && costPrice == null) {
                issues.add(RowIssue(rowNumber, "\"$name\": costPrice must be a number, or left blank"))
                return@forEachIndexed
            }

            val reorderStr = cell(COL_REORDER_LEVEL)
            val reorderLevel = if (reorderStr.isBlank()) 5 else reorderStr.toIntOrNull()
            if (reorderLevel == null) {
                issues.add(RowIssue(rowNumber, "\"$name\": reorderLevel must be a whole number"))
                return@forEachIndexed
            }

            validRows.add(
                BulkInventoryRow(
                    name = name,
                    category = cell(COL_CATEGORY),
                    quantity = quantity,
                    sellingPrice = sellingPrice,
                    costPrice = costPrice,
                    reorderLevel = reorderLevel
                )
            )
        }

        return BulkInventoryParseResult(validRows, issues)
    }
}