package io.iktech.demo.bttest

import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.*
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v4.app.DialogFragment
import android.support.v7.app.AppCompatActivity;
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

import kotlinx.android.synthetic.main.activity_main.*
import java.io.*
import java.lang.Exception
import java.util.*

var devices = ArrayList<BluetoothDevice>()
var devicesMap = HashMap<String, BluetoothDevice>()
var mArrayAdapter: ArrayAdapter<String>? = null
val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb") //  uuid for RFComm. Works with Arduino and teraterm
var message = ""

/*
Workflow

In general, transmitting data over bluetooth is not much different from transmitting them via the other channels:

    Receiving party creates a server that waits for the clients to connect
    Sending party initiates the connection, both sides getting sockets for data transmission
    Parties exchange data
    https://medium.com/@ikolomiyets/transferring-data-between-android-devices-over-bluetooth-with-kotlin-3cab7e5ca0d2

                                                                 +-------------------+
                                                   1.connect     |                   |
                            +--------------+    +----------------| Server controller |
                            |              |    |                |                   |
                            |   Client     |----|                +--------|----------+
                            |              |    |                         | 2.socket
                            +--------------+    |                         |
                                                |3.transmission  +-------------------+
                                                |                |                   |
                                                |----------------|      Server       |
                                                                 |                   |
                                                                 +-------------------+
    Receiving party creates a Server Controller, which is running in its own thread. Server Controller creates a service
    listener identified by the name and UUID. The clients then will use the UUID to access a specific service. Listener
    creates a Server Socket that placed into waiting state using accept() method.
    The client on the other hand, initiates a connection to the specified device attempting to access the service, that
    is identified by UUID. Once connection is established, accept() method call returns a socket object, which is used
    to create a Server thread that used to communicate with the client.
    Once exchange is over, both sides should close the sockets.
*/

class MainActivity : AppCompatActivity() {
    private var textView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        mArrayAdapter = ArrayAdapter(this, R.layout.dialog_select_device)
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(mReceiver, filter) // Don't forget to unregister during onDestroy
        this.textView = findViewById(R.id.textView)
        val button = findViewById<Button>(R.id.button)
        button.setOnClickListener {view ->
            if (BluetoothAdapter.getDefaultAdapter() == null) {
                Log.i("xxxserver", "Bluetooth is disabled")

                Snackbar.make(view, "Bluetooth is disabled", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()

            } else {

                devicesMap = HashMap()
                devices = ArrayList()
                mArrayAdapter!!.clear()

                val editText = findViewById<EditText>(R.id.editText)
                message = editText.text.toString()
                editText.text.clear()
                for (device in BluetoothAdapter.getDefaultAdapter().bondedDevices) {
                    devicesMap.put(device.address, device)
                    devices.add(device)
                    // Add the name and address to an array adapter to show in a ListView
                    mArrayAdapter!!.add((if (device.name != null) device.name else "Unknown") + "\n" + device.address + "\nPared")
                }

                // Start discovery process
                if (BluetoothAdapter.getDefaultAdapter().startDiscovery()) {
                    val dialog = SelectDeviceDialog()
                    dialog.show(supportFragmentManager, "select_device")
                }
            }
        }

        BluetoothServerController(this).start()
    }
    override fun onDestroy() {
        super.onDestroy()

        Log.i("xxxserver", "onDestroy")
        //Runtime.getRuntime().exit(0) just a try
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun appendText(text: String) {
        runOnUiThread {
            this.textView?.text = this.textView?.text.toString() +"\n" + text
        }
    }
    // Create a BroadcastReceiver for ACTION_FOUND
    private val mReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            // When discovery finds a device
            if (BluetoothDevice.ACTION_FOUND == action) {
                // Get the BluetoothDevice object from the Intent
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val pairedDevice = devicesMap[device.address]
                if (pairedDevice == null) {
                    var index = -1
                    for (i in devices.indices) {
                        val tmp = devices[i]
                        if (tmp.address == device.address) {
                            index = i
                            break
                        }
                    }

                    if (index > -1) {
                        if (device.name != null) {
                            mArrayAdapter?.insert(
                                (if (device.name != null) device.name else "Unknown") + "\n" + device.address,
                                index
                            )
                        }
                    } else {
                        devices.add(device)
                        // 	Add the name and address to an array adapter to show in a ListView
                        mArrayAdapter?.add((if (device.name != null) device.name else "Unknown") + "\n" + device.address)
                    }
                }
            }
        }
    }

}

/*
    Create a server socket, identified by the uuid, in the class constructor
    Once thread execution started wait for the client connections using accept() method
    Once client established connection accept() method returns a BluetoothSocket reference
    that gives access to the input and output streams. We use this socket to start the Server thread.

 */

class BluetoothServerController(activity: MainActivity) : Thread() {
    private var cancelled: Boolean
    private val serverSocket: BluetoothServerSocket? // does not receive in server mode
    private val activity = activity

    init {
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter != null) {
            this.serverSocket = btAdapter.listenUsingInsecureRfcommWithServiceRecord("Android", uuid) // not sure what name is required
            //    this.serverSocket = btAdapter.listenUsingRfcommWithServiceRecord("test", uuid) no difference
            this.cancelled = false
        } else {
            this.serverSocket = null
            this.cancelled = true
        }

    } 

    override fun run() {
        var socket: BluetoothSocket? = null


        while(true) {
            Log.i("xxxserver", "Socket to define")

            if (this.cancelled) {
                Log.i("xxxserver", "Cancelled")
                break
            }
            Log.i("xxxserver", "Cancelled: "+ this.cancelled.toString() +  " Socket: " + socket.toString())

            try {
                Log.i("xxxserver", "Define Socket")
                // Always gives timeout on a China phone. Does not al all work on lifetab!!!!
                socket = serverSocket!!.accept(5000)

                Log.i("xxxserver", "Defined Socket" + socket.toString())
            } catch(e: IOException) {
                Log.i("xxxserver", "No socket")
                break
            }
            Log.i("xxxserver", "again Cancelled?  "+ this.cancelled.toString() +  " Socket: " + socket.toString())

            if (!this.cancelled && socket != null) {
                Log.i("xxxserver", "Connecting to socket")
                BluetoothServer(this.activity, socket).start()
            }
        }
    }

    fun cancel() {
        this.cancelled = true
        try {
            this.serverSocket!!.close()
        }
        catch (e: IOException) {
            Log.i("xxxserver", "Cannot close socket")
        }
    }
}



class BluetoothServer(private val activity: MainActivity, private val socket: BluetoothSocket): Thread() {
    private val inputStream = this.socket.inputStream
    private val outputStream = this.socket.outputStream

    override fun run() {
        Log.i("xxxserver", "Trying Reading")

        try {
            val available = inputStream.available()
            val bytes = ByteArray(available)
            Log.i("xxxserver", "Reading")
            inputStream.read(bytes, 0, available)
            val text = String(bytes)
            Log.i("xxxserver", "Message received")
            Log.i("xxxserver", text)
            activity.appendText(text)
        } catch (e: Exception) {
            Log.e("xxxclient", "Cannot read data", e)
        } finally {
            inputStream.close()
            outputStream.close()
            Thread.sleep(1000)
            socket.close()
        }
    }
}

class SelectDeviceDialog: DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(this.activity)
        builder.setTitle("Send message to")
        builder.setAdapter(mArrayAdapter) { _, which: Int ->
            BluetoothAdapter.getDefaultAdapter().cancelDiscovery()
            Log.i("xxxclient", "connecting to:" + devices[which].toString())

            BluetoothClient(device = devices[which]).start()
        }

        return builder.create()
    }

    override fun onCancel(dialog: DialogInterface?) {
        super.onCancel(dialog)
    }
}

class BluetoothClient(device: BluetoothDevice): Thread() {
    private val socket = device.createRfcommSocketToServiceRecord(uuid)

    override fun run() {
        Log.i("xxxclient", "Connecting client now")
        this.socket.connect()

        Log.i("xxxclient", "Sending")
        val outputStream = this.socket.outputStream
        val inputStream = this.socket.inputStream
        try {
            outputStream.write(message.toByteArray())
            outputStream.flush()
            Log.i("xxxclient", "Sent")
        } catch(e: Exception) {
            Log.e("xxxclient", "Cannot send", e)
        } finally {
            outputStream.close()
            inputStream.close()
            Thread.sleep(1000)
            this.socket.close()
        }

    }
}