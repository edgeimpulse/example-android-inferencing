package com.edgeimpulse.datalogger

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.mockito.*
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class SensorViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Mock
    lateinit var application: Application

    @Mock
    lateinit var sensorCollector: SensorCollector

    @Mock
    lateinit var gattServerManager: GattServerManager

    @Mock
    lateinit var edgeImpulseManager: EdgeImpulseManager

    @Mock
    lateinit var dataRepository: DataRepository

    @Mock
    lateinit var zephyrBLEClient: ZephyrBLEClient

    @Mock
    lateinit var wearOSClient: WearOSClient

    @Mock
    lateinit var apiKeyStore: ApiKeyStore

    @Mock
    lateinit var voiceSettingsStore: VoiceSettingsStore

    @Mock
    lateinit var locationCollector: LocationCollector

    @Mock
    lateinit var audioRecorder: AudioFileRecorder

    @Mock
    lateinit var usbSerialClient: UsbSerialClient

    private lateinit var viewModel: SensorViewModel

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)

        // Mock dependencies returned by lazy or in constructor
        `when`(sensorCollector.dataFlow).thenReturn(MutableStateFlow(SensorData(0, emptyMap())))
        `when`(sensorCollector.availableSensorLabels()).thenReturn(listOf("Accelerometer"))
        `when`(locationCollector.dataFlow).thenReturn(MutableStateFlow(SensorData(0, emptyMap())))
        `when`(usbSerialClient.isConnected).thenReturn(MutableStateFlow(false))
        `when`(usbSerialClient.statusMessage).thenReturn(MutableStateFlow(""))
        `when`(usbSerialClient.sampleCount).thenReturn(MutableStateFlow(0))
        `when`(usbSerialClient.columnHeaders).thenReturn(MutableStateFlow(emptyList()))
        `when`(usbSerialClient.lastSample).thenReturn(MutableStateFlow(null))
        
        // Mock StateFlows for edgeImpulseManager
        `when`(edgeImpulseManager.isConnected).thenReturn(MutableStateFlow(false))
        `when`(edgeImpulseManager.connectionError).thenReturn(MutableStateFlow(""))
        
        // Mock StateFlows for wearOSClient
        `when`(wearOSClient.samplesReceived).thenReturn(MutableStateFlow(0))
        `when`(wearOSClient.connectedNode).thenReturn(MutableStateFlow(null))
        
        // Mock StateFlows for zephyrBLEClient
        `when`(zephyrBLEClient.isConnected).thenReturn(MutableStateFlow(false))
        `when`(zephyrBLEClient.latestInference).thenReturn(MutableStateFlow(null))
        `when`(zephyrBLEClient.scannedDevices).thenReturn(MutableStateFlow(emptyList()))
        `when`(zephyrBLEClient.currentLabel).thenReturn(MutableStateFlow("idle"))
        `when`(zephyrBLEClient.sampleCount).thenReturn(MutableStateFlow(0))

        `when`(voiceSettingsStore.state).thenReturn(MutableStateFlow(VoiceSettingsStore.VoiceSettings(false, 0.8f, false, "", 5)))

        viewModel = SensorViewModel(
            application,
            sensorCollector,
            gattServerManager,
            edgeImpulseManager,
            dataRepository,
            zephyrBLEClient,
            wearOSClient,
            apiKeyStore,
            voiceSettingsStore,
            locationCollector,
            audioRecorder,
            usbSerialClient
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startUnifiedRecording triggers repository and sensors`() = runTest {
        viewModel.startUnifiedRecording(1000L, "test_label", includePhoneSensors = true, includeWear = true, includeZephyr = true)
        
        verify(dataRepository).startMultiRecording()
        verify(sensorCollector).startAll()
        verify(locationCollector).start()
        verify(wearOSClient).startRecording("test_label", 1000L)
    }

    @Test
    fun `stopSensor stops all collectors and server`() = runTest {
        viewModel.stopSensor()
        
        verify(sensorCollector).stop()
        verify(locationCollector).stop()
        verify(gattServerManager).stopServer()
    }
}
