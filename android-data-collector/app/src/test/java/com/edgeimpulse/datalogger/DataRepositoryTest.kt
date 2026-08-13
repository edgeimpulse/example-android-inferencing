package com.edgeimpulse.datalogger

import android.content.Context
import com.edgeimpulse.datalogger.voice.VoiceCommand
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import java.io.File

class DataRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Mock
    lateinit var context: Context

    @Mock
    lateinit var apiKeyStore: ApiKeyStore

    private lateinit var repository: DataRepository
    private lateinit var testDir: File

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        `when`(context.getExternalFilesDir(null)).thenReturn(tempFolder.root)
        
        repository = DataRepository(context, apiKeyStore)
        testDir = repository.datasetsDir()
    }

    @Test
    fun `datasetsDir creates directory if not exists`() {
        val dir = repository.datasetsDir()
        assertTrue(dir.exists())
        assertEquals("sensor_logs", dir.name)
    }

    @Test
    fun `offline logging creates a file and writes headers`() {
        val headers = listOf("accelX", "accelY", "accelZ")
        repository.startOfflineLogging(headers)
        repository.stopOfflineLogging() // Ensure buffer is flushed/closed
        
        val files = testDir.listFiles { f -> f.extension == "csv" }
        assertNotNull("No files in $testDir", files)
        assertTrue("Files empty in $testDir", files!!.isNotEmpty())
        
        val file = files[0]
        val content = file.readText()
        assertTrue("Content was: \'$content\'", content.contains("timestamp,accelX,accelY,accelZ"))
    }

    @Test
    fun `saveSensorData writes data to file when logging is on`() {
        val headers = listOf("accel_0", "accel_1", "accel_2")
        repository.startOfflineLogging(headers)
        
        val sample = SensorData(1000L, mapOf(
            "accel_0" to 1.1f,
            "accel_1" to 2.2f,
            "accel_2" to 3.3f
        ))
        repository.saveSensorData(sample)
        repository.stopOfflineLogging()
        
        val file = testDir.listFiles()!![0]
        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertEquals("1000,1.1,2.2,3.3", lines[1])
    }

    @Test
    fun `appendWearSamples routes to saveSensorData when offline logging is on`() {
        repository.startOfflineLogging(listOf("wear_0", "wear_1"))
        
        repository.appendWearSamples("wear", 2000L, floatArrayOf(5.5f, 6.6f))
        repository.stopOfflineLogging()
        
        val file = testDir.listFiles()!![0]
        val lines = file.readLines()
        assertTrue("Lines were: $lines", lines[1].contains("2000,5.5,6.6"))
    }

    @Test
    fun `saveZephyrSensorData writes to CSV when logging is on`() {
        repository.startOfflineLogging(listOf("z_0", "z_1"))
        
        repository.saveZephyrSensorData(floatArrayOf(1.2f, 3.4f))
        repository.stopOfflineLogging()
        
        val file = testDir.listFiles()!![0]
        val lines = file.readLines()
        assertTrue("Lines were: $lines", lines[1].contains("1.2,3.4"))
    }

    @Test
    fun `saveZephyrInferenceResult requires sensor data to upload`() {
        val result = ZephyrInferenceResult("test", 0.9f, 10, 20, 12345L)
        
        // This should log a warning and return early because pendingZephyrSensorData is empty
        repository.saveZephyrInferenceResult(result)
        
        verify(apiKeyStore, never()).get()
    }
}
