package com.vestateck.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.vestateck.smetracker.data.entities.Task
import com.vestateck.smetracker.viewmodel.SMEViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TASK_PRIORITIES = listOf("High", "Medium", "Low")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(viewModel: SMEViewModel, navController: NavController) {
    val tasks by viewModel.pendingTasks.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasks", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Task")
            }
        }
    ) { padding ->
        if (tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.TaskAlt, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text("No pending tasks", fontSize = 16.sp, color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(tasks.sortedWith(compareBy({ priorityRank(it.priority) }, { it.dueDate ?: Long.MAX_VALUE })), key = { it.id }) { task ->
                    TaskListItem(
                        task = task,
                        onComplete = { viewModel.completeTask(task.id) },
                        onDelete = { viewModel.deleteTask(task) }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showDialog) {
        AddTaskDialog(
            onDismiss = { showDialog = false },
            onConfirm = { title, description, priority, dueDate ->
                viewModel.addTask(title, description, priority, dueDate)
                showDialog = false
            }
        )
    }
}

private fun priorityRank(priority: String): Int = when (priority) {
    "High" -> 0
    "Medium" -> 1
    else -> 2
}

private fun priorityColor(priority: String): Color = when (priority) {
    "High" -> Color(0xFFD32F2F)
    "Medium" -> Color(0xFFF9A825)
    else -> Color(0xFF388E3C)
}

@Composable
private fun TaskListItem(task: Task, onComplete: () -> Unit, onDelete: () -> Unit) {
    var showConfirmDelete by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val isOverdue = task.dueDate != null && task.dueDate < System.currentTimeMillis()

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onComplete) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Complete", tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (!task.description.isNullOrBlank()) {
                    Text(task.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 2)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(6.dp), color = priorityColor(task.priority).copy(alpha = 0.15f)) {
                        Text(
                            task.priority,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = priorityColor(task.priority),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    task.dueDate?.let {
                        Text(
                            "Due ${dateFormat.format(Date(it))}",
                            fontSize = 12.sp,
                            color = if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontWeight = if (isOverdue) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            IconButton(onClick = { showConfirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("Delete Task") },
            text = { Text("Are you sure you want to delete \"${task.title}\"?") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showConfirmDelete = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, description: String?, priority: String, dueDate: Long?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("Medium") }
    var expanded by remember { mutableStateOf(false) }
    var titleError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add Task", fontWeight = FontWeight.Bold, fontSize = 20.sp)

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; titleError = false },
                    label = { Text("Title *") },
                    isError = titleError,
                    supportingText = { if (titleError) Text("Title is required") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Notes (optional)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = priority,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Priority") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        TASK_PRIORITIES.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = { priority = option; expanded = false }
                            )
                        }
                    }
                }

                // Due date entry is kept simple (no date picker dependency yet) —
                // tasks can be created without a due date and prioritized by priority alone.

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = {
                        titleError = title.isBlank()
                        if (!titleError) {
                            onConfirm(title.trim(), description.trim().ifBlank { null }, priority, null)
                        }
                    }) { Text("Save") }
                }
            }
        }
    }
}