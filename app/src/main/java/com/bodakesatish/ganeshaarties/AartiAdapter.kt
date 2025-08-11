package com.bodakesatish.ganeshaarties
// In AartiAdapter.kt

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
// Ensure these imports are correct for your project structure
import com.bodakesatish.ganeshaarties.databinding.ListItemAartiBinding


interface AartiItemListener {
    fun onAartiToggled(aarti: AartiItem, isChecked: Boolean)
    fun onStartDrag(viewHolder: RecyclerView.ViewHolder)
}

class AartiAdapter(private val listener: AartiItemListener) :
    ListAdapter<AartiItem, AartiAdapter.AartiViewHolder>(AartiDiffCallback()) {

    private var interactionsEnabled = true // Default to enabled

    @SuppressLint("NotifyDataSetChanged") // See note below
    fun setInteractionsEnabled(enabled: Boolean) {
        if (interactionsEnabled != enabled) {
            interactionsEnabled = enabled
            // We need to re-bind all visible items to update their enabled state
            notifyDataSetChanged() // This is a bit heavy.
            // A more granular update would be better if performance is critical
            // for very large lists, but for typical list sizes, this is often acceptable.
            // Alternatively, you could iterate through visible view holders
            // and update them directly if you have access to the RecyclerView instance here.
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AartiViewHolder {
        val binding = ListItemAartiBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AartiViewHolder(binding)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: AartiViewHolder, position: Int) {
        val aarti = getItem(position)
        holder.bind(aarti, interactionsEnabled) // Pass the enabled state

        // Update checkbox state visually (it won't have its own listener anymore)
        holder.binding.checkboxAarti.isChecked = aarti.isChecked
        // Disable the checkbox itself from direct interaction if the whole item is clickable
        holder.binding.checkboxAarti.isEnabled = false // Or you could hide it: holder.binding.checkboxAarti.visibility = View.GONE

        // Item click listener to toggle the checked state
        if (interactionsEnabled) {
            holder.itemView.setOnClickListener {
                // When the item is clicked, notify the listener with the *new* toggled state
                val newCheckedState = !aarti.isChecked
                listener.onAartiToggled(aarti, newCheckedState)
            }
        } else {
            holder.itemView.setOnClickListener(null)
        }


        // Drag handle interaction
        holder.binding.imageViewDragHandle.isEnabled = interactionsEnabled // Visual cue
        holder.binding.imageViewDragHandle.alpha = if (interactionsEnabled) 1.0f else 0.5f // Visual cue
        if (interactionsEnabled) {
            holder.binding.imageViewDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    listener.onStartDrag(holder)
                }
                false
            }
        } else {
            holder.binding.imageViewDragHandle.setOnTouchListener(null)
        }
    }

    inner class AartiViewHolder(val binding: ListItemAartiBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(aarti: AartiItem, isEnabled: Boolean) {
            binding.textViewAartiTitle.text = aarti.title
            // You can also adjust the appearance of the whole item if needed
            itemView.alpha = if (isEnabled) 1.0f else 0.7f // Example: Dim the item
        }
    }
}

// AartiDiffCallback remains the same
class AartiDiffCallback : DiffUtil.ItemCallback<AartiItem>() {
    override fun areItemsTheSame(oldItem: AartiItem, newItem: AartiItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: AartiItem, newItem: AartiItem): Boolean {
        return oldItem == newItem
    }
}
