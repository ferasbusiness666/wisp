package com.wisp.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wisp.app.databinding.ItemContactBinding

data class ContactRow(val contact: Contact, val online: Boolean)

class ContactAdapter(
    private val onCall: (Contact) -> Unit,
    private val onRemove: (Contact) -> Unit
) : ListAdapter<ContactRow, ContactAdapter.Holder>(Diff) {

    object Diff : DiffUtil.ItemCallback<ContactRow>() {
        override fun areItemsTheSame(old: ContactRow, new: ContactRow) =
            old.contact.code == new.contact.code

        override fun areContentsTheSame(old: ContactRow, new: ContactRow) = old == new
    }

    class Holder(val binding: ItemContactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        return Holder(ItemContactBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val binding = holder.binding
        binding.name.text = row.contact.name
        binding.code.text = row.contact.code
        binding.presenceDot.setImageResource(
            if (row.online) R.drawable.dot_online else R.drawable.dot_offline
        )
        binding.presenceLabel.setText(
            if (row.online) R.string.online else R.string.offline
        )
        binding.root.setOnClickListener { onCall(row.contact) }
        binding.root.setOnLongClickListener {
            onRemove(row.contact)
            true
        }
    }
}
