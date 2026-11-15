package com.wisp.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wisp.app.databinding.ItemContactBinding

data class ContactRow(val contact: Contact, val online: Boolean)

/**
 * Contact rows: tapping the camera calls, tapping the row opens their card.
 * Calling is never one stray tap on a big target away.
 */
class ContactAdapter(
    private val onCall: (Contact) -> Unit,
    private val onOpen: (Contact) -> Unit
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
        val context = binding.root.context
        Avatars.bind(binding.avatar, row.contact.name, row.contact.code)
        binding.name.text = row.contact.name
        binding.presenceDot.visibility = if (row.online) View.VISIBLE else View.GONE
        binding.presenceLabel.setText(if (row.online) R.string.active_now else R.string.offline)
        binding.presenceLabel.setTextColor(
            ContextCompat.getColor(context, if (row.online) R.color.wisp_online else R.color.wisp_text_dim)
        )
        binding.callButton.contentDescription = context.getString(R.string.call_name, row.contact.name)
        binding.callButton.setOnClickListener { onCall(row.contact) }
        binding.root.setOnClickListener { onOpen(row.contact) }
    }
}
