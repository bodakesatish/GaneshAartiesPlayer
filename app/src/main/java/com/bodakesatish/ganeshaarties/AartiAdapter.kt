package com.bodakesatish.ganeshaarties
// In AartiAdapter.kt

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
// Ensure these imports are correct for your project structure
import com.bodakesatish.ganeshaarties.databinding.ListItemAartiBinding
import com.google.android.material.color.MaterialColors


interface AartiItemListener {
    fun onAartiToggled(aarti: AartiItem, isChecked: Boolean)
    fun onStartDrag(viewHolder: RecyclerView.ViewHolder)
}

class AartiAdapter(private val listener: AartiItemListener) :
    ListAdapter<AartiItem, AartiAdapter.AartiViewHolder>(AartiDiffCallback()) {

    private var interactionsEnabled = true // Default to enabled
    private var currentlyPlayingItemId: Int? = null // To store the ID of the playing item
    private var isActuallyPlaying: Boolean = false // To know if playback is active


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

    // New method to update playing item info
    @SuppressLint("NotifyDataSetChanged")
    fun setPlayingState(playingItemId: Int?, isPlaying: Boolean) {
        val changed = (currentlyPlayingItemId != playingItemId) || (this.isActuallyPlaying != isPlaying)
        currentlyPlayingItemId = playingItemId
        this.isActuallyPlaying = isPlaying
        if (changed) {
            // If the playing item or playing state changes, we need to refresh items
            // to apply/remove the playing highlight.
            notifyDataSetChanged() // This is simple; for optimization, diffing could be used
            // or only rebind affected items.
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AartiViewHolder {
        val binding = ListItemAartiBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AartiViewHolder(binding)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: AartiViewHolder, position: Int) {
        val aarti = getItem(position)
        // Pass aarti, interactionsEnabled, and whether this item is the one currently playing
        holder.bind(
            aarti,
            interactionsEnabled,
            isActuallyPlaying && aarti.id == currentlyPlayingItemId // isCurrentlyPlaying
        )

        holder.binding.checkboxAarti.isChecked = aarti.isChecked
        holder.binding.checkboxAarti.isEnabled = false // Keep checkbox non-interactive directly

        if (interactionsEnabled) {
            holder.itemView.setOnClickListener {
                val newCheckedState = !aarti.isChecked
                listener.onAartiToggled(aarti, newCheckedState)
            }
        } else {
            // If interactions are disabled (e.g., during playback),
            // clicking the item could potentially be used to play it if it's not the current one,
            // or pause/play if it *is* the current one. This depends on desired UX.
            // For now, let's keep it simple: no click listener if interactions are globally disabled.
            holder.itemView.setOnClickListener(null)
        }

        holder.binding.imageViewDragHandle.isEnabled = interactionsEnabled
        holder.binding.imageViewDragHandle.alpha = if (interactionsEnabled) 1.0f else 0.5f
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
        fun bind(aarti: AartiItem, isEnabledOverall: Boolean, isCurrentlyPlaying: Boolean) {
            binding.textViewAartiTitle.setText(aarti.title)
            itemView.alpha = if (isEnabledOverall || isCurrentlyPlaying) 1.0f else 0.7f // Dim non-interactive items, unless playing

            val context = itemView.context
            val typedValue = TypedValue()
            // Apply highlighting based on state
            when {
                isCurrentlyPlaying -> {
                    // Highlight for the currently playing item
                  //  binding.textViewAartiTitle.setTextColor(ContextCompat.getColor(context, R.color.playing_text_color)) // Example
//                    itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.playing_background_color)) // Example
                    binding.textViewAartiTitle.setTextColor(MaterialColors.getColor(context, R.attr.myListItemPlayingTextColor, Color.BLACK))
                    binding.root.setBackgroundColor(MaterialColors.getColor(context, R.attr.myListItemPlayingBackgroundColor, Color.LTGRAY))

                }
                aarti.isChecked -> {
                    // Highlight for selected (checked) items that are NOT currently playing
                 //   binding.textViewAartiTitle.setTextColor(ContextCompat.getColor(context, R.color.selected_text_color)) // Example
//                    itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.selected_background_color)) // Example
                    binding.textViewAartiTitle.setTextColor(MaterialColors.getColor(context, R.attr.myListItemSelectedTextColor, Color.BLACK))
                    binding.root.setBackgroundColor(MaterialColors.getColor(context, R.attr.myListItemSelectedBackgroundColor, Color.DKGRAY))

                }
                else -> {
                    // Default appearance for items that are neither playing nor selected
                  //  binding.textViewAartiTitle.setTextColor(ContextCompat.getColor(context, R.color.default_text_color)) // Or your theme's default
//                    itemView.setBackgroundColor(Color.TRANSPARENT) // Or your default item background
                    binding.textViewAartiTitle.setTextColor(MaterialColors.getColor(context, R.attr.myListItemDefaultTextColor, Color.GRAY))
                    binding.root.setBackgroundColor(MaterialColors.getColor(context, R.attr.myListItemDefaultBackgroundColor, Color.WHITE))
                }
            }
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



// Inside AartiAdapter.kt, AartiViewHolder's bind method
//val context = itemView.context
//if (isCurrentlyPlaying) {
//    // For light/dark specific colors without theme attributes:
//    val playingTextColor = ContextCompat.getColor(context, if (isSystemInDarkTheme()) R.color.list_item_playing_text_dark else R.color.list_item_playing_text_light)
//    val playingBgColor = ContextCompat.getColor(context, if (isSystemInDarkTheme()) R.color.list_item_playing_background_dark else R.color.list_item_playing_background_light)
//    binding.textViewAartiTitle.setTextColor(playingTextColor)
//    binding.root.setBackgroundColor(playingBgColor)
//} else if (aarti.isChecked) {
//    val selectedTextColor = ContextCompat.getColor(context, if (isSystemInDarkTheme()) R.color.list_item_selected_text_dark else R.color.list_item_selected_text_light)
//    val selectedBgColor = ContextCompat.getColor(context, if (isSystemInDarkTheme()) R.color.list_item_selected_background_dark else R.color.list_item_selected_background_light)
//    binding.textViewAartiTitle.setTextColor(selectedTextColor)
//    binding.root.setBackgroundColor(selectedBgColor)
//} else {
//    val defaultTextColor = ContextCompat.getColor(context, if (isSystemInDarkTheme()) R.color.list_item_default_text_dark else R.color.list_item_default_text_light)
//    val defaultBgColor = ContextCompat.getColor(context, if (isSystemInDarkTheme()) R.color.list_item_default_background_dark else R.color.list_item_default_background_light)
//    binding.textViewAartiTitle.setTextColor(defaultTextColor)
//    binding.root.setBackgroundColor(defaultBgColor)
//}
//
//// Helper to check current theme (put in a utility file or base activity if used often)
//private fun isSystemInDarkTheme(): Boolean {
//    // You might need to pass context or get it from itemView
//    val uiModeManager = itemView.context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
//    return uiModeManager.nightMode == UiModeManager.MODE_NIGHT_YES
//}
