package com.wisp.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wisp.app.databinding.ItemDiscoveredBinding

/** People online right now who aren't saved yet, as a row of avatars — tap to add. */
class DirectoryAdapter(
    private val onAdd: (Contact) -> Unit
) : ListAdapter<Contact, DirectoryAdapter.Holder>(Diff) {

    object Diff : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(old: Contact, new: Contact) = old.code == new.code
        override fun areContentsTheSame(old: Contact, new: Contact) = old == new
    }

    class Holder(val binding: ItemDiscoveredBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemDiscoveredBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val person = getItem(position)
        Avatars.bind(holder.binding.avatar, person.name, person.code)
        holder.binding.name.text = person.name
        holder.binding.root.contentDescription =
            "${holder.binding.root.context.getString(R.string.add)} ${person.name}"
        holder.binding.root.setOnClickListener { onAdd(person) }
    }
}
