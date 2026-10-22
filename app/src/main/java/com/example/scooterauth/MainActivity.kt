package com.example.scooterauth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Приложение только для теста: сканирует BLE, подключается к выбранному
 * устройству, показывает все сервисы/характеристики, подписывается на
 * уведомления и умеет как автоматически, так и вручную отвечать тем
 * хэшем, который считает KugooCrypto (твой алгоритм, портированный 1:1).
 *
 * UUID сервисов/характеристик конкретно твоей модели самоката неизвестны,
 * поэтому приложение не "зашивает" их, а показывает всё, что отдаёт GATT,
 * и даёт тебе самому выбрать нужную характеристику для записи, либо делает
 * это эвристически (первая writable-характеристика в том же сервисе, что
 * и notify, из которой пришёл "+PM>").
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Реальные UUID из декомпилированного LFBluetootService.java (kugookirin / kugoo).
        // Индекс в этой карте == "mode" в KugooCrypto, т.к. именно так была
        // устроена CONNECT_TYPE в оригинальном приложении.
        data class KnownService(val mode: Int, val label: String, val service: UUID, val write: UUID, val read: UUID)

        val KNOWN_SERVICES = listOf(
            KnownService(1, "default", UUID.fromString("9546bdc0-60ba-11e9-8647-0002a5d5c51b"),
                UUID.fromString("9546bdc1-60ba-11e9-8647-0002a5d5c51b"),
                UUID.fromString("9546bdc4-60ba-11e9-8647-0002a5d5c51b")),
            KnownService(2, "kugoo", UUID.fromString("b1b99ca0-98e7-11e9-80fe-0002a5d5c51b"),
                UUID.fromString("b1b99ca1-98e7-11e9-80fe-0002a5d5c51b"),
                UUID.fromString("b1b99ca4-98e7-11e9-80fe-0002a5d5c51b")),
            KnownService(3, "fmi", UUID.fromString("331023c0-fdcb-11e8-8eb2-0002a5d5c51b"),
                UUID.fromString("331023c1-fdcb-11e8-8eb2-0002a5d5c51b"),
                UUID.fromString("331023c4-fdcb-11e8-8eb2-0002a5d5c51b")),
        )
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var listDevices: ListView
    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnSend: Button
    private lateinit var btnSendCode: Button
    private lateinit var btnHandshake: Button
    private lateinit var editManual: EditText
    private lateinit var radioMode: RadioGroup
    private lateinit var checkAutoReply: CheckBox

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }
    private val scanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    private val scanResults = LinkedHashMap<String, BluetoothDevice>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var gatt: BluetoothGatt? = null
    private var scanning = false

    // Writable характеристики по serviceUuid -> список характеристик, для ручного выбора
    private val writableChars = mutableListOf<BluetoothGattCharacteristic>()
    private var selectedWriteChar: BluetoothGattCharacteristic? = null

    // Заполняется, если среди сервисов самоката найден один из известных (KNOWN_SERVICES):
    // тогда mode для KugooCrypto берётся отсюда автоматически, а не из радио-кнопок.
    private var detectedMode: Int? = null
    private var detectedLabel: String? = null
    private var detectedWriteChar: BluetoothGattCharacteristic? = null
    private var detectedReadChar: BluetoothGattCharacteristic? = null

    // Полный протокол взаимного рукопожатия (+VER/+PM/+PA), см. KugooAuth.kt
    private val auth: KugooAuth by lazy {
        KugooAuth(
            mode = { selectedMode() },
            sender = { text -> sendAuthString(text) },
            log = { msg -> log(msg) }
        )
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val permissionsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            startScan()
        } else {
            log("Нет нужных разрешений (Bluetooth/Location).")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        listDevices = findViewById(R.id.listDevices)
        btnScan = findViewById(R.id.btnScan)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnSend = findViewById(R.id.btnSend)
        btnSendCode = findViewById(R.id.btnSendCode)
        btnHandshake = findViewById(R.id.btnHandshake)
        editManual = findViewById(R.id.editManual)
        radioMode = findViewById(R.id.radioMode)
        checkAutoReply = findViewById(R.id.checkAutoReply)

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listDevices.adapter = deviceAdapter

        btnScan.setOnClickListener { requestPermissionsAndScan() }
        btnDisconnect.setOnClickListener { disconnectGatt() }
        btnSend.setOnClickListener { sendManual() }
        btnSendCode.setOnClickListener { sendCode() }
        btnHandshake.setOnClickListener { startHandshake() }

        listDevices.setOnItemClickListener { _, _, position, _ ->
            val name = deviceAdapter.getItem(position) ?: return@setOnItemClickListener
            val address = name.substringAfterLast("(").removeSuffix(")")
            val device = scanResults[address] ?: return@setOnItemClickListener
            stopScan()
            connect(device)
        }
    }

    // Если среди сервисов самоката нашёлся один из известных (KNOWN_SERVICES),
    // используем его mode автоматически; иначе — то, что выбрано вручную в радио-кнопках.
    private fun selectedMode(): Int = detectedMode ?: when (radioMode.checkedRadioButtonId) {
        R.id.radioMode2 -> 2
        R.id.radioMode3 -> 3
        else -> 1
    }

    // ---------- Разрешения и сканирование ----------

    private fun requestPermissionsAndScan() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = needed.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startScan()
        } else {
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!bluetoothAdapter.isEnabled) {
            log("Bluetooth выключен. Включи его и попробуй снова.")
            return
        }
        scanResults.clear()
        deviceAdapter.clear()
        scanning = true
        tvStatus.text = "Статус: сканирование..."
        log("Сканирование запущено")
        scanner.startScan(scanCallback)
        handler.postDelayed({ stopScan() }, 12_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (scanning) {
            scanner.stopScan(scanCallback)
            scanning = false
            tvStatus.text = "Статус: сканирование остановлено (${scanResults.size} устройств)"
            log("Сканирование остановлено")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            if (!scanResults.containsKey(address)) {
                scanResults[address] = device
                val name = try { device.name ?: "(без имени)" } catch (e: SecurityException) { "(без имени)" }
                deviceAdapter.add("$name  rssi=${result.rssi}  ($address)")
                deviceAdapter.notifyDataSetChanged()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Скан не удался, код $errorCode")
        }
    }

    // ---------- Подключение и GATT ----------

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        log("Подключаюсь к ${device.address}...")
        tvStatus.text = "Статус: подключение к ${device.address}"
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writableChars.clear()
        selectedWriteChar = null
        detectedMode = null
        detectedLabel = null
        detectedWriteChar = null
        detectedReadChar = null
        auth.reset()
        btnDisconnect.isEnabled = false
        tvStatus.text = "Статус: отключено"
        log("Отключено вручную")
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    runOnUiThread {
                        tvStatus.text = "Статус: подключено, ищу сервисы..."
                        btnDisconnect.isEnabled = true
                    }
                    log("Connected (status=$status). Discovering services...")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    runOnUiThread {
                        tvStatus.text = "Статус: соединение разорвано (status=$status)"
                        btnDisconnect.isEnabled = false
                    }
                    log("Disconnected (status=$status)")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("discoverServices failed: $status")
                return
            }
            writableChars.clear()
            detectedMode = null
            detectedLabel = null
            detectedWriteChar = null
            detectedReadChar = null
            log("Найдено сервисов: ${g.services.size}")
            for (service in g.services) {
                log("Service ${service.uuid}")
                for (ch in service.characteristics) {
                    val props = describeProps(ch.properties)
                    log("  Char ${ch.uuid}  [$props]")
                    if (ch.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                        writableChars.add(ch)
                    }
                    if (ch.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        enableNotify(g, ch)
                    }

                    // Совпадение с известным протоколом kugookirin (см. LFBluetootService.java)
                    val known = KNOWN_SERVICES.firstOrNull { it.write == ch.uuid }
                    if (known != null) {
                        detectedMode = known.mode
                        detectedLabel = known.label
                        detectedWriteChar = ch
                        log("  -> Распознан известный протокол '${known.label}', mode=${known.mode} (write-характеристика)")
                    }
                    val knownRead = KNOWN_SERVICES.firstOrNull { it.read == ch.uuid }
                    if (knownRead != null) {
                        detectedReadChar = ch
                        log("  -> Распознана известная read/notify-характеристика '${knownRead.label}'")
                    }
                }
            }
            runOnUiThread {
                val modeInfo = if (detectedMode != null) ", распознан протокол '$detectedLabel' (mode=$detectedMode)" else ""
                tvStatus.text = "Статус: подключено, сервисов ${g.services.size}, writable=${writableChars.size}$modeInfo"
                // Приоритет: известная write-характеристика -> иначе первая попавшаяся writable.
                selectedWriteChar = detectedWriteChar ?: writableChars.firstOrNull()
                if (selectedWriteChar != null) {
                    log("Для записи выбрана характеристика: ${selectedWriteChar?.uuid}")
                }
                if (checkAutoReply.isChecked && selectedWriteChar != null) {
                    handler.postDelayed({ startHandshake() }, 500)
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (cccd != null) {
                val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, value)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
        }

        @Deprecated("Deprecated in API33, handled below for older APIs")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleIncoming(g, characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncoming(g, characteristic, value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            log("Write to ${characteristic.uuid} -> status=$status")
        }
    }

    private fun describeProps(props: Int): String {
        val parts = mutableListOf<String>()
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts += "READ"
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts += "WRITE"
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts += "WRITE_NR"
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts += "NOTIFY"
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts += "INDICATE"
        return parts.joinToString(",")
    }

    private fun handleIncoming(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val hex = value.joinToString(" ") { String.format("%02X", it) }
        // Полный текст без фильтрации непечатных символов — у протокола фиксированная
        // длина строк (например "+PM>" + 12 hex = ровно 16 символов), это важно для сверки.
        val rawText = value.toString(Charsets.UTF_8).trimEnd('\u0000')
        log("<< ${characteristic.uuid}  hex=[$hex]  text=\"$rawText\"")

        when (rawText.trim().uppercase()) {
            "CODE_OK" -> log("Самокат принял пароль (CODE_OK). Можно слать команды, например GETDEVID.")
            "CODE_NG" -> log("Самокат отклонил пароль (CODE_NG). Попробуй другой код в поле ниже.")
        }

        if (checkAutoReply.isChecked) {
            auth.onMessage(rawText)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendAuthString(text: String) {
        val g = gatt
        val target = detectedWriteChar ?: selectedWriteChar ?: writableChars.firstOrNull()
        if (g == null || target == null) {
            log("Не могу отправить \"$text\": нет подключения или writable-характеристики.")
            return
        }
        writeBytes(g, target, text.toByteArray(Charsets.ISO_8859_1))
    }

    /** Запускает полное рукопожатие +VER/+PM/+PA с нуля. */
    private fun startHandshake() {
        if (gatt == null) {
            log("Сначала подключись к устройству.")
            return
        }
        auth.reset()
        auth.start()
    }

    @SuppressLint("MissingPermission")
    private fun writeBytes(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray) {
        log(">> ${ch.uuid}  \"${bytes.toString(Charsets.ISO_8859_1)}\"  hex=[${bytes.joinToString(" ") { String.format("%02X", it) }}]")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    // ---------- Ручная отправка ----------

    @SuppressLint("MissingPermission")
    private fun sendManual() {
        val g = gatt
        if (g == null) {
            log("Сначала подключись к устройству.")
            return
        }
        val target = selectedWriteChar ?: writableChars.firstOrNull()
        if (target == null) {
            log("Нет writable-характеристики.")
            return
        }
        val input = editManual.text.toString().trim()
        if (input.isEmpty()) return

        // Если ввели ровно 12 hex-символов — считаем это nonce и автоматически считаем ответ.
        val isHexNonce = input.length == 12 && input.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }
        if (isHexNonce) {
            val mode = selectedMode()
            val reply = KugooCrypto.encryptToken(input.uppercase(), mode)
            log("Ручной nonce=$input, mode=$mode -> ответ=$reply")
            writeBytes(g, target, "+PM<$reply".toByteArray(Charsets.ISO_8859_1))
        } else {
            // иначе отправляем как есть (текстом)
            writeBytes(g, target, input.toByteArray(Charsets.ISO_8859_1))
        }
    }

    /**
     * Отдельный, независимый от +PM канал: оригинальное приложение шлёт текстом
     * "CODE=<пароль>" (или "CODE=000000" для одного из вариантов протокола) и ждёт
     * в ответ "CODE_OK"/"CODE_NG". Бери пароль из поля ручной отправки.
     */
    @SuppressLint("MissingPermission")
    private fun sendCode() {
        val g = gatt
        val target = detectedWriteChar ?: selectedWriteChar ?: writableChars.firstOrNull()
        if (g == null || target == null) {
            log("Нет подключения или writable-характеристики.")
            return
        }
        val code = editManual.text.toString().trim().ifEmpty { "000000" }
        writeBytes(g, target, "CODE=$code".toByteArray(Charsets.ISO_8859_1))
    }

    // ---------- Логирование ----------

    private fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        Log.d("ScooterAuth", line)
        runOnUiThread {
            tvLog.append(line + "\n")
        }
    }
}
