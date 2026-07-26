package com.vestateck.smetracker.viewmodel.actions

import com.vestateck.smetracker.data.entities.Task
import com.vestateck.smetracker.fakes.FakeInventoryDao
import com.vestateck.smetracker.fakes.FakeSMEDao
import com.vestateck.smetracker.repository.SMERepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskActionsTest {

    private lateinit var smeDao: FakeSMEDao
    private lateinit var actions: TaskActions

    @Before
    fun setUp() {
        smeDao = FakeSMEDao()
        val repository = SMERepository(smeDao, FakeInventoryDao())
        actions = TaskActions(repository, syncEngine = null)
    }

    @Test
    fun `addTask inserts a pending task with the given fields`() = runTest {
        actions.addTask(title = "Restock shelves", description = "Check aisle 3", priority = "High", dueDate = 1_000L)

        val task = smeDao.tasksFlow.value.single()
        assertEquals("Restock shelves", task.title)
        assertEquals("Check aisle 3", task.description)
        assertEquals("High", task.priority)
        assertEquals(1_000L, task.dueDate)
        assertFalse(task.isCompleted)
    }

    @Test
    fun `addTask defaults priority to Medium and description to null`() = runTest {
        actions.addTask(title = "Call supplier")

        val task = smeDao.tasksFlow.value.single()
        assertEquals("Medium", task.priority)
        assertNull(task.description)
    }

    @Test
    fun `completeTask marks the task completed and pending sync`() = runTest {
        val task = Task(id = "task-1", title = "Pay rent", isCompleted = false, pendingSync = false)
        smeDao.tasksFlow.value = listOf(task)

        actions.completeTask("task-1")

        val updated = smeDao.tasksFlow.value.first { it.id == "task-1" }
        assertTrue(updated.isCompleted)
        assertTrue(updated.pendingSync)
    }

    @Test
    fun `deleteTask soft deletes and marks pending sync`() = runTest {
        val task = Task(id = "task-2", title = "Restock", isDeleted = false, pendingSync = false)
        smeDao.tasksFlow.value = listOf(task)

        actions.deleteTask(task)

        val updated = smeDao.tasksFlow.value.first { it.id == "task-2" }
        assertTrue(updated.isDeleted)
        assertTrue(updated.pendingSync)
    }
}