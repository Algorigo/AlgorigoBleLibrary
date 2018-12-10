package com.algorigo.algorigoblelibrary

import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.algorigo.algorigoble.BleDevice
import kotlinx.android.synthetic.main.item_ble_device_list.view.*

class BleRecyclerAdapter(private val bleRecyclerListener: BleRecyclerListener) : RecyclerView.Adapter<BleRecyclerAdapter.BleRecyclerViewHolder>() {

    interface BleRecyclerListener {
        fun onSelect(bleDevice: BleDevice)
        fun onButton(bleDevice: BleDevice)
    }

    inner class BleRecyclerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        init {
            itemView.setOnClickListener {
                bleDeviceList?.get(adapterPosition)?.let {
                    bleRecyclerListener.onSelect(it)
                }
            }
            itemView.connectBtn.setOnClickListener {
                bleDeviceList?.get(adapterPosition)?.let {
                    bleRecyclerListener.onButton(it)
                }
            }
        }

        fun setData(bleDevice: BleDevice?) {
            itemView.titleView.setText(bleDevice?.toString())
            when (bleDevice?.connectionState) {
                BleDevice.ConnectionState.CONNECTING -> {
                    itemView.connectBtn.isEnabled = false
                    itemView.connectBtn.text = itemView.context.getString(R.string.connecting, bleDevice.connectionState.status)
                }
                BleDevice.ConnectionState.CONNECTED -> {
                    itemView.connectBtn.isEnabled = true
                    itemView.connectBtn.setText(R.string.disconnect)
                }
                BleDevice.ConnectionState.DISCONNECTED -> {
                    itemView.connectBtn.isEnabled = true
                    itemView.connectBtn.setText(R.string.connect)
                }
                BleDevice.ConnectionState.DISCONNECTING -> {
                    itemView.connectBtn.isEnabled = false
                    itemView.connectBtn.setText(R.string.disconnecting)
                }
            }
        }
    }

    var bleDeviceList: List<BleDevice>? = null
            set(value) {
                field = value
                notifyDataSetChanged()
            }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BleRecyclerViewHolder {
        return LayoutInflater.from(parent.context).inflate(R.layout.item_ble_device_list, parent, false).let {
            BleRecyclerViewHolder(it)
        }
    }

    override fun getItemCount(): Int {
        return bleDeviceList?.size ?: 0
    }

    override fun onBindViewHolder(vh: BleRecyclerViewHolder, position: Int) {
        vh.setData(bleDeviceList?.get(position))
    }
}