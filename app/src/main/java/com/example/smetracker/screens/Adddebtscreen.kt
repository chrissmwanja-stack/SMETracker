package com.example.smetracker.screens


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.smetracker.data.entities.Debt
import com.example.smetracker.viewmodel.SMEViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDebtScreen(viewModel: SMEViewModel, navController: NavController) {

    val customers by viewModel.customers.collectAsState()

    var customerName by remember { mutableStateOf("") }
    var description  by remember { mutableStateOf("") }
    var amount       by remember { mutableStateOf("") }
    var expanded     by remember { mutableStateOf(false) }

    var nameError   by remember { mutableStateOf(false) }
    var descError   by remember { mutableStateOf(false) }
    var amountError by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Debt", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    navigationIconContentColor = androidx.compose.ui.graphics.Color.White
                )
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Text("Debt Details", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = customerName,
                    onValueChange = {
                        customerName = it
                        nameError = false
                        expanded = it.isNotEmpty()
                    },
                    label = { Text("Customer Name *") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    isError = nameError,
                    supportingText = { if (nameError) Text("Customer name is required") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )

                val filtered = customers.filter {
                    it.name.contains(customerName, ignoreCase = true)
                }
                if (filtered.isNotEmpty()) {
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        filtered.forEach { customer ->
                            DropdownMenuItem(
                                text = { Text(customer.name) },
                                onClick = {
                                    customerName = customer.name
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = description,
                onValueChange = { description = it; descError = false },
                label = { Text("Description *") },
                isError = descError,
                supportingText = { if (descError) Text("Description is required") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it; amountError = false },
                label = { Text("Amount Owed (UGX) *") },
                isError = amountError,
                supportingText = { if (amountError) Text("Enter a valid amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                prefix = { Text("UGX ") }
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    nameError   = customerName.isBlank()
                    descError   = description.isBlank()
                    amountError = amount.toDoubleOrNull() == null || amount.toDouble() <= 0

                    if (!nameError && !descError && !amountError) {
                        viewModel.insertDebt(
                            Debt(
                                customerName = customerName.trim(),
                                description  = description.trim(),
                                amount       = amount.toDouble()
                            )
                        )
                        navController.popBackStack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Save Debt", fontSize = 16.sp)
            }

            OutlinedButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Cancel")
            }
        }
    }
}
