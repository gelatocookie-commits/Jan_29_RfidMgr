package com.gelatocookie.rfidMgr

import android.content.Context
import android.util.Log
import com.zebra.rfid.api3.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class RFIDManager(
    private val context: Context,
    private val callback: RFIDCallback
) : Readers.RFIDReaderEventHandler, RfidEventsListener {

    interface RFIDCallback {
        fun onEmcUpdate(message: String)
        fun onBeforeUpdate(message: String)
        fun onStatusUpdate(message: String)
        fun onAfterUpdate(message: String)
        fun onTagsScanned(tagDB: Map<String, Int>)
        fun onInventoryStarted()
        fun onInventoryStopped()
        fun onTotalCountUpdate(total: Int)
        fun emcUpdate(message: String)
    }

    private lateinit var readers: Readers
    private var readerDevice: ReaderDevice? = null
    private var reader: RFIDReader? = null
    private val tagDB = ConcurrentHashMap<String, Int>(1000)

    @Volatile
    private var isReadInProgress: Boolean = false
    private val TAG = "RFIDManager"

    fun init() {
        Log.d(TAG, "Init")
        try {
            readers = Readers(context, ENUM_TRANSPORT.ALL)
            connect()
        } catch (e: Exception) {
            Log.e(TAG, "Init Error", e)
        }
    }

    private fun connect() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                callback.onStatusUpdate("Finding Reader...")
                val availableReaders = readers.GetAvailableRFIDReaderList()

                if (availableReaders.isNullOrEmpty()) {
                    callback.onStatusUpdate("No Readers Found")
                    return@launch
                }

                Log.d(TAG, "Found ${availableReaders.size} Readers")
                
                var serialNumbers = ""
                var selectedDevice: ReaderDevice? = null

                availableReaders.forEachIndexed { index, device ->
                    Log.d(TAG, "Reader [$index]: Name=${device.name}, SN=${device.serialNumber}")
                    serialNumbers += "${device.serialNumber}\n"

                    val isZebraDevice = device.name.startsWith("RFID") || device.name.startsWith("TC")
                    if (isZebraDevice) {
                        callback.emcUpdate("EMC: ${device.name}\nAPI SN: ${device.serialNumber}")
                    } else {
                        callback.emcUpdate("External RFD40 RFID API\nSN= ${device.serialNumber}")
                    }
                    
                    selectedDevice = device
                }

                callback.onBeforeUpdate(serialNumbers.trim())

                selectedDevice?.let { device ->
                    readerDevice = device
                    Readers.attach(this@RFIDManager)
                    reader = device.rfidReader
                    
                    Log.d(TAG, "Attempting to connect to: ${device.name}")
                    
                    reader?.let { r ->
                        if (!r.isConnected) {
                            val statusJob = launch {
                                for (i in 1..10) {
                                    callback.onStatusUpdate("Connecting... (${i}s)")
                                    delay(1000)
                                }
                            }
                            
                            try {
                                r.connect()
                            } finally {
                                statusJob.cancel()
                            }
                            
                            configureReader()
                            
                            val radioSerial = r.ReaderCapabilities.serialNumber
                            Log.d(TAG, "Connected. Radio SN: $radioSerial")
                            
                            callback.onAfterUpdate(radioSerial)
                            callback.onStatusUpdate("RFID Connected:\nHost: ${r.hostName}")
                        }
                    }
                }
            } catch (e: OperationFailureException) {
                callback.onStatusUpdate("Error: ${e.statusDescription}")
            }
            catch (e: Exception) {
                Log.e(TAG, "Connect error", e)
                callback.onStatusUpdate("Error: ${e.message}")
            }
        }
    }

    private fun configureReader() {
        try {
            reader?.let { r ->
                if (r.isConnected) {
                    r.Events?.apply {
                        addEventsListener(this@RFIDManager)
                        setHandheldEvent(true)
                        setTagReadEvent(true)
                        setInventoryStartEvent(true)
                        setInventoryStopEvent(true)
                        setOperationEndSummaryEvent(true)
                    }
                    isReadInProgress = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Config Error", e)
        }
    }

    fun startInventory() {
        try {
            tagDB.clear()
            reader?.Actions?.Inventory?.perform()
        } catch (e: Exception) {
            Log.e(TAG, "Start Error", e)
        }
    }

    fun stopInventory() {
        try {
            if (reader?.isConnected == true) {
                reader?.Actions?.Inventory?.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop Error", e)
        }
    }

    override fun eventStatusNotify(rfidStatusEvents: RfidStatusEvents?) {
        val eventData = rfidStatusEvents?.StatusEventData ?: return
        when (eventData.statusEventType) {
            STATUS_EVENT_TYPE.INVENTORY_START_EVENT -> {
                isReadInProgress = true
                callback.onInventoryStarted()
            }
            STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT -> {
                isReadInProgress = false
                callback.onInventoryStopped()
                callback.onTagsScanned(tagDB.toMap())
            }
            STATUS_EVENT_TYPE.OPERATION_END_SUMMARY_EVENT -> {
                val total = eventData.OperationEndSummaryData?.totalTags ?: 0
                callback.onTotalCountUpdate(total)
            }
            STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT -> {
                val trigger = eventData.HandheldTriggerEventData.handheldEvent
                if (trigger == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                    if (isReadInProgress) {
                        callback.onStatusUpdate("Scanner busy, try again.")
                    } else {
                        startInventory()
                    }
                } else {
                    stopInventory()
                }
            }
            STATUS_EVENT_TYPE.DISCONNECTION_EVENT -> {
                callback.onStatusUpdate("Disconnected: Reader connection lost")
            }
            else -> {}
        }
    }

    override fun eventReadNotify(rfidReadEvents: RfidReadEvents?) {
        val scannedTags = reader?.Actions?.getReadTags(100)
        scannedTags?.forEach { tag ->
            tag.tagID?.let { id ->
                val currentCount = tagDB[id] ?: 0
                tagDB[id] = currentCount + tag.tagSeenCount
            }
        }
        callback.onTagsScanned(tagDB.toMap())
    }

    fun dispose() {
        Log.d(TAG, "Dispose")
        try {
            reader?.let { r ->
                if (r.isConnected) r.disconnect()
                r.Events?.removeEventsListener(this)
            }
            Readers.deattach(this)
        } catch (e: Exception) {
            Log.e(TAG, "Dispose Error", e)
        }
    }

    override fun RFIDReaderAppeared(device: ReaderDevice?) {
        Log.d(TAG, "Reader Appeared: ${device?.name}")
        callback.onStatusUpdate("Disconnected: Reader Disappeared")
    }

    override fun RFIDReaderDisappeared(device: ReaderDevice?) {
        Log.d(TAG, "Reader Disappeared: ${device?.name}")
        callback.onStatusUpdate("Disconnected: Reader Disappeared")
    }
}
