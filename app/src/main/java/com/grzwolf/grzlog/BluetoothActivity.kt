package com.grzwolf.grzlog

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ToggleButton
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.grzwolf.grzlog.DataStore.ACTION
import com.grzwolf.grzlog.DataStore.TIMESTAMP
import com.grzwolf.grzlog.MainActivity.Companion.ds
import com.grzwolf.grzlog.MainActivity.Companion.lvMain
import com.grzwolf.grzlog.MainActivity.Companion.showMenuItemUndo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayList
import java.util.UUID
import kotlin.text.isNotEmpty

class BluetoothActivity : AppCompatActivity() {

    // supposed to make sure to return to MainActivity -->
    companion object {
        fun newInstance(context: Context) = Intent(context, BluetoothActivity::class.java)
    }

    // constants passed to startActivityForResult()
    val REQUEST_ENABLE_BT = 1001
    val REQUEST_DISABLE_BT = 1002

    override fun onStart() {
        super.onStart()
    }

    override fun onDestroy() {
        btOnOffButton.isChecked = false
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
    }

    // have some margin to the left in landscape mode
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setMargins(newConfig.orientation)
    }

    // margin insets correction
    fun setMargins(orientation: Int) {
        // revert API36 insets for API < 35
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val view = findViewById(R.id.main_view) as LinearLayout
            view.margin(top = 0F)
            view.margin(left = 0F)
        }
        // insets API36
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                val view = findViewById(R.id.main_view) as LinearLayout
                view.margin(top = 120F)
                view.margin(left = 50F)
            } else {
                if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                    val view = findViewById(R.id.main_view) as LinearLayout
                    view.margin(top = 120F)
                    view.margin(left = 0F)
                }
            }
        }
    }

    // build a menu bar with options
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // back to MainActivity
        if (item.itemId == android.R.id.home) {
            onBackPressed()
        }
        return super.onOptionsItemSelected(item)
    }

    // Android back button & gesture detection
    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // so far empty return data to give back to MainActivity
            setResult(RESULT_OK, Intent())
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // BT should have been set to enable
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode != -1) {
                centeredToast(this, getString(R.string.bluetooth_was_left_in_status_off), 3000)
                // switch back to status off
                btOnOffButton.isChecked = false
            } else {
                // give green light
                btOnOffButton.setBackgroundColor(getResources().getColor(R.color.lightgreen))
                // enable button to connect to a BT remote device
                btCheckBoxVisibilty.isEnabled = true
                btSelectRemoteButton.isEnabled = true
                btSelectRemoteButton.setBackgroundColor(getResources().getColor(R.color.lightskyblue))
                // note
                centeredToast(this, getString(R.string.bluetooth_is_turned_on), 3000)
            }
        }
        // BT should have been set to disable
        if (requestCode == REQUEST_DISABLE_BT) {
            if (resultCode != -1) {
                centeredToast(this, getString(R.string.bluetooth_was_left_in_status_on), 3000)
            } else {
                centeredToast(this, getString(R.string.bluetooth_is_turned_off), 3000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.bluetooth_activity)

        // action bar to go back to MainActivity
        val actionBar = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)

        // activity title
        title = getString(R.string.grzlog_synchronization)

        // make volatile data static
        btContext = applicationContext
        btActivity = this

        // make UI views static --> all are declared in companion object
        btCheckAutoConnect = findViewById(R.id.checkbox_auto_connect)
        btOnOffButton = findViewById(R.id.toggle_onoff)
        btCheckBoxVisibilty = findViewById(R.id.checkbox_device_visibility)
        btStatusTv = findViewById(R.id.textview_connection_status)
        btSelectRemoteButton = findViewById(R.id.button_select_remote)
        btSelectedRemoteTv = findViewById(R.id.textview_remote_device)
        btRequestFileButton = findViewById(R.id.button_request_file)
        btRequestFileStatusTv = findViewById(R.id.textview_request_file)
        btSendFileButton = findViewById(R.id.button_send_file)
        btSendMsgButton = findViewById(R.id.button_send_text)
        btSendMsgEt = findViewById(R.id.editText_send_text)

        // handle auto connect to last saved BT device
        var executeAutoConnectBluetooth = false
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        selectedBluetoothDeviceIndex = sharedPref.getInt("BluetoothAutoConnectDeviceIndex", -1)
        if (selectedBluetoothDeviceIndex != -1) {
            // check BEFORE btCheckAutoConnect.setOnCheckedChangeListener to avoid click listener
            btCheckAutoConnect.isChecked = true
            executeAutoConnectBluetooth = true
        }

        //
        // UI click handlers
        //
        // auto connect to BT device
        btCheckAutoConnect.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
                // error: connection is not active OR no pairedDevices OR invalid device selection
                if (btOnOffButton.isChecked == false || pairedDevices!!.isEmpty() || selectedBluetoothDeviceIndex == -1) {
                    if (buttonView.isPressed) {
                        btCheckAutoConnect.isChecked = false
                    }
                    return@setOnCheckedChangeListener
                }
                // error: selected device index not contained in paired list
                if (selectedBluetoothDeviceIndex > pairedDevices.size) {
                    if (buttonView.isPressed) {
                        btCheckAutoConnect.isChecked = false
                    }
                    return@setOnCheckedChangeListener
                }
                // save preference
                val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
                var spe = sharedPref.edit()
                spe.putInt("BluetoothAutoConnectDeviceIndex", selectedBluetoothDeviceIndex)
                spe.apply()
            } else {
                // remove preference
                val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
                var spe = sharedPref.edit()
                spe.putInt("BluetoothAutoConnectDeviceIndex", -1)
                spe.apply()
            }
        }

        // sync ON / OFF
        btOnOffButton.setOnCheckedChangeListener { buttonView, isChecked ->
            btOnOffButtonClick(isChecked)
        }

        // this device BT visibility
        btCheckBoxVisibilty.setOnCheckedChangeListener { buttonView, isChecked ->
            // sanity check
            if (isChecked && btOnOffButton.isChecked == false) {
                btCheckBoxVisibilty.isChecked = false
                return@setOnCheckedChangeListener
            }
            // register BroadcastReceiver
            if (isChecked) {
                registerReceiverForBluetoothDevices()
            }
            // start or stop the BT beacon for discovery by other BT devices
            requestBluetoothBeacon(isChecked)
        }
        btCheckBoxVisibilty.setOnClickListener(View.OnClickListener { view ->
            if (btCheckBoxVisibilty.isChecked) {
                manualVisibiltyStart = true
            }
        })

        // select a remote BT device
        btSelectRemoteButton.setOnClickListener(View.OnClickListener { view ->
            btSelectRemoteButtonClick()
        })

        // request a folder from remote BT device
        btRequestFileButton.setOnClickListener(View.OnClickListener { view ->
            btRequestFileButtonClick()
        })

        // send a folder to a remote BT device
        btSendFileButton.setOnClickListener(View.OnClickListener { view ->
            btSendFileButtonClick()
        })

        // send message
        btSendMsgButton.setOnClickListener(View.OnClickListener { view ->
            btSendMsgButtonClick()
        })

        // after requesting a folder from remote, a message should pop up here
        btRequestFileStatusTv.addTextChangedListener(textWatcher)

        // initially all buttons are disabled
        if (btCheckAutoConnect.isChecked == false) {
            btCheckAutoConnect.isEnabled = false
        }
        btCheckBoxVisibilty.isEnabled = false
        btSelectRemoteButton.isEnabled = false
        btRequestFileButton.isEnabled = false
        btSendFileButton.isEnabled = false
        btSendMsgButton.isEnabled = false

        // setup BT infra for permissions, BT on, adapter etc.
        bluetoothManager = this.getSystemService(BluetoothManager::class.java)
        bluetoothManager?.getAdapter().also { bluetoothAdapter = it }

        // status of BT before we jump in
        try {
            if (bluetoothAdapter != null) {
                btAdapterIsEnabledOriginal = bluetoothAdapter!!.isEnabled
            }
        } catch (_: Exception) {
            // ok to leave unhandled
        }

        // adjust margins
        setMargins(this.getResources().getConfiguration().orientation)

        // adding onBackPressed callback listener
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        // exec auto Bluetooth connect
        if (executeAutoConnectBluetooth) {
            // enable Bluetooth sync
            btOnOffButton.isChecked = true
            // start connect thread
            val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
            if (pairedDevices!!.isEmpty() || selectedBluetoothDeviceIndex > pairedDevices!!.size || selectedBluetoothDeviceIndex == -1) {
                btCheckAutoConnect.isChecked = false
                return
            }
            val connectThread = ConnectThread(pairedDevices!!.elementAt(selectedBluetoothDeviceIndex))
            connectThread.start()
        }

    }

    // BT on off button click implementation
    fun btOnOffButtonClick(isChecked: Boolean) {
        // the current status is checked, so the switch was turned ON
        if (isChecked) {
            if (bluetoothAdapter == null) {
                // leave note, if device doesn't support Bluetooth
                okBox(
                    this,
                    getString(R.string.note),
                    "No Bluetooth available",
                    {
                        btServiceWorkThreadIsInitialized = false
                        btOnOffButton.isChecked = false
                    }
                )
                return
            } else {
                if (bluetoothAdapter?.isEnabled == false) {
                    // if bt is not turned on
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        // request permissions includes to turn BT on if allowed
                        requestBluetoothPermissions()
                        // need to continue in onActivityResult after BT on
                        return
                    } else {
                        // if permissions are granted, so turn bluetooth on
                        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
                        // need to continue in onActivityResult after BT on
                        return
                    }
                } else {
                    // request permissions includes to turn BT on if allowed
                    requestBluetoothPermissions()
                    // need to continue in onActivityResult after permissions granted
                    return
                }
            }
        } else {
            // stop BT server discovery, aka stop BT beacon
            requestBluetoothBeacon(false)
            // stop connection to remote BT device
            if (btServiceWorkThreadIsInitialized == true) {
                try {
                    val name = bluetoothAdapter?.name
                    // !! leave as not translated string !! bc. it is used as a control word
                    val msg = name + ": Remote BT device closed connection"
                    btServiceWorkThread.write(msg.toByteArray(Charsets.UTF_8))
                    btServiceWorkThread.cancel()
                    btServiceWorkThreadIsInitialized = false
                } catch (e: Exception) {
                    // ok to leave unhandled
                }
            }
            btSelectedRemoteTv.text = getString(R.string.nothing)
            btStatusTv.text = getString(R.string.nothing)
            btCheckBoxVisibilty.isEnabled = false
            btSelectRemoteButton.isEnabled = false
            btSelectRemoteButton.setBackgroundColor(getResources().getColor(R.color.lightskyblue))
            btRequestFileButton.isEnabled = false
            btSendFileButton.isEnabled = false
            btSendMsgButton.isEnabled = false
            btOnOffButton.setBackgroundColor(getResources().getColor(R.color.lightskyblue))
            // if BT was originally OFF, turn it OFF here to re-establish such original state
            if (btAdapterIsEnabledOriginal == false) {
                bluetoothAdapter?.disable()
                val btIntent = Intent("android.bluetooth.adapter.action.REQUEST_DISABLE")
                startActivityForResult(btIntent, REQUEST_DISABLE_BT)
            }
        }
    }

    // select a remote BT device
    fun btSelectRemoteButtonClick() {
        try {
            // get already paired devices
            val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
            if (pairedDevices!!.isNotEmpty()) {
                //
                // begin with already paired BT devices if they exist
                //
                val deviceArray: MutableList<String> = ArrayList()
                pairedDevices.forEach { device ->
                    deviceArray.add(device.name)
                }
                // select one already paired BT device and continue or quit
                var tmpSelectionNdx = 0
                var selectBuilder: AlertDialog.Builder?
                selectBuilder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                selectBuilder.setTitle(this.getString(R.string.select_bt_device))
                selectBuilder.setSingleChoiceItems(
                    deviceArray.toTypedArray(),
                    tmpSelectionNdx,
                    DialogInterface.OnClickListener { dialog, which ->
                        tmpSelectionNdx = which
                    })
                // select paired BT device OK
                selectBuilder.setPositiveButton(
                    getString(R.string.ok),
                    DialogInterface.OnClickListener { dialog, which ->
                        for (i: Int in pairedDevices.indices) {
                            if (i == tmpSelectionNdx) {
                                // index of item with "name of mobile phone" as shown in 'About Phone'
                                selectedBluetoothDeviceIndex = i
                                val connectThread = ConnectThread(pairedDevices.elementAt(i))
                                // start connect thread
                                connectThread.start()
                                break
                            }
                        }
                    }
                )
                // select paired BT device NEUTRAL
                // --> user didn't choose a paired BT device, so he wants to look for another remote BT devices
                selectBuilder.setNeutralButton(
                    "Pair a new remote device",
                    DialogInterface.OnClickListener { dlg, which ->
                        selectedBluetoothDeviceIndex = -1
                        // pair with a new remote device
                        pairNewDevice()
                        // just get out
                        return@OnClickListener
                    }
                )
                // select paired BT device CANCEL
                // --> user didn't choose a paired BT device, so he wants to look for another remote BT devices
                selectBuilder.setNegativeButton(
                    R.string.cancel,
                    DialogInterface.OnClickListener { dlg, which ->
                        selectedBluetoothDeviceIndex = -1
                        // just get out
                        return@OnClickListener
                    }
                )
                // select paired BT device show
                val dlg = selectBuilder.create()
                dlg.setOnShowListener {
                    dlg.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                }
                dlg.show()
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setAllCaps(false)
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setAllCaps(false)
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setAllCaps(false)
                dlg.setCanceledOnTouchOutside(false)
            } else {
                //
                // no existing paired BT devices so far
                //
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    selectedBluetoothDeviceIndex = -1
                    // goes the full cycle:
                    //    start broadcast receiver
                    //    request making remote device visible
                    //    make local device visible
                    pairNewDevice()
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        PERMISSION_REQUEST_LOCATION)
                    okBox(this, getString(R.string.note), getString(R.string.tryAgain))
                }
            }
        } catch (e: Exception) {
            centeredToast(this,
                getString(R.string.bt_select_remote_error) + e.message.toString(), 3000)
        }
    }

    // request a folder from remote: the textWatcher informs about the result
    fun btRequestFileButtonClick() {
        // ask remote to send a folder ::::REQUEST_FOLDER::::
        btServiceWorkThread.transmitFolderName = MainActivity.ds.namesSection[MainActivity.ds.selectedSection]
        btSendMsgButtonClick("REQUEST_FOLDER::::" + btServiceWorkThread.transmitFolderName)
        // set workflow control vars in btServiceWorkThread
        btServiceWorkThread.requestFolder = true
        btServiceWorkThread.transmitFolder = false
        btServiceWorkThread.receiveFileSize = true
        btServiceWorkThread.receiveFile = false
        // update status
        btRequestFileStatusTv.text = getString(R.string.waiting_for_folder)
        // hold on for now
        btSendFileButton.isEnabled = false
        btRequestFileButton.isEnabled = false
    }

    // send a folder to remote
    fun btSendFileButtonClick() {
        // tell remote to expect to receive a folder  send ::::TRANSMIT_FOLDER::::
        btSendMsgButtonClick("TRANSMIT_FOLDER::::" + MainActivity.ds.namesSection[MainActivity.ds.selectedSection])
        // set workflow control vars in btServiceWorkThread
        btServiceWorkThread.requestFolder = false
        btServiceWorkThread.transmitFolder = true
        btServiceWorkThread.receiveFileSize = false
        btServiceWorkThread.receiveFile = false
        // hold on for now
        btSendFileButton.isEnabled = false
        btRequestFileButton.isEnabled = false
    }

    // textWatcher observes messages sent from btServiceWorkThread to UI
    var textWatcher: TextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {

            // after a folder is received, the receiving user has to make a decision about further data handling
            if (s!!.startsWith("::::FINAL_HANDLING::::")) {

                // kudos: https://stackoverflow.com/questions/6354833/how-to-change-textview-text-on-datachange-without-calling-back-a-textwatcher-lis
                btRequestFileStatusTv.removeTextChangedListener(textWatcher)
                val newText = s.substring(("::::FINAL_HANDLING::::").length)
                btRequestFileStatusTv.text = newText
                btRequestFileStatusTv.addTextChangedListener(textWatcher)

                // get received folder name
                var remoteFolderName = "<?>"
                val split = newText.split("'")
                if (split.size > 1) {
                    remoteFolderName = split[1]
                }

                // get active folder name on local device
                val localFolderName = MainActivity.ds.namesSection[MainActivity.ds.selectedSection]

                // check: keep folder OR switch folder OR create a new one
                var localFolderIndex = -1
                var createNewLocalFolder = false
                var decisionMessage = ""
                if (remoteFolderName.equals(localFolderName)) {

                    // check if existing local folder is identical to the received folder
                    val textOri = MainActivity.ds.dataSection[MainActivity.ds.selectedSection]
                    val fileName = File(MainActivity.appStoragePath, "test.txt")
                    val textNew = fileName.readText(Charsets.UTF_8)
                    if (textNew.equals(textOri)) {
                        // nothing to do
                        okBox(
                            this@BluetoothActivity,
                            getString(R.string.note),
                            getString(R.string.folders_plus) + localFolderName + getString(R.string.received_and_local_are_identical_there_s_nothing_to_do),
                            {
                                // cancel all file ops
                                btServiceWorkThread.requestFolder = false
                                btServiceWorkThread.transmitFolder = false
                                btServiceWorkThread.receiveFileSize = false
                                btServiceWorkThread.receiveFile = false
                                btServiceWorkThread.transmitFolderName = ""
                                btRequestFileStatusTv.text = getString(R.string.remote_is_done_with_folder)
                                btSendFileButton.isEnabled = true
                                btRequestFileButton.isEnabled = true
                                btSendMsgButton.isEnabled = true
                                // send transmission done
                                btServiceWorkThread.write(
                                    ("::::FILE_TRANSMISSION_DONE::::".toByteArray(
                                        Charsets.UTF_8
                                    ))
                                )
                                return@okBox
                            })
                        return
                    }

                    // remote folder name equals local active folder name --> override on MainActivity.ds.selectedSection
                    localFolderIndex = MainActivity.ds.selectedSection
                    decisionMessage = buildString {
                        append(getString(R.string.the_received_folder))
                        append("'$remoteFolderName' ")
                        append(getString(R.string.will_overwrite_the_local_folder))
                        append(MainActivity.ds.namesSection[localFolderIndex])
                        append("'")}
                } else {
                    // switch local folder index
                    for (i: Int in MainActivity.ds.namesSection.indices) {
                        if (remoteFolderName.equals(MainActivity.ds.namesSection[i])) {
                            // folder is found on local device --> override on localFolderIndex
                            localFolderIndex = i
                            decisionMessage = buildString {
                                append(getString(R.string.the_received_folder))
                                append("'$remoteFolderName' ")
                                append(getString(R.string.will_overwrite_the_local_folder))
                                append(MainActivity.ds.namesSection[localFolderIndex])
                                append("'")}
                            break
                        }
                    }
                    // create a new local folder, if remote folder name does not exist on local device
                    if (localFolderIndex == -1) {
                        createNewLocalFolder = true
                        decisionMessage = buildString {
                            append(getString(R.string.the_received_folder))
                            append("'$remoteFolderName' ")
                            append(getString(R.string.will_create_the_new_local_folder))
                            append("'$remoteFolderName' ")}
                    }
                }
                //
                // file transfer was successful, now finalize the job
                //
                decisionBox(
                    this@BluetoothActivity,
                    DECISION.YESNO,
                    getString(R.string.note),
                    decisionMessage,
                    // runner OK
                    {
                        val fileName = File(MainActivity.appStoragePath, "test.txt")
                        val textNew = fileName.readText(Charsets.UTF_8)
                        /// there are two paths to follow
                        if (createNewLocalFolder) {
                            // create the new local folder with the received folder data
                            MainActivity.ds.dataSection.add(textNew)
                            // add a folder name
                            ds.namesSection.add(remoteFolderName)
                            // set folder index to the created folder, which is initially the last entry in the list
                            ds.selectedSection = ds.namesSection.size - 1
                            ds.timeSection.add(ds.selectedSection, TIMESTAMP.OFF) // no timestamp setting
                            ds.tagSection = mutableListOf(-1, -1)                 // no tags so far
                            // write modified DataStore to disk
                            MainActivity.writeAppData(
                                MainActivity.appStoragePath,
                                MainActivity.ds,
                                MainActivity.appName
                            )
                            MainActivity.deleteAppDataCache(MainActivity.contextMainActivity)
                            // inform MainActivity to reread / reload all data
                            MainActivity.reReadAppFileData = true
                            // success note
                            okBox(
                                this@BluetoothActivity,
                                getString(R.string.note),
                                getString(R.string.received_folder_successfully_created_a_local_clone) + remoteFolderName + "'",
                                {
                                    // send transmission done
                                    btServiceWorkThread.write(("::::FILE_TRANSMISSION_DONE::::".toByteArray(Charsets.UTF_8)))
                                })

                        } else {
                            // save undo data for undo operation
                            ds.undoSection = ds.dataSection[ds.selectedSection]
                            ds.undoText = getString(R.string.edited_items)
                            ds.undoAction = ACTION.REVERTMULTIEDIT
                            MainActivity.returningFromBluetoothActivityShowMenuItemUndo = true
                            // override folder data with the received folder
                            MainActivity.ds.dataSection[localFolderIndex] = textNew
                            // set folder index, it might have changed --> user will see what happened as soon as he is back in MainActivity
                            ds.selectedSection = localFolderIndex
                            // write modified DataStore to disk
                            MainActivity.writeAppData(
                                MainActivity.appStoragePath,
                                MainActivity.ds,
                                MainActivity.appName
                            )
                            MainActivity.deleteAppDataCache(MainActivity.contextMainActivity)
                            // inform MainActivity to reread / reload all data
                            MainActivity.reReadAppFileData = true
                            // success note
                            okBox(
                                this@BluetoothActivity,
                                getString(R.string.note),
                                getString(R.string.received_folder_successfully_overwrote_local_folder) + remoteFolderName + "'",
                                {
                                    // send transmission done
                                    btServiceWorkThread.write(("::::FILE_TRANSMISSION_DONE::::".toByteArray(Charsets.UTF_8)))
                                })
                        }
                    },
                    // runner CANCEL
                    {
                        okBox(
                            this@BluetoothActivity,
                            getString(R.string.note),
                            getString(R.string.folder_sync_was_cancelled_nothing_happened),
                            {
                                // send transmission done
                                btServiceWorkThread.write(("::::FILE_TRANSMISSION_DONE::::".toByteArray(Charsets.UTF_8)))
                            })
                    }
                )
            }
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        }
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        }
    }

    @SuppressLint("MissingPermission")
    fun btSendMsgButtonClick(msg: String = "") {
        if (btServiceWorkThreadIsInitialized == true) {
            try {
                var message = ""
                val name = bluetoothAdapter?.name
                if (msg.isEmpty()) {
                    // any sent out standard message from button will reset the file save workflow
                    btServiceWorkThread.receiveFileSize = false
                    btServiceWorkThread.receiveFile = false
                    message = name + ": " + btSendMsgEt.text
                } else {
                    message = name + "::::" + msg
                }
                btServiceWorkThread.write(message.toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                // ok to leave unhandled
            }
        }
    }

    //
    // pairing of a nwe BT device:
    //   either on request --> select a device --> pair a new device
    //        OR
    //   at enabling sync with no paired device available
    //
    fun pairNewDevice() {
        decisionBox(
            btActivity as Context,
            DECISION.OKCANCEL,
            getString(R.string.pair_a_new_device),
            getString(R.string.on_new_device),
            {
                // permissions check
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    // clear app list of so far collected unpaired bt devices
                    devicesListBlootooth.clear()

                    //
                    // workflow sequence is important:
                    //  1. start BroadcastReceiver (needs to run, before beacons are expected)
                    //  2. requestBluetoothBeacon(true)
                    //  3. start discovery
                    //
                    // register a BroadcastReceiver for BT devices sending a beacon signal
                    //   will show a list of so far unpaired devices
                    registerReceiverForBluetoothDevices()
                    // activate BT beacon for discovery: makes local device visible to other BT devices
                    requestBluetoothBeacon(true)
                    // start BT discovery
                    val started = bluetoothAdapter?.startDiscovery()
                    //
                    // connection work is finally done in BroadcastReceiver
                    //

                    // just in case: error handling
                    if (started!! == false) {
                        centeredToast(this, "BT discovery start error", 3000)
                        unregisterReceiverForBluetoothDevices()
                    }
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        PERMISSION_REQUEST_LOCATION
                    )
                    okBox(this, getString(R.string.note), getString(R.string.tryAgain))
                }
            },
            {
                null
            }
        )
    }

    //
    // handle bluetooth permissions & start BT if not yet turned on
    //
    fun requestBluetoothPermissions() {
        try {
            // check android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // launch helper #1
                requestMultiplePermissions.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    )
                )
            } else {
                // < API 31
                // launch helper #2
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                requestEnableBluetooth.launch(enableBtIntent)
            }
        } catch (e: Exception) {
            okBox(this, getString(R.string.note), e.message.toString())
        }
    }
    // requestBluetoothPermissions() --> helper #1
    @SuppressLint("MissingPermission")
    val requestMultiplePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var granted = false
            permissions.entries.forEach {
                if (it.value) {
                    granted = true
                } else {
                    granted = false
                }
            }
            if (granted) {
                // if granted
                if (bluetoothAdapter?.isEnabled == false) {
                    // turn BT on if not yet on
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
                    // continues in onActivityResult
                } else {
                    if (bluetoothAdapter?.bondedDevices!!.isEmpty()) {
                        //
                        // no existing paired BT devices so far
                        //
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            decisionBox(
                                btActivity as Context,
                                DECISION.YESNO,
                                getString(R.string.no_paired_bt_device),
                                getString(R.string.search_for_an_available_device),
                                {
                                    // goes the full cycle:
                                    //    start broadcast receiver
                                    //    request making remote device visible
                                    //    make local device visible
                                    pairNewDevice()
                                },
                                {
                                    okBox(this, getString(R.string.note),
                                        getString(R.string.continue_with_select_remote))
                                }
                            )
                        } else {
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                                PERMISSION_REQUEST_LOCATION)
                            okBox(this, getString(R.string.note), getString(R.string.tryAgain))
                        }
                    } else {
                        //
                        // existing paired devices --> go directly to AcceptThread
                        //
                        acceptThread = AcceptThread()
                        acceptThread.start()
                    }
                    // give green light
                    btOnOffButton.setBackgroundColor(getResources().getColor(R.color.lightgreen))
                    // enable button to connect to a BT remote device
                    btCheckBoxVisibilty.isEnabled = true
                    btSelectRemoteButton.isEnabled = true
                    btSelectRemoteButton.setBackgroundColor(getResources().getColor(R.color.lightskyblue))
                }
            } else {
                // denied
                okBox(this, getString(R.string.note),
                    getString(R.string.bluetooth_permission_denied))
            }
        }
    // requestBluetoothPermissions() --> helper #2
    @SuppressLint("MissingPermission")
    val requestEnableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // if granted
                if (bluetoothAdapter?.isEnabled == false) {
                    // turn BT on if not yet on
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
                    // continues in onActivityResult
                } else {
                    if (bluetoothAdapter?.bondedDevices!!.isEmpty()) {
                        //
                        // no existing paired BT devices so far
                        //
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            // goes the full cycle:
                            //    start broadcast receiver
                            //    request making remote device visible
                            //    make local device visible
                            pairNewDevice()
                        } else {
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                                PERMISSION_REQUEST_LOCATION)
                            okBox(this, getString(R.string.note), getString(R.string.tryAgain))
                        }
                    } else {
                        //
                        // existing paired devices --> go directly to AcceptThread
                        //
                        acceptThread = AcceptThread()
                        acceptThread.start()
                    }
                    // give green light
                    btOnOffButton.setBackgroundColor(getResources().getColor(R.color.lightgreen))
                    // enable button to connect to a BT remote device
                    btCheckBoxVisibilty.isEnabled = true
                    btSelectRemoteButton.isEnabled = true
                    btSelectRemoteButton.setBackgroundColor(getResources().getColor(R.color.lightskyblue))
                }
            } else {
                // denied
                okBox(this, getString(R.string.note), getString(R.string.bluetooth_permission_denied))
            }
        }

    //
    // handle start/stop of BT server/beacon for BT devices discovery
    //
    lateinit var acceptThread: AcceptThread
    @SuppressLint("MissingPermission")
    fun requestBluetoothBeacon(start: Boolean) {
        // start / stop BT server discovery
        if (start == true) {
            // BT server discovery shall be started --> start AcceptThread
            acceptThread = AcceptThread()
            acceptThread.start()
            // launch related async thread
            val discoverableIntent: Intent =
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, timeoutBluetoothBeacon)
                }
            // nothing really important is going to happen, just a check for success
            startBluetoothBeacon.launch(discoverableIntent) // see helper below
            // more important things happen in BroadcastReceiver(),
            //   which needs to be started before requestBluetoothBeacon(true)
        } else {
            // unregister a receiver for bt devices: will show a list of so far unpaired devices
            unregisterReceiverForBluetoothDevices()
            // disable BT discovery right away
            bluetoothAdapter?.cancelDiscovery()
            try {
                // stop AcceptThread
                acceptThread.cancel()
            } catch (_: Exception) {
                // ok to leave unhandled, bc acceptThread might be not initialized due to reject of BT turn on
            }
        }
    }
    @SuppressLint("MissingPermission")
    val startBluetoothBeacon =
        this.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // !! Honor tablet X9 reduced timeoutBluetoothBeacon to 120 by itself w/o any notice
            if (result.resultCode == timeoutBluetoothBeacon || result.resultCode == 120) {
                // result code == timeoutBluetoothBeacon indicates, the BT server discovery is started
                centeredToast(this, getString(R.string.bluetooth_server_started), 3000)
                // flag manualVisibiltyStart tells, if visibility was ignited by hand / checkbox click
                if (manualVisibiltyStart == false) {
                    // show some progress until the discovery result is shown 20s later
                    okBoxAutoClose(
                        this,
                        getString(R.string.bt_discovery_started),
                        getString(R.string.done_in),
                        timeoutBluetoothDiscovery,
                        {
                            // quits discovery
                            requestBluetoothBeacon(false)
                            // quits receiver for unpaired BT devices
                            unregisterReceiverForBluetoothDevices()
                        }
                    )
                }
                // reset flag manualVisibiltyStart
                manualVisibiltyStart = false
                // set checkbox back to off after visibility timeout is reached
                Handler(Looper.getMainLooper()).postDelayed({
                    btCheckBoxVisibilty.isChecked = false
                }, result.resultCode * 1000L)
            } else {
                // any other result code:  error OR user denial of permission
                centeredToast(this, getString(R.string.bluetooth_server_did_not_start), 3000)
                requestBluetoothBeacon(false)
                unregisterReceiverForBluetoothDevices()
            }
        }
}

var btContext: Context? = null
    private set
private var btActivity: AppCompatActivity? = null
fun getActivity(): Context? {
    return btActivity
}

val timeoutBluetoothBeacon    = 300     // [s]  aka 5 min = 60s * 5
val timeoutBluetoothDiscovery = 20000L  // [ms] 20s

// UI views
lateinit var btCheckAutoConnect:    CheckBox
lateinit var btOnOffButton:         ToggleButton
lateinit var btCheckBoxVisibilty:   CheckBox
lateinit var btStatusTv:            TextView
lateinit var btSelectRemoteButton:  Button
lateinit var btSelectedRemoteTv:    TextView
lateinit var btSendMsgButton:       Button
lateinit var btSendMsgEt:           EditText
lateinit var btRequestFileButton:   Button
lateinit var btRequestFileStatusTv: TextView
lateinit var btSendFileButton:      Button

var manualVisibiltyStart = false

// bluetooth basics
var bluetoothManager: BluetoothManager? = null
var bluetoothAdapter: BluetoothAdapter? = null
var btAdapterIsEnabledOriginal = false

var selectedBluetoothDeviceIndex = -1

// app's fix UUID
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
// why not working?
// val MY_BT_UUID_str = "ccdf3083-8816-4802-9583-e8c81b6b64ba"
// https://stackoverflow.com/questions/55797579/how-to-fix-java-io-ioexception-read-failed-socket-might-closed-or-timeout-wh
val MY_BT_UUID_str = "00001101-0000-1000-8000-00805f9b34fb"
val MY_BT_UUID = UUID.nameUUIDFromBytes(MY_BT_UUID_str.toByteArray())

// establish data transfer from/to other device using the device's connected socket
var requestedFolderIndex = -1
var btServiceWorkThreadIsInitialized = false
lateinit var btServiceWorkThread: ServiceBT.WorkThread
fun manageMyConnectedSocket(socket: BluetoothSocket) {
    val myBluetoothService = ServiceBT(Handler(Looper.getMainLooper()))
    btServiceWorkThread = myBluetoothService.WorkThread(socket)
    btServiceWorkThread.start()
    btServiceWorkThreadIsInitialized = true
}
// service to read & write data via BT using the device's already connected BluetoothSocket
class ServiceBT(val handler: Handler) {

    inner class WorkThread(val mmSocket: BluetoothSocket) : Thread() {

        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024)
        private val mmTransfer: ByteArray = ByteArray(1024)

        // two cases: request folder vs. transmit folder
        var requestFolder = false
        var transmitFolder = false

        // the name of the folder to transmit/receive
        var transmitFolderName = ""

        // workflow for file receive
        var receiveFileSize = false
        var receiveFileInNextLoop = false
        var receiveFile = false
        var totalFileSizeInBytes = 0
        var totalBytesRead = 0

        // listener for incoming data from the remote device
        @SuppressLint("MissingPermission")
        override fun run() {
            // update connection status
            handler.postDelayed({
                btStatusTv.text = btActivity!!.getString(R.string.ok_connected_to_device_shown_below)
                btSelectedRemoteTv.text = mmSocket.remoteDevice.name
                btCheckAutoConnect.isEnabled = true
                btCheckBoxVisibilty.isChecked = false
                btCheckBoxVisibilty.isEnabled = false
                btSelectRemoteButton.isEnabled = false
                btSelectRemoteButton.setBackgroundColor(btActivity!!.getResources().getColor(R.color.lightgreen))
                btSendFileButton.isEnabled = true
                btRequestFileButton.isEnabled = true
                btSendMsgButton.isEnabled = true
            }, 0)
            // keep listening to the InputStream until an exception occurs
            while (true) {
                try {
                    //
                    // clear old buffer
                    //
                    mmBuffer.fill(0)

                    //
                    // read from the InputStream
                    // --> blocking call until data arrive
                    //
                    var read = mmInStream.read(mmBuffer)

                    //
                    // string from bytearray with zeros eliminated
                    //
                    var msg = ""
                    if (read < 1024) {
                        msg = mmBuffer.toString(Charsets.UTF_8).substring(0, read)
                    }

                    //
                    // remote pushed the transmit folder button and sends a folder name via "::::TRANSMIT_FOLDER::::"
                    //
                    if (msg.contains("::::TRANSMIT_FOLDER::::")) {
                        // set workflow control vars
                        requestFolder = false
                        transmitFolder = true
                        // get folder name
                        val split = msg.split("::::")
                        if (split.size == 3) {
                            // memorize transmit folder name
                            transmitFolderName = split[2]
                            // send "::::TRANSMIT_FOLDER_ACK::::" as a sort of 'go ahead'
                            btServiceWorkThread.write("::::TRANSMIT_FOLDER_ACK::::".toByteArray(Charsets.UTF_8))
                        } else {
                            // error: no folder name provided
                            requestFolder = false
                            transmitFolder = false
                        }
                    }

                    // valid for TRANSMIT workflow
                    if (transmitFolder) {
                        // the device, which received "::::TRANSMIT_FOLDER::::" answered with "::::TRANSMIT_FOLDER_ACK::::"
                        if (msg.contains("::::TRANSMIT_FOLDER_ACK::::")) {
                            // the folder name to operate with
                            MainActivity.ds.namesSection[MainActivity.ds.selectedSection]
                            // build bulk data for file size calc only
                            val bulk = MainActivity.ds.dataSection[MainActivity.ds.selectedSection].toByteArray(Charsets.UTF_8)
                            // device which sent "::::TRANSMIT_FOLDER::::" sends now the file size, which is needed in stream read operation
                            btServiceWorkThread.write(("::::FILE_SIZE::::" + bulk.size.toString()).toByteArray(Charsets.UTF_8))
                        }
                        // device which received "::::TRANSMIT_FOLDER::::" + "::::FILE_SIZE::::" sent previously "::::FILE_SIZE_ACK::::" in a section below
                        if (msg.contains("::::FILE_SIZE_ACK::::")) {
                            // device which sent "::::TRANSMIT_FOLDER::::" sends now the file as bulk data
                            val bulk = MainActivity.ds.dataSection[MainActivity.ds.selectedSection].toByteArray(Charsets.UTF_8)
                            btServiceWorkThread.write(bulk)
                            // sake of mind
                            receiveFileSize = false
                            receiveFile = false
                            // clear msg to avoid further side tracks
                            msg = ""
                        }
                    }

                    //
                    // REMOTE-device requests a folder from HERE-device
                    //
                    if (msg.contains("::::REQUEST_FOLDER::::")) {
                        // set workflow control vars
                        requestFolder = true
                        transmitFolder = false
                        // remote BT device requested to send over a named GrzLog folder
                        val split = msg.split("::::")
                        if (split.size == 3) {
                            // obtain requested folder name
                            transmitFolderName = split[2]
                            // check requested folder name being present
                            requestedFolderIndex = -1
                            for (i: Int in MainActivity.ds.namesSection.indices) {
                                if (transmitFolderName.equals(MainActivity.ds.namesSection[i])) {
                                    // requested folder is found on the providing device
                                    requestedFolderIndex = i
                                    break
                                }
                            }
                            // sanity checks and build an error message (if so) to the folder requesting device
                            var messageErr = ""
                            var folderIsProtected = false
                            if (requestedFolderIndex == -1) {
                                // error --> folder not existing on providing device
                                messageErr = btActivity!!.getString(R.string.requested_folder_is_not_existing_on) + bluetoothAdapter!!.name
                            } else {
                                // error --> a protected folder needs to be the active one on the providing device, if not --> no transmit
                                if (requestedFolderIndex != MainActivity.ds.selectedSection) {
                                    if (MainActivity.ds.timeSection[requestedFolderIndex] == TIMESTAMP.AUTH) {
                                        folderIsProtected = true
                                        messageErr = btActivity!!.getString(R.string.the_folder) +
                                                " '" + transmitFolderName + "': " +
                                                btActivity!!.getString(R.string.requested_folder_is_protected_it_needs_to_be_opened_on) +
                                                "'" + bluetoothAdapter!!.name + "'"
                                    }
                                }
                            }
                            // UI thread
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (requestedFolderIndex == -1 || folderIsProtected) {
                                    // send error case to remote and inform local UI
                                    btServiceWorkThread.write(("::::FILE_CANCEL::::".toByteArray(Charsets.UTF_8)))
                                    okBox(btActivity as Context, btActivity!!.getString(R.string.note), messageErr)
                                } else {
                                    // go ahead with file transfer
                                    btStatusTv.text = msg
                                    // ask folder provider, whether it is ok to send the requested folder
                                    decisionBox(
                                        btActivity as Context,
                                        DECISION.YESNO,
                                        btActivity!!.getString(R.string.remote_folder_request),
                                        btActivity!!.getString(R.string.ok_to_send_folder) +
                                                transmitFolderName +
                                                btActivity!!.getString(R.string.to_plus) +
                                                btServiceWorkThread.mmSocket.remoteDevice.name + "' ?",
                                        {
                                            // build bulk data for byte count calc only
                                            val bulk = MainActivity.ds.dataSection[requestedFolderIndex].toByteArray(Charsets.UTF_8)
                                            // tell the remote receiver the bulk size: needed in remote read operation
                                            btServiceWorkThread.write(("::::FILE_SIZE::::" + bulk.size.toString()).toByteArray(Charsets.UTF_8))
                                        },
                                        {
                                            // inform remote about cancellation
                                            btServiceWorkThread.write(("::::FILE_CANCEL::::".toByteArray(Charsets.UTF_8)))
                                        }
                                    )
                                }
                            }, 0)
                        } else {
                            requestFolder = false
                            transmitFolder = false
                        }
                    }

                    if (requestFolder) {
                        // on here valid for REQUEST: finalize folder transfer after folder's byte count is ACKed by remote
                        if (msg.contains("::::FILE_SIZE_ACK::::")) {
                            // send folder as bulk to remote
                            val bulk = MainActivity.ds.dataSection[requestedFolderIndex].toByteArray(Charsets.UTF_8)
                            btServiceWorkThread.write(bulk)
                        }
                    }

                    if (transmitFolder) {
                        // set control vars for folder's  byte count calculation
                        if (msg.contains("::::FILE_SIZE::::")) {
                            // "::::FILE_SIZE::::" includes the byte count is received
                            receiveFileSize = true
                            // not yet ready for receiving the folder
                            receiveFile = false
                        }
                        // set control vars for receiving folder data
                        if (msg.contains("::::FILE_SIZE_ACK::::")) {
                            // receiving folder's byte count is done
                            receiveFileSize = false
                            // now ready for receiving the folder in the next iteration of the while loop
                            receiveFile = true
                        }
                    }

                    //
                    // prevent from getting stuck in blocking call 'read = mmInStream.read(mmBuffer)' below
                    //    receiveFileInNextLoop is set TRUE after receiving the byte cont
                    //    this awkward flag makes sure, not to get stuck in 'if (receiveFile) {'
                    //    it is better to start with the read operation at the start of this while loop
                    //
                    if (receiveFileInNextLoop) {
                        // reset next loop flag
                        receiveFileInNextLoop = false
                        // activate receive file data
                        receiveFile = true
                    }

                    //
                    // valid for REQUEST and TRANSMIT: learn about expected folder byte count during transmission
                    //
                    if (receiveFileSize) {
                        //
                        // FILE_SIZE receiver for folder: handling to obtain file size
                        //
                        if (msg.startsWith("::::FILE_SIZE::::")) {
                            msg = msg.substring(0, read)
                            totalBytesRead = 0
                            val split = msg.split("::::")
                            if (split.size == 3) {
                                totalFileSizeInBytes = split[2].toInt()
                                // receiving file size byte count is done
                                receiveFileSize = false
                                // prevent from getting stuck in blocking call 'read = mmInStream.read(mmBuffer)'
                                //    --> receiveFile becomes active in the next loop
                                receiveFileInNextLoop = true
                                // post note to UI
                                handler.postDelayed({
                                    btRequestFileStatusTv.text = btActivity!!.getString(R.string.remote_sent_file_size)
                                }, 0)
                                // confirm reception of file size to remote
                                write("::::FILE_SIZE_ACK::::".toByteArray(Charsets.UTF_8))
                            } else {
                                // error case handling
                                receiveFileSize = false
                                receiveFile = false
                                // post note to UI
                                handler.postDelayed({
                                    btRequestFileStatusTv.text = "File transfer error"
                                    btSendFileButton.isEnabled = true
                                    btRequestFileButton.isEnabled = true
                                    btSendMsgButton.isEnabled = true
                                }, 0)
                            }
                        }
                    }

                    //
                    // valid for REQUEST and TRANSMIT: collect data into baos, write to file, show result in UI, continue in UI
                    //
                    if (receiveFile) {
                        // baos collects all folder data: needs totalFileSizeInBytes to break
                        val baos = ByteArrayOutputStream()
                        // the loop won't actually reach the situation with read == -1
                        while (read != -1) {
                            // put data into baos
                            baos.write(mmBuffer, 0, read)
                            // update byte count read
                            totalBytesRead += read
                            // ... since we know about the byte count to receive ...
                            if (totalBytesRead >= totalFileSizeInBytes) {
                                // ... we can break the loop, as soon as the file size is reached ...
                                break
                            }
                            // ... and this does not become a blocking call anymore ...
                            read = mmInStream.read(mmBuffer)
                        }
                        baos.flush()
                        // write to file
                        val saveFile = File(MainActivity.appStoragePath, "test.txt")
                        if (saveFile.exists()) {
                            saveFile.delete()
                        }
                        val fos = FileOutputStream(saveFile.path)
                        fos.write(baos.toByteArray())
                        fos.close()
                        // reset file receive flag quits folder transfer
                        receiveFile = false
                        // post note to UI: "::::FINAL_HANDLING::::" is a control word, parsed in a UI TextWatcher
                        handler.postDelayed({
                            btRequestFileStatusTv.text = "::::FINAL_HANDLING::::" +
                                    btActivity!!.getString(R.string.success_folder_received) +
                                    transmitFolderName + "'"
                        }, 0)
                    }

                    //
                    // remote is done with folder transmission
                    //
                    if (msg.startsWith("::::FILE_TRANSMISSION_DONE::::")) {
                        // no need to forward msg, would otherwise show as cryptic msg in UI
                        msg = ""
                        // cancel all file ops
                        requestFolder = false
                        transmitFolder = false
                        receiveFileSize = false
                        receiveFile = false
                        transmitFolderName = ""
                        // post note
                        handler.postDelayed({
                            btRequestFileStatusTv.text = btActivity!!.getString(R.string.remote_is_done_with_folder)
                            btSendFileButton.isEnabled = true
                            btRequestFileButton.isEnabled = true
                            btSendMsgButton.isEnabled = true
                        }, 0)
                    }

                    //
                    // remote cancels file transfer
                    //
                    if (msg.startsWith("::::FILE_CANCEL::::")) {
                        // cancel all file ops
                        requestFolder = false
                        transmitFolder = false
                        receiveFileSize = false
                        receiveFile = false
                        transmitFolderName = ""
                        // post note
                        handler.postDelayed({
                            btRequestFileStatusTv.text = btActivity!!.getString(R.string.remote_cancelled_file_transfer)
                            btSendFileButton.isEnabled = true
                            btRequestFileButton.isEnabled = true
                            btSendMsgButton.isEnabled = true
                        }, 0)
                    }

                    //
                    // normal MESSAGE receiver: show in UI
                    //
                    if (!requestFolder && !transmitFolder) {
                        // forward to UI
                        handler.postDelayed({
                            // !! leave as not translated string !! bc. it is used as a control word
                            if (msg.contains(": Remote BT device closed connection")) {
                                //
                                // remote BT closed connection
                                //
                                btOnOffButton.isChecked = false
                                Handler(Looper.getMainLooper()).postDelayed({
                                    btStatusTv.text = msg
                                }, 200)
                            } else {
                                //
                                // normal messages with no internal meaning or control word
                                //
                                if (msg.isNotEmpty()) {
                                    centeredToast(btActivity as Context, msg, 3000)
                                }
                            }
                            mmTransfer.fill(0)
                        }, 0)
                    }

                } catch (e: IOException) {
                    // input stream got disconnected
                    handler.postDelayed({
                        // cannot set auto connect anymore
                        if (btCheckAutoConnect.isChecked == false) {
                            btCheckAutoConnect.isEnabled = false
                        }
                        // turn off all BT sync
                        btOnOffButton.isChecked = false
                    }, 0)
                    break
                }
            }
        }

        // call this to send data to the remote device
        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
            } catch (e: IOException) {
                handler.postDelayed({
                    btStatusTv.text = btActivity!!.getString(R.string.error_remote_device_is_not_reachable)
                    // cannot set auto connect anymore
                    if (btCheckAutoConnect.isChecked == false) {
                        btCheckAutoConnect.isEnabled = false
                    }
                    // allow to reconnect
                    btCheckBoxVisibilty.isEnabled = true
                    btSelectRemoteButton.isEnabled = true
                    btSelectRemoteButton.setBackgroundColor(btActivity!!.getResources().getColor(R.color.lightskyblue))
                    // no requests to remote allowed
                    btRequestFileButton.isEnabled = false
                    btSendFileButton.isEnabled = false
                    btSendMsgButton.isEnabled = false
                }, 0)
                return
            }
        }

        // call this method to shut down the connection.
        fun cancel() {
            try {
                // cannot set auto connect anymore
                handler.postDelayed({
                    if (btCheckAutoConnect.isChecked == false) {
                        btCheckAutoConnect.isEnabled = false
                    }
                }, 0)
                mmSocket.close()
            } catch (e: IOException) {
//                  Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }
}

// thread that accepts incoming BT connections
//    this is always the 1st step for a BT connection
@SuppressLint("MissingPermission")
class AcceptThread : Thread() {
    private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
        bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord("GrzLogSrvBT", MY_BT_UUID)
    }
    override fun run() {
        // update connection status
        Handler(Looper.getMainLooper()).postDelayed({
            btStatusTv.text = "waiting for accept"
        }, 0)
        // keep listening until exception occurs or a socket is returned
        var shouldLoop = true
        while (shouldLoop) {
            val socket: BluetoothSocket? = try {
                mmServerSocket?.accept()
            } catch (e: IOException) {
//                  Log.e(TAG, "Socket's accept() method failed", e)
                shouldLoop = false
                null
            }
            socket?.also {
                // switch to ServiceBT.WorkThread
                manageMyConnectedSocket(it)
                // close AcceptThread
                mmServerSocket?.close()
                shouldLoop = false
            }
        }
    }
    // closes the connect socket and causes the thread to finish
    fun cancel() {
        try {
            mmServerSocket?.close()
            // update connection status
            Handler(Looper.getMainLooper()).postDelayed({
                btStatusTv.text = "accept mode cancelled"
                btCheckBoxVisibilty.isChecked = false
            }, 0)
        } catch (e: IOException) {
            Handler(Looper.getMainLooper()).postDelayed({
                btStatusTv.text = "accept mode exception"
            }, 0)
        }
    }
}

// client thread that initiates a BT connection:
// --> called from "Select Remote" button via connectThread.start()
class ConnectThread(device: BluetoothDevice) : Thread() {
    private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
        device.createRfcommSocketToServiceRecord(MY_BT_UUID)
    }
    @SuppressLint("MissingPermission")
    public override fun run() {
        // Cancel discovery because it otherwise slows down the connection.
        bluetoothAdapter?.cancelDiscovery()
        mmSocket?.let { socket ->
            try {
                // update connection status
                Handler(Looper.getMainLooper()).postDelayed({
                    btStatusTv.text = btActivity!!.getString(R.string.waiting_for_connect)
                }, 0)
                // Connect to the remote device through the socket. This call blocks
                // until it succeeds or throws an exception.
                socket.connect()
                // connection attempt succeeded,
                //    continues in a worker thread called ServiceBT and ServiceBT.WorkThread
                manageMyConnectedSocket(socket)
            } catch (e: Exception) {
                // update status: a connection request was made from here, but remote did not answer
                Handler(Looper.getMainLooper()).postDelayed({
                    btStatusTv.text = btActivity!!.getString(R.string.no_answer_from_remote_device) + e.message
                    var message = btActivity!!.getString(R.string.enable_bt_on_remote_device)
                    if (selectedBluetoothDeviceIndex != -1) {
                        message = btActivity!!.getString(R.string.enable_bt_on_remote_ok)
                    }
                    decisionBox(
                        btActivity as Context,
                        DECISION.YESNO,
                        btActivity!!.getString(R.string.connect_failed),
                        btActivity!!.getString(R.string.forgot_enable),
                        {
                            okBox(
                                btActivity as Context,
                                btActivity!!.getString(R.string.note),
                                message,
                                {
                                    // auto connect failed, bc remote was not activated
                                    if (selectedBluetoothDeviceIndex != -1) {
                                        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
                                        if (pairedDevices != null && pairedDevices.isNotEmpty()) {
                                            val connectThread = ConnectThread(pairedDevices!!.elementAt(selectedBluetoothDeviceIndex))
                                            connectThread.start()
                                        }
                                    }
                                }
                            )
                        },
                        {
                            okBox(
                                btActivity as Context,
                                btActivity!!.getString(R.string.note),
                                btActivity!!.getString(R.string.on_both_phones_goto_settings)
                            )
                        }
                    )
                }, 0)
            }
        }
    }
    // Closes the client socket and causes the thread to finish.
    fun cancel() {
        try {
            mmSocket?.close()
        } catch (e: IOException) {
//              Log.e(TAG, "Could not close the client socket", e)
        }
    }
}

// proceed a list of available remote BT devices to pair with as a follow-up from BroadcastReceiver()
@SuppressLint("MissingPermission")
fun handleUnpairedBtDevices(devices: MutableList<DeviceBluetooth>) {
    // no auto connect anymore
    btCheckAutoConnect.isChecked = false
    // build a list of String for selection dialog
    val deviceArray: MutableList<String> = ArrayList()
    for (item in devices) {
        deviceArray.add(item.name)
    }
    // add already paired BT devices if not yet contained
    val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
    if (pairedDevices != null && pairedDevices.isNotEmpty()) {
        for (item in pairedDevices) {
            if (!deviceArray.contains(item.name)) {
                deviceArray.add(item.name)
            }
        }
    }
    // sake of mind
    if (deviceArray.isEmpty()) {
        return
    }
    // select one BT device and continue or quit
    var tmpSelectionNdx = 0
    var selectBuilder: AlertDialog.Builder?
    selectBuilder = AlertDialog.Builder(btActivity, android.R.style.Theme_Material_Dialog)
    selectBuilder.setTitle(btActivity!!.getString(R.string.select_bt_device))
    selectBuilder.setSingleChoiceItems(
        deviceArray.toTypedArray(),
        tmpSelectionNdx,
        DialogInterface.OnClickListener { dialog, which ->
            tmpSelectionNdx = which
        })
    // select OPTIONS ok
    selectBuilder.setPositiveButton(
        btActivity!!.getString(R.string.ok),
        DialogInterface.OnClickListener { dialog, which ->
            for (i: Int in devices.indices) {
                if (i == tmpSelectionNdx) {
                    // index of item with "name of mobile phone" as shown in 'About Phone'
                    val connectThread = ConnectThread(devices[i].btDevice)
                    //
                    // if device is not yet paired --> OS pairing dialogs on both phone appear
                    // if device is already paired --> simply connect
                    //
                    connectThread.start()
                    break
                }
            }
        }
    )
    // select OPTIONS cancel
    selectBuilder.setNegativeButton(
        R.string.cancel,
        DialogInterface.OnClickListener { dlg, which ->
            return@OnClickListener
        }
    )
    // select OPTIONS show
    val dlg = selectBuilder.create()
    dlg.setOnShowListener {
        dlg.getWindow()!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }
    dlg.show()
}

// helper class
class DeviceBluetooth(
    var name: String,
    var address: String,
    var btDevice: BluetoothDevice) {}
// helper list
var devicesListBlootooth: MutableList<DeviceBluetooth> = ArrayList()
//
// BroadcastReceiver for BluetoothDevice.ACTION_FOUND:
// --> waits for broadcast from other BT devices
// --> is called, if a broadcast comes in from another BT device
// --> devicesListBlootooth becomes filled with reasonable devices
// --> Handler continues after 20s (empirical value to be ok) with further foreign device handling
//
val bcReceiver = object : BroadcastReceiver() {
    // receiver for btActivity!!.registerReceiver(bcReceiver, filter), from below
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        when (action) {
            BluetoothDevice.ACTION_FOUND -> {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as BluetoothDevice?
                val deviceName = device!!.name
                val deviceAddress = device.address
                // BT device shall bee somehow reasonable
                if (deviceName != null && deviceName.isNotEmpty()) {
                    // continues after 20s with further remote devices handling
                    if (devicesListBlootooth.isEmpty()) {
                        // logic goes here, when the 1st unpaired device arrives
                        Handler(Looper.getMainLooper()).postDelayed( {
                            //
                            // all the work needed to deal with unpaired devices
                            //
                            handleUnpairedBtDevices(devicesListBlootooth)
                        }, timeoutBluetoothDiscovery)
                    }
                    // only add a BT device to devicesListBlootooth if not yet contained
                    val element = DeviceBluetooth(deviceName, deviceAddress, device)
                    var foundItem = false
                    for (item in devicesListBlootooth) {
                        if (item.name.equals(deviceName)) {
                            foundItem = true
                            break
                        }
                    }
                    if (foundItem == false) {
                        devicesListBlootooth.add(element)
                    }
                }
            }
        }
    }
}
// start a BT BroadcastReceiver for BT broadcasts from a remote device
fun registerReceiverForBluetoothDevices() {
    // register for broadcasts when a bluetooth device is discovered
    if (btActivity != null) {
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        btActivity!!.registerReceiver(bcReceiver, filter)
    }
}
// stop BT BroadcastReceiver
fun unregisterReceiverForBluetoothDevices() {
    try {
        if (btActivity != null) {
            btActivity!!.unregisterReceiver(bcReceiver)
        }
    } catch (e: Exception) {
        // ok to leave unhandled
    }
}
