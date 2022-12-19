package com.algorigo.algorigoblelibrary

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.algorigo.algorigoble2.BleCharacterisic
import java.util.*

class CharacteristicAdapter(private val callback: DeviceActivity) : RecyclerView.Adapter<CharacteristicAdapter.CharacteristicViewHolder>() {

    interface Callback {
        fun onReadCharacteristicBtn(uuid: UUID)
        fun onWriteCharacteristicBtn(uuid: UUID, byteArray: ByteArray)
        fun onNotifyBtn(uuid: UUID)
    }

    inner class CharacteristicViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val uuidEditText: EditText = itemView.findViewById(R.id.uuid_edit_text)
        private val readCharacteristicBtn: Button = itemView.findViewById(R.id.read_characteristic_btn)
        private val writeCharacteristicLayout: LinearLayout = itemView.findViewById(R.id.write_characteristic_layout)
        private val writeEditText: EditText = itemView.findViewById(R.id.write_edit_text)
        private val writeCharacteristicBtn: Button = itemView.findViewById(R.id.write_characteristic_btn)
        private val notifyBtn: Button = itemView.findViewById(R.id.notify_btn)

        fun setCharacteristic(index: Int, characteristic: BleCharacterisic) {
            itemView.setBackgroundColor(getColor(index))

            uuidEditText.setText(characteristic.uuid.toString())
            readCharacteristicBtn.visibility = if (characteristic.isReadable()) View.VISIBLE else View.GONE
            if (characteristic.isWritable()) {
                writeCharacteristicLayout.visibility = View.VISIBLE
                writeCharacteristicBtn.visibility = View.VISIBLE
            } else {
                writeCharacteristicLayout.visibility = View.GONE
                writeCharacteristicBtn.visibility = View.GONE
            }
            notifyBtn.visibility = if (characteristic.isNotifyAvailable()) View.VISIBLE else View.GONE

            readCharacteristicBtn.setOnClickListener {
                callback.onReadCharacteristicBtn(characteristic.uuid)
            }
            writeCharacteristicBtn.setOnClickListener {
                val byteArray = writeEditText.text.toString().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                callback.onWriteCharacteristicBtn(characteristic.uuid, byteArray)
            }
            notifyBtn.setOnClickListener {
                callback.onNotifyBtn(characteristic.uuid)
            }
        }
    }

    var characteristics = mutableListOf<BleCharacterisic>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CharacteristicViewHolder {
        return LayoutInflater.from(parent.context).inflate(R.layout.item_characteristic, parent, false).let {
            CharacteristicViewHolder((it))
        }
    }

    override fun onBindViewHolder(holder: CharacteristicViewHolder, position: Int) {
        holder.setCharacteristic(position, characteristics[position])
    }

    override fun getItemCount(): Int {
        return characteristics.size
    }

    companion object {
        private val colors = arrayOf(
            Color.CYAN,
            Color.LTGRAY,
            Color.MAGENTA,
            Color.YELLOW,
            Color.GREEN,
        )

        private fun getColor(index: Int): Int {
            return colors[(index % colors.size)]
        }
    }
}